package org.example.filemanager;

import android.content.*;
import android.os.SystemClock;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import com.termux.terminal.TerminalSession;
import io.github.rosemoe.sora.widget.CodeEditor;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Predicate;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ManualCompletionInstrumentedTest {
    Context context;File work;FileStore store;UiDevice device;
    @Before public void setup()throws Exception{context=InstrumentationRegistry.getInstrumentation().getTargetContext();work=Files.createTempDirectory(context.getCacheDir().toPath(),"complete41-").toFile();store=new FileStore(context);device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());}
    @After public void cleanup()throws Exception{for(var e:TerminalSessions.list())if(e.cwd.equals(work.getPath())){InstrumentationRegistry.getInstrumentation().runOnMainSync(()->e.session.finishIfRunning());}try(var paths=Files.walk(work.toPath())){for(var p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    File file(String name,byte[] bytes)throws IOException{File f=new File(work,name);Files.write(f.toPath(),bytes);return f;}
    byte[] bytes(int size){byte[] data=new byte[size];new Random(4164).nextBytes(data);return data;}
    Object field(Object target,String name){return Iteration38InstrumentedTest.field(target,name);}
    void invoke(Object target,String name,Object... args){ManualAlignmentInstrumentedTest.invoke(target,name,args);}
    <T extends android.app.Activity> void await(ActivityScenario<T> screen,Predicate<T> check){boolean[] done={false};long end=SystemClock.uptimeMillis()+15000;while(!done[0]&&SystemClock.uptimeMillis()<end){screen.onActivity(a->done[0]=check.test(a));if(!done[0])SystemClock.sleep(30);}assertTrue("activity timeout",done[0]);}
    @Test public void allSingleStreamsAndZstdTarRoundTrip()throws Exception{
        byte[] data=bytes(200000);File original=file("source.bin",data);
        for(String format:List.of("gz","bz2","xz","lz4","zst","tar.zst")){
            File out=new File(work,"payload."+format);ArchiveOps.pack(store,List.of(FileStore.local(original)),out,format,new char[0]);
            List<ArchiveOps.Item> entries=ArchiveOps.list(out,"");assertEquals(format,1,entries.size());
            try(InputStream in=ArchiveOps.openEntry(out,entries.get(0).path())){assertArrayEquals(format,data,FileStore.readBytes(in,data.length+1));}
            File unpack=new File(work,"extract-"+format);ArchiveOps.extract(out,"",unpack);assertArrayEquals(data,Files.readAllBytes(new File(unpack,entries.get(0).name()).toPath()));
        }
    }
    @Test public void splitZipReadsEveryPartPlainAndEncrypted()throws Exception{
        byte[] data=bytes(310000);File original=file("large.bin",data);
        for(boolean encrypted:List.of(false,true)){
            File folder=new File(work,encrypted?"secret":"plain");assertTrue(folder.mkdir());File zip=new File(folder,"split.zip");char[] pass=encrypted?"secret41".toCharArray():new char[0];
            List<File> parts=ArchiveOps.packSplit(store,List.of(FileStore.local(original)),zip,pass,6,65536);assertTrue(parts.size()>3);assertTrue(ArchiveOps.isSplitZip(zip));assertEquals("large.bin",ArchiveOps.list(zip,"").get(0).name());
            assertArrayEquals(data,ArchiveOps.bytes(zip,"large.bin",400000));File extract=new File(folder,"output");ArchiveOps.extract(zip,"",extract);assertArrayEquals(data,Files.readAllBytes(new File(extract,"large.bin").toPath()));
            File missing=parts.stream().filter(f->f.getName().endsWith(".z02")).findFirst().orElseThrow();Files.delete(missing.toPath());
            try{ArchiveOps.bytes(zip,"large.bin",400000);fail("missing volume accepted");}catch(IOException expected){}
        }
    }
    @Test public void compressionLevelsWorkAcrossFormats()throws Exception{
        File source=file("levels.txt","repeatable compression content\n".repeat(3000).getBytes(StandardCharsets.UTF_8));
        for(String format:List.of("gz","bz2","xz","zst","7z"))for(int level:List.of(0,1,9)){
            File archive=new File(work,"level-"+level+"."+format);ArchiveOps.pack(store,List.of(FileStore.local(source)),archive,format,new char[0],level);
            String entry=ArchiveOps.list(archive,"").get(0).path();assertArrayEquals(format+" "+level,Files.readAllBytes(source.toPath()),ArchiveOps.bytes(archive,entry,200000));
        }
    }
    @Test public void compressionPanelOffersFormatsLevelsAndSplitOptions()throws Exception{
        File source=file("compress.txt","text".getBytes(StandardCharsets.UTF_8));
        try(ActivityScenario<MainActivity> screen=ActivityScenario.launch(MainActivity.class)){
            screen.onActivity(a->invoke(a,"compress",List.of(FileStore.local(source))));
            assertNotNull(device.wait(Until.findObject(By.desc("ZIP 分卷大小")),5000));
            device.takeScreenshot(new File(context.getExternalFilesDir(null),"0641-compress-zip.png"));
            device.findObject(By.desc("压缩格式")).click();assertNotNull(device.wait(Until.findObject(By.text("TAR.ZST")),3000));
            device.takeScreenshot(new File(context.getExternalFilesDir(null),"0641-compress-formats.png"));device.findObject(By.text("TAR.ZST")).click();
            device.wait(Until.gone(By.text("TAR.XZ")),3000);assertTrue(device.findObject(By.desc("压缩级别")).isEnabled());assertFalse(device.findObject(By.desc("压缩密码")).isEnabled());
            device.takeScreenshot(new File(context.getExternalFilesDir(null),"0641-compress-zstd.png"));device.findObject(By.text("取消")).click();
        }
    }
    @Test public void compressionDeletesOnlyAfterRoundTripAndKeepsConflicts()throws Exception{
        byte[] data=bytes(180000);File source=file("move.bin",data);File target=new File(work,"target");assertTrue(target.mkdir());
        FileStore.Node result=ArchiveCreation.create(context,store,List.of(FileStore.local(source)),FileStore.local(target),"moved.zip","zip",new char[0],6,65536,true);
        assertFalse(source.exists());assertArrayEquals(data,ArchiveOps.bytes(result.file,"move.bin",200000));
        source=file("keep.bin",data);File existing=new File(target,"conflict.z01");Files.write(existing.toPath(),new byte[]{7});
        try{ArchiveCreation.create(context,store,List.of(FileStore.local(source)),FileStore.local(target),"conflict.zip","zip",new char[0],6,65536,true);fail("overwrote split part");}catch(IOException expected){}
        assertArrayEquals(data,Files.readAllBytes(source.toPath()));assertArrayEquals(new byte[]{7},Files.readAllBytes(existing.toPath()));assertFalse(new File(target,"conflict.zip").exists());
    }
    @Test public void compressionVerifiesDirectoryBeforeRemovingIt()throws Exception{
        File source=new File(work,"folder");assertTrue(source.mkdir());assertTrue(new File(source,"empty").mkdir());Files.write(new File(source,"a.txt").toPath(),"hello".getBytes(StandardCharsets.UTF_8));
        FileStore.Node result=ArchiveCreation.create(context,store,List.of(FileStore.local(source)),FileStore.local(work),"folder.tar.zst","tar.zst",new char[0],6,0,true);
        assertFalse(source.exists());assertEquals("hello",new String(ArchiveOps.bytes(result.file,"folder/a.txt",100),StandardCharsets.UTF_8));
    }
    @Test public void splitArchiveToolAndComparisonKeepAllVolumes()throws Exception{
        File original=file("split-source.bin",bytes(220000));File zip=new File(work,"split-tool.zip");
        ArchiveOps.packSplit(store,List.of(FileStore.local(original)),zip,new char[0],6,65536);
        try(FileComparison compare=new FileComparison(context,FileStore.local(zip),FileStore.local(zip),()->false)){
            compare.load("zip");assertEquals(1,compare.compared);assertTrue(compare.differences.isEmpty());
        }
        File target=new File(work,"split-copy-target");assertTrue(target.mkdir());
        FileStore.Node entry=FileStore.archiveNode(FileStore.local(zip),original.getName(),false,original.length(),0);
        store.copyArchiveBatch(List.of(entry),FileStore.local(target),null,n->{});
        assertArrayEquals(Files.readAllBytes(original.toPath()),Files.readAllBytes(new File(target,original.getName()).toPath()));
        Intent intent=new Intent(context,ArchiveActivity.class);FileStore.local(zip).put(intent);
        try(ActivityScenario<ArchiveActivity> screen=ActivityScenario.launch(intent)){
            await(screen,a->field(a,"archive")!=null&&((List<?>)field(a,"items")).size()==1);
            File[] copy={null};screen.onActivity(a->{copy[0]=(File)field(a,"archive");assertTrue((Boolean)field(a,"rar"));});
            assertArrayEquals(Files.readAllBytes(original.toPath()),ArchiveOps.bytes(copy[0],original.getName(),300000));
        }
    }
    @Test public void renamedSplitDestinationRollsBackAndKeepsSource()throws Exception{
        var resolver=context.getContentResolver();var control=android.net.Uri.parse("content://"+CountingDocumentsProvider.AUTHORITY);
        var root=android.net.Uri.parse(BatchCopyInstrumentedTest.grantFixture(context).getString("uri"));
        try{
            android.os.Bundle config=new android.os.Bundle();config.putString("renameName","provider.z01");resolver.call(control,"configure",null,config);
            File source=file("provider-source.bin",bytes(150000));
            try{ArchiveCreation.create(context,store,List.of(FileStore.local(source)),store.document(root),"provider.zip","zip",new char[0],6,65536,true);fail("provider changed split filename");}catch(IOException expected){assertTrue(expected.getMessage().contains("更改了文件名"));}
            assertTrue(source.exists());assertTrue(store.list(store.document(root)).isEmpty());
        }finally{resolver.call(control,"reset",null,null);}
    }
    @Test public void terminalSessionResumesSameProcessAndSurvivesRecreation()throws Exception{
        String[] id={null};TerminalSession[] original={null};
        try(ActivityScenario<TerminalActivity> screen=ActivityScenario.launch(new Intent(context,TerminalActivity.class).putExtra("interactive",true).putExtra("cwd",work.getPath()))){
            await(screen,a->field(a,"session")!=null);screen.onActivity(a->{original[0]=(TerminalSession)field(a,"session");id[0]=(String)field(a,"sessionId");original[0].write("export DSL_KEEP=kept41\r");});
            await(screen,a->a.terminalText().contains("DSL_KEEP"));
        }
        assertTrue(original[0].isRunning());assertTrue(TerminalSessions.list().stream().anyMatch(e->e.id.equals(id[0])));
        try(ActivityScenario<TerminalActivity> screen=ActivityScenario.launch(new Intent(context,TerminalActivity.class).putExtra("terminal-session",id[0]))){
            screen.onActivity(a->{assertSame(original[0],field(a,"session"));original[0].write("printf 'value:%s\\n' \"$DSL_KEEP\"\r");});await(screen,a->a.terminalText().contains("value:kept41"));
            screen.recreate();screen.onActivity(a->assertSame(original[0],field(a,"session")));
            screen.onActivity(a->invoke(a,"sessions"));assertNotNull(device.wait(Until.findObject(By.text("终端会话")),3000));
            device.takeScreenshot(new File(context.getExternalFilesDir(null),"0641-terminal-sessions.png"));device.pressBack();
            screen.onActivity(a->original[0].write("exit\r"));await(screen,a->!original[0].isRunning());
            await(screen,a->a.terminalText().contains("进程已结束"));
        }
    }
    @Test public void editorDetectsExternalChangesAndReloads()throws Exception{
        File source=file("external.txt","before".getBytes(StandardCharsets.UTF_8));Intent intent=new Intent(context,EditorActivity.class);FileStore.local(source).put(intent);
        try(ActivityScenario<EditorActivity> screen=ActivityScenario.launch(intent)){
            await(screen,a->(Boolean)field(a,"loaded"));Files.write(source.toPath(),"after41".getBytes(StandardCharsets.UTF_8));screen.onActivity(a->invoke(a,"checkExternalChange"));
            UiObject2 reload=device.wait(Until.findObject(By.text("重新读取")),5000);assertNotNull(reload);reload.click();await(screen,a->(Boolean)field(a,"loaded")&&((CodeEditor)field(a,"editor")).getText().toString().equals("after41"));
            screen.onActivity(a->{assertNull(field(a,"blockedSave"));((CodeEditor)field(a,"editor")).getText().insert(0,7," edited");invoke(a,"save");});await(screen,a->!(Boolean)field(a,"saving"));assertEquals("after41 edited",new String(Files.readAllBytes(source.toPath()),StandardCharsets.UTF_8));
        }
    }
    @Test public void syntaxSupportsLookaroundBackreferencesBuiltinsAndEndPriority()throws Exception{
        MtsxSyntax syntax=MtsxSyntax.parse("{name:[\"extended41\",\".x41\"] contains:[{match:/(?<=foo)(bar)\\1/,0:\"keyword\"} #JAVA_NUMBER# #JAVA_QUOTED_STRING# {start:{match:/@\"/},end:{match:/\"/},style:\"string\",endPriority:1,contains:[{match:/\"\"/,0:\"strEscape\"}]}]}");
        MtsxLanguage language=new MtsxLanguage(syntax);assertTrue(language.analyzer.tokenizeLine("foobarbar 0xF \"quoted\"",List.of(),0).state.isEmpty());
        var first=language.analyzer.tokenizeLine("@\"a\"\"b",List.of(),0);assertEquals(1,first.state.size());assertTrue(language.analyzer.tokenizeLine("end\"",first.state,1).state.isEmpty());
        assertTrue(language.analyzer.tokenizeLine("\"unterminated",List.of(),0).state.isEmpty());
        MtsxSyntax decorated=MtsxSyntax.parse("{name:[\"styles41\"] styles:[\"marked\" #FFFFFF#112233 #AABBCC#223344 @BIUS] contains:[{builtin:#C_NUMBER#},{match:/x/,0:\"marked\"}]}");
        assertEquals(0xff112233,decorated.styles.get("marked").lightBackground());assertTrue(decorated.styles.get("marked").underline());assertTrue(decorated.styles.get("marked").strike());
    }
    @Test public void editorPreferencesValidateSymbolsAndMenuOrder()throws Exception{
        assertArrayEquals(new String[]{"Tab","=>","TODO"},EditorPreferences.symbols("Tab => TODO"));assertEquals(Integer.valueOf(7),EditorPreferences.order("[7,5,6]").get(0));assertEquals(19,EditorPreferences.order("[7,5,6]").size());
        try{EditorPreferences.order("[1,1]");fail();}catch(IOException expected){}try{EditorPreferences.symbols(" ");fail();}catch(IOException expected){}
    }
    @Test public void syntaxRepeatedCapturesAndFixedEndKeepTheirOwnRanges()throws Exception{
        MtsxSyntax syntax=MtsxSyntax.parse("{name:[\"capture41\"] contains:[{match:/a(?:(1)|(2))+b/,recordAllGroups:true,1:\"meta\",2:\"error\"}]}");
        var captureAnalyzer=new MtsxLanguage(syntax).analyzer;
        // Initialize capture-tree classes before checking colors within the production 12 ms line budget.
        captureAnalyzer.tokenizeLine("a12121b",List.of(),0);
        var spans=captureAnalyzer.tokenizeLine("a12121b",List.of(),0).spans;
        for(int i=1;i<=5;i++){int at=i;var span=spans.stream().filter(s->s.getColumn()<=at).reduce((a,b)->b).orElseThrow();assertEquals(syntax.styles.get(i%2==1?"meta":"error").id(),io.github.rosemoe.sora.lang.styling.TextStyle.getForegroundColorId(span.getStyle()));}
        MtsxSyntax fixed=MtsxSyntax.parse("{name:[\"end41\"] contains:[{start:{match:/</},end:{match:/>/},style:\"string\",matchEndFirst:true,contains:[{match:/x>y/,0:\"keyword\"}]}]}");
        var line=new MtsxLanguage(fixed).analyzer.tokenizeLine("<x>y",List.of(),0);assertTrue(line.state.isEmpty());
        var outside=line.spans.stream().filter(s->s.getColumn()<=3).reduce((a,b)->b).orElseThrow();assertEquals(io.github.rosemoe.sora.widget.schemes.EditorColorScheme.TEXT_NORMAL,io.github.rosemoe.sora.lang.styling.TextStyle.getForegroundColorId(outside.getStyle()));
        MtsxSyntax inherited=MtsxSyntax.parse("{name:[\"inherit41\"] contains:[{start:{match:/</},end:{match:/>/},style:\"string\",contains:[{group:select,contains:[{match:/x/}]}]}]}");
        var inheritedGroup=inherited.rules.get(0).children.get(0);
        assertEquals("select",inheritedGroup.group);
        assertEquals(inherited.styles.get("string").id(),inheritedGroup.style);
        assertEquals(inherited.styles.get("string").id(),inheritedGroup.children.get(0).style);
    }
    @Test public void syntaxDynamicEndUsesLiteralExportedDelimiter()throws Exception{
        MtsxSyntax syntax=MtsxSyntax.parse("{name:[\"fence41\"] contains:[{start:{match:/^(`{3,})[^`]*$/,export:1=>\"fence\",1:\"operator\"},end:{match:/^/+var(\"fence\")+/`*$/,0:\"operator\"},childrenStyle:\"string\"}]}");
        var analyzer=new MtsxLanguage(syntax).analyzer;var opened=analyzer.tokenizeLine("````java",List.of(),0);assertEquals(1,opened.state.size());
        assertEquals(1,analyzer.tokenizeLine("```",opened.state,1).state.size());assertTrue(analyzer.tokenizeLine("`````",opened.state,1).state.isEmpty());
        assertTrue(analyzer.stateEquals(opened.state,analyzer.tokenizeLine("````java",List.of(),0).state));
        MtsxSyntax literal=MtsxSyntax.parse("{name:[\"literal41\"] contains:[{start:{match:/BEGIN (.+)/,export:1=>\"end\"},end:{match:/^/+var(\"end\")+/$/},style:\"string\"}]}");
        var a=new MtsxLanguage(literal).analyzer;var state=a.tokenizeLine("BEGIN a+b",List.of(),0).state;
        assertEquals(1,a.tokenizeLine("aaab",state,1).state.size());assertTrue(a.tokenizeLine("a+b",state,1).state.isEmpty());
        try{MtsxSyntax.parse("{name:[\"bad\"] contains:[{start:{match:/(x)/,export:1=>\"v\"},end:{match:/[/+var(\"v\")+/]/}}]}");fail("var inside character class accepted");}catch(IOException expected){}
    }
    @Test public void copyAndExtractPreserveTimesWhenEnabled()throws Exception{
        var prefs=FileManagerSettings.prefs(context);boolean previous=prefs.getBoolean("preserve-time",true);
        try{prefs.edit().putBoolean("preserve-time",true).commit();File source=file("dated.txt",new byte[]{1,2,3});long stamp=1700000000000L;assertTrue(source.setLastModified(stamp));
            File target=new File(work,"times");assertTrue(target.mkdir());FileStore.Node copied=store.copy(FileStore.local(source),FileStore.local(target),false);assertEquals(stamp,copied.file.lastModified());
            File zip=new File(work,"dated.zip");ArchiveOps.pack(store,List.of(FileStore.local(source)),zip);FileStore.Node extracted=store.extract(List.of(FileStore.local(zip)),FileStore.local(work),"extracted");assertEquals(stamp,new File(extracted.file,"dated.txt").lastModified());
            prefs.edit().putBoolean("preserve-time",false).commit();FileStore.Node second=store.copy(FileStore.local(source),FileStore.local(target),false);assertTrue(second.file.lastModified()>stamp);
            assertEquals("rwxr-xr-x",FileListDisplay.permission(0755));assertEquals("rwsr-xr-t",FileListDisplay.permission(05755));
        }finally{prefs.edit().putBoolean("preserve-time",previous).commit();}
    }
}
