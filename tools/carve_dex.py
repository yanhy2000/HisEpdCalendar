"""Carve embedded DEX files out of an ART .odex (ELF/OAT) binary.

DEX layout: 8-byte magic ("dex\n035\0" etc.), file_size (u32 LE) at offset 32.
"""
import struct
import sys
import pathlib

MAGICS = [b"dex\n035\x00", b"dex\n036\x00", b"dex\n037\x00", b"dex\n038\x00"]


def carve(path: str, outdir: str):
    data = pathlib.Path(path).read_bytes()
    hits = []
    for magic in MAGICS:
        start = 0
        while True:
            i = data.find(magic, start)
            if i < 0:
                break
            size = struct.unpack_from("<I", data, i + 32)[0]
            if 112 <= size and i + size <= len(data):
                hits.append((i, size))
            start = i + 1
    # keep non-overlapping, largest first
    hits.sort(key=lambda t: -t[1])
    kept = []
    for off, size in hits:
        if all(off + size <= o or off >= o + s for o, s in kept):
            kept.append((off, size))
    out = pathlib.Path(outdir)
    out.mkdir(parents=True, exist_ok=True)
    for n, (off, size) in enumerate(sorted(kept)):
        dest = out / f"{pathlib.Path(path).stem}_classes{n}.dex"
        dest.write_bytes(data[off:off + size])
        print(f"{dest}  offset={off} size={size}")


if __name__ == "__main__":
    carve(sys.argv[1], sys.argv[2])
