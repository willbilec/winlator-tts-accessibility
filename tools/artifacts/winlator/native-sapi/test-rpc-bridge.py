"""Run unchanged NVDA controller client through the real RPC/SAPI server."""
import os
from pathlib import Path
import subprocess
import time
import shutil
import sys
import socket

HERE = Path(__file__).resolve().parent
BUILD = HERE / "runtime" / "rpc-test"
PREFIX = Path("/home/willb/winlator-sapi-diagnostic")
ENV = dict(os.environ, WINEPREFIX=str(PREFIX), WINEDEBUG="-all", WINEDLLOVERRIDES="sapi=n")
WINE = "/usr/bin/wine64-stable"
SERVER = "/usr/lib/x86_64-linux-gnu/wine/wineserver"
BRIDGE = PREFIX / "drive_c" / "NVDA-Bridge"

if __name__ == "__main__":
    rate = int(sys.argv[1]) if len(sys.argv) > 1 else 0
    if not -10 <= rate <= 10:
        raise ValueError("rate must be between -10 and 10")
    subprocess.run([WINE, "reg.exe", "add", r"HKCU\Software\Winlator\Speech",
        "/v", "NvdaRate", "/t", "REG_DWORD", "/d", str(rate & 0xffffffff), "/f"],
        env=ENV, capture_output=True, timeout=30, check=True)
    backend_log = BRIDGE / "sapi-backend.log"
    backend_log.unlink(missing_ok=True)
    ready = BRIDGE / "nvda-rpc-ready"
    ready.unlink(missing_ok=True)
    launch_log = BRIDGE / "native-launch.log"
    launch_log.unlink(missing_ok=True)
    client_dir = BRIDGE / "rpc-test"
    client_dir.mkdir(exist_ok=True)
    for name in ["probe.exe", "nvdaControllerClient.dll"]:
        shutil.copyfile(BUILD / name, client_dir / name)
    shutil.copyfile(HERE / "payload" / "nvda-launch.exe", client_dir / "nvda-launch.exe")
    with open(HERE / "rpc-regression.log", "w") as log:
        helper = subprocess.Popen([WINE, str(BUILD / "nvda_sapi_rpc_test64.exe")],
                                  env=ENV, stdout=log, stderr=log)
        try:
            for _ in range(300):
                if ready.exists():
                    break
                if helper.poll() is not None:
                    raise RuntimeError("RPC helper exited before readiness")
                time.sleep(.1)
            else:
                raise RuntimeError("RPC helper readiness timed out")
            expected = ["ready=0", "speak=0", "cancel=0", "cancel2=0", "speak2=0"]
            for desktop in ["nogui", "shell"]:
                launch_log.unlink(missing_ok=True)
                (client_dir / "probe-result.txt").unlink(missing_ok=True)
                launcher = subprocess.Popen([WINE, "explorer", f"/desktop={desktop},1024x768",
                    r"C:\NVDA-Bridge\rpc-test\nvda-launch.exe", r"C:\NVDA-Bridge\rpc-test\probe.exe"],
                    env=ENV, stdout=log, stderr=log)
                for _ in range(350):
                    if launch_log.exists() and "guest exit=0" in launch_log.read_text():
                        break
                    if launcher.poll() is not None:
                        raise RuntimeError(f"{desktop} desktop exited before the client")
                    time.sleep(.1)
                else:
                    raise RuntimeError(f"{desktop} game startup timed out")
                output = (client_dir / "probe-result.txt").read_text()
                print(f"desktop={desktop}\n{launch_log.read_text()}{output}", flush=True)
                if any(value not in output.splitlines() for value in expected):
                    raise RuntimeError(f"{desktop} stock-client RPC result failed")
            import array
            data = (BRIDGE / "nvda-rpc-test.wav").read_bytes()
            start = data.index(b"data") + 8
            samples = array.array("h", data[start:len(data) - (len(data) - start) % 2])
            peak = max(map(abs, samples), default=0)
            assert f"NVDA rate={rate} hr=00000000" in backend_log.read_text()
            assert 'cancel=1 skipped=0' in backend_log.read_text(), 'active cancellation was not exercised'
            assert 'cancel=1 skipped=1' in backend_log.read_text(), 'repeated cancellation was not exercised'
            print(f"RPC rate={rate} SAPI PCM frames={len(samples)} peak={peak}")
            if len(samples) < 24000 or peak < 100:
                raise RuntimeError("RPC returned success without synthesized audio")
            print("PASS: stock NVDA client readiness, speech, cancellation and speech after cancel produce audio.")
            # No guest cancellation call: Android's loopback channel must be
            # able to interrupt active speech while the client is sleeping.
            shutil.copyfile(BUILD / "external-control-probe.exe", client_dir / "external-control-probe.exe")
            control_log = BRIDGE / "nvda-rpc-service.log"
            offset = len(control_log.read_text())
            backend_offset = len(backend_log.read_text())
            client = subprocess.Popen([WINE, str(client_dir / "external-control-probe.exe")],
                                      env=ENV, stdout=log, stderr=log)
            for _ in range(1000):
                if "speak length=665 status=0" in control_log.read_text()[offset:]:
                    break
                time.sleep(.01)
            else:
                raise RuntimeError("External-control probe did not submit speech")
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as control:
                control.sendto(b"C", ("127.0.0.1", 51235))
            client.wait(timeout=25)
            assert "keyboard cancel status=0" in control_log.read_text()[offset:]
            assert "cancel=1 skipped=0" in backend_log.read_text()[backend_offset:]
            result = (client_dir / "probe-result.txt").read_text().splitlines()
            assert all(value in result for value in ["ready=0", "speak=0", "speak2=0"])
            assert not any(value.startswith("cancel=") for value in result)
            print("PASS: external Control channel purges active speech without a guest cancel call; subsequent speech succeeds.")
        finally:
            subprocess.run([SERVER, "-k"], env=ENV, timeout=10, check=True)
            helper.wait(timeout=10)
