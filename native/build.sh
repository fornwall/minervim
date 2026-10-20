#!/usr/bin/env bash
# Reproducible Linux cross-build. Outputs are consumed by the app's Gradle task.
set -euo pipefail
cd "$(dirname "$0")/.."
root=$PWD
version=0.12.6
ndk_version=30.0.16248370
sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}
ndk="$sdk/ndk/$ndk_version"
if [[ ! -f "$ndk/build/cmake/android.toolchain.cmake" ]]; then
  echo "Install NDK $ndk_version: sdkmanager 'ndk;$ndk_version'" >&2
  exit 1
fi
work="$root/native/work"
source="$work/neovim-$version"
# CMake caches are bound to their source tree, so build trees are per version.
out="$work/build-$version"
mkdir -p "$out"
exec 9>"$work/build.lock"
flock 9
tarball="$work/neovim-$version.tar.gz"
if [[ ! -f "$tarball" ]]; then
  curl --fail --location --retry 3 "https://codeload.github.com/neovim/neovim/tar.gz/refs/tags/v$version" -o "$tarball"
fi
echo "8629fdc5eb951daf89a76f68cad12f2f1e03fab0d539150ba3fe6d177d254b7f  $tarball" | sha256sum -c -
patch_hash=$(sha256sum native/patch-neovim.py | cut -d' ' -f1)
if [[ ! -f "$out/patch-stamp" ]] || [[ "$(cat "$out/patch-stamp")" != "$patch_hash" ]]; then
  tar -xzf "$tarball" -C "$work"
  python3 native/patch-neovim.py "$source"
  echo "$patch_hash" > "$out/patch-stamp"
fi
jobs=${NEOVIM_BUILD_JOBS:-8}
cmake -S "$source/cmake.deps" -B "$out/host-deps" -G Ninja -DUSE_BUNDLED_TS_PARSERS=OFF
cmake --build "$out/host-deps" -j "$jobs"
cmake -S "$source" -B "$out/host" -G Ninja -DCMAKE_BUILD_TYPE=Release -DDEPS_PREFIX="$out/host-deps/usr" -DENABLE_LIBINTL=OFF
cmake --build "$out/host" --target nlua0 -j "$jobs"
if [[ $# == 0 ]]; then set -- x86_64 arm64-v8a; fi
for abi in "$@"; do
  case "$abi" in x86_64|arm64-v8a) ;; *) echo "Unsupported ABI: $abi" >&2; exit 1 ;; esac
  deps="$out/ndk30-$abi-deps"
  build="$out/ndk30-$abi"
  android_args=(-DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-37)
  cmake -S "$source/cmake.deps" -B "$deps" -G Ninja "${android_args[@]}" -DUSE_BUNDLED_TS_PARSERS=OFF
  cmake --build "$deps" -j "$jobs"
  cmake -S "$source" -B "$build" -G Ninja "${android_args[@]}" \
    -DCMAKE_BUILD_TYPE=Release -DDEPS_PREFIX="$deps/usr" -DCMAKE_FIND_ROOT_PATH="$deps/usr" \
    -DANDROID_HOST_NLUA0="$out/host/lib/libnlua0.so" \
    -DANDROID_BRIDGE_SOURCE="$root/native/bridge.c" \
    -DLUA_PRG="$out/host-deps/usr/bin/luajit" \
    -DLUA_GEN_PRG="$out/host-deps/usr/bin/luajit" \
    -DENABLE_LIBINTL=OFF -DCOMPILE_LUA=OFF -DCMAKE_POSITION_INDEPENDENT_CODE=ON
  cmake --build "$build" --target neovim -j "$jobs"
  mkdir -p "app/build/generated/neovim/jniLibs/$abi"
  cp "$build/lib/libneovim.so" "app/build/generated/neovim/jniLibs/$abi/"
  "$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" --strip-unneeded "app/build/generated/neovim/jniLibs/$abi/libneovim.so"
done
assets="$root/app/build/generated/neovim/assets"
rm -rf "$assets"
mkdir -p "$assets/runtime" "$assets/licenses"
cp -a "$source/runtime/." "$assets/runtime/"
mkdir -p "$assets/runtime/lua/jit"
cp -a "$out/ndk30-$1-deps/usr/share/luajit-2.1/jit/." "$assets/runtime/lua/jit/"
cp "$source/src/nvim/vterm/LICENSE" "$assets/licenses/vterm.txt"
cp "$source/src/mpack/LICENSE-MIT" "$assets/licenses/mpack.txt"
cp "$source/src/xdiff/COPYING" "$assets/licenses/xdiff.txt"
cp "$source/LICENSE.txt" "$assets/licenses/neovim.txt"
python3 "$root/native/copy-licenses.py" "$out/ndk30-$1-deps/build/src" "$assets/licenses" "$source"
