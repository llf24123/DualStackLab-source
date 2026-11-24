package org.example.filemanager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;
@RunWith(AndroidJUnit4.class)
public class PropertyImprovementsInstrumentedTest {
    @Test public void smallTransferAvoidsDirectoryListingAndConflictFallsBack() throws Exception {
        var context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        File root=Files.createTempDirectory(context.getCacheDir().toPath(),"probe-").toFile();
        File source=new File(root,"small"), destination=new File(root,"target"); destination.mkdir(); Files.write(source.toPath(),new byte[]{1});

        FileStore store=new FileStore(context);
        try {
            for(int i=0;i<1000;i++) Files.write(new File(destination,"other"+i).toPath(),new byte[0]);
            var nodes=List.of(FileStore.local(source)); var parent=FileStore.local(destination);
            assertTrue(store.transferNames(parent,nodes).isEmpty());
            Files.write(new File(destination,"small").toPath(),new byte[]{2});
            assertTrue(store.transferNames(parent,nodes).contains("small"));
        } finally { new FileStore(context).delete(FileStore.local(root)); }
    }
    @Test public void largeFolderStatisticsMeasureMetadataOnly() throws Exception {
        var context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        File root=Files.createTempDirectory(context.getCacheDir().toPath(),"stats-speed-").toFile();
        try {
            for(int i=0;i<2000;i++) Files.write(new File(root,"file"+i).toPath(),new byte[32]);
            long started=android.os.SystemClock.elapsedRealtime();
            var result=new FileStatistics.Scan().run(new FileStore(context),List.of(FileStore.local(root)));
            long elapsed=android.os.SystemClock.elapsedRealtime()-started;
            assertTrue(result.snapshot().complete()); assertEquals(64000,result.snapshot().bytes()); assertEquals(2000,result.snapshot().files());
            android.util.Log.i("StatisticsBenchmark","2000 files: "+elapsed+" ms");
            Files.write(new File(context.getExternalFilesDir(null),"0648-statistics-benchmark.txt").toPath(),("2000 files, 64000 bytes: "+elapsed+" ms").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder paired = new StringBuilder("Same 2000-file tree; 64000 logical bytes; emulator app cache.\n");
            for (int round=0;round<3;round++) {
                for (int workers : round % 2 == 0 ? new int[]{1,4} : new int[]{4,1}) {
                    started=android.os.SystemClock.elapsedRealtime();
                    var measured=new FileStatistics.Scan(workers).run(new FileStore(context),List.of(FileStore.local(root)));
                    elapsed=android.os.SystemClock.elapsedRealtime()-started;
                    assertTrue(measured.snapshot().complete());assertEquals(64000,measured.snapshot().bytes());assertEquals(2000,measured.snapshot().files());
                    paired.append("round ").append(round+1).append(", workers ").append(workers).append(": ").append(elapsed).append(" ms\n");
                }
            }
            Files.write(new File(context.getExternalFilesDir(null),"0648-statistics-paired.txt").toPath(),paired.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } finally { new FileStore(context).delete(FileStore.local(root)); }
    }
    @Test public void manifestReportsMismatchMissingAndDuplicates() {
        String hash="a".repeat(64); var values=Map.of("one",hash,"two",hash);
        assertEquals("匹配 1，不匹配 0，未计算 0，无效行 0，清单未包含 1",ChecksumManifest.compare(hash+"  one",values));
        String report=ChecksumManifest.compare("b".repeat(64)+"  one\n"+hash+"  missing\n"+hash+"  missing",values);
        assertTrue(report.contains("不匹配 1")); assertTrue(report.contains("未计算 1")); assertTrue(report.contains("无效行 1"));
    }
}
