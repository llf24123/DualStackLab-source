package org.example.filemanager;

import java.util.*;
import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;

final class DirectoryCompare {
    enum Kind { LEFT_ONLY, RIGHT_ONLY, TYPE, SIZE, MODIFIED, CONTENT, ERROR }
    record Difference(String name, Kind kind, FileStore.Node left, FileStore.Node right, String detail) {
        Difference(String name, Kind kind, FileStore.Node left, FileStore.Node right) { this(name, kind, left, right, ""); }
        String label() {
            return switch (kind) {
                case LEFT_ONLY -> "仅当前窗口";
                case RIGHT_ONLY -> "仅另一窗口";
                case TYPE -> "类型不同";
                case SIZE -> "大小不同";
                case MODIFIED -> "修改时间不同";
                case CONTENT -> "SHA-256 不同";
                case ERROR -> "未能校验";
            };
        }
    }
    record Options(boolean recursive, boolean sha256) { }
    interface DifferenceSink { void accept(Difference difference) throws IOException; }
    record Report(List<Difference> differences, long files, Options options, String left, String right) {
        String text() {
            StringBuilder text = new StringBuilder("双栈 Lab 目录校验\n当前窗口：" + left + "\n另一窗口：" + right
                    + "\n范围：" + (options.recursive ? "递归子目录" : "当前层") + "\n校验：" + (options.sha256 ? "SHA-256 与元数据" : "名称、类型、大小、时间")
                    + "\n比较项目：" + files + "\n差异或错误：" + differences.size() + "\n");
            for (Difference difference : differences) text.append(difference.label()).append("\t").append(escape(difference.name))
                    .append("\t当前：").append(metadata(difference.left)).append("\t另一：").append(metadata(difference.right))
                    .append(difference.detail.isEmpty() ? "" : "\t" + escape(difference.detail)).append('\n');
            return text.toString();
        }
    }
    private static String escape(String value) { return value.replace("\\", "\\\\").replace("\t", "\\t").replace("\r", "\\r").replace("\n", "\\n"); }
    static String metadata(FileStore.Node node) {
        if (node == null) return "无";
        return (node.directory ? "目录" : node.size < 0 ? "大小未知" : node.size + " 字节") + " / "
                + (node.modified > 0 ? new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date(node.modified)) : "日期未知");
    }
    static final class Scan {
        final AtomicBoolean cancelled = new AtomicBoolean();
        private final android.os.CancellationSignal signal = new android.os.CancellationSignal();
        private volatile InputStream active;
        volatile String current = "准备校验";
        void cancel() {
            cancelled.set(true);
            InputStream stream = active;
            Tasks.CONTROL.execute(() -> { signal.cancel(); if (stream != null) try { stream.close(); } catch (IOException ignored) { } });
        }
        private void check() throws Tasks.CancelledException { if (cancelled.get()) throw new Tasks.CancelledException(); Tasks.Progress.check(); }
        private String digest(FileStore store, FileStore.Node node) throws IOException {
            check(); String before = store.identity(node);
            try (InputStream input = store.read(node)) {
                active = input; check(); String value = FileIntegrity.calculate(input, cancelled).sha256(); check();
                if (!before.equals(store.identity(node))) throw new IOException("读取期间文件发生变化"); return value;
            } finally { active = null; }
        }
        Report run(FileStore store, FileStore.Node left, FileStore.Node right, Options options) throws IOException {
            return run(store, left, right, options, null);
        }
        Report run(FileStore store, FileStore.Node left, FileStore.Node right, Options options, DifferenceSink sink) throws IOException {
            record Pair(FileStore.Node left, FileStore.Node right, String prefix, int depth) { }
            Deque<Pair> queue = new ArrayDeque<>(); queue.add(new Pair(left, right, "", 0));
            Set<String> visitedLeft = new HashSet<>(), visitedRight = new HashSet<>();
            List<Difference> differences = new ArrayList<>(); long count = 0;
            while (!queue.isEmpty()) {
                check(); Pair pair = queue.removeFirst(); current = pair.prefix;
                List<FileStore.Node> a, b;
                try {
                    if (pair.depth > 128) throw new IOException("目录层级超过 128 层");
                    a = children(store, pair.left, visitedLeft); b = children(store, pair.right, visitedRight);
                } catch (Exception error) {
                    check(); add(differences, sink, new Difference(pair.prefix, Kind.ERROR, pair.left, pair.right, message(error))); continue;
                }
                Map<String, FileStore.Node> first = new HashMap<>(), second = new HashMap<>();
                for (var node : a) first.put(node.name, node); for (var node : b) second.put(node.name, node);
                Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder())); names.addAll(first.keySet()); names.addAll(second.keySet());
                for (String name : names) {
                    check(); count++; String path = pair.prefix + name; current = path;
                    FileStore.Node l = first.get(name), r = second.get(name); Kind kind = null; String detail = "";
                    if (l == null) kind = Kind.RIGHT_ONLY;
                    else if (r == null) kind = Kind.LEFT_ONLY;
                    else if (l.directory != r.directory) kind = Kind.TYPE;
                    else if (!l.directory) {
                        try {
                            if (l.size >= 0 && r.size >= 0 && l.size != r.size) kind = Kind.SIZE;
                            else if (options.sha256) {
                                String lh = digest(store, l), rh = digest(store, r);
                                if (!lh.equals(rh)) { kind = Kind.CONTENT; detail = "当前 SHA-256=" + lh + "；另一 SHA-256=" + rh; }
                            }
                            if (kind == null && l.modified != r.modified) kind = Kind.MODIFIED;
                        } catch (Exception error) { check(); kind = Kind.ERROR; detail = message(error); }
                    } else if (l.modified != r.modified) kind = Kind.MODIFIED;
                    if (kind != null) add(differences, sink, new Difference(path, kind, l, r, detail));
                    if (options.recursive && (l != null && l.directory || r != null && r.directory))
                        queue.add(new Pair(l != null && l.directory ? l : null, r != null && r.directory ? r : null, path + "/", pair.depth + 1));
                }
            }
            check(); return new Report(List.copyOf(differences), count, options, left.path(), right.path());
        }
        private void add(List<Difference> items, DifferenceSink sink, Difference difference) throws IOException {
            if (sink == null) items.add(difference); else sink.accept(difference);
        }
        private List<FileStore.Node> children(FileStore store, FileStore.Node node, Set<String> visited) throws IOException {
            if (node == null) return List.of();
            String key = node.key();
            if (node.file != null) {
                if (java.nio.file.Files.isSymbolicLink(node.file.toPath())) throw new IOException("符号链接目录未递归");
                key = node.file.getCanonicalPath();
            }
            if (!visited.add(key)) throw new IOException("重复或循环目录未递归");
            return store.list(node, signal);
        }
        private String message(Exception error) { return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(); }
    }
    static List<Difference> compare(Collection<FileStore.Node> left, Collection<FileStore.Node> right) {
        Map<String, FileStore.Node> a = new HashMap<>(), b = new HashMap<>();
        for (FileStore.Node node : left) a.put(node.name, node);
        for (FileStore.Node node : right) b.put(node.name, node);
        Comparator<String> order = String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder());
        Set<String> names = new TreeSet<>(order); names.addAll(a.keySet()); names.addAll(b.keySet());
        List<Difference> differences = new ArrayList<>();
        for (String name : names) {
            FileStore.Node first = a.get(name), second = b.get(name);
            if (first == null) differences.add(new Difference(name, Kind.RIGHT_ONLY, null, second));
            else if (second == null) differences.add(new Difference(name, Kind.LEFT_ONLY, first, null));
            else if (first.directory != second.directory) differences.add(new Difference(name, Kind.TYPE, first, second));
            else if (!first.directory && first.size != second.size) differences.add(new Difference(name, Kind.SIZE, first, second));
            else if (first.modified != second.modified) differences.add(new Difference(name, Kind.MODIFIED, first, second));
        }
        return differences;
    }
    private DirectoryCompare() { }
}
