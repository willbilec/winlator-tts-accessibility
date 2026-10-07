"""Remove exec-out's interleaved tar diagnostics at known record boundaries.

Android tar cannot archive UNIX sockets. adb exec-out merges stderr into the
binary stream. Keep the original, and remove only the five observed messages.
"""
from pathlib import Path
import shutil
import tarfile

base = Path(__file__).resolve().parent
source = base / "winlator-data-before-clean-reset-2026-10-04.tar"
target = base / "winlator-data-before-clean-reset-2026-10-04-verified.tar"
messages = [
    (8549545984, b"tar: unknown file type '140000'\n"),
    (8549546528, b"tar: unknown file type '140000'\n"),
    (8549547072, b"tar: unknown file type '140000'\n"),
    (8549558880, b"tar: unknown file type '140000'\n"),
    (8549768320, b"tar: had errors\n"),
]
with source.open("rb") as original, target.open("xb") as clean:
    position = 0
    for offset, message in messages:
        remaining = offset - position
        while remaining:
            chunk = original.read(min(remaining, 8 * 1024 * 1024))
            if not chunk:
                raise RuntimeError("Unexpected end of backup")
            clean.write(chunk)
            remaining -= len(chunk)
        if original.read(len(message)) != message:
            raise RuntimeError(f"Unexpected bytes at diagnostic offset {offset}")
        position = offset + len(message)
    shutil.copyfileobj(original, clean, 8 * 1024 * 1024)
if target.stat().st_size % 512:
    raise RuntimeError("Clean archive is not aligned to TAR records")
containers = set()
settings = databases = 0
count = 0
with tarfile.open(target) as archive:
    for member in archive:
        count += 1
        if "xuser-" in member.name:
            containers.add(member.name.split("xuser-", 1)[1].split("/", 1)[0])
        settings += member.name.startswith("shared_prefs/")
        databases += member.name == "databases" or member.name.startswith("databases/")
if containers != {"1", "2", "3"} or not settings or not databases:
    raise RuntimeError("Missing expected app-data groups")
print(f"Archive: {target}")
print(f"Validated {count} records; containers 1, 2, 3; {settings} preference files; {databases} database entries")
