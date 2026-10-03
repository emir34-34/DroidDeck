#!/usr/bin/env bash
# Builds a glibc (Linux runtime) PanVK for Mali GPUs on Android's kbase kernel driver, packed as a
# zip the app imports under GPU drivers > Linux runtime driver (LinuxVulkanDriverManager).
#
# The kbase/CSF backend is the community's (wonderkast02/panvk-g720-kbase-csf); its releases are
# bionic builds for Winlator, which the runtime's glibc programs cannot load. mcghjbcg's multi-GPU
# fork (851a474) was tried first and is NOT used: on a Mali-G720 (MT6899) its fragment subqueue
# hangs on the vertex/tiler sync wait after ~50 presented frames - gamescope and Steam's update UI
# froze - while this branch ran vkcube and the Steam client without a single queue timeout. This builds the same source for aarch64 glibc, cross-compiled on an x86_64
# Linux host without root and without touching the host's packages:
#   - host LLVM 22 / clang / libclc / SPIRV-LLVM-Translator from the Arch archive, unpacked into a
#     private prefix, to build Mesa's host tools (mesa_clc, vtn_bindgen2, panfrost_compile);
#   - an Arch Linux ARM sysroot (the runtime's distribution) for the target;
#   - clang --target=aarch64-unknown-linux-gnu with the NDK's ld.lld.
# Needs: git, curl, python3 (venv), pkg-config, bison, flex, wayland-scanner, glslangValidator,
# spirv-tools (headers + .pc), and the Android NDK (for ld.lld/llvm-ar).
#
# Usage: tools/panvk/build-panvk-kbase.sh [workdir]   -> <workdir>/PanVK-kbase-glibc-DroidDeck.zip
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
W=$(realpath -m "${1:-$HOME/panvk-work}")
NDK=${ANDROID_NDK_HOME:-$HOME/Android/Sdk/ndk/27.3.13750724}
NDKBIN=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin
SRC_REPO=https://github.com/wonderkast02/panvk-g720-kbase-csf.git
SRC_BRANCH=g720-development
SRC_COMMIT=ca163891e8d3367c4b65ecaf7dcb7452545f4172
ARCH_ARCHIVE=https://archive.archlinux.org/packages
ALARM=http://de3.mirror.archlinuxarm.org/aarch64
HOST_PKGS=(l/llvm/llvm-22.1.8-2-x86_64 l/llvm-libs/llvm-libs-22.1.8-2-x86_64 c/clang/clang-22.1.8-1-x86_64
           l/libclc/libclc-22.1.8-2-any s/spirv-llvm-translator/spirv-llvm-translator-22.1.6-1-x86_64
           s/spirv-headers/spirv-headers-1%3A1.4.357.0-1-any)
# libstdc++ is left out on purpose: with only gcc's libstdc++.a in the sysroot, Mesa's few C++
# objects link it statically, so the driver asks nothing of the runtime's libstdc++ version.
TARGET_PKGS=(glibc linux-api-headers gcc libgcc zlib expat libdrm wayland wayland-protocols
             libx11 libxcb xorgproto libxau libxdmcp libxext libxrandr libxrender libxshmfence libxfixes libffi)

mkdir -p "$W"/{pkgs,hostroot,armpkgs,sysroot,host-bin}
cd "$W"

# Python build tools.
[ -x venv/bin/meson ] || { python3 -m venv venv && venv/bin/pip -q install meson ninja mako pyyaml packaging pycparser; }
export PATH="$W/venv/bin:$W/host-bin:$PATH"

# Host LLVM, private prefix; its .pc files are pointed at the prefix.
for u in "${HOST_PKGS[@]}"; do
  f=pkgs/$(basename "$u").pkg.tar.zst
  [ -s "$f" ] || curl -sSfLo "$f" "$ARCH_ARCHIVE/$u.pkg.tar.zst"
  tar --zstd -xf "$f" -C hostroot --exclude=.PKGINFO --exclude=.BUILDINFO --exclude=.MTREE --exclude=.INSTALL
done
grep -rl '^prefix=/usr$' hostroot/usr/lib/pkgconfig hostroot/usr/share/pkgconfig | xargs -r sed -i "s#^prefix=/usr\$#prefix=$W/hostroot/usr#"
sed -i "s#^libexecdir=/usr/share/clc#libexecdir=$W/hostroot/usr/share/clc#" hostroot/usr/share/pkgconfig/libclc.pc
export LD_LIBRARY_PATH="$W/hostroot/usr/lib"

# Arch Linux ARM sysroot.
for repo in core extra; do [ -s armpkgs/idx-$repo.html ] || curl -sSfLo armpkgs/idx-$repo.html "$ALARM/$repo/"; done
for p in "${TARGET_PKGS[@]}"; do
  re=$(printf '%s' "$p" | sed 's/+/\\+/g')
  f=$(grep -hoE "href=\"$re-[0-9][^\"]*(aarch64|any)\.pkg\.tar\.[a-z]+\"" armpkgs/idx-*.html | sed 's/href="//;s/"$//' | sort -V | tail -1)
  repo=$(grep -lF "$f" armpkgs/idx-*.html | head -1 | sed 's#.*idx-##;s#\.html##')
  [ -s "armpkgs/$f" ] || curl -sSfLo "armpkgs/$f" "$ALARM/$repo/$(printf '%s' "$f" | sed 's/+/%2B/g')"
  tar -xf "armpkgs/$f" -C sysroot --exclude=.PKGINFO --exclude=.BUILDINFO --exclude=.MTREE --exclude=.INSTALL
