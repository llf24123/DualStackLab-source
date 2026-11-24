package org.example.filemanager;

import android.content.Context;
import android.os.Environment;
import android.system.Os;
import android.system.StructStat;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.Files;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ProfileTransferRecoveryInstrumentedTest {
    @Test public void mappedOwnerDetectionUsesPinnedDeviceRatherThanPathOrMountpoint() throws Exception {
        String mounts="12 1 8:1 / / rw - ext4 /dev/block/data rw\n"
                +"13 1 0:73 / /storage/emulated rw - fuse /dev/fuse rw\n"
                +"14 1 8:2 / /storage/ABCD-1234 rw - exfat /dev/block/usb rw\n";
        assertTrue(RootFileOps.filesystemMapsOwner(73,new BufferedReader(new StringReader(mounts))));
        assertTrue(RootFileOps.filesystemMapsOwner((8L<<8)|2,new BufferedReader(new StringReader(mounts))));
        assertFalse(RootFileOps.filesystemMapsOwner((8L<<8)|1,new BufferedReader(new StringReader(mounts))));
        assertFalse(RootFileOps.filesystemMapsOwner(99,new BufferedReader(new StringReader(mounts))));
    }
    @Test public void helperAcceptsRealSharedFilesystemOwnershipMapping() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        UiDevice device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        device.executeShellCommand("cmd appops set "+context.getPackageName()+" MANAGE_EXTERNAL_STORAGE allow");
        File fixture=Environment.getExternalStorageDirectory();
        String name="dualstack-mapped-"+java.util.UUID.randomUUID();
        try(RootMutations.Session helper=new RootMutations.Session(context,false)) {
            StructStat parent=Os.stat(fixture.getPath());
            assertNotEquals("Fixture must belong to a different UID",android.os.Process.myUid(),parent.st_uid);
            RootFileOps.Result made=helper.execute(RootFileOps.Request.create(directory(fixture),name+".sh",false),null);
            File output=new File(fixture,name+".sh");
            assertTrue(output.isFile());assertEquals(FileStore.fileIdentity(Os.stat(output.getPath())),made.identity);
            assertNotEquals("FUSE keeps the creator mapping rather than inheriting the parent UID",parent.st_uid,made.uid);
            RootFileOps.Result folder=helper.execute(RootFileOps.Request.create(directory(fixture),name,true),null);
            assertTrue(new File(fixture,name).isDirectory());assertEquals(folder.identity,folder.directoryIdentity);
            RootFileOps.Spec selected=new RootFileOps.Spec(output.getPath(),made.identity,made.parentIdentity,null,false);
            byte[] bytes=new byte[]{5,4,3,2,1};
            helper.execute(RootFileOps.Request.writeEmpty(selected,bytes.length),null,new ByteArrayInputStream(bytes));
            assertArrayEquals(bytes,Files.readAllBytes(output.toPath()));
        } finally {
            Files.deleteIfExists(new File(fixture,name+".sh").toPath());
            Files.deleteIfExists(new File(fixture,name).toPath());
        }
    }
    @Test public void profileClassificationPreservesUserAndPathBoundaries() {
        assertTrue(StorageNavigation.otherUserStorage(new File("/storage/emulated/999/Download"),0));
        assertTrue(StorageNavigation.otherUserStorage(new File("/mnt/user/10/primary/Download"),0));
        assertTrue(StorageNavigation.otherUserStorage(new File("/mnt/runtime/full/emulated/10/a"),0));
        assertFalse(StorageNavigation.otherUserStorage(new File("/storage/emulated/0/a"),0));
        assertFalse(StorageNavigation.otherUserStorage(new File("/storage/emulated/999/a"),999));
        assertTrue(StorageNavigation.otherUserStorage(new File("/storage/emulated/0/a"),999));
        assertFalse(StorageNavigation.otherUserStorage(new File("/storage/emulated/999/../0/a"),0));
        assertFalse(StorageNavigation.otherUserStorage(new File("/storage/ABCD-1234/a"),0));
    }
    private Object cached() throws Exception { Field f=RootMutations.class.getDeclaredField("cached");f.setAccessible(true);return f.get(null); }
    private RootFileOps.Result call(Context context,RootFileOps.Request request) throws Exception {
        Method m=RootMutations.class.getDeclaredMethod("call",Context.class,RootFileOps.Request.class,boolean.class);m.setAccessible(true);
        try{return (RootFileOps.Result)m.invoke(null,context,request,false);}
        catch(InvocationTargetException e){if(e.getCause() instanceof Exception error)throw error;throw e;}
    }
    private RootFileOps.Spec directory(File file){return new RootFileOps.Spec(file.getPath(),null,null,null,true);}
    @Test public void permissionFailureRetiresHelperAndNextTransferSucceeds() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        File fixture=Files.createTempDirectory(context.getCacheDir().toPath(),"profile-recovery-").toFile();
        File denied=new File(fixture,"denied"),destination=new File(fixture,"destination");
        assertTrue(denied.mkdir());assertTrue(destination.mkdir());RootMutations.close();
        try {
            call(context,RootFileOps.Request.create(directory(destination),"before",false));
            RootMutations.Session old=(RootMutations.Session)cached();assertNotNull(old);
            Os.chmod(denied.getPath(),0500);
            try {call(context,RootFileOps.Request.create(directory(denied),"blocked",false));fail("permission failure expected");}
            catch(IOException expected){assertTrue(expected.getMessage(),expected.getMessage().contains("EACCES")||expected.getMessage().contains("EPERM"));}
            assertNull(cached());assertFalse(old.isAlive());assertFalse(new File(denied,"blocked").exists());
            RootFileOps.Result after=call(context,RootFileOps.Request.create(directory(destination),"after",false));
            assertNotNull(after);assertTrue(new File(destination,"after").isFile());assertNotSame(old,cached());
            File input=new File(fixture,"payload.txt");Files.write(input.toPath(),new byte[]{1,2,3,4});
            boolean automatic=FileManagerSettings.prefs(context).getBoolean("root",true);
            RootAccess.setAutomatic(false);
            try {FileStore.Node copied=new FileStore(context).copy(FileStore.local(input),FileStore.local(destination),false);assertArrayEquals(Files.readAllBytes(input.toPath()),Files.readAllBytes(copied.file.toPath()));}
            finally {RootAccess.setAutomatic(automatic);}
        } finally {
            RootMutations.close();Os.chmod(denied.getPath(),0700);
            try(var paths=Files.walk(fixture.toPath())){for(var path:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.delete(path);}
        }
    }
}
