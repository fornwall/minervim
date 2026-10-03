#!/usr/bin/env bash
# Reproducible Linux cross-build. Outputs are consumed by the app's Gradle task.
set -euo pipefail
cd "$(dirname "$0")/.."
root=$PWD
version=0.11.6
ndk_version=30.0.16248370
sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}
ndk="$sdk/ndk/$ndk_version"
if [[ ! -f "$ndk/build/cmake/android.toolchain.cmake" ]]; then
  echo "Install NDK $ndk_version: sdkmanager 'ndk;$ndk_version'" >&2
  exit 1
fi
work="$root/native/work"
source="$work/neovim-$version"
mkdir -p "$work"
exec 9>"$work/build.lock"
flock 9
if [[ ! -f "$work/neovim.tar.gz" ]]; then
  curl --fail --location --retry 3 "https://codeload.github.com/neovim/neovim/tar.gz/refs/tags/v$version" -o "$work/neovim.tar.gz"
fi
echo "d1c8e3f484ed1e231fd5f48f53b7345b628e52263d5eef489bb8b73ca8d90fca  $work/neovim.tar.gz" | sha256sum -c -
patch_hash=$(sha256sum native/patch-neovim.py | cut -d' ' -f1)
if [[ ! -f "$work/patch-stamp" ]] || [[ "$(cat "$work/patch-stamp")" != "$patch_hash" ]]; then
  tar -xzf "$work/neovim.tar.gz" -C "$work"
  python3 native/patch-neovim.py "$source"
  echo "$patch_hash" > "$work/patch-stamp"
fi
jobs=${NEOVIM_BUILD_JOBS:-8}
cmake -S "$source/cmake.deps" -B "$work/host-deps" -G Ninja -DUSE_BUNDLED_TS_PARSERS=OFF
cmake --build "$work/host-deps" -j "$jobs"
cmake -S "$source" -B "$work/host" -G Ninja -DCMAKE_BUILD_TYPE=Release -DDEPS_PREFIX="$work/host-deps/usr" -DENABLE_LIBINTL=OFF
cmake --build "$work/host" --target nlua0 -j "$jobs"
if [[ $# == 0 ]]; then set -- x86_64 arm64-v8a; fi
for abi in "$@"; do
  case "$abi" in x86_64|arm64-v8a) ;; *) echo "Unsupported ABI: $abi" >&2; exit 1 ;; esac
  deps="$work/ndk30-$abi-deps"
  build="$work/ndk30-$abi"
  android_args=(-DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-37)
  cmake -S "$source/cmake.deps" -B "$deps" -G Ninja "${android_args[@]}" -DUSE_BUNDLED_TS_PARSERS=OFF
  cmake --build "$deps" -j "$jobs"
  cmake -S "$source" -B "$build" -G Ninja "${android_args[@]}" \
    -DCMAKE_BUILD_TYPE=Release -DDEPS_PREFIX="$deps/usr" -DCMAKE_FIND_ROOT_PATH="$deps/usr" \
    -DANDROID_HOST_NLUA0="$work/host/lib/libnlua0.so" \
    -DANDROID_BRIDGE_SOURCE="$root/native/bridge.c" \
    -DLUA_PRG="$work/host-deps/usr/bin/luajit" \
    -DLUA_GEN_PRG="$work/host-deps/usr/bin/luajit" \
    -DENABLE_LIBINTL=OFF -DCOMPILE_LUA=OFF -DCMAKE_POSITION_INDEPENDENT_CODE=ON
  cmake --build "$build" --target neovim -j "$jobs"
  mkdir -p "app/build/generated/neovim/jniLibs/$abi"
  cp "$build/lib/libneovim.so" "app/build/generated/neovim/jniLibs/$abi/"
  "$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" --strip-unneeded "app/build/generated/neovim/jniLibs/$abi/libneovim.so"
done
assets="$root/app/build/generated/neovim/assets"
mkdir -p "$assets/runtime" "$assets/licenses"
cp -a "$source/runtime/." "$assets/runtime/"
mkdir -p "$assets/runtime/lua/jit"
cp -a "$work/ndk30-$1-deps/usr/share/luajit-2.1/jit/." "$assets/runtime/lua/jit/"
cp "$source/src/nvim/vterm/LICENSE" "$assets/licenses/vterm.txt"
cp "$source/src/mpack/LICENSE-MIT" "$assets/licenses/mpack.txt"
cp "$source/src/xdiff/COPYING" "$assets/licenses/xdiff.txt"
cp "$source/LICENSE.txt" "$assets/licenses/neovim.txt"
python3 "$root/native/copy-licenses.py" "$work/ndk30-$1-deps/build/src" "$assets/licenses" "$source"
