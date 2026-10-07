"""Summarize physical arrow packets versus captured game snapshots."""
import argparse
import re
import subprocess
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("path", nargs="?")
parser.add_argument("--device", default="R5CWA1XFMZJ")
args = parser.parse_args()
if args.path:
    text = Path(args.path).read_text(encoding="utf-8-sig")
else:
    text = subprocess.check_output([
        "adb", "-s", args.device, "shell", "run-as", "com.winlator", "cat",
        "files/rootfs/home/xuser-1/.wine/drive_c/NVDA-Bridge/bavisoft-capture/grizzly-dinput-trace.log",
    ], text=True)
records = []
for line in text.splitlines():
    fields = dict(re.findall(r"(\w+)=([^\s]+)", line))
    if "t" in fields:
        fields["time"] = float(fields["t"])
        records.append(fields)
records.sort(key=lambda record: record["time"])
arrows = {38: ("Up", 0), 39: ("Right", 1), 40: ("Down", 2), 37: ("Left", 3)}
snapshots = [record for record in records if record["code"] == "3"]
held = {}
taps = []
for record in records:
    if record["code"] != "12":
        continue
    value = int(record["value"])
    key, flags = value & 0xffff, value >> 16
    if key not in arrows:
        continue
    if flags & 1:
        if key in held:
            down = held.pop(key)
            taps.append((key, down, record))
    elif key not in held:
        held[key] = record
for number, (key, start, end) in enumerate(taps, 1):
    down, up = start["time"], end["time"]
    event_duration = up-down+float(start["call_ms"])-float(end["call_ms"]) if "call_ms=event_age" in text else None
    name, index = arrows[key]
    seen = [record for record in snapshots if down <= record["time"] <= up + 25
            and int(record["arrows"].split(",")[index], 16) & 0x80]
    after = next((record for record in snapshots if record["time"] >= up
                  and not int(record["arrows"].split(",")[index], 16) & 0x80), None)
    before = next((record for record in reversed(snapshots) if record["time"] < down), None)
    poll_delta = int(after["polls"]) - int(before["polls"]) if after and before and after["object"] == before["object"] else None
    print(f"{number}: {name} down={down:.3f} duration={up-down:.3f}ms "
          f"held_snapshots={len(seen)} first_logical_before="
          f"{seen[0]['mapped_before'].split(',')[index] if seen else '-'} "
          f"release_read_ms={after['time']-up if after else None} poll_delta={poll_delta} "
          f"event_age_ms={start['call_ms']}/{end['call_ms']} event_duration_ms={event_duration}")
print(f"paired_taps={len(taps)} held_keys={list(held)} snapshots={len(snapshots)} "
      f"records={len(records)} full={'TRACE FULL' in text}")
