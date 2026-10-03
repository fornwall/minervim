package net.fornwall.androidneovim.rpc;

import static org.junit.Assert.*;

import java.io.Closeable;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class NvimRpcClientTest {
    private static final class Peer implements Closeable {
        final PipedInputStream inbound = new PipedInputStream(65536);
        final PipedOutputStream serverOutput = new PipedOutputStream(inbound);
        final PipedInputStream serverInput = new PipedInputStream(65536);
        final PipedOutputStream outbound = new PipedOutputStream(serverInput);
        final BlockingQueue<List<?>> notifications = new LinkedBlockingQueue<>();
        final CompletableFuture<Throwable> closed = new CompletableFuture<>();
        final CompletableFuture<Object> later = new CompletableFuture<>();
        final NvimRpcClient client;

        Peer() throws IOException { this(Runnable::run, false); }

        Peer(Executor executor, boolean failNotification) throws IOException {
            client = new NvimRpcClient(inbound, outbound, executor, new NvimRpcClient.Listener() {
                @Override public void onNotification(String method, List<?> arguments) {
                    if (failNotification) throw new IllegalStateException("Broken UI callback");
                    notifications.add(List.of(method, arguments));
                }
                @Override public void onClosed(Throwable cause) { closed.complete(cause); }
                @Override public CompletableFuture<?> onRequest(String method, List<?> arguments) {
                    return switch (method) {
                        case "echo" -> CompletableFuture.completedFuture(arguments);
                        case "later" -> later;
                        case "broken" -> throw new IllegalStateException("broken handler");
                        default -> null;
                    };
                }
            });
            client.start();
        }

        List<?> read() throws IOException { return (List<?>) MessagePack.read(serverInput); }
        void send(Object frame) throws IOException {
            MessagePack.write(serverOutput, frame);
            serverOutput.flush();
        }
        @Override public void close() throws IOException {
            client.close();
            serverOutput.close();
            serverInput.close();
        }
    }

    @Test(timeout = 5000) public void correlatesOutOfOrderRepliesAroundRedraw() throws Exception {
        try (Peer peer = new Peer()) {
            CompletableFuture<Object> first = peer.client.request("nvim_get_mode");
            CompletableFuture<Object> second = peer.client.request("nvim_eval", "1+1");
            List<?> firstFrame = peer.read();
            List<?> secondFrame = peer.read();
            assertEquals("nvim_get_mode", firstFrame.get(2));
            assertEquals(List.of("1+1"), secondFrame.get(3));
            List<?> events = List.of(List.of("flush", List.of()));
            peer.send(List.of(2L, "redraw", events));
            peer.send(Arrays.asList(1L, secondFrame.get(1), null, 2L));
            peer.send(Arrays.asList(1L, firstFrame.get(1), null, "normal"));
            assertEquals(2L, second.get(2, TimeUnit.SECONDS));
            assertEquals("normal", first.get(2, TimeUnit.SECONDS));
            assertEquals(List.of("redraw", events), peer.notifications.poll(2, TimeUnit.SECONDS));
        }
    }

    @Test(timeout = 5000) public void rpcErrorOnlyFailsItsRequest() throws Exception {
        try (Peer peer = new Peer()) {
            CompletableFuture<Object> result = peer.client.request("bad_method");
            List<?> request = peer.read();
            peer.send(Arrays.asList(1L, request.get(1), List.of(0L, "bad method"), null));
            ExecutionException error = assertThrows(ExecutionException.class,
                    () -> result.get(2, TimeUnit.SECONDS));
            assertTrue(error.getCause() instanceof NvimRpcClient.RpcException);
            assertFalse(peer.client.isClosed());
            peer.send(List.of(0L, 99L, "unknown_ui_method", List.of()));
            List<?> reply = peer.read();
            assertEquals(1L, reply.get(0));
            assertEquals(99L, reply.get(1));
            assertNotNull(reply.get(2));
            assertNull(reply.get(3));
        }
    }

    @Test(timeout = 5000) public void answersEveryNeovimRequestExactlyOnce() throws Exception {
        try (Peer peer = new Peer()) {
            peer.send(List.of(0L, 1L, "later", List.of()));
            peer.send(List.of(0L, 2L, "echo", List.of("a", 1L)));
            assertEquals(Arrays.asList(1L, 2L, null, List.of("a", 1L)), peer.read());
            peer.later.complete("done");
            assertEquals(Arrays.asList(1L, 1L, null, "done"), peer.read());
            peer.send(List.of(0L, 3L, "broken", List.of()));
            List<?> broken = peer.read();
            assertEquals(3L, broken.get(1));
            assertEquals(List.of(0L, "broken handler"), broken.get(2));
            assertFalse(peer.client.isClosed());
        }
    }

    @Test(timeout = 5000) public void malformedFrameFailsPendingAndCloses() throws Exception {
        try (Peer peer = new Peer()) {
            CompletableFuture<Object> result = peer.client.request("nvim_eval", "1");
            peer.read();
            peer.send(List.of(1L));
            assertThrows(ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
            assertNotNull(peer.closed.get(2, TimeUnit.SECONDS));
            assertTrue(peer.client.isClosed());
            assertTrue(peer.client.request("after_close").isCompletedExceptionally());
        }
    }

    @Test(timeout = 5000) public void closeResolvesPendingAndRejectsNewRequests() throws Exception {
        try (Peer peer = new Peer()) {
            CompletableFuture<Object> result = peer.client.request("nvim_eval", "1");
            peer.client.close();
            assertThrows(ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
            assertTrue(peer.client.request("after_close").isCompletedExceptionally());
        }
    }

    @Test(timeout = 5000) public void pendingRequestsHaveABound() throws Exception {
        try (Peer peer = new Peer()) {
            List<CompletableFuture<Object>> futures = new ArrayList<>();
            for (int i = 0; i < 256; i++) {
                futures.add(peer.client.request("nvim_get_mode"));
                peer.read();
            }
            assertTrue(peer.client.request("one_too_many").isCompletedExceptionally());
            peer.client.close();
            assertTrue(futures.stream().allMatch(CompletableFuture::isCompletedExceptionally));
        }
    }

    @Test(timeout = 5000) public void callbackFailureClosesAndFailsPendingRequests() throws Exception {
        try (Peer peer = new Peer(Runnable::run, true)) {
            CompletableFuture<Object> pending = peer.client.request("nvim_get_mode");
            peer.read();
            peer.send(List.of(2L, "redraw", List.of()));
            assertTrue(peer.closed.get(2, TimeUnit.SECONDS) instanceof IllegalStateException);
            assertTrue(pending.isCompletedExceptionally());
        }
    }

    @Test(timeout = 5000) public void slowUiCannotAccumulateUnboundedCallbacks() throws Exception {
        BlockingQueue<Runnable> callbacks = new LinkedBlockingQueue<>();
        try (Peer peer = new Peer(callbacks::add, false)) {
            for (int i = 0; i < 64; i++) peer.send(List.of(2L, "redraw", List.of()));
            List<Runnable> held = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                Runnable callback = callbacks.poll(2, TimeUnit.SECONDS);
                assertNotNull(callback);
                held.add(callback);
            }
            assertNull(callbacks.poll(100, TimeUnit.MILLISECONDS));
            held.get(0).run();
            assertNotNull(callbacks.poll(2, TimeUnit.SECONDS));
        }
    }
}
