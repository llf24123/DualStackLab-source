package org.example.filemanager;

import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class RenameRegexInstrumentedTest {
    @Test public void lookaroundsBackreferencesAndNamedReplacementGroupsWork()throws Exception{
        assertEquals("photo_09.jpg",new RenameRegex("(?<=photo_)\\d+(?=\\.jpg$)").replace("photo_12.jpg","09"));
        assertEquals("doc-copy.jpg",new RenameRegex("(?<!photo)_").replace("doc_copy.jpg","-"));
        assertEquals("hello",new RenameRegex("\\b(\\w+)\\s+\\1\\b").replace("hello hello","$1"));
        assertEquals("photo-12",new RenameRegex("(?<base>photo)_(\\d+)").replace("photo_12","${base}-$2"));
        assertEquals("same",new RenameRegex("(?<dup>[a-z]+)-\\k<dup>").replace("same-same","${dup}"));
        assertEquals("Zb",new RenameRegex("(?>a+)(?=b)").replace("aaab","Z"));
    }

    @Test public void ordinaryPatternsKeepFastPathAndLiteralReplacementEscapes()throws Exception{
        String pathological="a".repeat(2000)+"!";
        assertEquals(pathological,new RenameRegex("(a+)+$").replace(pathological,""));
        assertEquals("$1.txt",new RenameRegex("\\d+").replace("12.txt","\\$1"));
        assertEquals("okend",new RenameRegex("\\Q*+\\E(?=end)").replace("*+end","ok"));
        assertEquals("-a-b-",new RenameRegex("(?=.)|$").replace("ab","-"));
    }

    @Test public void expensiveAdvancedPatternStopsAndLaterItemsFailImmediately()throws Exception{
        RenameRegex regex=new RenameRegex("(a+)+(?=b)");
        long start=SystemClock.uptimeMillis();
        try{regex.replace("a".repeat(2000)+"!","");fail("Expensive regex completed");}
        catch(IOException expected){assertTrue(expected.getMessage(),expected.getMessage().contains("预算")||expected.getMessage().contains("嵌套"));}
        assertTrue("Regex cancellation exceeded 2 seconds",SystemClock.uptimeMillis()-start<2000);
        start=SystemClock.uptimeMillis();
        try{regex.replace("ab","x");fail("Budget failure must invalidate the remaining preview");}
        catch(IOException expected){assertTrue(expected.getMessage().contains("预算"));}
        assertTrue(SystemClock.uptimeMillis()-start<500);
    }

    @Test public void zeroWidthUnboundedPossessiveAndExtremeCountsAreRejected(){
        for(String expression:List.of("(?:){2147483647}(?=a)","(?:)*+(?=a)","(?:)++(?=a)","(?:){1,}+(?=a)","(?x) (?:) * #text\n + (?=a)")){
            try{new RenameRegex(expression);fail(expression);}catch(IllegalArgumentException expected){assertTrue(expected.getMessage(),expected.getMessage().contains("量词")||expected.getMessage().contains("次数"));}
        }
    }

    @Test public void failedRegexPreviewCannotPartiallyRenameFiles()throws Exception{
        var context=InstrumentationRegistry.getInstrumentation().getTargetContext();FileStore store=new FileStore(context);
        File directory=Files.createTempDirectory(context.getCacheDir().toPath(),"rename-regex-").toFile();
        try{
            File first=new File(directory,"first.txt"),second=new File(directory,"second.txt");
            Files.write(first.toPath(),"one".getBytes(StandardCharsets.UTF_8));Files.write(second.toPath(),"two".getBytes(StandardCharsets.UTF_8));
            RenameExpression expression=new RenameExpression(new RenameExpression.Config("a".repeat(2000)+"!",RenameExpression.DEFAULT_DATE,"(a+)+(?=b)","x",true));
            BatchRename.Plan plan=BatchRename.preview(store,FileStore.local(directory),List.of(FileStore.local(first),FileStore.local(second)),expression);
            assertFalse(plan.valid());assertTrue(plan.items().stream().allMatch(item->!item.error().isEmpty()));
            try{BatchRename.execute(store,plan);fail("Invalid preview was executed");}catch(IOException expected){}
            assertTrue(first.isFile());assertTrue(second.isFile());assertEquals(2,directory.list().length);
        }finally{try(var paths=Files.walk(directory.toPath())){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    }

    @Test public void interruptionRemainsCancellationAndOversizedReplacementIsRejected()throws Exception{
        Thread.currentThread().interrupt();
        try{new RenameRegex("(?<=a)b").replace("ab","x");fail();}catch(Tasks.CancelledException expected){}finally{Thread.interrupted();}
        try{new RenameRegex("(?=a)").replace("a".repeat(100),"x".repeat(100));fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("过长"));}
    }
}
