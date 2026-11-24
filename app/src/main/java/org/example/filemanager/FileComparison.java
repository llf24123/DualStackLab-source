package org.example.filemanager;

import android.content.Context;
import android.util.Xml;
import com.reandroid.apk.ApkModule;
import com.reandroid.arsc.chunk.TableBlock;
import com.reandroid.arsc.coder.xml.XmlCoder;
import com.reandroid.arsc.model.ResourceEntry;
import com.reandroid.arsc.value.ResConfig;
import org.jf.baksmali.BaksmaliOptions;
import org.jf.baksmali.Adaptors.ClassDefinition;
import org.jf.baksmali.formatter.BaksmaliWriter;
import org.jf.dexlib2.*;
import org.jf.dexlib2.iface.ClassDef;
import org.xmlpull.v1.XmlSerializer;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.BooleanSupplier;

final class FileComparison implements Closeable {
    static final int TEXT_LIMIT = 4 * 1024 * 1024;
    record Item(String key, String digest, long bytes, File text) {}
    record Difference(String name, Item left, Item right) {
        String label() { return left == null ? "新增" : right == null ? "删除" : "修改"; }
    }
    final File directory;
    final FileStore store;
    final BooleanSupplier cancelled;
    final List<Difference> differences = new ArrayList<>();
    final List<FileStore.Node> sources;
    String leftText, rightText;
    final TextMerge.Snapshot[] textSnapshots = new TextMerge.Snapshot[2];
    int compared;
    FileComparison(Context context, FileStore.Node left, FileStore.Node right, BooleanSupplier cancelled) throws IOException {
        this.store = new FileStore(context); this.sources = List.of(left,right); this.cancelled=cancelled;
        directory=Files.createTempDirectory(context.getCacheDir().toPath(),"file-compare-").toFile();
    }
    void load(String mode) throws Exception {
        if(mode.equals("text")) {
            textSnapshots[0]=TextMerge.read(store,sources.get(0),cancelled);
            textSnapshots[1]=TextMerge.read(store,sources.get(1),cancelled);
            leftText=textSnapshots[0].text();rightText=textSnapshots[1].text();return;
        }
        Map<String,Item> a=index(sources.get(0),mode),b=index(sources.get(1),mode);TreeSet<String> keys=new TreeSet<>(a.keySet());keys.addAll(b.keySet());
        for(String key:keys){check();Item left=a.get(key),right=b.get(key);if(left==null||right==null||!left.digest.equals(right.digest))differences.add(new Difference(key,left,right));}
        compared=keys.size();
    }
    byte[] bounded(InputStream in,int limit)throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[32768];int n;
        while((n=in.read(buffer))!=-1){check();if(out.size()+n>limit)throw new IOException("单项内容超过 "+(limit/1024/1024)+" MiB，请分段查看");out.write(buffer,0,n);}return out.toByteArray();
    }
    private File snapshot(FileStore.Node node)throws IOException {
        FileStore.checkName(node.name);File folder=Files.createTempDirectory(directory.toPath(),"source-").toFile();
        File file=new File(folder,node.name);ArchiveCopies.copy(store,node,file,cancelled);return file;
    }
    private Map<String,Item> index(FileStore.Node source,String mode)throws Exception {
        File file=snapshot(source);Map<String,Item> result=new TreeMap<>();boolean apk=source.name.toLowerCase(Locale.ROOT).endsWith(".apk");
        switch(mode){
            case "zip" -> {
                try(ArchiveOps.ReadSession reader=new ArchiveOps.ReadSession(file)) {
                for(String key:ArchiveOps.entryNames(file)){check();if(result.containsKey(key))throw new IOException("归档有重复路径："+key);
                    if(key.endsWith("/")){result.put(key,new Item(key,"directory",0,null));continue;}
                    try(InputStream in=reader.open(key)){result.put(key,item(key,in,null));}
                }
                }
            }
            case "dex" -> {
                if(apk){for(String entry:ArchiveOps.entryNames(file))if(entry.matches("classes(?:[2-9][0-9]*|1[0-9]+)?\\.dex")){
                    File dex=File.createTempFile("dex-",".dex",directory);try(InputStream in=ArchiveOps.openEntry(file,entry);OutputStream out=new FileOutputStream(dex)){byte[] bytes=bounded(in,64*1024*1024);out.write(bytes);}dex(dex,result);
                }}else dex(file,result);
            }
            case "axml" -> {
                synchronized(ApkTools.class){
                    try(ApkModule module=ApkModule.loadApkFile(file)){
                        for(String key:ArchiveOps.entryNames(file))if(key.endsWith(".xml")){
                            check();String text;
                            try(InputStream in=ArchiveOps.openEntry(file,key)){byte[] bytes=bounded(in,TEXT_LIMIT);
                                if(com.reandroid.arsc.chunk.xml.ResXmlDocument.isResXmlBlock(bytes))text=module.loadResXmlDocument(key).serializeToXml();else text=TextCodec.decode(bytes,null).text();}
                            result.put(key,textItem(key,text));
                        }
                    }
                }
            }
            case "arsc" -> {
                synchronized(ApkTools.class){
                    if(apk){try(ApkModule module=ApkModule.loadApkFile(file)){TableBlock table=module.getTableBlock();if(table==null)throw new IOException("APK 没有资源表");resources(table,result);}}
                    else {if(file.length()>64L*1024*1024)throw new IOException("资源表超过 64 MiB");resources(TableBlock.load(file),result);}
                }
            }
            default -> throw new IOException("未知对比类型");
        }return result;
    }
    private void dex(File file,Map<String,Item> out)throws Exception {
        check();if(file.length()>64L*1024*1024)throw new IOException("DEX 超过 64 MiB");
        BaksmaliOptions options=new BaksmaliOptions();options.apiLevel=28;options.debugInfo=true;options.localsDirective=true;
        for(ClassDef cls:DexFileFactory.loadDexFile(file,Opcodes.getDefault()).getClasses()){
            check();String key=cls.getType();if(out.containsKey(key))throw new IOException("DEX 中有重复类："+key);
            File text=File.createTempFile("class-",".smali",directory);
            try(Writer writer=new OutputStreamWriter(new FileOutputStream(text),StandardCharsets.UTF_8)){
                ClassDefinition definition=new ClassDefinition(options,cls);definition.writeTo(new BaksmaliWriter(writer));
                if(definition.hadValidationErrors())throw new IOException("类反汇编失败："+key);
            }
            try(InputStream in=new FileInputStream(text)){out.put(key,item(key,in,text));}
            if(out.size()>100000)throw new IOException("类数量超过 100000");
        }
    }
    private void resources(TableBlock table,Map<String,Item> out)throws Exception {
        Iterator<ResourceEntry> resources=table.getResources();XmlCoder coder=new XmlCoder();
        while(resources.hasNext()){
            check();ResourceEntry resource=resources.next();String key=resource.getPackageName()+":"+resource.getType()+"/"+resource.getName();
            StringWriter text=new StringWriter();text.append("<!-- resource id: ").append(resource.getHexId()).append(" -->\n");
            List<ResConfig> configs=new ArrayList<>();resource.getConfigs().forEachRemaining(configs::add);configs.sort(Comparator.comparing(Object::toString));
            for(ResConfig config:configs){check();text.append("<!-- ").append(config.toString()).append(" -->\n");XmlSerializer xml=Xml.newSerializer();xml.setOutput(text);xml.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output",true);coder.VALUES_XML.decode(xml,resource,config,e->false);xml.flush();text.append('\n');if(text.getBuffer().length()>TEXT_LIMIT)throw new IOException("资源内容过长："+key);}
            if(out.put(key,textItem(key,text.toString()))!=null)throw new IOException("重复资源名称："+key);
        }
    }
    private Item textItem(String key,String content)throws Exception {
        File text=File.createTempFile("decoded-",".txt",directory);Files.write(text.toPath(),content.getBytes(StandardCharsets.UTF_8));
        try(InputStream in=new FileInputStream(text)){return item(key,in,text);}
    }
    private Item item(String key,InputStream in,File text)throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] data=new byte[65536];int count;long length=0;
        while((count=in.read(data))!=-1){check();digest.update(data,0,count);length+=count;}return new Item(key,HexDocument.hex(digest.digest()),length,text);
    }
    String content(Item item)throws IOException {
        if(item==null)return "";
        if(item.text==null)return item.key+"\n字节："+item.bytes+"\nSHA-256："+item.digest;
        try(InputStream in=new FileInputStream(item.text)){return new String(bounded(in,TEXT_LIMIT),StandardCharsets.UTF_8);}
    }
    private void check()throws IOException{TextDiff.check(cancelled);}
    @Override public void close()throws IOException{
        try(var paths=Files.walk(directory.toPath())){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
    }
}
