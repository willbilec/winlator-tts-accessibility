/*
 * protontts unixlib.
 *
 * Copyright 2023 Shaun Ren for CodeWeavers
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301, USA
 */

#define _GNU_SOURCE

#if 0
#pragma makedep unix
#endif

#include <stdarg.h>
#include <stdint.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <pthread.h>
#include <fcntl.h>
#include <linux/limits.h>
#include <sys/stat.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <netinet/in.h>
#include <arpa/inet.h>

#include <espeak-ng/speak_lib.h>

#include "ntstatus.h"
#define WIN32_NO_STATUS
#include "winternl.h"

#include "wine/debug.h"

#include "protontts_private.h"

WINE_DEFAULT_DEBUG_CHANNEL(protontts);

static inline void touch_tts_used_tag(void)
{
    const char *e;


    if ((e = getenv("STEAM_COMPAT_TRANSCODED_MEDIA_PATH")))
    {
        char buffer[PATH_MAX];
        int fd;

        snprintf(buffer, sizeof(buffer), "%s/tts-used", e);

        fd = open(buffer, O_CREAT, S_IRUSR | S_IWUSR | S_IRGRP | S_IWGRP | S_IROTH | S_IWOTH);
        if (fd == -1)
        {
            fprintf(stderr, "protontts: failed to open/create %s/tts-used\n", e);
            return;
        }

        futimens(fd, NULL);

        close(fd);
    }
    else
    {
        /* The tag is optional outside Steam/Proton. */
    }
}

struct tts_audio_buf
{
    pthread_mutex_t mutex;
    pthread_cond_t empty_cond;
    void *buf;
    UINT32 size;
    size_t mapped_size;
    bool done;
};

struct tts_voice
{
    INT64 speaker_id;
    float length_scale;
    int sample_rate;
    atomic_bool abort_requested;
    atomic_bool android_speech_active;

    struct tts_audio_buf audio;
};

static pthread_mutex_t espeak_mutex = PTHREAD_MUTEX_INITIALIZER;
static struct tts_voice *active_voice;
static int espeak_sample_rate;
static bool espeak_initialized;

static bool android_tts_request(char command, const char *text, UINT32 length)
{
    int fd, result;
    struct sockaddr_in address = {0};
    struct timeval timeout = {.tv_sec = 2, .tv_usec = 0};
    unsigned char header[5], reply;
    UINT32 network_length = htonl(length);
    size_t offset;

    if (length > 65536 || (length && !text)) return false;
    fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) return false;
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
    setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout));
    address.sin_family = AF_INET;
    address.sin_port = htons(51234);
    address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    if (connect(fd, (struct sockaddr *)&address, sizeof(address)) < 0) goto failed;
    header[0] = (unsigned char)command;
    memcpy(header + 1, &network_length, sizeof(network_length));
    for (offset = 0; offset < sizeof(header); offset += result)
    {
        result = send(fd, header + offset, sizeof(header) - offset, 0);
        if (result <= 0) goto failed;
    }
    for (offset = 0; offset < length; offset += result)
    {
        result = send(fd, text + offset, length - offset, 0);
        if (result <= 0) goto failed;
    }
    result = recv(fd, &reply, 1, 0);
    close(fd);
    return result == 1 && reply == 0;
failed:
    close(fd);
    return false;
}

#ifdef TTS_TRACE
static FILE *trace_file;
static unsigned callback_count;
static void trace_tts(const char *format, ...)
{
    va_list args;
    const char *prefix;
    if (!trace_file)
    {
        char path[PATH_MAX];
        prefix = getenv("WINEPREFIX");
        if (prefix && snprintf(path, sizeof(path), "%s/drive_c/NVDA-Bridge/espeak-native.log", prefix) < sizeof(path))
            trace_file = fopen(path, "a");
    }
    if (!trace_file) return;
    va_start(args, format);
    vfprintf(trace_file, format, args);
    va_end(args);
    fflush(trace_file);
}
#else
#define trace_tts(...) ((void)0)
#endif

static inline struct tts_voice *get_voice(tts_voice_t voice)
{
    return (struct tts_voice *)(ULONG_PTR)voice;
}

static NTSTATUS process_attach(void *args)
{
    touch_tts_used_tag();
    return STATUS_SUCCESS;
}

