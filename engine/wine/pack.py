#!/usr/bin/env python3
"""Packs an installed Wine-for-Android tree into PocketOS's engine asset.

Output: <out>/wine.zip (layout arm64-v8a/{bin,lib/wine/...}, share/wine, fex/*.dll) and <out>/VERSION.
Symlinks are stored as "<name>.symlink" text entries (WineEngine recreates them). Unix-side ELF files and Windows DLLs are stripped of symbols.
libfreetype.so goes to jniLibs so Android installs it next to PocketOS's own native code.
"""
import argparse, hashlib, io, os, shutil, subprocess, sys, tarfile, zipfile

SKIP_DIRS = {"include", "man", "applications"}          # headers, man pages, Linux desktop files
SKIP_SUFFIX = (".a", ".def", ".la")                      # import/static libraries are only for building programs


def is_elf(path):
    with open(path, "rb") as f:
        return f.read(4) == b"\x7fELF"


def fex_dlls(deb, dest):
    """Extracts the FEX Windows DLLs from the fex-emu-wine .deb (ar archive with data.tar.*)."""
    os.makedirs(dest, exist_ok=True)
    data = subprocess.run(["ar", "p", deb, *[m for m in subprocess.run(["ar", "t", deb], capture_output=True, text=True, check=True).stdout.split() if m.startswith("data.tar")]],
                          capture_output=True, check=True).stdout
    found = []
    with tarfile.open(fileobj=io.BytesIO(data)) as t:
        for m in t.getmembers():
            name = os.path.basename(m.name)
            if m.isfile() and name in ("libarm64ecfex.dll", "libwow64fex.dll"):
                with open(os.path.join(dest, name), "wb") as o:
                    o.write(t.extractfile(m).read())
                found.append(name)
    if sorted(found) != ["libarm64ecfex.dll", "libwow64fex.dll"]:
        sys.exit("FEX DLLs not found in " + deb)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--inst", required=True, help="installed prefix (DESTDIR + /usr/local)")
    ap.add_argument("--freetype", required=True)
    ap.add_argument("--fex-deb", required=True)
    ap.add_argument("--strip", required=True)
    ap.add_argument("--pe-strip", required=True, help="llvm-mingw's llvm-strip (for the Windows DLLs)")
    ap.add_argument("--out", required=True)
    ap.add_argument("--jnilibs", required=True)
    a = ap.parse_args()

    stage = a.out + ".stage"
    shutil.rmtree(stage, ignore_errors=True)
    os.makedirs(stage)
    for top in ("arm64-v8a", "share"):
        # Moved, not copied: the installed tree is about 1 GB. Run "make install" again to pack a second time.
        shutil.move(os.path.join(a.inst, top), os.path.join(stage, top))
    for root, dirs, files in os.walk(stage, topdown=True):
        for d in [d for d in dirs if d in SKIP_DIRS]:
            shutil.rmtree(os.path.join(root, d)); dirs.remove(d)
        for n in files:
            if n.endswith(SKIP_SUFFIX):
                os.remove(os.path.join(root, n))
    fex_dlls(a.fex_deb, os.path.join(stage, "fex"))

    for root, _, files in os.walk(stage):
        for n in files:
            p = os.path.join(root, n)
            if os.path.islink(p):
                continue
            if is_elf(p):
                subprocess.run([a.strip, "--strip-unneeded", p], check=True)
            elif "-windows" in root and open(p, "rb").read(2) == b"MZ":
                subprocess.run([a.pe_strip, p], check=True)   # removes only the COFF symbol table

    os.makedirs(a.out, exist_ok=True)
    for old in os.listdir(a.out):
        os.remove(os.path.join(a.out, old))
    zpath = os.path.join(a.out, "wine.zip")
    with zipfile.ZipFile(zpath, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for root, dirs, files in os.walk(stage):
            dirs.sort()
            for n in sorted(files) + [d for d in dirs if os.path.islink(os.path.join(root, d))]:
                p = os.path.join(root, n)
                rel = os.path.relpath(p, stage)
                if os.path.islink(p):
                    z.writestr(rel + ".symlink", os.readlink(p))
                else:
                    z.write(p, rel)
    h = hashlib.sha256(open(zpath, "rb").read()).hexdigest()[:16]
    with open(os.path.join(a.out, "VERSION"), "w") as f:
        f.write("wine-11.19+fex-2610+" + h + "\n")

    os.makedirs(a.jnilibs, exist_ok=True)
    shutil.copy(a.freetype, os.path.join(a.jnilibs, "libfreetype.so"))
    subprocess.run([a.strip, "--strip-unneeded", os.path.join(a.jnilibs, "libfreetype.so")], check=True)
    shutil.rmtree(stage)
    print(zpath, os.path.getsize(zpath))


if __name__ == "__main__":
    main()
