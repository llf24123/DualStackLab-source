package org.example.filemanager;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.*;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ArchiveToolsInstrumentedTest {
    private Context context;private File work;private FileStore store;
    @Before public void setup()throws Exception{context=InstrumentationRegistry.getInstrumentation().getTargetContext();work=Files.createTempDirectory(context.getCacheDir().toPath(),"archive-tools-test-").toFile();store=new FileStore(context);}
    @After public void cleanup()throws Exception{try(var paths=Files.walk(work.toPath())){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    private File zip(String name,Map<String,byte[]> entries)throws Exception{
        File archive=new File(work,name);try(ZipOutputStream out=new ZipOutputStream(new FileOutputStream(archive))){for(var entry:entries.entrySet()){
            ZipEntry header=new ZipEntry(entry.getKey());header.setMethod(ZipEntry.STORED);header.setSize(entry.getValue().length);CRC32 crc=new CRC32();crc.update(entry.getValue());header.setCrc(crc.getValue());out.putNextEntry(header);out.write(entry.getValue());out.closeEntry();
        }}return archive;
    }
    @Test public void commentRoundTripPreservesPayloadAndHandlesSameTemporaryLookingName()throws Exception{
        byte[] content="original text".getBytes(StandardCharsets.UTF_8);File archive=zip("comment-edit.zip",Map.of("text.txt",content));
        try(ArchiveTools tools=new ArchiveTools(context,FileStore.local(archive),()->false)){
            tools.saveComment("中文注释 · note\n第二行",()->false);assertEquals("中文注释 · note\n第二行",tools.comment());
            tools.saveComment("更新说明",()->false);
        }
        try(net.lingala.zip4j.ZipFile opened=new net.lingala.zip4j.ZipFile(archive)){assertEquals("更新说明",opened.getComment());}
        assertArrayEquals(content,ArchiveOps.bytes(archive,"text.txt",1024));
    }
    @Test public void commentRejectsExternalModificationAndOversizedUtf8()throws Exception{
        File archive=zip("guarded.zip",Map.of("one",new byte[]{1,2}));
        try(ArchiveTools tools=new ArchiveTools(context,FileStore.local(archive),()->false)){
            try{tools.saveComment("字".repeat(22000),()->false);fail("oversize accepted");}catch(IOException expected){assertTrue(expected.getMessage().contains("65535"));}
            zip("guarded.zip",Map.of("new",new byte[]{8,9}));byte[] changed=Files.readAllBytes(archive.toPath());
            try{tools.saveComment("must not replace",()->false);fail("stale source overwritten");}catch(IOException expected){}
            assertArrayEquals(changed,Files.readAllBytes(archive.toPath()));
        }
    }
    @Test public void splitMergePreservesAllSourcesAndRejectsExistingDestination()throws Exception{
        byte[] data=new byte[220000];new Random(31).nextBytes(data);File payload=new File(work,"large.bin");Files.write(payload.toPath(),data);
        File archive=new File(work,"split.zip");List<File> parts=ArchiveOps.packSplit(store,List.of(FileStore.local(payload)),archive,new char[0],6,65536);
        Map<File,byte[]> original=new LinkedHashMap<>();for(File part:parts)original.put(part,Files.readAllBytes(part.toPath()));
        try(ArchiveTools tools=new ArchiveTools(context,FileStore.local(archive),()->false)){
            assertTrue(tools.split);assertEquals(parts.size(),tools.volumes);
            FileStore.Node output=tools.merge(FileStore.local(work),"merged.zip",()->false,p->{});
            assertFalse(ArchiveOps.isSplitZip(output.file));assertArrayEquals(data,ArchiveOps.bytes(output.file,"large.bin",300000));
            try{tools.merge(FileStore.local(work),"merged.zip",()->false,p->{});fail("replaced existing output");}catch(IOException expected){}
            try{tools.saveComment("not allowed",()->false);fail("modified split");}catch(IOException expected){}
        }
        for(var originalPart:original.entrySet())assertArrayEquals(originalPart.getKey().getName(),originalPart.getValue(),Files.readAllBytes(originalPart.getKey().toPath()));
    }
    @Test public void integrityContinuesAfterCorruptEntryAndCountsGoodEntries()throws Exception{
        LinkedHashMap<String,byte[]> entries=new LinkedHashMap<>();entries.put("bad.txt",new byte[]{1,2,3,4});entries.put("good.txt",new byte[]{5,6,7});
        File archive=zip("corrupt.zip",entries);
        try(RandomAccessFile data=new RandomAccessFile(archive,"rw")){data.seek(26);int name=data.readUnsignedByte()|data.readUnsignedByte()<<8;int extra=data.readUnsignedByte()|data.readUnsignedByte()<<8;data.seek(30+name+extra);int value=data.readUnsignedByte();data.seek(30+name+extra);data.writeByte(value^127);}
        try(ArchiveTools tools=new ArchiveTools(context,FileStore.local(archive),()->false)){
            ArchiveTools.Report report=tools.verify(new char[0],()->false,p->{});assertFalse(report.stopped());assertEquals(2,report.checked());assertEquals(1,report.passed());assertEquals(1,report.failed());assertEquals("bad.txt",report.issues().get(0).entry());
        }
    }
    @Test public void integrityStopsInsideLargeMemberWithoutChangingSource()throws Exception{
        byte[] bytes=new byte[2*1024*1024];new Random(12).nextBytes(bytes);File archive=zip("cancel.zip",Map.of("large.bin",bytes));byte[] original=Files.readAllBytes(archive.toPath());AtomicBoolean cancelled=new AtomicBoolean();
        try(ArchiveTools tools=new ArchiveTools(context,FileStore.local(archive),()->false)){
            ArchiveTools.Report report=tools.verify(new char[0],cancelled::get,p->{if(p.bytes()>0)cancelled.set(true);});
            assertTrue(report.stopped());assertEquals(0,report.checked());assertTrue(report.bytes()>0);assertTrue(report.bytes()<bytes.length);
        }
        assertArrayEquals(original,Files.readAllBytes(archive.toPath()));
    }
    @Test public void encryptedIntegrityChecksAuthenticationAndReportsWrongPassword()throws Exception{
        File source=new File(work,"secret.txt");Files.write(source.toPath(),"secret".getBytes(StandardCharsets.UTF_8));File archive=new File(work,"secret.zip");
        ArchiveOps.pack(store,List.of(FileStore.local(source)),archive,"zip","correct".toCharArray(),6);
        try(ArchiveTools tools=new ArchiveTools(context,FileStore.local(archive),()->false)){
            assertTrue(tools.encrypted);ArchiveTools.Report ok=tools.verify("correct".toCharArray(),()->false,p->{});assertEquals(1,ok.passed());assertEquals(0,ok.failed());assertEquals(6,ok.bytes());
            ArchiveTools.Report wrong=tools.verify("wrong".toCharArray(),()->false,p->{});assertEquals(0,wrong.passed());assertEquals(1,wrong.failed());
        }
    }
}
