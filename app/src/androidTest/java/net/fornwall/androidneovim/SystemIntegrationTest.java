package net.fornwall.androidneovim;

import static net.fornwall.androidneovim.EditorIntegrationTest.await;
import static net.fornwall.androidneovim.EditorIntegrationTest.screen;
import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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
            await(() -> screen(activity).replace("\n", "").contains("BrokenConfigMarker"));
            // The multi-line error waits at a hit-enter prompt, which keys still reach.
            assertEquals(Boolean.TRUE, ((Map<?, ?>) request("nvim_get_mode")).get("blocking"));
            request("nvim_input", "<CR>");
            awaitUi();
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

            // Changed elsewhere, to content of the same size: a save stays local.
            writeOriginal(uri, "ex\n");
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
            File newest = documents.importDocument(uri, readWrite).get(10, TimeUnit.SECONDS);
            // Pruning deletes the unchanged forgotten copy, and keeps the one with local changes.
            session.files.submit(documents::prune).get(10, TimeUnit.SECONDS);
            assertEquals("v3\n", Files.readString(local.toPath()));
            assertFalse(reopened.getParentFile().exists());
            assertEquals("ex 2\n", Files.readString(newest.toPath()));

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
