package org.example.filemanager;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Completed reports survive recreation; only one page is decoded for presentation. */
final class CompareReport {
    static final int PAGE_SIZE = 200;
    record Row(String name, String label, String left, String right, String detail) { }
    static final class Stored {
        final File directory; final String id, heading; final long files; final int count;
        Stored(File directory, String id, JSONObject metadata) throws JSONException {
            this.directory = directory; this.id = id; heading = metadata.getString("heading");
            files = metadata.getLong("files"); count = metadata.getInt("count");
        }
        List<Row> page(int page) throws IOException {
            List<Row> result = new ArrayList<>(); if (page < 0 || (long)page * PAGE_SIZE >= count) return result;
            try (RandomAccessFile data = new RandomAccessFile(new File(directory, id + ".rows"), "r");
                 RandomAccessFile index = new RandomAccessFile(new File(directory, id + ".idx"), "r")) {
                index.seek((long)page * 8); data.seek(index.readLong());
                for (int i = 0; i < Math.min(PAGE_SIZE, count - page * PAGE_SIZE); i++) result.add(readRow(data));
            } return result;
        }
        void export(OutputStream stream) throws IOException {
            Writer out = new BufferedWriter(new OutputStreamWriter(stream, StandardCharsets.UTF_8)); out.write(heading);
            try (RandomAccessFile data = new RandomAccessFile(new File(directory, id + ".rows"), "r")) {
                for (int i = 0; i < count; i++) {
                    Tasks.Progress.check(); Row row = readRow(data);
                    out.write(row.label + "\t" + escape(row.name) + "\t当前：" + escape(row.left) + "\t另一：" + escape(row.right));
                    if (!row.detail.isEmpty()) out.write("\t" + escape(row.detail)); out.write('\n');
                }
            } out.flush();
        }
    }
    static Stored create(Context context, FileStore store, FileStore.Node left, FileStore.Node right,
                         DirectoryCompare.Options options, DirectoryCompare.Scan scan) throws Exception {
        File directory = directory(context); String id = UUID.randomUUID().toString();
        File rows = new File(directory, id + ".rows"), index = new File(directory, id + ".idx"), meta = new File(directory, id + ".json");
        int[] count = {0}; boolean complete = false;
        try {
            DirectoryCompare.Report report;
            try (RandomAccessFile data = new RandomAccessFile(rows, "rw"); RandomAccessFile offsets = new RandomAccessFile(index, "rw")) {
                report = scan.run(store, left, right, options, difference -> {
                    try {
                        if (count[0] % PAGE_SIZE == 0) offsets.writeLong(data.getFilePointer());
                        JSONObject row = new JSONObject().put("name", difference.name()).put("label", difference.label())
                                .put("left", DirectoryCompare.metadata(difference.left())).put("right", DirectoryCompare.metadata(difference.right())).put("detail", difference.detail());
                        data.write((row + "\n").getBytes(StandardCharsets.UTF_8)); count[0]++;
                    } catch (JSONException error) { throw new IOException(error); }
                });
                data.getFD().sync(); offsets.getFD().sync();
            }
            String heading = "双栈 Lab 目录校验\n当前窗口：" + left.path() + "\n另一窗口：" + right.path()
                    + "\n范围：" + (options.recursive() ? "递归子目录" : "当前层") + "\n校验：" + (options.sha256() ? "SHA-256 与元数据" : "名称、类型、大小、时间")
                    + "\n比较项目：" + report.files() + "\n差异或错误：" + count[0] + "\n";
            JSONObject metadata = new JSONObject().put("heading", heading).put("files", report.files()).put("count", count[0]);
            try (FileOutputStream out = new FileOutputStream(meta)) { out.write(metadata.toString().getBytes(StandardCharsets.UTF_8)); out.getFD().sync(); }
            complete = true; return new Stored(directory, id, metadata);
        } finally { if (!complete) { rows.delete(); index.delete(); meta.delete(); } }
    }
    static Stored open(Context context, String id) throws Exception {
        if (id == null || !id.matches("[0-9a-f-]{36}")) throw new IOException("校验记录地址无效");
        File directory = directory(context);
        try (InputStream in = new FileInputStream(new File(directory, id + ".json"))) {
            return new Stored(directory, id, new JSONObject(new String(FileStore.readBytes(in, 65536), StandardCharsets.UTF_8)));
        }
    }
    private static Row readRow(RandomAccessFile file) throws IOException {
        String raw = file.readLine(); if (raw == null) throw new EOFException("校验记录不完整");
        try {
            JSONObject row = new JSONObject(new String(raw.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8));
            return new Row(row.getString("name"), row.getString("label"), row.getString("left"), row.getString("right"), row.getString("detail"));
        } catch (JSONException e) { throw new IOException("校验记录损坏", e); }
    }
    private static File directory(Context context) throws IOException {
        File directory = new File(context.getFilesDir(), "comparison-reports");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法保存校验结果"); return directory;
    }
    private static String escape(String s) { return s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r"); }
    private CompareReport() { }
}
