package org.example.filemanager;

import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

/** A copy plan built from selected comparison rows. Every overwrite is guarded. */
final class DirectorySync {
    record Parent(FileStore.Node anchor, List<String> missing) { }
    record Operation(String relative, FileStore.Node source, String sourceVersion,
                     Parent parent, String parentIdentity, FileStore.Node target, String targetVersion) { }
    record Preview(List<Operation> operations, List<String> skipped) { }
    private record Reviewed(Operation operation, String targetIdentity) { }

    static Preview preview(FileStore store, FileStore.Node from, FileStore.Node to,
                           Collection<CompareReport.Row> rows, boolean leftToRight) throws IOException {
        List<Operation> operations = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        Map<String, Map<String, FileStore.Node>> listings = new HashMap<>();
        Map<String, String> parents = new HashMap<>();
        for (CompareReport.Row row : rows) {
            Tasks.Progress.check();
            String priorSource = leftToRight ? row.left() : row.right();
            String priorTarget = leftToRight ? row.right() : row.left();
            try {
                if (row.label().equals("未能校验") || row.label().equals("类型不同") || priorSource.equals("无"))
                    throw new IOException("此方向没有可同步的项目");
                String[] path = row.name().split("/", -1);
                for (String part : path) FileStore.checkName(part);
                Parent sourceParent = findParent(store, from, path, listings);
                if (!sourceParent.missing.isEmpty()) throw new IOException("来源父目录已改变");
                Parent targetParent = findParent(store, to, path, listings);
                String name = path[path.length - 1];
                FileStore.Node source = children(store, sourceParent.anchor, listings).get(name);
                FileStore.Node target = targetParent.missing.isEmpty() ? children(store, targetParent.anchor, listings).get(name) : null;
                if (source == null || link(source) || target != null && (source.directory != target.directory || link(target)))
                    throw new IOException("文件不存在，或当前类型不支持同步");
                if (!priorSource.equals(DirectoryCompare.metadata(source)) || !priorTarget.equals(DirectoryCompare.metadata(target)))
                    throw new IOException("比较后项目已变化，请重新校验");
                if (source.directory && target != null) throw new IOException("目标目录已存在，目录属性不参与同步");
                store.validateCopy(source, targetParent.anchor);
                operations.add(new Operation(row.name(), source, version(store, source), targetParent,
                        parentIdentity(targetParent.anchor, parents), target,
                        target == null ? null : version(store, target)));
            } catch (IOException rejected) { skipped.add(row.name() + " · " + rejected.getMessage()); }
        }
        return new Preview(List.copyOf(operations), List.copyOf(skipped));
    }

    static int apply(FileStore store, Preview preview, FileStore.ConflictPolicy conflict) throws IOException {
        Map<String, Map<String, FileStore.Node>> listings = new HashMap<>();
        Map<String, String> parents = new HashMap<>();
        List<Reviewed> selected = new ArrayList<>();
        for (Operation operation : preview.operations) {
            Tasks.Progress.check();
            if (!operation.sourceVersion.equals(version(store, operation.source)) || link(operation.source))
                throw new IOException("来源已变化：" + operation.relative);
            Parent parent = operation.parent;
            if (!operation.parentIdentity.equals(parentIdentity(parent.anchor, parents)))
                throw new IOException("目标父目录已变化，请重新预览：" + operation.relative);
            Map<String, FileStore.Node> directory = children(store, parent.anchor, listings);
            if (!parent.missing.isEmpty() && directory.containsKey(parent.missing.get(0)))
                throw new IOException("目标父目录已变化，请重新预览：" + operation.relative);
            FileStore.Node current = parent.missing.isEmpty() ? directory.get(operation.source.name) : null;
            String identity = current == null ? null : store.identity(current);
            if (current == null ? operation.target != null : operation.target == null
                    || !current.key().equals(operation.target.key()) || link(current)
                    || !Objects.equals(operation.targetVersion, version(store, current)))
                throw new IOException("目标已变化，请重新预览：" + operation.relative);
            if (current != null && conflict == FileStore.ConflictPolicy.SKIP) continue;
            selected.add(new Reviewed(operation, identity));
        }
        Map<String, Map<String, FileStore.Node>> created = new HashMap<>();
        TransferPlan plan = new TransferPlan();
        int directories = 0;
        for (Reviewed reviewed : selected) {
            Tasks.Progress.check();
            Operation operation = reviewed.operation;
            FileStore.Node parent = operation.parent.anchor;
            for (String component : operation.parent.missing) parent = createDirectory(store, parent, component, created);
            if (operation.source.directory) {
                createDirectory(store, parent, operation.source.name, created);
                directories++;
                continue;
            }
            FileStore.Node target = operation.target;
            plan.items.add(new TransferPlan.Item(operation.source, parent,
                    target == null ? FileStore.ConflictPolicy.SKIP : conflict, target,
                    reviewed.targetIdentity));
            if (!plan.directoryNames.containsKey(parent.key()))
                plan.directoryNames.put(parent.key(), new HashSet<>(children(store, parent, listings).keySet()));
        }
        return directories + (plan.items.isEmpty() ? 0 : store.executePlan(plan, false).size());
    }

