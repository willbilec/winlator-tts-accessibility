#define COBJMACROS
#include <windows.h>
#include <objbase.h>
#include <sapi.h>
#include <stdio.h>
#include <mmsystem.h>

int main(void) {
    ISpVoice *voice = NULL;
    SPVOICESTATUS status = {0};
    ISpObjectToken *token = NULL;
    WCHAR *token_id = NULL;
    USHORT volume = 0;
    DWORD started;
    HRESULT hr;
    FILE *log = fopen(sizeof(void *) == 8 ?
        "C:\\NVDA-Bridge\\audio-probe64.log" : "C:\\NVDA-Bridge\\audio-probe32.log", "w");
    if (!log) return 1;
    fprintf(log, "desktop=%p\n", GetDesktopWindow()); fflush(log);
    fprintf(log, "initialWaveDevices=%u\n", waveOutGetNumDevs()); fflush(log);
    hr = CoInitializeEx(NULL, COINIT_MULTITHREADED);
    fprintf(log, "COM=%08lx\n", (unsigned long)hr); fflush(log);
    if (SUCCEEDED(hr)) hr = CoCreateInstance(&CLSID_SpVoice, NULL,
        CLSCTX_INPROC_SERVER, &IID_ISpVoice, (void **)&voice);
    fprintf(log, "create=%08lx\n", (unsigned long)hr); fflush(log);
    if (voice) {
        hr = ISpVoice_GetVoice(voice, &token);
        fprintf(log, "voice=%08lx\n", (unsigned long)hr);
        if (token && SUCCEEDED(ISpObjectToken_GetId(token, &token_id))) {
            fprintf(log, "token=%ls\n", token_id);
            CoTaskMemFree(token_id);
            ISpObjectToken_Release(token);
        }
        hr = ISpVoice_GetVolume(voice, &volume);
        fprintf(log, "volume=%u hr=%08lx\n", volume, (unsigned long)hr);
        hr = ISpVoice_SetOutput(voice, NULL, FALSE);
        fprintf(log, "defaultOutput=%08lx\n", (unsigned long)hr); fflush(log);
        {
            ISpStreamFormat *output = NULL;
            ISpAudio *audio = NULL;
            SPAUDIOBUFFERINFO info = {0};
            hr = ISpVoice_GetOutputStream(voice, &output);
            if (SUCCEEDED(hr)) hr = ISpStreamFormat_QueryInterface(output, &IID_ISpAudio, (void **)&audio);
            if (SUCCEEDED(hr)) hr = ISpAudio_GetBufferInfo(audio, &info);
            fprintf(log, "audioBuffer=%08lx notify=%lu size=%lu bias=%lu\n", (unsigned long)hr,
                info.ulMsMinNotification, info.ulMsBufferSize, info.ulMsEventBias); fflush(log);
            if (audio) ISpAudio_Release(audio);
            if (output) ISpStreamFormat_Release(output);
        }
        hr = ISpVoice_Speak(voice, sizeof(void *) == 8 ?
            L"64 bit native speech playback test." : L"32 bit native speech playback test.",
            SPF_ASYNC | SPF_IS_NOT_XML, NULL);
        fprintf(log, "Speak=%08lx\n", (unsigned long)hr); fflush(log);
        started = GetTickCount();
        hr = ISpVoice_WaitUntilDone(voice, 10000);
        fprintf(log, "wait=%08lx duration=%lu\n", (unsigned long)hr,
            GetTickCount() - started); fflush(log);
        hr = ISpVoice_GetStatus(voice, &status, NULL);
        fprintf(log, "status=%08lx state=%lu last=%08lx\n", (unsigned long)hr,
            status.dwRunningState, (unsigned long)status.hrLastResult); fflush(log);
        {
            ISpStream *stream = NULL;
            GUID format_id;
            WAVEFORMATEX format = {WAVE_FORMAT_PCM, 1, 24000, 48000, 2, 16, 0};
            hr = CLSIDFromString(L"{C31ADBAE-527F-4FF5-A230-F62BB61FF70C}", &format_id);
            if (SUCCEEDED(hr)) hr = CoCreateInstance(&CLSID_SpFileStream, NULL,
                CLSCTX_INPROC_SERVER, &IID_ISpStream, (void **)&stream);
            if (SUCCEEDED(hr)) hr = ISpStream_BindToFile(stream,
                L"C:\\NVDA-Bridge\\audio-probe.wav", SPFM_CREATE_ALWAYS, &format_id, &format, 0);
            if (SUCCEEDED(hr)) hr = ISpVoice_SetOutput(voice, (IUnknown *)stream, FALSE);
            fprintf(log, "fileOutput=%08lx\n", (unsigned long)hr); fflush(log);
            if (SUCCEEDED(hr)) hr = ISpVoice_Speak(voice,
                L"Native voice rendering diagnostic.", SPF_ASYNC | SPF_IS_NOT_XML, NULL);
            if (SUCCEEDED(hr)) hr = ISpVoice_WaitUntilDone(voice, 10000);
            fprintf(log, "fileSpeech=%08lx\n", (unsigned long)hr); fflush(log);
            if (stream) ISpStream_Close(stream);
            fprintf(log, "waveDevices=%u\n", waveOutGetNumDevs()); fflush(log);
            {
                BOOL playing = PlaySoundW(L"C:\\NVDA-Bridge\\audio-probe.wav", NULL,
                    SND_FILENAME | SND_ASYNC | SND_NODEFAULT);
                fprintf(log, "PlaySound=%u error=%lu\n", playing, GetLastError()); fflush(log);
                Sleep(4000);
            }
        }
    }
    fclose(log);
    /* Bound diagnostics even if a broken audio driver blocks COM teardown. */
    ExitProcess(0);
}
