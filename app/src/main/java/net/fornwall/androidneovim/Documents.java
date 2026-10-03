package net.fornwall.androidneovim;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.net.Uri;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Local copies of Android documents. A writable original is remembered by local path, so a
 * Neovim write can update it. All file and provider I/O runs on the serial file executor.
 */
final class Documents {
    static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final int MAX_REMEMBERED = 64;
    private final Context context;
    private final Executor files;
    private final SharedPreferences originals;
    private final SecureRandom random = new SecureRandom();
    final File directory;

    Documents(Context context, Executor files) {
        this.context = context.getApplicationContext();
        this.files = files;
        originals = this.context.getSharedPreferences("documents", Context.MODE_PRIVATE);
        directory = new File(this.context.getFilesDir(), "documents");
    }

    /** Copies a document into app storage. Write grants make later saves update the original. */
    CompletableFuture<File> importDocument(Uri uri, int grants) {
        return supply(() -> {
            String name = "document.txt";
            try (var cursor = context.getContentResolver().query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) name = cursor.getString(0);
            } catch (RuntimeException unavailable) {
                // Some providers reject metadata queries but still open their documents.
            }
            byte[] content;
            try (InputStream stream = context.getContentResolver().openInputStream(uri)) {
                if (stream == null) throw new IOException("Cannot read document");
                content = stream.readNBytes(MAX_BYTES + 1);
            }
            if (content.length > MAX_BYTES) throw new IOException("Document exceeds the 4 MiB import limit");
            File destination = store(name, content);
            if ((grants & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0) remember(destination, uri, grants);
            return destination;
        });
    }

    /** Stores shared text as a new local document. */
    CompletableFuture<File> importText(String name, String text) {
        return supply(() -> {
            byte[] content = text.getBytes(StandardCharsets.UTF_8);
            if (content.length > MAX_BYTES) throw new IOException("Text exceeds the 4 MiB import limit");
            return store(name, content);
        });
    }

    /** Called after Neovim writes a local copy. Resolves to the original's URI, or null if none. */
    CompletableFuture<Uri> written(File local) {
        return supply(() -> {
            String original = originals.getString(local.getCanonicalPath(), null);
            if (original == null) return null;
            Uri uri = Uri.parse(original);
            copy(local, uri);
            return uri;
        });
    }

    CompletableFuture<Uri> export(File local, Uri destination) {
        return supply(() -> {
            copy(local, destination);
            return destination;
        });
    }

    private File store(String name, byte[] content) throws IOException {
        name = name.replaceAll("[^\\p{L}\\p{N}._ -]", "_");
        if (name.isBlank() || name.equals(".") || name.equals("..")) name = "document.txt";
        if (name.length() > 120) name = name.substring(name.length() - 120);
        // A short random directory keeps names distinct without crowding Neovim's statusline.
        Files.createDirectories(directory.toPath());
        File parent;
        do parent = new File(directory, Long.toString(random.nextLong() >>> 24, 36));
        while (parent.exists());
        Files.createDirectory(parent.toPath());
        File destination = new File(parent, name);
        Files.write(destination.toPath(), content);
        return destination;
    }

    private void copy(File local, Uri destination) throws IOException {
        byte[] content;
        try (InputStream in = Files.newInputStream(local.toPath())) {
            content = in.readNBytes(MAX_BYTES + 1);
        }
        if (content.length > MAX_BYTES) throw new IOException("File exceeds the 4 MiB export limit");
        try (OutputStream out = context.getContentResolver().openOutputStream(destination, "wt")) {
            if (out == null) throw new IOException("Cannot write document");
            out.write(content);
        }
    }

    private void remember(File local, Uri uri, int grants) throws IOException {
        if ((grants & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0) {
            try {
                context.getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (SecurityException notPersistable) {
                // The grant still lasts for this process.
            }
        }
        SharedPreferences.Editor editor = originals.edit();
        // A re-imported original is now edited through its newest copy.
        for (Map.Entry<String, ?> entry : originals.getAll().entrySet()) {
            if (uri.toString().equals(entry.getValue())) editor.remove(entry.getKey());
        }
        editor.putString(local.getCanonicalPath(), uri.toString()).apply();
        Map<String, ?> all = originals.getAll();
        if (all.size() <= MAX_REMEMBERED) return;
        // Forget the least recently written copies and their persisted grants.
        editor = originals.edit();
        var oldest = all.keySet().stream().sorted(Comparator.comparingLong(path -> new File(path).lastModified()))
                .limit(all.size() - MAX_REMEMBERED).toList();
        for (String path : oldest) editor.remove(path);
        editor.apply();
        for (String path : oldest) release(Uri.parse(String.valueOf(all.get(path))));
    }

    private void release(Uri uri) {
        if (originals.getAll().containsValue(uri.toString())) return;
        for (UriPermission permission : context.getContentResolver().getPersistedUriPermissions()) {
            if (permission.getUri().equals(uri)) {
                context.getContentResolver().releasePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            }
        }
    }

    private interface IoSupplier<T> { T get() throws Exception; }

    private <T> CompletableFuture<T> supply(IoSupplier<T> work) {
        CompletableFuture<T> result = new CompletableFuture<>();
        files.execute(() -> {
            try { result.complete(work.get()); }
            catch (Exception error) { result.completeExceptionally(error); }
        });
        return result;
    }
}
