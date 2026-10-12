#!/usr/bin/env bash
# Builds PocketOS's Windows engine: upstream Wine for Android (arm64) with the i386, ARM64EC and ARM64 Windows
# sides, plus the FEX emulator DLLs, and packs it into app/src/main/assets/engine.
#
# Nothing from Winlator is used. Inputs (all downloaded from their upstream projects):
#   Wine 11.19 source      https://dl.winehq.org/wine/source/11.x/wine-11.19.tar.xz       (LGPL-2.1-or-later)
#   FreeType 2.13.3        https://download.savannah.gnu.org/releases/freetype/          (FTL)
#   llvm-mingw 20261006    https://github.com/mstorsjo/llvm-mingw/releases               (PE cross compiler)
#   FEX 2610 (Wine DLLs)   https://ppa.launchpadcontent.net/fex-emu/fex/ubuntu/pool/main/f/fex-emu-wine/  (MIT)
#   Android NDK 24.0.8215888 (API 28 target)
#
# Usage: WINE_SRC=~/wine-11.19 NDK=~/android-sdk/ndk/24.0.8215888 MINGW=~/llvm-mingw/bin FEX_DEB=fex-emu-wine.deb \
#        ./engine/wine/build.sh
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
: "${WINE_SRC:?path to the Wine source}" "${NDK:?Android NDK}" "${MINGW:?llvm-mingw bin directory}" "${FEX_DEB:?fex-emu-wine .deb}"
work="${WORK:-$HOME/wb}"
tc="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
mkdir -p "$work"

# 1. PocketOS patches: the driver talks to com.chronos.pocketos.engine.WineHost over a PocketOS-named socket.
(cd "$WINE_SRC" && for p in "$here"/patches/*.patch; do patch -p1 -N --dry-run -s < "$p" >/dev/null 2>&1 && patch -p1 -s < "$p" || true; done)

# 2. Wine's build tools for this PC.
mkdir -p "$work/tools" && (cd "$work/tools" && "$WINE_SRC/configure" --without-x --without-freetype --enable-win64 -q && \
  make -j"$(nproc)" tools/all tools/widl/all tools/winebuild/all tools/winegcc/all tools/wmc/all tools/wrc/all tools/sfnt2fon/all nls/all)

# 3. FreeType for Android (Wine's font rendering).
if [ ! -f "$work/sysroot/lib/libfreetype.so" ]; then
  echo "Build FreeType 2.13.3 with $tc/aarch64-linux-android28-clang into $work/sysroot (cmake -DBUILD_SHARED_LIBS=ON" \
       "-DFT_DISABLE_ZLIB=ON -DFT_DISABLE_PNG=ON -DFT_DISABLE_HARFBUZZ=ON -DFT_DISABLE_BROTLI=ON -DFT_DISABLE_BZIP2=ON)" >&2
  exit 1
fi

# 4. Wine for Android.
mkdir -p "$work/android"
cd "$work/android"
export PATH="$tc:$MINGW:$PATH"
GRADLE=/bin/true ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}" PKG_CONFIG_LIBDIR="$work/sysroot/lib/pkgconfig" \
"$WINE_SRC/configure" --host=aarch64-linux-android --with-wine-tools="$work/tools" --enable-archs=i386,arm64ec,aarch64 \
  CC=aarch64-linux-android28-clang CXX=aarch64-linux-android28-clang++ AR=llvm-ar RANLIB=llvm-ranlib STRIP=llvm-strip \
  CFLAGS=-O2 CROSSCFLAGS=-O2 FREETYPE_CFLAGS="-I$work/sysroot/include/freetype2" FREETYPE_LIBS="-L$work/sysroot/lib -lfreetype" \
  --disable-tests --without-x --without-gstreamer --without-pulse --without-alsa --without-oss --without-cups --without-dbus \
  --without-gnutls --without-sane --without-krb5 --without-netapi --without-usb --without-v4l2 --without-wayland \
  --without-opencl --without-pcap --without-sdl --without-udev --without-capi --without-gphoto --without-inotify \
  --without-ffmpeg --without-fontconfig --without-gssapi --without-vulkan
make -j"$(nproc)"
rm -rf "$work/inst" && make install DESTDIR="$work/inst" >/dev/null

# 5. Pack: arm64-v8a/{bin,lib/wine}, share/wine, fex/*.dll.
exec python3 "$here/pack.py" --inst "$work/inst/usr/local" --freetype "$work/sysroot/lib/libfreetype.so" \
  --fex-deb "$FEX_DEB" --strip "$tc/llvm-strip" --pe-strip "$MINGW/llvm-strip" --out "$repo/app/src/main/assets/engine" \
  --jnilibs "$repo/app/src/main/jniLibs/arm64-v8a"
