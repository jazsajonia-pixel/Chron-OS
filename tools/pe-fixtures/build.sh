#!/bin/sh
# Rebuilds the PE test fixtures used by :core tests. Requires mingw-w64.
set -e
cd "$(dirname "$0")"
out=../../core/src/test/resources/pe
mkdir -p "$out"
x86_64-w64-mingw32-windres version.rc -O coff -o version64.o
i686-w64-mingw32-windres version.rc -O coff -o version32.o
x86_64-w64-mingw32-gcc -O2 -s -o "$out/hello_console_x64.exe" hello_console.c version64.o
i686-w64-mingw32-gcc -O2 -s -o "$out/hello_console_x86.exe" hello_console.c
x86_64-w64-mingw32-windres version.rc -O coff -o versiong.o
x86_64-w64-mingw32-gcc -O2 -s -mwindows -o "$out/hello_gui_x64.exe" hello_gui.c versiong.o
mkdir -p ../../app/src/main/assets/sample && cp "$out/hello_gui_x64.exe" ../../app/src/main/assets/sample/pocketos-sample.exe
x86_64-w64-mingw32-gcc -O2 -s -shared -o "$out/hello_x64.dll" hello_dll.c
rm -f version64.o version32.o versiong.o
ls -l "$out"
# Program importing a DLL that does not exist (for missing-component diagnostics).
x86_64-w64-mingw32-dlltool -d m.def -l libm_missing.a
x86_64-w64-mingw32-gcc -O2 -s -o "$out/needs_missing_dll_x64.exe" missing.c libm_missing.a
rm -f libm_missing.a
