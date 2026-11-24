package org.example.filemanager;

import android.app.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.function.Consumer;

/** Resolve all choices before starting; merging expands only directories that already exist. */
final class TransferPlan {
    record Item(FileStore.Node source, FileStore.Node parent, FileStore.ConflictPolicy policy,
                FileStore.Node existing, String expected) { }
    final List<Item> items = new ArrayList<>();
    final Map<String, Set<String>> directoryNames = new HashMap<>();
    final List<FileStore.Node> merged = new ArrayList<>();
    final Set<String> skipped = new HashSet<>();
    final Map<String, FileStore.Node> published = new LinkedHashMap<>();
    final Map<String, FileStore.OutputVersion> versions = new HashMap<>();
    final Map<String, String> archiveRevisions = new HashMap<>();
    final Set<String> removedArchives = new HashSet<>();
    final Set<String> removedSources = new HashSet<>();
    final Set<String> settled = new HashSet<>();
    int retainedEmptyDirectories;
    static final class Incomplete extends IOException {
        final TransferPlan plan;
        Incomplete(TransferPlan plan, Exception cause) {
            super("已写入 " + plan.published.size() + " 项，操作未完成。重试会校验并复用已写入的文件。\n" + cause.getMessage(), cause); this.plan = plan;
        }
    }
    private record Pending(FileStore.Node source, FileStore.Node parent, FileStore.Node existing, int depth) { }
    static void resolve(Activity activity, FileStore store, List<FileStore.Node> sources, FileStore.Node parent, Consumer<TransferPlan> ready) {
        new Resolver(activity, store, ready).start(sources, parent);
    }
    private static final class Resolver {
        final Activity activity; final FileStore store; final Consumer<TransferPlan> ready;
        final TransferPlan plan = new TransferPlan(); final Deque<Pending> pending = new ArrayDeque<>();
        final Set<String> visited = new HashSet<>();
        FileStore.ConflictPolicy allFiles, allDirectories;
        boolean mergeAll, cancelled;
        Resolver(Activity activity, FileStore store, Consumer<TransferPlan> ready) { this.activity = activity; this.store = store; this.ready = ready; }
        void start(List<FileStore.Node> sources, FileStore.Node parent) {
            Tasks.run(activity, Tasks.BROWSE, () -> collect(sources, parent, 0), entries -> { pending.addAll(entries); next(); }, this::failed);
        }
        List<Pending> collect(List<FileStore.Node> sources, FileStore.Node parent, int depth) throws IOException {
            if (depth > 128) throw new IOException("合并目录层级超过 128 层");
            Map<String, FileStore.Node> existing = new HashMap<>(); for (var child : store.list(parent)) existing.put(child.name, child);
            plan.directoryNames.put(parent.key(), new HashSet<>(existing.keySet()));
            List<Pending> list = new ArrayList<>(); for (var source : sources) list.add(new Pending(source, parent, existing.get(source.name), depth)); return list;
        }
        void next() {
            if (cancelled || activity.isFinishing() || activity.isDestroyed()) return;
            while (!pending.isEmpty()) {
                Pending p = pending.peek();
                if (p.existing == null) { pending.removeFirst(); plan.items.add(new Item(p.source, p.parent, FileStore.ConflictPolicy.KEEP_BOTH, null, null)); }
                else if ((p.source.directory || p.existing.directory ? allDirectories : allFiles) == FileStore.ConflictPolicy.SKIP
                        && !(p.source.directory && p.existing.directory && mergeAll)) {
                    pending.removeFirst(); plan.skipped.add(p.source.key());
                } else break;
            }
            if (pending.isEmpty()) { ready.accept(plan); return; }
            Pending p = pending.removeFirst();
            boolean dirs = p.source.directory && p.existing.directory;
            FileStore.ConflictPolicy all = p.source.directory || p.existing.directory ? allDirectories : allFiles;
            if (dirs && mergeAll) { merge(p); return; }
            if (all != null && (all != FileStore.ConflictPolicy.OVERWRITE || !p.source.directory && !p.existing.directory)) { choose(p, all); return; }
            Tasks.run(activity, Tasks.BROWSE, () -> store.identity(p.existing), expected -> show(p, expected, dirs), this::failed);
        }
        void show(Pending p, String expected, boolean dirs) {
            LinearLayout body = Ui.column(activity); int pad = Ui.dp(activity, 18); body.setPadding(pad, pad, pad, 0);
            body.addView(Ui.text(activity, p.source.name + "\n来源：" + p.source.path() + "\n" + DirectoryCompare.metadata(p.source)
                    + "\n\n目标：" + p.existing.path() + "\n" + DirectoryCompare.metadata(p.existing), 14, Ui.TEXT));
            CheckBox all = new CheckBox(activity); all.setText("应用到后续同类冲突"); all.setMinHeight(Ui.dp(activity, 48)); body.addView(all);
            ScrollView scroll = new ScrollView(activity); scroll.addView(body);
            AlertDialog dialog = MaterialDialogs.builder(activity).setTitle("处理同名项目").setView(scroll)
                    .setNegativeButton("取消传输", (d, w) -> cancelled = true).setOnCancelListener(d -> cancelled = true).create();
            if (dirs) button(body, "合并目录（逐项处理内部重名）", "合并", () -> { dialog.dismiss(); if (all.isChecked()) mergeAll = true; merge(p); });
            if (!p.source.directory && !p.existing.directory) button(body, "覆盖同名文件", "覆盖", () -> {
                dialog.dismiss(); if (all.isChecked()) allFiles = FileStore.ConflictPolicy.OVERWRITE; add(p, FileStore.ConflictPolicy.OVERWRITE, expected); next();
            });
            button(body, "另存一份", "另存", () -> { dialog.dismiss(); if (all.isChecked()) setAll(p, FileStore.ConflictPolicy.KEEP_BOTH); add(p, FileStore.ConflictPolicy.KEEP_BOTH, expected); next(); });
            button(body, "跳过同名项", "跳过", () -> { dialog.dismiss(); if (all.isChecked()) setAll(p, FileStore.ConflictPolicy.SKIP); plan.skipped.add(p.source.key()); next(); });
            dialog.show();
        }
        void setAll(Pending p, FileStore.ConflictPolicy policy) { if (p.source.directory || p.existing.directory) allDirectories = policy; else allFiles = policy; }
        void choose(Pending p, FileStore.ConflictPolicy policy) {
            if (policy == FileStore.ConflictPolicy.SKIP) { plan.skipped.add(p.source.key()); next(); return; }
            Tasks.run(activity, Tasks.BROWSE, () -> store.identity(p.existing), expected -> { add(p, policy, expected); next(); }, this::failed);
        }
        void add(Pending p, FileStore.ConflictPolicy policy, String expected) { plan.items.add(new Item(p.source, p.parent, policy, p.existing, expected)); }
        void merge(Pending p) {
            Tasks.run(activity, Tasks.BROWSE, () -> {
                if (!visited.add(p.source.key())) throw new IOException("检测到重复合并目录");
                if (p.source.file != null && java.nio.file.Files.isSymbolicLink(p.source.file.toPath())) throw new IOException("符号链接目录不能合并");
                store.validateCopy(p.source, p.existing);
                return collect(store.list(p.source), p.existing, p.depth + 1);
            }, entries -> { plan.merged.add(p.source); for (int i = entries.size() - 1; i >= 0; i--) pending.addFirst(entries.get(i)); next(); }, this::failed);
        }
        void button(LinearLayout body, String title, String description, Runnable action) {
            TextView button = Ui.text(activity, title, 15, Ui.TEXT); button.setMinHeight(Ui.dp(activity, 48));
            button.setContentDescription("同名处理 " + description); button.setBackground(Ui.ripple(Ui.PANEL)); button.setOnClickListener(v -> action.run()); body.addView(button);
        }
        void failed(Exception error) { cancelled = true; Tasks.showError(activity, error); }
    }
}
