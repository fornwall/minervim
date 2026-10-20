package net.fornwall.androidneovim;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class FileTreesTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void replaceLeavesNoFileOfTheOldTree() throws IOException {
        File runtime = new File(temporary.getRoot(), "runtime");
        Files.createDirectories(new File(runtime, "doc").toPath());
        Files.writeString(new File(runtime, "doc/tags").toPath(), "stale");
        Files.writeString(new File(runtime, "removed.lua").toPath(), "stale");
        // A leftover from an interrupted replacement is discarded.
        Files.createDirectories(new File(temporary.getRoot(), "runtime.tmp/partial").toPath());
        FileTrees.replace(runtime, staging -> {
            assertFalse(new File(staging, "partial").exists());
            Files.createDirectories(new File(staging, "doc").toPath());
            Files.writeString(new File(staging, "doc/help.txt").toPath(), "new");
        });
        assertEquals("new", Files.readString(new File(runtime, "doc/help.txt").toPath()));
        assertFalse(new File(runtime, "doc/tags").exists());
        assertFalse(new File(runtime, "removed.lua").exists());
        assertFalse(new File(temporary.getRoot(), "runtime.tmp").exists());
    }

    @Test public void failedFillKeepsTheOldTree() throws IOException {
        File runtime = temporary.newFolder("runtime");
        Files.writeString(new File(runtime, "kept.lua").toPath(), "old");
        assertThrows(IOException.class, () -> FileTrees.replace(runtime, staging -> { throw new IOException("full"); }));
        assertEquals("old", Files.readString(new File(runtime, "kept.lua").toPath()));
    }

    @Test public void deleteRemovesLinksWithoutFollowingThem() throws IOException {
        File outside = temporary.newFolder("outside");
        Files.writeString(new File(outside, "data").toPath(), "keep");
        File tree = temporary.newFolder("tree");
        Files.createSymbolicLink(new File(tree, "link").toPath(), outside.toPath());
        FileTrees.delete(tree);
        FileTrees.delete(tree);
        assertFalse(tree.exists());
        assertEquals("keep", Files.readString(new File(outside, "data").toPath()));
    }
}
