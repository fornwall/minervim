# Android Neovim

An initial native Android Neovim editor. The Android UI and Neovim 0.11.6 run in
one application process: Neovim owns a native thread, and a dedicated Android
`View` draws its `ext_linegrid` events. There is no terminal emulator between them.

## Build

Requires Linux x86-64, JDK 25, CMake, Ninja, Make, a host C compiler, Python 3,
curl, and the Android SDK. Install these pinned SDK components:

```sh
sdkmanager 'platforms;android-37.2' 'build-tools;37.0.0' 'ndk;30.0.16248370'
export ANDROID_HOME="$HOME/Android/Sdk"
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The minimum and target SDK are 37; compilation uses SDK 37.2. NDK 30 compiles the
native code against API 37. Only `arm64-v8a` and `x86_64` are packaged. Both native
libraries use 16 KiB ELF alignment.

The first build downloads checksum-pinned Neovim and its pinned dependencies,
builds host code generators, and cross-compiles both ABIs. Intermediate native
files live in ignored `native/work/`; Gradle's generated libraries and runtime
live under `app/build/generated/neovim/`. `NEOVIM_BUILD_JOBS` controls native
parallelism (default 8). Normal Gradle builds reuse these artifacts.

## Use

- Press `i` to insert text and **Esc** to return to normal mode.
- **Save** / `:w` writes the current file to the app's private storage.
- **Open** imports a document using Android's picker. It edits a local copy;
  **Export** writes a copy to a destination chosen through Android. Import and
  export currently accept files up to 4 MiB.
- Keyboard, local IME composition, touch/mouse input, wheel scrolling, and
  clipboard paste are supported. Paste is capped at 64 Ki characters.
- The default `notes.txt` survives process restarts. The running editor and
  current buffers survive Activity recreation and rotation. Unsaved edits are
  not protected against Android terminating the application process.

This is an embedding prototype. `:qa` stops the editor thread without exiting
Android. Starting another editor after `:qa` currently requires force-stopping
and reopening the app; Neovim's global state is not reinitialized in-process.
There is no foreground service or background-survival guarantee.

Ordinary subprocess launch paths are disabled, including shell commands, jobs,
and terminal jobs. External language servers and process-based plugin providers
are consequently unavailable. The app starts with `-u NONE --noplugin`; it does
not provide plugin installation or a configuration UI. These restrictions are
integration boundaries, not a sandbox for untrusted Lua/FFI code.

The view currently uses Android's monospace font and draws individual cells.
Cross-cell ligatures, cached row rendering, rich accessibility navigation, and
full IME surrounding-text behavior are future work. Underline variants render
as a simple underline. Native Tree-sitter parser modules are not bundled;
the help/Lua/query filetype plugins fall back to their traditional Vimscript
behavior when no parser is available.

## Architecture

- `native/`: pinned upstream download, cross-build patches, JNI startup, exit and
  subprocess adapters. Process signal handling remains with Android.
- `EditorSession`: app-lifetime native session, private Unix socket transport,
  runtime extraction, UI attachment, and byte-preserving input retries.
- `rpc/`: bounded MessagePack codec and asynchronous RPC with backpressure.
- `ui/`: UI-owned grid, complete-frame publication on Neovim `flush`, drawing,
  Android input, and basic accessibility.

The view learns from `android-ssh`'s terminal rendering/input design, but does not
depend on `TerminalSession`, `libterm`, terminal modes, or scrollback.

## Verify

```sh
./gradlew build
ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest
```

JVM tests cover grid update semantics, MessagePack limits and RPC lifecycle.
Device tests exercise the real JNI core, Unicode IME input (including input
larger than Neovim's queue), saving, visible redraws, Activity recreation,
same-process execution, rejected subprocesses, and editor shutdown. UI tests
also cover hardware key notation, IME lifecycle, and accessibility.

The initial device validation uses an API 37 x86-64 emulator. ARM64 is
cross-compiled and its ELF checked; it still needs testing on ARM64 hardware.

Neovim and native dependency license notices are bundled in APK
`assets/licenses/`. Upstream sources remain in the native build cache.
