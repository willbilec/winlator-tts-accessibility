"""Fetch the required build font from the first public release, with SHA-256 validation."""
from hashlib import sha256
from pathlib import Path
from tempfile import NamedTemporaryFile
from urllib.request import urlopen
import os

ROOT = Path(__file__).resolve().parent.parent
TARGET = ROOT / "app/src/main/assets/audio_game_dependencies/breed_memorial_sourcehansans.ttc"
EXPECTED = "e7d21914e6426157fda3f40dbd53df643dd2e5b9bce6e58790d76d666d89a538"
URL = "https://github.com/willbilec/winlator-tts-accessibility/releases/download/v0.1.0-build94/breed_memorial_sourcehansans.ttc"


def digest(path):
    checksum = sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            checksum.update(chunk)
    return checksum.hexdigest()


def main():
    if TARGET.exists() and digest(TARGET) == EXPECTED:
        print("Build font is present and its SHA-256 matches.")
        return
    TARGET.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with NamedTemporaryFile(dir=TARGET.parent, suffix=".tmp", delete=False) as output:
            temporary = Path(output.name)
            with urlopen(URL, timeout=120) as response:
                for chunk in iter(lambda: response.read(1024 * 1024), b""):
                    output.write(chunk)
        if digest(temporary) != EXPECTED:
            raise RuntimeError("Downloaded font SHA-256 mismatch; existing font was preserved.")
        os.replace(temporary, TARGET)
        print("Downloaded build font and verified its SHA-256.")
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


if __name__ == "__main__":
    main()
