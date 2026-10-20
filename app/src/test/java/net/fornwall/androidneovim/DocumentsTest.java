package net.fornwall.androidneovim;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class DocumentsTest {
    private static final String V1 = Documents.sha256("v1\n".getBytes(StandardCharsets.UTF_8));

    @Test public void copiesRoundTripAndReadEarlierUriOnlyRecords() {
        var copy = new Documents.Copy(Documents.Copy.WRITABLE, 1700000000123L, 3, V1, "content://a/document/x y");
        assertEquals(copy, Documents.Copy.decode(copy.encode()));
        var shared = new Documents.Copy(Documents.Copy.READ_ONLY, -1, -1, null, "");
        assertEquals(shared, Documents.Copy.decode(shared.encode()));
        assertEquals(new Documents.Copy(Documents.Copy.WRITABLE, -1, -1, null, "content://a/document/1"),
                Documents.Copy.decode("content://a/document/1"));
        assertEquals(Documents.Copy.FORGOTTEN, copy.forgotten().state());
        assertEquals(copy.uri(), copy.forgotten().uri());
    }

    @Test public void metadataDecidesBeforeContentIsHashed() throws Exception {
        var copy = new Documents.Copy(Documents.Copy.WRITABLE, 1000, 3, V1, "content://a/1");
        AtomicInteger hashed = new AtomicInteger();
        Documents.IoSupplier<String> same = () -> { hashed.incrementAndGet(); return V1; };
        Documents.IoSupplier<String> other = () -> { hashed.incrementAndGet(); return "other"; };
        // A different size is a change; an unchanged modification time is not.
        assertFalse(copy.matches(1000, 4, same));
        assertTrue(copy.matches(1000, 3, other));
        assertTrue(copy.matches(1000, -1, other));
        assertEquals(0, hashed.get());
        // A new time or unknown metadata compares content: a touched but unchanged original still matches.
        assertTrue(copy.matches(2000, 3, same));
        assertFalse(copy.matches(2000, 3, other));
        assertFalse(copy.matches(-1, -1, other));
        assertEquals(3, hashed.get());
        // Without remembered metadata, only the content can tell.
        var unknown = new Documents.Copy(Documents.Copy.WRITABLE, -1, -1, V1, "content://a/1");
        assertFalse(unknown.matches(-1, -1, other));
        assertTrue(unknown.matches(-1, 3, same));
        // An earlier version's record has no hash and keeps its old behaviour: write.
        assertTrue(Documents.Copy.decode("content://a/1").matches(5, 9, other));
    }
}
