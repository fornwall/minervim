package net.fornwall.androidneovim.rpc;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Stream-based MessagePack RPC. Supply a serial executor to preserve UI notification order. */
public final class NvimRpcClient implements Closeable {
    public interface Listener {
        /** For redraw, arguments is the list of event groups, ready for a grid decoder. */
        void onNotification(String method, List<?> arguments);
        void onClosed(Throwable cause);
    }

    public static final class RpcException extends IOException {
        private final Object error;

        RpcException(Object error) {
            super("Neovim RPC error: " + error);
            this.error = error;
        }

        public Object error() { return error; }
    }

    private static final int MAX_PENDING = 256;
    private static final int CALLBACK_KIB = 16 * 1024;
    private final InputStream input;
    private final OutputStream output;
    private final Executor callbackExecutor;
    private final Listener listener;
    private final ArrayBlockingQueue<List<?>> outgoing = new ArrayBlockingQueue<>(MAX_PENDING);
    private final Map<Long, CompletableFuture<Object>> pending = new ConcurrentHashMap<>();
    private final Semaphore pendingSlots = new Semaphore(MAX_PENDING);
    private final Semaphore callbackSlots = new Semaphore(32);
    private final Semaphore callbackMemory = new Semaphore(CALLBACK_KIB);
    private final AtomicLong nextId = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Thread reader;
    private final Thread writer;
    private boolean started;

    public NvimRpcClient(InputStream input, OutputStream output, Executor callbackExecutor,
            Listener listener) {
        this.input = Objects.requireNonNull(input);
        this.output = Objects.requireNonNull(output);
        this.callbackExecutor = Objects.requireNonNull(callbackExecutor);
        this.listener = Objects.requireNonNull(listener);
        reader = new Thread(this::readLoop, "neovim-rpc-reader");
        writer = new Thread(this::writeLoop, "neovim-rpc-writer");
        reader.setDaemon(true);
        writer.setDaemon(true);
    }

    public synchronized void start() {
        if (started || closed.get()) throw new IllegalStateException("RPC client already started or closed");
        started = true;
        writer.start();
        reader.start();
    }

    /** The future completes on the reader thread; use an explicit executor for UI continuations. */
    public synchronized CompletableFuture<Object> request(String method, Object... arguments) {
        Objects.requireNonNull(method);
        CompletableFuture<Object> result = new CompletableFuture<>();
        if (closed.get() || !started) {
            result.completeExceptionally(new IOException("RPC client is not running"));
            return result;
        }
        if (!pendingSlots.tryAcquire()) {
            result.completeExceptionally(new IOException("Too many pending RPC requests"));
            return result;
        }
        long id = nextId.getAndIncrement();
        pending.put(id, result);
        if (!outgoing.offer(List.of(0L, id, method, Arrays.asList(arguments.clone())))) {
            pending.remove(id);
            pendingSlots.release();
            result.completeExceptionally(new IOException("RPC outgoing queue is full"));
        }
        return result;
    }

    /** Enqueue input without blocking the Android UI. Failure closes the connection explicitly. */
    public synchronized void notify(String method, Object... arguments) {
        Objects.requireNonNull(method);
        if (!started || closed.get()) return;
        if (!outgoing.offer(List.of(2L, method, Arrays.asList(arguments.clone()))))
            fail(new IOException("RPC outgoing queue is full"));
    }

    public boolean isClosed() { return closed.get(); }

    @Override public void close() {
        fail(new IOException("RPC client closed"));
    }

    private void writeLoop() {
        try {
            while (!closed.get()) {
                List<?> message = outgoing.take();
                // Validate/encode the whole frame before changing the transport stream.
                ByteArrayOutputStream encoded = new ByteArrayOutputStream();
                MessagePack.write(encoded, message);
                encoded.writeTo(output);
                output.flush();
            }
        } catch (Exception error) {
            fail(error);
        }
    }

    private void readLoop() {
        try {
            while (!closed.get()) {
                Object decoded = MessagePack.read(input);
                if (!(decoded instanceof List<?> frame) || frame.isEmpty())
                    throw new IOException("Invalid RPC frame");
                long kind = number(frame.get(0));
                if (kind == 1 && frame.size() == 4) {
                    long id = number(frame.get(1));
                    CompletableFuture<Object> future = pending.remove(id);
                    if (future == null) throw new IOException("Unknown RPC response id: " + id);
                    pendingSlots.release();
                    if (frame.get(2) == null) future.complete(frame.get(3));
                    else future.completeExceptionally(new RpcException(frame.get(2)));
                } else if (kind == 2 && frame.size() == 3
                        && frame.get(1) instanceof String method && frame.get(2) instanceof List<?> args) {
                    dispatch(method, args);
                } else if (kind == 0 && frame.size() == 4
                        && frame.get(2) instanceof String && frame.get(3) instanceof List<?>) {
                    // A UI has no custom rpcrequest handlers. Reply so Neovim cannot deadlock.
                    long id = number(frame.get(1));
                    List<?> response = Arrays.asList(1L, id,
                            List.of(0L, "Android UI does not implement this RPC method"), null);
                    if (!outgoing.offer(response)) throw new IOException("RPC outgoing queue is full");
                } else throw new IOException("Invalid RPC message shape");
            }
        } catch (Exception error) {
            fail(error);
        }
    }

    private void dispatch(String method, List<?> args) throws IOException, InterruptedException {
        long bytes = estimatedBytes(args) + 2L * method.length();
        int kib = (int) Math.min(Integer.MAX_VALUE, (bytes + 1023) / 1024);
        if (kib > CALLBACK_KIB) throw new IOException("RPC notification exceeds callback memory limit");
        callbackSlots.acquire();
        try {
            callbackMemory.acquire(kib);
        } catch (InterruptedException error) {
            callbackSlots.release();
            throw error;
        }
        try {
            callbackExecutor.execute(() -> {
                try {
                    if (!closed.get()) listener.onNotification(method, args);
                } catch (Exception error) {
                    fail(error);
                } finally {
                    callbackMemory.release(kib);
                    callbackSlots.release();
                }
            });
        } catch (RuntimeException error) {
            callbackMemory.release(kib);
            callbackSlots.release();
            throw error;
        }
    }

    private static long estimatedBytes(Object value) {
        if (value instanceof String string) return 48L + string.length() * 2L;
        if (value instanceof byte[] bytes) return 24L + bytes.length;
        if (value instanceof MessagePack.Extension extension) return 48L + extension.data().length;
        long bytes = 32;
        if (value instanceof List<?> list) {
            for (Object item : list) bytes += 8 + estimatedBytes(item);
        } else if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet())
                bytes += 48 + estimatedBytes(entry.getKey()) + estimatedBytes(entry.getValue());
        }
        return bytes;
    }

    private static long number(Object value) throws IOException {
        if (!(value instanceof Long number) || number < 0)
            throw new IOException("RPC type and request ids must be nonnegative integers");
        return number;
    }

    private synchronized void fail(Throwable cause) {
        if (!closed.compareAndSet(false, true)) return;
        reader.interrupt();
        writer.interrupt();
        try { input.close(); } catch (IOException ignored) {}
        try { output.close(); } catch (IOException ignored) {}
        outgoing.clear();
        for (CompletableFuture<Object> future : pending.values()) future.completeExceptionally(cause);
        pending.clear();
        try {
            callbackExecutor.execute(() -> {
                try { listener.onClosed(cause); } catch (RuntimeException ignored) {}
            });
        } catch (RuntimeException ignored) {
            // An executor shutting down must not prevent stream and request cleanup.
        }
    }
}
