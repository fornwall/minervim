package net.fornwall.androidneovim;

import static net.fornwall.androidneovim.EditorIntegrationTest.await;
import static net.fornwall.androidneovim.EditorIntegrationTest.screen;
import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.MimeTypeMap;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Android integration: startup, configuration, Activity lifecycle and documents. */
@RunWith(AndroidJUnit4.class)
public final class SystemIntegrationTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final EditorSession session = ((EditorApplication) context.getApplicationContext()).session();

    @Test
    public void setupErrorsStillAttachTheUiAndShowTheError() throws Exception {
        // Neovim starts with the first Activity, so a configuration written now is read at startup.
        configure("vim.api.nvim_create_autocmd('BufReadPost', { pattern = '*/notes.txt', "
                + "callback = function() error('BrokenConfigMarker') end })");
        try (ActivityScenario<MainActivity> activity = ActivityScenario.launch(MainActivity.class)) {
            // The error waits at a hit-enter prompt, which keys still reach.
            await(() -> {
                try { return Boolean.TRUE.equals(((Map<?, ?>) request("nvim_get_mode")).get("blocking")); }
                catch (Exception starting) { return false; }
            });
            await(() -> screen(activity).replace("\n", "").contains("Android setup"));
            request("nvim_input", "<CR>");
            awaitUi();
            // However the screen wrapped the error, :messages has all of it.
            assertTrue(((String) request("nvim_exec_lua", "return vim.fn.execute('messages')", List.of()))
                    .contains("BrokenConfigMarker"));
            assertTrue(String.valueOf(request("nvim_buf_get_name", 0)).endsWith("notes.txt"));
            // Steps after the failing one still ran, and documents can be opened.
            assertEquals(context.getFilesDir().getCanonicalPath(), request("nvim_call_function", "getcwd", List.of()));
            File other = new File(context.getFilesDir(), "other.txt");
            Files.writeString(other.toPath(), "other\n");
            session.edit(other).get(10, TimeUnit.SECONDS);
            assertEquals(List.of("other"), request("nvim_buf_get_lines", 0, 0, -1, true));
        }
    }

    @Test
    public void recreationKeepsTheAttachedUiAndItsWindowLayout() throws Exception {
        configure("vim.g.resized = 0\n"
                + "vim.api.nvim_create_autocmd('VimResized', { callback = function() vim.g.resized = vim.g.resized + 1 end })\n"
                + "vim.api.nvim_create_autocmd('UIEnter', { callback = function() "
                + "vim.g.attached = { vim.api.nvim_list_uis()[1].width, vim.o.columns, vim.o.lines } end })");
        try (ActivityScenario<MainActivity> activity = ActivityScenario.launch(MainActivity.class)) {
            awaitUi();
            AtomicReference<List<Long>> size = new AtomicReference<>();
            activity.onActivity(a -> size.set(List.of((long) a.editorView().columns(), (long) a.editorView().rows())));
            // The UI attached at the laid-out size, not at a placeholder size corrected by a resize.
            assertEquals(List.of(size.get().get(0), size.get().get(0), size.get().get(1)), request("nvim_get_var", "attached"));
            // Uneven splits: a shrink and regrow would move rows and columns to the bottom and right windows.
            String layout = "return vim.tbl_map(function(w) return { vim.api.nvim_win_get_width(w), "
                    + "vim.api.nvim_win_get_height(w) } end, vim.api.nvim_tabpage_list_wins(0))";
            request("nvim_exec_lua", "vim.cmd.vsplit(); vim.api.nvim_win_set_width(0, vim.o.columns - 12); "
                    + "vim.cmd.split(); vim.api.nvim_win_set_height(0, vim.o.lines - 12)", List.of());
            Object windows = request("nvim_exec_lua", layout, List.of());
            Object resized = request("nvim_get_var", "resized");
            String before = stableScreen(activity);
            assertTrue(before.contains("notes.txt"));

            activity.recreate();
            // The new view draws the last complete frame before any redraw arrives.
            assertEquals(before, screen(activity));
            assertEquals(1, ((List<?>) request("nvim_list_uis")).size());
            assertEquals(windows, request("nvim_exec_lua", layout, List.of()));
            assertEquals(resized, request("nvim_get_var", "resized"));
        }
    }

    @Test
    public void recreationKeepsAFontSizeSetSinceTheLastGuifont() throws Exception {
        configure("vim.g.resized = 0\n"
                + "vim.api.nvim_create_autocmd('VimResized', { callback = function() vim.g.resized = vim.g.resized + 1 end })");
        try (ActivityScenario<MainActivity> activity = ActivityScenario.launch(MainActivity.class)) {
            awaitUi();
            request("nvim_set_option_value", "guifont", ":h12", Map.of());
            await(() -> fontSize(activity) == 12);
            // As a pinch does when it ends.
            activity.onActivity(a -> a.editorView().setFontSize(20));
            String size = "return { vim.o.columns, vim.o.lines }";
            await(() -> request("nvim_exec_lua", size, List.of()).equals(List.of(
                    (long) onMain(activity, a -> a.editorView().columns()), (long) onMain(activity, a -> a.editorView().rows()))));
            Object resized = request("nvim_get_var", "resized");

            activity.recreate();
            assertEquals(20, fontSize(activity), 0);
            stableScreen(activity);
            assertEquals(resized, request("nvim_get_var", "resized"));
        }
    }

    private static float fontSize(ActivityScenario<MainActivity> activity) {
        return onMain(activity, a -> a.editorView().fontSize());
    }

    private static <T> T onMain(ActivityScenario<MainActivity> activity, Function<MainActivity, T> function) {
        AtomicReference<T> result = new AtomicReference<>();
        activity.onActivity(a -> result.set(function.apply(a)));
        return result.get();
    }

    @Test
    public void writesBackOnlyToWritableOriginalsThatDidNotChangeElsewhere() throws Exception {
        int readWrite = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        Documents documents = session.documents;
        Uri uri = TestDocumentProvider.uri("original-" + System.nanoTime() + ".txt");
        writeOriginal(uri, "v1\n");
        try (ActivityScenario<MainActivity> activity = ActivityScenario.launch(MainActivity.class)) {
            awaitUi();
            File local = documents.importDocument(uri, readWrite).get(10, TimeUnit.SECONDS);
            assertEquals("v1\n", Files.readString(local.toPath()));
            save(local, "v2");
            await(() -> readOriginal(uri).equals("v2\n"));
            // Reopening an original keeps its copy while both have the same content.
            assertEquals(local.getCanonicalFile(), documents.importDocument(uri, readWrite).get(10, TimeUnit.SECONDS).getCanonicalFile());
            // Providers can update the reported time later; the content still matches, so a save updates.
            touchOriginal(uri, 1_000_000_000_000L);
            assertEquals(Documents.WriteBack.UPDATED, documents.written(local).get(10, TimeUnit.SECONDS));

            // Changed elsewhere, to content of the same size and with a distinct time: a save stays local.
            writeOriginal(uri, "ex\n");
            touchOriginal(uri, 946_684_800_000L);
            save(local, "v3");
            assertEquals(Documents.WriteBack.CHANGED_ELSEWHERE, documents.written(local).get(10, TimeUnit.SECONDS));
            assertEquals("ex\n", readOriginal(uri));
            assertEquals("v3\n", Files.readString(local.toPath()));

            // Reopening the changed original makes a new copy, and the old one no longer writes back.
            File reopened = documents.importDocument(uri, readWrite).get(10, TimeUnit.SECONDS);
            assertNotEquals(local.getCanonicalFile(), reopened.getCanonicalFile());
            assertEquals("ex\n", Files.readString(reopened.toPath()));
            assertEquals(Documents.WriteBack.FORGOTTEN, documents.written(local).get(10, TimeUnit.SECONDS));
            writeOriginal(uri, "ex 2\n");
            File forgotten = documents.importDocument(uri, readWrite).get(10, TimeUnit.SECONDS);
            writeOriginal(uri, "ex 3\n");
            File newest = documents.importDocument(uri, readWrite).get(10, TimeUnit.SECONDS);
            // A file that the user saved beside a copy, as with :saveas %:h/other.txt.
            File beside = new File(reopened.getParentFile(), "other.txt");
            Files.writeString(beside.toPath(), "kept\n");
            // Pruning deletes unchanged forgotten copies, with their directories once empty, and keeps the
            // copy with local changes.
            session.files.submit(documents::prune).get(10, TimeUnit.SECONDS);
            assertEquals("v3\n", Files.readString(local.toPath()));
            assertFalse(reopened.exists());
            assertEquals("kept\n", Files.readString(beside.toPath()));
            assertFalse(forgotten.getParentFile().exists());
            assertEquals("ex 3\n", Files.readString(newest.toPath()));

            Uri readOnly = TestDocumentProvider.uri("read-only-" + System.nanoTime() + ".txt");
            writeOriginal(readOnly, "ro\n");
            File copy = documents.importDocument(readOnly, Intent.FLAG_GRANT_READ_URI_PERMISSION).get(10, TimeUnit.SECONDS);
            save(copy, "changed");
            assertEquals(Documents.WriteBack.READ_ONLY, documents.written(copy).get(10, TimeUnit.SECONDS));
            assertEquals("ro\n", readOriginal(readOnly));
            // Only content URIs are imported, never files such as the app's own storage.
            File own = new File(context.getFilesDir(), "notes.txt");
            ExecutionException rejected = assertThrows(ExecutionException.class,
                    () -> documents.importDocument(Uri.fromFile(own), readWrite).get(10, TimeUnit.SECONDS));
            assertTrue(rejected.getCause().getMessage(), rejected.getCause().getMessage().contains("content"));
        }
    }

    @Test
    public void runtimeUpdateReplacesTheOldTreeAndItsHelpTags() throws Exception {
        // A runtime extracted by an earlier version, with a file that the update removed.
        File runtime = new File(context.getFilesDir(), "runtime");
        Files.createDirectories(new File(runtime, "doc").toPath());
        Files.createDirectories(new File(runtime, "plugin").toPath());
        Files.writeString(new File(runtime, "doc/tags").toPath(), "stale-topic\tstale.txt\t/*stale-topic*\n");
        Files.writeString(new File(runtime, "plugin/stale.lua").toPath(), "vim.g.stale_plugin = true\n");
        Files.writeString(new File(context.getFilesDir(), "runtime-version").toPath(), "0");
        try (ActivityScenario<MainActivity> activity = ActivityScenario.launch(MainActivity.class)) {
            awaitUi();
            assertFalse(new File(runtime, "plugin/stale.lua").exists());
            assertEquals(0L, request("nvim_eval", "exists('g:stale_plugin')"));
            assertEquals(List.of(false, true), request("nvim_exec_lua", "local stale = pcall(vim.cmd.help, 'stale-topic'); "
                    + "local current = pcall(vim.cmd.help, 'vim.ui.open()'); return {stale, current}", List.of()));
            assertEquals("help", request("nvim_get_option_value", "filetype", Map.of("buf", 0)));
        }
    }

    @Test
    public void uiOpenViewsUrlsWithAndroidApps() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        List<Intent> started = new CopyOnWriteArrayList<>();
        Instrumentation.ActivityMonitor monitor = new Instrumentation.ActivityMonitor() {
            @Override public Instrumentation.ActivityResult onStartActivity(Intent intent) {
                if (!Intent.ACTION_VIEW.equals(intent.getAction())) return null;
                started.add(new Intent(intent));
                return new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null);
            }
        };
        instrumentation.addMonitor(monitor);
        try (ActivityScenario<MainActivity> activity = ActivityScenario.launch(MainActivity.class)) {
            awaitUi();
            // gx waits for the opener and reports a nonzero exit code as an error.
            assertEquals(List.of(0L, true), request("nvim_exec_lua", "local cmd, err = vim.ui.open('https://example.com/open'); "
                    + "return {cmd:wait(1000).code, err == nil}", List.of()));
            request("nvim_exec_lua", "vim.cmd.enew(); vim.api.nvim_buf_set_lines(0, 0, -1, true, {'see https://example.com/gx'}); "
                    + "vim.api.nvim_win_set_cursor(0, {1, 8}); vim.cmd.normal('gx')", List.of());
            request("nvim_command", "Open https://example.com/command");
            await(() -> started.size() == 3);
            for (Intent intent : started) assertTrue(intent.hasCategory(Intent.CATEGORY_BROWSABLE));
            assertEquals(List.of("https://example.com/open", "https://example.com/gx", "https://example.com/command"),
                    started.stream().map(intent -> intent.getData().toString()).toList());
            assertEquals(List.of(true, true), request("nvim_exec_lua", "local cmd, err = vim.ui.open('notes.txt'); "
                    + "return {cmd == nil, err:find('not files', 1, true) ~= nil}", List.of()));
            // Without a view, as after the Activity finished, the URL opens in a new task.
            activity.onActivity(a -> session.detach(a.editorView(), false));
            request("nvim_exec_lua", "vim.ui.open('https://example.com/detached')", List.of());
            await(() -> started.size() == 4);
            assertEquals("https://example.com/detached", started.get(3).getData().toString());
            assertNotEquals(0, started.get(3).getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK);
        } finally {
            instrumentation.removeMonitor(monitor);
        }
    }

    @Test
    public void exportSavesChangesFirstAndKeepsTheFileExtension() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        Uri destination = TestDocumentProvider.uri("export-" + System.nanoTime());
        List<Intent> started = new CopyOnWriteArrayList<>();
        Instrumentation.ActivityMonitor monitor = new Instrumentation.ActivityMonitor() {
            @Override public Instrumentation.ActivityResult onStartActivity(Intent intent) {
                if (!Intent.ACTION_CREATE_DOCUMENT.equals(intent.getAction())) return null;
                started.add(new Intent(intent));
                return new Instrumentation.ActivityResult(Activity.RESULT_OK, new Intent().setData(destination));
            }
        };
        instrumentation.addMonitor(monitor);
        try (ActivityScenario<MainActivity> activity = ActivityScenario.launch(MainActivity.class)) {
            awaitUi();
            File file = new File(context.getFilesDir(), "init.lua");
            request("nvim_exec_lua", "vim.cmd.edit(vim.fn.fnameescape(...)); vim.api.nvim_buf_set_lines(0, 0, -1, true, {'return 1'})",
                    List.of(file.getAbsolutePath()));
            clickExport(activity);
            await(() -> {
                try { return readOriginal(destination).equals("return 1\n"); }
                catch (IOException notYet) { return false; }
            });
            assertEquals("return 1\n", Files.readString(file.toPath()));
            assertEquals("init.lua", started.get(0).getStringExtra(Intent.EXTRA_TITLE));
            // A text/plain type would make providers save init.lua.txt.
            String type = MimeTypeMap.getSingleton().getMimeTypeFromExtension("lua");
            assertEquals(type == null ? "application/octet-stream" : type, started.get(0).getType());

            // An unmodified file is exported without writing it.
            assertTrue(file.setLastModified(946_684_800_000L));
            writeOriginal(destination, "");
            clickExport(activity);
            await(() -> readOriginal(destination).equals("return 1\n"));
            assertEquals(946_684_800_000L, file.lastModified());
        } finally {
            instrumentation.removeMonitor(monitor);
        }
    }

    private static void clickExport(ActivityScenario<MainActivity> activity) {
        activity.onActivity(a -> {
            ArrayList<View> buttons = new ArrayList<>();
            a.getWindow().getDecorView().findViewsWithText(buttons, "Export a copy of the current file",
                    View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);
            assertTrue(buttons.get(0).performClick());
        });
    }

    @Test
    public void backLeavesInsertModeAndKeepsItsDefaultInNormalMode() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        try (ActivityScenario<MainActivity> activity = ActivityScenario.launch(MainActivity.class)) {
            awaitUi();
            assertFalse(backEscapes(activity));
            request("nvim_input", "i");
            await(() -> backEscapes(activity));
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            await(() -> "n".equals(((Map<?, ?>) request("nvim_get_mode")).get("mode")));
            await(() -> !backEscapes(activity));
            assertEquals(Lifecycle.State.RESUMED, activity.getState());

            // Terminal mode passes <Esc> to the terminal, so Back sends <C-\><C-N>.
            request("nvim_exec_lua", "vim.cmd.enew(); vim.api.nvim_open_term(0, {}); vim.cmd.startinsert()", List.of());
            await(() -> "t".equals(((Map<?, ?>) request("nvim_get_mode")).get("mode")));
            await(() -> backEscapes(activity));
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            await(() -> "nt".equals(((Map<?, ?>) request("nvim_get_mode")).get("mode")));
            await(() -> !backEscapes(activity));

            // Ex mode ignores <Esc>; without a mode change, the next Back goes to Android.
            request("nvim_input", "gQ");
            await(() -> "cv".equals(((Map<?, ?>) request("nvim_get_mode")).get("mode")));
            await(() -> backEscapes(activity));
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            await(() -> !backEscapes(activity));
            assertEquals("cv", ((Map<?, ?>) request("nvim_get_mode")).get("mode"));
            assertEquals(Lifecycle.State.RESUMED, activity.getState());
        }
    }

    private static boolean backEscapes(ActivityScenario<MainActivity> activity) {
        AtomicReference<Boolean> escapes = new AtomicReference<>();
        activity.onActivity(a -> escapes.set(a.backEscapes()));
        return escapes.get();
    }

    @Test
    public void startupRemovesTemporaryDirectoriesOfEarlierProcesses() throws Exception {
        File stale = new File(context.getCacheDir(), "nvim.user/AbCdEf");
        // Earlier versions kept TMPDIR and XDG_CACHE_HOME in files/tmp, which users may also save to.
        File legacy = new File(context.getFilesDir(), "tmp/nvim.user/AbCdEf");
        File legacyCache = new File(context.getFilesDir(), "tmp/nvim/luac");
        File saved = new File(context.getFilesDir(), "tmp/todo.txt");
        for (File directory : List.of(stale, legacy, legacyCache)) {
            Files.createDirectories(directory.toPath());
            Files.writeString(new File(directory, "0").toPath(), "stale");
        }
        Files.writeString(saved.toPath(), "kept");
        try (ActivityScenario<MainActivity> activity = ActivityScenario.launch(MainActivity.class)) {
            awaitUi();
            assertFalse(stale.getParentFile().exists());
            assertFalse(legacy.getParentFile().exists());
            assertFalse(legacyCache.getParentFile().exists());
            assertEquals("kept", Files.readString(saved.toPath()));
            String temporary = (String) request("nvim_call_function", "tempname", List.of());
            assertTrue(temporary, temporary.startsWith(new File(context.getCacheDir(), "nvim.").getPath())
                    || temporary.startsWith(new File(context.getCacheDir(), "nvim.").getCanonicalPath()));
        }
    }

    @Test
    public void editorHasFocusAfterLaunchAndRecreationWithoutShowingTheKeyboard() throws Exception {
        try (ActivityScenario<MainActivity> activity = ActivityScenario.launch(MainActivity.class)) {
            awaitUi();
            // A focused editor in a focused window draws a solid block cursor.
            await(() -> editorFocused(activity));
            Thread.sleep(1000);
            AtomicReference<Boolean> keyboard = new AtomicReference<>();
            activity.onActivity(a -> keyboard.set(a.getWindow().getDecorView().getRootWindowInsets().isVisible(WindowInsets.Type.ime())));
            assertFalse(keyboard.get());
            activity.recreate();
            await(() -> editorFocused(activity));
        }
    }

    private static boolean editorFocused(ActivityScenario<MainActivity> activity) {
        AtomicReference<Boolean> focused = new AtomicReference<>();
        activity.onActivity(a -> focused.set(a.editorView().isFocused() && a.editorView().hasWindowFocus()));
        return focused.get();
    }

    @Test
    public void reopeningADocumentWithUnsavedChangesKeepsThem() throws Exception {
        int readWrite = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        Uri uri = TestDocumentProvider.uri("unsaved-" + System.nanoTime() + ".txt");
        writeOriginal(uri, "saved\n");
        try (ActivityScenario<MainActivity> activity = ActivityScenario.launch(MainActivity.class)) {
            awaitUi();
            File copy = session.documents.importDocument(uri, readWrite).get(10, TimeUnit.SECONDS);
            session.edit(copy).get(10, TimeUnit.SECONDS);
            request("nvim_buf_set_lines", 0, 0, -1, true, List.of("unsaved"));
            // The copy still has the original's content, so it is reused, and its buffer is shown as it is.
            File reopened = session.documents.importDocument(uri, readWrite).get(10, TimeUnit.SECONDS);
            assertEquals(copy.getCanonicalFile(), reopened.getCanonicalFile());
            session.edit(reopened).get(10, TimeUnit.SECONDS);
            assertEquals(List.of("unsaved"), request("nvim_buf_get_lines", 0, 0, -1, true));
            assertEquals(Boolean.TRUE, request("nvim_get_option_value", "modified", Map.of("buf", 0)));
        }
    }

    @Test
    public void openingAFileIgnoresWildignoreAndKeepsTheArgumentList() throws Exception {
        try (ActivityScenario<MainActivity> activity = ActivityScenario.launch(MainActivity.class)) {
            awaitUi();
            File lock = new File(context.getFilesDir(), "yarn.lock");
            Files.writeString(lock.toPath(), "lockfile\n");
            File script = new File(context.getFilesDir(), "script.lua");
            Files.writeString(script.toPath(), "return 1\n");
            request("nvim_exec_lua", "vim.o.wildignore = '*.lock'; vim.cmd.args(vim.fn.fnameescape(...))",
                    List.of(script.getAbsolutePath()));
            session.edit(lock).get(10, TimeUnit.SECONDS);
            assertEquals(List.of("lockfile"), request("nvim_buf_get_lines", 0, 0, -1, true));
            session.edit(script).get(10, TimeUnit.SECONDS);
            // Loaded as :edit would, with filetype detection, and listed.
            assertEquals(List.of("lua", true, 1L), request("nvim_exec_lua",
                    "return {vim.bo.filetype, vim.bo.buflisted, vim.fn.argc()}", List.of()));
        }
    }

    @Test
    public void backgroundSavesHistoryWhenAutosaveIsOff() throws Exception {
        try (ActivityScenario<MainActivity> activity = ActivityScenario.launch(MainActivity.class)) {
            awaitUi();
            File file = new File(context.getFilesDir(), "unsaved.txt");
            File shada = new File((String) request("nvim_exec_lua", "vim.g.android_autosave = false; "
                    + "vim.cmd.enew(); vim.api.nvim_buf_set_name(0, ...); vim.api.nvim_buf_set_lines(0, 0, -1, true, {'kept'}); "
                    + "return vim.fn.stdpath('state') .. '/shada/main.shada'", List.of(file.getAbsolutePath())));
            assertFalse(shada.exists());
            InstrumentationRegistry.getInstrumentation().runOnMainSync(session::background);
            // Neovim runs requests in order, so this one returns after the background save.
            request("nvim_eval", "1");
            assertTrue(shada.isFile());
            assertFalse(file.exists());
        }
    }

    @Test
    public void plusRegisterKeepsNulCharacters() throws Exception {
        ClipboardManager clipboard = context.getSystemService(ClipboardManager.class);
        try (ActivityScenario<MainActivity> activity = ActivityScenario.launch(MainActivity.class)) {
            awaitUi();
            // A register line, as the clipboard provider sees it, represents NUL as "\n".
            request("nvim_exec_lua", "vim.cmd.enew(); vim.api.nvim_buf_set_lines(0, 0, -1, true, {'a\\0b'}); "
                    + "vim.cmd('normal! \"+yy')", List.of());
            await(() -> {
                AtomicReference<String> text = new AtomicReference<>();
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                    ClipData data = clipboard.getPrimaryClip();
                    text.set(data == null ? null : String.valueOf(data.getItemAt(0).getText()));
                });
                return text.get() != null && text.get().startsWith("a\u0000b");
            });
            request("nvim_command", "normal! \"+p");
            assertEquals(List.of("a\u0000b", "a\u0000b"), request("nvim_buf_get_lines", 0, 0, -1, true));
        }
    }

    private void save(File file, String line) throws Exception {
        request("nvim_exec_lua", "local path, line = ...; vim.cmd.edit(vim.fn.fnameescape(path)); "
                + "vim.api.nvim_buf_set_lines(0, 0, -1, true, {line}); vim.cmd.write()", List.of(file.getAbsolutePath(), line));
    }

    private String readOriginal(Uri uri) throws Exception {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void writeOriginal(Uri uri, String text) throws Exception {
        try (OutputStream out = context.getContentResolver().openOutputStream(uri, "wt")) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private void touchOriginal(Uri uri, long time) {
        Bundle extras = new Bundle();
        extras.putLong("time", time);
        context.getContentResolver().call(uri, "touch", uri.getLastPathSegment(), extras);
    }

    private void configure(String lua) throws Exception {
        File config = new File(context.getFilesDir(), "config/nvim/init.lua");
        Files.createDirectories(config.toPath().getParent());
        Files.writeString(config.toPath(), lua + "\n");
    }

    private void awaitUi() throws Exception { EditorIntegrationTest.awaitUi(session); }

    private Object request(String method, Object... args) throws Exception {
        return EditorIntegrationTest.request(session, method, args);
    }

    /** The screen once redraws have settled. */
    private static String stableScreen(ActivityScenario<MainActivity> activity) throws Exception {
        AtomicReference<String> last = new AtomicReference<>("");
        await(() -> {
            String previous = last.get();
            Thread.sleep(200);
            last.set(screen(activity));
            return !previous.isEmpty() && previous.equals(last.get());
        });
        return last.get();
    }
}
