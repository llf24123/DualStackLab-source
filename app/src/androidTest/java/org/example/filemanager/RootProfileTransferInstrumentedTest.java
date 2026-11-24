package org.example.filemanager;

import android.content.Context;
import android.os.Environment;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;

/** Real root/FUSE operations confined to unique fixtures in the test user's two volumes. */
@RunWith(AndroidJUnit4.class)
public class RootProfileTransferInstrumentedTest {
    private Context context;
    private FileStore store;
    private String fixture,profile,primary;
    private boolean configured;
    private final byte[] payload=new byte[32*1024];

    @Before public void setup() throws Exception {
        context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String testSu=InstrumentationRegistry.getArguments().getString("rootTestSu");
        if(testSu!=null){RootShell.configure(testSu);configured=true;}
        RootAccess.setAutomatic(true);
        Assume.assumeTrue("Requires root and an explicitly supplied test profile",RootAccess.available());
        String user=InstrumentationRegistry.getArguments().getString("profileUser");
        Assume.assumeTrue("profileUser must name the isolated test profile",user!=null&&user.matches("[1-9][0-9]*"));
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).executeShellCommand(
                "cmd appops set "+context.getPackageName()+" MANAGE_EXTERNAL_STORAGE allow");
        String name="dualstack-profile-"+UUID.randomUUID();
        fixture="/data/local/tmp/"+name;
        profile="/storage/emulated/"+user+"/"+name;
        primary=Environment.getExternalStorageDirectory().getPath()+"/"+name;
        FileStore.shell("mkdir "+q(fixture)+" "+q(profile)+" "+q(primary),true);
        new Random(644).nextBytes(payload);
        File input=new File(context.getCacheDir(),name);
        try {
            Files.write(input.toPath(),payload);
            FileStore.shell("cp "+q(input.getPath())+" "+q(fixture+"/payload.sh"),true);
        } finally {Files.deleteIfExists(input.toPath());}
        store=new FileStore(context);
    }
    @After public void cleanup() throws Exception {
        try {
            RootMutations.close();RootDirectoryReader.close();
            if(fixture!=null)FileStore.shell("rm -rf "+q(fixture)+" "+q(profile)+" "+q(primary),true);
        } finally {
            RootMutations.close();RootDirectoryReader.close();RootAccess.reset();
            if(configured)RootShell.configure("su");
        }
    }
    @Test public void copyToProfileThenPrimaryAndCreateBothKinds() throws Exception {
        FileStore.Node source=FileStore.local(new File(fixture,"payload.sh"),true);
        FileStore.Node profileDir=FileStore.directory(new File(profile),true);
        assertFalse(StorageNavigation.appManagedStorage(profileDir.file));
        FileStore.Node copied=store.copy(source,profileDir,false);
        assertBytes(copied);
        FileStore.Node primaryDir=FileStore.directory(new File(primary),true);
        assertBytes(store.copy(source,primaryDir,false));
        assertTrue(store.create(profileDir,"folder",true).directory);
        assertFalse(store.create(profileDir,"empty",false).directory);
        assertTrue(store.create(primaryDir,"folder",true).directory);
        assertFalse(store.create(primaryDir,"empty",false).directory);
        assertBytes(source);
    }
    @Test public void failedProfileOperationDoesNotBreakImmediatePrimaryOperations() throws Exception {
        FileStore.Node source=FileStore.local(new File(fixture,"payload.sh"),true);
        FileStore.Node profileDir=FileStore.directory(new File(profile),true);
        FileStore.Node original=store.copy(source,profileDir,false);
        // Exercise a real helper failure and its teardown, not a synthetic thrown exception.
        try {RootMutations.create(context,profileDir,"payload.sh",false);fail("Conflict must fail");}
        catch(IOException expected){assertTrue(expected.getMessage().contains("已存在"));}
        assertBytes(original);assertBytes(source);
        FileStore.Node primaryDir=FileStore.directory(new File(primary),true);
        assertBytes(store.copy(source,primaryDir,false));
        assertTrue(store.create(primaryDir,"after-failure",true).directory);
        FileStore.Node moved=store.copy(original,FileStore.directory(new File(primary,"after-failure"),true),true);
        assertBytes(moved);
        assertEquals("0",FileStore.shell("if [ -e "+q(original.path())+" ]; then echo 1; else echo 0; fi",true).trim());
    }
    @Test public void staleSharedGuardDoesNotCreateAndNextCreateSucceeds() throws Exception {
        FileStore.Node stale=FileStore.directory(new File(primary),true);
        stale.directoryIdentity="0:0";
        try {RootMutations.create(context,stale,"never",false);fail("Stale inode must fail");}
        catch(IOException expected){assertTrue(expected.getMessage().contains("变化"));}
        assertFalse(new File(primary,"never").exists());
        assertFalse(store.create(FileStore.directory(new File(primary),true),"after-stale",false).directory);
    }
    @Test public void differentMountViewsFallBackBeforeCreateAndWriteOnlyInRootView() throws Exception {
        String harness=InstrumentationRegistry.getArguments().getString("rootTestHarness");
        Assume.assumeTrue("Isolated emulator harness only",configured&&harness!=null);
        File view=new File(primary,"view");assertTrue(view.mkdir());
        String backing=fixture+"/root-view",socket=fixture+"/root.sock",script=fixture+"/su";
        String regularSu=InstrumentationRegistry.getArguments().getString("rootTestSu");
        FileStore.shell("mkdir "+q(backing),true);
        String server="mount -o rprivate none / && mount --bind "+q(backing)+" "+q(view.getPath())
                +" && exec "+q(harness)+" --server "+q(socket);
        String pid=FileStore.shell("/system/bin/toybox unshare -m /system/bin/sh -c "+q(server)
                +" >"+q(fixture+"/namespace.log")+" 2>&1 </dev/null & echo $!",true).trim();
        try {
            boolean ready=false;
            for(int i=0;i<30;i++){
                if(FileStore.shell("if [ -S "+q(socket)+" ]; then echo yes; fi",true).trim().equals("yes")){ready=true;break;}
                Thread.sleep(100);
            }
            assertTrue("Namespace broker: "+FileStore.shell("cat "+q(fixture+"/namespace.log"),true),ready);
            String body="#!/system/bin/sh\nexec "+q(harness)+" --socket "+q(socket)+" \"$@\"\n";
            FileStore.shell("printf %s "+q(body)+" >"+q(script)+" && chmod 755 "+q(script),true);
            RootMutations.close();RootDirectoryReader.close();RootShell.configure(script);
            String rootIdentity=RootDirectoryReader.stat(view.getPath(),"%d:%i",true);
            assertNotEquals(FileStore.fileIdentity(android.system.Os.stat(view.getPath())),rootIdentity);
            FileStore.Node target=FileStore.directory(view,true);target.directoryIdentity=rootIdentity;
            assertTrue(StorageNavigation.appManagedStorage(view));
            FileStore.Node made=RootMutations.create(context,target,"fallback.bin",false);
            RootMutations.writeEmpty(context,made,new ByteArrayInputStream(payload),payload.length);
            assertBytes(made);
            assertFalse("App's different mount view must remain untouched",new File(view,"fallback.bin").exists());
            assertEquals(made.identity,RootDirectoryReader.stat(view.getPath()+"/fallback.bin","%d:%i",true));
            assertTrue(RootMutations.create(context,target,"fallback-folder",true).directory);
        } finally {
            RootMutations.close();RootDirectoryReader.close();RootShell.configure(regularSu);
            if(pid.matches("[0-9]+"))FileStore.shell("kill "+pid,true);
        }
    }
    private void assertBytes(FileStore.Node node) throws Exception {
        try(InputStream input=store.read(node)){assertArrayEquals(payload,FileStore.readBytes(input,payload.length+1));}
    }
    private static String q(String path){return FileStore.quote(path);}
}
