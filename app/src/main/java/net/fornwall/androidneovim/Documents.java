package net.fornwall.androidneovim;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Local copies of Android documents. Each copy is remembered by local path with its original and the
 * version of the original it last matched, so a Neovim write can update a writable original that has
 * not changed elsewhere. All file and provider I/O runs on the serial file executor.
 */
final class Documents {
    /** What a Neovim write of a local copy did to its original. */
    enum WriteBack { NONE, UPDATED, READ_ONLY, CHANGED_ELSEWHERE, FORGOTTEN }

    static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final int MAX_REMEMBERED = 64;
    private final Context context;
    private final Executor files;
    private final SharedPreferences copies;
    private final SecureRandom random = new SecureRandom();
    final File directory;

    Documents(Context context, Executor files) {
        this.context = context.getApplicationContext();
        this.files = files;
        copies = this.context.getSharedPreferences("documents", Context.MODE_PRIVATE);
        directory = new File(this.context.getFilesDir(), "documents");
    }

    /** Copies a document into app storage. Write grants make later saves update the original. */
    CompletableFuture<File> importDocument(Uri uri, int grants) {
        return supply(() -> {
            // Other schemes, such as file paths into this app's own storage, are not documents.
            if (!ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
                throw new IOException("Only content: documents can be opened");
            }
            Metadata metadata = metadata(uri);
            byte[] content = read(uri);
            String hash = sha256(content);
            boolean writable = (grants & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0;
            if (writable && (grants & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0) {
                try {
                    context.getContentResolver().takePersistableUriPermission(uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                } catch (SecurityException notPersistable) {
                    // The grant still lasts for this process.
                }
            }
            // Reopening an original keeps its copy, and with it any open buffer, while their contents agree.
            for (Map.Entry<String, ?> entry : copies.getAll().entrySet()) {
                Copy copy = Copy.decode(String.valueOf(entry.getValue()));
                File local = new File(entry.getKey());
                if (copy.state() != Copy.FORGOTTEN && copy.uri().equals(uri.toString()) && hash.equals(localSha256(local))) {
                    char state = writable || copy.state() == Copy.WRITABLE ? Copy.WRITABLE : Copy.READ_ONLY;
                    Copy current = new Copy(state, metadata.modified, content.length, hash, copy.uri());
                    copies.edit().putString(entry.getKey(), current.encode()).commit();
                    return local;
                }
            }
            File destination = store(metadata.name == null ? "document.txt" : metadata.name, content);
            remember(destination, new Copy(writable ? Copy.WRITABLE : Copy.READ_ONLY,
                    metadata.modified, content.length, hash, uri.toString()));
            return destination;
        });
    }

    /** Stores shared text as a new local document. */
    CompletableFuture<File> importText(String name, String text) {
        return supply(() -> {
            byte[] content = text.getBytes(StandardCharsets.UTF_8);
            if (content.length > MAX_BYTES) throw new IOException("Text exceeds the 4 MiB import limit");
            File destination = store(name, content);
            remember(destination, new Copy(Copy.READ_ONLY, -1, content.length, sha256(content), ""));
            return destination;
        });
    }

    /** Called after Neovim writes a local copy: updates a writable original unless it changed elsewhere. */
    CompletableFuture<WriteBack> written(File local) {
        return supply(() -> {
            String path = local.getCanonicalPath();
            String value = copies.getString(path, null);
            Copy copy = value == null ? null : Copy.decode(value);
            if (copy == null || copy.uri().isEmpty()) return WriteBack.NONE;
            if (copy.state() == Copy.READ_ONLY) return WriteBack.READ_ONLY;
            if (copy.state() == Copy.FORGOTTEN) return WriteBack.FORGOTTEN;
            Uri uri = Uri.parse(copy.uri());
            Metadata original = metadata(uri);
            if (!copy.matches(original.modified, original.size, () -> sha256(read(uri)))) return WriteBack.CHANGED_ELSEWHERE;
            byte[] content = readLocal(local);
            write(uri, content);
            Metadata updated = metadata(uri);
            Copy current = new Copy(Copy.WRITABLE, updated.modified, content.length, sha256(content), copy.uri());
            copies.edit().putString(path, current.encode()).commit();
            return WriteBack.UPDATED;
        });
    }

    CompletableFuture<Uri> export(File local, Uri destination) {
        return supply(() -> {
            write(destination, readLocal(local));
            return destination;
        });
    }

    /**
     * Deletes forgotten copies that still hold the content they were imported or last saved with. Run
     * before Neovim starts, when no buffer can hold one; a copy with local changes is kept.
     */
    void prune() {
        File root = directory.getAbsoluteFile();
        try { root = root.getCanonicalFile(); } catch (IOException unresolved) { }
        SharedPreferences.Editor editor = copies.edit();
        for (Map.Entry<String, ?> entry : copies.getAll().entrySet()) {
            Copy copy = Copy.decode(String.valueOf(entry.getValue()));
            if (copy.state() != Copy.FORGOTTEN) continue;
            editor.remove(entry.getKey());
            // Paths are canonical. Files saved beside a copy, as with :saveas, keep its directory.
            File local = new File(entry.getKey());
            File parent = local.getParentFile();
            if (copy.sha256() != null && copy.sha256().equals(localSha256(local))
                    && parent != null && root.equals(parent.getParentFile())) {
                try {
                    Files.deleteIfExists(local.toPath());
                    Files.deleteIfExists(parent.toPath());
                } catch (IOException notEmpty) {
                    // Such as a DirectoryNotEmptyException.
                }
            }
        }
        editor.commit();
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

    private void remember(File local, Copy copy) throws IOException {
        SharedPreferences.Editor editor = copies.edit();
        // A reimported original is now edited through its newest copy.
        for (Map.Entry<String, ?> entry : copies.getAll().entrySet()) {
            Copy other = Copy.decode(String.valueOf(entry.getValue()));
            if (!copy.uri().isEmpty() && copy.uri().equals(other.uri()) && other.state() != Copy.FORGOTTEN) {
                editor.putString(entry.getKey(), other.forgotten().encode());
            }
        }
        editor.putString(local.getCanonicalPath(), copy.encode()).commit();
        List<String> remembered = new ArrayList<>();
        for (Map.Entry<String, ?> entry : copies.getAll().entrySet()) {
            if (Copy.decode(String.valueOf(entry.getValue())).state() != Copy.FORGOTTEN) remembered.add(entry.getKey());
        }
        if (remembered.size() <= MAX_REMEMBERED) return;
        // Forget the least recently written copies and their persisted grants.
        var oldest = remembered.stream().sorted(Comparator.comparingLong(path -> new File(path).lastModified()))
                .limit(remembered.size() - MAX_REMEMBERED).toList();
        editor = copies.edit();
        List<String> released = new ArrayList<>();
        for (String path : oldest) {
            Copy forgotten = Copy.decode(copies.getString(path, "")).forgotten();
            editor.putString(path, forgotten.encode());
            released.add(forgotten.uri());
        }
        editor.commit();
        for (String uri : released) if (!uri.isEmpty()) release(uri);
    }

    private void release(String uri) {
        for (Object value : copies.getAll().values()) {
            Copy copy = Copy.decode(String.valueOf(value));
            if (copy.state() != Copy.FORGOTTEN && copy.uri().equals(uri)) return;
        }
        for (UriPermission permission : context.getContentResolver().getPersistedUriPermissions()) {
            if (permission.getUri().toString().equals(uri)) {
                context.getContentResolver().releasePersistableUriPermission(permission.getUri(),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            }
        }
    }

    private record Metadata(String name, long modified, long size) {}

    /** The display name, last-modified time and size, each null or -1 when the provider does not report it. */
    private Metadata metadata(Uri uri) {
        String[][] projections = {
                {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED},
                {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}};
        for (String[] projection : projections) {
            try (Cursor cursor = context.getContentResolver().query(uri, projection, null, null, null)) {
                if (cursor == null || !cursor.moveToFirst()) break;
                int name = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                return new Metadata(name < 0 || cursor.isNull(name) ? null : cursor.getString(name),
                        number(cursor, DocumentsContract.Document.COLUMN_LAST_MODIFIED), number(cursor, OpenableColumns.SIZE));
            } catch (RuntimeException unsupported) {
                // Some providers reject a column, or any metadata query, but still open their documents.
            }
        }
        return new Metadata(null, -1, -1);
    }

    private static long number(Cursor cursor, String column) {
        int index = cursor.getColumnIndex(column);
        return index < 0 || cursor.isNull(index) ? -1 : cursor.getLong(index);
    }

    private byte[] read(Uri uri) throws IOException {
        try (InputStream stream = context.getContentResolver().openInputStream(uri)) {
            if (stream == null) throw new IOException("Cannot read document");
            byte[] content = stream.readNBytes(MAX_BYTES + 1);
            if (content.length > MAX_BYTES) throw new IOException("Document exceeds the 4 MiB import limit");
            return content;
        }
    }

    private void write(Uri destination, byte[] content) throws IOException {
        try (OutputStream out = context.getContentResolver().openOutputStream(destination, "wt")) {
            if (out == null) throw new IOException("Cannot write document");
            out.write(content);
        }
    }

    private static byte[] readLocal(File local) throws IOException {
        byte[] content;
        try (InputStream in = Files.newInputStream(local.toPath())) {
            content = in.readNBytes(MAX_BYTES + 1);
        }
        if (content.length > MAX_BYTES) throw new IOException("File exceeds the 4 MiB limit");
        return content;
    }

    /** The hash of a local copy, or null if it is missing or too large to be a document. */
    private static String localSha256(File local) {
        try { return sha256(readLocal(local)); }
        catch (IOException unreadable) { return null; }
    }

    static String sha256(byte[] content) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    /**
     * What is remembered about a local copy: whether it updates a writable original, has a read-only
     * one, or was forgotten, the original's URI (empty for shared text), and, from when the copy last
     * matched the original, the provider's last-modified time and the content's size and hash (-1 or
     * null if unknown).
     */
    record Copy(char state, long modified, long size, String sha256, String uri) {
        static final char WRITABLE = 'w', READ_ONLY = 'r', FORGOTTEN = 'x';

        String encode() { return state + " " + modified + " " + size + " " + (sha256 == null ? "-" : sha256) + " " + uri; }

        static Copy decode(String value) {
            String[] parts = value.split(" ", 5);
            if (parts.length == 5 && parts[0].length() == 1) {
                try {
                    return new Copy(parts[0].charAt(0), Long.parseLong(parts[1]), Long.parseLong(parts[2]),
                            parts[3].equals("-") ? null : parts[3], parts[4]);
                } catch (NumberFormatException legacy) {
                    // Fall through.
                }
            }
            // Earlier versions remembered only the URI of a writable original.
            return new Copy(WRITABLE, -1, -1, null, value);
        }

        Copy forgotten() { return new Copy(FORGOTTEN, modified, size, sha256, uri); }

        /**
         * Whether the original, with the reported last-modified time and size, is still the version this
         * copy matched. Providers may update their metadata some time after a write, so only an unchanged
         * time and size skip hashing the content.
         */
        boolean matches(long modified, long size, IoSupplier<String> sha256) throws Exception {
            if (modified > 0 && modified == this.modified && size >= 0 && size == this.size) return true;
            // A copy remembered by an earlier version has no hash to compare.
            return this.sha256 == null || this.sha256.equals(sha256.get());
        }
    }

    interface IoSupplier<T> { T get() throws Exception; }

    private <T> CompletableFuture<T> supply(IoSupplier<T> work) {
        CompletableFuture<T> result = new CompletableFuture<>();
        files.execute(() -> {
            try { result.complete(work.get()); }
            catch (Exception error) { result.completeExceptionally(error); }
        });
        return result;
    }
}