    private static FileStore.Node createDirectory(FileStore store, FileStore.Node parent, String name,
                                                   Map<String, Map<String, FileStore.Node>> created) throws IOException {
        var siblings = created.computeIfAbsent(parent.key(), key -> new HashMap<>());
        FileStore.Node directory = siblings.get(name);
        if (directory == null) {
            directory = store.create(parent, name, true);
            if (!directory.directory || !name.equals(directory.name)) throw new IOException("存储提供方改变了新建目录名称：" + name);
            siblings.put(name, directory);
        }
        return directory;
    }

    private static String version(FileStore store, FileStore.Node node) throws IOException {
        if (node.file != null && !node.root) {
            var attrs = Files.readAttributes(node.file.toPath(), java.nio.file.attribute.BasicFileAttributes.class,
                    java.nio.file.LinkOption.NOFOLLOW_LINKS);
            return store.identity(node) + ":" + attrs.lastModifiedTime();
        }
        return store.identity(node);
    }

    private static String parentIdentity(FileStore.Node parent, Map<String, String> parents) throws IOException {
        String identity = parents.get(parent.key());
        if (identity != null) return identity;
        if (parent.file == null) identity = parent.key();
        else if (parent.root) {
            if (link(parent)) throw new IOException("目标父目录是链接");
            identity = RootDirectoryReader.stat(parent.path(), "%d:%i", false);
        } else {
            try {
                var info = android.system.Os.lstat(parent.path());
                if (!android.system.OsConstants.S_ISDIR(info.st_mode)) throw new IOException("目标父目录已失效");
                identity = info.st_dev + ":" + info.st_ino;
            } catch (android.system.ErrnoException error) { throw new IOException(error); }
        }
        parents.put(parent.key(), identity);
        return identity;
    }

    private static Parent findParent(FileStore store, FileStore.Node directory, String[] path,
                                      Map<String, Map<String, FileStore.Node>> listings) throws IOException {
        for (int i = 0; i < path.length - 1; i++) {
            FileStore.Node child = children(store, directory, listings).get(path[i]);
            if (child == null) return new Parent(directory, List.copyOf(Arrays.asList(path).subList(i, path.length - 1)));
            if (!child.directory || link(child)) throw new IOException("父路径存在同名文件或链接");
            directory = child;
        }
        return new Parent(directory, List.of());
    }

    private static Map<String, FileStore.Node> children(FileStore store, FileStore.Node parent,
                                                        Map<String, Map<String, FileStore.Node>> listings) throws IOException {
        Map<String, FileStore.Node> known = listings.get(parent.key());
        if (known != null) return known;
        Map<String, FileStore.Node> index = new HashMap<>();
        for (FileStore.Node node : store.list(parent)) {
            if (index.putIfAbsent(node.name, node) != null) throw new IOException("目录内有重复名称");
        }
        listings.put(parent.key(), index);
        return index;
    }

    private static boolean link(FileStore.Node node) throws IOException {
        if (node.file == null) return false;
        if (!node.root) return Files.isSymbolicLink(node.file.toPath());
        int mode = Integer.parseUnsignedInt(RootDirectoryReader.stat(node.path(), "%f", false), 16);
        return android.system.OsConstants.S_ISLNK(mode);
    }

    private DirectorySync() { }
}
