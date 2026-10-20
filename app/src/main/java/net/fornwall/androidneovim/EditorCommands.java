package net.fornwall.androidneovim;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Orders editor actions after input retries. All calls and callbacks belong to one serial executor.
 * Neovim accepts input on receipt, but defers most requests until it next waits for typed input,
 * after earlier typeahead. At a prompt or pending key ("Press ENTER", {@code g}, {@code r}, ...) it reads
 * keys without running them, so a sent request must never hold back later input.
 */
final class EditorCommands {
    @FunctionalInterface
    interface Requester {
        CompletableFuture<Object> request(String method, Object... arguments);
    }

    private static final String RESIZE = "nvim_ui_try_resize";
    static final int PASTE_CHUNK = 64 * 1024;
    /** Requests later input may overtake: fast calls, which Neovim runs on receipt, and resizes. */
    private static final Set<String> UNORDERED = Set.of("nvim_get_mode", "nvim_input_mouse", RESIZE);
    /** Below the RPC client's pending limit, so requests stuck at a prompt leave room for input. */
    private static final int MAX_OPERATIONS = 128;
    private static final int MAX_INPUT_BYTES = 64 * 1024;
    private final Requester requester;
    private final Executor callbacks;
    private final Consumer<Runnable> retryLater;
    /** Unsent operations; the head may be input that Neovim has partially accepted. */
    private final ArrayDeque<Operation> queue = new ArrayDeque<>();
    /** Sent requests awaiting a reply. */
    private final Set<Operation> sent = new LinkedHashSet<>();
    /** At most one resize is sent at a time; later sizes replace the unsent one. */
    private Operation resizing;
    private Operation nextResize;
    private boolean inputting;
    /** Requests were sent since the last probe that later input must not overtake. */
    private boolean unordered;
    private boolean probing;
    private int inputBytes;
    private Throwable closed;

    EditorCommands(Requester requester, Executor callbacks, Consumer<Runnable> retryLater) {
        this.requester = Objects.requireNonNull(requester);
        this.callbacks = Objects.requireNonNull(callbacks);
        this.retryLater = Objects.requireNonNull(retryLater);
    }

