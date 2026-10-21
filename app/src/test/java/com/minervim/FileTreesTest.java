package com.minervim;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class FileTreesTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void deleteRemovesNestedTreesAndLinksWithoutFollowingThem() throws IOException {
        File outside = temporary.newFolder("outside");
        Files.writeString(new File(outside, "data").toPath(), "keep");
        File tree = temporary.newFolder("tree");
        Files.createDirectories(new File(tree, "doc/nested").toPath());
        Files.writeString(new File(tree, "doc/nested/tags").toPath(), "stale");
        Files.createSymbolicLink(new File(tree, "link").toPath(), outside.toPath());
        FileTrees.delete(tree);
        // A missing tree is not an error.
        FileTrees.delete(tree);
        assertFalse(tree.exists());
        assertEquals("keep", Files.readString(new File(outside, "data").toPath()));
    }
}
