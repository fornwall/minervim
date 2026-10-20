package net.fornwall.androidneovim;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.Test;

public class EditorCommandsTest {
    private static final class Fake {
        final List<String> methods = new ArrayList<>();
        final List<Object[]> arguments = new ArrayList<>();
        final List<CompletableFuture<Object>> calls = new ArrayList<>();
        final ArrayDeque<CompletableFuture<Object>> replies = new ArrayDeque<>();
        final ArrayDeque<Runnable> retries = new ArrayDeque<>();
        final EditorCommands commands = new EditorCommands((method, args) -> {
            methods.add(method);
            arguments.add(args);
            CompletableFuture<Object> reply = new CompletableFuture<>();
            calls.add(reply);
            replies.add(reply);
            return reply;
        }, Runnable::run, retries::add);

        /** Answers the oldest unanswered call. */
        void reply(Object value) { replies.remove().complete(value); }

        /** Answers a call by its index in {@link #methods}, as Neovim may for a deferred request. */
        void answer(int call, Object value) {
            replies.remove(calls.get(call));
            calls.get(call).complete(value);
        }
    }

    @Test public void pasteAndSaveStayBehindEarlierInput() {
        Fake fake = new Fake();
        fake.commands.input("i");
        fake.commands.input("abc");
        CompletableFuture<Object> paste = fake.commands.request("nvim_paste", "XYZ", true, -1);
        CompletableFuture<Object> save = fake.commands.request("nvim_command", "write");
        assertEquals(List.of("nvim_input"), fake.methods);
        fake.reply(1L);
        assertEquals(List.of("nvim_input", "nvim_input"), fake.methods);
        assertArrayEquals("abc".getBytes(StandardCharsets.UTF_8), (byte[]) fake.arguments.get(1)[0]);
        fake.reply(3L);
        // Neovim runs one channel's requests in order, so they need not wait for each other's replies.
        assertEquals(List.of("nvim_input", "nvim_input", "nvim_paste", "nvim_command"), fake.methods);
        fake.reply(true);
        assertEquals(true, paste.join());
        fake.reply(null);
        assertTrue(save.isDone());
    }

    @Test public void unansweredRequestDoesNotHoldBackLaterInput() {
        // At "Press ENTER", Neovim accepts input and answers nvim_get_mode, but defers other requests.
        Fake fake = new Fake();
        CompletableFuture<Object> autosave = fake.commands.request("nvim_exec_lua", "AndroidNeovim.background()", List.of());
        CompletableFuture<Object> enter = fake.commands.input("<CR>");
        assertEquals(List.of("nvim_exec_lua", "nvim_get_mode"), fake.methods);
        fake.commands.input("x");
        fake.answer(1, Map.of("mode", "r", "blocking", true));
        assertEquals(List.of("nvim_exec_lua", "nvim_get_mode", "nvim_input"), fake.methods);
        fake.answer(2, 4L);
        assertEquals(4L, enter.join());
        assertEquals(List.of("nvim_exec_lua", "nvim_get_mode", "nvim_input", "nvim_input"), fake.methods);
        fake.answer(3, 1L);
        assertFalse(autosave.isDone());
        fake.answer(0, null);
        assertTrue(autosave.isDone());
    }

    @Test public void inputAfterARequestWaitsUntilNeovimCouldRunIt() {
        Fake fake = new Fake();
        CompletableFuture<Object> paste = fake.commands.request("nvim_paste", "XYZ", true, -1);
        fake.commands.input("def");
        assertEquals(List.of("nvim_paste", "nvim_get_mode"), fake.methods);
        // Not blocked at a prompt: the paste ran before Neovim answered the probe.
        fake.reply(true);
        fake.reply(Map.of("mode", "i", "blocking", false));
        assertEquals(true, paste.join());
        assertEquals(List.of("nvim_paste", "nvim_get_mode", "nvim_input"), fake.methods);
        // Fast calls and resizes need no probe.
        fake.reply(3L);
        fake.commands.request("nvim_input_mouse", "left", "press", "", 0, 1, 2);
        fake.commands.request("nvim_ui_try_resize", 80, 24);
        fake.commands.input("g");
        assertEquals(List.of("nvim_paste", "nvim_get_mode", "nvim_input", "nvim_input_mouse",
                "nvim_ui_try_resize", "nvim_input"), fake.methods);
    }

