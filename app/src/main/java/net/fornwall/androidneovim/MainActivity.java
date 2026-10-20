package net.fornwall.androidneovim;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.MimeTypeMap;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.fornwall.androidneovim.ui.NeovimView;

public final class MainActivity extends Activity {
    private static final int OPEN = 1;
    private static final int EXPORT = 2;
    private static final String EXPORTING = "exporting";
    private final List<Button> actions = new ArrayList<>();
    private EditorSession session;
    private NeovimView editor;
    private LinearLayout statusRow;
    private TextView status;
    private Button restart;
    /** The file being exported; it outlives process death while Android's picker is shown. */
    private String exporting;
    // Back leaves Insert, Visual and other modes. In Normal mode, Android handles it, with its
    // predictive animation; that needs the callback registered ahead of the gesture.
    private final OnBackInvokedCallback escape = () -> session.input("<Esc>");
    private boolean editorReady;
    private boolean normalMode = true;
    private boolean escapes;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        session = ((EditorApplication) getApplication()).session();
        if (state != null) exporting = state.getString(EXPORTING);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.chrome));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            var padding = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            view.setPadding(padding.left, padding.top, padding.right, padding.bottom);
            return WindowInsets.CONSUMED;
        });
        statusRow = row();
        status = new TextView(this);
        status.setTextColor(getColor(R.color.status));
        status.setTextSize(12);
        status.setPadding(dp(12), 0, dp(12), 0);
        status.setText(R.string.starting_editor);
        statusRow.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        restart = new Button(this);
        restart.setText(R.string.restart);
        restart.setAllCaps(false);
        restart.setVisibility(View.GONE);
        restart.setOnClickListener(ignored -> restart());
        statusRow.addView(restart, new LinearLayout.LayoutParams(-2, -1));
        root.addView(statusRow);

        LinearLayout toolbar = row();
        action(toolbar, "Open", "Open a document from another app", this::open);
        action(toolbar, "Save", "Save the current file", this::save);
        action(toolbar, "Export", "Export a copy of the current file", this::export);
        action(toolbar, "Copy", "Copy the selection or current line to the clipboard", this::copy);
        action(toolbar, "Paste", "Paste clipboard into Neovim", () -> editor.pasteClipboard());
        action(toolbar, "Keys", "Show the software keyboard", () -> editor.showKeyboard());
        root.addView(toolbar);
        editor = new NeovimView(this, session.grid);
        // A stable id lets a recreated Activity restore the editor's state.
        editor.setId(R.id.editor);
        root.addView(editor, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout extraKeys = row();
        key(extraKeys, "Esc", "Escape", "<Esc>");
        // Sticky modifiers apply to the next key from this row, the soft keyboard, or hardware.
        Button ctrl = action(extraKeys, "Ctrl", "Control modifier for the next key", () -> editor.toggleCtrl());
        Button alt = action(extraKeys, "Alt", "Alt modifier for the next key", () -> editor.toggleAlt());
        ColorStateList keyColors = ctrl.getTextColors();
        editor.setModifierListener((ctrlOn, altOn) -> {
            for (Button button : List.of(ctrl, alt)) {
                boolean on = button == ctrl ? ctrlOn : altOn;
                button.setSelected(on);
                if (on) button.setTextColor(getColor(R.color.accent));
                else button.setTextColor(keyColors);
                button.setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
            }
        });
        key(extraKeys, "Tab", "Tab", "<Tab>");
        key(extraKeys, ":", "Command line", ":");
        key(extraKeys, "/", "Search", "/");
        key(extraKeys, "←", "Left", "<Left>");
        key(extraKeys, "↓", "Down", "<Down>");
        key(extraKeys, "↑", "Up", "<Up>");
        key(extraKeys, "→", "Right", "<Right>");
        root.addView(extraKeys);
        setContentView(root);
        root.requestApplyInsets();
        // Keys go to the editor from the start; the soft keyboard still waits for a tap (stateHidden).
        editor.requestFocus();
        session.attach(editor, new EditorSession.Observer() {
            @Override public void onStatus(String message, boolean ready) {
                status.setText(message);
                // Hide the status row while the editor runs normally; Neovim's own UI shows its state.
                statusRow.setVisibility(ready && message.equals(EditorSession.READY) ? View.GONE : View.VISIBLE);
                restart.setVisibility(session.stopped() ? View.VISIBLE : View.GONE);
                for (Button action : actions) action.setEnabled(ready);
                editorReady = ready;
                updateBack();
            }
            @Override public void onMessage(String message) { message(message); }
            @Override public void onModeChanged(String mode) {
                normalMode = mode.equals("normal");
                updateBack();
            }
        });
        if (state == null) handle(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handle(intent);
    }

    /** Opens documents and text shared or sent to the app. */
    private void handle(Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        int grants = intent.getFlags();
        switch (intent.getAction()) {
            case Intent.ACTION_VIEW, Intent.ACTION_EDIT -> {
                if (intent.getData() != null) open(session.documents.importDocument(intent.getData(), grants));
            }
            case Intent.ACTION_SEND -> {
                Uri stream = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
                CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
                if (stream != null) open(session.documents.importDocument(stream, grants));
                else if (text != null) open(session.documents.importText("shared.txt", text.toString()));
            }
            default -> { }
        }
    }

    private void updateBack() {
        boolean next = editorReady && !normalMode;
        if (next == escapes) return;
        escapes = next;
        if (next) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, escape);
        else getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(escape);
    }

    private void open(CompletableFuture<File> imported) {
        imported.thenCompose(file -> session.edit(file)).whenComplete((ignored, error) -> {
            if (error != null) message("Open failed: " + EditorSession.rootMessage(error));
        });
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(48)));
        return row;
    }

    private Button action(LinearLayout row, String label, String description, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(12);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(0, 0, 0, 0);
        button.setContentDescription(description);
        button.setEnabled(false);
        button.setOnClickListener(ignored -> action.run());
        row.addView(button, new LinearLayout.LayoutParams(0, -1, 1));
        actions.add(button);
        return button;
    }

    private void key(LinearLayout row, String label, String description, String input) {
        action(row, label, description, () -> {
            editor.sendKey(input);
            editor.requestFocus();
        });
    }

    private void open() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, OPEN);
    }

    private void save() {
        session.request("nvim_exec_lua", "vim.cmd.write()", List.of()).whenComplete((ignored, error) -> {
            if (error != null) message("Save failed: " + EditorSession.rootMessage(error));
        });
    }

    /** Writes the current file before choosing a destination, so the editor is not needed for the result. */
    private void export() {
        String write = "vim.cmd.write(); return vim.api.nvim_buf_get_name(0)";
        session.request("nvim_exec_lua", write, List.of()).whenComplete((path, error) -> {
            if (error != null || !(path instanceof String file)) {
                message("Export failed: " + (error == null ? "no file name" : EditorSession.rootMessage(error)));
                return;
            }
            exporting = file;
            String name = new File(exporting).getName();
            int dot = name.lastIndexOf('.');
            String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
            String type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            // Providers append an extension for a type that the name's extension does not match.
            intent.setType(type == null ? "application/octet-stream" : type);
            intent.putExtra(Intent.EXTRA_TITLE, name);
            startActivityForResult(intent, EXPORT);
        });
    }

    /** Copies the visual selection, or the current line outside visual mode, to Android's clipboard. */
    private void copy() {
        session.request("nvim_get_mode").whenComplete((mode, error) -> {
            String current = mode instanceof Map<?, ?> map ? String.valueOf(map.get("mode")) : "";
            if (current.startsWith("v") || current.startsWith("V") || current.startsWith("\u0016")) session.input("\"+y");
            else session.request("nvim_exec_lua", "vim.fn.setreg('+', vim.api.nvim_get_current_line(), 'V')", List.of());
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        String exported = exporting;
        if (requestCode == EXPORT) exporting = null;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == OPEN) open(session.documents.importDocument(uri, data.getFlags()));
        if (requestCode == EXPORT && exported != null) {
            session.documents.export(new File(exported), uri).whenComplete((ignored, error) -> message(error == null
                    ? "Exported a copy" : "Export failed: " + EditorSession.rootMessage(error)));
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString(EXPORTING, exporting);
    }

    /** Neovim's global state cannot be reinitialized in-process, so start a fresh app process. */
    private void restart() {
        startActivity(Intent.makeRestartActivityTask(getComponentName()));
        Runtime.getRuntime().exit(0);
    }

    private void message(String text) {
        runOnUiThread(() -> {
            if (!isDestroyed()) Toast.makeText(this, text, Toast.LENGTH_LONG).show();
        });
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    NeovimView editorView() { return editor; }
    boolean backEscapes() { return escapes; }

    @Override
    protected void onStop() {
        // Recreation keeps the process and editor; only leaving the app risks losing edits.
        if (!isChangingConfigurations()) session.background();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        session.detach(editor, isFinishing());
        super.onDestroy();
    }
}
