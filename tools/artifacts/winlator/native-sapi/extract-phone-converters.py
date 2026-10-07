"""Copy original SAPI phone-converter registration from verified SP1 manifests."""
from pathlib import Path
import xml.etree.ElementTree as ET

HERE = Path(__file__).resolve().parent
PREFIX = r"HKEY_LOCAL_MACHINE\SOFTWARE\Microsoft\Speech\PhoneConverters"

def collect(architecture):
    manifests = list((HERE / "runtime" / architecture).glob("*.manifest"))
    if len(manifests) != 1:
        raise RuntimeError(f"Expected one {architecture} speech manifest")
    entries = {}
    for key in ET.parse(manifests[0]).findall(".//{*}registryKey"):
        path = key.get("keyName", "")
        if path.lower().startswith(PREFIX.lower()):
            values = {}
            for value in key.findall("{*}registryValue"):
                if value.get("valueType") != "REG_SZ":
                    raise RuntimeError(f"Unexpected converter value type: {value.attrib}")
                values[value.get("name", "")] = value.get("value", "")
            entries[path] = values
    if not any(path.lower().endswith(r"tokens\universal") for path in entries):
        raise RuntimeError("Universal phone converter absent from manifest")
    return entries

def quote(value):
    return '"' + value.replace('\\', '\\\\').replace('"', '\\"') + '"'

if __name__ == "__main__":
    entries = collect("x64")
    if entries != collect("x86"):
        raise RuntimeError("SAPI converter registration differs by architecture")
    lines = ["Windows Registry Editor Version 5.00", ""]
    for path, values in sorted(entries.items()):
        lines.append(f"[{path}]")
        for name, value in sorted(values.items()):
            lines.append((quote(name) if name else "@") + "=" + quote(value))
        lines.append("")
    destination = HERE / "payload" / "phone-converters.reg"
    destination.parent.mkdir(exist_ok=True)
    destination.write_text("\r\n".join(lines), encoding="utf-16")
    print(f"Extracted {len(entries)} matching converter registry keys from both SP1 manifests")