static NTSTATUS tts_create(void *args)
{
    /* eSpeak-NG is initialized once and shared by the voice instances. */
    *(tts_t *)args = 1;
    return STATUS_SUCCESS;
}

static NTSTATUS tts_destroy(void *args)
{
    return STATUS_SUCCESS;
}

static int espeak_audio_callback(short *data, int samples, espeak_EVENT *events)
{
    struct tts_voice *voice = active_voice;
    size_t size;

    if (!voice || !data || samples <= 0)
    {
        trace_tts("callback end samples=%d voice=%p\n", samples, voice);
        return 0;
    }
    if (atomic_load(&voice->abort_requested))
    {
        trace_tts("callback aborted\n");
        return 1;
    }
#ifdef TTS_TRACE
    if (++callback_count <= 3 || callback_count % 100 == 0)
        trace_tts("callback count=%u samples=%d\n", callback_count, samples);
#endif

    size = sizeof(*data) * samples;
    pthread_mutex_lock(&voice->audio.mutex);
    while (voice->audio.buf)
    {
        struct timespec deadline;

        if (atomic_load(&voice->abort_requested))
        {
            pthread_mutex_unlock(&voice->audio.mutex);
            return 1;
        }
        clock_gettime(CLOCK_REALTIME, &deadline);
        deadline.tv_nsec += 10000000;
        if (deadline.tv_nsec >= 1000000000)
        {
            deadline.tv_sec++;
            deadline.tv_nsec -= 1000000000;
        }
        pthread_cond_timedwait(&voice->audio.empty_cond, &voice->audio.mutex, &deadline);
    }

#ifdef __x86_64__
    /* New-WoW64 passes this pointer to a 32-bit SAPI DLL. A regular malloc()
     * may be above 4 GiB and PtrToUlong() in the thunk would truncate it. */
    voice->audio.buf = mmap(NULL, size, PROT_READ | PROT_WRITE,
                            MAP_PRIVATE | MAP_ANONYMOUS | MAP_32BIT, -1, 0);
    if (voice->audio.buf == MAP_FAILED)
        voice->audio.buf = NULL;
    else if ((uintptr_t)voice->audio.buf + size > UINT32_MAX)
    {
        munmap(voice->audio.buf, size);
        voice->audio.buf = NULL;
    }
    if (voice->audio.buf) voice->audio.mapped_size = size;
#else
    voice->audio.buf = malloc(size);
#endif
    if (voice->audio.buf)
    {
        memcpy(voice->audio.buf, data, size);
        voice->audio.size = size;
    }
    pthread_mutex_unlock(&voice->audio.mutex);

    return voice->audio.buf ? 0 : 1;
}

static NTSTATUS tts_voice_load(void *args)
{
    struct tts_voice_load_params *params = args;
    const char *voice_files_dir = getenv("PROTON_VOICE_FILES");
    char *espeak_data_path;
    struct tts_voice *voice;


    if (!voice_files_dir)
    {
        fprintf(stderr, "protontts: PROTON_VOICE_FILES is not set\n");
        return STATUS_UNSUCCESSFUL;
    }
    if (!(espeak_data_path = malloc(strlen(voice_files_dir) + 1)))
        return STATUS_NO_MEMORY;
    strcpy(espeak_data_path, voice_files_dir);

    if (!(voice = calloc(1, sizeof(*voice))))
    {
        free(espeak_data_path);
        return STATUS_NO_MEMORY;
    }

    voice->speaker_id = params->speaker_id;
    atomic_init(&voice->abort_requested, false);
    atomic_init(&voice->android_speech_active, false);
    voice->length_scale = 1.0f;
    pthread_mutex_lock(&espeak_mutex);
    if (!espeak_initialized)
    {
        /* Wine consumes audio concurrently while eSpeak produces it. */
        espeak_sample_rate = espeak_Initialize(AUDIO_OUTPUT_RETRIEVAL, 0, espeak_data_path, 0);
        if (espeak_sample_rate > 0)
        {
            espeak_SetSynthCallback(espeak_audio_callback);
            espeak_initialized = true;
        }
    }
    if (espeak_initialized) espeak_SetVoiceByName("en");
    pthread_mutex_unlock(&espeak_mutex);
    free(espeak_data_path);
    if (!espeak_initialized)
    {
        free(voice);
        return STATUS_UNSUCCESSFUL;
    }
    voice->sample_rate = espeak_sample_rate;

    pthread_mutex_init(&voice->audio.mutex, NULL);
    pthread_cond_init(&voice->audio.empty_cond, NULL);

    params->voice = (tts_voice_t)(ULONG_PTR)voice;

    /* Voice is ready. */
    return STATUS_SUCCESS;
}

