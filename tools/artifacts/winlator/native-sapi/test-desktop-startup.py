"""Compare Wine desktop ownership before and after the helper startup change."""
import os
from pathlib import Path
import subprocess
import time

HERE = Path(__file__).resolve().parent
ENV = dict(os.environ, WINEPREFIX="/home/willb/winlator-sapi-diagnostic",
           WINEDEBUG="-all", WINEDLLOVERRIDES="sapi=n")
WINE = "/usr/bin/wine64-stable"
SERVER = "/usr/lib/x86_64-linux-gnu/wine/wineserver"

def check(mode):
    name = f"tts-{mode}-{os.getpid()}"
    with open(HERE / f"desktop-{mode}.log", "w") as log:
        helper = subprocess.Popen([WINE, str(HERE / "desktop-startup-probe.exe"), mode, name],
                                  env=ENV, stdout=log, stderr=log)
        if mode == "--precreate":
            for _ in range(100):
                if "window-ready" in (HERE / f"desktop-{mode}.log").read_text():
                    break
                time.sleep(.1)
            else:
                raise RuntimeError("Probe did not create desktop window")
        launcher = subprocess.Popen([WINE, "explorer", f"/desktop={name},1024x768", "notepad"],
                                    env=ENV, stdout=log, stderr=log)
        time.sleep(3)
        status = launcher.poll()
        print(f"{mode}: explorer status={status}", flush=True)
        subprocess.run([SERVER, "-k"], env=ENV, timeout=10, check=True)
        helper.wait(timeout=10)
        launcher.wait(timeout=10)
        return status

if __name__ == "__main__":
    before = check("--precreate")
    after = check("--attach")
    if before != 0 or after is not None:
        raise RuntimeError(f"Unexpected desktop lifecycle: {before}, {after}")
    print("PASS: precreating the desktop exits its launcher; attaching preserves its owner.")
