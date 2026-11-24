package org.example.filemanager;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class FileManualCoreInstrumentedTest {
    Context context;File work;
    @Before public void setup()throws Exception{context=InstrumentationRegistry.getInstrumentation().getTargetContext();work=Files.createTempDirectory(context.getCacheDir().toPath(),"manual41-core-").toFile();}
    @After public void cleanup()throws Exception{try(var paths=Files.walk(work.toPath())){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    File file(String name,byte[] bytes)throws IOException{File f=new File(work,name);Files.write(f.toPath(),bytes);return f;}
    HexDocument document(byte[] bytes)throws IOException{return new HexDocument(file(UUID.randomUUID()+".bin",bytes),work);}
    @Test public void hexRandomEditsMatchByteArrayAndUndoRedo()throws Exception{
        byte[] expected=new byte[2048];new Random(20).nextBytes(expected);
        try(HexDocument doc=document(expected)){
            Random random=new Random(41);
            for(int i=0;i<250;i++){
                byte[] before=expected.clone(),insert=new byte[random.nextInt(20)];random.nextBytes(insert);int at=random.nextInt(expected.length+1),count=random.nextInt(Math.min(20,expected.length-at)+1);
                if(count==0&&insert.length==0)continue;
                expected=new byte[before.length-count+insert.length];System.arraycopy(before,0,expected,0,at);System.arraycopy(insert,0,expected,at,insert.length);System.arraycopy(before,at+count,expected,at+insert.length,before.length-at-count);
                doc.replace(at,count,insert);assertArrayEquals(expected,doc.read(0,10000));doc.undo();assertArrayEquals(before,doc.read(0,10000));doc.redo();assertArrayEquals(expected,doc.read(0,10000));
            }
            File output=new File(work,"output.bin");doc.writeTo(output,()->false);assertArrayEquals(expected,Files.readAllBytes(output.toPath()));
            doc.markSaved();assertFalse(doc.dirty());doc.replace(0,0,new byte[]{1});assertTrue(doc.dirty());doc.undo();assertFalse(doc.dirty());
        }
    }
    @Test public void hexReadsSparseFileBeyondTwoGiB()throws Exception{
        File source=new File(work,"sparse.bin");try(RandomAccessFile raf=new RandomAccessFile(source,"rw")){raf.setLength(3L*1024*1024*1024);raf.seek(raf.length()-3);raf.write(new byte[]{1,2,3});}
        try(HexDocument doc=new HexDocument(source,work)){assertEquals(3L*1024*1024*1024,doc.size());assertArrayEquals(new byte[]{1,2,3},doc.read(doc.size()-3,20));doc.replace(doc.size()-2,1,new byte[]{9});assertArrayEquals(new byte[]{1,9,3},doc.read(doc.size()-3,3));}
    }
    @Test public void hexFindsAcrossReadBoundariesAndReplaceAllIsAtomic()throws Exception{
        byte[] input=new byte[131100];Arrays.fill(input,(byte)'x');byte[] needle="ABABA".getBytes(StandardCharsets.UTF_8);System.arraycopy(needle,0,input,65534,5);System.arraycopy(needle,0,input,131070,5);
        try(HexDocument doc=document(input)){assertEquals(65534,doc.find(needle,0,doc.size(),false,()->false));assertEquals(131070,doc.find(needle,0,doc.size(),true,()->false));
            try{doc.replaceAll(needle,new byte[]{7},()->true);fail();}catch(InterruptedIOException expected){}assertArrayEquals(input,doc.read(0,input.length));assertFalse(doc.dirty());
            assertEquals(2,doc.replaceAll(needle,new byte[]{7},()->false));assertEquals(input.length-8,doc.size());assertEquals(7,doc.read(65534,1)[0]);doc.undo();assertArrayEquals(input,doc.read(0,input.length));
        }
    }
    @Test public void hexCopyFormatsAndEndianInspectorAreCorrect()throws Exception{
        assertArrayEquals(new byte[]{0,(byte)255,65},HexDocument.parse("00 FF 41",0));assertArrayEquals(new byte[]{65},HexDocument.parse("QQ==",2));
        assertEquals("352441C2",HexDocument.format("abc".getBytes(StandardCharsets.UTF_8),6));assertTrue(HexDocument.inspect(new byte[]{1,2,3,4},true).contains("16909060"));assertTrue(HexDocument.inspect(new byte[]{1,2,3,4},false).contains("67305985"));
        try{HexDocument.parse("0",0);fail();}catch(IOException expected){}
    }
    @Test public void textDiffReconstructsBothInputsWithInsertionsAndChanges()throws Exception{
        String a="first\nremove\nold\nlast\n",b="first\nnew\nlast\nadd\n";List<TextDiff.Row> rows=TextDiff.compare(a,b,false,false,()->false);
        assertEquals(a,reconstruct(rows,true));assertEquals(b,reconstruct(rows,false));assertEquals(3,rows.stream().filter(r->r.kind()==TextDiff.Kind.SAME).count());
    }
    @Test public void textDiffPatienceHandlesLargeRepeatedFilesWithoutLosingLines()throws Exception{
        StringBuilder left=new StringBuilder(),right=new StringBuilder();for(int i=0;i<3000;i++){left.append(i%5==0?"unique"+i:"repeat").append('\n');right.append(i==1700?"changed":i%5==0?"unique"+i:"repeat").append('\n');}
        List<TextDiff.Row> rows=TextDiff.compare(left.toString(),right.toString(),false,false,()->false);assertEquals(left.toString(),reconstruct(rows,true));assertEquals(right.toString(),reconstruct(rows,false));assertEquals(1,rows.stream().filter(r->r.kind()!=TextDiff.Kind.SAME).count());
    }
    @Test public void textDiffRandomizedReconstruction()throws Exception{
        Random random=new Random(32);for(int sample=0;sample<100;sample++){StringBuilder a=new StringBuilder(),b=new StringBuilder();for(int i=0;i<60;i++){a.append(random.nextInt(10)).append('\n');b.append(random.nextInt(10)).append('\n');}List<TextDiff.Row> rows=TextDiff.compare(a.toString(),b.toString(),false,false,()->false);assertEquals(a.toString(),reconstruct(rows,true));assertEquals(b.toString(),reconstruct(rows,false));for(TextDiff.Row row:rows)if(row.kind()==TextDiff.Kind.SAME)assertEquals(row.left(),row.right());}
    }
    String reconstruct(List<TextDiff.Row> rows,boolean left){return String.join("\n",rows.stream().filter(r->left?r.leftLine()>0:r.rightLine()>0).map(r->left?r.left():r.right()).toList());}
    @Test public void textDiffOptionsAndCancellation()throws Exception{
        assertEquals(TextDiff.Kind.SAME,TextDiff.compare(" A b ","ab",true,true,()->false).get(0).kind());
        try{TextDiff.compare("a","b",false,false,()->true);fail();}catch(InterruptedIOException expected){}
    }
    File zip(String name,Map<String,String> entries)throws IOException{File f=new File(work,name);try(ZipOutputStream out=new ZipOutputStream(new FileOutputStream(f))){for(var entry:entries.entrySet()){out.putNextEntry(new ZipEntry(entry.getKey()));out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));out.closeEntry();}}return f;}
    @Test public void zipComparisonIncludesContentAndEmptyDirectories()throws Exception{
        File a=zip("a.zip",Map.of("empty/","","same.txt","same","change.txt","old","removed","x")),b=zip("b.zip",Map.of("same.txt","same","change.txt","new","added","x"));
        try(FileComparison cmp=new FileComparison(context,FileStore.local(a),FileStore.local(b),()->false)){cmp.load("zip");assertEquals(5,cmp.compared);assertEquals(4,cmp.differences.size());assertTrue(cmp.differences.stream().anyMatch(d->d.name().equals("change.txt")&&d.label().equals("修改")));}
    }
    @Test public void dexComparisonIdentifiesChangedClassAndSmali()throws Exception{
        File one=new File(work,"smali1"),two=new File(work,"smali2");one.mkdir();two.mkdir();String code=".class public LExample;\n.super Ljava/lang/Object;\n.method public static value()I\n.locals 1\nconst/4 v0, 0x1\nreturn v0\n.end method\n";
        Files.write(new File(one,"Example.smali").toPath(),code.getBytes(StandardCharsets.UTF_8));Files.write(new File(two,"Example.smali").toPath(),code.replace("0x1","0x2").getBytes(StandardCharsets.UTF_8));File a=new File(work,"a.dex"),b=new File(work,"b.dex");ApkTools.assemble(one,a);ApkTools.assemble(two,b);
        try(FileComparison cmp=new FileComparison(context,FileStore.local(a),FileStore.local(b),()->false)){cmp.load("dex");assertEquals(1,cmp.differences.size());var difference=cmp.differences.get(0);assertEquals("LExample;",difference.name());assertTrue(cmp.content(difference.left()).contains("0x1"));assertTrue(cmp.content(difference.right()).contains("0x2"));}
    }
    @Test public void arscComparisonUsesDecodedResourceValues()throws Exception{
        File source=new File(context.getApplicationInfo().sourceDir),a=new File(work,"a.arsc"),b=new File(work,"b.arsc");ArchiveOps.extractOne(source,"resources.arsc",a);
        com.reandroid.arsc.chunk.TableBlock table=com.reandroid.arsc.chunk.TableBlock.load(a);var resources=table.getResources();boolean changed=false;
        while(resources.hasNext()&&!changed){var resource=resources.next();if(!resource.getType().equals("string"))continue;for(var entry:resource){if(entry.getValueAsString()!=null){entry.setValueAsString("DualStack resource compare fixture");changed=true;break;}}}assertTrue(changed);table.refreshFull();table.writeBytes(b);
        try(FileComparison cmp=new FileComparison(context,FileStore.local(a),FileStore.local(b),()->false)){cmp.load("arsc");assertEquals(1,cmp.differences.size());assertTrue(cmp.content(cmp.differences.get(0).right()).contains("DualStack resource compare fixture"));}
    }
    @Test public void axmlComparisonDecodesManifest()throws Exception{
        File original=new File(InstrumentationRegistry.getInstrumentation().getContext().getApplicationInfo().sourceDir),modified=new File(work,"changed.apk");ApkTools.clonePackage(original,modified,"com.dualstack.fixture.changed");
        try(FileComparison cmp=new FileComparison(context,FileStore.local(original),FileStore.local(modified),()->false)){cmp.load("axml");var manifest=cmp.differences.stream().filter(d->d.name().equals("AndroidManifest.xml")).findFirst().orElseThrow();assertTrue(cmp.content(manifest.right()).contains("com.dualstack.fixture.changed"));}
    }
    static final String SYNTAX="{ name: [\"Test fixture\", \".fixture\"] styles: [\"custom\", #123456, #ABCDEF, @BI] defines: [\"words\": keywordsToRegex(\"hello world\")] comment: {startsWith: \"#\"} bracketPairs: [\"()\"] contains: [{match: include(\"words\"), 0: \"keyword\"} {start: {match: /\"/} end: {match: /\"/} style: \"string\"} {match: /(abc)(123)/, 1: \"custom\", 2: \"number\"}] }";
    @Test public void mtsxParsesStylesIncludesGroupsAndMultilineState()throws Exception{
        MtsxSyntax syntax=MtsxSyntax.parse(SYNTAX);assertEquals("Test fixture",syntax.name);assertEquals(List.of(".fixture"),syntax.extensions);assertEquals(0xff123456,syntax.styles.get("custom").light());
        MtsxLanguage language=new MtsxLanguage(syntax);var first=language.analyzer.tokenizeLine("hello \"open",List.of(),0);assertEquals(1,first.state.size());var second=language.analyzer.tokenizeLine("closed\" abc123",first.state,1);assertTrue(second.state.isEmpty());
        assertTrue(second.spans.stream().anyMatch(s->io.github.rosemoe.sora.lang.styling.TextStyle.getForegroundColorId(s.getStyle())==syntax.styles.get("custom").id()));
    }
    @Test public void mtsxRejectsUnsupportedConstructsAndInvalidGroups()throws Exception{
        for(String text:List.of("{name:[\"bad\"] childrenSyntax:\"Java\" contains:[]}","{name:[\"bad\"] contains:[{match:/x/,2:\"string\"}]}","{name:[\"bad\"] contains:[{match:/(unterminated/}]}")){try{MtsxSyntax.parse(text);fail(text);}catch(IOException expected){assertFalse(expected.getMessage().isBlank());}}
    }
    @Test public void mtsxPathologicalRegexHasBoundedHighlightTime()throws Exception{
        MtsxSyntax syntax=MtsxSyntax.parse("{name:[\"budget\"] contains:[{match:/(a+)+b/,0:\"keyword\"}]} ");MtsxLanguage language=new MtsxLanguage(syntax);long start=System.nanoTime();language.analyzer.tokenizeLine("a".repeat(4096),List.of(),0);assertTrue("highlight exceeded 500ms",System.nanoTime()-start<500_000_000L);
    }
}
