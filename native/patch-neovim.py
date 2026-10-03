#!/usr/bin/env python3
"""Apply the small Android embedding adaptation to the pinned upstream source."""
import pathlib
import sys

source = pathlib.Path(sys.argv[1])

# This prototype does not package native parser modules. Keep the traditional
# Vimscript ftplugins/syntax when an upstream Lua ftplugin requires a parser.
for filetype in ('help', 'lua', 'query'):
    path = source / f'runtime/ftplugin/{filetype}.lua'
    path.write_text("if not vim.treesitter.get_parser(0, nil, { error = false }) then return end\n"
                    + path.read_text())


def replace(file, old, new):
    path = source / file
    text = path.read_text()
    if old not in text:
        raise RuntimeError(f'Patch does not apply: {file}: {old[:80]}')
    path.write_text(text.replace(old, new))


replace('cmake/Deps.cmake', 'if(APPLE)\n', '''if(ANDROID)
  list(APPEND DEPS_CMAKE_ARGS
    -DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}
    -DANDROID_ABI=${ANDROID_ABI}
    -DCMAKE_FIND_ROOT_PATH=${DEPS_INSTALL_DIR}
    -DANDROID_PLATFORM=${ANDROID_PLATFORM}
    -DCMAKE_C_FLAGS=-DLLONG_MAX=__LONG_LONG_MAX__)
endif()
if(APPLE)
''')
replace('cmake.deps/cmake/BuildLuajit.cmake', 'if(UNIX)\n  BuildLuaJit', '''if(ANDROID)
  string(REPLACE "-none" "" android_cc_target "${CMAKE_C_COMPILER_TARGET}")
  BuildLuaJit(INSTALL_COMMAND ${MAKE_PRG} -j8
    HOST_CC=cc
    STRIP=${CMAKE_STRIP}
    CC=${ANDROID_NDK}/toolchains/llvm/prebuilt/linux-x86_64/bin/${android_cc_target}-clang
    "AR=${CMAKE_AR} rcu"
    TARGET_SYS=Linux
    BUILDMODE=static
    XCFLAGS=-fPIC
    PREFIX=${DEPS_INSTALL_DIR}
    install)
elseif(UNIX)
  BuildLuaJit''')
replace('src/nvim/CMakeLists.txt', 'set(LUA_GEN ${LUA_GEN_PRG} ${GENERATOR_PRELOAD} ${PROJECT_SOURCE_DIR} $<TARGET_FILE:nlua0> ${PROJECT_BINARY_DIR})', '''if(ANDROID)
  set(LUA_GEN ${LUA_GEN_PRG} ${GENERATOR_PRELOAD} ${PROJECT_SOURCE_DIR} ${ANDROID_HOST_NLUA0} ${PROJECT_BINARY_DIR})
  list(APPEND gen_cflags --target=${CMAKE_C_COMPILER_TARGET} --sysroot=${CMAKE_SYSROOT})
else()
  set(LUA_GEN ${LUA_GEN_PRG} ${GENERATOR_PRELOAD} ${PROJECT_SOURCE_DIR} $<TARGET_FILE:nlua0> ${PROJECT_BINARY_DIR})
endif()''')
replace('src/nvim/CMakeLists.txt', 'set(LUA_GEN_DEPS ${GENERATOR_PRELOAD} $<TARGET_FILE:nlua0>)', '''if(ANDROID)
  set(LUA_GEN_DEPS ${GENERATOR_PRELOAD} ${ANDROID_HOST_NLUA0})
else()
  set(LUA_GEN_DEPS ${GENERATOR_PRELOAD} $<TARGET_FILE:nlua0>)
endif()''')
# Shared embedding target uses nvim_main but does not require exporting Neovim's internal symbols.
with (source / 'src/nvim/CMakeLists.txt').open('a') as out:
    out.write('''
if(ANDROID)
  add_library(neovim SHARED ${ANDROID_BRIDGE_SOURCE})
  target_compile_definitions(neovim PRIVATE MAKE_LIB)
  target_compile_options(neovim PRIVATE -fvisibility=hidden)
  target_link_libraries(neovim PRIVATE main_lib libuv log)
  target_link_options(neovim PRIVATE -Wl,-Bsymbolic -Wl,--wrap=exit -Wl,--wrap=_exit -Wl,--wrap=fork -Wl,--wrap=forkpty -Wl,--wrap=system -Wl,--wrap=popen -Wl,-z,max-page-size=16384)
endif()
''')
# Android owns process signals. Leaving Neovim's watchers alive but unstarted makes
# their normal teardown safe without replacing ART's process-wide signal handlers.
replace('src/nvim/os/signal.c', '  signal_start();', '''#ifndef __ANDROID__
  signal_start();
#endif''')
replace('src/nvim/os/signal.c', 'void signal_start(void)\n{', '''void signal_start(void)
{
#ifdef __ANDROID__
  return;
#endif''')

replace('src/nvim/CMakeLists.txt', 'elseif (NOT CMAKE_SYSTEM_NAME STREQUAL "SunOS")', 'elseif (NOT ANDROID AND NOT CMAKE_SYSTEM_NAME STREQUAL "SunOS")')

# Reject PTYs before Neovim changes process-wide SIGCHLD/stdio state.
replace('src/nvim/os/pty_proc_unix.c',
        'int pty_proc_spawn(PtyProc *ptyproc)\n  FUNC_ATTR_NONNULL_ALL\n{',
        '''int pty_proc_spawn(PtyProc *ptyproc)
  FUNC_ATTR_NONNULL_ALL
{
#ifdef __ANDROID__
  return UV_ENOSYS;
#endif''')


# uv_spawn initializes its handle even on errors. Neovim and luv both uv_close()
# that handle after a failed spawn, so rejecting at the linker boundary leaves
# them an invalid handle. Reject inside libuv after its normal initialization.
replace('cmake.deps/cmake/BuildLibuv.cmake',
        '  DOWNLOAD_DIR ${DEPS_DOWNLOAD_DIR}/libuv',
        '''  DOWNLOAD_DIR ${DEPS_DOWNLOAD_DIR}/libuv
  PATCH_COMMAND ${CMAKE_COMMAND}
    -DLIBUV_PROCESS_SOURCE=${DEPS_BUILD_DIR}/src/libuv/src/unix/process.c
    -P ${CMAKE_CURRENT_SOURCE_DIR}/cmake/PatchAndroidLibuv.cmake''')
(source / 'cmake.deps/cmake/PatchAndroidLibuv.cmake').write_text('''file(READ "${LIBUV_PROCESS_SOURCE}" contents)
if(NOT contents MATCHES "ANDROID_NEOVIM_NO_SUBPROCESSES")
  set(before "  uv__queue_init(&process->queue);\n  process->status = 0;")
  string(FIND "${contents}" "${before}" position)
  if(position EQUAL -1)
    message(FATAL_ERROR "Pinned libuv Android spawn patch no longer applies")
  endif()
  set(after "${before}\n#ifdef __ANDROID__\n  /* ANDROID_NEOVIM_NO_SUBPROCESSES: preserve failed-spawn handle semantics. */\n  return UV_ENOSYS;\n#endif")
  string(REPLACE "${before}" "${after}" contents "${contents}")
  file(WRITE "${LIBUV_PROCESS_SOURCE}" "${contents}")
endif()
''')
