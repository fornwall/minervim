package net.fornwall.androidneovim;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Orders editor actions after input retries. All calls and callbacks belong to one serial executor. */
final class EditorCommands {
    @FunctionalInterface
    interface Requester {
        CompletableFuture<Object> request(String method, Object... arguments);
    }

    private static final int MAX_OPERATIONS = 256;
    private static final int MAX_INPUT_BYTES = 64 * 1024;
    private final Requester requester;
    private final Executor callbacks;
    private final Consumer<Runnable> retryLater;
    private final ArrayDeque<Operation> queue = new ArrayDeque<>();
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

    CompletableFuture<Object> request(String method, Object... arguments) {
        return enqueue(new Operation(Objects.requireNonNull(method), arguments.clone()));
    }

    void close(Throwable cause) {
        if (closed != null) return;
        closed = Objects.requireNonNull(cause);
        Operation[] pending = queue.toArray(new Operation[0]);
        queue.clear();
        inputBytes = 0;
        for (Operation operation : pending) operation.result.completeExceptionally(cause);
    }

    private CompletableFuture<Object> enqueue(Operation operation) {
        if (closed != null) return CompletableFuture.failedFuture(closed);
        if (queue.size() >= MAX_OPERATIONS)
            return CompletableFuture.failedFuture(new IOException("Editor command queue is full"));
        boolean start = queue.isEmpty();
        queue.add(operation);
        if (operation.input != null) inputBytes += operation.input.length;
        if (start) send(operation);
        return operation.result;
    }

    private void send(Operation operation) {
        if (closed != null || queue.peek() != operation) return;
        CompletableFuture<Object> response;
        try {
            response = operation.input == null
                    ? requester.request(operation.method, operation.arguments)
                    : requester.request("nvim_input", Arrays.copyOfRange(
                            operation.input, operation.offset, operation.input.length));
            Objects.requireNonNull(response, "Requester returned no future");
        } catch (Exception failure) {
            finish(operation, null, failure);
            return;
        }
        response.whenCompleteAsync((result, error) -> finish(operation, result, error), callbacks);
    }

    private void finish(Operation operation, Object result, Throwable error) {
        if (closed != null || queue.peek() != operation) return;
        if (error == null && operation.input != null) {
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
        if (operation.input != null) inputBytes -= operation.input.length - operation.offset;
        queue.remove();
        // Start the next queued action before completing a future whose continuations can enqueue more.
        Operation next = queue.peek();
        if (next != null) send(next);
        if (error == null) operation.result.complete(result);
        else operation.result.completeExceptionally(error);
    }

    private static final class Operation {
        final String method;
        final Object[] arguments;
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
