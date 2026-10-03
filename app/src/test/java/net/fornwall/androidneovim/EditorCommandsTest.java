package net.fornwall.androidneovim;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.Test;

public class EditorCommandsTest {
    private static final class Fake {
        final List<String> methods = new ArrayList<>();
        final List<Object[]> arguments = new ArrayList<>();
        final ArrayDeque<CompletableFuture<Object>> replies = new ArrayDeque<>();
        final ArrayDeque<Runnable> retries = new ArrayDeque<>();
        final EditorCommands commands = new EditorCommands((method, args) -> {
            methods.add(method);
            arguments.add(args);
            CompletableFuture<Object> reply = new CompletableFuture<>();
            replies.add(reply);
            return reply;
        }, Runnable::run, retries::add);

        void reply(Object value) { replies.remove().complete(value); }
    }

    @Test public void pasteAndSaveStayBehindEarlierInput() {
        Fake fake = new Fake();
        fake.commands.input("i");
        fake.commands.input("abc");
        CompletableFuture<Object> paste = fake.commands.request("nvim_paste", "XYZ", false, -1);
        CompletableFuture<Object> save = fake.commands.request("nvim_command", "write");
        assertEquals(List.of("nvim_input"), fake.methods);
        fake.reply(1L);
        assertEquals(List.of("nvim_input", "nvim_input"), fake.methods);
        assertArrayEquals("abc".getBytes(StandardCharsets.UTF_8), (byte[]) fake.arguments.get(1)[0]);
        fake.reply(3L);
        assertEquals(List.of("nvim_input", "nvim_input", "nvim_paste"), fake.methods);
        fake.reply(true);
        assertEquals(List.of("nvim_input", "nvim_input", "nvim_paste", "nvim_command"), fake.methods);
        assertEquals(true, paste.join());
        fake.reply(null);
        assertTrue(save.isDone());
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
        for (int i = 0; i < 255; i++) fake.commands.request("nvim_get_mode");
        assertTrue(fake.commands.request("overflow").isCompletedExceptionally());
    }

    @Test public void invalidInputResultFailsAndReleasesInputBudget() {
        Fake fake = new Fake();
        CompletableFuture<Object> input = fake.commands.input("abc");
        fake.reply(4L);
        assertTrue(input.isCompletedExceptionally());
        assertFalse(fake.commands.input("x".repeat(65536)).isCompletedExceptionally());
    }
}
