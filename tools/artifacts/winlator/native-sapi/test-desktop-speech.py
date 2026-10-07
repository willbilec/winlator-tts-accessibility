"""Isolated launch-order check; no game input or device changes."""
import os
from pathlib import Path
import shutil
import subprocess
import time

HERE = Path(__file__).resolve().parent
PREFIX = Path('/home/willb/winlator-sapi-diagnostic')
ENV = dict(os.environ, WINEPREFIX=str(PREFIX), WINEDEBUG='err+all')
ENV.pop('WINEDLLOVERRIDES', None)
WINE = '/usr/bin/wine64-stable'
BRIDGE = PREFIX / 'drive_c/NVDA-Bridge'
bootstrap = PREFIX / 'drive_c/WinlatorNativeSapi/payload/configure-native-sapi.exe'
saved = bootstrap.read_bytes() if bootstrap.exists() else None
bootstrap.parent.mkdir(parents=True, exist_ok=True)
# Desktop Wine lacks the phone's WOW64 runtime. Use the same RPC/SAPI worker
# compiled for desktop x64 as the bootstrap fixture; production is unchanged.
shutil.copyfile(HERE / 'runtime/rpc-test/nvda_sapi_rpc_test_console64.exe', bootstrap)
for name in ['nvda-rpc-ready', 'nvda-rpc-service.log', 'native-launch.log']:
    (BRIDGE / name).unlink(missing_ok=True)
windows_path = lambda path: 'Z:' + str(path).replace('/', '\\')
game = HERE / 'runtime/superliam/Super Liam.exe'
try:
    subprocess.run([WINE, 'reg', 'add', r'HKCU\Software\Wine\DllOverrides',
        '/v', 'sapi', '/d', 'native', '/f'], env=ENV, timeout=30, check=True,
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    with open(HERE / 'desktop-speech-test.log', 'w') as output:
        process = subprocess.Popen([WINE, 'explorer', '/desktop=nogui,1024x768',
            windows_path(HERE / 'payload/nvda-launch.exe'), '--desktop-speech',
            windows_path(game)], cwd=game.parent, env=ENV, stdout=output, stderr=output)
        time.sleep(25)
        launch = (BRIDGE / 'native-launch.log').read_text()
        rpc = (BRIDGE / 'nvda-rpc-service.log').read_text()
        assert process.poll() is None, 'desktop launcher exited'
        assert 'speech ready on game desktop' in launch, launch
        assert 'helper SAPI=n, guest SAPI=b' in launch, launch
        assert 'desktop=nogui' in launch, launch
        assert 'class=SDL_app' in launch and 'visible=1' in launch, launch
        assert 'speak length=665 status=0' in rpc, rpc
        assert 'class=wxWindowClassNR' not in launch, launch
        assert 'class=WineConsoleClass' not in launch, launch
        print('PASS: Explorer creates nogui before speech bootstrap; Super Liam has a visible SDL window and sends NVDA speech without a detection window.')
        print('\n'.join(line for line in launch.splitlines() if
            'speech' in line or 'class=SDL_app' in line))
finally:
    subprocess.run(['/usr/lib/x86_64-linux-gnu/wine/wineserver', '-k'],
        env=ENV, timeout=10, check=True)
    if saved is None:
        bootstrap.unlink(missing_ok=True)
    else:
        bootstrap.write_bytes(saved)
