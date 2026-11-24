package org.example.filemanager;

import java.io.IOException;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Line-oriented merge. Conflicting regions require an explicit choice. */
final class ThreeWayMerge {
    private record Edit(int from, int to, List<String> replacement) { }
    record Region(int baseLine, List<String> base, List<String> left, List<String> right, boolean conflict) { }
    record Plan(List<Region> regions) {
        List<Region> conflicts() { return regions.stream().filter(Region::conflict).toList(); }
        String render(List<Integer> choices) throws IOException {
            List<String> output = new ArrayList<>();
            int index = 0;
            for (Region region : regions) {
                if (!region.conflict) output.addAll(region.left);
                else {
                    if (index >= choices.size()) throw new IOException("还有冲突没有选择");
                    output.addAll(switch (choices.get(index++)) {
                        case 0 -> region.left;
                        case 1 -> region.right;
                        case 2 -> region.base;
                        default -> throw new IOException("无效的冲突选择");
                    });
                }
            }
            return String.join("\n", output);
        }
    }

    static Plan plan(String baseText, String leftText, String rightText, BooleanSupplier cancelled) throws IOException {
        List<String> base = baseText.isEmpty() ? List.of() : Arrays.asList(baseText.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1));
        List<Edit> left = edits(TextDiff.compare(baseText, leftText, false, false, cancelled));
        List<Edit> right = edits(TextDiff.compare(baseText, rightText, false, false, cancelled));
        List<Region> regions = new ArrayList<>();
        int l = 0, r = 0, at = 0;
        while (l < left.size() || r < right.size()) {
            TextDiff.check(cancelled);
            int start = Math.min(l < left.size() ? left.get(l).from : Integer.MAX_VALUE,
                    r < right.size() ? right.get(r).from : Integer.MAX_VALUE);
            if (at < start) regions.add(resolved(at, base.subList(at, start)));
            int firstL = l, firstR = r, end = start;
            do {
                if (l < left.size() && touches(left.get(l), start, end)) end = Math.max(end, left.get(l++).to);
                if (r < right.size() && touches(right.get(r), start, end)) end = Math.max(end, right.get(r++).to);
            } while (l < left.size() && touches(left.get(l), start, end)
                    || r < right.size() && touches(right.get(r), start, end));
            List<String> original = base.subList(start, end);
            List<String> a = apply(base, start, end, left.subList(firstL, l));
            List<String> b = apply(base, start, end, right.subList(firstR, r));
            if (a.equals(b) || b.equals(original)) regions.add(resolved(start, a));
            else if (a.equals(original)) regions.add(resolved(start, b));
            else regions.add(new Region(start + 1, List.copyOf(original), a, b, true));
            at = end;
        }
        if (at < base.size()) regions.add(resolved(at, base.subList(at, base.size())));
        return new Plan(List.copyOf(regions));
    }

    private static boolean touches(Edit edit, int start, int end) {
        return edit.from == start || edit.from < end;
    }

    private static Region resolved(int start, List<String> lines) {
        return new Region(start + 1, List.of(), List.copyOf(lines), List.of(), false);
    }

    private static List<String> apply(List<String> base, int start, int end, List<Edit> edits) {
        List<String> lines = new ArrayList<>();
        int at = start;
        for (Edit edit : edits) {
            lines.addAll(base.subList(at, edit.from));
            lines.addAll(edit.replacement);
            at = edit.to;
        }
        lines.addAll(base.subList(at, end));
        return List.copyOf(lines);
    }

    private static List<Edit> edits(List<TextDiff.Row> rows) {
        List<Edit> result = new ArrayList<>();
        int base = 0;
        for (int index = 0; index < rows.size();) {
            TextDiff.Row row = rows.get(index);
            if (row.kind() == TextDiff.Kind.SAME) { base++; index++; continue; }
            int start = base;
            List<String> replacement = new ArrayList<>();
            while (index < rows.size() && rows.get(index).kind() != TextDiff.Kind.SAME) {
                row = rows.get(index++);
                if (row.leftLine() > 0) base++;
                if (row.rightLine() > 0) replacement.add(row.right());
            }
            result.add(new Edit(start, base, List.copyOf(replacement)));
        }
        return result;
    }

    private ThreeWayMerge() { }
}
