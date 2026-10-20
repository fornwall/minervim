package net.fornwall.androidneovim;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.Configuration;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.fornwall.androidneovim.rpc.NvimRpcClient;
import net.fornwall.androidneovim.ui.NeovimView;

/** Owns one native editor for the application lifetime; Android view state stays on the main thread. */
final class EditorSession implements NeovimView.Listener {
    interface Observer {
        void onStatus(String message, boolean ready);
        /** A transient notice, such as the result of updating an original document. */
        default void onMessage(String message) {}
    }

    static final String READY = "Neovim · local session";
    /** Matches init.lua's copy limit; Android's clipboard transactions are limited to about 1 MB. */
    private static final int MAX_CLIPBOARD_CHARS = 512 * 1024;

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    final ExecutorService files = Executors.newSingleThreadExecutor(r -> new Thread(r, "editor-files"));
    final Documents documents;
    private NvimRpcClient rpc;
    private EditorCommands commands;
    private NeovimView view;
    private Observer observer;
    private boolean started;
    private boolean attached;
    private boolean initialized;
    private final CompletableFuture<Void> ready = new CompletableFuture<>();
    private String status = "Starting Neovim…";
    private boolean statusReady;
    private boolean stopped;
    /** The last text Neovim copied, so pasting it back keeps a linewise or blockwise register type. */
    private String copiedText;
    private String copiedType;

    EditorSession(Context context) {
        this.context = context.getApplicationContext();
        documents = new Documents(this.context, files);
    }

    void attach(NeovimView next, Observer nextObserver) {
        if (view != null && view != next) detach(view);
        view = next;
        observer = nextObserver;
        next.setListener(this);
        nextObserver.onStatus(status, statusReady);
        if (rpc != null) attachUi();
        if (!started) {
            started = true;
            files.execute(this::start);
        }
    }

    void detach(NeovimView previous) {
        if (view != previous) return;
        previous.setListener(null);
        view = null;
        observer = null;
        if (rpc != null && attached) request("nvim_ui_detach");
        attached = false;
    }

