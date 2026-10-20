package net.fornwall.androidneovim;

import static org.junit.Assert.*;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Process;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Exercises the real JNI core, RPC, Android input and rendered grid together. */
@RunWith(AndroidJUnit4.class)
public final class EditorIntegrationTest {
    @Test
    public void editsSavesRecreatesAndQuitsWithoutKillingAndroid() throws Exception {
        int process = Process.myPid();
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        EditorSession session = ((EditorApplication) context.getApplicationContext()).session();
        try (ActivityScenario<MainActivity> activity = ActivityScenario.launch(MainActivity.class)) {
            await(() -> {
                try { return !((List<?>) request(session, "nvim_list_uis")).isEmpty(); }
                catch (Exception starting) { return false; }
            });
            // Wait until the application's initial file has opened before replacing the test buffer.
            await(() -> String.valueOf(request(session, "nvim_buf_get_name", 0)).endsWith("notes.txt"));
            request(session, "nvim_command", "help");
            assertEquals("help", request(session, "nvim_get_option_value", "filetype", Map.of("buf", 0)));
            request(session, "nvim_command", "bwipeout!");
            Object channelBefore = ((List<?>) request(session, "nvim_get_api_info")).get(0);
            File file = new File(context.getFilesDir(), "instrumented.txt");
            request(session, "nvim_exec_lua", "vim.cmd('enew!'); vim.api.nvim_buf_set_name(0, ...)",
                    List.of(file.getAbsolutePath()));
            activity.onActivity(a -> a.editorView().sendKey("i"));
            await(() -> "i".equals(((Map<?, ?>) request(session, "nvim_get_mode")).get("mode")));
            String text = "héllo <Esc> 世界";
            activity.onActivity(a -> {
                var input = a.editorView().onCreateInputConnection(new EditorInfo());
                assertTrue(input.commitText(text, 1));
            });
            await(() -> List.of(text).equals(request(session, "nvim_buf_get_lines", 0, 0, -1, true)));
            activity.onActivity(a -> a.editorView().sendKey("<Esc>"));
            await(() -> "n".equals(((Map<?, ?>) request(session, "nvim_get_mode")).get("mode")));
            request(session, "nvim_command", "write");
            assertEquals(text + "\n", Files.readString(file.toPath()));
            await(() -> screen(activity).contains(text));

            activity.recreate();
            await(() -> screen(activity).contains(text));
            assertEquals(channelBefore, ((List<?>) request(session, "nvim_get_api_info")).get(0));
            assertEquals(List.of(text), request(session, "nvim_buf_get_lines", 0, 0, -1, true));
            assertEquals(process, Process.myPid());
            assertEquals((long) process, request(session, "nvim_exec_lua", "return vim.uv.os_getpid()", List.of()));

            // Exceed Neovim's input buffer with multibyte text; retries must preserve partial UTF-8.
            request(session, "nvim_command", "enew!");
            activity.onActivity(a -> a.editorView().sendKey("i"));
            await(() -> "i".equals(((Map<?, ?>) request(session, "nvim_get_mode")).get("mode")));
            String longText = "é世".repeat(2000);
            activity.onActivity(a -> assertTrue(a.editorView().onCreateInputConnection(new EditorInfo()).commitText(longText, 1)));
            await(() -> List.of(longText).equals(request(session, "nvim_buf_get_lines", 0, 0, -1, true)));
            activity.onActivity(a -> a.editorView().sendKey("<Esc>"));
            await(() -> "n".equals(((Map<?, ?>) request(session, "nvim_get_mode")).get("mode")));
            request(session, "nvim_exec_lua", "vim.cmd('enew!'); vim.api.nvim_buf_set_name(0, ...)",
                    List.of(new File(context.getFilesDir(), "ordered-input.txt").getAbsolutePath()));
            AtomicReference<CompletableFuture<Object>> saved = new AtomicReference<>();
            activity.onActivity(a -> {
                session.input("i");
                session.input("abc");
                session.paste("XYZ");
                session.input("def<Esc>");
                saved.set(session.request("nvim_command", "write"));
            });
            saved.get().get(10, TimeUnit.SECONDS);
            assertEquals("abcXYZdef\n", Files.readString(new File(context.getFilesDir(), "ordered-input.txt").toPath()));
            for (boolean pty : List.of(false, true)) {
                List<?> result = (List<?>) request(session, "nvim_exec_lua",
                        "return {pcall(vim.fn.jobstart, {'/system/bin/sh', '-c', 'true'}, {pty=...})}", List.of(pty));
                assertTrue("Jobs must not create a subprocess", Boolean.FALSE.equals(result.get(0))
                        || ((Number) result.get(1)).longValue() <= 0);
            }
            List<?> spawn = (List<?>) request(session, "nvim_exec_lua",
                    "local p,e=vim.uv.spawn('/system/bin/sh',{args={'-c','true'}},function() end); "
                            + "return {p==nil,e}", List.of());
            assertEquals(Boolean.TRUE, spawn.get(0));

            // Normal startup loads bundled runtime plugins; relative paths resolve inside app storage.
            assertEquals(2L, request(session, "nvim_eval", "exists(':Tutor')"));
            assertEquals(context.getFilesDir().getCanonicalPath(), request(session, "nvim_call_function", "getcwd", List.of()));

            // Bundled Tree-sitter parsers load from libneovim.so, and the upstream ftplugins use them.
            // Injections resolve built-in parsers that nothing has loaded yet.
            assertEquals(List.of(true, true, true), request(session, "nvim_exec_lua", "vim.cmd('enew!'); "
                    + "assert(not vim._ts_has_language('markdown_inline')); "
                    + "vim.api.nvim_buf_set_lines(0, 0, -1, true, {'# Notes', '', '```lua', 'local x = 1', '```', '', 'Some *text*.'}); "
                    + "vim.bo.filetype = 'markdown'; local parser = vim.treesitter.get_parser(0); parser:parse(true); "
                    + "return {vim.treesitter.highlighter.active[vim.api.nvim_get_current_buf()] ~= nil, "
                    + "parser:children().lua ~= nil, parser:children().markdown_inline ~= nil}", List.of()));
            List<String> parsers = List.of("c", "lua", "markdown", "markdown_inline", "query", "vim", "vimdoc");
            assertEquals(parsers, request(session, "nvim_exec_lua", "return vim.tbl_filter(function(lang) "
                    + "return vim.treesitter.language.add(lang) == true end, {...})", parsers));
            assertEquals(List.of(true, true), request(session, "nvim_exec_lua",
                    "local ok, res = pcall(vim.treesitter.language.add, 'nonexistent'); return {ok, res == nil}", List.of()));
            request(session, "nvim_command", "help");
            assertEquals(List.of(true, 1L), request(session, "nvim_exec_lua",
                    "return {vim.treesitter.highlighter.active[vim.api.nvim_get_current_buf()] ~= nil, "
                            + "vim.fn.maparg('gO', 'n', false, true).buffer}", List.of()));
            request(session, "nvim_command", "bwipeout!");

            // The + register is Android's clipboard in both directions, without a clipboard tool process.
            ClipboardManager clipboard = context.getSystemService(ClipboardManager.class);
            request(session, "nvim_exec_lua", "vim.fn.setreg('+', {'yanked', 'lines'}, 'V')", List.of());
            await(() -> {
                AtomicReference<String> clip = new AtomicReference<>();
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                    ClipData data = clipboard.getPrimaryClip();
                    clip.set(data == null ? null : String.valueOf(data.getItemAt(0).getText()));
                });
                return "yanked\nlines\n".equals(clip.get());
            });
            assertEquals(List.of(List.of("yanked", "lines"), "V"), request(session, "nvim_exec_lua",
                    "return {vim.fn.getreg('+', 1, true), vim.fn.getregtype('+')}", List.of()));
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                    clipboard.setPrimaryClip(ClipData.newPlainText("test", "from android")));
            assertEquals(List.of(List.of("from android"), "v"), request(session, "nvim_exec_lua",
                    "return {vim.fn.getreg('+', 1, true), vim.fn.getregtype('+')}", List.of()));

            // Moving to the background saves modified file buffers.
            File autosaved = new File(context.getFilesDir(), "autosaved.txt");
            request(session, "nvim_exec_lua", "vim.cmd('enew!'); vim.api.nvim_buf_set_name(0, ...); "
                    + "vim.api.nvim_buf_set_lines(0, 0, -1, true, {'kept'})", List.of(autosaved.getAbsolutePath()));
            InstrumentationRegistry.getInstrumentation().runOnMainSync(session::background);
            await(() -> autosaved.isFile() && Files.readString(autosaved.toPath()).equals("kept\n"));

            // Native :qa must tear down its thread and socket, never exit Android's process.
            try { request(session, "nvim_command", "qa!"); } catch (Exception expectedDisconnect) { }
            await(() -> NativeBridge.failure() != null);
            assertEquals(process, Process.myPid());
        }
    }

    @Test
    public void imeFollowsModesAndSendsEnterAndMultiLineText() throws Exception {
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        EditorSession session = ((EditorApplication) context.getApplicationContext()).session();
        try (ActivityScenario<MainActivity> activity = ActivityScenario.launch(MainActivity.class)) {
            awaitUi(session);
            // Autoindent and an insert-mode mapping would mangle multi-line text typed as keys.
            request(session, "nvim_exec_lua", "vim.cmd('enew!'); vim.bo.autoindent = true; "
                    + "vim.keymap.set('i', 'jk', '<Esc>')", List.of());
            // Normal-mode keys are commands, so the keyboard is asked for plain keys instead of composed text.
            await(() -> inputClass(activity) == InputType.TYPE_NULL);
            activity.onActivity(a -> a.editorView().sendKey("i"));
            await(() -> "i".equals(((Map<?, ?>) request(session, "nvim_get_mode")).get("mode")));
            await(() -> inputClass(activity) == InputType.TYPE_CLASS_TEXT);
            activity.onActivity(a -> {
                var input = a.editorView().onCreateInputConnection(new EditorInfo());
                assertTrue(input.commitText("  ab", 1));
                assertTrue(input.commitText("\n", 1));
                assertTrue(input.commitText("jk\n  x\n", 1));
            });
            await(() -> List.of("  ab", "  jk", "  x", "").equals(request(session, "nvim_buf_get_lines", 0, 0, -1, true)));
            assertEquals("i", ((Map<?, ?>) request(session, "nvim_get_mode")).get("mode"));
        }
    }

    @Test
    public void windowFocusChangesReachNeovim() throws Exception {
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        EditorSession session = ((EditorApplication) context.getApplicationContext()).session();
        try (ActivityScenario<MainActivity> activity = ActivityScenario.launch(MainActivity.class)) {
            awaitUi(session);
            request(session, "nvim_exec_lua", "vim.g.focus = ''; vim.api.nvim_create_autocmd({'FocusLost', 'FocusGained'}, "
                    + "{callback = function(event) vim.g.focus = vim.g.focus .. event.event:sub(6, 6) end})", List.of());
            // The window must have focus before a dialog can take it from the editor.
            await(() -> {
                AtomicBoolean focused = new AtomicBoolean();
                activity.onActivity(a -> focused.set(a.hasWindowFocus()));
                return focused.get();
            });
            AtomicReference<Dialog> dialog = new AtomicReference<>();
            activity.onActivity(a -> dialog.set(new AlertDialog.Builder(a).setMessage("Focus").show()));
            await(() -> String.valueOf(request(session, "nvim_get_var", "focus")).endsWith("L"));
            activity.onActivity(a -> dialog.get().dismiss());
            await(() -> String.valueOf(request(session, "nvim_get_var", "focus")).endsWith("LG"));
        }
    }

    @Test
    public void requestsAtAPromptDoNotHoldBackInput() throws Exception {
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        EditorSession session = ((EditorApplication) context.getApplicationContext()).session();
        try (ActivityScenario<MainActivity> activity = ActivityScenario.launch(MainActivity.class)) {
            awaitUi(session);
            File autosaved = new File(context.getFilesDir(), "prompt-autosave.txt");
            request(session, "nvim_exec_lua", "vim.cmd('enew!'); vim.api.nvim_buf_set_name(0, ...); "
                    + "vim.api.nvim_buf_set_lines(0, 0, -1, true, {'kept'})", List.of(autosaved.getAbsolutePath()));
            // At "Press ENTER", Neovim reads keys but defers other requests until the prompt ends.
            activity.onActivity(a -> session.input(":echo \"a\\nb\\nc\"<CR>"));
            await(() -> Boolean.TRUE.equals(((Map<?, ?>) request(session, "nvim_get_mode")).get("blocking")));
            AtomicReference<Integer> columns = new AtomicReference<>();
            activity.onActivity(a -> {
                session.background();
                columns.set(a.editorView().columns() - 1);
                session.resize(columns.get(), a.editorView().rows());
                session.input("<CR>");
            });
            await(() -> autosaved.isFile() && Files.readString(autosaved.toPath()).equals("kept\n"));
            Map<?, ?> mode = (Map<?, ?>) request(session, "nvim_get_mode");
            assertEquals("n", mode.get("mode"));
            assertEquals(false, mode.get("blocking"));
            assertEquals((long) columns.get(), ((Map<?, ?>) ((List<?>) request(session, "nvim_list_uis")).get(0)).get("width"));
        }
    }

    @Test
    public void clipboardWithMoreLinesThanTheRpcItemLimitKeepsTheSession() throws Exception {
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        EditorSession session = ((EditorApplication) context.getApplicationContext()).session();
        ClipboardManager clipboard = context.getSystemService(ClipboardManager.class);
        try (ActivityScenario<MainActivity> activity = ActivityScenario.launch(MainActivity.class)) {
            awaitUi(session);
            request(session, "nvim_exec_lua", "vim.cmd('enew!'); "
                    + "vim.api.nvim_buf_set_lines(0, 0, -1, true, vim.fn['repeat']({''}, 300000)); vim.cmd('%yank +')", List.of());
            await(() -> onMain(() -> {
                ClipData data = clipboard.getPrimaryClip();
                return data != null && data.getItemAt(0).getText().length() == 300_000;
            }));
            assertEquals(300_001L, request(session, "nvim_exec_lua",
                    "vim.cmd('enew!'); vim.cmd('normal! \"+P'); return vim.api.nvim_buf_line_count(0)", List.of()));
            assertFalse(onMain(session::stopped));
            assertEquals(2L, request(session, "nvim_eval", "1+1"));
        }
    }

    @Test
    public void pasteAndPlusRegisterTreatCrlfAsOneLineBreak() throws Exception {
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        EditorSession session = ((EditorApplication) context.getApplicationContext()).session();
        ClipboardManager clipboard = context.getSystemService(ClipboardManager.class);
        try (ActivityScenario<MainActivity> activity = ActivityScenario.launch(MainActivity.class)) {
            awaitUi(session);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                    clipboard.setPrimaryClip(ClipData.newPlainText("test", "a\r\nb")));
            request(session, "nvim_command", "enew!");
            activity.onActivity(a -> a.editorView().pasteClipboard());
            await(() -> List.of("a", "b").equals(request(session, "nvim_buf_get_lines", 0, 0, -1, true)));
            assertEquals(List.of("a", "b"), request(session, "nvim_exec_lua", "return vim.fn.getreg('+', 1, true)", List.of()));

            // Larger text streams in several chunks that still paste as one change.
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                    clipboard.setPrimaryClip(ClipData.newPlainText("test", "line\r\n".repeat(40_000) + "end")));
            request(session, "nvim_command", "enew!");
            activity.onActivity(a -> a.editorView().pasteClipboard());
            await(() -> 40_001L == (Long) request(session, "nvim_buf_line_count", 0));
            assertEquals(List.of("line", "line", "end"), request(session, "nvim_exec_lua",
                    "local b = vim.api.nvim_buf_get_lines(0, 0, -1, true); return {b[1], b[20001], b[#b]}", List.of()));
            request(session, "nvim_command", "silent undo");
            assertEquals(List.of(""), request(session, "nvim_buf_get_lines", 0, 0, -1, true));
        }
    }

    @Test
    public void luaOsExitStopsTheEditorWithoutKillingAndroid() throws Exception {
        int process = Process.myPid();
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        EditorSession session = ((EditorApplication) context.getApplicationContext()).session();
        try (ActivityScenario<MainActivity> activity = ActivityScenario.launch(MainActivity.class)) {
            awaitUi(session);
            File left = new File(context.getFilesDir(), "vim-leave.txt");
            request(session, "nvim_exec_lua", "local path = ...; vim.api.nvim_create_autocmd('VimLeavePre', "
                    + "{callback = function() vim.fn.writefile({'left'}, path) end}); "
                    + "vim.schedule(function() os.exit(3) end)", List.of(left.getAbsolutePath()));
            await(() -> onMain(session::stopped) && onMain(session::status).contains("exited (3)"));
            assertEquals(process, Process.myPid());
            // Only Neovim's own exit path runs VimLeavePre; an exit() that skips it stops the session too.
            assertEquals("left\n", Files.readString(left.toPath()));
        }
    }

    @Test
    public void luaOsExitKeepsTheLowEightBitsOfItsStatus() throws Exception {
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        EditorSession session = ((EditorApplication) context.getApplicationContext()).session();
        try (ActivityScenario<MainActivity> activity = ActivityScenario.launch(MainActivity.class)) {
            awaitUi(session);
            request(session, "nvim_exec_lua", "vim.schedule(function() os.exit(-1) end)", List.of());
            await(() -> onMain(session::stopped) && onMain(session::status).contains("exited (255)"));
        }
    }

    @Test
    public void deepVimscriptRecursionFitsTheEditorStack() throws Exception {
        int process = Process.myPid();
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        EditorSession session = ((EditorApplication) context.getApplicationContext()).session();
        try (ActivityScenario<MainActivity> activity = ActivityScenario.launch(MainActivity.class)) {
            awaitUi(session);
            assertEquals(500L, request(session, "nvim_exec_lua", "vim.o.maxfuncdepth = 1000; vim.cmd([[\n"
                    + "function! AndroidDepth(n)\n  return a:n <= 1 ? 1 : 1 + AndroidDepth(a:n - 1)\nendfunction\n]]); "
                    + "return vim.fn.AndroidDepth(500)", List.of()));
            assertEquals(process, Process.myPid());
        }
    }

    @Test
    public void identifiesItselfToNeovimAsAUi() throws Exception {
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        EditorSession session = ((EditorApplication) context.getApplicationContext()).session();
        try (ActivityScenario<MainActivity> activity = ActivityScenario.launch(MainActivity.class)) {
            awaitUi(session);
            Map<?, ?> client = (Map<?, ?>) ((Map<?, ?>) request(session, "nvim_get_chan_info", 0)).get("client");
            assertEquals("android-neovim", client.get("name"));
            assertEquals("ui", client.get("type"));
        }
    }

    private static int inputClass(ActivityScenario<MainActivity> activity) {
        EditorInfo info = new EditorInfo();
        activity.onActivity(a -> a.editorView().onCreateInputConnection(info));
        return info.inputType & InputType.TYPE_MASK_CLASS;
    }

    /** Waits until the UI is attached and the application's initial file has opened. */
    static void awaitUi(EditorSession session) throws Exception {
        await(() -> {
            try { return !((List<?>) request(session, "nvim_list_uis")).isEmpty(); }
            catch (Exception starting) { return false; }
        });
        await(() -> String.valueOf(request(session, "nvim_buf_get_name", 0)).endsWith("notes.txt"));
    }

    private static <T> T onMain(Supplier<T> supplier) {
        AtomicReference<T> value = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> value.set(supplier.get()));
        return value.get();
    }

    static String screen(ActivityScenario<MainActivity> activity) {
        AtomicReference<String> text = new AtomicReference<>();
        activity.onActivity(a -> text.set(a.editorView().screenText()));
        return text.get();
    }

    static Object request(EditorSession session, String method, Object... args) throws Exception {
        AtomicReference<CompletableFuture<Object>> future = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> future.set(session.request(method, args)));
        return future.get().get(10, TimeUnit.SECONDS);
    }

    interface Condition { boolean get() throws Exception; }

    static void await(Condition condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
        while (!condition.get()) {
            if (System.nanoTime() > deadline) fail("Timed out waiting for editor state");
            Thread.sleep(25);
        }
    }
}
