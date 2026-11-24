package org.example.filemanager;

import android.content.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SettingsAlignmentInstrumentedTest {
    Context context; File work; FileStore store; SettingsExtras.Snapshot original;
    final Map<String, Map<String, ?>> otherPrefs = new LinkedHashMap<>();
    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext(); store = new FileStore(context);
        work = Files.createTempDirectory(context.getCacheDir().toPath(), "settings41-").toFile();
        original = SettingsExtras.snapshot(context);
        for (String group : SettingsExtras.GROUPS) context.getSharedPreferences(group, 0).edit().clear().commit();
        for (File syntax : MtsxSyntax.installed(context)) Files.delete(syntax.toPath());
        for (String name : List.of("MainActivity", "appearance", "directory-places", RenameTemplates.PREFS, OpenMethods.PREFS))
            otherPrefs.put(name, new HashMap<>(context.getSharedPreferences(name, 0).getAll()));
    }
    @After public void cleanup() throws Exception {
        SettingsExtras.restore(context, original);
        for (var entry : otherPrefs.entrySet()) {
            var edit = context.getSharedPreferences(entry.getKey(), 0).edit().clear();
            for (var item : entry.getValue().entrySet()) {
                Object v = item.getValue(); String key = item.getKey();
                if (v instanceof String s) edit.putString(key,s); else if (v instanceof Boolean b) edit.putBoolean(key,b);
                else if (v instanceof Integer n) edit.putInt(key,n); else if (v instanceof Float n) edit.putFloat(key,n); else if (v instanceof Long n) edit.putLong(key,n);
            } edit.commit();
        }
        try (var paths = Files.walk(work.toPath())) { for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
    }
    private File text(String name, String value) throws IOException { File file = new File(work,name); Files.write(file.toPath(),value.getBytes(StandardCharsets.UTF_8)); return file; }
    private String read(File file) throws IOException { return new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8); }
    private File syntax() throws Exception { MtsxSyntax.install(context,"{name:[\"Settings Test\",\".test41\"] contains:[{match:/hello/,0:\"keyword\"}]}".getBytes(StandardCharsets.UTF_8)); return MtsxSyntax.installed(context).get(0); }
    @Test public void settingsBackupRestoresSyntaxFloatSizeAndMenuOrder() throws Exception {
        File syntax=syntax(); byte[] source=Files.readAllBytes(syntax.toPath());
        FileManagerSettings.prefs(context).edit().putInt("name-lines",8).putString("menu-order","[7,1,0]").putBoolean("animations",false).commit();
        context.getSharedPreferences("editor-display",0).edit().putFloat("textSize",17.5f).putBoolean("tabs",true).commit();
        byte[] backup=SettingsBackup.export(context);
        FileManagerSettings.prefs(context).edit().clear().commit(); context.getSharedPreferences("editor-display",0).edit().clear().commit(); Files.delete(syntax.toPath());
        SettingsBackup.apply(context,SettingsBackup.read(new ByteArrayInputStream(backup)),false);
        assertEquals(8,FileListDisplay.lines(context)); assertEquals(List.of(7,1,0),FileMenuOrder.load(context).subList(0,3));
        assertEquals(17.5f,context.getSharedPreferences("editor-display",0).getFloat("textSize",0),0f);
        assertTrue(context.getSharedPreferences("editor-display",0).getBoolean("tabs",false));
        assertArrayEquals(source,Files.readAllBytes(syntax.toPath()));
    }
    @Test public void invalidExtendedSettingIsRejectedBeforeAnyChange() throws Exception {
        FileManagerSettings.prefs(context).edit().putInt("accent",2).commit();
        var data=SettingsBackup.read(new ByteArrayInputStream(SettingsBackup.export(context)));
        data.main().put("comfortable",true); data.extras().getJSONObject("file-manager").put("name-lines",99);
        String before=SettingsBackup.export(context).length+":"+FileManagerSettings.prefs(context).getAll();
        boolean old=context.getSharedPreferences("MainActivity",0).getBoolean("comfortable",false);
        try { SettingsBackup.apply(context,data,false); fail("Invalid settings applied"); } catch (IOException expected) { }
        assertEquals(old,context.getSharedPreferences("MainActivity",0).getBoolean("comfortable",false));
        assertEquals(before,SettingsBackup.export(context).length+":"+FileManagerSettings.prefs(context).getAll());
    }
    @Test public void validBackupCanRepairDamagedInstalledSyntax() throws Exception {
        File file=syntax(); byte[] valid=Files.readAllBytes(file.toPath()),backup=SettingsBackup.export(context);
        Files.write(file.toPath(),"broken syntax".getBytes(StandardCharsets.UTF_8));
        SettingsBackup.apply(context,SettingsBackup.read(new ByteArrayInputStream(backup)),false);
        assertArrayEquals(valid,Files.readAllBytes(file.toPath()));
    }
    @Test public void invalidSyntaxBackupCannotReplaceWorkingSyntax() throws Exception {
        File file=syntax(); byte[] valid=Files.readAllBytes(file.toPath()); JSONObject backup=new JSONObject(new String(SettingsBackup.export(context),StandardCharsets.UTF_8));
        backup.getJSONObject("extras").getJSONObject("syntax").put(file.getName(),"{name:[\"oops\"] contains:[{match:/(invalid/}]}");
        try { SettingsBackup.read(new ByteArrayInputStream(backup.toString().getBytes(StandardCharsets.UTF_8))); fail("Broken syntax accepted"); } catch (IOException expected) { }
        assertArrayEquals(valid,Files.readAllBytes(file.toPath()));
    }
    @Test public void dateSettingsPreserveQuotedTextAndSeconds() {
        FileManagerSettings.prefs(context).edit().putString("date-format","yyyy-MM-dd 'seconds: ss' HH:mm:ss.SSS").putBoolean("short-year",true).putBoolean("seconds",false).commit();
        String value=FileListDisplay.dates(context).format(new GregorianCalendar(2026,10,17,16,25,36).getTime());
        assertEquals("26-11-17 seconds: ss 16:25",value);
        FileManagerSettings.prefs(context).edit().putBoolean("short-year",false).putBoolean("seconds",true).commit();
        assertEquals("2026-11-17 seconds: ss 16:25:36.000",FileListDisplay.dates(context).format(new GregorianCalendar(2026,10,17,16,25,36).getTime()));
    }
    @Test public void editorBackupDefaultOffAndRepeatedBackupsNeverOverwrite() throws Exception {
        File file=text("draft.txt","original"); EditorBackups.beforeSave(store,FileStore.local(file)); assertEquals(1,Objects.requireNonNull(work.list()).length);
        FileManagerSettings.prefs(context).edit().putBoolean("editor-backup",true).commit();
        EditorBackups.beforeSave(store,FileStore.local(file)); assertEquals("original",read(new File(work,"draft.txt.bak")));
        Files.write(file.toPath(),"second".getBytes(StandardCharsets.UTF_8)); EditorBackups.beforeSave(store,FileStore.local(file));
        assertEquals("original",read(new File(work,"draft.txt.bak")));
        List<String> contents=new ArrayList<>(); for(File f:Objects.requireNonNull(work.listFiles()))contents.add(read(f));
        assertEquals(3,contents.size()); assertEquals(2,Collections.frequency(contents,"second"));
    }
    @Test public void archiveEditorBackupPreservesOriginalContainerWithoutChangingRevision() throws Exception {
        File archive=new File(work,"original.zip"); try(ZipOutputStream output=new ZipOutputStream(new FileOutputStream(archive))){output.putNextEntry(new ZipEntry("entry.txt"));output.write("before".getBytes(StandardCharsets.UTF_8));output.closeEntry();}
        byte[] original=Files.readAllBytes(archive.toPath()); FileStore.Node entry=FileStore.archiveNode(FileStore.local(archive),"entry.txt",false,6,0);
        String revision=store.fingerprint(entry); FileManagerSettings.prefs(context).edit().putBoolean("editor-backup",true).commit();
        EditorBackups.beforeSave(store,entry); assertEquals(revision,store.fingerprint(entry));
        store.write(entry,text("replacement.txt","after"));
        assertArrayEquals(original,Files.readAllBytes(new File(work,"original.zip.bak").toPath()));
        assertEquals("after",new String(ArchiveOps.bytes(archive,"entry.txt",100),StandardCharsets.UTF_8));
    }
}
