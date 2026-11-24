package org.example.filemanager;

import android.app.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.function.Consumer;

/** A preview is immutable; execution rechecks every source and destination before changing names. */
final class BatchRename {
    interface Naming { String name(FileStore store, FileStore.Node node, int index, int total) throws IOException; }
    record Rule(String find, String replacement, boolean number, int start, String extension) implements Naming {
        public String name(FileStore store, FileStore.Node node, int index, int total) throws IOException {
            if (start < 0) throw new IOException("起始序号不能小于 0");
            return name(node, index);
        }
        String name(FileStore.Node node, int index) {
            String value = find.isEmpty() ? node.name : node.name.replace(find, replacement);
            int dot = node.directory ? -1 : value.lastIndexOf('.');
            String stem = dot > 0 ? value.substring(0, dot) : value;
            String suffix = dot > 0 ? value.substring(dot) : "";
            if (number) stem += "_" + ((long) start + index);
            if (!node.directory && !extension.isBlank()) suffix = "." + extension.replaceFirst("^\\.+", "");
            return stem + suffix;
        }
    }
    record Item(FileStore.Node source, String name, String revision, String error) { }
    record Plan(FileStore.Node parent, List<Item> items) {
        boolean valid() { return items.stream().noneMatch(i -> !i.error.isEmpty()); }
        long changes() { return items.stream().filter(i -> !i.source.name.equals(i.name)).count(); }
    }
    static Plan preview(FileStore store, List<FileStore.Node> nodes, Naming rule) throws IOException {
        return preview(store, nodes.isEmpty() ? null : store.parent(nodes.get(0)), nodes, rule);
    }
    static Plan preview(FileStore store, FileStore.Node parent, List<FileStore.Node> nodes, Naming rule) throws IOException {
        if (nodes.isEmpty()) throw new IOException("先选择需要重命名的项目");
        if (parent == null) throw new IOException("不能重命名根目录");
        Set<String> occupied = new HashSet<>(store.listNames(parent));
        Set<String> childKeys = new HashSet<>();
        if (parent.uri != null) for (var child : store.list(parent)) childKeys.add(child.key());
        Set<String> selected = new HashSet<>(), results = new HashSet<>();
        List<String> names = new ArrayList<>(), errors = new ArrayList<>(); Set<String> duplicates = new HashSet<>();
        for (int i = 0; i < nodes.size(); i++) {
            Tasks.Progress.check();
            FileStore.Node node = nodes.get(i); FileStore.checkRemoval(node);
            FileStore.Node actualParent = store.parent(node);
            if (!(parent.uri != null ? childKeys.contains(node.key()) : actualParent != null && parent.key().equals(actualParent.key())) || !selected.add(node.key()))
                throw new IOException("批量重命名需要同一目录内的不同项目");
            occupied.remove(node.name);
            String name = node.name, error = "";
            try { name = rule.name(store, node, i, nodes.size()); }
            catch (Tasks.CancelledException cancelled) { throw cancelled; }
            catch (IOException | IllegalArgumentException invalid) { error = invalid.getMessage(); }
            names.add(name); errors.add(error);
            if (!results.add(name)) duplicates.add(name);
        }
        List<Item> items = new ArrayList<>();
        String archiveRevision = parent.container == null ? null : store.fingerprint(parent.container);
        for (int i = 0; i < nodes.size(); i++) {
            Tasks.Progress.check();
            FileStore.Node node = nodes.get(i); String name = names.get(i), error = errors.get(i);
            try { FileStore.checkName(name); } catch (IOException invalid) { error = invalid.getMessage(); }
            if (error.isEmpty() && occupied.contains(name)) error = "与未选中的项目重名";
            if (error.isEmpty() && duplicates.contains(name)) error = "多个项目会得到同一个名称";
            items.add(new Item(node, name, archiveRevision == null ? store.identity(node) : archiveRevision, error));
        }
        return new Plan(parent, List.copyOf(items));
    }
    static List<FileStore.Node> execute(FileStore store, Plan plan) throws IOException {
        if (!plan.valid()) throw new IOException("请先解决预览中的名称冲突");
        Set<String> occupied = new HashSet<>(store.listNames(plan.parent));
        String archiveRevision = plan.parent.container == null ? null : store.fingerprint(plan.parent.container);
        for (Item item : plan.items) {
            if (!item.revision.equals(archiveRevision == null ? store.identity(item.source) : archiveRevision)) throw new IOException("来源已变化，请重新预览：" + item.source.name);
            occupied.remove(item.source.name);
        }
        for (Item item : plan.items) if (!occupied.add(item.name)) throw new IOException("目标名称已被占用：" + item.name);
        List<Item> changes = plan.items.stream().filter(i -> !i.source.name.equals(i.name)).toList();
        if (changes.isEmpty()) return List.of();
        if (plan.parent.container != null) {
            Map<FileStore.Node, String> names = new LinkedHashMap<>();
            for (Item item : changes) names.put(item.source, item.name);
            return store.renameArchiveBatch(names, changes.get(0).revision);
        }
        // Temporary names also support swaps and case-only renames on case-insensitive providers.
        Map<Item, FileStore.Node> current = new LinkedHashMap<>();
        Map<Item, String> temporary = new LinkedHashMap<>();
        Set<Item> finalNames = new HashSet<>();
        for (Item item : changes) temporary.put(item, ".dualstack-rename-" + UUID.randomUUID());
        RenameJournal journal = RenameJournal.begin(store, plan, changes, temporary);
        try {
            for (Item item : changes) {
                String temp = temporary.get(item);
                FileStore.Node renamed = store.rename(item.source, temp); current.put(item, renamed);
                journal.moved(item, renamed);
                if (!renamed.name.equals(temp)) throw new IOException("存储提供方改变了临时名称");
            }
            for (Item item : changes) {
                FileStore.Node renamed = store.rename(current.get(item), item.name); current.put(item, renamed); finalNames.add(item);
                journal.moved(item, renamed);
                if (!renamed.name.equals(item.name)) throw new IOException("存储提供方改变了目标名称：" + renamed.name);
            }
            journal.complete(); return new ArrayList<>(current.values());
        } catch (IOException | RuntimeException failure) {
            List<String> recovery = new ArrayList<>();
            Tasks.Progress.cleanup(() -> {
                for (Item item : finalNames) try { current.put(item, store.rename(current.get(item), temporary.get(item))); journal.moved(item, current.get(item)); }
                catch (Exception error) { failure.addSuppressed(error); }
                for (Item item : changes) if (current.containsKey(item)) try {
                    FileStore.Node restored = store.rename(current.get(item), item.source.name); current.put(item, restored);
                    journal.moved(item, restored);
                    if (!restored.name.equals(item.source.name)) throw new IOException("提供方没有恢复原名");
                } catch (Exception error) { recovery.add(current.get(item).path() + " → " + item.source.name); failure.addSuppressed(error); }
            });
            if (recovery.isEmpty()) try { journal.complete(); } catch (IOException error) { failure.addSuppressed(error); }
            throw new IOException("批量重命名未完成" + (recovery.isEmpty() ? "，已恢复原名" : "，以下项目需恢复名称：\n" + String.join("\n", recovery))
                    + "\n" + failure.getMessage(), failure);
        }
    }
    static void show(Activity activity, FileStore store, FileStore.Node parent, List<FileStore.Node> nodes, Consumer<Plan> execute) {
        RenameDialog.show(activity, store, parent, nodes, execute);
    }
    private BatchRename() { }
}
