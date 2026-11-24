package org.example.filemanager;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.widget.ListView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class TextMergeInstrumentedTest {
    @Test public void identicalMixedLineEndingsDoNotRewriteSameContent(){
        String mixed="one\r\ntwo\nthree\rfour";
        assertEquals(mixed,TextMerge.applyAll(mixed,mixed));
    }
    Context context;File work;FileStore store;
    @Before public void setup()throws Exception{
        context=InstrumentationRegistry.getInstrumentation().getTargetContext();store=new FileStore(context);
        work=Files.createTempDirectory(context.getCacheDir().toPath(),"merge45-").toFile();
    }
    @After public void cleanup()throws Exception{
        try(var paths=Files.walk(work.toPath())){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
    }
    private File file(String name,byte[] bytes)throws Exception{File f=new File(work,name);Files.write(f.toPath(),bytes);return f;}
    private File file(String name,String text)throws Exception{return file(name,text.getBytes(StandardCharsets.UTF_8));}
    private List<TextDiff.Row> diff(String a,String b)throws Exception{return TextDiff.compare(a,b,false,false,()->false);}
    private int first(List<TextDiff.Row> rows){for(int i=0;i<rows.size();i++)if(rows.get(i).kind()!=TextDiff.Kind.SAME)return i;throw new AssertionError("No difference");}
    private String merge(String source,String target)throws Exception{List<TextDiff.Row> rows=diff(source,target);return TextMerge.apply(source,target,rows,first(rows),true);}

    @Test public void blockMergeKeepsUnrelatedChangesAndMixedTargetEndings()throws Exception{
        String a="header\nnew\nanchor\nother\nend",b="header\r\nold\r\nanchor\rother change\nend";
        assertEquals("header\r\nnew\r\nanchor\rother change\nend",merge(a,b));
        List<TextDiff.Row> rows=diff(a,b);
        assertEquals("header\nold\nanchor\nother\nend",TextMerge.apply(a,b,rows,first(rows),false));
    }
    @Test public void handlesInsertionDeletionAndEmptyDocuments()throws Exception{
        assertEquals("a\r\nb\r\nc",merge("a\nb\nc","a\r\nc"));
        assertEquals("a\r\nc",merge("a\nc","a\r\nb\r\nc"));
        assertEquals("new\n",merge("new\n",""));assertEquals("",merge("","old\r\n"));
        assertEquals("",merge("","\n"));assertEquals("\r\n",merge("\r\n",""));
    }
    @Test public void preservesOrRemovesFinalNewlineAsRequested()throws Exception{
        assertEquals("a\r\nb",merge("a\nb","a\r\n"));
        assertEquals("a\r\n",merge("a\n","a\r\nb"));
        assertEquals("a\n",merge("a\n","a"));assertEquals("a",merge("a","a\r\n"));
        assertEquals("a\nb",merge("a\nb","a"));
    }
    @Test public void replacingWholeTextKeepsDestinationLineConvention(){
        assertEquals("x\r\ny\r\n",TextMerge.applyAll("x\ny\n","old\r\n"));
        assertEquals("x\ry",TextMerge.applyAll("x\ny","old\r"));
        assertEquals("",TextMerge.applyAll("","old"));
    }
    @Test public void filteredEqualityDoesNotRewriteUnselectedWhitespace()throws Exception{
        String a="Alpha \nnew\nEnd",b="alpha\r\nold\r\nend";
        List<TextDiff.Row> rows=TextDiff.compare(a,b,true,true,()->false);
        assertEquals("alpha\r\nnew\r\nend",TextMerge.apply(a,b,rows,first(rows),true));
    }
    @Test public void repeatedBlocksConvergeWithoutDroppingLines()throws Exception{
        Random random=new Random(4521);
        for(int sample=0;sample<100;sample++){
            StringBuilder a=new StringBuilder(),b=new StringBuilder();
            for(int i=0,n=random.nextInt(25);i<n;i++)a.append(random.nextInt(6)).append('\n');
            for(int i=0,n=random.nextInt(25);i<n;i++)b.append(random.nextInt(6)).append('\n');
            String source=a.toString(),target=b.toString();int remaining=60;
            while(!source.equals(target)&&remaining-->0){target=merge(source,target);}
            assertEquals("sample "+sample,source,target);
        }
    }
    @Test public void savesUtf16BomAndUndoRestoresExactBytes()throws Exception{
        byte[] bom={(byte)0xff,(byte)0xfe};byte[] original=TextCodec.encode("原文\r\n尾行\r\n",StandardCharsets.UTF_16LE,bom);
        File target=file("utf16.txt",original);FileStore.Node node=FileStore.local(target);
        TextMerge.Snapshot snapshot=TextMerge.read(store,node,()->false);
        String merged=TextMerge.applyAll("新内容\n尾行\n",snapshot.text());
        TextMerge.Undo undo=TextMerge.save(store,node,snapshot,merged,()->false);
        assertArrayEquals(TextCodec.encode("新内容\r\n尾行\r\n",StandardCharsets.UTF_16LE,bom),Files.readAllBytes(target.toPath()));
        TextMerge.undo(store,undo,()->false);assertArrayEquals(original,Files.readAllBytes(target.toPath()));
    }
    @Test public void externalSameSizeSameTimestampChangeBlocksSave()throws Exception{
        File target=file("changed.txt","old");FileStore.Node node=FileStore.local(target);TextMerge.Snapshot snapshot=TextMerge.read(store,node,()->false);
        long time=target.lastModified();Files.write(target.toPath(),"ext".getBytes(StandardCharsets.UTF_8));assertTrue(target.setLastModified(time));
        try{TextMerge.save(store,node,snapshot,"new",()->false);fail("must reject external edit");}catch(IOException expected){assertTrue(expected.getMessage().contains("改变"));}
        assertEquals("ext",new String(Files.readAllBytes(target.toPath()),StandardCharsets.UTF_8));
    }
    @Test public void externalChangeBlocksUndo()throws Exception{
        File target=file("undo.txt","old");FileStore.Node node=FileStore.local(target);
        TextMerge.Undo undo=TextMerge.save(store,node,TextMerge.read(store,node,()->false),"new",()->false);
        Files.write(target.toPath(),"external".getBytes(StandardCharsets.UTF_8));
        try{TextMerge.undo(store,undo,()->false);fail("must reject external edit");}catch(IOException expected){assertTrue(expected.getMessage().contains("改变"));}
        assertEquals("external",new String(Files.readAllBytes(target.toPath()),StandardCharsets.UTF_8));
    }
    @Test public void cancellationLeavesOriginalIntact()throws Exception{
        File target=file("cancel.txt","old");FileStore.Node node=FileStore.local(target);TextMerge.Snapshot snapshot=TextMerge.read(store,node,()->false);
        try{TextMerge.save(store,node,snapshot,"new",()->true);fail();}catch(InterruptedIOException expected){}
        assertEquals("old",new String(Files.readAllBytes(target.toPath()),StandardCharsets.UTF_8));
    }
    @Test public void unmappableCharactersNeverBecomeQuestionMarks()throws Exception{
        File target=file("latin.txt","old");FileStore.Node node=FileStore.local(target);
        TextMerge.Snapshot baseline=new TextMerge.Snapshot("old".getBytes(StandardCharsets.US_ASCII),new TextCodec.Decoded("old",StandardCharsets.US_ASCII,new byte[0],false),store.fingerprint(node));
        try{TextMerge.save(store,node,baseline,"中文",()->false);fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("编码"));}
        assertEquals("old",new String(Files.readAllBytes(target.toPath()),StandardCharsets.UTF_8));
    }
    @Test public void uiRequiresSaveAndRetainsUndoThroughRecreation()throws Exception{
        File left=file("left.txt","title\nnew\nlast"),right=file("right.txt","title\r\nold\r\nlast");
        UiDevice device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        Intent intent=new Intent(context,FileCompareActivity.class).putExtra("left",left.getPath()).putExtra("right",right.getPath()).putExtra("mode","text");
        try(ActivityScenario<FileCompareActivity> screen=ActivityScenario.launch(intent)){
            ready(screen);
            screen.onActivity(a->{ListView list=(ListView)field(a,"list");list.performItemClick(list.getChildAt(1),1,1);});
            find(device,"左 → 右").click();find(device,"取消").click();
            assertEquals("title\r\nold\r\nlast",new String(Files.readAllBytes(right.toPath()),StandardCharsets.UTF_8));
            screen.onActivity(a->{ListView list=(ListView)field(a,"list");list.performItemClick(list.getChildAt(1),1,1);});
            find(device,"左 → 右").click();find(device,"保存合并").click();ready(screen);
            assertEquals("title\r\nnew\r\nlast",new String(Files.readAllBytes(right.toPath()),StandardCharsets.UTF_8));
            screen.recreate();ready(screen);
            UiObject2 menu=device.wait(Until.findObject(By.desc("对比菜单")),5000);assertNotNull(menu);menu.click();
            find(device,"撤销上次合并").click();find(device,"撤销合并").click();ready(screen);
            assertEquals("title\r\nold\r\nlast",new String(Files.readAllBytes(right.toPath()),StandardCharsets.UTF_8));
        }
    }
    private UiObject2 find(UiDevice device,String label){UiObject2 object=device.wait(Until.findObject(By.text(label)),5000);assertNotNull(label,object);return object;}
    private Object field(Object object,String name){return Iteration38InstrumentedTest.field(object,name);}
    private void ready(ActivityScenario<FileCompareActivity> screen){
        long end=SystemClock.uptimeMillis()+15000;boolean[] ready={false};
        while(SystemClock.uptimeMillis()<end&&!ready[0]){screen.onActivity(a->ready[0]=!(Boolean)field(a,"busy")&&field(a,"leftText")!=null);if(!ready[0])SystemClock.sleep(40);}
        assertTrue("comparison timeout",ready[0]);
    }
}
