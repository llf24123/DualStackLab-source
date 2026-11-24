package org.example.filemanager;

import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.function.BooleanSupplier;

/** A merge changes a complete diff block, keeping the destination's encoding and surrounding bytes. */
final class TextMerge {
    record Snapshot(byte[] bytes, TextCodec.Decoded decoded, String revision) {
        String text() { return decoded.text(); }
    }
    record Undo(String key, byte[] bytes, String revision) { }
    record Block(int first, int end, int leftStart, int leftEnd, int rightStart, int rightEnd) { }
    private record Line(String text, String ending) { }

    static Snapshot read(FileStore store, FileStore.Node node, BooleanSupplier cancelled) throws IOException {
        String before = store.fingerprint(node);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = store.read(node)) {
            byte[] buffer = new byte[32768]; int n;
            while ((n = in.read(buffer)) != -1) {
                TextDiff.check(cancelled);
                if (out.size() + n > FileComparison.TEXT_LIMIT) throw new IOException("文本超过 4 MiB，请分段查看");
                out.write(buffer, 0, n);
            }
        }
        TextDiff.check(cancelled);
        if (!before.equals(store.fingerprint(node))) throw new IOException("读取期间来源已变化，请重新对比");
        byte[] bytes = out.toByteArray();
        return new Snapshot(bytes, TextCodec.decode(bytes, null), before);
    }

    static Block block(List<TextDiff.Row> rows, int selected) {
        if (selected < 0 || selected >= rows.size() || rows.get(selected).kind() == TextDiff.Kind.SAME)
            throw new IllegalArgumentException("请选择一处差异");
        int first = selected, end = selected + 1;
        while (first > 0 && rows.get(first - 1).kind() != TextDiff.Kind.SAME) first--;
        while (end < rows.size() && rows.get(end).kind() != TextDiff.Kind.SAME) end++;
        int a = 0, b = 0, ae = 0, be = 0;
        for (int i = 0; i < end; i++) {
            TextDiff.Row row = rows.get(i);
            if (i < first) { if (row.leftLine() > 0) a++; if (row.rightLine() > 0) b++; }
            if (row.leftLine() > 0) ae++; if (row.rightLine() > 0) be++;
        }
        return new Block(first, end, a, ae, b, be);
    }

    static String apply(String left, String right, List<TextDiff.Row> rows, int selected, boolean toRight) {
        Block block = block(rows, selected);
        List<Line> source = lines(toRight ? left : right), target = lines(toRight ? right : left);
        int sourceStart = toRight ? block.leftStart : block.rightStart;
        int sourceEnd = toRight ? block.leftEnd : block.rightEnd;
        int targetStart = toRight ? block.rightStart : block.leftStart;
        int targetEnd = toRight ? block.rightEnd : block.leftEnd;
        String ending = preferredEnding(target, source);
        List<Line> result = new ArrayList<>(target.subList(0, targetStart));
        for (Line line : source.subList(sourceStart, sourceEnd))
            result.add(new Line(line.text, line.ending.isEmpty() ? "" : ending));
        result.addAll(target.subList(targetEnd, target.size()));
        return join(result, ending);
    }

    static String applyAll(String source, String target) {
        if (source.equals(target)) return target;
        List<Line> input = lines(source);
        String ending = preferredEnding(lines(target), input);
        List<Line> result = new ArrayList<>();
        for (Line line : input) result.add(new Line(line.text, line.ending.isEmpty() ? "" : ending));
        return join(result, ending);
    }

    private static String join(List<Line> lines, String ending) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i); out.append(line.text);
            // The last logical line has no terminator. Removing the final empty line
            // also removes the newline formerly attached to its preceding line.
            if (i + 1 < lines.size()) out.append(line.ending.isEmpty() ? ending : line.ending);
        }
        return out.toString();
    }

    private static List<Line> lines(String text) {
        List<Line> result = new ArrayList<>(); if (text.isEmpty()) return result;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '\r' && c != '\n') continue;
            int end = i + 1;
            if (c == '\r' && end < text.length() && text.charAt(end) == '\n') end++;
            result.add(new Line(text.substring(start, i), text.substring(i, end)));
            i = end - 1; start = end;
        }
        result.add(new Line(text.substring(start), "")); return result;
    }

    private static String preferredEnding(List<Line> target, List<Line> source) {
        LinkedHashMap<String,Integer> counts = new LinkedHashMap<>();
        for (Line line : target) if (!line.ending.isEmpty()) counts.merge(line.ending, 1, Integer::sum);
        if (counts.isEmpty()) for (Line line : source) if (!line.ending.isEmpty()) return line.ending;
        String best = "\n"; int maximum = 0;
        for (var entry : counts.entrySet()) if (entry.getValue() > maximum) { best = entry.getKey(); maximum = entry.getValue(); }
        return best;
    }

    static Undo save(FileStore store, FileStore.Node target, Snapshot baseline, String text,
                     BooleanSupplier cancelled) throws IOException {
        // Reject a non-roundtripping decoder rather than silently rewriting legacy/control bytes.
        if (!Arrays.equals(baseline.bytes, TextCodec.encode(baseline.text(), baseline.decoded.charset(), baseline.decoded.bom())))
            throw new IOException("原编码不能无损重新编码，请先在文本编辑器中确认编码");
        byte[] encoded = TextCodec.encode(text, baseline.decoded.charset(), baseline.decoded.bom());
        if (encoded.length > FileComparison.TEXT_LIMIT) throw new IOException("合并结果超过 4 MiB，请使用文本编辑器");
        TextDiff.check(cancelled); store.checkRevision(target, baseline.revision);
        File staging = File.createTempFile("text-merge-", ".tmp", store.context().getCacheDir());
        try {
            Files.write(staging.toPath(), encoded);
            EditorBackups.beforeSave(store, target);
            TextDiff.check(cancelled);
            String revision = store.write(target, staging, baseline.revision);
            return new Undo(target.key(), baseline.bytes, revision);
        } finally { Files.deleteIfExists(staging.toPath()); }
    }

    static void undo(FileStore store, Undo undo, BooleanSupplier cancelled) throws IOException {
        FileStore.Node target = store.fromKey(undo.key);
        TextDiff.check(cancelled); store.checkRevision(target, undo.revision);
        File staging = File.createTempFile("text-merge-undo-", ".tmp", store.context().getCacheDir());
        try {
            Files.write(staging.toPath(), undo.bytes); TextDiff.check(cancelled);
            store.write(target, staging, undo.revision);
        } finally { Files.deleteIfExists(staging.toPath()); }
    }
    private TextMerge() { }
}