    /** Resolves to the original UTF-8 byte count once Neovim has accepted every byte. */
    CompletableFuture<Object> input(String keys) {
        Objects.requireNonNull(keys);
        if (closed != null) return CompletableFuture.failedFuture(closed);
        if (keys.length() > MAX_INPUT_BYTES)
            return CompletableFuture.failedFuture(new IOException("Input queue is full"));
        byte[] bytes = keys.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_INPUT_BYTES - inputBytes)
            return CompletableFuture.failedFuture(new IOException("Input queue is full"));
        if (bytes.length == 0) return CompletableFuture.completedFuture(0L);
        return enqueue(new Operation(bytes));
    }

    /** Sent once earlier input is accepted. Unsent resizes share one request with the latest size. */
    CompletableFuture<Object> request(String method, Object... arguments) {
        Objects.requireNonNull(method);
        Operation resize = closed == null && method.equals(RESIZE) ? unsentResize() : null;
        if (resize == null) return enqueue(new Operation(method, arguments.clone()));
        resize.arguments = arguments.clone();
        return resize.result;
    }

    /**
     * Streams text through nvim_paste in chunks, as a terminal does, keeping each CRLF and surrogate
     * pair within one chunk. Resolves once Neovim has handled every chunk.
     */
    CompletableFuture<Object> paste(String text) {
        List<CompletableFuture<Object>> chunks = new ArrayList<>();
        int start = 0;
        do {
            int end = Math.min(text.length(), start + PASTE_CHUNK);
            if (end < text.length() && (Character.isHighSurrogate(text.charAt(end - 1))
                    || text.charAt(end - 1) == '\r' && text.charAt(end) == '\n')) end--;
            int phase = start == 0 ? (end == text.length() ? -1 : 1) : (end == text.length() ? 3 : 2);
            CompletableFuture<Object> chunk = request("nvim_paste", text.substring(start, end), true, phase);
            chunks.add(chunk);
            if (chunk.isCompletedExceptionally()) break;
            start = end;
        } while (start < text.length());
        return CompletableFuture.allOf(chunks.toArray(new CompletableFuture<?>[0]))
                .thenApply(ignored -> chunks.get(chunks.size() - 1).join());
    }

    void close(Throwable cause) {
        if (closed != null) return;
        closed = Objects.requireNonNull(cause);
        List<Operation> pending = new ArrayList<>(queue);
        pending.addAll(sent);
        if (nextResize != null) pending.add(nextResize);
        queue.clear();
        sent.clear();
        resizing = nextResize = null;
        inputBytes = 0;
        for (Operation operation : pending) operation.result.completeExceptionally(cause);
    }

    private Operation unsentResize() {
        if (nextResize != null) return nextResize;
        for (Operation operation : queue) if (RESIZE.equals(operation.method)) return operation;
        return null;
    }

    private CompletableFuture<Object> enqueue(Operation operation) {
        if (closed != null) return CompletableFuture.failedFuture(closed);
        // Requests awaiting replies count too, but cannot block input, which may be what ends a prompt.
        int used = queue.size() + (operation.input == null ? sent.size() : 0);
        if (used >= MAX_OPERATIONS)
            return CompletableFuture.failedFuture(new IOException("Editor command queue is full"));
        queue.add(operation);
        if (operation.input != null) inputBytes += operation.input.length;
        advance();
        return operation.result;
    }

    /** Sends requests after all earlier input is accepted, and input after earlier requests could run. */
    private void advance() {
        while (closed == null && !inputting && !probing) {
            Operation next = queue.peek();
            if (next == null) return;
            if (next.input == null) {
                queue.remove();
                if (next.method.equals(RESIZE) && resizing != null) nextResize = next;
                else sendRequest(next);
            } else if (unordered) probe();
            else {
                inputting = true;
                send(next);
            }
        }
    }

    /**
     * Neovim answers nvim_get_mode after the requests sent before it, or at once while it blocks
     * for a key, when those requests cannot run before more input. Either way input may follow.
     */
    private void probe() {
        unordered = false;
        probing = true;
        call("nvim_get_mode").whenCompleteAsync((ignored, error) -> {
            probing = false;
            advance();
        }, callbacks);
    }

    private void sendRequest(Operation operation) {
        if (!UNORDERED.contains(operation.method)) unordered = true;
        if (operation.method.equals(RESIZE)) resizing = operation;
        // An attach carries the current size, which supersedes an older resize.
        if (operation.method.equals("nvim_ui_attach") && nextResize != null) {
            nextResize.result.complete(null);
            nextResize = null;
        }
        sent.add(operation);
        call(operation.method, operation.arguments)
                .whenCompleteAsync((result, error) -> answered(operation, result, error), callbacks);
    }

    private void answered(Operation operation, Object result, Throwable error) {
        if (!sent.remove(operation)) return;
        if (operation == resizing) {
            resizing = null;
            Operation next = nextResize;
            nextResize = null;
            if (next != null) sendRequest(next);
        }
        complete(operation, result, error);
    }

    private void send(Operation operation) {
        if (closed != null || queue.peek() != operation) return;
        call("nvim_input", (Object) Arrays.copyOfRange(operation.input, operation.offset, operation.input.length))
                .whenCompleteAsync((result, error) -> finish(operation, result, error), callbacks);
    }

    private void finish(Operation operation, Object result, Throwable error) {
        if (closed != null || queue.peek() != operation) return;
        if (error == null) {
            int remaining = operation.input.length - operation.offset;
            if (!(result instanceof Number number) || number.longValue() < 0
                    || number.longValue() > remaining) {
                error = new IOException("Neovim returned an invalid input byte count");
            } else {
                int used = number.intValue();
                operation.offset += used;
                inputBytes -= used;
                if (operation.offset < operation.input.length) {
                    if (used == 0) retryLater.accept(() -> send(operation));
                    else send(operation);
                    return;
                }
                result = (long) operation.input.length;
            }
        }
        inputBytes -= operation.input.length - operation.offset;
        queue.remove();
        inputting = false;
        // Start the next queued action before completing a future whose continuations can enqueue more.
        advance();
        complete(operation, result, error);
    }

    private CompletableFuture<Object> call(String method, Object... arguments) {
        try {
            return Objects.requireNonNull(requester.request(method, arguments), "Requester returned no future");
        } catch (Exception failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private static void complete(Operation operation, Object result, Throwable error) {
        if (error == null) operation.result.complete(result);
        else operation.result.completeExceptionally(error);
    }

    private static final class Operation {
        final String method;
        Object[] arguments;
        final byte[] input;
        final CompletableFuture<Object> result = new CompletableFuture<>();
        int offset;

        Operation(String method, Object[] arguments) {
            this.method = method;
            this.arguments = arguments;
            this.input = null;
        }

        Operation(byte[] input) {
            this.method = null;
            this.arguments = null;
            this.input = input;
        }
    }
}
