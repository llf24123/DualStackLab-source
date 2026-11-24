package org.example.filemanager;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import java.io.IOException;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.Future;

/** Compact, read-only overview; expensive scans and explicit edits live on workers. */
final class FileProperties {
    static AlertDialog show(Activity activity, List<FileStore.Node> selected, Runnable changed) {
        if (selected.isEmpty()) throw new IllegalArgumentException("没有选择文件");
        return new Overview(activity, List.copyOf(selected), changed).show();
    }

    private static final class Overview {
        final Activity activity;
        final List<FileStore.Node> nodes;
        final FileStore.Node first;
        final Runnable changed;
        final boolean single, editable;
        final LinearLayout body;
        AlertDialog dialog;
        FileStatistics.Scan scan;
        Future<?> statistics, metadata;
        FileStatistics.Result result;
        FileAttributes.State state;
        TextView size, files, folders, status, modified, permissions, owner, group;
        Button stop, errors;
        int generation;
        boolean scanFinished, stopping;

        Overview(Activity activity, List<FileStore.Node> nodes, Runnable changed) {
            this.activity = activity; this.nodes = nodes; this.first = nodes.get(0); this.changed = changed;
            single = nodes.size() == 1;
            editable = nodes.stream().allMatch(n -> n.file != null && n.container == null && n.uri == null);
            body = body(activity);
        }
        AlertDialog show() {
            String names = nodes.stream().limit(8).map(n -> n.name).collect(java.util.stream.Collectors.joining(", "));
            if (nodes.size() > 8) names += " …（共 " + nodes.size() + " 项）";
            TextView name = row(activity, body, "名称", names); name.setMaxLines(4);
            String directory = parent(first);
            if (nodes.stream().anyMatch(n -> !parent(n).equals(parent(first)))) directory = "多个目录";
            TextView location = row(activity, body, "目录", directory);
            location.setMinHeight(dp(activity,48)); location.setMaxLines(2); location.setEllipsize(android.text.TextUtils.TruncateAt.END);
            location.setOnClickListener(v -> message(activity, "完整路径", nodes.stream().map(n -> n.uri != null ? n.uri.toString() : n.path()).collect(java.util.stream.Collectors.joining("\n"))));
            if (single) row(activity, body, "类型", first.directory ? "文件夹" : first.container != null ? "归档内文件" : "文件");
            size = row(activity, body, single ? "大小" : "总大小", "正在计算…"); size.setContentDescription("属性大小");
            if (!single || first.directory) {
                files = row(activity, body, "文件数", "正在计算…"); files.setContentDescription("属性文件数");
                folders = row(activity, body, "文件夹数", "正在计算…"); folders.setContentDescription("属性文件夹数");
            }
            if (single) {
                modified = row(activity, body, "修改时间", first.modified > 0 ? date(first.modified) : "未知");
                if (editable) {
                    rule(activity, body);
                    permissions = row(activity, body, "权限", "读取中…"); permissions.setContentDescription("文件权限");
                    owner = row(activity, body, "所有者", "读取中…"); owner.setContentDescription("所有者与用户组");
                    group = row(activity, body, "用户组", "读取中…");
                    permissions.setMinHeight(dp(activity,48)); owner.setMinHeight(dp(activity,48)); group.setMinHeight(dp(activity,48)); modified.setMinHeight(dp(activity,48));
                    permissions.setOnClickListener(v -> editPermissions());
                    owner.setOnClickListener(v -> editOwner()); group.setOnClickListener(v -> editOwner());
                    modified.setOnClickListener(v -> editTime());
                }
            }
            status = Ui.text(activity, "正在统计…", 12, Ui.MUTED); status.setContentDescription("属性统计状态");
            status.setPadding(0, dp(activity, 8), 0, dp(activity, 4)); body.addView(status);
            LinearLayout actions = Ui.row(activity);
            stop = button(activity, "停止统计"); errors = button(activity, "查看未统计项"); errors.setVisibility(View.GONE);
            actions.addView(stop, new LinearLayout.LayoutParams(0, -2, 1));
            actions.addView(errors, new LinearLayout.LayoutParams(0, -2, 1)); body.addView(actions);
            stop.setOnClickListener(v -> {
                if (scanFinished) { refresh(); return; }
                if (scan == null) return;
                stopping = true;
                scan.cancel(); cancel(statistics);
                FileStatistics.Snapshot last = scan.snapshot();
                render(new FileStatistics.Snapshot(last.bytes(), last.files(), last.directories(), last.unknownFiles(),
                        last.skippedLinks(), last.duplicateNodes(), last.errorCount(), last.current(), true, true));
            });
            errors.setOnClickListener(v -> {
                if (result == null) return;
                StringBuilder text = new StringBuilder();
                for (FileStatistics.Issue issue : result.errors()) text.append(issue.path()).append('\n').append(issue.message()).append("\n\n");
                if (result.snapshot().errorCount() > result.errors().size()) text.append("仅显示前 ").append(result.errors().size()).append(" 条错误");
                message(activity, "未统计项", text.toString());
            });
            AlertDialog.Builder builder = MaterialDialogs.builder(activity).setTitle("属性").setView(scroll(activity, body))
                    .setNeutralButton("更多", null).setPositiveButton("关闭", null);
            if (single && !first.directory) builder.setNegativeButton("校验", null);
            dialog = builder.create();
            bind(activity, dialog, () -> {
                generation++;
                if (scan != null) scan.cancel();
                cancel(statistics); cancel(metadata);
            });
            dialog.setOnShowListener(ignored -> {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> more());
                if (single && !first.directory) {
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(!editable);
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> showChecksums(activity, first));
                }
                refresh();
            });
            showDialog(dialog); return dialog;
        }
        boolean visible(int current) { return current == generation && dialog.isShowing() && alive(activity); }
        void refresh() { refresh(true); }
        void refresh(boolean measure) {
            int current = measure ? ++generation : generation;
            cancel(metadata);
            if (measure) {
            if (scan != null) scan.cancel(); cancel(statistics);
            stopping = false; scanFinished = false; stop.setText("停止统计");
            result = null; stop.setVisibility(View.VISIBLE); stop.setEnabled(true); errors.setVisibility(View.GONE);
            scan = new FileStatistics.Scan(); FileStatistics.Scan running = scan;
            statistics = Tasks.SHELL.submit(() -> {
                try {
                    FileStatistics.Result calculated = running.run(new FileStore(activity.getApplicationContext()), nodes,
                            snapshot -> activity.runOnUiThread(() -> { if (visible(current) && (!stopping || snapshot.finished())) render(snapshot); }));
                    activity.runOnUiThread(() -> {
                        if (!visible(current)) return;
                        result = calculated; render(calculated.snapshot());
                        errors.setVisibility(calculated.errors().isEmpty() ? View.GONE : View.VISIBLE);
                    });
                } catch (Exception error) { activity.runOnUiThread(() -> {
                    if (visible(current)) { size.setText("未知"); status.setText("统计失败：" + error.getMessage()); stop.setVisibility(View.GONE); }
                }); }
            });
            }
            if (!single || !editable) return;
            metadata = Tasks.SHELL.submit(() -> {
                try {
                    FileAttributes.State read = FileAttributes.read(first);
                    String link = read.link() ? (read.root() ? RootDirectoryReader.readLink(first.path()) : android.system.Os.readlink(first.path())) : null;
                    java.io.File target = link == null ? null : new java.io.File(link);
                    if (target != null && !target.isAbsolute()) target = new java.io.File(first.file.getParentFile(), link);
                    final String linkPath = target == null ? null : target.toPath().normalize().toString();
                    boolean exists = false;
                    if (target != null) try { if (read.root()) RootDirectoryReader.stat(linkPath, "%f", true); else android.system.Os.stat(linkPath); exists = true; } catch (Exception ignored) { }
                    final boolean targetExists = exists;
                    activity.runOnUiThread(() -> {
                        if (!visible(current)) return;
                        state = read; modified.setText(date(read.modified()));
                        permissions.setText((read.link() ? "l" : first.directory ? "d" : "-")
                                + FilePermissions.symbolic(read.mode()) + " (" + FilePermissions.octal(read.mode()) + ")");
                        owner.setText("UID " + read.uid()); group.setText("GID " + read.gid());
                        if (!first.directory) dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(!read.link());
                        if (read.link()) {
                            permissions.setEnabled(false); owner.setEnabled(false); group.setEnabled(false); modified.setEnabled(false);
                            if (body.findViewWithTag("link-target") == null) {
                                TextView targetView = row(activity, body, "链接目标", linkPath + (targetExists ? "" : "（不存在或无法访问）"));
                                targetView.setTag("link-target"); targetView.setMinHeight(dp(activity,48));
                                targetView.setOnClickListener(v -> {
                                    if (targetExists) activity.startActivity(new android.content.Intent(activity, MainActivity.class).putExtra("navigatePath", linkPath));
                                    else message(activity, "链接目标", linkPath + "\n不存在或无法访问");
                                });
                            }
                            if (dialog.getButton(AlertDialog.BUTTON_NEGATIVE) != null) dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
                        }
                    });
                } catch (Exception error) { activity.runOnUiThread(() -> {
                    if (visible(current)) { permissions.setText("无法读取"); owner.setText("未知"); group.setText("未知"); }
                }); }
            });
        }
        void render(FileStatistics.Snapshot value) {
            String prefix = value.complete() ? "" : value.finished() ? "已统计 " : "计算中 ";
            size.setText(prefix + exactSize(value.bytes()));
            if (files != null) files.setText(Long.toString(value.files()));
            if (folders != null) folders.setText(Long.toString(Math.max(0, value.directories() - (single && first.directory ? 1 : 0))));
            List<String> notes = new ArrayList<>();
            if (value.cancelled()) notes.add("已停止，以上为部分结果");
            else if (!value.finished()) notes.add("正在统计…");
            else if (!value.complete()) notes.add("统计未完成，以上为已知大小");
            if (value.unknownFiles() > 0) notes.add(value.unknownFiles() + " 个文件大小未知");
            if (value.errorCount() > 0) notes.add(value.errorCount() + " 项无法读取");
            if (value.skippedLinks() > 0) notes.add("已跳过 " + value.skippedLinks() + " 个符号链接");
            if (value.duplicateNodes() > 0) notes.add("已去重 " + value.duplicateNodes() + " 项");
            if (!single && nodes.stream().anyMatch(n -> n.directory)) notes.add("文件夹数包含所选目录");
            status.setText(String.join(" · ", notes)); status.setVisibility(notes.isEmpty() ? View.GONE : View.VISIBLE);
            scanFinished = value.finished();
            stop.setText(value.finished() ? "重新统计" : "停止统计");
            stop.setVisibility(View.VISIBLE);
        }
        void more() {

            AlertDialog more = MaterialDialogs.builder(activity).setTitle(single ? "修改属性" : "批量修改 · " + nodes.size() + " 项")
                    .setItems(new String[]{"修改时间", "文件权限", "所有者与用户组", "复制属性信息", "重新统计", "批量 SHA256 清单"}, (d, which) -> {
                        if (which == 3) { copy(activity, "属性信息", overviewText()); return; }
                        if (which == 4) { refresh(); return; }
                        if (which == 5) { ChecksumManifest.show(activity, nodes); return; }
                        if (!editable) { message(activity, "属性修改", "此来源未提供可靠的属性修改接口"); return; }
                        if (which == 0) editTime(); else if (which == 1) editPermissions(); else editOwner();
                    }).setNegativeButton("取消", null).create();
            bind(activity, more, () -> { }); showDialog(more);
        }
        String overviewText() {
            StringBuilder text = new StringBuilder();
            for (FileStore.Node node : nodes) text.append(node.name).append("\n").append(node.uri != null ? node.uri : node.path()).append("\n");
            text.append("大小：").append(size.getText());
            if (files != null) text.append("\n文件数：").append(files.getText()).append("\n文件夹数：").append(folders.getText());
            if (state != null) text.append("\n修改时间：").append(date(state.modified())).append("\n权限：").append(FilePermissions.octal(state.mode())).append("\nUID：").append(state.uid()).append("\nGID：").append(state.gid());
            if (status.getVisibility() == View.VISIBLE) text.append("\n").append(status.getText());
            return text.toString();
        }
        CheckBox[] scope(LinearLayout content) {
            CheckBox childrenFiles = check(activity, "同时应用到所有子文件");
            CheckBox childrenDirectories = check(activity, "同时应用到所有子文件夹");
            if (nodes.stream().anyMatch(n -> n.directory)) {
                rule(activity, content); content.addView(childrenFiles); content.addView(childrenDirectories);
            }
            return new CheckBox[]{childrenFiles, childrenDirectories};
        }
        void apply(AlertDialog editor, BatchFileAttributes.Change change, CheckBox[] scope) {
            try { change.validate(); } catch (IOException error) { Tasks.showError(activity, error); return; }
            Runnable update = () -> { changed.run(); if (dialog.isShowing()) refresh(false); };
            if (activity instanceof MainActivity main) {
                editor.dismiss(); main.applyFileAttributes(nodes, change, scope[0].isChecked(), scope[1].isChecked(), update);
            } else {
                editor.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                Tasks.run(activity, Tasks.FILES, () -> BatchFileAttributes.apply(activity.getApplicationContext(), nodes, change,
                        scope[0].isChecked(), scope[1].isChecked()), result -> { editor.dismiss(); update.run(); showResult(activity, result); },
                        error -> { editor.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true); Tasks.showError(activity, error); });
            }
        }
        void editPermissions() {
            if (!editable || state != null && state.link()) return;
            LinearLayout content = body(activity);
            if (!single) content.addView(Ui.text(activity, "为所选 " + nodes.size() + " 项设置统一权限；输入后才会应用。", 13, Ui.MUTED));
            EditText mode = Ui.input(activity, single ? "八进制权限，如 644、755" : "输入统一权限，如 644、755");
            mode.setContentDescription("八进制权限"); mode.setInputType(android.text.InputType.TYPE_CLASS_NUMBER); content.addView(mode);
            TextView preview = Ui.text(activity, "", 13, Ui.MUTED); content.addView(preview);
            int[] bits = {0400,0200,0100,0040,0020,0010,0004,0002,0001,04000,02000,01000};
            CheckBox[] checks = new CheckBox[bits.length];
            for (int row = 0; row < 3; row++) {
                LinearLayout line = Ui.row(activity);
                line.addView(Ui.text(activity, new String[]{"所有者","用户组","其他人"}[row], 13, Ui.TEXT),
                        new LinearLayout.LayoutParams(dp(activity, 60), -2));
                for (int column = 0; column < 3; column++) {
                    int i = row * 3 + column;
                    checks[i] = check(activity, new String[]{"读","写","执行"}[column]);
                    checks[i].setTextSize(12); checks[i].setPadding(0,0,0,0);
                    checks[i].setContentDescription(new String[]{"所有者","用户组","其他人"}[row] + new String[]{"读取","写入","执行"}[column]);
                    line.addView(checks[i], new LinearLayout.LayoutParams(0, -2, 1));
                }
                content.addView(line);
            }
            LinearLayout special = Ui.row(activity);
            for (int i = 9; i < 12; i++) {
                checks[i] = check(activity, new String[]{"设置 UID","设置 GID","粘滞"}[i - 9]); checks[i].setTextSize(12); checks[i].setPadding(0,0,0,0);
                special.addView(checks[i], new LinearLayout.LayoutParams(0, -2, 1));
            }
            content.addView(special); CheckBox[] scope = scope(content);
            AlertDialog editor = MaterialDialogs.builder(activity).setTitle(single ? "文件权限" : "批量修改权限")
                    .setView(scroll(activity, content)).setNegativeButton("取消", null).setPositiveButton("保存权限", null).create();
            boolean[] syncing = {false};
            Runnable validate = () -> {
                boolean valid = false; int value = 0;
                try { value = FilePermissions.parse(mode.getText().toString().trim()); valid = true; mode.setError(null); }
                catch (IOException e) { if (mode.length() > 0) mode.setError(e.getMessage()); }
                syncing[0] = true;
                for (int i = 0; i < checks.length; i++) { checks[i].setChecked(valid && (value & bits[i]) != 0); checks[i].setEnabled(valid); }
                syncing[0] = false;
                preview.setText(valid ? FilePermissions.symbolic(value) : "请输入权限值"); editor.getButton(-1).setEnabled(valid);
            };
            watch(mode, () -> { if (editor.isShowing()) validate.run(); });
            for (CheckBox check : checks) check.setOnCheckedChangeListener((button, checked) -> {
                if (syncing[0]) return;
                int value = 0; for (int i = 0; i < checks.length; i++) if (checks[i].isChecked()) value |= bits[i];
                mode.setText(FilePermissions.octal(value));
            });
            bind(activity, editor, () -> { });
            editor.setOnShowListener(d -> {
                if (single && state != null) mode.setText(FilePermissions.octal(state.mode()));
                validate.run();
                if (single && state == null) Tasks.run(activity, Tasks.SHELL, () -> FileAttributes.read(first), read -> {
                    if (!editor.isShowing()) return; state = read;
                    if (mode.length() == 0 && !read.link()) mode.setText(FilePermissions.octal(read.mode()));
                }, error -> preview.setText("无法读取现有权限，请手动输入"));
                if (!single) Tasks.run(activity, Tasks.SHELL, () -> {
                    Integer common = null;
                    for (FileStore.Node node : nodes) {
                        int value = FileAttributes.read(node).mode();
                        if (common != null && common != value) return "混合权限";
                        common = value;
                    }
                    return common == null ? "未知" : FilePermissions.octal(common);
                }, value -> { if (editor.isShowing()) mode.setHint("当前：" + value + "；输入后应用"); },
                   error -> { if (editor.isShowing()) mode.setHint("部分权限无法读取；输入后应用"); });
                editor.getButton(-1).setOnClickListener(v -> {
                    try { apply(editor, new BatchFileAttributes.Change(FilePermissions.parse(mode.getText().toString().trim()), null,null,null,null), scope); }
                    catch (IOException e) { mode.setError(e.getMessage()); }
                });
            }); showDialog(editor);
        }
        void editTime() {
            if (!editable || state != null && state.link()) return;
            LinearLayout content = body(activity);
            content.addView(Ui.text(activity, "修改时间", 13, Ui.MUTED));
            EditText input = Ui.input(activity, "yyyy-MM-dd HH:mm:ss"); input.setContentDescription("修改时间输入"); content.addView(input);
            if (single && (state != null || first.modified > 0)) input.setText(date(state != null ? state.modified() : first.modified));
            CheckBox[] scope = scope(content);
            AlertDialog editor = MaterialDialogs.builder(activity).setTitle(single ? "修改时间" : "批量修改时间")
                    .setView(scroll(activity, content)).setNeutralButton("当前时间", null)
                    .setNegativeButton("取消", null).setPositiveButton("确定", null).create();
            bind(activity, editor, () -> { });
            editor.setOnShowListener(d -> {
                if (single && state == null) {
                    editor.getButton(-1).setEnabled(false);
                    Tasks.run(activity, Tasks.SHELL, () -> FileAttributes.read(first), read -> {
                        if (!editor.isShowing()) return;
                        state = read; input.setText(date(read.modified())); editor.getButton(-1).setEnabled(!read.link());
                    }, error -> { if (editor.isShowing()) { input.setError("无法读取原时间，请确认输入的新时间"); editor.getButton(-1).setEnabled(true); } });
                }
                editor.getButton(-3).setOnClickListener(v -> input.setText(date(System.currentTimeMillis())));
                editor.getButton(-1).setOnClickListener(v -> {
                    try { apply(editor, new BatchFileAttributes.Change(null,null,null,parseDate(input.getText().toString()),null), scope); }
                    catch (IOException e) { input.setError(e.getMessage()); }
                });
            }); showDialog(editor);
        }
        void editOwner() {
            if (!editable || state != null && state.link()) return;
            LinearLayout content = body(activity);
            content.addView(Ui.text(activity, "输入数字 ID；留空的项目保持原值。", 13, Ui.MUTED));
            EditText uid = Ui.input(activity, "所有者 UID"); uid.setContentDescription("所有者 UID");
            EditText gid = Ui.input(activity, "用户组 GID"); gid.setContentDescription("用户组 GID");
            uid.setInputType(android.text.InputType.TYPE_CLASS_NUMBER); gid.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            content.addView(uid); content.addView(gid);
            if (single && state != null) {
                uid.setHint("所有者 UID（当前 " + state.uid() + "）"); gid.setHint("用户组 GID（当前 " + state.gid() + "）");
            }
            CheckBox[] scope = scope(content);
            AlertDialog editor = MaterialDialogs.builder(activity).setTitle(single ? "所有者与用户组" : "批量修改所有者与用户组")
                    .setView(scroll(activity, content)).setNegativeButton("取消", null).setPositiveButton("确定", null).create();
            bind(activity, editor, () -> { });
            editor.setOnShowListener(d -> editor.getButton(-1).setOnClickListener(v -> {
                try { apply(editor, new BatchFileAttributes.Change(null, id(uid), id(gid),null,null), scope); }
                catch (IOException e) { Tasks.showError(activity, e); }
            })); showDialog(editor);
        }
    }

    static AlertDialog showChecksums(Activity activity, FileStore.Node node) {
        LinearLayout body = body(activity);
        TextView hashes = Ui.text(activity, "正在计算 MD5 / SHA-1 / SHA-256 / CRC32…", 13, Ui.TEXT);
        hashes.setTextIsSelectable(true); hashes.setContentDescription("文件摘要值，可长按复制"); body.addView(hashes);
        TextView progress = Ui.text(activity, "已读取 0 字节", 12, Ui.MUTED); progress.setPadding(0,dp(activity,8),0,dp(activity,8)); body.addView(progress);
        EditText input = Ui.input(activity, "输入 MD5、SHA-1、SHA-256 或 CRC32"); input.setContentDescription("输入校验值"); body.addView(input);
        TextView comparison = Ui.text(activity, "", 13, Ui.ACCENT); comparison.setMinHeight(dp(activity, 40)); body.addView(comparison);
        PropertyChecksums.Values[] values = {null}; boolean[] uppercase = {false};
        PropertyChecksums.Scan scan = new PropertyChecksums.Scan(); Future<?>[] job = {null};
        AlertDialog dialog = MaterialDialogs.builder(activity).setTitle("校验 · " + node.name).setView(scroll(activity, body))
                .setNeutralButton("粘贴校验", null).setNegativeButton("大小写", null).setPositiveButton("关闭", null).create();
        Runnable render = () -> {
            if (values[0] == null) return;
            PropertyChecksums.Values value = values[0];
            String text = "MD5\n" + value.md5() + "\n\nSHA-1\n" + value.sha1() + "\n\nSHA-256\n" + value.sha256() + "\n\nCRC32\n" + value.crc32();
            hashes.setText(uppercase[0] ? text.toUpperCase(Locale.ROOT) : text);
            comparison.setText(input.length() == 0 ? "输入或粘贴校验值进行比对" : PropertyChecksums.compare(input.getText().toString(), value));
        };
        watch(input, render);
        bind(activity, dialog, () -> { scan.cancel(); cancel(job[0]); if (Tasks.INTEGRITY instanceof java.util.concurrent.ThreadPoolExecutor pool) pool.purge(); });
        dialog.setOnShowListener(d -> {
            dialog.getButton(-3).setOnClickListener(v -> {
                ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard.hasPrimaryClip() && clipboard.getPrimaryClip().getItemCount() > 0) {
                    CharSequence text = clipboard.getPrimaryClip().getItemAt(0).getText();
                    if (text != null) input.setText(text); else Tasks.toast(activity, "剪贴板中没有文本");
                }
            });
            dialog.getButton(-2).setOnClickListener(v -> { uppercase[0] = !uppercase[0]; render.run(); });
            job[0] = Tasks.INTEGRITY.submit(() -> {
                try {
                    PropertyChecksums.Values calculated = scan.calculate(new FileStore(activity.getApplicationContext()), node,
                            bytes -> activity.runOnUiThread(() -> { if (dialog.isShowing() && alive(activity)) progress.setText("已读取 " + exactSize(bytes)); }));
                    activity.runOnUiThread(() -> {
                        if (!dialog.isShowing() || !alive(activity)) return;
                        values[0] = calculated; progress.setText("计算完成 · " + exactSize(calculated.bytes())); render.run();
                    });
                } catch (Exception error) { activity.runOnUiThread(() -> {
                    if (dialog.isShowing() && alive(activity)) { hashes.setText("校验失败：" + error.getMessage()); progress.setText(""); }
                }); }
            });
        }); showDialog(dialog); return dialog;
    }

    static void showResult(Activity activity, BatchFileAttributes.Result result) { showResult(activity,result,null); }
    static void showResult(Activity activity, BatchFileAttributes.Result result, Runnable retry) {
        if (result.failed() == 0 && !result.cancelled()) { Tasks.toast(activity, result.summary()); return; }
        StringBuilder detail = new StringBuilder(result.summary()); int shown = 0;
        for (BatchFileAttributes.ItemResult item : result.items()) if (!item.errors().isEmpty()) {
            if (shown++ >= 100) { detail.append("\n\n仅显示前 100 项错误"); break; }
            detail.append("\n\n").append(item.node().path()).append('\n').append(String.join("\n", item.errors()));
        }
        AlertDialog.Builder builder = MaterialDialogs.builder(activity).setTitle("属性修改结果").setMessage(detail.toString())
                .setNegativeButton("关闭", null).setNeutralButton("复制错误详情", (d,w) -> copy(activity,"错误详情",detail.toString()));
        if (retry != null) builder.setPositiveButton("重试未完成项", (d,w) -> retry.run());
        AlertDialog report = builder.create(); bind(activity, report, () -> { }); showDialog(report);
    }
    static String exactSize(long bytes) { return FileStore.size(bytes) + " (" + bytes + " 字节)"; }
    static long parseDate(String text) throws IOException {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT); format.setLenient(false);
        ParsePosition position = new ParsePosition(0); String input = text.trim(); Date date = format.parse(input, position);
        if (date == null || position.getIndex() != input.length() || !format.format(date).equals(input) || date.getTime() < 0)
            throw new IOException("请输入有效时间，格式 yyyy-MM-dd HH:mm:ss");
        return date.getTime();
    }
    private static Long id(EditText input) throws IOException {
        String text = input.getText().toString().trim(); if (text.isEmpty()) return null;
        try { long value = Long.parseLong(text); if (value < 0 || value > Integer.MAX_VALUE) throw new NumberFormatException(); return value; }
        catch (NumberFormatException error) { throw new IOException("UID/GID 必须为 0–2147483647 的整数"); }
    }
    private static String parent(FileStore.Node node) {
        if (node.uri != null) {
            try {
                String id = android.provider.DocumentsContract.getDocumentId(node.uri);
                if ("com.android.externalstorage.documents".equals(node.uri.getAuthority())) {
                    int colon = id.indexOf(':');
                    if (colon >= 0) {
                        String relative = id.substring(colon + 1);
                        int slash = relative.lastIndexOf('/');
                        String parent = slash < 0 ? "" : relative.substring(0, slash);
                        return (id.substring(0, colon).equals("primary") ? "内部存储" : "外部存储 " + id.substring(0, colon)) + "/" + parent;
                    }
                }
                return "系统授权目录 · " + node.uri.getAuthority() + "\n" + id;
            } catch (IllegalArgumentException ignored) { return "系统授权目录 · " + node.name; }
        }
        String path = node.path(); int slash = path.lastIndexOf('/'); return slash < 0 ? path : path.substring(0, slash + 1);
    }
    private static String date(long time) { return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date(time)); }
    private static int dp(Activity activity, int value) { return Ui.dp(activity, value); }
    private static boolean alive(Activity activity) { return !activity.isFinishing() && !activity.isDestroyed(); }
    private static LinearLayout body(Activity activity) { LinearLayout body = Ui.column(activity); body.setPadding(dp(activity,24), dp(activity,8), dp(activity,24), dp(activity,8)); return body; }
    private static ScrollView scroll(Activity activity, LinearLayout body) { ScrollView scroll = new ScrollView(activity); scroll.addView(body); return scroll; }
    private static TextView row(Activity activity, LinearLayout body, String label, String value) {
        LinearLayout line = Ui.row(activity); line.setGravity(Gravity.TOP);
        TextView name = Ui.text(activity, label, 14, Ui.MUTED); name.setMinHeight(dp(activity, 48));
        TextView text = Ui.text(activity, value, 14, Ui.TEXT); text.setMinHeight(dp(activity, 48)); text.setPadding(0,dp(activity,6),0,dp(activity,6));
        text.setContentDescription("属性" + label);
        text.setOnLongClickListener(v -> {
            ((ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText(label, text.getText()));
            Tasks.toast(activity, "已复制" + label); return true;
        });
        line.addView(name, new LinearLayout.LayoutParams(dp(activity, 72), -2)); line.addView(text, new LinearLayout.LayoutParams(0, -2, 1)); body.addView(line);
        return text;
    }
    private static void rule(Activity activity, LinearLayout body) { View line = new View(activity); line.setBackgroundColor(Ui.LINE); body.addView(line, new LinearLayout.LayoutParams(-1, dp(activity,1))); }
    private static Button button(Activity activity, String title) { Button button = new Button(activity); button.setText(title); button.setTextColor(Ui.ACCENT); button.setTextSize(13); button.setMinHeight(dp(activity,48)); return button; }
    private static CheckBox check(Activity activity, String title) { CheckBox check = new CheckBox(activity); check.setText(title); check.setTextColor(Ui.TEXT); check.setTextSize(14); check.setMinHeight(dp(activity,48)); check.setMinWidth(dp(activity,48)); return check; }
    private static void watch(EditText input, Runnable changed) { input.addTextChangedListener(new TextWatcher() {
        public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
        public void onTextChanged(CharSequence s, int start, int before, int count) { }
        public void afterTextChanged(Editable s) { changed.run(); }
    }); }
    private static void cancel(Future<?> future) { if (future != null) future.cancel(true); }
    private static void showDialog(AlertDialog dialog) {
        dialog.show();
        if (dialog.getWindow() != null) dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }
    private static void bind(Activity activity, AlertDialog dialog, Runnable closed) {
        androidx.lifecycle.LifecycleEventObserver observer = (owner, event) -> {
            if (event == androidx.lifecycle.Lifecycle.Event.ON_DESTROY) dialog.dismiss();
        };
        if (activity instanceof androidx.lifecycle.LifecycleOwner owner) owner.getLifecycle().addObserver(observer);
        dialog.setOnDismissListener(d -> {
            closed.run();
            if (activity instanceof androidx.lifecycle.LifecycleOwner owner) owner.getLifecycle().removeObserver(observer);
        });
    }
    private static void copy(Activity activity, String label, String text) {
        ((ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText(label, text));
        Tasks.toast(activity, "已复制" + label);
    }
    private static void message(Activity activity, String title, String text) {
        TextView content = Ui.text(activity, text, 14, Ui.TEXT); content.setTextIsSelectable(true);
        LinearLayout body = body(activity); body.addView(content);
        AlertDialog dialog = MaterialDialogs.builder(activity).setTitle(title).setView(scroll(activity, body)).setNeutralButton("复制", (d,w) -> copy(activity,title,text)).setPositiveButton("关闭", null).create();
        bind(activity, dialog, () -> { }); showDialog(dialog);
    }
    private FileProperties() { }
}
