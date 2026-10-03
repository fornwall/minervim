package net.fornwall.androidneovim;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fornwall.androidneovim.ui.NeovimView;

public final class MainActivity extends Activity {
    private static final int OPEN = 1;
    private static final int EXPORT = 2;
    private static final int MAX_DOCUMENT_BYTES = 4 * 1024 * 1024;
    private final List<Button> actions = new ArrayList<>();
    private EditorSession session;
    private NeovimView editor;
    private TextView status;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        session = ((EditorApplication) getApplication()).session();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(22, 27, 34));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            var padding = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            view.setPadding(padding.left, padding.top, padding.right, padding.bottom);
            return WindowInsets.CONSUMED;
        });
        status = new TextView(this);
        status.setTextColor(0xffa9b1bd);
        status.setTextSize(12);
        status.setPadding(dp(12), dp(6), dp(12), dp(4));
        status.setText(R.string.starting_editor);
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout toolbar = row();
        action(toolbar, "Open", "Import a text document", this::open);
        action(toolbar, "Save", "Save the current local file", this::save);
        action(toolbar, "Export", "Export a copy of the current file", this::export);
        action(toolbar, "Paste", "Paste clipboard into Neovim", this::paste);
        action(toolbar, "Keys", "Show the software keyboard", () -> {
            editor.requestFocus();
            getSystemService(InputMethodManager.class).showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT);
        });
        root.addView(toolbar);
        editor = new NeovimView(this);
        editor.setId(View.generateViewId());
        root.addView(editor, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout extraKeys = row();
        key(extraKeys, "Esc", "Escape", "<Esc>");
        key(extraKeys, "Tab", "Tab", "<Tab>");
        key(extraKeys, ":", "Command line", ":");
        key(extraKeys, "←", "Left", "<Left>");
        key(extraKeys, "↓", "Down", "<Down>");
        key(extraKeys, "↑", "Up", "<Up>");
        key(extraKeys, "→", "Right", "<Right>");
        root.addView(extraKeys);
        setContentView(root);
        root.requestApplyInsets();
        session.attach(editor, (message, ready) -> {
            status.setText(message);
            for (Button action : actions) action.setEnabled(ready);
        });
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(48)));
        return row;
    }

    private void action(LinearLayout row, String label, String description, Runnable action) {
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
        session.request("nvim_exec_lua", "vim.cmd.write()", List.of()).whenComplete((ignored, error) ->
                message(error == null ? "Saved locally" : "Save failed: " + rootMessage(error)));
    }

    private void export() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE, "notes.txt");
        startActivityForResult(intent, EXPORT);
    }

    private void paste() {
        editor.pasteClipboard();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == OPEN) session.files.execute(() -> importDocument(uri));
        if (requestCode == EXPORT) {
            session.request("nvim_exec_lua", "vim.cmd.write(); return vim.api.nvim_buf_get_name(0)", List.of())
                    .whenComplete((path, error) -> {
                        if (error != null) message("Export failed: " + rootMessage(error));
                        else session.files.execute(() -> exportDocument(uri, new File((String) path)));
                    });
        }
    }

    private void importDocument(Uri uri) {
        try {
            String name = "document.txt";
            try (var cursor = getContentResolver().query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) name = cursor.getString(0);
            }
            name = name.replaceAll("[^\\p{L}\\p{N}._ -]", "_");
            if (name.isBlank() || name.equals(".") || name.equals("..")) name = "document.txt";
            if (name.length() > 120) name = name.substring(name.length() - 120);
            byte[] content;
            try (InputStream stream = getContentResolver().openInputStream(uri)) {
                if (stream == null) throw new IOException("Cannot read document");
                content = stream.readNBytes(MAX_DOCUMENT_BYTES + 1);
            }
            if (content.length > MAX_DOCUMENT_BYTES) throw new IOException("Document exceeds the 4 MiB import limit");
            File directory = new File(getFilesDir(), "documents/" + UUID.randomUUID());
            Files.createDirectories(directory.toPath());
            File destination = new File(directory, name);
            Files.write(destination.toPath(), content);
            runOnUiThread(() -> session.request("nvim_exec_lua", "vim.cmd.edit(vim.fn.fnameescape(...))",
                    List.of(destination.getAbsolutePath())).whenComplete((ignored, error) ->
                    message(error == null ? "Opened a local copy; use Export to share changes"
                            : "Open failed: " + rootMessage(error))));
        } catch (Exception error) {
            message("Open failed: " + rootMessage(error));
        }
    }

    private void exportDocument(Uri uri, File source) {
        try {
            byte[] content;
            try (InputStream in = Files.newInputStream(source.toPath())) {
                content = in.readNBytes(MAX_DOCUMENT_BYTES + 1);
            }
            if (content.length > MAX_DOCUMENT_BYTES) throw new IOException("File exceeds the 4 MiB export limit");
            try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                if (out == null) throw new IOException("Cannot write document");
                out.write(content);
            }
            message("Exported a copy");
        } catch (Exception error) {
            message("Export failed: " + rootMessage(error));
        }
    }

    private static String rootMessage(Throwable error) {
        while (error.getCause() != null) error = error.getCause();
        return error.getMessage();
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

    @Override
    protected void onDestroy() {
        session.detach(editor);
        super.onDestroy();
    }
}
