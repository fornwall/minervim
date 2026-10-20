package net.fornwall.androidneovim;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

/** Files named by the URI's last segment, reporting a name, size and modification time like a document provider. */
public final class TestDocumentProvider extends ContentProvider {
    static final String AUTHORITY = "net.fornwall.androidneovim.test.documents";

    static Uri uri(String name) { return new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(name).build(); }

    private File file(Uri uri) {
        String name = uri.getLastPathSegment();
        if (name == null || name.contains("/") || name.startsWith(".")) throw new IllegalArgumentException("Invalid name");
        return new File(getContext().getFilesDir(), name);
    }

    @Override public boolean onCreate() { return true; }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] arguments, String order) {
        File file = file(uri);
        if (projection == null) projection = new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor cursor = new MatrixCursor(projection);
        Object[] row = new Object[projection.length];
        for (int i = 0; i < projection.length; i++) {
            row[i] = switch (projection[i]) {
                case OpenableColumns.DISPLAY_NAME -> file.getName();
                case OpenableColumns.SIZE -> file.length();
                case DocumentsContract.Document.COLUMN_LAST_MODIFIED -> file.lastModified();
                default -> null;
            };
        }
        cursor.addRow(row);
        return cursor;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.parseMode(mode));
    }

    @Override public String getType(Uri uri) { return "text/plain"; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] arguments) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] arguments) {
        throw new UnsupportedOperationException();
    }
}