done

# Source, pinned, with DroidDeck's patches.
if [ "$(git -C src remote get-url origin 2>/dev/null)" != "$SRC_REPO" ]; then
  rm -rf src
  git clone -q --branch "$SRC_BRANCH" "$SRC_REPO" src
fi
git -C src checkout -q -f "$SRC_COMMIT"
for p in "$HERE"/patches/*.patch; do git -C src apply "$p"; done

# Host tools, against the private LLVM.
PKG_CONFIG_PATH="$W/hostroot/usr/lib/pkgconfig:$W/hostroot/usr/share/pkgconfig" PATH="$W/hostroot/usr/bin:$PATH" \
  meson setup --wipe src/build-host src -Dplatforms=[] -Dgallium-drivers=[] -Dvulkan-drivers=[] -Dtools=panfrost \
  -Dprecomp-compiler=enabled -Dinstall-precomp-compiler=true -Dllvm=enabled -Dmesa-clc=enabled -Dinstall-mesa-clc=true >/dev/null
ninja -C src/build-host src/compiler/clc/mesa_clc src/compiler/spirv/vtn_bindgen2 src/panfrost/clc/panfrost_compile
ln -sf "$W/src/build-host/src/compiler/clc/mesa_clc" host-bin/mesa_clc
ln -sf "$W/src/build-host/src/compiler/spirv/vtn_bindgen2" host-bin/vtn_bindgen2
ln -sf "$W/src/build-host/src/panfrost/clc/panfrost_compile" host-bin/panfrost_compile

# Cross file: the linker only on link lines, or meson's -Werror checks fail on an unused argument.
cat > cross-aarch64-glibc.ini <<EOF
[binaries]
c = ['$W/hostroot/usr/bin/clang', '--target=aarch64-unknown-linux-gnu', '--sysroot=$W/sysroot']
cpp = ['$W/hostroot/usr/bin/clang++', '--target=aarch64-unknown-linux-gnu', '--sysroot=$W/sysroot']
ar = '$NDKBIN/llvm-ar'
strip = '$NDKBIN/llvm-strip'
pkg-config = 'pkg-config'

[properties]
sys_root = '$W/sysroot'
pkg_config_libdir = ['$W/sysroot/usr/lib/pkgconfig', '$W/sysroot/usr/share/pkgconfig']

[built-in options]
c_link_args = ['--ld-path=$NDKBIN/ld.lld']
cpp_link_args = ['--ld-path=$NDKBIN/ld.lld']

[host_machine]
system = 'linux'
cpu_family = 'aarch64'
cpu = 'armv8-a'
endian = 'little'
EOF

meson setup --wipe src/build-glibc src --cross-file cross-aarch64-glibc.ini -Dbuildtype=release \
  -Dplatforms=x11,wayland -Dglx=disabled -Dgbm=disabled -Degl=disabled -Dopengl=false -Dgles1=disabled \
  -Dgles2=disabled -Dglvnd=disabled -Dvalgrind=disabled -Dgallium-drivers= -Dzstd=disabled \
  -Dmesa-clc=system -Dprecomp-compiler=system -Dvulkan-drivers=panfrost -Dllvm=disabled \
  -Dpanfrost-kmds=kbase,panthor,panfrost -Dlibunwind=disabled -Dexpat=enabled >/dev/null
ninja -C src/build-glibc src/panfrost/vulkan/libvulkan_panfrost.so

# Package: the library and a meta.json in LinuxVulkanDriverManager's schema.
rm -rf out && mkdir out
"$NDKBIN/llvm-strip" --strip-debug -o out/libvulkan_panfrost.so src/build-glibc/src/panfrost/vulkan/libvulkan_panfrost.so
glibc=$(readelf -V out/libvulkan_panfrost.so | grep -o 'GLIBC_[0-9.]*' | sort -uV | tail -1 | sed 's/GLIBC_//')
cat > out/meta.json <<EOF
{
  "schemaVersion": 1,
  "kind": "linux-vulkan-icd",
  "name": "PanVK kbase (glibc) for DroidDeck",
  "driverVersion": "Mesa 26.3.0-devel PanVK kbase-CSF (${SRC_COMMIT:0:7} + DroidDeck patches)",
  "minGlibc": "$glibc",
  "libc": "glibc"
}
EOF
python3 - "$W" <<'PY'
import sys, zipfile
w = sys.argv[1]
with zipfile.ZipFile(f"{w}/PanVK-kbase-glibc-DroidDeck.zip", "w", zipfile.ZIP_DEFLATED) as z:
    z.write(f"{w}/out/libvulkan_panfrost.so", "libvulkan_panfrost.so")
    z.write(f"{w}/out/meta.json", "meta.json")
PY
echo "built $W/PanVK-kbase-glibc-DroidDeck.zip (needs glibc $glibc)"
