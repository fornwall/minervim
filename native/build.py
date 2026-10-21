#!/usr/bin/env python3
"""Build the pinned Android core on Linux or macOS. Invoked by Gradle."""
import argparse
import fcntl
import hashlib
import os
from pathlib import Path
import platform
import shlex
import shutil
import subprocess
import sys


VERSION = "0.12.6"
SHA256 = "8629fdc5eb951daf89a76f68cad12f2f1e03fab0d539150ba3fe6d177d254b7f"
ROOT = Path(__file__).resolve().parent.parent
WORK = ROOT / "native/work"


def run(*args):
    args = [str(arg) for arg in args]
    print(f"+ {shlex.join(args)}", flush=True)
    subprocess.run(args, check=True, cwd=ROOT)


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def configure(source, build, *args):
    run("cmake", "-S", source, "-B", build, "-G", "Ninja", *args)


def build(args):
    host_tag = {"Linux": "linux-x86_64", "Darwin": "darwin-x86_64"}.get(platform.system())
    if host_tag is None:
        raise RuntimeError("Native builds require Linux or macOS")
    toolchain = args.ndk / "build/cmake/android.toolchain.cmake"
    strip = args.ndk / "toolchains/llvm/prebuilt" / host_tag / "bin/llvm-strip"
    if not toolchain.is_file() or not strip.is_file():
        raise RuntimeError(f"Missing Android NDK toolchain: {args.ndk}")
    for tool in ("cmake", "ninja", "make", "cc", "curl", "tar"):
        if not shutil.which(tool):
            raise RuntimeError(f"Missing build tool: {tool}. See README.md for prerequisites.")

    source = WORK / f"neovim-{VERSION}"
    # CMake caches and host generators cannot be shared between host platforms.
    out = WORK / f"build-{VERSION}-{platform.system().lower()}-{platform.machine()}"
    tarball = WORK / f"neovim-{VERSION}.tar.gz"
    if not tarball.exists():
        download = tarball.with_suffix(".tmp")
        run("curl", "--fail", "--location", "--retry", "3",
            f"https://codeload.github.com/neovim/neovim/tar.gz/refs/tags/v{VERSION}", "-o", download)
        if sha256(download) != SHA256:
            raise RuntimeError(f"Neovim checksum mismatch: {download}")
        download.replace(tarball)
    if sha256(tarball) != SHA256:
        raise RuntimeError(f"Neovim checksum mismatch; remove {tarball} and rebuild")

    patch = ROOT / "native/patch-neovim.py"
    patch_hash = sha256(patch)
    stamp = out / "patch-stamp"
    if not source.is_dir() or not stamp.exists() or stamp.read_text() != patch_hash:
        # Rebuild dependencies too: their ExternalProject stamps may hide patch changes.
        if out.exists():
            shutil.rmtree(out)
        if source.exists():
            shutil.rmtree(source)
        run("tar", "-xzf", tarball, "-C", WORK)
        run(sys.executable, patch, source)
        out.mkdir(parents=True)
        stamp.write_text(patch_hash)

    host_deps = out / "host-deps"
    host = out / "host"
    configure(source / "cmake.deps", host_deps, "-DUSE_BUNDLED_TS_PARSERS=OFF")
    run("cmake", "--build", host_deps, "-j", args.jobs)
    configure(source, host, "-DCMAKE_BUILD_TYPE=Release", f"-DDEPS_PREFIX={host_deps}/usr",
              "-DENABLE_LIBINTL=OFF")
    run("cmake", "--build", host, "--target", "nlua0", "-j", args.jobs)

    for abi in args.abis:
        deps = out / f"{args.ndk.name}-{abi}-deps"
        target = out / f"{args.ndk.name}-{abi}"
        android_args = (f"-DCMAKE_TOOLCHAIN_FILE={toolchain}", f"-DANDROID_ABI={abi}",
                        f"-DANDROID_PLATFORM=android-{args.api}")
        configure(source / "cmake.deps", deps, *android_args, "-DUSE_BUNDLED_TS_PARSERS=ON")
        run("cmake", "--build", deps, "-j", args.jobs)
        configure(source, target, *android_args,
                  "-DCMAKE_BUILD_TYPE=Release", f"-DDEPS_PREFIX={deps}/usr",
                  f"-DCMAKE_FIND_ROOT_PATH={deps}/usr", f"-DANDROID_HOST_NLUA0={host}/lib/libnlua0.so",
                  f"-DANDROID_BRIDGE_SOURCE={ROOT}/native/bridge.c",
                  f"-DLUA_PRG={host_deps}/usr/bin/luajit", f"-DLUA_GEN_PRG={host_deps}/usr/bin/luajit",
                  "-DENABLE_LIBINTL=OFF", "-DCOMPILE_LUA=OFF", "-DCMAKE_POSITION_INDEPENDENT_CODE=ON")
        run("cmake", "--build", target, "--target", "neovim", "-j", args.jobs)
        lib_dir = args.output / "jniLibs" / abi
        lib_dir.mkdir(parents=True, exist_ok=True)
        library = lib_dir / "libneovim.so"
        shutil.copyfile(target / "lib/libneovim.so", library)
        run(strip, "--strip-unneeded", library)

    assets = args.output / "assets"
    if assets.exists():
        shutil.rmtree(assets)
    shutil.copytree(source / "runtime", assets / "runtime")
    shutil.copytree(deps / "usr/share/luajit-2.1/jit", assets / "runtime/lua/jit")
    licenses = assets / "licenses"
    licenses.mkdir()
    for original, name in (("src/nvim/vterm/LICENSE", "vterm.txt"),
                           ("src/mpack/LICENSE-MIT", "mpack.txt"),
                           ("src/xdiff/COPYING", "xdiff.txt"), ("LICENSE.txt", "neovim.txt")):
        shutil.copyfile(source / original, licenses / name)
    run(sys.executable, ROOT / "native/copy-licenses.py", deps / "build/src", licenses, source)


def positive_int(value):
    number = int(value)
    if number < 1:
        raise argparse.ArgumentTypeError("must be a positive integer")
    return number


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ndk", type=Path, required=True)
    parser.add_argument("--api", type=positive_int, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--jobs", type=positive_int, default=os.environ.get("NEOVIM_BUILD_JOBS", "8"))
    parser.add_argument("abis", nargs="+", choices=("arm64-v8a", "x86_64"))
    args = parser.parse_args()
    WORK.mkdir(parents=True, exist_ok=True)
    # fcntl is available on both supported hosts; the lock is released even on failure.
    with (WORK / "build.lock").open("w") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        build(args)


if __name__ == "__main__":
    try:
        main()
    except (OSError, RuntimeError, subprocess.CalledProcessError) as error:
        sys.exit(f"Native build failed: {error}")
