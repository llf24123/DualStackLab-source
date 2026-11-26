package org.example.filemanager;

import android.content.Context;
import android.os.Bundle;
import android.os.Parcel;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.Until;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Regression coverage for the whole-app review following 0.6.56. */
@RunWith(AndroidJUnit4.class)
public class CurrentAuditInstrumentedTest {
    private final Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();

    private static SearchJob.Hit search(String text, String expression) throws Exception {
        return SearchJob.findStream(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), Pattern.compile(expression));
    }

    @Test public void multilineSearchKeepsItsSemanticsAcrossTheEncodingSample() throws Exception {
        for (String prefix : new String[]{"", "padding\n".repeat(9500)}) {
            String text = prefix + "begin\nend\n" + "x\n".repeat(33000);
            for (String regex : new String[]{"begin\\nend", "begin\\send", "begin[\\t-z]end", "(?s)begin.end", "(?<=begin\\n)end"}) {
                SearchJob.Hit hit = search(text, regex);
                assertNotNull(regex, hit);
                assertEquals(prefix.isEmpty() ? regex.startsWith("(?<=") ? 2 : 1 : regex.startsWith("(?<=") ? 9502 : 9501, hit.line());
            }
            assertNull(search("first\n" + text, "^begin"));
            assertNotNull(search(text, "(?m)^end$"));
        }
    }

    @Test public void carriageReturnLineNumbersAgreeForSmallAndStreamedFiles() throws Exception {
        for (String newline : new String[]{"\r", "\r\n", "\n"}) {
            for (String padding : new String[]{"", "padding".repeat(10000)}) {
                SearchJob.Hit hit = search("header" + newline + padding + "target", "target");
                assertNotNull(hit); assertEquals(2, hit.line());
                assertNotNull(search("header" + newline + "target" + padding, "header\\Rtarget"));
            }
        }
    }

    @Test public void contentLimitIsReportedWhileOtherFilesStillProduceResults() throws Exception {
        File directory = Files.createTempDirectory(app.getCacheDir().toPath(), "search-limit-").toFile();
        try {
            Files.write(new File(directory, "large.txt").toPath(), "x\n".repeat(SearchJob.MAX_MULTILINE_CHARS / 2 + 1).getBytes(StandardCharsets.UTF_8));
            Files.write(new File(directory, "small.txt").toPath(), "begin\nend".getBytes(StandardCharsets.UTF_8));
            SearchJob job = SearchJob.start(app, FileStore.local(directory), "begin\\s+end", Pattern.compile("begin\\s+end"), true);
            long until = android.os.SystemClock.uptimeMillis() + 10000;
            while (!job.snapshot().finished() && android.os.SystemClock.uptimeMillis() < until) Thread.sleep(20);
            SearchJob.Snapshot state = job.snapshot();
            assertTrue(state.finished()); assertNull(state.error()); assertEquals(1, state.denied());
            assertNotNull(state.notice()); assertTrue(state.notice().contains("8 Mi"));
            assertEquals(1, state.matches().size()); assertEquals("small.txt", state.matches().get(0).name);
        } finally { new FileStore(app).delete(FileStore.local(directory)); }
    }

    @Test public void literalNewlineAndQuotedBackslashesKeepTheirMeaning() throws Exception {
        String text = "header\n".repeat(10000) + "a\nB\\folder\n";
        SearchJob.Hit hit = SearchJob.findStream(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)),
                Pattern.compile(Pattern.quote("a\nB\\folder")));
        assertNotNull(hit); assertEquals(10001, hit.line());
        assertNotNull(search(text, Pattern.quote("B\\folder")));
        assertNull(search(text, "header$"));
    }

    @Test public void literalKeywordsFindHitsWithinAndAfterLinesLongerThanOneMi() throws Exception {
        String padding = "x".repeat(1024 * 1024 + 17);
        for (Pattern pattern : new Pattern[]{Pattern.compile("needle"), Pattern.compile(Pattern.quote("needle")),
                Pattern.compile("needle", Pattern.LITERAL)}) {
            SearchJob.Hit inside = SearchJob.findStream(new ByteArrayInputStream((padding + "needle" + padding).getBytes(StandardCharsets.UTF_8)), pattern);
            assertNotNull(inside); assertEquals(1, inside.line());
            assertEquals("needle", inside.text().substring(inside.start(), inside.end()));
            SearchJob.Hit after = SearchJob.findStream(new ByteArrayInputStream(("header\r\n" + padding + "\r\nneedle").getBytes(StandardCharsets.UTF_8)), pattern);
            assertNotNull(after); assertEquals(3, after.line());
            assertEquals("needle", after.text().substring(after.start(), after.end()));
        }
    }

    @Test public void literalBoundaryWindowsPreserveUnicodeCaseAndQuotedTerminators() throws Exception {
        String literal = "a\\E.b[ü]😀", value = "A\\E.B[Ü]😀";
        for (int start : new int[]{32765, 65533, 1048573}) {
            String text = "x".repeat(start) + value + "tail".repeat(10000);
            Pattern pattern = Pattern.compile(Pattern.quote(literal), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            SearchJob.Hit hit = SearchJob.findStream(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), pattern);
            assertNotNull(hit); assertEquals(1, hit.line());
            assertEquals(value, hit.text().substring(hit.start(), hit.end()));
        }
        String lower = "𐐨", upper = "𐐀";
        Pattern supplementary = Pattern.compile(lower, Pattern.LITERAL | Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        assertTrue(supplementary.matcher(upper).find());
        String text = "x".repeat(32767) + upper + "x".repeat(65536);
        SearchJob.Hit hit = SearchJob.findStream(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), supplementary);
        assertNotNull(hit); assertEquals(upper, hit.text().substring(hit.start(), hit.end()));
    }

    @Test public void streamedLiteralLineNumbersResolveCrLfAcrossWindows() throws Exception {
        for (String newline : new String[]{"\r", "\n", "\r\n"}) {
            String text = "row" + newline + "x".repeat(32767 - 3 - newline.length()) + newline + "needle" + "x".repeat(65536);
            SearchJob.Hit hit = search(text, "needle");
            assertNotNull(hit); assertEquals(3, hit.line());
        }
        String text = "x".repeat(32767) + "\r\nneedle" + "x".repeat(65536);
        SearchJob.Hit newline = search(text, "\\r\\nneedle");
        assertNotNull(newline); assertEquals(1, newline.line());
    }

    @Test public void regexLongLineLimitIsVisibleWhileOrdinaryKeywordsRemainAvailable() throws Exception {
        File directory = Files.createTempDirectory(app.getCacheDir().toPath(), "search-line-limit-").toFile();
        try {
            Files.write(new File(directory, "large.txt").toPath(), ("x".repeat(1024 * 1024 + 1) + "\nneedle").getBytes(StandardCharsets.UTF_8));
            Files.write(new File(directory, "small.txt").toPath(), "needle".getBytes(StandardCharsets.UTF_8));
            SearchJob job = SearchJob.start(app, FileStore.local(directory), "needle+", Pattern.compile("needle+"), true);
            long until = android.os.SystemClock.uptimeMillis() + 10000;
            while (!job.snapshot().finished() && android.os.SystemClock.uptimeMillis() < until) Thread.sleep(20);
            SearchJob.Snapshot state = job.snapshot();
            assertTrue(state.finished()); assertNull(state.error()); assertEquals(1, state.denied());
            assertNotNull(state.notice()); assertTrue(state.notice().contains("1 Mi"));
            assertEquals(1, state.matches().size()); assertEquals("small.txt", state.matches().get(0).name);
        } finally { new FileStore(app).delete(FileStore.local(directory)); }
    }

    @Test public void interruptedLiteralSearchStopsWithoutConsumingTheFile() throws Exception {
        java.util.concurrent.atomic.AtomicInteger consumed = new java.util.concurrent.atomic.AtomicInteger();
        java.io.InputStream endless = new java.io.InputStream() {
            @Override public int read() { consumed.incrementAndGet(); return 'x'; }
            @Override public int read(byte[] bytes, int offset, int length) {
                java.util.Arrays.fill(bytes, offset, offset + length, (byte) 'x');
                if (consumed.addAndGet(length) > 128 * 1024) Thread.currentThread().interrupt();
                return length;
            }
        };
        try {
            SearchJob.findStream(endless, Pattern.compile(Pattern.quote("needle")));
            fail("Interrupted literal search did not stop");
        } catch (Tasks.CancelledException expected) {
            assertTrue(consumed.get() < 256 * 1024);
        } finally { Thread.interrupted(); }
    }

    @Test public void literalEarlyHitDoesNotReadTheRemainderOfALargeFile() throws Exception {
        java.util.concurrent.atomic.AtomicInteger consumed = new java.util.concurrent.atomic.AtomicInteger();
        byte[] data = ("needle" + "x".repeat(2 * 1024 * 1024)).getBytes(StandardCharsets.UTF_8);
        ByteArrayInputStream input = new ByteArrayInputStream(data) {
            @Override public synchronized int read(byte[] bytes, int offset, int length) {
                int count = super.read(bytes, offset, length); if (count > 0) consumed.addAndGet(count); return count;
            }
        };
        assertNotNull(SearchJob.findStream(input, Pattern.compile(Pattern.quote("needle"))));
        assertTrue("Literal search consumed the whole file before returning its first hit", consumed.get() <= 128 * 1024);
    }

    @Test public void linearRegexScansPastTheFormerReadBudgetAndRemainsCancellable() {
        // Failed alternatives force RE2 to visit the document instead of a literal fast path.
        String text = "a\n".repeat(1024 * 1024) + "needle";
        SearchJob.SearchPattern positive = new SearchJob.SearchPattern(Pattern.compile("a[^a\n]*z|needle$"));
        SearchJob.Match hit = positive.find(text);
        assertNotNull(hit); assertEquals(text.length() - 6, hit.start());
        assertNull(new SearchJob.SearchPattern(Pattern.compile("a[^a\n]*z|absent$")).find(text));
        Thread.currentThread().interrupt();
        try { positive.find(text); fail("Interrupted linear regex continued matching"); }
        catch (SearchJob.MatchBudgetExceeded expected) { }
        finally { Thread.interrupted(); }
    }

    @Test public void advancedRegexKeepsARealBudgetForPathologicalBacktracking() {
        SearchJob.SearchPattern pattern = new SearchJob.SearchPattern(Pattern.compile("(?<=x)(a+)+b"));
        long started = android.os.SystemClock.uptimeMillis();
        try { pattern.find("x" + "a".repeat(20000)); fail("Unbounded backtracking was accepted"); }
        catch (SearchJob.MatchBudgetExceeded expected) {
            assertTrue("Backtracking exceeded the bounded worker budget", android.os.SystemClock.uptimeMillis() - started < 10000);
        }
    }

    @Test @SuppressWarnings("unchecked") public void fiveThousandSelectionsStayOutsideActivityStateAndRestoreInOrder() throws Exception {
        Set<String> expected = new LinkedHashSet<>();
        for (int i = 0; i < 5000; i++) expected.add("/storage/emulated/0/Download/" + "x".repeat(85) + String.format(Locale.ROOT, "%05d.txt", i));
        try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(MainActivity.class)) {
            screen.onActivity(activity -> {
                Set<String> selected = (Set<String>) field(field(activity, "left"), "selected");
                Set<String> original = new HashSet<>(selected);
                try {
                    selected.clear(); selected.addAll(expected);
                    Bundle state = new Bundle();
                    InstrumentationRegistry.getInstrumentation().callActivityOnSaveInstanceState(activity, state);
                    Parcel parcel = Parcel.obtain();
                    try { parcel.writeBundle(state); assertTrue("Large selection leaked into Binder state", parcel.dataSize() < 64 * 1024); }
                    finally { parcel.recycle(); }
                    Set<String> restored = new LinkedHashSet<>();
                    PaneSelections.restore(app, state, "leftPane.selected", restored);
                    assertEquals(expected, restored);
                    assertEquals(new java.util.ArrayList<>(selected), new java.util.ArrayList<>(restored));
                } finally {
                    selected.clear(); selected.addAll(original);
                    PaneSelections.save(app, new Bundle(), "leftPane.selected", Set.of(), (String) field(activity, "selectionSession"));
                }
            });
        }
    }

    @Test public void externallyOpenedExecutableRequiresAnExplicitRunAction() throws Exception {
        File executable = File.createTempFile("external-review-", ".elf", app.getCacheDir());
        Files.write(executable.toPath(), new byte[]{0x7f, 'E', 'L', 'F'});
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(MainActivity.class)) {
            screen.onActivity(activity -> {
                try {
                    var open = MainActivity.class.getDeclaredMethod("open", FileStore.Node.class, boolean.class);
                    open.setAccessible(true); open.invoke(activity, FileStore.local(executable), true);
                } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            });
            assertNotNull(device.wait(Until.findObject(By.text("运行外部文件？")), 5000));
            var cancel = device.findObject(By.text("取消")); assertNotNull(cancel); cancel.click();
            assertTrue(device.wait(Until.gone(By.text("运行外部文件？")), 3000));
        } finally { Files.deleteIfExists(executable.toPath()); }
    }

    private static Object field(Object object, String name) {
        try { Field f = object.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(object); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
}
