package com.minervim;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;

/** Directory tree operations on app storage. Symbolic links are removed, never followed. */
final class FileTrees {
    private FileTrees() {}

    /** Deletes a file or directory tree; a missing one is not an error. */
    static void delete(File file) throws IOException {
        if (Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) delete(child);
        }
        Files.deleteIfExists(file.toPath());
    }
}
