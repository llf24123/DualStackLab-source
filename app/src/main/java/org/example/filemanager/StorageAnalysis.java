package org.example.filemanager;

import android.os.CancellationSignal;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

final class StorageAnalysis {
    record Entry(FileStore.Node node, long bytes, long files, boolean incomplete) { }
    record Duplicates(String sha256, long size, List<FileStore.Node> files) {
        long redundantBytes() { return size * (files.size() - 1L); }
    }
    record Result(List<Entry> entries, List<Duplicates> duplicates, List<String> errors, long errorCount, long files, long bytes, long ignored) { }
    static final class Scan {
        final AtomicBoolean cancelled = new AtomicBoolean();
        private final CancellationSignal signal = new CancellationSignal();
        private volatile FileStatistics.Scan statistics;
        private volatile PropertyChecksums.Scan checksum;
        volatile String current = "准备扫描";
        volatile long scanned, hashed;
        void cancel() {
            cancelled.set(true);
            FileStatistics.Scan count = statistics; if (count != null) count.cancel();
            PropertyChecksums.Scan hash = checksum; if (hash != null) hash.cancel();
            Tasks.CONTROL.execute(signal::cancel);
        }
        private void check() throws Tasks.CancelledException { if (cancelled.get()) throw new Tasks.CancelledException(); Tasks.Progress.check(); }
        Result run(FileStore store, FileStore.Node root, boolean duplicates) throws IOException {
            List<Entry> entries = new ArrayList<>(); List<String> errors = new ArrayList<>();
            Map<Long, List<FileStore.Node>> sizes = new HashMap<>();
            check();
            {
                List<FileStore.Node> roots = store.list(root, signal);
                long[] branchBytes = new long[roots.size()], branchFiles = new long[roots.size()];
                boolean[] incomplete = new boolean[roots.size()];
                FileStatistics.Scan counting = new FileStatistics.Scan();
                statistics = counting;
                FileStatistics.Result counted;
                try {
                    check();
                    counted = counting.run(store, roots, state -> {
                        scanned = state.files();
                        current = state.current();
                    }, visit -> {
                        FileStatistics.Entry entry = visit.entry();
                        if (entry.kind() != FileStatistics.Kind.FILE && entry.kind() != FileStatistics.Kind.SPECIAL) return;
                        int index = visit.rootIndex();
                        branchFiles[index]++;
                        if (entry.kind() == FileStatistics.Kind.SPECIAL || entry.bytes() < 0) {
                            incomplete[index] = true;
                            return;
                        }
                        branchBytes[index] = Math.addExact(branchBytes[index], entry.bytes());
                        if (duplicates) sizes.computeIfAbsent(entry.bytes(), value -> new ArrayList<>()).add(entry.node());
                    });
                } finally { statistics = null; }
                check();
                FileStatistics.Snapshot state = counted.snapshot();
                long errorCount = state.errorCount();
                boolean unassignedError = errorCount > counted.errors().size();
                for (FileStatistics.Issue issue : counted.errors()) {
                    addError(errors, issue.path() + "：" + issue.message());
                    if (issue.rootIndex() >= 0) incomplete[issue.rootIndex()] = true;
                    else unassignedError = true;
                }
                for (int i = 0; i < roots.size(); i++)
                    entries.add(new Entry(roots.get(i), branchBytes[i], branchFiles[i], incomplete[i] || unassignedError));
                List<Duplicates> matches = new ArrayList<>();
                if (duplicates) for (var candidates : sizes.entrySet()) {
                    check(); if (candidates.getValue().size() < 2) continue;
                    Map<String, List<FileStore.Node>> hashes = new LinkedHashMap<>();
                    for (FileStore.Node node : candidates.getValue()) {
                        check(); current = "校验 " + node.path();
                        try {
                            PropertyChecksums.Scan hashing = new PropertyChecksums.Scan(); checksum = hashing; check();
                            PropertyChecksums.Values value;
                            try { value = hashing.calculateSha256(store, node, null); }
                            finally { checksum = null; }
                            check();
                            if (value.bytes() != candidates.getKey()) throw new IOException("文件大小在扫描期间已变化");
                            hashed++; hashes.computeIfAbsent(value.sha256(), key -> new ArrayList<>()).add(node);
                        } catch (Exception error) { check(); errorCount++; addError(errors, node.path() + "：" + message(error)); }
                    }
                    for (var group : hashes.entrySet()) if (group.getValue().size() > 1)
                        matches.add(new Duplicates(group.getKey(), candidates.getKey(), List.copyOf(group.getValue())));
                }
                entries.sort(Comparator.comparingLong(Entry::bytes).reversed().thenComparing(e -> e.node.name, NaturalOrder::compare));
                matches.sort(Comparator.comparingLong(Duplicates::redundantBytes).reversed());
                if (errorCount > errors.size()) errors.add("共 " + errorCount + " 项错误，仅显示前 100 项。");
                check(); return new Result(List.copyOf(entries), List.copyOf(matches), List.copyOf(errors), errorCount,
                        state.files(), state.bytes(), state.skippedLinks() + state.duplicateNodes());
            }
        }
        private static String message(Exception e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
        private static void addError(List<String> errors, String message) {
            if (errors.size() < 100) errors.add(message.length() > 2048 ? message.substring(0,2048) + "…" : message);
        }
    }
    static String objectKey(FileStore store, FileStore.Node node) throws IOException {
        if (node.file != null) {
            String[] stat = store.identity(node).split(":");
            if (stat.length >= 2) return "inode:" + stat[0] + ":" + stat[1];
        }
        return node.key();
    }
    private StorageAnalysis() { }
}
