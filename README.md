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

- Press `i` to insert text and **Esc** to return to normal mode. **Back** also
  sends Esc outside normal mode (`<C-\><C-N>` in terminal mode); in normal mode,
  or when the key did not change the mode, as in Ex mode, it leaves the app as
  usual.
- **Save** / `:w` writes the current file to the app's private storage. Relative
  paths such as `:e todo.txt` resolve there too.
- **Open** edits a document chosen through Android's picker, and the app also
  accepts text documents and shared text from other apps. Neovim edits a local
  copy; opening the same document again reuses it while both have the same
  content. When Android grants write access, each write also updates the
  original, and that grant is persisted. A write stays local, with a notice, if
  the original is read-only or changed elsewhere since it was opened or last
  updated (by content, unless its modification time and size are unchanged).
  **Export** saves changes to the current file, then writes a copy with the same
  name to a destination chosen through Android. Documents are limited to 4 MiB.
  A copy replaced by a newer one, or forgotten after 64 newer documents, is
  deleted at the next start unless it has local changes.
- The `+` and `*` registers use Android's clipboard: `"+y`, `"+p`, and
  `clipboard=unnamedplus` work without a clipboard tool. **Copy** copies the
  visual selection, or the current line outside visual mode. Clipboard text is
  limited to 512 Ki characters in each direction.
- Touch: tap moves the cursor and shows the keyboard, drag or fling scrolls,
  long-press then drag selects (Visual mode), and pinch changes the font size
  (8–32 sp, remembered). Scroll steps assume the default `mousescroll`. A mouse
  keeps ordinary button, drag and wheel semantics, with keyboard modifiers and
  horizontal wheels. Touchpad two-finger swipes scroll and pinches zoom.
- The extra-keys row has sticky **Ctrl** and **Alt**: they modify the next key
  from the row, the soft keyboard, or a hardware keyboard.
- `gx`, `:Open` and `vim.ui.open()` open URLs in an Android app, such as a
  browser; they do not open local files.
- Keyboard, local IME composition, and clipboard paste are supported. **Paste**
  streams large text to Neovim in chunks. Pasted CRLF line breaks, and those read
  by `"+p`, become single line breaks.
- The soft keyboard's Enter is `<CR>`, and multi-line text it commits (clipboard
  suggestions, dictation) is pasted rather than typed, so mappings and
  autoindent do not apply to it. Text still being composed is sent before
  another key, a tap, a sticky modifier, or focus loss. Outside Insert and
  command-line modes the keyboard is asked for plain keys, so a composing
  keyboard does not hold back commands such as `dd`. Replace mode and `r` take
  plain keys too, so CJK keyboards cannot compose there. Android drops what a
  keyboard sends while it restarts, so the switch waits until the keyboard has
  been idle for 150 ms; a key typed at that very moment can still be lost.
- On a hardware keyboard, Ctrl+Shift+letter is `<C-S-x>`, keypad keys are
  `<k0>`–`<k9>`, `<kPlus>`, `<kEnter>` and so on (their characters unless
  mapped), and right Alt is Alt unless the layout gives it characters of its own
  (AltGr), so it is Alt on US, ChromeOS and the default layouts.
- `FocusGained` and `FocusLost` fire when the app window gains or loses input
  focus, for example behind a dialog or when the app goes to the background.
- When the app goes to the background, modified named file buffers are written
  and ShaDa (history, registers, marks, `:oldfiles`) is saved. Set
  `vim.g.android_autosave = false` to keep buffers unwritten; ShaDa is still
  saved. Neovim defers this save while it waits at a prompt, such as "Press
  ENTER" or a `confirm()` dialog, or for the rest of a mapping. On the next start
  the most recent file in app storage reopens. Persistent undo is on by default.
- Neovim's `background` follows the system dark/light theme, also when it changes
  while the editor is open, unless `vim.g.android_follow_system_theme = false`.
- Neovim starts normally: `:e $MYVIMRC` edits
  `files/config/nvim/init.lua`, and bundled runtime plugins (`:Tutor`, netrw,
  matchparen, editorconfig, ...) load. The app's defaults (`number`, `mouse`,
  `linebreak`, `undofile`, ...) apply only to options the configuration did not
  set. There is no plugin installer; plugins can be copied under
  `files/config/nvim/pack/`. If a configuration error makes a setup step fail,
  for example while reopening the last file, later steps still run and the error
  is shown once the editor appears, so the configuration can be fixed.
