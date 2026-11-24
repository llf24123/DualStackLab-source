package org.example.filemanager;

import android.content.Context;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.Comparator;
import java.util.function.Consumer;

/** Changes selected entries by default; recursion requires separate explicit file/directory flags. */
final class BatchFileAttributes {
    private static final int MAX_DEPTH = 256, MAX_ITEMS = 100000;
    record Change(Integer mode, Long uid, Long gid, Long modified, Long accessed) {
        void validate() throws IOException {
            if (mode == null && uid == null && gid == null && modified == null && accessed == null)
                throw new IOException("请至少选择一项要修改的属性");
            if (mode != null && (mode < 0 || mode > 07777)) throw new IOException("权限值超出 0000–7777");
            if (uid != null || gid != null) RootFileOps.validateOwner(uid, gid);
            if (modified != null || accessed != null) RootFileOps.validateTimes(modified, accessed);
        }
    }
    record ItemResult(FileStore.Node node, List<String> applied, List<String> errors) {
        ItemResult { applied = List.copyOf(applied); errors = List.copyOf(errors); }
        boolean success() { return errors.isEmpty() && !applied.isEmpty(); }
        boolean partial() { return !applied.isEmpty() && !errors.isEmpty(); }
    }
    record Result(List<ItemResult> items, List<FileStore.Node> remaining, boolean cancelled) {
        Result { items = List.copyOf(items); remaining = List.copyOf(remaining); }
        int succeeded() { return (int) items.stream().filter(ItemResult::success).count(); }
        int failed() { return (int) items.stream().filter(item -> !item.success()).count(); }
        int partial() { return (int) items.stream().filter(ItemResult::partial).count(); }
        String summary() {
            String result = "已完成 " + succeeded() + " 项";
            if (failed() > 0) result += "，失败 " + failed() + " 项";
            if (partial() > 0) result += "（其中 " + partial() + " 项已修改部分属性）";
            if (cancelled) result += "，已停止";
            if (!remaining.isEmpty()) result += "，未处理 " + remaining.size() + " 项";
            return result;
        }
    }

    static Result apply(Context context, List<FileStore.Node> selected, Change change) throws IOException {
        return apply(context, selected, change, false, false);
    }
    static Result apply(Context context, List<FileStore.Node> selected, Change change, boolean childFiles, boolean childDirectories) throws IOException {
        return apply(context, selected, change, childFiles, childDirectories, ignored -> { });
    }

    /** Observer runs on the worker after each fully accounted item, useful for lightweight progress. */
    static Result apply(Context context, List<FileStore.Node> selected, Change change, Consumer<ItemResult> completed) throws IOException {
        return apply(context, selected, change, false, false, completed);
    }
    static Result apply(Context context, List<FileStore.Node> selected, Change change, boolean childFiles, boolean childDirectories,
                        Consumer<ItemResult> completed) throws IOException {
        change.validate();
        LinkedHashMap<String, FileStore.Node> unique = new LinkedHashMap<>();
        for (FileStore.Node node : List.copyOf(selected)) unique.putIfAbsent(node.key(), node);
        List<FileStore.Node> selectedNodes = List.copyOf(unique.values());
        List<Pending> pending = new ArrayList<>();
        if (childFiles || childDirectories) {
            Planner planner = new Planner(childFiles, childDirectories, unique.keySet(), pending);
            List<FileStore.Node> roots = new ArrayList<>(selectedNodes);
            roots.sort(Comparator.comparingInt(node -> node.path().split("/").length));
            try (Tasks.Progress.Phase ignored = Tasks.Progress.stage("读取所选目录")) {
                for (FileStore.Node node : roots) planner.walk(node, true, 0);
            } catch (Tasks.CancelledException stopped) {
                // Enumeration precedes every mutation, so cancellation here leaves all selected roots unchanged.
                return new Result(List.of(), selectedNodes, true);
            }
        } else for (FileStore.Node node : selectedNodes) pending.add(new Pending(node, true, List.of()));
        List<FileStore.Node> nodes = pending.stream().map(Pending::node).toList();
        List<ItemResult> results = new ArrayList<>();
        for (int index = 0; index < nodes.size(); index++) {
            FileStore.Node node = nodes.get(index);
            Pending planned = pending.get(index);
            List<String> applied = new ArrayList<>(), errors = new ArrayList<>(planned.errors);
            boolean started = false;
            try {
                Tasks.Progress.check();
                FileAttributes.requireFilesystem(node);
                FileStore.Node fixed = node;
                if (planned.apply) {
                    FileAttributes.Snapshot snapshot = FileAttributes.inspect(node);
                    if (snapshot.link()) throw new IOException("符号链接未修改，也未进入目标");
                    if (!snapshot.directory() && !snapshot.regular()) throw new IOException("特殊文件不支持属性修改");
                    fixed = snapshot.node();
                }
                FileStore.Node target = fixed;
                started = true;
                // chown may clear setuid/setgid; mode comes last so mode 000 does not block setTimes.
                if (planned.apply && (change.uid != null || change.gid != null)) perform("所有者/用户组", applied, errors,
                        () -> FileAttributes.owner(context, target, change.uid, change.gid));
                if (planned.apply && (change.modified != null || change.accessed != null)) perform("时间", applied, errors,
                        () -> FileAttributes.times(context, target, change.modified, change.accessed));
                if (planned.apply && change.mode != null) perform("权限", applied, errors, () -> FilePermissions.apply(context, target, change.mode));
            } catch (Tasks.CancelledException stopped) {
                if (started) {
                    errors.add("已停止，当前项的剩余属性未完成");
                    ItemResult item = new ItemResult(node, applied, errors); results.add(item); completed.accept(item);
                }
                return new Result(results, nodes.subList(index + (started ? 1 : 0), nodes.size()), true);
            } catch (IOException | RuntimeException error) { errors.add(message(error)); }
            ItemResult item = new ItemResult(node, applied, errors); results.add(item); completed.accept(item);
        }
        return new Result(results, List.of(), false);
    }

