package org.example.filemanager;

import android.content.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class NewFeaturesInstrumentedTest {
    Context context; FileStore store; File work;
    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext(); store = new FileStore(context);
        work = Files.createTempDirectory(context.getCacheDir().toPath(), "features-0637-").toFile();
    }
    @After public void cleanup() throws Exception { store.delete(FileStore.local(work)); }
    File dir(String name) { File dir = new File(work, name); assertTrue(dir.mkdirs()); return dir; }
    File write(File parent, String name, String content) throws Exception {
        File file = new File(parent, name); Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8)); return file;
    }
    String read(File file) throws Exception { return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8); }
    @Test public void batchPreviewIsReadOnlyAndAppliesReplacementNumberExtension() throws Exception {
        File a = write(work, "old-a.txt", "aaa"), b = write(work, "old-b.log", "bbb");
        var plan = BatchRename.preview(store, List.of(FileStore.local(a), FileStore.local(b)), new BatchRename.Rule("old", "new", true, 7, "md"));
        assertTrue(plan.valid()); assertEquals("new-a_7.md", plan.items().get(0).name()); assertEquals("new-b_8.md", plan.items().get(1).name());
        assertTrue(a.exists()); assertFalse(new File(work, "new-a_7.md").exists());
        assertEquals(2, BatchRename.execute(store, plan).size());
        assertEquals("aaa", read(new File(work, "new-a_7.md"))); assertEquals("bbb", read(new File(work, "new-b_8.md"))); assertFalse(a.exists());
    }
    @Test public void batchConflictsAndInvalidNamesDoNotMutate() throws Exception {
        File a = write(work, "a.txt", "a"); write(work, "a.log", "keep");
        var conflict = BatchRename.preview(store, List.of(FileStore.local(a)), new BatchRename.Rule("", "", false, 0, "log"));
        assertFalse(conflict.valid()); try { BatchRename.execute(store, conflict); fail(); } catch (IOException expected) { }
        var invalid = BatchRename.preview(store, List.of(FileStore.local(a)), new BatchRename.Rule("a", "../a", false, 0, ""));
        assertFalse(invalid.valid()); assertEquals("a", read(a)); assertEquals("keep", read(new File(work, "a.log")));
    }
    @Test public void batchDetectsDuplicateOutputsAndExternalChanges() throws Exception {
        File a = write(work, "a.txt", "a"), b = write(work, "a.log", "b");
        var duplicate = BatchRename.preview(store, List.of(FileStore.local(a), FileStore.local(b)), new BatchRename.Rule("", "", false, 0, "md")); assertFalse(duplicate.valid());
        var stale = BatchRename.preview(store, List.of(FileStore.local(a)), new BatchRename.Rule("a", "new", false, 0, ""));
        write(work, "a.txt", "changed-size"); try { BatchRename.execute(store, stale); fail(); } catch (IOException expected) { assertTrue(expected.getMessage().contains("来源已变化")); }
        assertFalse(new File(work, "new.txt").exists());
    }
    @Test public void batchSupportsDirectoriesAndCyclicNames() throws Exception {
        File a = write(work, "a", "A"), b = write(work, "b", "B");
        var items = List.of(new BatchRename.Item(FileStore.local(a), "b", store.identity(FileStore.local(a)), ""),
                new BatchRename.Item(FileStore.local(b), "a", store.identity(FileStore.local(b)), ""));
        BatchRename.execute(store, new BatchRename.Plan(FileStore.local(work), items)); assertEquals("B", read(a)); assertEquals("A", read(b));
        File folder = dir("folder"); write(folder, "child", "kept");
        var plan = BatchRename.preview(store, List.of(FileStore.local(folder)), new BatchRename.Rule("folder", "renamed", true, 1, "txt"));
        BatchRename.execute(store, plan); assertEquals("kept", read(new File(work, "renamed_1/child")));
    }
    File zip(String name, Map<String,String> files) throws Exception {
        File file = new File(work, name); try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
            for (var entry : files.entrySet()) { zip.putNextEntry(new ZipEntry(entry.getKey())); zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8)); zip.closeEntry(); }
        } return file;
    }
    FileStore.Node archive(File file) throws Exception { return FileStore.archiveNode(FileStore.local(file), "", true, 0, 0); }
    @Test public void archiveBatchRenamePreservesCompressedPayloadAndNestedDirectories() throws Exception {
        File zip = zip("rename.zip", Map.of("old.txt", "content", "folder/child.txt", "inside"));
        FileStore.Node root = archive(zip); var plan = BatchRename.preview(store, store.list(root), new BatchRename.Rule("", "", true, 1, ""));
        List<FileStore.Node> outputs = BatchRename.execute(store, plan); assertEquals(2, outputs.size());
        try (ZipFile result = new ZipFile(zip)) {
            assertNull(result.getEntry("old.txt"));
            for (var item : plan.items()) assertNotNull(result.getEntry(item.name() + (item.source().directory ? "/child.txt" : "")));
        }
    }
    @Test public void recursiveHashFindsSameSizeSameTimeChangedContentAndExportsAll() throws Exception {
        File left = dir("left/nested"), right = dir("right/nested");
        File a = write(left, "same.txt", "aaaa"), b = write(right, "same.txt", "bbbb"); a.setLastModified(1000); b.setLastModified(1000);
        write(left, "only.txt", "left"); left.setLastModified(1000); right.setLastModified(1000);
        var shallow = new DirectoryCompare.Scan().run(store, FileStore.local(left.getParentFile()), FileStore.local(right.getParentFile()), new DirectoryCompare.Options(false, false));
        assertTrue(shallow.differences().isEmpty());
        var report = new DirectoryCompare.Scan().run(store, FileStore.local(left.getParentFile()), FileStore.local(right.getParentFile()), new DirectoryCompare.Options(true, true));
        assertTrue(report.differences().stream().anyMatch(d -> d.name().equals("nested/same.txt") && d.kind() == DirectoryCompare.Kind.CONTENT));
        assertTrue(report.text().contains("nested/only.txt")); assertTrue(report.text().contains("当前 SHA-256="));
    }
    @Test public void recursiveCompareContainsEveryMissingChildAndDoesNotFollowSymlinks() throws Exception {
        File left = dir("left"), right = dir("right"), nested = new File(left, "nested"); assertTrue(nested.mkdir());
        for (int i = 0; i < 180; i++) write(nested, "item" + i, "x");
        Files.createSymbolicLink(new File(left, "loop").toPath(), left.toPath());
        var report = new DirectoryCompare.Scan().run(store, FileStore.local(left), FileStore.local(right), new DirectoryCompare.Options(true, false));
        assertTrue(report.text().contains("nested/item179")); assertTrue(report.differences().stream().anyMatch(d -> d.kind() == DirectoryCompare.Kind.ERROR && d.name().equals("loop/")));
    }
    @Test public void directoryCompareCancelNeverReportsSuccess() throws Exception {
        var scan = new DirectoryCompare.Scan(); scan.cancel();
        try { scan.run(store, FileStore.local(work), FileStore.local(work), new DirectoryCompare.Options(true, true)); fail(); } catch (Tasks.CancelledException expected) { }
    }
    @Test public void settingsRoundTripRestoresBookmarksThemeSortingAndMerge() throws Exception {
        byte[] old = SettingsBackup.export(context);
        try {
            var prefs = context.getSharedPreferences("MainActivity", 0); prefs.edit().putInt("sort-left", 2).putBoolean("comfortable", true).putString("home-right", "/data/adb").commit();
            new DirectoryPlaces(context).clear(true); new DirectoryPlaces(context).bookmark(new DirectoryPlaces.Entry("/example", "例子", "/example", "", false));
            var appearance=context.getSharedPreferences("appearance",0);
            var styleEdit=appearance.edit().putString("mode","dark").putString("material","tide");
            IconStyle.write(styleEdit,ThemeMaterial.FROST,IconStyle.TILE);
            IconStyle.write(styleEdit,ThemeMaterial.PAPER,IconStyle.OUTLINE);styleEdit.commit();
            byte[] bytes = SettingsBackup.export(context); var parsed = SettingsBackup.read(new ByteArrayInputStream(bytes));
            prefs.edit().putInt("sort-left", 0).commit();styleEdit=appearance.edit().putString("material","frost");
            IconStyle.write(styleEdit,ThemeMaterial.FROST,IconStyle.DUOTONE);styleEdit.commit();new DirectoryPlaces(context).clear(true);
            SettingsBackup.apply(context, parsed, false); assertEquals(2, prefs.getInt("sort-left", -1)); assertEquals("dark", Ui.themeMode(context));
            assertEquals(ThemeMaterial.TIDE,ThemeMaterial.current(context));
            assertEquals(IconStyle.TILE,IconStyle.forMaterial(context,ThemeMaterial.FROST));
            assertEquals(IconStyle.OUTLINE,IconStyle.forMaterial(context,ThemeMaterial.PAPER));
            assertEquals("例子", new DirectoryPlaces(context).entries(true).get(0).label());
            new DirectoryPlaces(context).bookmark(new DirectoryPlaces.Entry("/other", "另一个", "/other", "", false));
            SettingsBackup.apply(context, parsed, true); assertEquals(2, new DirectoryPlaces(context).entries(true).size());
            JSONObject legacy=new JSONObject(new String(bytes,StandardCharsets.UTF_8));legacy.remove("material");legacy.remove("iconStyles");
            var oldFormat=SettingsBackup.read(new ByteArrayInputStream(legacy.toString().getBytes(StandardCharsets.UTF_8)));
            SettingsBackup.apply(context,oldFormat,true);assertEquals(ThemeMaterial.TIDE,ThemeMaterial.current(context));
            assertEquals(IconStyle.TILE,IconStyle.forMaterial(context,ThemeMaterial.FROST));
            SettingsBackup.apply(context,oldFormat,false);assertEquals(ThemeMaterial.CLASSIC,ThemeMaterial.current(context));
            assertEquals(IconStyle.FOLLOW,IconStyle.forMaterial(context,ThemeMaterial.FROST));
            assertEquals(IconStyle.FOLLOW,IconStyle.forMaterial(context,ThemeMaterial.PAPER));
        } finally { SettingsBackup.apply(context, SettingsBackup.read(new ByteArrayInputStream(old)), false); }
    }
    @Test public void malformedBackupsAreRejectedBeforeAnyPreferenceWrite() throws Exception {
        byte[] before = SettingsBackup.export(context);
        JSONObject invalid = new JSONObject(new String(before, StandardCharsets.UTF_8)); invalid.getJSONObject("main").put("sort-left", "invalid");
        try { SettingsBackup.read(new ByteArrayInputStream(invalid.toString().getBytes(StandardCharsets.UTF_8))); fail(); } catch (Exception expected) { }
        assertArrayEquals(before, SettingsBackup.export(context));
        for(Object styles:new Object[]{"outline",new JSONObject().put("classic","duotone"),new JSONObject().put("unknown","tile"),
                new JSONObject().put("tide",14),new JSONObject().put("paper","missing")}){
            JSONObject document=new JSONObject(new String(before,StandardCharsets.UTF_8)).put("iconStyles",styles);
            try{SettingsBackup.read(new ByteArrayInputStream(document.toString().getBytes(StandardCharsets.UTF_8)));fail("Invalid icon style accepted: "+styles);}
            catch(JSONException|IOException expected){ }
            assertArrayEquals(before,SettingsBackup.export(context));
        }
        try { SettingsBackup.read(new ByteArrayInputStream(new byte[SettingsBackup.LIMIT + 1])); fail(); } catch (IOException expected) { }
        assertArrayEquals(before, SettingsBackup.export(context));
        assertTrue(SettingsBackup.needsGrant(context, new DirectoryPlaces.Entry("content://missing/tree/x/document/x", "外部", "目录", "content://missing/tree/x/document/x", false)));
        assertFalse(SettingsBackup.needsGrant(context, new DirectoryPlaces.Entry("/storage/emulated/0/Download", "下载", "/storage/emulated/0/Download", "", false)));
    }
    TransferPlan.Item item(File source, File parent, FileStore.ConflictPolicy policy) throws Exception {
        File target = new File(parent, source.getName()); var existing = target.exists() ? FileStore.local(target) : null;
        return new TransferPlan.Item(FileStore.local(source), FileStore.local(parent), policy, existing, existing == null ? null : store.identity(existing));
    }
    @Test public void mixedPerItemChoicesPreserveSkippedAndKeepBoth() throws Exception {
        File source = dir("source"), target = dir("target");
        File overwrite = write(source, "a", "new-a"), both = write(source, "b", "new-b"), skip = write(source, "c", "new-c");
        write(target, "a", "old-a"); write(target, "b", "old-b"); write(target, "c", "old-c");
        TransferPlan plan = new TransferPlan(); plan.items.add(item(overwrite, target, FileStore.ConflictPolicy.OVERWRITE)); plan.items.add(item(both, target, FileStore.ConflictPolicy.KEEP_BOTH)); plan.skipped.add(FileStore.local(skip).key());
        store.executePlan(plan, true); assertEquals("new-a", read(new File(target, "a"))); assertEquals("old-b", read(new File(target, "b")));
        assertEquals("new-b", read(new File(target, "b (1)"))); assertEquals("new-c", read(skip)); assertFalse(overwrite.exists()); assertFalse(both.exists());
    }
    @Test public void confirmationDetectsChangedDestinationBeforeAnyWrite() throws Exception {
        File source = dir("source"), target = dir("target"); File a = write(source, "a", "new"), old = write(target, "a", "old");
        TransferPlan plan = new TransferPlan(); plan.items.add(item(a, target, FileStore.ConflictPolicy.OVERWRITE)); write(target, "a", "externally-modified");
        try { store.executePlan(plan, true); fail(); } catch (IOException expected) { assertTrue(expected.getMessage().contains("目标")); }
        assertEquals("externally-modified", read(old)); assertTrue(a.exists());
    }
    @Test public void mergeMovePreservesExistingUnrelatedFilesAndSkippedSources() throws Exception {
        File source = dir("source/folder"), target = dir("target/folder");
        File a = write(source, "a", "a"), skip = write(source, "skip", "skip"); write(target, "untouched", "keep");
        TransferPlan plan = new TransferPlan(); plan.items.add(item(a, target, FileStore.ConflictPolicy.KEEP_BOTH)); plan.merged.add(FileStore.local(source)); plan.skipped.add(FileStore.local(skip).key());
        store.executePlan(plan, true); assertEquals("a", read(new File(target, "a"))); assertEquals("keep", read(new File(target, "untouched"))); assertTrue(skip.exists());
    }
    @Test public void archiveMixedMoveRewritesRemovalOnceAndKeepsSkippedEntries() throws Exception {
        File zip = zip("move.zip", Map.of("a", "new-a", "b", "new-b", "skip", "keep")); File target = dir("target"); write(target, "a", "old-a");
        TransferPlan plan = new TransferPlan(); var root = archive(zip);
        for (var source : store.list(root)) {
            if (source.name.equals("skip")) { plan.skipped.add(source.key()); continue; }
            File existingFile = new File(target, source.name); var existing = existingFile.exists() ? FileStore.local(existingFile) : null;
            plan.items.add(new TransferPlan.Item(source, FileStore.local(target), existing == null ? FileStore.ConflictPolicy.KEEP_BOTH : FileStore.ConflictPolicy.OVERWRITE, existing, existing == null ? null : store.identity(existing)));
        }
        store.executePlan(plan, true); assertEquals("new-a", read(new File(target, "a"))); assertEquals("new-b", read(new File(target, "b")));
        try (ZipFile result = new ZipFile(zip)) { assertNull(result.getEntry("a")); assertNull(result.getEntry("b")); assertNotNull(result.getEntry("skip")); }
    }
    @Test public void archiveTargetMixedPoliciesPublishBatchTogether() throws Exception {
        File zip = zip("target.zip", Map.of("a", "old-a", "b", "old-b", "keep", "keep")); File sourceDir = dir("source");
        File a = write(sourceDir, "a", "new-a"), b = write(sourceDir, "b", "new-b"); FileStore.Node target = archive(zip);
        Map<String, FileStore.Node> existing = new HashMap<>(); for (var node : store.list(target)) existing.put(node.name, node);
        TransferPlan plan = new TransferPlan();
        plan.items.add(new TransferPlan.Item(FileStore.local(a), target, FileStore.ConflictPolicy.OVERWRITE, existing.get("a"), store.identity(existing.get("a"))));
        plan.items.add(new TransferPlan.Item(FileStore.local(b), target, FileStore.ConflictPolicy.KEEP_BOTH, existing.get("b"), store.identity(existing.get("b"))));
        store.executePlan(plan, false);
        try (ZipFile result = new ZipFile(zip)) {
            assertNotNull(result.getEntry("b (1)")); assertEquals("new-a", new String(result.getInputStream(result.getEntry("a")).readAllBytes(), StandardCharsets.UTF_8));
            assertNotNull(result.getEntry("keep"));
        }
    }
    @Test public void cancelledBatchRenameLeavesNoTemporaryNames() throws Exception {
        File a = write(work, "a", "a"); var plan = BatchRename.preview(store, List.of(FileStore.local(a)), new BatchRename.Rule("a", "b", false, 0, ""));
        Tasks.Progress progress = new Tasks.Progress(); progress.cancelled.set(true);
        try { progress.run(() -> BatchRename.execute(store, plan)); fail(); } catch (IOException expected) { }
        assertEquals(Set.of("a"), store.listNames(FileStore.local(work)));
    }
    android.net.Uri control = android.net.Uri.parse("content://" + CountingDocumentsProvider.AUTHORITY);
    FileStore.Node documents() throws Exception {
        var done = new java.util.concurrent.CountDownLatch(1); var result = new java.util.concurrent.atomic.AtomicReference<android.os.Bundle>();
        String pkg = InstrumentationRegistry.getInstrumentation().getContext().getPackageName();
        context.sendOrderedBroadcast(new Intent(CountingDocumentsProvider.AUTHORITY + ".GRANT")
                .setComponent(new ComponentName(pkg, CountingDocumentsProvider.GrantReceiver.class.getName())).addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES),
                null, new BroadcastReceiver() { @Override public void onReceive(Context c, Intent i) { result.set(getResultExtras(false)); done.countDown(); } },
                null, android.app.Activity.RESULT_CANCELED, null, null);
        assertTrue(done.await(10, java.util.concurrent.TimeUnit.SECONDS)); assertNotNull(result.get());
        return store.document(android.net.Uri.parse(result.get().getString("uri")));
    }
    android.os.Bundle call(String method, android.os.Bundle args) { return context.getContentResolver().call(control, method, null, args); }
    @Test public void safBatchRenameRollsBackWholeBatchWhenSecondFinalNameFails() throws Exception {
        FileStore.Node root = documents();
        try {
            for (String name : List.of("a", "b")) { var seed = new android.os.Bundle(); seed.putString("name", name); seed.putString("text", name); call("seed", seed); }
            var plan = BatchRename.preview(store, root, store.list(root), new BatchRename.Rule("", "", true, 1, ""));
            var config = new android.os.Bundle(); config.putString("failRenameName", "b_2"); call("configure", config);
            try { BatchRename.execute(store, plan); fail(); } catch (IOException expected) { assertTrue(expected.getMessage(), expected.getMessage().contains("已恢复原名")); }
            assertEquals(Set.of("a", "b"), store.listNames(root));
            call("configure", new android.os.Bundle());
            var retry = BatchRename.preview(store, root, store.list(root), new BatchRename.Rule("", "", true, 1, ""));
            assertEquals(2, BatchRename.execute(store, retry).size()); assertEquals(Set.of("a_1", "b_2"), store.listNames(root));
        } finally { call("reset", null); }
    }
    @Test public void failedTransferPlanRetriesOnlyUnfinishedItems() throws Exception {
        FileStore.Node root = documents();
        try {
            File a = write(work, "a", "a"), b = write(work, "b", "b");
            TransferPlan plan = new TransferPlan();
            for (File file : List.of(a, b)) plan.items.add(new TransferPlan.Item(FileStore.local(file), root, FileStore.ConflictPolicy.KEEP_BOTH, null, null));
            var config = new android.os.Bundle(); config.putString("failCreateName", "b"); call("configure", config);
            try { store.executePlan(plan, false); fail(); } catch (TransferPlan.Incomplete expected) { assertSame(plan, expected.plan); }
            assertEquals(Set.of("a"), store.listNames(root));
            File edited = write(work, "edited", "user-edited-completed-output"); store.write(store.list(root).get(0), edited);
            call("configure", new android.os.Bundle()); assertEquals(2, store.executePlan(plan, false).size());
            assertEquals(Set.of("a", "b"), store.listNames(root));
            for (var node : store.list(root)) if (node.name.equals("a")) try (InputStream in = store.read(node)) {
                assertEquals("user-edited-completed-output", new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        } finally { call("reset", null); }
    }
    @Test public void sameArchiveMixedMoveUsesSinglePublicationAndNoDuplicateSources() throws Exception {
        File zip = zip("same.zip", Map.of("source/a", "new-a", "source/b", "new-b", "target/a", "old-a", "target/b", "old-b"));
        FileStore.Node container = FileStore.local(zip), source = FileStore.archiveNode(container, "source/", true, 0, 0), target = FileStore.archiveNode(container, "target/", true, 0, 0);
        Map<String, FileStore.Node> existing = new HashMap<>(); for (var node : store.list(target)) existing.put(node.name, node);
        TransferPlan plan = new TransferPlan();
        for (var node : store.list(source)) plan.items.add(new TransferPlan.Item(node, target,
                node.name.equals("a") ? FileStore.ConflictPolicy.OVERWRITE : FileStore.ConflictPolicy.KEEP_BOTH, existing.get(node.name), store.identity(existing.get(node.name))));
        store.executePlan(plan, true);
        try (ZipFile result = new ZipFile(zip)) { assertNull(result.getEntry("source/a")); assertNull(result.getEntry("source/b")); assertNotNull(result.getEntry("target/b (1)")); }
    }

}
