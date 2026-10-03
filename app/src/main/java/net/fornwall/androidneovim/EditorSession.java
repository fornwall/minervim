package net.fornwall.androidneovim;

import android.content.Context;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
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
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    final ExecutorService files = Executors.newSingleThreadExecutor(r -> new Thread(r, "editor-files"));
    private NvimRpcClient rpc;
    private EditorCommands commands;
    private NeovimView view;
    private Observer observer;
    private boolean started;
    private boolean attached;
    private boolean initialized;
    private String status = "Starting Neovim…";

    EditorSession(Context context) {
        this.context = context.getApplicationContext();
    }

    void attach(NeovimView next, Observer nextObserver) {
        if (view != null && view != next) detach(view);
        view = next;
        observer = nextObserver;
        next.setListener(this);
        nextObserver.onStatus(status, false);
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
                        + "Press i to insert text; Esc returns to normal mode.\n"
                        + "Save writes this file to the app's private storage.\n"
                        + "Open imports a document; Export saves a copy through Android.\n"
                        + "Your notes survive closing and reopening the app.\n\n"
                        + "The editor and Android UI run in one process.\n"
                        + "This initial build has no shell, language servers, or plugin installer.\n");
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
            NvimRpcClient client = new NvimRpcClient(
                    transport.getInputStream(), transport.getOutputStream(), main::post,
                    new NvimRpcClient.Listener() {
                        @Override
                        public void onNotification(String method, List<?> args) {
                            if (method.equals("redraw") && view != null) view.handleRedraw(args);
                        }

                        @Override
                        public void onClosed(Throwable cause) {
                            attached = false;
                            rpc = null;
                            if (commands != null) commands.close(cause);
                            try { transport.close(); } catch (IOException ignored) { }
                            setStatus("Editor stopped. Force stop and reopen the app to start a new session.", false);
                        }
                    });
            main.post(() -> {
                rpc = client;
                client.start();
                commands = new EditorCommands(client::request, main::post,
                        retry -> main.postDelayed(retry, 10));
                client.request("nvim_exec_lua", "vim.opt.termguicolors=true; vim.opt.mouse='a'; "
                        + "vim.opt.number=true; vim.opt.swapfile=false; vim.opt.shortmess:append('I'); "
                        + "if vim.fn.filereadable(vim.env.VIMRUNTIME..'/doc/tags')==0 then "
                        + "vim.cmd.helptags(vim.fn.fnameescape(vim.env.VIMRUNTIME..'/doc')) end; "
                        + "vim.cmd('filetype plugin indent on'); vim.cmd('syntax enable'); "
                        + "vim.cmd.edit(vim.fn.fnameescape(...))", List.of(notes.getAbsolutePath()))
                        .whenComplete((ignored, error) -> main.post(() -> {
                            if (error != null) setStatus("Editor setup failed: " + error.getMessage(), false);
                            else {
                                initialized = true;
                                attachUi();
                            }
                        }));
            });
        } catch (Throwable error) {
            if (connected != null) try { connected.close(); } catch (IOException ignored) { }
            main.post(() -> setStatus("Cannot start Neovim: " + error.getMessage(), false));
        }
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
        request("nvim_ui_attach", Math.max(1, target.columns()), Math.max(1, target.rows()),
                Map.of("rgb", true, "ext_linegrid", true, "ext_multigrid", false))
                .whenComplete((ignored, error) -> main.post(() -> {
                    if (view != target) return;
                    if (error != null) {
                        attached = false;
                        setStatus("Editor setup failed: " + error.getMessage(), false);
                    }
                    else setStatus("Neovim · local session", true);
                }));
    }

    CompletableFuture<Object> request(String method, Object... args) {
        if (rpc == null || commands == null) return CompletableFuture.failedFuture(new IOException("Editor is not connected"));
        return commands.request(method, args);
    }

    @Override
    public void input(String keys) {
        if (rpc == null || !attached || keys.isEmpty()) return;
        commands.input(keys).exceptionally(error -> {
            main.post(() -> {
                if (rpc != null) setStatus("Input failed: " + error.getMessage(), true);
            });
            return null;
        });
    }

    @Override
    public void paste(String text) {
        request("nvim_paste", text, false, -1).exceptionally(error -> {
            main.post(() -> setStatus("Paste failed: " + error.getMessage(), true));
            return null;
        });
    }

    @Override
    public void mouse(String button, String action, String modifiers, int row, int col) {
        if (attached) request("nvim_input_mouse", button, action, modifiers, 0, row, col);
    }

    @Override
    public void resize(int cols, int rows) {
        if (attached) request("nvim_ui_try_resize", cols, rows);
    }

    private void setStatus(String message, boolean ready) {
        status = message;
        if (observer != null) observer.onStatus(message, ready);
    }
}
