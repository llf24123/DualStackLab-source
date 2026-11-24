package org.example.filemanager;

import android.app.*;
import android.content.*;
import android.os.*;
import android.text.InputFilter;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.*;

/** Pasted paths identify selected content; they are never opened. */
final class ChecksumManifest {
    private static final int MAX_FILES = 100_000, MAX_ERRORS = 100, PREVIEW_LENGTH = 16_000, MAX_INPUT = 1_000_000;
    private static final int MAX_MANIFEST_CHARACTERS = 8 * 1024 * 1024;
    private static final Pattern GNU_LINE = Pattern.compile("^([0-9a-fA-F]{64}) [ *](.+)$");
    private static final Pattern BSD_LINE = Pattern.compile("^SHA256 \\((.+)\\) = ([0-9a-fA-F]{64})$", Pattern.CASE_INSENSITIVE);
    record Item(FileStore.Node node, String name) { }
    record Completed(Item item, String version, long bytes) { }
    record Result(Map<String, String> hashes, List<Item> remaining, List<String> errors,
                  long errorCount, long bytes, long ignored, boolean cancelled, boolean enumerated,
                  String manifest, Map<String, Completed> completedItems) { }
    record Comparison(String summary, List<String> details) {
        String display() { return summary + (details.isEmpty() ? "" : "\n\n" + String.join("\n", details)); }
    }
    static final class Scan {
        private final AtomicBoolean started = new AtomicBoolean(), cancelled = new AtomicBoolean();
        private final FileStatistics.Scan listing = new FileStatistics.Scan();
        private volatile PropertyChecksums.Scan active;
        volatile String current = "读取所选目录";
        volatile long completed, total, bytes, currentBytes;
        private long ignored;
        private final Map<String, Completed> completedItems = new LinkedHashMap<>();
        void cancel() { cancelled.set(true); listing.cancel(); PropertyChecksums.Scan hash = active; if (hash != null) hash.cancel(); }
        private void check() throws Tasks.CancelledException {
            if (cancelled.get()) throw new Tasks.CancelledException(); Tasks.Progress.check();
        }
        Result run(FileStore store, List<FileStore.Node> nodes) throws IOException { return run(store, nodes, null); }
        Result run(FileStore store, List<FileStore.Node> nodes, Result retry) throws IOException {
            if (!started.compareAndSet(false, true)) throw new IllegalStateException("清单任务不能重复运行");
            Map<String, String> hashes = new LinkedHashMap<>();
            List<String> errors = new ArrayList<>(); long errorCount = 0;
            List<Item> items; boolean enumerated;
            if (retry != null && retry.enumerated) {
                hashes.putAll(retry.hashes);
                completedItems.putAll(retry.completedItems);
                bytes = retry.bytes;
                ignored = retry.ignored;
                items = new ArrayList<>(retry.remaining);
                for (Completed done : retry.completedItems.values()) {
                    if (cancelled.get() || Thread.currentThread().isInterrupted()) {
                        cancelled.set(true);
                        hashes.clear();
                        completedItems.clear();
                        bytes = 0;
                        return result(hashes, items, errors, errorCount, false);
                    }
                    String version;
                    try { version = store.identity(done.item.node); }
                    catch (IOException | RuntimeException missing) { version = null; }
                    if (!done.version.equals(version)) {
                        hashes.remove(done.item.name);
                        completedItems.remove(done.item.name);
                        bytes -= done.bytes;
                        items.add(done.item);
                    }
                }
                enumerated = true;
            } else {
                Map<String, Item> candidates = new LinkedHashMap<>();
                Set<String> ambiguous = new HashSet<>(); boolean[] limit = {false}; long[] characters = {0};
                FileStatistics.Result found = listing.run(store, nodes, state -> current = "读取目录 · " + state.current(), visit -> {
                    FileStatistics.Entry entry = visit.entry();
                    if (entry.kind() != FileStatistics.Kind.FILE) return;
                    String name = visit.relativePath();
                    if (ambiguous.contains(name)) return;
                    if (candidates.containsKey(name)) { candidates.remove(name); ambiguous.add(name); return; }
                    if (candidates.size() >= MAX_FILES || characters[0] + name.length() + 67 > MAX_MANIFEST_CHARACTERS) {
                        limit[0] = true; return;
                    }
                    characters[0] += name.length() + 67;
                    candidates.put(name, new Item(entry.node(), name));
                });
                enumerated = found.snapshot().complete();
                ignored = found.snapshot().skippedLinks() + found.snapshot().duplicateNodes();
                for (FileStatistics.Issue issue : found.errors()) addError(errors, issue.path() + "：" + issue.message());
                errorCount = found.snapshot().errorCount();
                for (String name : ambiguous) { addError(errors, name + "：所选目录产生重复相对路径"); errorCount++; }
                if (limit[0]) { addError(errors, "文件超过 100000 项或清单超过 8 Mi 字符，清单不完整，请分批计算"); errorCount++; enumerated = false; }
                if (!ambiguous.isEmpty()) enumerated = false;
                items = new ArrayList<>(candidates.values());
                if (found.snapshot().cancelled()) {
                    cancelled.set(true); return result(hashes, items, errors, errorCount, false);
                }
            }
            List<Item> remaining = new ArrayList<>(); total = items.size(); completed = 0;
            int index = 0;
            try {
                for (; index < items.size(); index++) {
                    check(); Item item = items.get(index); current = item.name; currentBytes = 0;
                    try {
                        if (item.name.indexOf('\n') >= 0 || item.name.indexOf('\r') >= 0)
                            throw new IOException("名称含换行，不能写入逐行校验清单");
                        String version = store.identity(item.node);
                        PropertyChecksums.Scan hash = new PropertyChecksums.Scan(); active = hash; check();
                        PropertyChecksums.Values value = hash.calculateSha256(store, item.node, read -> currentBytes = read);
                        check();
                        if (!version.equals(store.identity(item.node))) throw new IOException("文件在校验期间已变化");
                        bytes = Math.addExact(bytes, value.bytes());
                        hashes.put(item.name, value.sha256());
                        completedItems.put(item.name, new Completed(item, version, value.bytes()));
                    } catch (Exception error) {
                        check(); remaining.add(item); errorCount++; addError(errors, item.name + "：" + message(error));
                    } finally { active = null; currentBytes = 0; }
                    completed++;
                }
            } catch (Tasks.CancelledException stopped) {
                cancelled.set(true); remaining.addAll(items.subList(index, items.size()));
            }
            return result(hashes, remaining, errors, errorCount, enumerated);
        }
        private Result result(Map<String,String> hashes, List<Item> remaining, List<String> errors, long errorCount, boolean enumerated) {
            StringBuilder output = new StringBuilder();
            hashes.forEach((name, hash) -> output.append(hash).append("  ").append(name).append('\n'));
            current = cancelled.get() ? "已停止" : "计算完成";
            return new Result(Collections.unmodifiableMap(new LinkedHashMap<>(hashes)), List.copyOf(remaining),
                    List.copyOf(errors), errorCount, bytes, ignored, cancelled.get(), enumerated, output.toString(),
                    Map.copyOf(completedItems));
        }
    }
    static void show(Activity activity, List<FileStore.Node> selected) { new Panel(activity, List.copyOf(selected)).show(); }
    private static final class Panel {
        final Activity activity; final List<FileStore.Node> nodes; final Handler handler = new Handler(Looper.getMainLooper());
        AlertDialog dialog; TextView progress, report, comparison; EditText expected; Button stop, share, importFile;
        androidx.activity.result.ActivityResultLauncher<String[]> picker;
        Scan scan; Result result; Future<?> job, comparisonJob; boolean closed; int generation;
        final Runnable tick = new Runnable() { public void run() {
            if (closed || scan == null) return;
            progress.setText("已处理 " + scan.completed + " / " + scan.total + " 项 · 已读取 "
                    + FileStore.size(scan.bytes + scan.currentBytes) + "\n" + scan.current);
            handler.postDelayed(this, 150);
        } };
        Panel(Activity activity, List<FileStore.Node> nodes) { this.activity = activity; this.nodes = nodes; }
        void show() {
            LinearLayout body = Ui.column(activity);
            body.setPadding(Ui.dp(activity,24), Ui.dp(activity,8), Ui.dp(activity,24), Ui.dp(activity,8));
            progress = Ui.text(activity, "准备计算…", 13, Ui.MUTED); progress.setContentDescription("清单计算进度");
            progress.setMaxLines(3); body.addView(progress);
            LinearLayout actions = Ui.row(activity); stop = button("停止计算"); share = button("分享清单文件");
            LinearLayout.LayoutParams stopLayout = new LinearLayout.LayoutParams(0,-2,1); stopLayout.rightMargin = Ui.dp(activity,4);
            LinearLayout.LayoutParams shareLayout = new LinearLayout.LayoutParams(0,-2,1); shareLayout.leftMargin = Ui.dp(activity,4);
            actions.addView(stop, stopLayout); actions.addView(share, shareLayout); body.addView(actions);
            report = Ui.text(activity, "", 13, Ui.TEXT); report.setTypeface(android.graphics.Typeface.MONOSPACE);
            report.setPadding(0,Ui.dp(activity,12),0,Ui.dp(activity,8));
            report.setTextIsSelectable(true); report.setContentDescription("SHA256 清单预览"); body.addView(report);
            expected = Ui.input(activity,"粘贴 SHA256 清单进行比对"); expected.setContentDescription("SHA256 清单输入");
            expected.setSingleLine(false); expected.setMinLines(3); expected.setFilters(new InputFilter[]{new InputFilter.LengthFilter(MAX_INPUT)}); body.addView(expected);
            importFile = button("选择清单文件");
            if (activity instanceof androidx.activity.ComponentActivity owner) {
                picker = owner.getActivityResultRegistry().register("sha256-" + UUID.randomUUID(),
                        new androidx.activity.result.contract.ActivityResultContracts.OpenDocument(), uri -> {
                            if (uri == null || result == null || closed) return;
                            compareOnWorker(hashes -> {
                                InputStream input = activity.getContentResolver().openInputStream(uri);
                                if (input == null) throw new IOException("无法读取清单文件");
                                try (Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                                    return compareReader(reader, hashes, MAX_MANIFEST_CHARACTERS);
                                }
                            });
                        });
                importFile.setOnClickListener(v -> {
                    try { picker.launch(new String[]{"*/*"}); }
                    catch (RuntimeException error) { Tasks.showError(activity, error); }
                });
                body.addView(importFile);
            }
            comparison = Ui.text(activity,"支持所选文件夹递归；清单使用相对路径。",13,Ui.MUTED); comparison.setContentDescription("清单比对结果"); body.addView(comparison);
            ScrollView scroll = new ScrollView(activity); scroll.addView(body);
            dialog = MaterialDialogs.builder(activity).setTitle("批量 SHA256 清单").setView(scroll)
                    .setNeutralButton("复制清单",null).setNegativeButton("比对清单",null).setPositiveButton("关闭",null).create();
            androidx.lifecycle.LifecycleEventObserver observer = (owner,event) -> { if(event == androidx.lifecycle.Lifecycle.Event.ON_DESTROY) dialog.dismiss(); };
            if(activity instanceof androidx.lifecycle.LifecycleOwner owner) owner.getLifecycle().addObserver(observer);
            dialog.setOnDismissListener(d -> {
                closed = true; generation++; handler.removeCallbacksAndMessages(null);
                if (scan != null) scan.cancel(); if (job != null) job.cancel(true); if (comparisonJob != null) comparisonJob.cancel(true);
                if (picker != null) picker.unregister();
                if(activity instanceof androidx.lifecycle.LifecycleOwner owner) owner.getLifecycle().removeObserver(observer);
            });
            dialog.setOnShowListener(d -> {
                dialog.getButton(-3).setOnClickListener(v -> copy()); dialog.getButton(-2).setOnClickListener(v -> compareInput());
                stop.setOnClickListener(v -> {
                    if (scan != null) { scan.cancel(); stop.setEnabled(false); progress.setText("正在停止…"); handler.removeCallbacks(tick); }
                    else start(result != null && result.enumerated && !result.remaining.isEmpty() ? result : null);
                });
                share.setOnClickListener(v -> share()); start(null);
            });
            dialog.show();
            if (dialog.getWindow() != null) dialog.getWindow().setSoftInputMode(
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN | android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        Button button(String text) {
            Button button = new Button(activity); button.setText(text); button.setTextSize(13);
            button.setAllCaps(false); button.setMinHeight(Ui.dp(activity,48)); button.setElevation(0);
            button.setBackground(SettingsDesign.ripple(activity,SettingsDesign.tint(),12));
            button.setTextColor(new android.content.res.ColorStateList(new int[][]{new int[]{android.R.attr.state_enabled},new int[]{}},
                    new int[]{Ui.ACCENT,Ui.MUTED}));
            return button;
        }
        void start(Result retry) {
            int version = ++generation; Scan running = new Scan(); scan = running; result = null;
            report.setText(""); comparison.setText("计算中，可随时停止并保留已完成清单。");
            stop.setText("停止计算"); stop.setEnabled(true); share.setEnabled(false);
            importFile.setEnabled(false);
            dialog.getButton(-3).setEnabled(false); dialog.getButton(-2).setEnabled(false); handler.post(tick);
            job = Tasks.INTEGRITY.submit(() -> {
                try {
                    Result found = running.run(new FileStore(activity.getApplicationContext()), nodes, retry);
                    activity.runOnUiThread(() -> { if (!closed && generation == version) finished(found); });
                } catch (Exception error) { activity.runOnUiThread(() -> {
                    if (closed || generation != version) return;
                    scan = null; handler.removeCallbacks(tick); progress.setText("计算失败：" + message(error));
                    stop.setText("重新计算"); stop.setEnabled(true);
                }); }
            });
        }
        void finished(Result found) {
            result = found; scan = null; handler.removeCallbacks(tick);
            progress.setText((found.cancelled ? "已停止" : found.enumerated ? "计算完成" : "目录未完整读取")
                    + " · 成功 " + found.hashes.size() + " 项 · " + FileStore.size(found.bytes)
                    + "\n未完成 " + found.remaining.size() + " 项 · 错误 " + found.errorCount + " 项"
                    + (found.ignored > 0 ? " · 跳过链接/重复项 " + found.ignored : ""));
            String preview = found.manifest.length() > PREVIEW_LENGTH ? found.manifest.substring(0, PREVIEW_LENGTH)
                    + "\n…预览已省略，分享清单文件可获取完整内容。" : found.manifest;
            if (!found.errors.isEmpty()) preview += "\n未计算项：\n" + String.join("\n", found.errors)
                    + (found.errorCount > found.errors.size() ? "\n仅显示前 " + MAX_ERRORS + " 条错误。" : "");
            report.setText(preview.length() > 32_000 ? preview.substring(0,32_000) + "\n…内容已省略。" : preview);
            stop.setText(found.enumerated && !found.remaining.isEmpty() ? "重试未完成项" : "重新计算"); stop.setEnabled(true);
            boolean hasValues = !found.hashes.isEmpty(); share.setEnabled(hasValues);
            importFile.setEnabled(hasValues);
            dialog.getButton(-3).setEnabled(hasValues); dialog.getButton(-2).setEnabled(hasValues);
            comparison.setText(found.cancelled || !found.remaining.isEmpty() || !found.enumerated
                    ? "当前为部分清单，未完成项不会记为匹配。" : "可粘贴 SHA256 清单进行比对。");
        }
        void compareInput() {
            if (result == null) return;
            String input = expected.getText().toString();
            compareOnWorker(hashes -> compareDetailed(input, hashes));
        }
        private interface ComparisonJob { Comparison run(Map<String, String> hashes) throws IOException; }
        void compareOnWorker(ComparisonJob operation) {
            if (result == null) return;
            Map<String,String> hashes = result.hashes; int version = generation;
            dialog.getButton(-2).setEnabled(false); comparison.setText("正在比对…");
            importFile.setEnabled(false);
            comparisonJob = Tasks.INTEGRITY.submit(() -> {
                String full;
                try { full = operation.run(hashes).display(); }
                catch (IOException | RuntimeException error) { full = "无法比对：" + message(error); }
                String preview = full.length() > 32_000 ? full.substring(0,32_000) + "\n…仅显示部分差异。" : full;
                activity.runOnUiThread(() -> {
                    if (!closed && version == generation && scan == null) {
                        comparison.setText(preview); dialog.getButton(-2).setEnabled(true); importFile.setEnabled(true);
                    }
                });
            });
        }
        void copy() {
            if (result == null) return;
            if (result.manifest.length() > 128_000) { Tasks.toast(activity,"清单较大，请使用分享清单文件"); return; }
            try { ((ClipboardManager)activity.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("SHA256 清单",result.manifest)); Tasks.toast(activity,"已复制清单"); }
            catch (RuntimeException error) { Tasks.toast(activity,"复制失败，请使用分享清单文件"); }
        }
        void share() {
            if (result == null) return;
            String text = result.manifest; share.setEnabled(false);
            Tasks.run(activity, Tasks.IO, () -> {
                File temporary = File.createTempFile("checksums-", ".sha256", activity.getCacheDir());
                try {
                    Files.write(temporary.toPath(), text.getBytes(StandardCharsets.UTF_8));
                    File staged = ApkInstall.stageShared(activity, FileStore.local(temporary), ".sha256");
                    return androidx.core.content.FileProvider.getUriForFile(activity, activity.getPackageName()+".files", staged);
                } finally { temporary.delete(); }
            }, uri -> {
                if (closed) return; share.setEnabled(true);
                Intent intent = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM,uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                intent.setClipData(ClipData.newRawUri("SHA256 清单",uri));
                try { activity.startActivity(Intent.createChooser(intent,"分享 SHA256 清单")); }
                catch (Exception error) { Tasks.showError(activity,error); }
            }, error -> { if (!closed) { share.setEnabled(true); Tasks.showError(activity,error); } });
        }
    }
    static String compare(String input, Map<String,String> values) { return compareDetailed(input, values).summary; }
    static Comparison compareDetailed(String input, Map<String,String> values) {
        if (input == null) input = "";
        if (input.length() > MAX_INPUT) return new Comparison("清单超过 1000000 字符，请分批比对",List.of());
        try { return compareReader(new StringReader(input), values, MAX_INPUT); }
        catch (IOException error) { return new Comparison(message(error), List.of()); }
    }
    static Comparison compareReader(Reader input, Map<String,String> values, int limit) throws IOException {
        Set<String> seen = new HashSet<>();
        long matched=0, failed=0, missing=0, invalid=0;
        List<String> details = new ArrayList<>();
        int[] remaining = {limit};
        try (BufferedReader reader = new BufferedReader(new FilterReader(input) {
            @Override public int read(char[] buffer, int offset, int length) throws IOException {
                Tasks.Progress.check();
                int read = super.read(buffer, offset, Math.min(length, remaining[0] + 1));
                if (read > remaining[0]) throw new IOException("清单超过最大字符数，请分批比对");
                if (read > 0) remaining[0] -= read;
                return read;
            }
        })) {
            String line; int number=0;
            while ((line = reader.readLine()) != null) {
                if (Thread.currentThread().isInterrupted()) return new Comparison("已停止比对",List.copyOf(details));
                if (++number == 1 && line.startsWith("\ufeff")) line = line.substring(1);
                if (line.isBlank()) continue;
                Matcher gnu = GNU_LINE.matcher(line), bsd = BSD_LINE.matcher(line); String hash, name;
                if (gnu.matches()) { hash = gnu.group(1); name = gnu.group(2); }
                else if (bsd.matches()) { hash = bsd.group(2); name = bsd.group(1); }
                else { invalid++; addError(details,"第 " + number + " 行：格式无效"); continue; }
                if (name.startsWith("./")) name = name.substring(2);
                if (!seen.add(name)) { invalid++; addError(details,"第 " + number + " 行：重复路径 " + name); continue; }
                String actual = values.get(name);
                if (actual == null) { missing++; addError(details,"未计算：" + name); }
                else if (actual.equalsIgnoreCase(hash)) matched++;
                else { failed++; addError(details,"不匹配：" + name + "\n预期 " + hash + "\n实际 " + actual); }
            }
        }
        return new Comparison("匹配 " + matched + "，不匹配 " + failed + "，未计算 " + missing + "，无效行 " + invalid
                + "，清单未包含 " + values.keySet().stream().filter(name -> !seen.contains(name)).count(),List.copyOf(details));
    }
    private static void addError(List<String> errors, String message) {
        if (errors.size() < MAX_ERRORS) errors.add(message.length() > 2048 ? message.substring(0,2048) + "…" : message);
    }
    private static String message(Exception error) { return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(); }
    private ChecksumManifest() { }
}
