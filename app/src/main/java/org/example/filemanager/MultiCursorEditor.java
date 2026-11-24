package org.example.filemanager;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import io.github.rosemoe.sora.event.ContentChangeEvent;
import io.github.rosemoe.sora.event.SelectionChangeEvent;
import io.github.rosemoe.sora.widget.CodeEditor;

import java.util.ArrayList;
import java.util.List;

/** Secondary selections mirror input in a single undoable batch. Sora owns the primary caret. */
final class MultiCursorEditor extends CodeEditor {
    record Range(int start, int end) { }
    private final Paint marker = new Paint();
    private List<Range> ranges = List.of();
    private List<Range> composing;
    private int primary;
    private boolean applying;
    private Runnable changed;

    MultiCursorEditor(Context context) {
        super(context);
        subscribeEvent(ContentChangeEvent.class, (e, unsubscribe) -> { if (!applying) clearCursors(); });
        subscribeEvent(SelectionChangeEvent.class, (e, unsubscribe) -> {
            if (!applying && hasCursors() && (ranges.get(primary).start != e.getLeft().index
                    || ranges.get(primary).end != e.getRight().index)) clearCursors();
        });
    }

    boolean hasCursors() { return ranges.size() > 1; }
    int cursorCount() { return ranges.size(); }
    boolean applyingCursorEdit() { return applying; }
    void onCursorEdit(Runnable action) { changed = action; }

    void setCursors(List<Range> selections, int anchor) {
        if (selections.size() < 2) return;
        int end = -1;
        for (Range range : selections) {
            if (end >= range.start || range.start > range.end || range.end > getText().length()) return;
            end = range.end;
        }
        primary = anchor;
        ranges = List.copyOf(selections);
        composing = null;
        applying = true;
        try { selectPrimary(); } finally { applying = false; }
        invalidate();
    }

    void clearCursors() {
        if (!hasCursors()) return;
        ranges = List.of(); composing = null;
        invalidate();
    }

    @Override public void commitText(CharSequence text, boolean indent, boolean completeSymbol) {
        if (hasCursors() && isEditable()) applyText(text, 1, false);
        else super.commitText(text, indent, completeSymbol);
    }

