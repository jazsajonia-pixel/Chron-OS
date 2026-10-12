#!/usr/bin/env python3
"""Retarget Winlator's prebuilt runtime archives to PocketOS's package directory.

Winlator's Linux userland (glibc, Box64, Wine, Mesa, PulseAudio...) is built with the absolute prefix
/data/data/com.winlator/files. PocketOS uses the application id "com.chron.os", which has exactly the same
length, so every occurrence can be replaced byte-for-byte without breaking ELF binaries, caches or offsets.

Usage: retarget_assets.py <assets_dir>   (rewrites every *.tzst in place; verifies nothing is left behind)
"""
import io, os, subprocess, sys, tarfile

OLD = b"/data/data/com.winlator/"
NEW = b"/data/data/com.chron.os/"
assert len(OLD) == len(NEW)

def patch_archive(path):
    raw = subprocess.run(["zstd", "-dc", path], check=True, capture_output=True).stdout
    if OLD not in raw:
        return 0
    src = tarfile.open(fileobj=io.BytesIO(raw), mode="r:")
    out = io.BytesIO()
    dst = tarfile.open(fileobj=out, mode="w:", format=src.format if src.format is not None else tarfile.GNU_FORMAT)
    files = 0
    for m in src.getmembers():
        data = None
        if m.isreg():
            data = src.extractfile(m).read()
            if OLD in data:
                data = data.replace(OLD, NEW); files += 1
            m.size = len(data)
        if m.issym() or m.islnk():
            m.linkname = m.linkname.replace(OLD.decode(), NEW.decode())
        m.name = m.name.replace(OLD.decode(), NEW.decode())
        dst.addfile(m, io.BytesIO(data) if data is not None else None)
    dst.close()
    blob = out.getvalue()
    assert OLD not in blob, path
    comp = subprocess.run(["zstd", "-q", "-19", "-T0", "-c"], input=blob, check=True, capture_output=True).stdout
    with open(path + ".tmp", "wb") as f: f.write(comp)
    os.replace(path + ".tmp", path)
    return files

if __name__ == "__main__":
    root = sys.argv[1]
    for d, _, names in os.walk(root):
        for n in sorted(names):
            if n.endswith(".tzst"):
                p = os.path.join(d, n)
                c = patch_archive(p)
                if c: print(f"retargeted {c:4d} files in {os.path.relpath(p, root)}")
