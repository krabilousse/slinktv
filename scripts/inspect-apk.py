"""Inspect native libraries inside the APK, including Chaquopy's nested archives."""
import hashlib
import io
import json
from pathlib import Path
import struct
import sys
import zipfile


def elf_alignment(data):
    wide = data[4] == 2
    endian = "<" if data[5] == 1 else ">"
    offset = struct.unpack_from(endian + ("Q" if wide else "I"), data, 32 if wide else 28)[0]
    size, count = struct.unpack_from(endian + "HH", data, 54 if wide else 42)
    alignments = []
    for i in range(count):
        position = offset + i * size
        if struct.unpack_from(endian + "I", data, position)[0] == 1:
            alignments.append(struct.unpack_from(endian + ("Q" if wide else "I"), data, position + (48 if wide else 28))[0])
    return min(alignments)


def libraries(archive, prefix=""):
    for name in archive.namelist():
        if name.endswith(".so"):
            data = archive.read(name)
            if data.startswith(b"\x7fELF"):
                yield prefix + name, elf_alignment(data)
        elif name.endswith(".imy"):
            with zipfile.ZipFile(io.BytesIO(archive.read(name))) as child:
                yield from libraries(child, prefix + name + "!/")


if __name__ == "__main__":
    path = Path(sys.argv[1])
    with zipfile.ZipFile(path) as apk:
        assert apk.testzip() is None, "APK archive CRC failure"
        entries = dict(libraries(apk))
        abis = sorted({name.split("/")[1] for name in apk.namelist() if name.startswith("lib/")})
        assert abis == ["arm64-v8a", "armeabi-v7a", "x86_64"]
        assert "assets/chaquopy/app.imy" in apk.namelist()
        assert "assets/THIRD_PARTY_NOTICES.txt" in apk.namelist()
        assert "classes.dex" in apk.namelist()
    print(json.dumps({"apk": path.name, "bytes": path.stat().st_size,
                      "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                      "abis": abis, "native_libraries": len(entries),
                      "below_16kb_alignment": {name: alignment for name, alignment in entries.items() if alignment < 16384}}, indent=2))