- Neovim's bundled Tree-sitter parsers (C, Lua, Markdown, query, Vimscript and
  Vim help) are linked into `libneovim.so`, so help and Markdown buffers get
  Tree-sitter highlighting and the `gO` outline. A `parser/<lang>.so` on the
  runtimepath is still tried first. Other parsers cannot be installed:
  `:TSInstall` needs a compiler and subprocesses.

This is an embedding prototype. `:qa` stops the editor thread without exiting
Android, and Lua's `os.exit()` quits the same way, except while the user's own
configuration runs, before the app replaces it: there it ends the editor thread
without Neovim's teardown. Neovim's global state is not reinitialized in-process,
so **Restart** then starts a fresh app process. There is no foreground service or
background-survival guarantee. Edits made since the last background save are lost
if Android ends the process, also while Neovim still defers that save at a prompt
or pending key; swap files are disabled. Temporary files and `stdpath('cache')`
live in Android's cache directory, which Android may clear, so an `undodir` or
`backupdir` placed under `stdpath('cache')` can be lost.

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
fit when wider than their cells. `linespace` adds space between rows in dp. Each
time `guifont` is set, a size such as `:set guifont=:h16` sets the font size in sp,
replacing a pinched size, and no size restores the pinched one; font names are
ignored. Single, double, dotted and dashed underlines and
strikethrough follow the font's line metrics and use the text color unless `guisp`
sets one; undercurl is a wave. Overline is drawn, standout is drawn as reverse
video, and the block cursor is hollow while the editor or its window lacks focus.
The editor takes focus at launch, while the soft keyboard waits for a tap.
Cross-cell ligatures, cached row rendering, rich accessibility navigation, and full
IME surrounding-text behavior are future work.

## Architecture

- `native/`: pinned upstream download, cross-build patches, JNI startup, exit and
  subprocess adapters. The editor thread gets upstream's 8 MiB main-thread stack.
  Process signal handling remains with Android.
- `EditorSession`: app-lifetime native session, private Unix socket transport,
  runtime extraction, UI attachment, and clipboard. `EditorCommands` orders
  requests after earlier input and retries input byte-exactly. Neovim defers most
  requests while it waits at a prompt or for a pending key, so a sent request never
  holds back later input. Unsent UI resizes coalesce. The UI attaches once the view
  is laid out. Rotation and keyboard changes resize it in place; a recreated
  Activity keeps the UI attached, and its new view draws the session's last
  complete frame.
  Android integration Lua lives in `app/src/main/assets/android/init.lua`.
- `Documents`: local copies of Android documents and write-back to originals.
- `rpc/`: bounded MessagePack codec and asynchronous RPC with backpressure. A
  message that cannot be encoded, or a failing notification handler, affects only
  that message.
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
JVM tests cover grid update semantics, key notation, the glyph advance cache,
MessagePack limits and RPC lifecycle, including requests from Neovim, command
ordering, resize coalescing and chunked paste, document change detection, and
tree deletion.
Device tests exercise the real JNI core, Unicode IME input (including input
larger than Neovim's queue), saving, visible redraws, Activity recreation and
focus at launch, same-process execution, rejected subprocesses, ART's blocked
signals (a write to a closed socket), the cache directory and the cleanup of
temporary directories, client info, runtime plugins and updates, Tree-sitter
parsers and their ftplugins, clipboard round-trips (including more lines than
one RPC message may hold, and NUL), CRLF paste, requests at a "Press ENTER"
prompt, deep Vimscript recursion, background saves, window focus events, IME
line breaks and modes, document write-back through a test provider, opening and
reopening files, export, gx and `:Open`, Back, setup errors, and editor
shutdown, including Lua's `os.exit()`. UI tests also cover hardware key
notation, sticky modifiers, IME lifecycle and deletions, mouse modifiers, touch
and touchpad gestures, pinch, fallback glyphs, decorations, `linespace` and
`guifont`, first frames in the system theme, the hollow cursor, and
accessibility.

The initial device validation uses an API 37 x86-64 emulator. ARM64 is
cross-compiled and its ELF checked; it still needs testing on ARM64 hardware.

Neovim and native dependency license notices are bundled in APK
`assets/licenses/`. Upstream sources remain in the native build cache.