static NTSTATUS tts_voice_destroy(void *args)
{
    struct tts_voice *voice = get_voice(*(tts_voice_t *)args);

    if (!voice) return STATUS_SUCCESS;

    /* Serialize destruction with eSpeak's process-global synthesis state. */
    pthread_mutex_lock(&espeak_mutex);
    pthread_mutex_lock(&voice->audio.mutex);
    if (voice->audio.buf)
    {
        if (voice->audio.mapped_size)
            munmap(voice->audio.buf, voice->audio.mapped_size);
        else
            free(voice->audio.buf);
        voice->audio.buf = NULL;
        voice->audio.size = 0;
        voice->audio.mapped_size = 0;
    }
    pthread_cond_broadcast(&voice->audio.empty_cond);
    pthread_mutex_unlock(&voice->audio.mutex);
    pthread_mutex_destroy(&voice->audio.mutex);
    pthread_cond_destroy(&voice->audio.empty_cond);
    free(voice);
    pthread_mutex_unlock(&espeak_mutex);

    return STATUS_SUCCESS;
}

static NTSTATUS tts_voice_get_config(void *args)
{
    struct tts_voice_get_config_params *params = args;
    struct tts_voice *voice = get_voice(params->voice);
    params->length_scale = voice->length_scale;
    params->sample_rate  = voice->sample_rate;
    params->sample_width = sizeof(int16_t);
    params->channels     = 1;

    return STATUS_SUCCESS;
}

static NTSTATUS tts_voice_set_config(void *args)
{
    struct tts_voice_set_config_params *params = args;
    struct tts_voice *voice = get_voice(params->voice);

    voice->length_scale = params->length_scale ? *params->length_scale : 1.0f;
    atomic_store(&voice->abort_requested, false);

    return STATUS_SUCCESS;
}

static NTSTATUS tts_voice_synthesize(void *args)
{
    struct tts_voice_synthesize_params *params = args;
    struct tts_voice *voice = get_voice(params->voice);
    int result;

    (void)args;

    /* Android handles playback; SAPI needs only an end-of-audio signal.
     * Retain eSpeak as a fallback if the Android endpoint is unavailable. */
    if (params->size > 1 && android_tts_request('S', params->text, params->size - 1))
    {
        atomic_store(&voice->android_speech_active, true);
        pthread_mutex_lock(&voice->audio.mutex);
        voice->audio.done = true;
        pthread_cond_broadcast(&voice->audio.empty_cond);
        pthread_mutex_unlock(&voice->audio.mutex);
        return STATUS_SUCCESS;
    }
    atomic_store(&voice->android_speech_active, false);

    pthread_mutex_lock(&voice->audio.mutex);
    voice->audio.done = false;
    pthread_mutex_unlock(&voice->audio.mutex);
    pthread_mutex_lock(&espeak_mutex);
    active_voice = voice;
    trace_tts("synth begin bytes=%u text='%.*s'\n", params->size,
              params->size < 120 ? params->size : 120, params->text);
    espeak_SetParameter(espeakRATE, (int)(175.0f / voice->length_scale), 0);
    result = espeak_Synth(params->text, params->size - 1, 0, POS_CHARACTER, 0,
                          espeakCHARS_UTF8 | espeakENDPAUSE, NULL, NULL);
    trace_tts("synth submitted result=%d\n", result);
    if (result == EE_OK) result = espeak_Synchronize();
    trace_tts("synth synchronized result=%d\n", result);
    active_voice = NULL;
    pthread_mutex_unlock(&espeak_mutex);
    pthread_mutex_lock(&voice->audio.mutex);
    voice->audio.done = true;
    trace_tts("audio done\n");
    pthread_cond_broadcast(&voice->audio.empty_cond);
    pthread_mutex_unlock(&voice->audio.mutex);

    return result == EE_OK ? STATUS_SUCCESS : STATUS_UNSUCCESSFUL;
}

