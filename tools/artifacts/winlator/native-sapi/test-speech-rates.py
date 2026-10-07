"""Verify startup settings and real native SAPI synthesis in an isolated prefix."""
from pathlib import Path
import os
import subprocess
import wave

HERE = Path(__file__).resolve().parent
PREFIX = Path('/home/willb/winlator-sapi-diagnostic')
ENV = dict(os.environ, WINEPREFIX=str(PREFIX), WINEDEBUG='-all', WINEDLLOVERRIDES='sapi=n')
WINE = '/usr/bin/wine64-stable'
SETTINGS = PREFIX / 'drive_c/WinlatorNativeSapi/speech-settings.ini'

def run(*args):
    result = subprocess.run([WINE, *args], env=ENV, capture_output=True, text=True, timeout=60)
    if result.returncode:
        raise RuntimeError(result.stdout + result.stderr)
    return result.stdout

durations = []
try:
    for regular, nvda in [(-5, 7), (5, -7), (99, -99)]:
        SETTINGS.write_text(f'[speech]\nregularRate={regular}\nnvdaRate={nvda}\n')
        run(str(HERE / 'payload/configure-native-sapi.exe'), '--select-voice')
        output = run(str(HERE / 'sapi-diagnostic64.exe'), '--render')
        expected = max(-10, min(10, regular))
        assert f'default rate={expected}' in output, output
        assert 'render Speak=00000000' in output, output
        registry = run('reg.exe', 'query', r'HKCU\Software\Winlator\Speech', '/v', 'NvdaRate')
        assert hex(max(-10, min(10, nvda)) & 0xffffffff) in registry.lower(), registry
        # Diagnostic writes the actual voice output, rather than timing API submission.
        wav_path = PREFIX / 'drive_c/NVDA-Bridge/isolated-rhvoice.wav'
        with wave.open(str(wav_path)) as wav:
            duration = wav.getnframes() / wav.getframerate()
        durations.append(duration)
        print(f'regular={expected} nvda={max(-10, min(10, nvda))} PCM seconds={duration:.2f}', flush=True)
    assert durations[0] > durations[1] * 1.3, durations
    print('PASS: independent rates, clamping, native default rate and actual synthesis speed.')
finally:
    SETTINGS.write_text('[speech]\nregularRate=0\nnvdaRate=0\n')
    run(str(HERE / 'payload/configure-native-sapi.exe'), '--select-voice')
