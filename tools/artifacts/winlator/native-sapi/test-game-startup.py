"""Headless startup observations of unchanged game binaries; no UI input."""
import os
from pathlib import Path
import subprocess
import sys
import time

HERE = Path(__file__).resolve().parent
PREFIX = Path('/home/willb/winlator-sapi-diagnostic')
ENV = dict(os.environ, WINEPREFIX=str(PREFIX), WINEDEBUG='err+all', WINEDLLOVERRIDES='sapi=n')
WINE = '/usr/bin/wine64-stable'
BRIDGE = PREFIX / 'drive_c/NVDA-Bridge'
game = Path(sys.argv[1]).resolve()
super_liam = game.name.lower() == 'super liam.exe'
if super_liam:
    ENV.pop('WINEDLLOVERRIDES', None)
    subprocess.run([WINE, 'reg', 'add', r'HKCU\Software\Wine\DllOverrides',
        '/v', 'sapi', '/d', 'native', '/f'], env=ENV, timeout=30, check=True,
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
ready = BRIDGE / 'nvda-rpc-ready'
ready.unlink(missing_ok=True)
service_log = BRIDGE / 'nvda-rpc-service.log'
service_log.unlink(missing_ok=True)
launch_log = BRIDGE / 'native-launch.log'
launch_log.unlink(missing_ok=True)
with open(HERE / f'{game.stem}-startup.log', 'w') as output:
    helper = subprocess.Popen([WINE, str(HERE / 'runtime/rpc-test/nvda_sapi_rpc_test64.exe')],
        env=ENV, stdout=output, stderr=output)
    try:
        for _ in range(300):
            if ready.exists(): break
            if helper.poll() is not None: raise RuntimeError('helper exited')
            time.sleep(.1)
        else: raise RuntimeError('helper timeout')
        wrapper = HERE / 'payload/nvda-launch.exe'
        windows_path = lambda path: 'Z:' + str(path).replace('/', '\\')
        launcher = subprocess.Popen([WINE, 'explorer', '/desktop=nogui,1024x768',
            windows_path(wrapper), *(['--builtin-game-sapi'] if super_liam else []),
            windows_path(game)], cwd=game.parent, env=ENV, stdout=output, stderr=output)
        time.sleep(25)
        print(f'{game.name}: desktop exit={launcher.poll()}', flush=True)
        launch = launch_log.read_text() if launch_log.exists() else 'No launcher log'
        lines = service_log.read_text().splitlines()
        if super_liam:
            assert launcher.poll() is None, launch
            assert 'normal prelaunch native helper; registry status=0' in launch, launch
            assert 'class=SDL_app' in launch and 'visible=1' in launch, launch
            assert any('speak length=665 status=0' in line for line in lines), lines
            assert 'class=wxWindowClassNR' not in launch, launch
            assert 'class=WineConsoleClass' not in launch, launch
            print('PASS: prelaunch native NVDA helper plus per-game built-in SAPI preserves visible focused SDL window and original NVDA speech.')
        print('\n'.join(line for line in launch.splitlines() if 'SAPI=' in line or 'class=SDL_app' in line))
        print('\n'.join(line for line in lines if 'speak' in line))
    finally:
        subprocess.run(['/usr/lib/x86_64-linux-gnu/wine/wineserver', '-k'], env=ENV, timeout=10, check=True)
        helper.wait(timeout=10)
