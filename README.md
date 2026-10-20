# Android Neovim

An initial native Android Neovim editor. The Android UI and Neovim 0.12.6 run in
one application process: Neovim owns a native thread, and a dedicated Android
`View` draws its `ext_linegrid` events. There is no terminal emulator between them.

## Build

Requires Linux x86-64, JDK 25, CMake 3.24+, Ninja, Make, a host C compiler, Python 3,
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
- **Save** / `:w` writes the current file to the app's private storage. Relative
  paths such as `:e todo.txt` resolve there too.
- **Open** edits a document chosen through Android's picker, and the app also
  accepts text documents and shared text from other apps. Neovim edits a local
  copy. When Android grants write access, each write also updates the original,
  and that grant is persisted. **Export** writes a copy to a destination chosen
  through Android. Documents are limited to 4 MiB.
- The `+` and `*` registers use Android's clipboard: `"+y`, `"+p`, and
  `clipboard=unnamedplus` work without a clipboard tool. **Copy** copies the
  visual selection, or the current line outside visual mode. Neovim writes at most
  512 KiB to the clipboard.
- Touch: tap moves the cursor and shows the keyboard, drag or fling scrolls,
  long-press then drag selects (Visual mode), and pinch changes the font size
  (8–32 sp, remembered). Scroll steps assume the default `mousescroll`. A mouse
  keeps ordinary button, drag and wheel semantics.
- The extra-keys row has sticky **Ctrl** and **Alt**: they modify the next key
  from the row, the soft keyboard, or a hardware keyboard.
- Keyboard, local IME composition, and clipboard paste are supported. **Paste**
  is capped at 64 Ki characters.
- When the app goes to the background, modified named file buffers are written
  and ShaDa (history, registers, marks, `:oldfiles`) is saved. Set
  `vim.g.android_autosave = false` to disable the writes. On the next start the
  most recent file in app storage reopens. Persistent undo is on by default.
- Neovim's `background` follows the system dark/light theme on each attach unless
  `vim.g.android_follow_system_theme = false`.
- Neovim starts normally: `:e $MYVIMRC` edits
  `files/config/nvim/init.lua`, and bundled runtime plugins (`:Tutor`, netrw,
  matchparen, editorconfig, ...) load. The app's defaults (`number`, `mouse`,
  `linebreak`, `undofile`, ...) apply only to options the configuration did not
  set. There is no plugin installer; plugins can be copied under
  `files/config/nvim/pack/`.
- Neovim's bundled Tree-sitter parsers (C, Lua, Markdown, query, Vimscript and
  Vim help) are linked into `libneovim.so`, so help and Markdown buffers get
  Tree-sitter highlighting and the `gO` outline. A `parser/<lang>.so` on the
  runtimepath is still tried first. Other parsers cannot be installed:
  `:TSInstall` needs a compiler and subprocesses.

This is an embedding prototype. `:qa` stops the editor thread without exiting
Android. Neovim's global state is not reinitialized in-process, so **Restart**
then starts a fresh app process. There is no foreground service or
background-survival guarantee. Edits made since the last background save are lost
if Android ends the process; swap files are disabled.

Ordinary subprocess launch paths are disabled, including shell commands, jobs,
and terminal jobs. External language servers and process-based plugin providers
are consequently unavailable, as are runtime features that run tools, such as
`:make`, `:grep`, `:!`, spell-file downloads and compressed-file plugins.
These restrictions are integration boundaries, not a sandbox for untrusted
Lua/FFI code.

The view uses Android's monospace font. It draws backgrounds in runs and, per
highlight, ASCII and other characters that the font draws at the cell width (such as
Latin, Greek and Cyrillic) in text runs scaled to the cell grid. Other clusters, such
as CJK and symbols from fallback fonts, are drawn per cell: centred, or narrowed to
fit when wider than their cells. `linespace` adds space between rows in dp, and a
`guifont` size such as `:set guifont=:h16` sets the font size in sp until it is
cleared; font names are ignored. Cross-cell ligatures, cached row rendering, rich
accessibility navigation, and full IME surrounding-text behavior are future work.
Single, double, dotted and dashed underlines and strikethrough follow the font's
line metrics and use the text color unless `guisp` sets one; undercurl is a wave.

## Architecture

- `native/`: pinned upstream download, cross-build patches, JNI startup, exit and
  subprocess adapters. Process signal handling remains with Android.
- `EditorSession`: app-lifetime native session, private Unix socket transport,
  runtime extraction, UI attachment, clipboard, and byte-preserving input retries.
  Android integration Lua lives in `app/src/main/assets/android/init.lua`.
- `Documents`: local copies of Android documents and write-back to originals.
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

Device tests run under the AndroidX Test Orchestrator with cleared app data:
Neovim cannot restart in-process, so each test method gets a fresh process.
JVM tests cover grid update semantics, MessagePack limits and RPC lifecycle,
including requests from Neovim.
Device tests exercise the real JNI core, Unicode IME input (including input
larger than Neovim's queue), saving, visible redraws, Activity recreation,
same-process execution, rejected subprocesses, runtime plugins, Tree-sitter
parsers and their ftplugins, clipboard
round-trips, background saves, and editor shutdown. UI tests
also cover hardware key notation, sticky modifiers, IME lifecycle, touch gestures,
and accessibility.

The initial device validation uses an API 37 x86-64 emulator. ARM64 is
cross-compiled and its ELF checked; it still needs testing on ARM64 hardware.

Neovim and native dependency license notices are bundled in APK
`assets/licenses/`. Upstream sources remain in the native build cache.
