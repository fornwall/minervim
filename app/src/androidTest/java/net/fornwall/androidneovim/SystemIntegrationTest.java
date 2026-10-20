package net.fornwall.androidneovim;

import static net.fornwall.androidneovim.EditorIntegrationTest.await;
import static net.fornwall.androidneovim.EditorIntegrationTest.screen;
import static org.junit.Assert.*;

import android.content.Context;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Android integration: startup, configuration, Activity lifecycle and documents. */
@RunWith(AndroidJUnit4.class)
public final class SystemIntegrationTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final EditorSession session = ((EditorApplication) context.getApplicationContext()).session();

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