    private void start() {
        LocalSocket connected = null;
        try {
            File home = context.getFilesDir();
            File runtime = new File(home, "runtime");
            // Copy packaged runtime once per app update, before starting native code.
            long update = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).lastUpdateTime;
            File marker = new File(home, "runtime-version");
            if (!marker.isFile() || !Files.readString(marker.toPath()).equals(Long.toString(update))) {
                copyAsset("runtime", runtime);
                Files.writeString(marker.toPath(), Long.toString(update));
            }
            File notes = new File(home, "notes.txt");
            if (!notes.exists()) {
                Files.writeString(notes.toPath(), "Welcome to Neovim on Android.\n\n"
                        + "Press i to insert text; Esc returns to normal mode. Tap to move the cursor, "
                        + "drag to scroll, long-press and drag to select, and pinch to change the font size.\n\n"
                        + "Save writes this file to the app's private storage. Edits are also saved when "
                        + "the app goes to the background.\n\n"
                        + "Open edits a document from another app; saving updates the original when Android "
                        + "grants write access. Export saves a copy elsewhere.\n\n"
                        + "Copy and \"+y use Android's clipboard; Paste and \"+p read it.\n\n"
                        + "Run :Tutor to learn Neovim. :e $MYVIMRC edits your configuration.\n\n"
                        + "The editor and Android UI run in one process: there is no shell, job control, "
                        + "language server, or plugin installer.\n");
            }
            String init;
            try (InputStream in = context.getAssets().open("android/init.lua")) {
                init = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            String path = new File(home, "nvim.sock").getAbsolutePath();
            NativeBridge.start(home.getAbsolutePath(), path);
            long deadline = System.nanoTime() + 20_000_000_000L;
            while (true) {
                String failure = NativeBridge.failure();
                if (failure != null) throw new IOException(failure);
                connected = new LocalSocket();
                try {
                    connected.connect(new LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM));
                    break;
                } catch (IOException pending) {
                    connected.close();
                    connected = null;
                    if (System.nanoTime() > deadline) throw new IOException("Neovim did not start", pending);
                    Thread.sleep(25);
                }
            }
            LocalSocket transport = connected;
            watchNativeFailure(transport);
            NvimRpcClient client = new NvimRpcClient(
                    transport.getInputStream(), transport.getOutputStream(), main::post,
                    new NvimRpcClient.Listener() {
                        @Override
                        public void onNotification(String method, List<?> args) {
                            switch (method) {
                                case "redraw" -> { if (view != null) view.handleRedraw(args); }
                                case "android_clipboard_set" -> copy(args);
                                case "android_document_written" -> {
                                    if (!args.isEmpty() && args.get(0) instanceof String written) documentWritten(new File(written));
                                }
                                default -> { }
                            }
                        }

                        @Override
                        public CompletableFuture<?> onRequest(String method, List<?> args) {
                            return method.equals("android_clipboard_get") ? CompletableFuture.completedFuture(clipboard()) : null;
                        }

                        @Override
                        public void onClosed(Throwable cause) {
                            attached = false;
                            rpc = null;
                            if (commands != null) commands.close(cause);
                            try { transport.close(); } catch (IOException ignored) { }
                            stopped = true;
                            String failure = NativeBridge.failure();
                            setStatus(failure != null ? failure : "Editor stopped. Restart the app to start a new session.", false);
                        }
                    });
            main.post(() -> {
                rpc = client;
                client.start();
                commands = new EditorCommands(client::request, main::post,
                        retry -> main.postDelayed(retry, 10));
                // Names this UI in nvim_get_chan_info() and :checkhealth.
                client.request("nvim_set_client_info", "android-neovim", clientVersion(), "ui", Map.of(), Map.of());
                client.request("nvim_get_api_info")
                        .thenCompose(info -> client.request("nvim_exec_lua", init, List.of(((List<?>) info).get(0),
                                home.getAbsolutePath(), notes.getAbsolutePath(), documents.directory.getAbsolutePath())))
                        .whenComplete((ignored, error) -> main.post(() -> {
                            if (error != null) {
                                setStatus("Editor setup failed: " + error.getMessage(), false);
                                ready.completeExceptionally(error);
                            } else {
                                initialized = true;
                                attachUi();
                                ready.complete(null);
                            }
                        }));
            });
        } catch (Throwable error) {
            if (connected != null) try { connected.close(); } catch (IOException ignored) { }
            main.post(() -> {
                setStatus("Cannot start Neovim: " + error.getMessage(), false);
                ready.completeExceptionally(error);
            });
        }
    }

    /**
     * Neovim's thread can end without closing its sockets, such as when an exit() skips its teardown.
     * Shutting the socket down wakes the blocked reader, which close() does not.
     */
    private void watchNativeFailure(LocalSocket transport) {
        Thread watcher = new Thread(() -> {
            String failure = NativeBridge.awaitFailure();
            try { transport.shutdownInput(); } catch (IOException ignored) { }
            try { transport.shutdownOutput(); } catch (IOException ignored) { }
            main.post(() -> { if (stopped) setStatus(failure, false); });
        }, "neovim-exit");
        watcher.setDaemon(true);
        watcher.start();
    }

    /** The app's versionName, such as 0.1, as a client-info version dictionary. */
    private Map<String, Object> clientVersion() {
        Map<String, Object> version = new HashMap<>();
        try {
            String[] parts = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName.split("\\.");
            String[] keys = {"major", "minor", "patch"};
            for (int i = 0; i < Math.min(keys.length, parts.length); i++) version.put(keys[i], Long.parseLong(parts[i]));
        } catch (Exception unknown) {
            // The version is informational.
        }
        return version;
    }

    private void copyAsset(String name, File destination) throws IOException {
        String[] entries = context.getAssets().list(name);
        if (entries != null && entries.length > 0) {
            Files.createDirectories(destination.toPath());
            for (String entry : entries) copyAsset(name + "/" + entry, new File(destination, entry));
        } else {
            Files.createDirectories(destination.toPath().getParent());
            try (InputStream in = context.getAssets().open(name); OutputStream out = Files.newOutputStream(destination.toPath())) {
                in.transferTo(out);
            }
        }
    }

    private void attachUi() {
        if (view == null || rpc == null || attached || !initialized) return;
        NeovimView target = view;
        attached = true;
        int night = target.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        request("nvim_exec_lua", "AndroidNeovim.theme(...)", List.of(night != Configuration.UI_MODE_NIGHT_NO));
        request("nvim_ui_attach", Math.max(1, target.columns()), Math.max(1, target.rows()),
                Map.of("rgb", true, "ext_linegrid", true, "ext_multigrid", false))
                .whenComplete((ignored, error) -> main.post(() -> {
                    if (view != target) return;
                    if (error != null) {
                        attached = false;
                        setStatus("Editor setup failed: " + error.getMessage(), false);
                    }
                    else setStatus(READY, true);
                }));
        // Later window focus changes come from the view.
        if (target.hasWindowFocus()) request("nvim_ui_set_focus", true);
    }

    CompletableFuture<Object> request(String method, Object... args) {
        if (rpc == null || commands == null) return CompletableFuture.failedFuture(new IOException("Editor is not connected"));
        return commands.request(method, args);
    }

    boolean stopped() { return stopped; }

    String status() { return status; }

    /** Opens a file once the editor has started; callbacks run on the main thread. */
    CompletableFuture<Object> edit(File file) {
        return ready.thenComposeAsync(ignored -> request("nvim_exec_lua", "vim.cmd.edit(vim.fn.fnameescape(...))",
                List.of(file.getAbsolutePath())), main::post);
    }

    /** Android may end a stopped app without notice; save what Neovim can save first. */
    void background() {
        if (initialized && rpc != null) request("nvim_exec_lua", "AndroidNeovim.background()", List.of());
    }

    /** Neovim's lines arrive joined by newlines: one string, however many lines. */
    private void copy(List<?> args) {
        if (args.size() < 2 || !(args.get(0) instanceof String text) || !(args.get(1) instanceof String type)) return;
        ClipboardManager clipboard = context.getSystemService(ClipboardManager.class);
        if (clipboard == null) return;
        try {
            clipboard.setPrimaryClip(ClipData.newPlainText("Neovim", text));
        } catch (RuntimeException error) {
            // Such as a clip that exceeds Android's transaction limit.
            message("Copy failed: " + rootMessage(error));
            return;
        }
        copiedText = text;
        copiedType = type;
    }

    /**
     * The clipboard as [text, regtype], which init.lua splits at newlines. Linewise and blockwise text
     * ends with a newline; an empty regtype lets Neovim infer it. Text from other apps gets Unix line
     * breaks. Android reports empty when the app is unfocused.
     */
    private List<Object> clipboard() {
        ClipboardManager clipboard = context.getSystemService(ClipboardManager.class);
        ClipData clip = clipboard == null ? null : clipboard.getPrimaryClip();
        CharSequence value = clip == null || clip.getItemCount() == 0 ? null : clip.getItemAt(0).coerceToText(context);
        String text = value == null ? "" : value.toString();
        if (text.length() > MAX_CLIPBOARD_CHARS) throw new IllegalStateException("Clipboard exceeds 512 Ki characters");
        if (text.equals(copiedText)) return List.of(text, copiedType);
        return List.of(text.replace("\r\n", "\n"), "");
    }

    private void documentWritten(File local) {
        documents.written(local).whenComplete((uri, error) -> main.post(() -> {
            if (error != null) message("Saved locally; updating the original failed: " + rootMessage(error));
            else if (uri != null) message("Saved " + local.getName());
        }));
    }

    void message(String text) {
        if (observer != null) observer.onMessage(text);
    }

    static String rootMessage(Throwable error) {
        while (error.getCause() != null) error = error.getCause();
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    @Override
    public void input(String keys) {
        if (rpc == null || !attached || keys.isEmpty()) return;
        commands.input(keys).exceptionally(error -> {
            main.post(() -> {
                if (rpc != null) message("Input failed: " + rootMessage(error));
            });
            return null;
        });
    }

    @Override
    public void paste(String text) {
        CompletableFuture<Object> pasted = rpc == null || commands == null
                ? CompletableFuture.failedFuture(new IOException("Editor is not connected")) : commands.paste(text);
        pasted.exceptionally(error -> {
            main.post(() -> message("Paste failed: " + rootMessage(error)));
            return null;
        });
    }

    @Override
    public void mouse(String button, String action, String modifiers, int row, int col) {
        if (attached) request("nvim_input_mouse", button, action, modifiers, 0, row, col);
    }

    @Override
    public void focus(boolean gained) {
        if (attached) request("nvim_ui_set_focus", gained);
    }

    @Override
    public void resize(int cols, int rows) {
        if (attached) request("nvim_ui_try_resize", cols, rows);
    }

    private void setStatus(String message, boolean ready) {
        status = message;
        statusReady = ready;
        if (observer != null) observer.onStatus(message, ready);
    }
}