static NTSTATUS tts_voice_audio_lock(void *args)
{
    struct tts_voice_audio_lock_params *params = args;
    struct tts_voice *voice = get_voice(params->voice);

    (void)args;

    pthread_mutex_lock(&voice->audio.mutex);

    params->buf  = voice->audio.buf;
    params->size = voice->audio.size;
    params->done = voice->audio.done;
    if (params->buf || params->done)
        trace_tts("audio lock buf=%p size=%zu done=%d\n", params->buf,
                  params->size, params->done);

    return STATUS_SUCCESS;
}

static NTSTATUS tts_voice_audio_release(void *args)
{
    struct tts_voice *voice = get_voice(*(tts_voice_t *)args);
    (void)args;

    if (voice->audio.buf)
    {
        if (voice->audio.mapped_size)
            munmap(voice->audio.buf, voice->audio.mapped_size);
        else
            free(voice->audio.buf);
    }
    voice->audio.buf = NULL;
    voice->audio.size = 0;
    voice->audio.mapped_size = 0;
    trace_tts("audio release\n");

    pthread_cond_signal(&voice->audio.empty_cond);
    pthread_mutex_unlock(&voice->audio.mutex);

    return STATUS_SUCCESS;
}

static NTSTATUS tts_voice_abort(void *args)
{
    struct tts_voice *voice = get_voice(*(tts_voice_t *)args);
    atomic_store(&voice->abort_requested, true);
    if (atomic_exchange(&voice->android_speech_active, false))
        android_tts_request('C', NULL, 0);
    pthread_mutex_lock(&voice->audio.mutex);
    pthread_cond_broadcast(&voice->audio.empty_cond);
    pthread_mutex_unlock(&voice->audio.mutex);
    return STATUS_SUCCESS;
}

const unixlib_entry_t __wine_unix_call_funcs[] =
{
    process_attach,

    tts_create,
    tts_destroy,

    tts_voice_load,
    tts_voice_destroy,
    tts_voice_get_config,
    tts_voice_set_config,
    tts_voice_synthesize,

    tts_voice_audio_lock,
    tts_voice_audio_release,
    tts_voice_abort,
};

#ifdef _WIN64

typedef ULONG PTR32;

static NTSTATUS wow64_tts_voice_load(void *args)
{
    struct
    {
        tts_t tts;
        PTR32 model_path;
        INT64 speaker_id;
        tts_voice_t voice;
    } *params32 = args;
    struct tts_voice_load_params params =
    {
        .tts = params32->tts,
        .model_path = ULongToPtr(params32->model_path),
        .speaker_id = params32->speaker_id,
    };
    NTSTATUS ret;

    ret = tts_voice_load(&params);
    params32->voice = params.voice;
    return ret;
}

static NTSTATUS wow64_tts_voice_set_config(void *args)
{
    struct
    {
        tts_voice_t voice;
        PTR32 length_scale;
    } *params32 = args;
    struct tts_voice_set_config_params params =
    {
        .voice = params32->voice,
        .length_scale = ULongToPtr(params32->length_scale),
    };

    return tts_voice_set_config(&params);
}


static NTSTATUS wow64_tts_voice_synthesize(void *args)
{
    struct
    {
        tts_voice_t voice;
        PTR32 text;
        UINT32 size;
        PTR32 abort_event;
    } *params32 = args;
    struct tts_voice_synthesize_params params =
    {
        .voice = params32->voice,
        .text = ULongToPtr(params32->text),
        .size = params32->size,
        .abort_event = ULongToHandle(params32->abort_event),
    };

    return tts_voice_synthesize(&params);
}

static NTSTATUS wow64_tts_voice_audio_lock(void *args)
{
    struct
    {
        tts_voice_t voice;
        PTR32 buf;
        UINT32 size;
        UINT8 done;
    } *params32 = args;
    struct tts_voice_audio_lock_params params =
    {
        .voice = params32->voice,
    };
    NTSTATUS ret;

    ret = tts_voice_audio_lock(&params);
    params32->buf = PtrToUlong(params.buf);
    params32->size = params.size;
    params32->done = params.done;
    return ret;
}

const unixlib_entry_t __wine_unix_call_wow64_funcs[] =
{
    process_attach,

    tts_create,
    tts_destroy,

    wow64_tts_voice_load,
    tts_voice_destroy,
    tts_voice_get_config,
    wow64_tts_voice_set_config,
    wow64_tts_voice_synthesize,

    wow64_tts_voice_audio_lock,
    tts_voice_audio_release,
    tts_voice_abort,
};

#endif /* _WIN64 */
