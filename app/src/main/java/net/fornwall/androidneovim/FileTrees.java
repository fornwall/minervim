package net.fornwall.androidneovim;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;

/** Directory tree operations on app storage. Symbolic links are removed, never followed. */
final class FileTrees {
    interface Filler { void fill(File directory) throws IOException; }

    private FileTrees() {}

    /** Deletes a file or directory tree; a missing one is not an error. */
    static void delete(File file) throws IOException {
        if (Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) delete(child);
        }
        Files.deleteIfExists(file.toPath());
    }

    /**
     * Replaces a directory tree with one that {@code filler} creates beside it, so no file of the old
     * tree remains. If filling fails, the old tree is kept.
     */
    static void replace(File directory, Filler filler) throws IOException {
        File staging = new File(directory.getPath() + ".tmp");
        delete(staging);
        filler.fill(staging);
        delete(directory);
        Files.move(staging.toPath(), directory.toPath(), StandardCopyOption.ATOMIC_MOVE);
    }
}