    @Test public void resizesCoalesceToTheLatestSize() {
        Fake fake = new Fake();
        fake.commands.request("nvim_ui_try_resize", 80, 24);
        CompletableFuture<Object> last = null;
        for (int i = 1; i <= 50; i++) last = fake.commands.request("nvim_ui_try_resize", 80 + i, 24);
        assertEquals(List.of("nvim_ui_try_resize"), fake.methods);
        fake.reply(null);
        assertEquals(List.of("nvim_ui_try_resize", "nvim_ui_try_resize"), fake.methods);
        assertArrayEquals(new Object[]{130, 24}, fake.arguments.get(1));
        fake.reply(null);
        assertTrue(last.isDone());

        // Queued behind input, too.
        fake.commands.input("i");
        for (int i = 1; i <= 50; i++) last = fake.commands.request("nvim_ui_try_resize", 40, 10 + i);
        fake.reply(1L);
        assertEquals("nvim_ui_try_resize", fake.methods.get(3));
        assertArrayEquals(new Object[]{40, 60}, fake.arguments.get(3));
        assertEquals(4, fake.methods.size());
        fake.reply(null);
        assertTrue(last.isDone());
    }

    @Test public void attachSupersedesAnUnsentResize() {
        Fake fake = new Fake();
        fake.commands.request("nvim_ui_try_resize", 80, 24);
        CompletableFuture<Object> stale = fake.commands.request("nvim_ui_try_resize", 81, 24);
        fake.commands.request("nvim_ui_detach");
        fake.commands.request("nvim_ui_attach", 40, 60, Map.of());
        assertNull(stale.join());
        fake.reply(null);
        assertEquals(List.of("nvim_ui_try_resize", "nvim_ui_detach", "nvim_ui_attach"), fake.methods);
    }

    @Test public void aResizeAfterAQueuedAttachIsSentAfterIt() {
        // Neovim is busy, so a resize of the old view waits behind input when the Activity is recreated.
        Fake fake = new Fake();
        fake.commands.input("x");
        fake.commands.request("nvim_ui_try_resize", 80, 24);
        fake.commands.request("nvim_ui_detach");
        fake.commands.request("nvim_ui_attach", 1, 1, Map.of());
        CompletableFuture<Object> laidOut = fake.commands.request("nvim_ui_try_resize", 40, 60);
        CompletableFuture<Object> later = fake.commands.request("nvim_ui_try_resize", 41, 60);
        assertSame(laidOut, later);
        fake.reply(1L);
        assertEquals(List.of("nvim_input", "nvim_ui_try_resize", "nvim_ui_detach", "nvim_ui_attach"), fake.methods);
        assertArrayEquals(new Object[]{80, 24}, fake.arguments.get(1));
        fake.answer(1, null);
        assertEquals("nvim_ui_try_resize", fake.methods.get(4));
        assertArrayEquals(new Object[]{41, 60}, fake.arguments.get(4));
        fake.answer(4, true);
        assertEquals(true, later.join());
    }

    @Test public void largePastesStreamWithoutSplittingCrlfOrSurrogatePairs() {
        Fake fake = new Fake();
        CompletableFuture<Object> small = fake.commands.paste("a\r\nb");
        assertArrayEquals(new Object[]{"a\r\nb", true, -1}, fake.arguments.get(0));
        fake.reply(true);
        assertEquals(true, small.join());
        fake.commands.paste("");
        assertArrayEquals(new Object[]{"", true, -1}, fake.arguments.get(1));
        fake.reply(true);

        int size = EditorCommands.PASTE_CHUNK;
        String text = "x".repeat(size - 1) + "\r\n" + "y".repeat(size - 3) + "🙂" + "z".repeat(size);
        CompletableFuture<Object> large = fake.commands.paste(text);
        List<String> chunks = new ArrayList<>();
        List<Object> phases = new ArrayList<>();
        for (Object[] args : fake.arguments.subList(2, fake.arguments.size())) {
            chunks.add((String) args[0]);
            assertEquals(true, args[1]);
            phases.add(args[2]);
        }
        assertEquals(List.of(1, 2, 2, 3), phases);
        assertEquals(text, String.join("", chunks));
        assertTrue(chunks.get(1).startsWith("\r\n"));
        assertTrue(chunks.get(2).startsWith("🙂"));
        for (String chunk : chunks) assertTrue(chunk.length() <= size);
        for (int i = 0; i < 3; i++) fake.reply(true);
        assertFalse(large.isDone());
        fake.reply(true);
        assertEquals(true, large.join());
    }

    @Test public void pasteThatDoesNotFitIsNotStarted() {
        Fake fake = new Fake();
        for (int i = 0; i < 125; i++) fake.commands.request("nvim_command", "echo");
        String text = "x".repeat(3 * EditorCommands.PASTE_CHUNK + 1);
        assertTrue(fake.commands.paste(text).isCompletedExceptionally());
        assertFalse(fake.methods.contains("nvim_paste"));
        fake.reply(null);
        CompletableFuture<Object> pasted = fake.commands.paste(text);
        assertFalse(pasted.isCompletedExceptionally());
        List<Object> phases = new ArrayList<>();
        for (int i = 0; i < fake.methods.size(); i++)
            if (fake.methods.get(i).equals("nvim_paste")) phases.add(fake.arguments.get(i)[2]);
        assertEquals(List.of(1, 2, 2, 3), phases);
    }

