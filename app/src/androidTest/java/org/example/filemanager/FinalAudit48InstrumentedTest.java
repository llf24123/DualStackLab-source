package org.example.filemanager;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.system.Os;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class FinalAudit48InstrumentedTest {
    private Context context; private File work; private FileStore store; private boolean granted;
    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext(); store = new FileStore(context);
        work = Files.createTempDirectory(context.getCacheDir().toPath(),"audit48-").toFile();
    }
    @After public void cleanup() throws Exception {
        if (granted) { provider("releaseRead",null); provider("reset",null); }
        try (var files = Files.walk(work.toPath())) {
            for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
    private File write(File parent, String name, String text) throws IOException {
        File file = new File(parent,name); Files.write(file.toPath(),text.getBytes(StandardCharsets.UTF_8)); return file;
    }
    private Bundle provider(String method, Bundle value) {
        return context.getContentResolver().call(Uri.parse("content://"+CountingDocumentsProvider.AUTHORITY),method,null,value);
    }
    private Uri grant() throws Exception {
        Uri root = Uri.parse(BatchCopyInstrumentedTest.grantFixture(context).getString("uri")); granted = true; return root;
    }
    private FileStore.Node seed(Uri root, String name, String text) throws Exception {
        Bundle data = new Bundle(); data.putString("name",name); data.putString("text",text);
        return store.document(DocumentsContract.buildDocumentUriUsingTree(root,provider("seed",data).getString("id")));
    }
    @Test public void parallelStatisticsHasFourWorkerLimitAndStopsWithoutInterruptingCaller() throws Exception {
        CountDownLatch entered = new CountDownLatch(4), blocked = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger(), peak = new AtomicInteger(); AtomicBoolean closed = new AtomicBoolean();
        List<FileStore.Node> nodes = new ArrayList<>();
        for (int i=0;i<100;i++) nodes.add(new FileStore.Node("file"+i,new File(work,"file"+i),null,false,false,1,0));
        FileStatistics.Access fixture = new FileStatistics.Access() {
            public boolean canInspectConcurrently(FileStore.Node node) { return true; }
            public FileStatistics.Entry inspect(FileStore.Node node) throws IOException {
                int running = active.incrementAndGet(); peak.accumulateAndGet(running,Math::max); entered.countDown();
                try { blocked.await(); return new FileStatistics.Entry(node,node.name,FileStatistics.Kind.FILE,1); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new Tasks.CancelledException(); }
                finally { active.decrementAndGet(); }
            }
            public List<FileStore.Node> children(FileStatistics.Entry node) { return List.of(); }
            public void close() { closed.set(true); }
        };
        ExecutorService runner = Executors.newSingleThreadExecutor(); FileStatistics.Scan scan = new FileStatistics.Scan();
        try {
            Future<FileStatistics.Result> task = runner.submit(() -> scan.run(fixture,nodes,null));
            assertTrue(entered.await(5,TimeUnit.SECONDS)); scan.cancel();
            assertTrue(task.get(3,TimeUnit.SECONDS).snapshot().cancelled());
            assertEquals(4,peak.get()); assertTrue(closed.get());
        } finally { blocked.countDown(); runner.shutdownNow(); }
    }
    @Test public void concurrentMetadataFailureFallsBackToSerialAccessAndRetainsOtherFiles() throws Exception {
        AtomicInteger fallback = new AtomicInteger();
        FileStatistics.Access fixture = new FileStatistics.Access() {
            public boolean canInspectConcurrently(FileStore.Node node) { return true; }
            public FileStatistics.Entry inspectConcurrently(FileStore.Node node) throws IOException {
                if (node.name.equals("restricted")) throw new IOException("local access denied");
                return new FileStatistics.Entry(node,node.name,FileStatistics.Kind.FILE,7);
            }
            public FileStatistics.Entry inspect(FileStore.Node node) { fallback.incrementAndGet(); return new FileStatistics.Entry(node,node.name,FileStatistics.Kind.FILE,11); }
            public List<FileStore.Node> children(FileStatistics.Entry node) { return List.of(); }
        };
        List<FileStore.Node> nodes = List.of(new FileStore.Node("plain",new File(work,"plain"),null,false,false,7,0),
                new FileStore.Node("restricted",new File(work,"restricted"),null,false,false,11,0));
        var result = new FileStatistics.Scan().run(fixture,nodes,null);
        assertTrue(result.snapshot().complete()); assertEquals(18,result.snapshot().bytes()); assertEquals(1,fallback.get());
    }
    @Test public void recursiveManifestKeepsRelativeNamesAndSkipsLinksAndSpecialFiles() throws Exception {
        File tree = Files.createDirectory(new File(work,"tree").toPath()).toFile();
        File a = Files.createDirectory(new File(tree,"a").toPath()).toFile(), b = Files.createDirectory(new File(tree,"b").toPath()).toFile();
        write(a,"same.bin","hello"); write(b,"same.bin","world"); write(tree,"empty","");
        Os.symlink(tree.getPath(),new File(tree,"loop").getPath()); Os.mkfifo(new File(tree,"pipe").getPath(),0600);
        var result = new ChecksumManifest.Scan().run(store,List.of(FileStore.local(tree)));
        assertEquals(Set.of("tree/a/same.bin","tree/b/same.bin","tree/empty"),result.hashes().keySet());
        assertEquals(10,result.bytes()); assertEquals(0,result.remaining().size());
        assertEquals(1,result.errorCount()); assertFalse(result.enumerated());
        assertTrue(ChecksumManifest.compare(result.manifest(),result.hashes()).startsWith("匹配 3，不匹配 0"));
    }
    @Test public void manifestRetriesOnlyFailedPayloadAndRetainsCompletedHashes() throws Exception {
        Uri root=grant(); FileStore.Node good=seed(root,"good","hello"), bad=seed(root,"bad","world");
        Bundle config=new Bundle();config.putString("failReadName","bad");provider("configure",config);provider("clearCounters",null);
        var first=new ChecksumManifest.Scan().run(store,List.of(good,bad));
        assertEquals(Set.of("good"),first.hashes().keySet());assertEquals(1,first.remaining().size());assertTrue(first.enumerated());
        int before=provider("stats",null).getInt("readOpens");provider("configure",new Bundle());
        var retried=new ChecksumManifest.Scan().run(store,List.of(good,bad),first);
        assertEquals(2,retried.hashes().size());assertTrue(retried.errors().isEmpty());assertEquals(10,retried.bytes());
        assertEquals(before+1,provider("stats",null).getInt("readOpens"));
    }
    @Test public void blockedManifestCancellationReturnsPartialResultAndDoesNotHashLaterFile() throws Exception {
        Uri root=grant(); FileStore.Node blocked=seed(root,"blocked","hello"), later=seed(root,"later","world");
        Bundle config=new Bundle();config.putString("blockReadName","blocked");provider("configure",config);
        ChecksumManifest.Scan scan=new ChecksumManifest.Scan();ExecutorService runner=Executors.newSingleThreadExecutor();
        try {
            Future<ChecksumManifest.Result> job=runner.submit(()->scan.run(store,List.of(blocked,later)));
            long deadline=android.os.SystemClock.uptimeMillis()+5000;
            while(!provider("readState",null).getBoolean("prefixWritten")&&android.os.SystemClock.uptimeMillis()<deadline) Thread.sleep(20);
            assertTrue(provider("readState",null).getBoolean("prefixWritten"));scan.cancel();
            var result=job.get(4,TimeUnit.SECONDS);assertTrue(result.cancelled());assertTrue(result.hashes().isEmpty());assertEquals(2,result.remaining().size());
        } finally {provider("releaseRead",null);scan.cancel();runner.shutdownNow();}
    }
    @Test public void sha256OnlyPreservesExactHashAndReadsPayloadOnce() throws Exception {
        Uri root=grant();FileStore.Node file=seed(root,"known","123456789");provider("clearCounters",null);
        var value=new PropertyChecksums.Scan().calculateSha256(store,file,null);
        assertEquals("15e2b0d3c33891ebb0f1ef609ec419420c20e320ce94c65fbc8c3312448eb225",value.sha256());
        assertEquals("",value.md5());assertEquals("",value.sha1());assertEquals("",value.crc32());assertEquals(9,value.bytes());
        assertEquals(1,provider("stats",null).getInt("readOpens"));
    }
    @Test public void manifestComparisonHandlesBomBsdBinaryAndDetailedMismatchWithoutOpeningPaths() {
        String hash="a".repeat(64);
        var result=ChecksumManifest.compareDetailed("\ufeff"+hash+" *./folder/文件.txt\r\nSHA256 (other) = "+"b".repeat(64)+"\n"
                +hash+"  /not-opened",Map.of("folder/文件.txt",hash,"other",hash));
        assertTrue(result.summary().startsWith("匹配 1，不匹配 1，未计算 1"));
        assertTrue(result.display().contains("不匹配：other"));assertTrue(result.display().contains("实际 "+hash));
        assertTrue(result.display().contains("未计算：/not-opened"));
    }
    @Test public void sameManifestNameFromDifferentRootsIsReportedAsAmbiguous() throws Exception {
        File a=Files.createDirectory(new File(work,"a").toPath()).toFile(),b=Files.createDirectory(new File(work,"b").toPath()).toFile();
        File first=write(a,"same","one"),second=write(b,"same","two");
        var result=new ChecksumManifest.Scan().run(store,List.of(FileStore.local(first),FileStore.local(second)));
        assertTrue(result.hashes().isEmpty());assertEquals(1,result.errorCount());assertFalse(result.enumerated());
    }
    @Test public void storageAnalysisCountsMetadataAndRejectsPipesWithoutOpeningThem() throws Exception {
        File a=Files.createDirectory(new File(work,"a").toPath()).toFile(),b=Files.createDirectory(new File(work,"b").toPath()).toFile();
        write(a,"one","same");write(b,"two","same");Os.mkfifo(new File(a,"pipe").getPath(),0600);
        var result=new StorageAnalysis.Scan().run(store,FileStore.local(work),true);
        assertEquals(8,result.bytes());assertEquals(1,result.duplicates().size());assertEquals(1,result.errors().size());
        assertTrue(result.entries().stream().filter(e->e.node().name.equals("a")).findFirst().get().incomplete());
    }
}