    private record Pending(FileStore.Node node, boolean apply, List<String> errors) { }
    private static final class Planner {
        final boolean files, directories;
        final Set<String> explicit, visitedPaths = new HashSet<>(), visitedIdentities = new HashSet<>(), scheduled = new HashSet<>();
        final List<Pending> items;
        boolean limitReached;
        Planner(boolean files, boolean directories, Set<String> explicit, List<Pending> items) {
            this.files = files; this.directories = directories; this.explicit = Set.copyOf(explicit); this.items = items;
        }
        void walk(FileStore.Node requested, boolean selected, int depth) throws Tasks.CancelledException {
            Tasks.Progress.check();
            if (limitReached) {
                if (selected) items.add(new Pending(requested, false, List.of("递归条目超过 100000 项，未处理此分支")));
                return;
            }
            if (!visitedPaths.add(requested.key())) return;
            List<String> errors = new ArrayList<>();
            FileAttributes.Snapshot snapshot;
            try {
                if (visitedPaths.size() > MAX_ITEMS) { limitReached = true; throw new IOException("递归条目超过 100000 项，未处理此分支"); }
                snapshot = FileAttributes.inspect(requested);
            } catch (Tasks.CancelledException stopped) { throw stopped; }
            catch (IOException | RuntimeException error) { items.add(new Pending(requested, false, List.of(message(error)))); return; }
            FileStore.Node node = snapshot.node();
            boolean chosen = selected || explicit.contains(requested.key()) || (snapshot.directory() ? directories : files);
            if (snapshot.link() || !snapshot.directory() && !snapshot.regular()) {
                if (chosen) items.add(new Pending(node, false, List.of(snapshot.link() ? "符号链接未修改，也未进入目标" : "特殊文件不支持属性修改")));
                return;
            }
            if (visitedIdentities.add(snapshot.identity()) && snapshot.directory()) {
                try {
                    if (depth >= MAX_DEPTH) throw new IOException("目录层级超过 256 层，未处理更深层条目");
                    for (FileStore.Node child : FileAttributes.children(snapshot)) {
                        walk(child, false, depth + 1);
                        if (limitReached) throw new IOException("递归条目超过 100000 项，其余子项未处理");
                    }
                } catch (Tasks.CancelledException stopped) { throw stopped; }
                catch (IOException | RuntimeException error) { errors.add("子项读取失败：" + message(error)); }
            }
            // Postorder ensures changing a selected directory's owner/mode cannot block its descendants.
            if (chosen && scheduled.add(snapshot.identity())) items.add(new Pending(node, true, List.copyOf(errors)));
            else if (!errors.isEmpty()) items.add(new Pending(node, false, List.copyOf(errors)));
        }
    }

    private interface Operation { void run() throws IOException; }
    private static void perform(String label, List<String> applied, List<String> errors, Operation operation) throws Tasks.CancelledException {
        Tasks.Progress.check();
        try (Tasks.Progress.Phase ignored = Tasks.Progress.stage("修改" + label)) {
            operation.run(); applied.add(label);
        } catch (Tasks.CancelledException stopped) { throw stopped; }
        catch (IOException | RuntimeException error) {
            errors.add(label + "：" + message(error));
            // Reader/helper wrappers may preserve interruption as IOException rather than CancelledException.
            Tasks.Progress.check();
        }
    }
    private static String message(Exception error) {
        return error.getMessage() == null || error.getMessage().isBlank() ? error.getClass().getSimpleName() : error.getMessage();
    }
    private BatchFileAttributes() { }
}
