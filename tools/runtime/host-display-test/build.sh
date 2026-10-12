#!/bin/sh
# Builds PocketOS's display-server native code (X protocol sockets, input/output streams, drawing, SysV shared
# memory) for the host (x86-64 Linux), so the display pipeline can be tested with real Wine under qemu-aarch64.
# GPU parts (gpu_image.c, gpu_helper.c) are left out; the test draws with the CPU path.
set -e
cd "$(dirname "$0")"
SRC=../../../winruntime/src/main/cpp/pocketos_runtime
JH=${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}
mkdir -p out
python3 host_jni.py "$SRC/src" out/src
gcc -O2 -shared -fPIC -w -D__ANDROID_API__=30 -D_DEFAULT_SOURCE -include stdint.h -include string.h -include stdlib.h -include sys/types.h -include linux/memfd.h -Wno-incompatible-pointer-types -Istubs -I"$SRC/include" -I"$JH/include" -I"$JH/include/linux" \
    "out/src/drawable.c" "out/src/sysvshared_memory.c" "out/src/xconnector_epoll.c" "out/src/xinput_stream.c" \
    "out/src/xoutput_stream.c" "out/src/arrays.c" "out/src/ring_buffer.c" \
    -o out/libpocketos_runtime.so -lpthread -lm
echo "built $(pwd)/out/libpocketos_runtime.so"
# The GLX extension loads the prebuilt GL renderer library at start-up; the test program does not use OpenGL, so an
# empty host library stands in for it.
echo 'void pocketos_host_stub(void) {}' | gcc -shared -fPIC -x c - -o out/libgladiorenderer.so
# The Windows test program (needs mingw-w64: apt install gcc-mingw-w64-x86-64).
x86_64-w64-mingw32-gcc -O2 -mwindows click_test.c -o out/click_test.exe
