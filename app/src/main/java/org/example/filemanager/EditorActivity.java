package org.example.filemanager;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import androidx.lifecycle.ViewModelProvider;
import org.json.*;
import android.view.View;
import android.widget.*;
import io.github.rosemoe.sora.widget.CodeEditor;
import io.github.rosemoe.sora.widget.EditorSearcher;
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme;
import io.github.rosemoe.sora.event.ContentChangeEvent;
import io.github.rosemoe.sora.event.PublishSearchResultEvent;
import io.github.rosemoe.sora.event.SelectionChangeEvent;
import io.github.rosemoe.sora.text.Content;
import java.io.*;
import java.nio.charset.*;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Pattern;

public class EditorActivity extends LabActivity {
    static final int LIMIT = 2 * 1024 * 1024, PAGE = 256 * 1024, STRUCTURED_LIMIT = 32 * 1024 * 1024;
    private FileStore store;
    private FileStore.Node source;
    private MultiCursorEditor editor;
    private TextView info;
    private Charset charset = StandardCharsets.UTF_8;
    private byte[] bom = new byte[0];
    private boolean dirty, loading, loaded, saving, paged, reading, readOnly, reviewOnly;
    private boolean transcodedPages, uncertainEncoding, controlCharacters, denseControls;
    private CodeLanguage.Kind kind = CodeLanguage.Kind.GENERIC;
    private MtsxSyntax customSyntax;
    private boolean kept;
    private String outputLineEnding;
    private int lineAnchor = -1;
    private View keepButton;
    private TextView position, matchCount, regexToggle, caseToggle;
    private LinearLayout searchBar, replaceRow, symbols;
    private View undoButton, redoButton;
    private final List<View> bodyControls = new ArrayList<>();
    private EditText findInput, replaceInput;
    private boolean regexSearch, caseSensitive = true, imeVisible = android.os.Build.VERSION.SDK_INT < 30;
    private int pageSearchGeneration;
    private boolean pageSearching;
    private android.graphics.Typeface defaultTypeface;
    private volatile long savedSize = -1;
    private long offset, length;
    private File archive, preview, pageFile;
    private PagedTextFile.Page currentPage;
    private final ArrayDeque<Long> previousPages = new ArrayDeque<>();
    private String entry, mode;
    private int loadGeneration;
    private EditorSession session;
    private EditorDrafts.State pendingDraft;
    private String blockedSave;
    private Runnable afterSave;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long checkpointStarted;
    private long lastExitBack;
    private boolean retentionAsked, externalCheck;
    private String acknowledgedExternal;
    private final Runnable draftCheckpoint = () -> checkpoint(true);
    private record Loaded(TextCodec.Decoded decoded, Content content, boolean paged) {}
    private record Opening(FileStore.Node source, String document, EditorDrafts.State draft, boolean automatic,
                           IOException unavailable) {}
    private boolean classEdit() { return "smali-class".equals(mode); }
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (saved != null && saved.getBoolean("savedForCaller")) getIntent().putExtra("savedForCaller", true);
        if (getIntent().getBooleanExtra("savedForCaller", false)) setResult(RESULT_OK);
        if (saved != null && saved.containsKey("sourceRevision"))
            getIntent().putExtra("sourceRevision", saved.getString("sourceRevision"));
        store = new FileStore(this);
        session = new ViewModelProvider(this).get(EditorSession.class);
        kept = getIntent().getBooleanExtra("kept", false);
        session.sourceSaved |= getIntent().getBooleanExtra("savedForCaller", false);
        try { session.initialize(getApplicationContext(), saved == null ? null : saved.getString("editorSession")); }
        catch (IOException error) { Tasks.showError(this, error); finish(); return; }
        entry = getIntent().getStringExtra("archiveEntry");
        mode = getIntent().getStringExtra("mode");
        readOnly = getIntent().getBooleanExtra("readOnly", false);
        if (getIntent().hasExtra("archivePath")) archive = new File(getIntent().getStringExtra("archivePath"));
        if (getIntent().hasExtra("preview")) preview = new File(getIntent().getStringExtra("preview"));
        LinearLayout root = Ui.column(this);
        String name = getIntent().getStringExtra("displayName");
        if (name == null) name = getIntent().getStringExtra("name");
        LinearLayout top = Ui.toolbar(this, name == null ? "编辑器" : name, this::leave);
        keepButton = Ui.icon(this, R.drawable.ic_bookmark, "保留文件", Color.WHITE, this::keepFile);
        top.addView(keepButton, lp(48, 52));
        top.addView(Ui.icon(this, R.drawable.ic_search, "查找与替换", Color.WHITE, this::openSearch), lp(48, 52));
        View saveButton = Ui.icon(this, R.drawable.ic_save, "保存", Color.WHITE, this::save);
        if (readOnly) saveButton.setVisibility(View.GONE);
        top.addView(saveButton, lp(48, 52));
        top.addView(Ui.icon(this, R.drawable.ic_more, "编辑器菜单", Color.WHITE, this::menu), lp(48, 52));
        root.addView(top, lp(-1, 52));
        root.addView(buildSearchBar());
        editor = new MultiCursorEditor(this); editor.setTabWidth(4);
        editor.subscribeEvent(io.github.rosemoe.sora.event.LongPressEvent.class, (event, unsubscribe) -> {
            if (!loaded || event.getMotionRegion() != io.github.rosemoe.sora.event.EditorMotionEvent.REGION_LINE_NUMBER) return;
            event.intercept(); int line = event.getLine();
            if (lineAnchor < 0) lineAnchor = line;
            int from = Math.min(lineAnchor, line), to = Math.max(lineAnchor, line);
            editor.setSelectionRegion(from, 0, to, editor.getText().getColumnCount(to));
        });
        editor.setRenderFunctionCharacters(true);
        kind = classEdit() ? CodeLanguage.Kind.SMALI : "xml".equals(mode) ? CodeLanguage.Kind.MARKUP
                : mode != null && mode.startsWith("arsc") ? CodeLanguage.Kind.JSON
                : CodeLanguage.kindFor(entry != null ? entry : name);
        applyLanguage();
        editor.setEditable(false);
        applyDisplayPreferences();
        onPaletteChanged();
        root.addView(editor, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(Ui.line(this));
        LinearLayout bottom = Ui.row(this); ThemeMaterial.paint(bottom,ThemeMaterial.Surface.FOOTER);
        undoButton = Ui.icon(this, R.drawable.ic_back, "撤销 / 上一页", Ui.TEXT, () -> {
            if (paged) changePage(false); else undo();
        }); bottom.addView(undoButton, lp(48, 48));
        redoButton = Ui.icon(this, R.drawable.ic_back, "重做 / 下一页", Ui.TEXT, () -> {
            if (paged) changePage(true); else redo();
        }); redoButton.setRotation(180); bottom.addView(redoButton, lp(48, 48));
        info = Ui.single(this, "读取中…", 10, Ui.MUTED);
        bottom.addView(info, new LinearLayout.LayoutParams(0, -1, 1));
        position = Ui.single(this, "", 11, Ui.MUTED); position.setGravity(android.view.Gravity.CENTER);
        position.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 12), 0); position.setContentDescription("光标位置 · 跳转到行");
        position.setMinimumWidth(Ui.dp(this, 48)); position.setBackground(Ui.ripple(Color.TRANSPARENT)); position.setOnClickListener(v -> gotoLine());
        bottom.addView(position, new LinearLayout.LayoutParams(-2, -1)); root.addView(bottom, lp(-1, 48));
        root.addView(buildSymbolBar(), lp(-1, 48));
        Ui.install(this, root);
        root.getViewTreeObserver().addOnGlobalLayoutListener(this::updateSymbolBar);
        updateEditingControls();
        editor.subscribeEvent(ContentChangeEvent.class, (e, unsubscribe) -> {
            if (!editor.applyingCursorEdit()) editorChanged();
        });
        editor.onCursorEdit(this::editorChanged);
        editor.subscribeEvent(SelectionChangeEvent.class, (e, unsubscribe) -> { updatePosition(); updateMatchCount(); });
        editor.subscribeEvent(PublishSearchResultEvent.class, (e, unsubscribe) -> updateMatchCount());
        openEditor(saved != null);
    }
    private void editorChanged() {
        if (loading) return;
        session.contentRevision++;
        if (loaded) {
            dirty = true; details();
            // Public Sora APIs can bypass editable. Keep the latest body even if the Activity rotates mid-save.
            if (saving || session.saving) checkpoint(true); else scheduleCheckpoint();
        }
    }
    private void openEditor(boolean recreated) {
        if (isDestroyed() || isFinishing()) return;
        if (session.saving) { info.setText("保存中…"); handler.postDelayed(() -> openEditor(recreated), 50); return; }
        runEditor(() -> {
            EditorDrafts.State known = session.memory;
            if (known == null && recreated) known = EditorDrafts.read(this, session.drafts.id);
            FileStore.Node node;
            try { node = store.fromIntent(getIntent()); }
            catch (IOException | RuntimeException unavailable) {
                if (known == null || !known.metadata().optBoolean("dirty", true)) throw unavailable;
                IOException reason = unavailable instanceof IOException io ? io : new IOException(unavailable.getMessage(), unavailable);
                return new Opening(null, known.document(), known, true, reason);
            }
            String document = String.join("\u0000", documentKey(node), Objects.toString(entry, ""),
                    Objects.toString(mode, ""), Objects.toString(getIntent().getStringExtra("dexEntry"), ""),
                    Objects.toString(getIntent().getStringExtra("descriptor"), ""));
            EditorDrafts.State state = known;
            EditorDocuments.Entry retained = EditorDocuments.find(this, document);
            if (state == null && retained != null) { state = EditorDocuments.state(retained.id()); if (state == null) state = EditorDrafts.read(this, retained.id()); }
            boolean automatic = state != null;
            if (state == null && !readOnly) state = EditorDrafts.latest(this, document);
            if (state != null && !state.document().equals(document)) state = null;
            return new Opening(node, document, state, automatic, null);
        }, opened -> {
            if (opened.unavailable() != null) { reviewDraft(opened.draft(), opened.unavailable()); return; }
            source = opened.source(); session.document = opened.document();
            kept |= EditorDocuments.find(this, session.document) != null;
            keepButton.setVisibility(kept ? View.GONE : View.VISIBLE);
            if (opened.draft() == null) { load(null); return; }
            if (opened.automatic()) { restoreDraft(opened.draft()); return; }
            MaterialDialogs.builder(this).setTitle("发现未保存草稿")
                    .setMessage("恢复上次编辑内容？原文件不会自动改写。")
                    .setPositiveButton("恢复草稿", (d, which) -> restoreDraft(opened.draft()))
                    .setNegativeButton("放弃草稿", (d, which) -> {
                        try { session.restore(getApplicationContext(), opened.draft()); session.discard(); load(null); }
                        catch (IOException error) { Tasks.showError(this, error); }
                    }).setNeutralButton("取消", (d, which) -> finish()).setOnCancelListener(d -> finish()).show();
        }, error -> { info.setText("无法读取 · 草稿仍保留"); Tasks.showError(this, error); });
    }
    private void reviewDraft(EditorDrafts.State state, IOException reason) {
        try { session.restore(getApplicationContext(), state); }
        catch (IOException error) { Tasks.showError(this, error); return; }
        reviewOnly = true;
        blockedSave = "无法访问原文件，草稿只读显示；请从原来源重新打开后再保存";
        JSONObject data = state.metadata();
        charset = Charset.forName(data.optString("charset", "UTF-8"));
        bom = Base64.decode(data.optString("bom"), Base64.DEFAULT);
        paged = data.optBoolean("paged"); offset = data.optLong("start"); length = data.optLong("length");
        dirty = true; reading = false;
        loading = true; editor.setText(state.content().copyTextShallow(true), true, null); loading = false;
        classifyControls(editor.getText().toString());
        loaded = true; updateEditingControls(); details(); updatePosition();
        MaterialDialogs.builder(this).setTitle("原文件暂时无法访问")
                .setMessage(blockedSave + "。\n" + (reason.getMessage() == null ? reason.getClass().getSimpleName() : reason.getMessage()))
                .setPositiveButton("知道了", null).show();
    }
    private static String documentKey(FileStore.Node node) {
        if (node.container != null) return documentKey(node.container) + "!/" + node.entry;
        return node.file != null ? node.file.toPath().toAbsolutePath().normalize().toString() : node.uri.toString();
    }
    private void restoreDraft(EditorDrafts.State state) {
        try {
            session.restore(getApplicationContext(), state); pendingDraft = state;
            session.baseline = state.metadata().optString("baseline");
            load(state.metadata().optBoolean("paged") ? state.metadata().optString("charset", "UTF-8") : null);
        } catch (IOException error) { Tasks.showError(this, error); }
    }
    private <T> void runEditor(Tasks.Job<T> work, Tasks.Done<T> done, Tasks.Done<Exception> failed) {
        session.begin();
        Tasks.IO.execute(() -> {
            try {
                T result = work.run();
                handler.post(() -> { if (!isFinishing() && !isDestroyed()) done.accept(result); });
            } catch (Exception error) {
                handler.post(() -> { if (!isFinishing() && !isDestroyed()) failed.accept(error); });
            } finally { session.end(); }
        });
    }
    private void own(File file) { session.own(file); }
    private String fingerprint() throws IOException { return store.fingerprint(source); }
    private void prepareArchive() throws IOException {
        if (archive == null && entry == null) return;
        if (session.archive != null && session.archive.isFile()) { archive = session.archive; return; }
        File original = archive;
        File copy = File.createTempFile("editor-archive-", "-" + source.name.replaceAll("[^a-zA-Z0-9.]", "_"), getCacheDir());
        own(copy);
        try (InputStream input = store.read(source); OutputStream output = new FileOutputStream(copy)) { FileStore.pump(input, output); }
        if (source.file != null) ArchiveOps.copyPassword(source.file, copy);
        archive = copy; session.archive = copy;
        if (original != null && getIntent().getBooleanExtra("ownedArchive", false)) { own(original); session.ownParent(original.getParentFile()); }
    }
    private LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w < 0 ? w : Ui.dp(this, w), h < 0 ? h : Ui.dp(this, h));
    }
    private void load(String encoding) {
        int token = ++loadGeneration;
        loaded = false; reading = true; updateEditingControls(); info.setText("读取中…");
        runEditor(() -> {
            String observed = fingerprint();
            String workspaceRevision = getIntent().getStringExtra("sourceRevision");
            if (workspaceRevision != null) {
                if (session.baseline != null) workspaceRevision = session.baseline;
                if (!workspaceRevision.equals(observed))
                    throw new IOException("工作副本对应的原文件已变化，请返回工具页刷新后重新打开");
            }
            if (session.baseline == null) session.baseline = observed;
            else if (!session.baseline.equals(observed)) {
                if (pendingDraft != null && !pendingDraft.metadata().optBoolean("dirty", true)) {
                    pendingDraft = null; session.baseline = observed;
                } else blockedSave = "原文件已变化，草稿仅供核对，请重新打开原文件后合并修改";
            }
            prepareArchive();
            if (customSyntax == null) customSyntax = MtsxSyntax.forName(this, entry != null ? entry : source.name);
            if (classEdit()) {
                boolean draftText = pendingDraft != null && pendingDraft.metadata().optBoolean("dirty", true);
                if (pendingDraft != null || preview == null || !preview.isFile()) {
                    File rebuilt = File.createTempFile("editor-class-", ".smali", getCacheDir()); own(rebuilt);
                    if (draftText) Files.write(rebuilt.toPath(), pendingDraft.content().toString().getBytes(StandardCharsets.UTF_8));
                    else ApkTools.disassembleClass(archive, Objects.toString(getIntent().getStringExtra("dexEntry"), ""),
                            getIntent().getStringExtra("descriptor"), rebuilt);
                    preview = rebuilt;
                }
            }
            transcodedPages = false;
            byte[] bytes = readContent();
            if (!observed.equals(fingerprint())) throw new IOException("读取期间原文件已变化，请重新打开");
            boolean structured = mode != null && (mode.startsWith("arsc") || mode.equals("xml") || classEdit());
            if (structured && bytes.length > STRUCTURED_LIMIT) throw new IOException("结构化编辑内容超过 32 MiB");
            boolean large = bytes.length > pagingLimit() && !structured;
            TextCodec.Decoded decoded = TextCodec.decodeForEditor(large ? Arrays.copyOf(bytes, PAGE) : bytes, encoding, large);
            if (large && TextCodec.isStateful(decoded.charset())) {
                File converted = File.createTempFile("stateful-editor-", ".txt", getCacheDir());
                own(converted);
                try {
                    TextCodec.transcode(pageFile, converted, decoded.charset(), StandardCharsets.UTF_8, decoded.bom(), new byte[0]);
                } catch (Exception e) { Files.deleteIfExists(converted.toPath()); throw e; }
                pageFile = converted; length = converted.length(); transcodedPages = true;
            }
            return new Loaded(decoded, large ? null : new Content(decoded.text()), large);
        }, result -> {
            if (token != loadGeneration) return;
            reading = false;
            if (getIntent().hasExtra("sourceRevision") && blockedSave == null)
                getIntent().putExtra("sourceRevision", session.baseline);
            TextCodec.Decoded decoded = result.decoded();
            charset = decoded.charset(); bom = decoded.bom(); paged = result.paged(); uncertainEncoding = decoded.uncertain();
            classifyControls(decoded.text());
            if (paged) {
                offset = 0; previousPages.clear();
                page(pendingDraft == null ? 0 : pendingDraft.metadata().optLong("start")); return;
            }
            loading = true; editor.setText(result.content(), true, null);
            loading = false; loaded = true; dirty = false; completeLoad();
        }, error -> {
            if (token != loadGeneration) return;
            reading = false;
            if (pendingDraft != null) {
                blockedSave = "无法读取原文件，草稿已保留；恢复来源访问权限后再保存";
                loaded = true; completeLoad();
            } else { info.setText("无法解码 · 可重新选择编码"); Tasks.showError(this, error); }
        });
    }
    private byte[] readContent() throws Exception {
            if ("xml".equals(mode)) return ApkTools.xmlText(archive, entry).getBytes(StandardCharsets.UTF_8);
            if ("arsc".equals(mode)) return ApkTools.arscText(archive, entry).getBytes(StandardCharsets.UTF_8);
            if ("arsc-file".equals(mode)) return ApkTools.arscFileText(store.materialize(source)).getBytes(StandardCharsets.UTF_8);
            if (archive != null && entry != null) {
                long size = ArchiveOps.entrySize(archive, entry);
                if (size < 0) throw new IOException("归档条目不存在");
                if (size <= pagingLimit()) return ArchiveOps.bytes(archive, entry, pagingLimit());
                File directory = Files.createTempDirectory(getCacheDir().toPath(), "large-entry-").toFile();
                session.ownParent(directory);
                pageFile = new File(directory, "entry.txt");
                own(pageFile);
                ArchiveOps.extractOne(archive, entry, pageFile);
            } else {
                pageFile = preview != null ? preview : store.materialize(source);
                if (preview == null && (source.file == null || source.root)) own(pageFile);
            }
            length = pageFile.length();
            try (InputStream in = new FileInputStream(pageFile)) {
                return FileStore.readBytes(in, classEdit() ? STRUCTURED_LIMIT + 1 : pagingLimit() + 1);
            }
    }
    private void page(long position) {
        page(position, null);
    }
    private void page(long position, SearchJob.Match selection) {
        if (position >= length && position != 0) {
            if (pendingDraft != null) { blockedSave = "原文件分段已变化，草稿已保留"; completeLoad(); }
            return;
        }
        offset = position; loaded = false; reading = true; updateEditingControls();
        runEditor(() -> {
            PagedTextFile.Page result = PagedTextFile.read(pageFile, position,
                    transcodedPages ? StandardCharsets.UTF_8 : charset, transcodedPages ? new byte[0] : bom, PAGE);
            return new PageContent(result, new Content(result.text()));
        }, result -> {
            reading = false;
            currentPage = result.page();
            classifyControls(currentPage.text());
            loading = true; editor.setText(result.content(), true, null); loading = false; loaded = true; dirty = false;
            completeLoad();
            if (selection != null) {
                selectOffsets(selection.start(), selection.end());
                runSearch();
            }
        }, error -> {
            reading = false;
            if (pendingDraft != null) {
                blockedSave = "原文件分段已变化，草稿已保留；请核对后重新打开原文件";
                loaded = true; completeLoad();
            } else { info.setText("分段读取失败"); Tasks.showError(this, error); }
        });
    }
    private void completeLoad() {
        EditorDrafts.State state = pendingDraft; pendingDraft = null;
        if (state != null) {
            JSONObject data = state.metadata();
            charset = Charset.forName(data.optString("charset", "UTF-8"));
            bom = Base64.decode(data.optString("bom"), Base64.DEFAULT);
            dirty = data.optBoolean("dirty", true); paged = data.optBoolean("paged");
            outputLineEnding = data.has("outputLineEnding") ? data.optString("outputLineEnding") : null;
            uncertainEncoding = data.optBoolean("uncertain"); transcodedPages = data.optBoolean("transcoded");
            if (dirty) { loading = true; editor.setText(state.content().copyTextShallow(true), true, null); loading = false; }
            if (paged) {
                offset = data.optLong("start");
                currentPage = new PagedTextFile.Page(offset, data.optLong("end"), editor.getText().toString());
                previousPages.clear(); JSONArray previous = data.optJSONArray("previous");
                if (previous != null) for (int i = 0; i < previous.length(); i++) previousPages.addLast(previous.optLong(i));
            }
            classifyControls(editor.getText().toString());
            session.memory = state;
            handler.post(() -> {
                if (isDestroyed() || isFinishing()) return;
                Content content = editor.getText();
                int left = Math.max(0, Math.min(data.optInt("leftLine"), content.getLineCount() - 1));
                int right = Math.max(left, Math.min(data.optInt("rightLine", left), content.getLineCount() - 1));
                int leftColumn = Math.max(0, Math.min(data.optInt("leftColumn"), content.getColumnCount(left)));
                int rightColumn = Math.max(left == right ? leftColumn : 0,
                        Math.min(data.optInt("rightColumn", leftColumn), content.getColumnCount(right)));
                editor.setSelectionRegion(left, leftColumn, right, rightColumn);
                editor.getScroller().startScroll(editor.getOffsetX(), editor.getOffsetY(),
                        data.optInt("scrollX") - editor.getOffsetX(), data.optInt("scrollY") - editor.getOffsetY(), 0);
                editor.invalidate();
            });
        }
        loaded = true; updateEditingControls(); details();
        io.github.rosemoe.sora.text.UndoManager retainedUndo = EditorDocuments.undo(session.drafts.id, session.baseline);
        if (kept && retainedUndo != null && blockedSave == null) editor.getText().setUndoManager(retainedUndo);
        editor.getProps().disableTextExtracting = smoothMode();
        if (!kept && display().getInt("retention",0)==1 && !readOnly && !reviewOnly) keepFile();
        updatePosition(); updateSymbolBar(); runSearch();
        int hitLine = getIntent().getIntExtra("searchHitLine", 0);
        if (hitLine > 0) {
            getIntent().removeExtra("searchHitLine");
            if (!dirty) handler.post(() -> locateSearchLine(hitLine));
        }
        if (blockedSave != null) MaterialDialogs.builder(this).setTitle("草稿需要核对")
                .setMessage(blockedSave + "。不会自动覆盖原文件。")
                .setPositiveButton("知道了", null).show();
    }
    private void locateSearchLine(int line) {
        if (!loaded || dirty || isFinishing() || isDestroyed()) return;
        if (!paged) {
            int target = Math.min(line - 1, editor.getLineCount() - 1);
            editor.setSelection(target, 0); editor.ensurePositionVisible(target, 0);
            return;
        }
        File file = pageFile;
        Charset encoding = transcodedPages ? StandardCharsets.UTF_8 : charset;
        byte[] marker = transcodedPages ? new byte[0] : bom.clone();
        long origin = currentPage.start();
        runEditor(() -> {
            int current = 1;
            boolean afterCr = false;
            for (long position = 0; position < file.length();) {
                Tasks.Progress.check();
                var candidate = PagedTextFile.read(file, position, encoding, marker, PAGE);
                if (candidate.end() <= position) break;
                String text = candidate.text();
                if (current == line) {
                    int start = afterCr && !text.isEmpty() && text.charAt(0) == '\n' ? 1 : 0;
                    return new PageMatch(position, new SearchJob.Match(start, start));
                }
                for (int i = 0; i < text.length(); i++) {
                    char c = text.charAt(i);
                    if (c == '\n' && afterCr) { afterCr = false; continue; }
                    afterCr = c == '\r';
                    if ((c == '\r' || c == '\n') && ++current == line) {
                        int next = i + 1;
                        if (afterCr && next < text.length() && text.charAt(next) == '\n') next++;
                        if (next == text.length() && candidate.end() < file.length()) break;
                        return new PageMatch(position, new SearchJob.Match(next, next));
                    }
                }
                position = candidate.end();
            }
            return null;
        }, match -> {
            if (match != null && loaded && !dirty && currentPage.start() == origin)
                page(match.offset(), match.match());
            else if (match == null) Tasks.toast(this, "文件已变化或该行已不存在");
        }, error -> Tasks.showError(this, error));
    }
    private void scheduleCheckpoint() {
        if (readOnly || preview != null && !classEdit() || source == null) return;
        long now = SystemClock.uptimeMillis();
        if (checkpointStarted == 0) checkpointStarted = now;
        handler.removeCallbacks(draftCheckpoint);
        handler.postAtTime(draftCheckpoint, Math.min(now + 500, checkpointStarted + 2000));
    }
    private void checkpoint(boolean persist) {
        handler.removeCallbacks(draftCheckpoint); checkpointStarted = 0;
        if (!loaded || reading || source == null || readOnly
                || preview != null && !classEdit() || session.document == null) return;
        try {
            JSONObject data = new JSONObject().put("time", System.currentTimeMillis()).put("dirty", dirty)
                    .put("baseline", session.baseline).put("charset", charset.name())
                    .put("bom", Base64.encodeToString(bom, Base64.NO_WRAP)).put("paged", paged)
                    .put("start", paged ? currentPage.start() : 0).put("end", paged ? currentPage.end() : 0)
                    .put("length", length).put("uncertain", uncertainEncoding).put("transcoded", transcodedPages)
                    .put("previous", new JSONArray(previousPages)).put("leftLine", editor.getCursor().getLeftLine())
                    .put("leftColumn", editor.getCursor().getLeftColumn()).put("rightLine", editor.getCursor().getRightLine())
                    .put("rightColumn", editor.getCursor().getRightColumn()).put("scrollX", editor.getOffsetX()).put("scrollY", editor.getOffsetY());
            if (outputLineEnding != null) data.put("outputLineEnding", outputLineEnding);
            EditorDrafts.State state = dirty || kept ? session.drafts.remember(session.document, data, editor.getText().copyTextShallow(true))
                    : new EditorDrafts.State(session.drafts.id, session.document, 0, data, new Content(""));
            session.memory = state;
            if (kept) EditorDocuments.remember(this, state, getIntent(), editor.getText().getUndoManager(), session.baseline);
            if (persist && (dirty || kept)) {
                session.drafts.persist(state);
                EditorDrafts.WORKER.execute(() -> {
                    IOException failure = session.drafts.error();
                    if (failure != null) handler.post(() -> {
                        if (!isDestroyed() && !isFinishing()) { details(); Tasks.toast(this, "草稿写入失败，原文件未改写：" + failure.getMessage()); }
                    });
                });
            }
        } catch (JSONException error) { Tasks.showError(this, error); }
    }
    private record PageContent(PagedTextFile.Page page, Content content) {}
    private void changePage(boolean next) {
        if (!loaded) return;
        if (saving) { afterSave = () -> changePage(next); return; }
        if (blockedSave != null) { Tasks.toast(this, blockedSave); return; }
        Runnable navigate = () -> {
            if (next && currentPage.end() < length) {
                previousPages.push(offset); page(currentPage.end());
            } else if (!next && !previousPages.isEmpty()) page(previousPages.pop());
        };
        if (dirty) MaterialDialogs.builder(this).setTitle("当前分段尚未保存")
                .setNegativeButton("继续编辑", null).setPositiveButton("放弃修改", (d, w) -> {
                    if (saving || session.saving) { afterSave = () -> changePage(next); return; }
                    dirty = false; handler.removeCallbacks(draftCheckpoint); session.discard(); navigate.run();
                })
                .setNeutralButton("保存并翻页", (d, w) -> save(navigate, () -> changePage(next))).show();
        else navigate.run();
    }
    private void details() {
        info.setText(charset.name() + (uncertainEncoding ? " · 推测" : "") + (controlCharacters ? (denseControls ? " · 控制字符·简化显示" : " · 控制字符") : "") + " · " + (paged ? "分段 " + FileStore.size(offset) + "/" + FileStore.size(length)
                + (dirty ? " · 未保存" : "") + (readOnly ? " · 只读" : "") :
                editor.getLineCount() + " 行" + (mode == null && entry == null && preview == null && length > 0 ? " · " + FileStore.size(length) : "")
                + (dirty ? " · 未保存" : (readOnly || preview != null) && !classEdit() ? " · 只读" : ""))
                + (blockedSave == null ? "" : " · 来源待核对")
                + (session.drafts.error() == null ? "" : " · 草稿写入失败"));
    }
    /**
     * Sora measures each control-character badge against its whole line, so dense binary pages cost
     * hundreds of milliseconds per frame. Such pages draw control bytes as plain glyphs instead.
     */
    private void classifyControls(String text) {
        int total = 0, line = 0, worstLine = 0;
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            if (value == '\n') { worstLine = Math.max(worstLine, line); line = 0; }
            else if (value < 32 && value != '\r' && value != '\t') { total++; line++; }
        }
        worstLine = Math.max(worstLine, line);
        controlCharacters = total > 0;
        boolean dense = total > 8192 || worstLine > 512;
        if (dense && !denseControls) Tasks.toast(this, "控制字符过多，已改为简化显示以保持流畅");
        denseControls = dense;
        editor.setRenderFunctionCharacters(!dense && display().getBoolean("special", true));
        editor.getProps().showBidiDirectionIndicator = display().getBoolean("special", true);
        applyLanguage();
        applyWordwrap();
    }
    /** Dense binary pages get plain styling: regex highlighting of control bytes is noise and costs time. */
    private void applyLanguage() {
        boolean highlight = display().getBoolean("highlight", true), tabs = display().getBoolean("tabs", false);
        if (!denseControls && highlight && customSyntax != null) {
            if (!(editor.getEditorLanguage() instanceof MtsxLanguage current) || current.syntax != customSyntax) {
                MtsxLanguage language = new MtsxLanguage(customSyntax); editor.setEditorLanguage(language); language.colors(editor.getColorScheme());
            } ((MtsxLanguage)editor.getEditorLanguage()).tabs=tabs; return;
        }
        CodeLanguage.Kind effective = denseControls || !highlight ? CodeLanguage.Kind.PLAIN : kind;
        if (!(editor.getEditorLanguage() instanceof CodeLanguage current) || current.kind != effective) editor.setEditorLanguage(new CodeLanguage(effective));
        ((CodeLanguage)editor.getEditorLanguage()).tabs=tabs;
    }
    private void save() { save(null); }
    private void save(Runnable done) { save(done, this::leave); }
    private void save(Runnable done, Runnable changed) {
        if (reviewOnly) { Tasks.toast(this, blockedSave); return; }
        if (!loaded || reading || ((readOnly || preview != null) && !classEdit()) || saving || session.saving) {
            Tasks.toast(this, ((readOnly || preview != null) && !classEdit()) ? "当前为只读预览" : "等待读写完成"); return;
        }
        checkpoint(true);
        long revision = session.contentRevision;
        String content = editor.getText().toString();
        String text = outputLineEnding == null ? content : content.replace("\r\n", "\n").replace('\r', '\n').replace("\n", outputLineEnding);
        Charset current = charset; byte[] mark = bom;
        saving = true; session.saving = true; updateEditingControls(); info.setText("保存中…");
        session.begin();
        Tasks.IO.execute(() -> {
            String baseline = null; Exception failure = null;
            try {
                if (blockedSave != null) throw new IOException(blockedSave);
                store.checkWritable(source);
                if (!Objects.equals(session.baseline, fingerprint()))
                    throw new IOException("原文件已在外部改变，草稿已保留；请重新打开核对后再保存");
                EditorBackups.beforeSave(store, source);
                baseline = writeContent(text, current, mark);
            } catch (Exception error) { failure = error; }
            String published = baseline; Exception error = failure; long written = savedSize;
            // Unlike ordinary load callbacks, finalization must survive onDestroy's handler cleanup.
            // The recreated Activity waits on session.saving until baseline AND draft metadata agree.
            new Handler(Looper.getMainLooper()).post(() -> completeSave(revision, published, written, error, done, changed));
        });
    }
    private void completeSave(long revision, String baseline, long written, Exception error, Runnable done, Runnable changed) {
        try {
            boolean clean = false;
            if (error == null) {
                getIntent().putExtra("savedForCaller", true); setResult(RESULT_OK);
                if (written >= 0) length = written;
                try { clean = session.saved(revision, baseline, paged ? length : written, paged ? currentPage : null); }
                catch (JSONException failure) { error = failure; }
            }
            saving = false; session.saving = false;
            if (isFinishing() || isDestroyed()) return;
            updateEditingControls();
            if (getIntent().hasExtra("sourceRevision")) getIntent().putExtra("sourceRevision", session.baseline);
            if (error != null) {
                afterSave = null;
                dirty = true; details(); checkpoint(true); Tasks.showError(this, error); return;
            }
            dirty = !clean; details(); checkpoint(!clean);
            Tasks.toast(this, clean ? "已保存" : "已保存快照；当前修改尚未保存");
            Runnable queued = afterSave; afterSave = null;
            if (clean) { if (done != null) done.run(); }
            else if (done != null && queued == null) changed.run();
            // These are leave/changePage entry points, not raw finish/page actions: they recheck dirty.
            if (queued != null && !isFinishing()) queued.run();
        } finally { session.end(); }
    }
    private String writeContent(String text, Charset current, byte[] mark) throws Exception {
        savedSize = -1;
        String revision = null;
        if (paged) {
            File tmp = File.createTempFile("large-editor-", ".tmp", getCacheDir());
            File encoded = null;
            try {
                PagedTextFile.Page result = PagedTextFile.write(pageFile, tmp, currentPage, text,
                        transcodedPages ? StandardCharsets.UTF_8 : current, transcodedPages ? new byte[0] : mark);
                File payload = tmp;
                if (transcodedPages) {
                    encoded = File.createTempFile("stateful-save-", ".tmp", getCacheDir());
                    TextCodec.transcode(tmp, encoded, StandardCharsets.UTF_8, current, new byte[0], mark);
                    payload = encoded;
                }
                if (archive != null && entry != null) {
                    ArchiveOps.rewrite(archive, null, null, Map.of(entry, payload)); revision = store.write(source, archive, session.baseline);
                } else revision = store.write(source, payload, session.baseline);
                if (!pageFile.equals(source.file) || archive != null) FileStore.replace(tmp, pageFile);
                currentPage = result; length = pageFile.length();
            } finally {
                Files.deleteIfExists(tmp.toPath());
                if (encoded != null) Files.deleteIfExists(encoded.toPath());
            }
            return revision;
        } else if (classEdit()) {
            ApkTools.saveDexClass(archive, getIntent().getStringExtra("dexEntry"),
                    getIntent().getStringExtra("descriptor"), text, getCacheDir());
            Files.write(preview.toPath(), text.getBytes(StandardCharsets.UTF_8));
        } else if ("xml".equals(mode)) ApkTools.saveXml(archive, entry, text);
        else if ("arsc".equals(mode)) ApkTools.saveArsc(archive, entry, text);
        else if ("arsc-file".equals(mode)) {
            File tmp = File.createTempFile("resources-", ".arsc", getCacheDir());
            try { ApkTools.saveArscFile(tmp, text); return store.write(source, tmp, session.baseline); }
            finally { Files.deleteIfExists(tmp.toPath()); }
        } else {
            byte[] encoded = TextCodec.encode(text, current, mark);
            File tmp = File.createTempFile("editor-", ".tmp", getCacheDir());
            try {
                Files.write(tmp.toPath(), encoded);
                if (archive != null && entry != null) ArchiveOps.rewrite(archive, null, null, Map.of(entry, tmp));
                else { revision = store.write(source, tmp, session.baseline); savedSize = encoded.length; }
            } finally { Files.deleteIfExists(tmp.toPath()); }
        }
        if (archive != null) return store.write(source, archive, session.baseline);
        return revision;
    }
    private View buildSearchBar() {
        searchBar = Ui.column(this); searchBar.setBackgroundColor(Ui.PANEL); searchBar.setVisibility(View.GONE);
        searchBar.setPadding(Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4));
        LinearLayout findRow = Ui.row(this);
        findInput = searchInput("查找"); findInput.setContentDescription("查找内容");
        findInput.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        findInput.setOnEditorActionListener((v, action, event) -> { jumpMatch(true); return true; });
        findRow.addView(findInput, new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1));
        matchCount = Ui.single(this, "", 11, Ui.MUTED); matchCount.setGravity(android.view.Gravity.CENTER);
        findRow.addView(matchCount, lp(56, 48));
        View previous = Ui.icon(this, R.drawable.ic_up, "上一处", Ui.TEXT, () -> jumpMatch(false));
        findRow.addView(previous, lp(48, 48));
        View next = Ui.icon(this, R.drawable.ic_up, "下一处", Ui.TEXT, () -> jumpMatch(true));
        next.setRotation(180); findRow.addView(next, lp(48, 48));
        TextView close = Ui.text(this, "×", 22, Ui.MUTED); close.setGravity(android.view.Gravity.CENTER);
        close.setContentDescription("关闭查找"); close.setBackground(Ui.ripple(Ui.PANEL));
        close.setOnClickListener(v -> closeSearch()); findRow.addView(close, lp(48, 48));
        searchBar.addView(findRow);
        LinearLayout pageActions = Ui.row(this);
        pageActions.setTag("page-search-actions");
        pageActions.addView(searchAction("前面分段", () -> searchPages(false)), new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1));
        pageActions.addView(searchAction("后面分段", () -> searchPages(true)), new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1));
        bodyControls.remove(pageActions.getChildAt(0));
        bodyControls.remove(pageActions.getChildAt(1));
        searchBar.addView(pageActions);
        replaceRow = Ui.row(this);
        replaceInput = searchInput("替换为"); replaceInput.setContentDescription("替换内容");
        replaceRow.addView(replaceInput, new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1));
        regexToggle = toggle(".*", "正则表达式", () -> regexSearch = !regexSearch);
        caseToggle = toggle("Aa", "区分大小写", () -> caseSensitive = !caseSensitive);
        replaceRow.addView(regexToggle, lp(48, 48)); replaceRow.addView(caseToggle, lp(48, 48));
        replaceRow.addView(searchAction("替换", this::replaceOne), lp(-2, 48));
        replaceRow.addView(searchAction("全部替换", this::replaceAll), lp(-2, 48));
        searchBar.addView(replaceRow);
        MainActivity.watch(findInput, this::runSearch);
        View.OnFocusChangeListener focus = (v, has) -> updateSymbolBar();
        findInput.setOnFocusChangeListener(focus); replaceInput.setOnFocusChangeListener(focus);
        updateToggles();
        return searchBar;
    }
    private EditText searchInput(String hint) {
        EditText input = Ui.input(this, hint); input.setTextSize(13);
        input.setBackground(Ui.background(Ui.SURFACE, Ui.dp(this, 4)));
        input.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 10), 0);
        return input;
    }
    private TextView toggle(String label, String description, Runnable flip) {
        TextView toggle = Ui.text(this, label, 13, Ui.MUTED); toggle.setGravity(android.view.Gravity.CENTER);
        toggle.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        toggle.setContentDescription(description);
        toggle.setOnClickListener(v -> { flip.run(); updateToggles(); runSearch(); });
        return toggle;
    }
    private void updateToggles() {
        for (TextView toggle : new TextView[]{regexToggle, caseToggle}) {
            boolean on = toggle == regexToggle ? regexSearch : caseSensitive;
            toggle.setTextColor(on ? Ui.ACCENT : Ui.MUTED); toggle.setSelected(on);
            toggle.setBackground(Ui.background(on ? Ui.TINT : Ui.PANEL, Ui.dp(this, 4)));
        }
    }
    private TextView searchAction(String label, Runnable action) {
        TextView button = Ui.text(this, label, 13, Ui.ACCENT); button.setGravity(android.view.Gravity.CENTER);
        button.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 10), 0); button.setBackground(Ui.ripple(Ui.PANEL));
        button.setMinimumWidth(Ui.dp(this, 48)); button.setMinimumHeight(Ui.dp(this, 48)); button.setFocusable(true);
        button.setOnClickListener(v -> action.run()); bodyControls.add(button);
        return button;
    }
    private void openSearch() {
        if (!loaded) return;
        if (searchBar.getVisibility() == View.VISIBLE) { closeSearch(); return; }
        replaceRow.setVisibility(canReplace() ? View.VISIBLE : View.GONE);
        findInput.setHint(paged ? "查找（当前分段）" : "查找");
        searchBar.findViewWithTag("page-search-actions").setVisibility(paged ? View.VISIBLE : View.GONE);
        io.github.rosemoe.sora.text.Cursor cursor = editor.getCursor();
        if (cursor.isSelected() && cursor.getLeftLine() == cursor.getRightLine() && cursor.getRight() - cursor.getLeft() <= 100)
            findInput.setText(editor.getText().subSequence(cursor.getLeft(), cursor.getRight()));
        searchBar.setVisibility(View.VISIBLE);
        searchBar.animate().cancel(); searchBar.setAlpha(.4f); searchBar.setTranslationY(-Ui.dp(this, 8));
        searchBar.animate().alpha(1f).translationY(0).setDuration(Ui.motionEnabled() ? Ui.MOTION_SHORT : 0)
                .setInterpolator(Ui.EASING).start();
        findInput.requestFocus(); findInput.selectAll();
        ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                .showSoftInput(findInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        runSearch();
    }
    private boolean closeSearch() {
        if (searchBar == null || searchBar.getVisibility() != View.VISIBLE) return false;
        searchBar.animate().cancel(); searchBar.setAlpha(1f); searchBar.setTranslationY(0);
        searchBar.setVisibility(View.GONE); editor.getSearcher().stopSearch(); findInput.setError(null);
        pageSearchGeneration++; pageSearching = false;
        ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                .hideSoftInputFromWindow(findInput.getWindowToken(), 0);
        editor.requestFocus(); updateSymbolBar();
        return true;
    }
    private void runSearch() {
        if (searchBar == null || searchBar.getVisibility() != View.VISIBLE) return;
        String needle = findInput.getText().toString();
        findInput.setError(null);
        if (needle.isEmpty()) { editor.getSearcher().stopSearch(); matchCount.setText(""); return; }
        if (regexSearch) {
            try { Pattern.compile(needle); }
            catch (java.util.regex.PatternSyntaxException error) {
                editor.getSearcher().stopSearch(); findInput.setError("正则错误：" + error.getDescription()); matchCount.setText(""); return;
            }
        }
        editor.getSearcher().search(needle, new EditorSearcher.SearchOptions(regexSearch
                ? EditorSearcher.SearchOptions.TYPE_REGULAR_EXPRESSION : EditorSearcher.SearchOptions.TYPE_NORMAL, !caseSensitive));
        matchCount.setText("…");
    }
    private void updateMatchCount() {
        if (matchCount == null || searchBar.getVisibility() != View.VISIBLE) return;
        EditorSearcher searcher = editor.getSearcher();
        if (!searcher.hasQuery()) { matchCount.setText(""); return; }
        try {
            int total = searcher.getMatchedPositionCount(), current = searcher.getCurrentMatchedPositionIndex();
            matchCount.setText(total == 0 ? "无匹配" : (current < 0 ? "-" : String.valueOf(current + 1)) + "/" + total);
        } catch (RuntimeException pending) { matchCount.setText("…"); }
    }
    private void jumpMatch(boolean forward) {
        EditorSearcher searcher = editor.getSearcher();
        if (!searcher.hasQuery()) { findInput.requestFocus(); return; }
        try { if (!(forward ? searcher.gotoNext() : searcher.gotoPrevious())) Tasks.toast(this, "没有匹配"); }
        catch (RuntimeException pending) { Tasks.toast(this, "正在查找，请稍候"); }
        updateMatchCount();
    }
    private record PageMatch(long offset, SearchJob.Match match) { }
    private void searchPages(boolean forward) {
        if (!paged || !loaded || reading || saving || pageSearching) return;
        if (dirty) { Tasks.toast(this, "请先保存当前分段，再查找其他分段"); return; }
        String query = findInput.getText().toString();
        if (query.isEmpty()) { findInput.requestFocus(); return; }
        boolean regex = regexSearch;
        int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        long origin = currentPage.start(), start = forward ? currentPage.end() : 0;
        File file = pageFile;
        Charset encoding = transcodedPages ? StandardCharsets.UTF_8 : charset;
        byte[] marker = transcodedPages ? new byte[0] : bom.clone();
        int request = ++pageSearchGeneration;
        pageSearching = true;
        matchCount.setText("查找中");
        runEditor(() -> {
            SearchJob.SearchPattern pattern = new SearchJob.SearchPattern(Pattern.compile(regex ? query : Pattern.quote(query), flags));
            long end = forward ? file.length() : origin;
            PageMatch found = null;
            for (long position = start; position < end;) {
                Tasks.Progress.check();
                PagedTextFile.Page candidate = PagedTextFile.read(file, position, encoding, marker, PAGE);
                if (candidate.end() <= position) break;
                SearchJob.Match match = pattern.find(candidate.text());
                if (match != null) {
                    if (forward) return new PageMatch(position, match);
                    found = new PageMatch(position, match);
                }
                position = candidate.end();
            }
            return found;
        }, found -> {
            if (request != pageSearchGeneration) return;
            pageSearching = false;
            updateMatchCount();
            if (dirty || !loaded || currentPage.start() != origin || !query.equals(findInput.getText().toString())) return;
            if (found == null) { Tasks.toast(this, "其他分段没有匹配"); return; }
            if (!forward) previousPages.clear();
            else previousPages.push(origin);
            page(found.offset(), found.match());
        }, error -> {
            if (request != pageSearchGeneration) return;
            pageSearching = false; updateMatchCount(); Tasks.showError(this, error);
        });
    }
    private void selectOffsets(int start, int end) {
        Content content = editor.getText();
        var first = content.getIndexer().getCharPosition(start);
        var last = content.getIndexer().getCharPosition(end);
        editor.setSelectionRegion(first.line, first.column, last.line, last.column);
        editor.ensurePositionVisible(first.line, first.column);
    }
    private boolean canReplace() { return !reviewOnly && !readOnly && (preview == null || classEdit()); }
    private boolean canMutateBody() {
        return loaded && !loading && !reading && !saving && !session.saving && canReplace() && editor.isEditable();
    }
    private void updateEditingControls() {
        boolean editable = loaded && !loading && !reading && !saving && !session.saving && canReplace();
        editor.setEditable(editable);
        for (View control : bodyControls) control.setEnabled(editable);
        // Page arrows must remain available to queue navigation while a save is in flight.
        if (undoButton != null) undoButton.setEnabled(paged ? loaded : editable);
        if (redoButton != null) redoButton.setEnabled(paged ? loaded : editable);
        updateSymbolBar();
    }
    private void undo() { if (canMutateBody()) editor.undo(); }
    private void redo() { if (canMutateBody()) editor.redo(); }
    private void replaceOne() {
        if (!canMutateBody()) { Tasks.toast(this, "当前不可修改"); return; }
        EditorSearcher searcher = editor.getSearcher();
        if (!searcher.hasQuery()) return;
        try {
            if (searcher.isMatchedPositionSelected()) searcher.replaceCurrentMatch(replaceInput.getText().toString());
            else if (!searcher.gotoNext()) Tasks.toast(this, "没有匹配");
        } catch (RuntimeException pending) { Tasks.toast(this, "正在查找，请稍候"); }
        updateMatchCount();
    }
    private void replaceAll() {
        if (!canMutateBody()) { Tasks.toast(this, "当前不可修改"); return; }
        if (!editor.getSearcher().hasQuery()) return;
        try { editor.getSearcher().replaceAll(replaceInput.getText().toString()); }
        catch (RuntimeException error) { Tasks.toast(this, error.getMessage() == null ? "正在查找，请稍候" : error.getMessage()); }
    }
    private static final String[] SYMBOLS = {"Tab", "{", "}", "(", ")", "[", "]", "<", ">", "\"", "'", ";", ":",
            "=", "+", "-", "*", "/", "\\", "|", "&", "!", "?", "$", "#", "%", "_", ".", ",", "@", "~", "^", "`"};
    private View buildSymbolBar() {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false); scroll.setBackgroundColor(Ui.PANEL);
        symbols = Ui.row(this);
        populateSymbols();
        scroll.addView(symbols, new FrameLayout.LayoutParams(-2, -1));
        scroll.setVisibility(View.GONE);
        return scroll;
    }
    private String symbolConfiguration;
    private void populateSymbols(){
        if(symbols==null)return;String[] values=EditorPreferences.symbols(this);String configuration=String.join(" ",values);if(configuration.equals(symbolConfiguration))return;symbolConfiguration=configuration;
        for(int i=0;i<symbols.getChildCount();i++)bodyControls.remove(symbols.getChildAt(i));symbols.removeAllViews();
        for (String symbol : values) {
            TextView key = Ui.text(this, symbol, symbol.length() > 1 ? 12 : 16, Ui.TEXT);
            key.setGravity(android.view.Gravity.CENTER); key.setTypeface(android.graphics.Typeface.MONOSPACE);
            key.setContentDescription("插入 " + symbol); key.setBackground(Ui.ripple(Ui.PANEL));
            key.setMinimumWidth(Ui.dp(this, symbol.length() > 1 ? 52 : 48));
            key.setOnClickListener(v -> insertSymbol(symbol)); bodyControls.add(key);
            symbols.addView(key, new LinearLayout.LayoutParams(-2, -1));
        }
    }
    private void insertSymbol(String symbol) {
        if (!canMutateBody()) return;
        if (symbol.equals("Tab")) editor.indentOrCommitTab();
        else editor.insertText(symbol, symbol.length());
    }
    private void updateSymbolBar() {
        if (symbols == null) return;
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            android.view.WindowInsets insets = getWindow().getDecorView().getRootWindowInsets();
            imeVisible = insets != null && insets.isVisible(android.view.WindowInsets.Type.ime());
        }
        boolean searching = findInput.hasFocus() || replaceInput.hasFocus();
        boolean show = canMutateBody() && imeVisible && !searching && display().getBoolean("symbol-bar", true);
        View bar = (View) symbols.getParent();
        int wanted = show ? View.VISIBLE : View.GONE;
        if (bar.getVisibility() != wanted) { bar.setVisibility(wanted); if (show) Ui.fadeIn(bar); }
    }
    private void updatePosition() {
        if (position == null || !loaded) return;
        io.github.rosemoe.sora.text.Cursor cursor = editor.getCursor();
        position.setText(cursor.isSelected() ? "选中 " + (cursor.getRight() - cursor.getLeft())
                : (cursor.getLeftLine() + 1) + ":" + (cursor.getLeftColumn() + 1));
    }
    private void gotoLine() {
        if (!loaded) return;
        EditText line = Ui.input(this, "1 – " + editor.getLineCount());
        line.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        line.setContentDescription("目标行号");
        AlertDialog dialog = MaterialDialogs.builder(this).setTitle("跳转到行").setView(line)
                .setNegativeButton("取消", null).setPositiveButton("跳转", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                int target = Integer.parseInt(line.getText().toString().trim()) - 1;
                if (target < 0 || target >= editor.getLineCount()) { line.setError("行号超出范围"); return; }
                editor.setSelection(target, 0); editor.ensurePositionVisible(target, 0); dialog.dismiss();
            } catch (NumberFormatException e) { line.setError("请输入行号"); }
        }));
        dialog.show();
    }
    private android.content.SharedPreferences display() { return getSharedPreferences("editor-display", 0); }
    private int pagingLimit() { return Math.max(1,Math.min(16,display().getInt("paging-mib",2)))*1024*1024; }
    private boolean smoothMode() { return display().getBoolean("smooth",false) || editor.getText().length()>Math.max(10000,display().getInt("smooth-threshold",200000)); }
    private void applyDisplayPreferences() {
        if (defaultTypeface == null) defaultTypeface = editor.getTypefaceText();
        float density = getResources().getDisplayMetrics().density;
        editor.setScaleTextSizes(9 * density * getResources().getConfiguration().fontScale,
                32 * density * getResources().getConfiguration().fontScale);
        editor.setTextSize(Math.max(9f, Math.min(32f, display().getFloat("textSize", 13f))));
        editor.setTabWidth(Math.max(2, Math.min(8, display().getInt("tabWidth", 4))));
        editor.setLineNumberEnabled(display().getBoolean("lineNumbers", true));
        editor.setPinLineNumber(display().getBoolean("pin-lines", true));
        editor.setScalable(display().getBoolean("pinch-zoom", true));
        editor.setLineSpacingMultiplier(new float[]{1f,1.25f,1.5f,2f}[Math.max(0,Math.min(3,display().getInt("line-spacing",0)))]);
        editor.setBlockLineEnabled(display().getBoolean("indent-guides", true));
        editor.getProps().autoIndent=display().getBoolean("auto-indent", true);
        editor.getProps().symbolPairAutoCompletion=display().getBoolean("bracket-completion", true);
        editor.getProps().selectCompletionItemOnEnterForSoftKbd=display().getBoolean("completion-enter",false);
        editor.getComponent(io.github.rosemoe.sora.widget.component.EditorAutoCompletion.class).setMaxHeight(editor.getRowHeight()*Math.max(2,Math.min(10,display().getInt("completion-rows",3))));
        editor.getProps().disableTextExtracting=smoothMode();
        editor.getProps().showBidiDirectionIndicator=display().getBoolean("unicode-special",display().getBoolean("special",false));
        editor.setRenderFunctionCharacters(!denseControls&&display().getBoolean("special",false));
        editor.getComponent(io.github.rosemoe.sora.widget.component.Magnifier.class).setEnabled(display().getBoolean("magnifier", true));
        int flags=display().getBoolean("whitespace",false)?CodeEditor.FLAG_DRAW_WHITESPACE_LEADING|CodeEditor.FLAG_DRAW_WHITESPACE_INNER|CodeEditor.FLAG_DRAW_WHITESPACE_TRAILING|CodeEditor.FLAG_DRAW_WHITESPACE_FOR_EMPTY_LINE:0;
        if(display().getBoolean("wrap-arrows",false))flags|=CodeEditor.FLAG_DRAW_SOFT_WRAP;
        editor.setNonPrintablePaintingFlags(flags);
        applyTypeface(); applyWordwrap();populateSymbols();
    }
    private void applyTypeface() {
        android.graphics.Typeface face = display().getBoolean("monospace", false) ? android.graphics.Typeface.MONOSPACE : defaultTypeface;
        editor.setTypefaceText(face); editor.setTypefaceLineNumber(face);
    }
    /** Wrapping a dense binary page lays out every control byte; it stays off there whatever the preference. */
    private void applyWordwrap() {
        boolean wrap = display().getBoolean("wordwrap", false) && !denseControls;
        editor.setWordwrap(wrap,display().getBoolean("word-boundaries",true));
    }
    private void saveTextSize() {
        float sp = editor.getTextSizePx() / (getResources().getDisplayMetrics().density * getResources().getConfiguration().fontScale);
        if (sp > 0) display().edit().putFloat("textSize", Math.max(9f, Math.min(32f, sp))).apply();
    }
    private void menu() {
        boolean wrap = display().getBoolean("wordwrap", false), mono = display().getBoolean("monospace", false);
        String[] labels=new String[]{wrap ? "关闭自动换行" : "开启自动换行",
                mono ? "使用默认字体" : "使用等宽字体", "跳转到行", "重新选择读取编码", "转换保存编码",
                "撤销", "重做", "保存", "语法高亮", "保留的文件", "保留文件并最小化", "切换注释", "替换当前行", "保存换行符", "显示特殊字符", "流畅模式", "格式化 JSON", "压缩 JSON", "设置"};
        List<Integer> order=EditorPreferences.order(this);
        List<String> entries = new ArrayList<>(order.stream().map(i -> labels[i]).toList());
        entries.add(editor.hasCursors() ? "退出多光标（" + editor.cursorCount() + " 处）" : "多光标编辑");
        MaterialDialogs.builder(this).setItems(entries.toArray(String[]::new), (d, at) -> {
            if (at == order.size()) { if (editor.hasCursors()) editor.clearCursors(); else editOccurrences(); return; }
            int n=order.get(at);
            switch (n) {
                case 0 -> {
                    display().edit().putBoolean("wordwrap", !wrap).apply(); applyWordwrap();
                    if (!wrap && denseControls) Tasks.toast(this, "当前内容控制字符过多，暂不自动换行");
                }
                case 1 -> { display().edit().putBoolean("monospace", !mono).apply(); applyTypeface(); }
                case 2 -> gotoLine();
                case 3 -> {
                    if (dirty) { Tasks.toast(this, "先保存修改，再重新读取"); return; }
                    encoding(false);
                }
                case 4 -> encoding(true);
                case 5 -> undo();
                case 6 -> redo();
                case 7 -> save();
                case 8 -> SyntaxFiles.choose(this, syntax -> { customSyntax = syntax; applyLanguage(); },
                        () -> Tasks.run(this, () -> MtsxSyntax.forName(this,entry!=null?entry:source.name), syntax -> { customSyntax=syntax; applyLanguage(); }),
                        () -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), 641));
                case 9 -> EditorDocuments.show(this, this::keepFile);
                case 10 -> { keepFile(); if (kept) finish(); }
                case 11 -> toggleComment();
                case 12 -> replaceLine();
                case 13 -> lineEnding();
                case 14 -> { boolean show = !editor.isRenderFunctionCharacters(); editor.setRenderFunctionCharacters(show); editor.getProps().showBidiDirectionIndicator = show; display().edit().putBoolean("special", show).apply(); editor.invalidate(); }
                case 15 -> { boolean smooth = !editor.getProps().disableTextExtracting; editor.getProps().disableTextExtracting = smooth; display().edit().putBoolean("smooth", smooth).apply(); Tasks.toast(this, smooth ? "流畅模式已开启" : "流畅模式已关闭"); }
                case 16, 17 -> formatJson(n == 16);
                case 18 -> startActivityForResult(SettingsActivity.editorIntent(this),642);
            }
        }).show();
    }
    private void editOccurrences() {
        if (!canMutateBody()) return;
        var cursor = editor.getCursor();
        if (!cursor.isSelected() || cursor.getRight() - cursor.getLeft() > 256 || cursor.getLeftLine() != cursor.getRightLine()) {
            Tasks.toast(this, "请先选中一段单行文字（最多 256 字符）");
            return;
        }
        Content content = editor.getText();
        String needle = content.substring(cursor.getLeft(), cursor.getRight());
        String snapshot = content.toString();
        long revision = session.contentRevision;
        List<Integer> offsets = new ArrayList<>();
        for (int index = snapshot.indexOf(needle); index >= 0; index = snapshot.indexOf(needle, index + needle.length())) {
            if (offsets.size() == 200) { Tasks.toast(this, "匹配超过 200 处，请选择更具体的文字"); return; }
            offsets.add(index);
        }
        if (offsets.size() < 2) { Tasks.toast(this, "当前页只有一处匹配"); return; }
        boolean[] selected = new boolean[offsets.size()];
        Arrays.fill(selected, true);
        String[] positions = new String[offsets.size()];
        for (int i = 0; i < positions.length; i++) {
            var position = content.getIndexer().getCharPosition(offsets.get(i));
            positions[i] = "第 " + (position.line + 1) + " 行，第 " + (position.column + 1) + " 列";
        }
        int original = cursor.getLeft();
        MaterialDialogs.builder(this).setTitle("选择要同时编辑的位置")
                .setMultiChoiceItems(positions, selected, (d, index, value) -> selected[index] = value)
                .setNegativeButton("取消", null).setPositiveButton("启用多光标", (d, w) -> {
                    if (!canMutateBody() || session.contentRevision != revision || editor.getText() != content) {
                        Tasks.toast(this, "正文已变化，请重新选择"); return;
                    }
                    List<MultiCursorEditor.Range> selections = new ArrayList<>();
                    int primary = 0;
                    for (int i = 0; i < offsets.size(); i++) {
                        if (!selected[i]) continue;
                        int offset = offsets.get(i);
                        if (offset == original) primary = selections.size();
                        selections.add(new MultiCursorEditor.Range(offset, offset + needle.length()));
                    }
                    if (selections.size() < 2) { Tasks.toast(this, "请至少选择两处"); return; }
                    editor.setCursors(selections, primary);
                    editor.requestFocus();
                    editor.post(() -> {
                        var keyboard = getSystemService(android.view.inputmethod.InputMethodManager.class);
                        if (keyboard != null && editor.hasCursors()) keyboard.showSoftInput(editor, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
                    });
                }).show();
    }
    private void encoding(boolean convert) {
        if (reading || saving || session.saving || convert && !canMutateBody()) { Tasks.toast(this, "等待读写完成或当前不可修改"); return; }
        if (convert && paged) { Tasks.toast(this, "大文件分段编辑保留原编码"); return; }
        String[] choices = {"自动识别", "UTF-8", "UTF-16LE", "UTF-16BE", "UTF-32LE", "UTF-32BE",
                "GB18030", "GBK", "Big5", "Shift_JIS", "EUC-JP", "EUC-KR", "ISO-2022-JP",
                "windows-1252", "windows-1251", "KOI8-R", "ISO-8859-1"};
        if (convert) choices = Arrays.copyOfRange(choices, 1, choices.length);
        String[] encodings = choices;
        MaterialDialogs.builder(this).setTitle(convert ? "保存编码" : "读取编码").setItems(choices, (d, n) -> {
            // A chooser opened before save must not change encoding/reload the body after snapshot capture.
            if (reading || saving || session.saving || convert && !canMutateBody()) { Tasks.toast(this, "等待读写完成或当前不可修改"); return; }
            if (convert) {
                charset = Charset.forName(encodings[n]); bom = new byte[0]; uncertainEncoding = false;
                session.contentRevision++; dirty = true; details(); scheduleCheckpoint();
            } else load(n == 0 ? null : encodings[n]);
        }).show();
    }
    private void keepFile() {
        if (!loaded || reading || saving || readOnly || reviewOnly || preview != null && !classEdit()) { Tasks.toast(this, "当前文件暂不能保留"); return; }
        kept = true; getIntent().putExtra("kept", true); keepButton.setVisibility(View.GONE); checkpoint(true);
    }
    void releaseRetention(String document) { if (Objects.equals(session.document, document)) { kept=false; getIntent().removeExtra("kept"); keepButton.setVisibility(View.VISIBLE); } }
    private void lineEnding() {
        if (!canMutateBody()) return;
        if (paged) { Tasks.toast(this, "分段编辑保留原换行符，避免只转换一部分文件"); return; }
        MaterialDialogs.builder(this).setTitle("保存换行符").setItems(new String[]{"保留原内容", "LF · Unix", "CRLF · Windows", "CR · 旧 Mac"}, (d,n) -> {
            if (!canMutateBody()) return;
            outputLineEnding = new String[]{null,"\n","\r\n","\r"}[n]; session.contentRevision++; dirty=true; scheduleCheckpoint(); details();
        }).show();
    }
    private void replaceLine() {
        if (!canMutateBody()) return;
        ClipboardManager clipboard=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);if(!clipboard.hasPrimaryClip())return;
        String value=clipboard.getPrimaryClip().getItemAt(0).coerceToText(this).toString();int row=editor.getCursor().getLeftLine();
        if(value.endsWith("\r\n"))value=value.substring(0,value.length()-2);else if(value.endsWith("\n")||value.endsWith("\r"))value=value.substring(0,value.length()-1);
        editor.getText().replace(row,0,row,editor.getText().getColumnCount(row),value);
    }
    private void toggleComment() {
        if (!canMutateBody()) return;
        String start=customSyntax==null?switch(kind){case SHELL,CONFIG,SMALI->"#";case MARKUP->"<!--";case CODE,GENERIC->"//";default->null;}:customSyntax.commentStart;
        String end=customSyntax==null?(kind==CodeLanguage.Kind.MARKUP?"-->":null):customSyntax.commentEnd;
        if(start==null){Tasks.toast(this,"当前语法没有配置注释");return;}
        int first=editor.getCursor().getLeftLine(),last=editor.getCursor().getRightLine();Content content=editor.getText();
        if(end!=null){int a=content.getCharIndex(first,0),b=content.getCharIndex(last,content.getColumnCount(last));String text=content.substring(a,b);String stripped=text.trim();
            if(stripped.startsWith(start)&&stripped.endsWith(end)){int left=text.indexOf(start),right=text.lastIndexOf(end);content.replace(a,b,text.substring(0,left)+text.substring(left+start.length(),right)+text.substring(right+end.length()));}
            else content.replace(a,b,start+text+end);
        }else{boolean remove=true;for(int row=first;row<=last;row++)if(!content.getLineString(row).stripLeading().startsWith(start)){remove=false;break;}
            content.beginBatchEdit();try{for(int row=last;row>=first;row--){String text=content.getLineString(row);int indent=text.length()-text.stripLeading().length();if(remove){int endIndex=indent+start.length();if(endIndex<text.length()&&text.charAt(endIndex)==' ')endIndex++;content.delete(row,indent,row,endIndex);}else content.insert(row,indent,start+" ");}}finally{content.endBatchEdit();}}
    }
    private void formatJson(boolean pretty) {
        if (!canMutateBody()) return;String input=editor.getText().toString();long generation=session.contentRevision;
        if(paged){Tasks.toast(this,"分段内容不能作为完整 JSON 格式化");return;}
        runEditor(()->{JSONTokener tokener=new JSONTokener(input);Object value=tokener.nextValue();if(tokener.nextClean()!=0)throw new IOException("JSON 结尾有多余内容，未修改文件");if(value instanceof JSONObject object)return pretty?object.toString(2):object.toString();if(value instanceof JSONArray array)return pretty?array.toString(2):array.toString();throw new IOException("内容不是 JSON 对象或数组");},output->{
            if(!canMutateBody()||generation!=session.contentRevision){Tasks.toast(this,"内容已改变，请重新格式化");return;}editor.getText().replace(0,editor.getText().length(),output);
        },error->Tasks.showError(this,error));
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request==642){applyDisplayPreferences();applyLanguage();updateEditingControls();return;}
        if(request!=641||result!=RESULT_OK||data==null||data.getData()==null)return;
        Tasks.run(this,()->{try(InputStream input=getContentResolver().openInputStream(data.getData())){if(input==null)throw new IOException("无法读取语法文件");return MtsxSyntax.install(this,FileStore.readBytes(input,MtsxSyntax.LIMIT+1));}},syntax->{customSyntax=syntax;applyLanguage();Tasks.toast(this,"已安装并应用语法："+syntax.name);});
    }
    private void leave() {
        if (saving) { afterSave = this::leave; Tasks.toast(this, "保存完成后返回"); return; }
        if (reviewOnly) { finish(); return; }
        if(!kept&&!retentionAsked&&loaded&&!readOnly&&display().getInt("retention",0)==2){
            retentionAsked=true;MaterialDialogs.builder(this).setTitle("保留此文件？").setMessage("保留后，下次可继续编辑当前草稿。")
                    .setNegativeButton("不保留",(d,n)->leave()).setPositiveButton("保留",(d,n)->{keepFile();leave();}).setNeutralButton("继续编辑",null).show();return;
        }
        if (kept) { checkpoint(true); finish(); return; }
        if (dirty) MaterialDialogs.builder(this).setTitle("修改尚未保存").setNegativeButton("继续编辑", null)
                .setPositiveButton("放弃修改", (d, w) -> {
                    if (saving || session.saving) { afterSave = this::leave; return; }
                    dirty = false; handler.removeCallbacks(draftCheckpoint); session.discard(); finish();
                }).setNeutralButton("保存", (d, w) -> save(this::finish)).show();
        else finish();
    }
    @Override protected void handleBack() {
        if(closeSearch())return;
        if(display().getBoolean("double-back",false)){long now=SystemClock.uptimeMillis();if(now-lastExitBack>2000){lastExitBack=now;Tasks.toast(this,"再返回一次退出编辑器");return;}}
        leave();
    }
    @Override protected void onResume(){super.onResume();if(editor!=null&&loaded){applyDisplayPreferences();applyLanguage();updateSymbolBar();checkExternalChange();}}
    private void checkExternalChange(){
        if(externalCheck||reading||saving||source==null||session.baseline==null||reviewOnly||!display().getBoolean("detect-external",true))return;
        externalCheck=true;String baseline=session.baseline;
        runEditor(()->{try{return fingerprint();}catch(IOException e){return "unavailable:"+e.getMessage();}},revision->{
            externalCheck=false;if(!loaded||saving||!Objects.equals(baseline,session.baseline)||baseline.equals(revision)||Objects.equals(acknowledgedExternal,revision))return;
            acknowledgedExternal=revision;
            if(revision.startsWith("unavailable:")){MaterialDialogs.builder(this).setTitle("原文件当前无法读取").setMessage("编辑内容仍保留，可以继续核对草稿。\n"+revision.substring(12)).setPositiveButton("知道了",null).show();return;}
            MaterialDialogs.builder(this).setTitle("原文件已被外部修改").setMessage(dirty?"重新读取会替换当前未保存内容。":"是否读取磁盘上的新内容？")
                    .setNegativeButton("保留当前内容",null).setPositiveButton("重新读取",(d,n)->{
                        if(!saving&&!reading){dirty=false;handler.removeCallbacks(draftCheckpoint);session.discard();pendingDraft=null;session.baseline=null;session.archive=null;blockedSave=null;getIntent().removeExtra("sourceRevision");load(null);}
                    }).show();
        },error->{externalCheck=false;});
    }
    @Override protected void onPaletteChanged() {
        if (editor == null) return;
        EditorColorScheme colors = editor.getColorScheme();
        colors.setColor(EditorColorScheme.WHOLE_BACKGROUND, Ui.SURFACE);
        colors.setColor(EditorColorScheme.TEXT_NORMAL, Ui.TEXT);
        colors.setColor(EditorColorScheme.LINE_NUMBER_BACKGROUND, Ui.PANEL);
        colors.setColor(EditorColorScheme.LINE_NUMBER, Ui.MUTED);
        colors.setColor(EditorColorScheme.CURRENT_LINE, Ui.TINT);
        colors.setColor(EditorColorScheme.KEYWORD, Ui.dark ? 0xffeba6d2 : 0xffa34a86);
        colors.setColor(EditorColorScheme.LITERAL, Ui.dark ? 0xffedbd86 : 0xff8a5a00);
        colors.setColor(EditorColorScheme.HTML_TAG, Ui.dark ? 0xff78c9ec : 0xff247caa);
        colors.setColor(EditorColorScheme.ATTRIBUTE_NAME, Ui.dark ? 0xffc9b2f0 : 0xff7a4fb3);
        colors.setColor(EditorColorScheme.ATTRIBUTE_VALUE, Ui.dark ? 0xffa6e4b4 : 0xff2f7a3f);
        colors.setColor(EditorColorScheme.IDENTIFIER_VAR, Ui.dark ? 0xff7fd3e0 : 0xff0b7285);
        colors.setColor(EditorColorScheme.FUNCTION_NAME, Ui.dark ? 0xffe8c27a : 0xff8a5a00);
        colors.setColor(EditorColorScheme.ANNOTATION, Ui.dark ? 0xffd8c07a : 0xff7a4fb3);
        colors.setColor(EditorColorScheme.HIGHLIGHTED_DELIMITERS_FOREGROUND, Ui.ACCENT);
        colors.setColor(EditorColorScheme.HIGHLIGHTED_DELIMITERS_BACKGROUND, Ui.TINT);
        colors.setColor(EditorColorScheme.HIGHLIGHTED_DELIMITERS_UNDERLINE, Ui.ACCENT);
        colors.setColor(EditorColorScheme.MATCHED_TEXT_BACKGROUND, Ui.dark ? 0x66c9a227 : 0x66f2c94c);
        colors.setColor(EditorColorScheme.COMMENT, Ui.MUTED); editor.invalidate();
        if (editor.getEditorLanguage() instanceof MtsxLanguage language) language.colors(colors);
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        out.putBoolean("savedForCaller", session != null && session.sourceSaved);
        checkpoint(true); out.putString("editorSession", session.drafts.id);
        if (getIntent().hasExtra("sourceRevision")) out.putString("sourceRevision",
                session.baseline != null ? session.baseline : getIntent().getStringExtra("sourceRevision"));
        super.onSaveInstanceState(out);
    }
    @Override protected void onPause() { checkpoint(true); saveTextSize(); super.onPause(); }
    @Override public void finish() {
        if (session != null && session.sourceSaved) setResult(RESULT_OK);
        super.finish();
    }
    @Override protected void onStop() { checkpoint(true); super.onStop(); }
    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (editor != null) editor.release();
        super.onDestroy();
    }
}