    @Override public void deleteText() {
        if (hasCursors() && isEditable()) erase(1, 0, true, true);
        else super.deleteText();
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (hasCursors() && isEditable() && keyCode == KeyEvent.KEYCODE_FORWARD_DEL) {
            erase(0, 1, true, true); return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override public InputConnection onCreateInputConnection(EditorInfo info) {
        InputConnection normal = super.onCreateInputConnection(info);
        if (normal == null) return null;
        return new InputConnectionWrapper(normal, false) {
            private boolean active() { return hasCursors() && isEditable(); }
            @Override public boolean commitText(CharSequence text, int position) {
                return active() ? applyText(text, position, false) : super.commitText(text, position);
            }
            @Override public boolean setComposingText(CharSequence text, int position) {
                return active() ? applyText(text, position, true) : super.setComposingText(text, position);
            }
            @Override public boolean setComposingRegion(int start, int end) {
                if (!active()) return super.setComposingRegion(start, end);
                Range main = ranges.get(primary);
                List<Range> all = new ArrayList<>();
                for (Range range : ranges) {
                    int left = range.start + start - main.start, right = range.start + end - main.start;
                    if (left < 0 || right < left || right > getText().length() || (!all.isEmpty() && left < all.get(all.size() - 1).end))
                        return false;
                    all.add(new Range(left, right));
                }
                composing = List.copyOf(all); return true;
            }
            @Override public boolean finishComposingText() {
                composing = null;
                return super.finishComposingText();
            }
            @Override public boolean deleteSurroundingText(int before, int after) {
                return active() ? erase(before, after, false, false) : super.deleteSurroundingText(before, after);
            }
            @Override public boolean deleteSurroundingTextInCodePoints(int before, int after) {
                return active() ? erase(before, after, true, false) : super.deleteSurroundingTextInCodePoints(before, after);
            }
        };
    }

    private boolean applyText(CharSequence text, int cursorPosition, boolean keepComposing) {
        List<Range> targets = composing == null ? ranges : composing;
        return replace(targets, text.toString(), cursorPosition, keepComposing);
    }

    private boolean erase(int before, int after, boolean codePoints, boolean selectionFirst) {
        List<Range> targets = new ArrayList<>(ranges.size());
        var text = getText();
        for (Range caret : ranges) {
            if (selectionFirst && caret.start != caret.end) { targets.add(caret); continue; }
            if (caret.start != caret.end) return false;
            int first = before > caret.start ? 0 : caret.start - Math.max(0, before);
            int last = after > text.length() - caret.end ? text.length() : caret.end + Math.max(0, after);
            if (codePoints) {
                first = caret.start; last = caret.end;
                for (int i = 0; i < before && first > 0; i++) first -= Character.charCount(Character.codePointBefore(text, first));
                for (int i = 0; i < after && last < text.length(); i++) last += Character.charCount(Character.codePointAt(text, last));
            }
            targets.add(new Range(first, last));
        }
        return replace(targets, "", 1, false);
    }

    private boolean replace(List<Range> targets, String value, int position, boolean keepComposing) {
        var text = getText();
        List<Range> next = new ArrayList<>(targets.size());
        List<Range> pending = new ArrayList<>(targets.size());
        int delta = 0, previous = -1;
        for (Range target : targets) {
            if (target.start < previous || target.end > text.length()) return false;
            previous = target.end;
            int start = target.start + delta, end = start + value.length();
            long rawCaret = position > 0 ? (long) end + position - 1 : (long) start + position;
            // Keep relative cursor movement inside its inserted text so secondary selections never cross.
            int caret = (int) Math.max(start, Math.min((long) end, rawCaret));
            next.add(new Range(caret, caret));
            pending.add(new Range(start, end));
            delta += value.length() - (target.end - target.start);
        }
        for (int i = 1; i < next.size(); i++) if (next.get(i).start <= next.get(i - 1).start) return false;
        applying = true;
        text.beginBatchEdit();
        try {
            for (int i = targets.size() - 1; i >= 0; i--) {
                Range range = targets.get(i);
                if (range.start == range.end && value.isEmpty()) continue;
                var first = text.getIndexer().getCharPosition(range.start);
                var last = text.getIndexer().getCharPosition(range.end);
                text.replace(first.line, first.column, last.line, last.column, value);
            }
            ranges = List.copyOf(next);
            composing = keepComposing ? List.copyOf(pending) : null;
            selectPrimary();
        } finally {
            text.endBatchEdit();
            applying = false;
        }
        if (changed != null) changed.run();
        invalidate();
        return true;
    }

    private void selectPrimary() {
        Range main = ranges.get(primary);
        var cursor = getText().getIndexer();
        var first = cursor.getCharPosition(main.start);
        var last = cursor.getCharPosition(main.end);
        setSelectionRegion(first.line, first.column, last.line, last.column);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!hasCursors()) return;
        float floor = measureTextRegionOffset() - getOffsetX();
        float width = Ui.dp(getContext(), 2);
        var indexer = getText().getIndexer();
        for (int i = 0; i < ranges.size(); i++) {
            if (i == primary) continue;
            Range range = ranges.get(i);
            if (range.end > getText().length()) continue;
            var start = indexer.getCharPosition(range.start);
            var end = indexer.getCharPosition(range.end);
            float bottom = getCharOffsetY(start.line, start.column);
            if (bottom <= 0 || bottom - getRowHeight() >= getHeight()) continue;
            float x = getCharOffsetX(start.line, start.column);
            float right = range.start == range.end ? x + width : getCharOffsetX(end.line, end.column);
            marker.setColor(Ui.ACCENT);
            marker.setAlpha(range.start == range.end ? 240 : 95);
            canvas.drawRect(Math.max(floor, Math.min(x, right)), bottom - getRowHeight(), Math.max(x, right), bottom, marker);
        }
    }
}