    @Test public void partialUtf8IsRetriedAsExactBytesBeforeNextCommand() {
        Fake fake = new Fake();
        byte[] bytes = "é世".getBytes(StandardCharsets.UTF_8);
        CompletableFuture<Object> input = fake.commands.input("é世");
        fake.commands.request("nvim_command", "write");
        fake.reply(1L);
        assertArrayEquals(Arrays.copyOfRange(bytes, 1, bytes.length), (byte[]) fake.arguments.get(1)[0]);
        fake.reply(1L);
        assertArrayEquals(Arrays.copyOfRange(bytes, 2, bytes.length), (byte[]) fake.arguments.get(2)[0]);
        fake.reply(3L);
        assertEquals(5L, input.join());
        assertEquals("nvim_command", fake.methods.get(3));
    }

    @Test public void zeroAcceptanceWaitsWithoutLettingCommandsOvertake() {
        Fake fake = new Fake();
        fake.commands.input("abc");
        fake.commands.request("nvim_paste", "XYZ", false, -1);
        fake.reply(0L);
        assertEquals(1, fake.methods.size());
        assertEquals(1, fake.retries.size());
        fake.retries.remove().run();
        assertEquals(List.of("nvim_input", "nvim_input"), fake.methods);
        fake.reply(3L);
        assertEquals("nvim_paste", fake.methods.get(2));
    }

    @Test public void closeFailsQueuedAndInflightAndIgnoresLateResponses() {
        Fake fake = new Fake();
        CompletableFuture<Object> input = fake.commands.input("abc");
        CompletableFuture<Object> command = fake.commands.request("nvim_command", "write");
        fake.commands.close(new IOException("Disconnected"));
        assertTrue(input.isCompletedExceptionally());
        assertTrue(command.isCompletedExceptionally());
        fake.reply(3L);
        assertEquals(1, fake.methods.size());
        assertTrue(fake.commands.input("after").isCompletedExceptionally());
        assertTrue(fake.commands.request("after").isCompletedExceptionally());
    }

    @Test public void queueBoundsRejectWithoutDiscardingAcceptedInput() {
        Fake fake = new Fake();
        CompletableFuture<Object> full = fake.commands.input("é".repeat(32768));
        assertTrue(fake.commands.input("x").isCompletedExceptionally());
        fake.reply(65536L);
        assertEquals(65536L, full.join());
        assertFalse(fake.commands.input("x").isCompletedExceptionally());
        for (int i = 0; i < 127; i++) assertFalse(fake.commands.request("nvim_get_mode").isCompletedExceptionally());
        assertTrue(fake.commands.request("overflow").isCompletedExceptionally());
    }

    @Test public void requestsAwaitingRepliesCountButLeaveRoomForInput() {
        Fake fake = new Fake();
        for (int i = 0; i < 128; i++) fake.commands.request("nvim_command", "echo");
        assertEquals(128, fake.methods.size());
        assertTrue(fake.commands.request("overflow").isCompletedExceptionally());
        assertFalse(fake.commands.input("<CR>").isCompletedExceptionally());
        assertTrue(fake.commands.request("overflow").isCompletedExceptionally());
        fake.reply(null);
        fake.reply(null);
        assertFalse(fake.commands.request("nvim_command", "echo").isCompletedExceptionally());
    }

    @Test public void closeFailsSentRequests() {
        Fake fake = new Fake();
        CompletableFuture<Object> command = fake.commands.request("nvim_command", "write");
        fake.commands.request("nvim_ui_try_resize", 1, 1);
        CompletableFuture<Object> resize = fake.commands.request("nvim_ui_try_resize", 2, 2);
        fake.commands.close(new IOException("Disconnected"));
        assertTrue(command.isCompletedExceptionally());
        assertTrue(resize.isCompletedExceptionally());
        fake.reply(null);
        fake.reply(null);
        assertEquals(2, fake.methods.size());
    }

    @Test public void invalidInputResultFailsAndReleasesInputBudget() {
        Fake fake = new Fake();
        CompletableFuture<Object> input = fake.commands.input("abc");
        fake.reply(4L);
        assertTrue(input.isCompletedExceptionally());
        assertFalse(fake.commands.input("x".repeat(65536)).isCompletedExceptionally());
    }
}
