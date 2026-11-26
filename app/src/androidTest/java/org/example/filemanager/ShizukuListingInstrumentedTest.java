package org.example.filemanager;

import static org.junit.Assert.*;

import android.content.Context;
import android.os.Bundle;
import android.os.Parcel;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Exercises the service on the app's own Android/data directory; Shizuku is not required. */
@RunWith(AndroidJUnit4.class)
public class ShizukuListingInstrumentedTest {
    private Path root;
    private final ShizukuDataService service = new ShizukuDataService();

    @Before public void createFixture() throws Exception {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File external = app.getExternalFilesDir(null);
        assertNotNull(external);
        root = Files.createTempDirectory(external.toPath(), "listing-review-");
        assertEquals(root.toFile().getCanonicalPath(), ShizukuDataService.checked(root.toString()).getPath());
    }

    @After public void removeFixture() throws Exception {
        if (root == null) return;
        assertTrue(root.getFileName().toString().startsWith("listing-review-"));
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) throws IOException {
                Files.delete(path); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path path, IOException error) throws IOException {
                if (error != null) throw error;
                Files.delete(path); return FileVisitResult.CONTINUE;
            }
        });
    }

    @Test public void concurrentInsertionDoesNotShiftRemainingPages() throws Exception {
        Set<String> expected = new HashSet<>();
        for (int i = 0; i < 280; i++) {
            String name = String.format(java.util.Locale.ROOT, "item-%03d", i);
            Files.createFile(root.resolve(name)); expected.add(name);
        }
        IDataListing listing = service.list(root.toString());
        Bundle first = listing.page(0);
        Set<String> actual = new HashSet<>();
        append(first, actual);
        // This would shift the offset of every remaining entry if the service relisted each page.
        Files.createFile(root.resolve("000-added-after-enumeration"));
        int next = first.getInt("next");
        while (next != -1) {
            Bundle page = listing.page(next); append(page, actual);
            int following = page.getInt("next");
            assertTrue(following == -1 || following > next); next = following;
        }
        assertEquals(expected, actual);
    }

    @Test public void deepPathsStayBelowBinderPayloadLimitWithoutDroppingMembers() throws Exception {
        Path directory = root;
        String part = "d".repeat(220);
        while (directory.toString().length() < 3100) directory = Files.createDirectory(directory.resolve(part));
        Set<String> expected = new HashSet<>();
        for (int i = 0; i < 128; i++) {
            String name = String.format(java.util.Locale.ROOT, "%03d-%s", i, "f".repeat(200));
            Files.createFile(directory.resolve(name)); expected.add(name);
        }
        IDataListing listing = service.list(directory.toString());
        Set<String> actual = new HashSet<>(); int offset = 0, pages = 0;
        do {
            Bundle page = listing.page(offset);
            Parcel serialized = Parcel.obtain();
            try {
                serialized.writeBundle(page);
                assertTrue("A single page can overflow a busy Binder buffer", serialized.dataSize() < 256 * 1024);
            } finally { serialized.recycle(); }
            append(page, actual); pages++;
            int next = page.getInt("next");
            assertTrue(next == -1 || next > offset); offset = next;
        } while (offset != -1);
        assertTrue("Long paths must be split by payload size", pages > 1);
        assertEquals(expected, actual);
    }

    private static void append(Bundle page, Set<String> names) {
        ArrayList<Bundle> entries = page.getParcelableArrayList("entries");
        assertNotNull(entries);
        for (Bundle entry : entries) assertTrue("A directory entry was returned twice", names.add(entry.getString("name")));
    }
}
