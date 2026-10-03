package net.fornwall.androidneovim;

import static org.junit.Assert.*;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Process;
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
import java.util.concurrent.atomic.AtomicReference;
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

    private static String screen(ActivityScenario<MainActivity> activity) {
        AtomicReference<String> text = new AtomicReference<>();
        activity.onActivity(a -> text.set(a.editorView().screenText()));
        return text.get();
    }

    private static Object request(EditorSession session, String method, Object... args) throws Exception {
        AtomicReference<CompletableFuture<Object>> future = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> future.set(session.request(method, args)));
        return future.get().get(10, TimeUnit.SECONDS);
    }

    private interface Condition { boolean get() throws Exception; }

    private static void await(Condition condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
        while (!condition.get()) {
            if (System.nanoTime() > deadline) fail("Timed out waiting for editor state");
            Thread.sleep(25);
        }
    }
}
