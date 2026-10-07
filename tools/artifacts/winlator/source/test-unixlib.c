#define _GNU_SOURCE
#define __WINESRC__
#define WINE_UNIX_LIB

#include <dlfcn.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <pthread.h>

#include "protontts_private.h"

#define STATUS_SUCCESS 0

struct synth_args
{
    const unixlib_entry_t *funcs;
    struct tts_voice_synthesize_params params;
    NTSTATUS status;
};

static void *synthesize_thread(void *data)
{
    struct synth_args *args = data;
    args->status = args->funcs[unix_tts_voice_synthesize](&args->params);
    return NULL;
}

static void write_wav_header(FILE *file, int sample_rate, uint32_t samples)
{
    uint32_t data_size = samples * sizeof(int16_t);
    uint32_t riff_size = 36 + data_size;
    uint16_t format = 1, channels = 1, bits = 16;
    uint32_t byte_rate = sample_rate * channels * bits / 8;
    uint16_t block_align = channels * bits / 8;

    fseek(file, 0, SEEK_SET);
    fwrite("RIFF", 1, 4, file); fwrite(&riff_size, 4, 1, file); fwrite("WAVE", 1, 4, file);
    fwrite("fmt ", 1, 4, file); { uint32_t size = 16; fwrite(&size, 4, 1, file); }
    fwrite(&format, 2, 1, file); fwrite(&channels, 2, 1, file); fwrite(&sample_rate, 4, 1, file);
    fwrite(&byte_rate, 4, 1, file); fwrite(&block_align, 2, 1, file); fwrite(&bits, 2, 1, file);
    fwrite("data", 1, 4, file); fwrite(&data_size, 4, 1, file);
}

int main(int argc, char **argv)
{
    const unixlib_entry_t *funcs;
    void *module;
    tts_t tts = 0;
    tts_voice_t voice = 0;
    struct tts_voice_load_params load = {0};
    struct synth_args synth = {0};
    pthread_t thread;
    struct timespec deadline;
    char *abort_text;
    FILE *output;
    uint32_t samples = 0;
    int sample_rate;

    if (argc != 3) return fprintf(stderr, "usage: %s VOICE_DIR OUTPUT_WAV\n", argv[0]), 2;
    setenv("PROTON_VOICE_FILES", argv[1], 1);
    module = dlopen("./protontts.so", RTLD_NOW);
    if (!module) return fprintf(stderr, "dlopen: %s\n", dlerror()), 1;
    funcs = dlsym(module, "__wine_unix_call_funcs");
    if (!funcs) return fprintf(stderr, "dlsym: %s\n", dlerror()), 1;

    if (funcs[unix_tts_create](&tts) != STATUS_SUCCESS) return 1;
    load.tts = tts;
    load.model_path = "voices/en_US-lessac-low/en_US-lessac-low.onnx";
    if (funcs[unix_tts_voice_load](&load) != STATUS_SUCCESS) return fprintf(stderr, "voice load failed\n"), 1;
    voice = load.voice;
    sample_rate = 22050;
    funcs[unix_tts_voice_get_config](&(struct tts_voice_get_config_params){.voice=voice, .sample_rate=sample_rate});
    output = fopen(argv[2], "wb+");
    if (!output) return perror(argv[2]), 1;
    fwrite("RIFF", 1, 4, output); for (int i = 0; i < 40; ++i) fputc(0, output);

    synth.funcs = funcs;
    synth.params.voice = voice;
    synth.params.text = "Winlator text to speech test.";
    synth.params.size = strlen(synth.params.text) + 1;
    if (pthread_create(&thread, NULL, synthesize_thread, &synth)) return 1;

    for (;;)
    {
        struct tts_voice_audio_lock_params audio = {.voice=voice};
        funcs[unix_tts_voice_audio_lock](&audio);
        if (audio.buf && audio.size)
        {
            if ((uintptr_t)audio.buf > UINT32_MAX)
                return fprintf(stderr, "audio buffer is not 32-bit addressable: %p\n", audio.buf), 3;
            if (!samples) fprintf(stderr, "audio buffer address: %p\n", audio.buf);
            fwrite(audio.buf, 1, audio.size, output);
            samples += audio.size / sizeof(int16_t);
        }
        funcs[unix_tts_voice_audio_release](&voice);
        if (audio.done) break;
        usleep(1000);
    }
    pthread_join(thread, NULL);

    /* Regression test engine switching/cancellation while eSpeak is blocked
     * behind an audio buffer the SAPI client has not consumed yet. */
    if (!(abort_text = malloc(65536))) return 1;
    memset(abort_text, 'a', 65535);
    abort_text[65535] = '\0';
    synth.params.text = abort_text;
    synth.params.size = 65536;
    synth.status = STATUS_SUCCESS;
    if (pthread_create(&thread, NULL, synthesize_thread, &synth)) return 1;
    usleep(50000);
    funcs[unix_tts_voice_abort](&voice);
    clock_gettime(CLOCK_REALTIME, &deadline);
    deadline.tv_sec += 3;
    if (pthread_timedjoin_np(thread, NULL, &deadline))
        return fprintf(stderr, "aborted synthesis did not stop within three seconds\n"), 4;
    {
        struct tts_voice_audio_lock_params audio = {.voice=voice};
        funcs[unix_tts_voice_audio_lock](&audio);
        funcs[unix_tts_voice_audio_release](&voice);
    }
    free(abort_text);
    fprintf(stderr, "abort regression: synthesis stopped cleanly\n");

    funcs[unix_tts_voice_destroy](&voice);
    funcs[unix_tts_destroy](&tts);
    write_wav_header(output, sample_rate, samples);
    fclose(output);
    dlclose(module);
    return synth.status == STATUS_SUCCESS ? 0 : 1;
}
