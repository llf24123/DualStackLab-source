package org.example.filemanager;

import android.content.pm.PackageInfo;
import androidx.exifinterface.media.ExifInterface;
import java.io.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import org.json.*;

/** Parses the manual's filename expressions once; metadata is read only for requested tokens. */
final class RenameExpression implements BatchRename.Naming {
    static final String DEFAULT_DATE = "yyyy-MM-dd_HH-mm-ss";
    record Config(String expression, String date, String find, String replacement, boolean regex) {
        JSONObject json() throws JSONException {
            return new JSONObject().put("expression", expression).put("date", date).put("find", find)
                    .put("replacement", replacement).put("regex", regex);
        }
        static Config read(JSONObject value) throws IOException {
            try {
                for (String key : List.of("expression", "date", "find", "replacement"))
                    if (!(value.get(key) instanceof String text) || text.length() > 2048) throw new IOException("模板内容无效");
                if (!(value.get("regex") instanceof Boolean)) throw new IOException("模板开关无效");
                Config config = new Config(value.getString("expression"), value.getString("date"), value.getString("find"), value.getString("replacement"), value.getBoolean("regex"));
                new RenameExpression(config); return config;
            } catch (JSONException | IllegalArgumentException invalid) { throw new IOException("模板表达式无效：" + invalid.getMessage(), invalid); }
        }
    }
    private record Part(String value, boolean token) { }
    private final Config config;
    private final List<Part> parts = new ArrayList<>();
    private final DateTimeFormatter date;
    private final RenameRegex replace;
    private boolean photo, apk;
    RenameExpression(Config config) {
        this.config = config;
        if (config.expression.isEmpty() || config.expression.length() > 2048) throw new IllegalArgumentException("请输入 1–2048 字的文件名表达式");
        if (config.date.length() > 2048 || config.find.length() > 2048 || config.replacement.length() > 2048) throw new IllegalArgumentException("输入内容不能超过 2048 字");
        date = DateTimeFormatter.ofPattern(config.date.isEmpty() ? DEFAULT_DATE : config.date, Locale.getDefault());
        replace = config.regex && !config.find.isEmpty() ? new RenameRegex(config.find) : null;
        StringBuilder literal = new StringBuilder();
        for (int i = 0; i < config.expression.length();) {
            char c = config.expression.charAt(i++);
            if ((c == '{' || c == '}') && i < config.expression.length() && config.expression.charAt(i) == c) { literal.append(c); i++; continue; }
            if (c == '}') throw new IllegalArgumentException("表达式存在多余的 }，文字花括号请写成 }}");
            if (c != '{') { literal.append(c); continue; }
            if (literal.length() > 0) { parts.add(new Part(literal.toString(), false)); literal.setLength(0); }
            int end = config.expression.indexOf('}', i);
            if (end < 0) throw new IllegalArgumentException("表达式缺少 }");
            String token = config.expression.substring(i, end); i = end + 1;
            if (!Set.of("P", "S", "T", "E", "AN", "AP", "AV", "AC").contains(token)) {
                if (!token.matches("z?[0-9]+")) throw new IllegalArgumentException("未知占位符：{" + token + "}");
                try { Long.parseLong(token.startsWith("z") ? token.substring(1) : token); }
                catch (NumberFormatException tooLarge) { throw new IllegalArgumentException("序号过大"); }
            }
            photo |= token.equals("E"); apk |= token.startsWith("A"); parts.add(new Part(token, true));
        }
        if (literal.length() > 0) parts.add(new Part(literal.toString(), false));
    }
    public String name(FileStore store, FileStore.Node node, int index, int total) throws IOException {
        Tasks.Progress.check();
        int dot = node.directory ? -1 : node.name.lastIndexOf('.');
        String stem = dot > 0 ? node.name.substring(0, dot) : node.name, suffix = dot > 0 ? node.name.substring(dot) : "";
        ZonedDateTime modified = Instant.ofEpochMilli(node.modified).atZone(ZoneId.systemDefault());
        ZonedDateTime shot = photo ? photoTime(store, node, modified) : modified;
        ApkMetadata info = apk ? apkInfo(store, node) : null;
        StringBuilder out = new StringBuilder();
        for (Part part : parts) {
            if (!part.token) { out.append(part.value); continue; }
            switch (part.value) {
                case "P" -> out.append(stem);
                case "S" -> out.append(suffix);
                case "T" -> out.append(date.format(modified));
                case "E" -> out.append(date.format(shot));
                case "AN" -> out.append(info.label);
                case "AP" -> out.append(info.packageName);
                case "AV" -> out.append(info.versionName == null ? "" : info.versionName);
                case "AC" -> out.append(info.versionCode);
                default -> {
                    boolean padded = part.value.startsWith("z");
                    long start = Long.parseLong(padded ? part.value.substring(1) : part.value), value, last;
                    try { value = Math.addExact(start, index); last = Math.addExact(start, Math.max(0, total - 1)); }
                    catch (ArithmeticException overflow) { throw new IOException("递增序号超过上限"); }
                    String number = Long.toString(value);
                    if (padded) out.append("0".repeat(Math.max(0, Long.toString(last).length() - number.length())));
                    out.append(number);
                }
            }
        }
        String value = out.toString();
        if (!config.find.isEmpty()) {
            try { value = replace == null ? value.replace(config.find, config.replacement)
                    : replace.replace(value, config.replacement); }
            catch (RuntimeException invalid) { throw new IOException("查找替换失败：" + invalid.getMessage(), invalid); }
        }
        if (value.length() > 4096) throw new IOException("生成的文件名过长");
        return value;
    }
    private static ZonedDateTime photoTime(FileStore store, FileStore.Node node, ZonedDateTime fallback) throws IOException {
        if (node.directory || !PreviewActivity.isImageName(node.name)) return fallback;
        try (InputStream input = store.read(node)) {
            ExifInterface exif = new ExifInterface(input);
            for (String key : List.of(ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED, ExifInterface.TAG_DATETIME)) {
                String value = exif.getAttribute(key);
                if (value == null) continue;
                try { return LocalDateTime.parse(value.trim(), DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss", Locale.ROOT)).atZone(ZoneId.systemDefault()); }
                catch (DateTimeParseException ignored) { }
            }
        }
        return fallback;
    }
    private record ApkMetadata(String label, String packageName, String versionName, long versionCode) { }
    private static ApkMetadata apkInfo(FileStore store, FileStore.Node node) throws IOException {
        if (node.directory || !ApkInstall.isApkName(node.name)) throw new IOException("APK 信息占位符只能用于 APK 文件");
        File file = store.materialize(node);
        try {
            PackageInfo info = store.context().getPackageManager().getPackageArchiveInfo(file.getPath(), 0);
            if (info == null || info.applicationInfo == null) throw new IOException("无法读取 APK 信息");
            info.applicationInfo.sourceDir = file.getPath(); info.applicationInfo.publicSourceDir = file.getPath();
            return new ApkMetadata(info.applicationInfo.loadLabel(store.context().getPackageManager()).toString(), info.packageName, info.versionName,
                    android.os.Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode);
        } finally { store.release(node, file); }
    }
}
