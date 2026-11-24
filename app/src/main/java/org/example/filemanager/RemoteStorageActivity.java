package org.example.filemanager;

import android.app.AlertDialog;
import android.content.*;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.Future;

/** Browse a remote share and move data through the Android file picker. */
public final class RemoteStorageActivity extends LabActivity {
    private RemoteStorage remote;
    private List<String> path = List.of();
    private List<RemoteStorage.Entry> entries = List.of();
    private List<String> downloadPath;
    private LinearLayout form;
    private TextView location;
    private ListView list;
    private Button parent, upload, disconnect;
    private EditText endpoint, account, password, fingerprint;
    private boolean busy;
    private Future<?> work;
    private int generation;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout body = Ui.column(this);
        body.addView(Ui.toolbar(this, "网络存储", this::finish));
        form = Ui.column(this);
        int pad = Ui.dp(this, 16);
        form.setPadding(pad, pad, pad, pad);
        endpoint = Ui.input(this, "https://、smb:// 或 sftp:// 地址");
        endpoint.setSingleLine();
        endpoint.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        account = Ui.input(this, "用户名");
        account.setSingleLine();
        password = Ui.input(this, "密码");
        password.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        fingerprint = Ui.input(this, "SFTP 主机指纹（SHA256:…）");
        fingerprint.setSingleLine();
        form.addView(endpoint);
        form.addView(account);
        form.addView(password);
        form.addView(fingerprint);
        Button connect = button("连接", this::connect);
        form.addView(connect);
        var settings = getPreferences(MODE_PRIVATE);
        endpoint.setText(settings.getString("endpoint", ""));
        account.setText(settings.getString("account", ""));
        fingerprint.setText(settings.getString("fingerprint", ""));
        MainActivity.watch(endpoint, this::updateForm);
        updateForm();
        ScrollView formScroll = new ScrollView(this);
        formScroll.addView(form);
        body.addView(formScroll);
        location = Ui.text(this, "", 14, Ui.TEXT);
        location.setPadding(pad, Ui.dp(this, 8), pad, Ui.dp(this, 8));
        body.addView(location);
        LinearLayout commands = Ui.row(this);
        parent = button("上一级", () -> browse(path.subList(0, path.size() - 1)));
        upload = button("上传文件", () -> openPicker(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), 1));
        disconnect = button("断开", this::disconnect);
        for (Button control : List.of(parent, upload, disconnect)) commands.addView(control, new LinearLayout.LayoutParams(0, -2, 1));
        body.addView(commands);
        list = new ListView(this);
        list.setContentDescription("远程文件列表");
        list.setOnItemClickListener((container, row, index, id) -> open(entries.get(index)));
        body.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        Ui.install(this, body);
        refresh();
    }

    private Button button(String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setMinHeight(Ui.dp(this, 48));
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private void updateForm() {
        fingerprint.setVisibility(endpoint.getText().toString().trim().toLowerCase(Locale.ROOT).startsWith("sftp://")
                ? View.VISIBLE : View.GONE);
    }

    private void refresh() {
        boolean connected = remote != null;
        ((View) form.getParent()).setVisibility(connected ? View.GONE : View.VISIBLE);
        location.setText(busy ? (connected ? "正在读取或传输…" : "正在连接…") : connected ? "/" + String.join("/", path) : "");
        ((View) parent.getParent()).setVisibility(connected ? View.VISIBLE : View.GONE);
        list.setVisibility(connected ? View.VISIBLE : View.GONE);
        list.setEnabled(!busy);
        parent.setEnabled(connected && !busy && !path.isEmpty());
        upload.setEnabled(connected && !busy);
        disconnect.setText(busy ? "停止并断开" : "断开");
        list.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1,
                entries.stream().map(entry -> (entry.directory() ? "目录 · " : "") + entry.name()
                        + (!entry.directory() && entry.size() >= 0 ? "   " + FileStore.size(entry.size()) : "")).toArray(String[]::new)));
    }

    private void connect() {
        if (busy || remote != null) return;
        String address = endpoint.getText().toString().trim(), user = account.getText().toString();
        String secret = password.getText().toString(), hostKey = fingerprint.getText().toString();
        int attempt = ++generation;
        busy = true;
        refresh();
        work = Tasks.IO.submit(() -> {
            RemoteStorage connected = null;
            try {
                connected = RemoteStorage.connect(address, user, secret, hostKey);
                List<RemoteStorage.Entry> initial = connected.list(List.of());
                RemoteStorage ready = connected;
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || attempt != generation) {
                        Tasks.CONTROL.execute(() -> close(ready));
                        return;
                    }
                    remote = ready;
                    entries = initial;
                    busy = false;
                    password.setText("");
                    getPreferences(MODE_PRIVATE).edit().putString("endpoint", address)
                            .putString("account", user).putString("fingerprint", hostKey).apply();
                    refresh();
                });
            } catch (Exception error) {
                close(connected);
                runOnUiThread(() -> error(attempt, error));
            }
        });
    }

    private <T> void perform(Tasks.Job<T> job, Tasks.Done<T> done) {
        int attempt = generation;
        busy = true;
        refresh();
        work = Tasks.IO.submit(() -> {
            try {
                T result = job.run();
                runOnUiThread(() -> {
                    if (attempt != generation || isFinishing() || isDestroyed()) return;
                    busy = false;
                    done.accept(result);
                    refresh();
                });
            } catch (Exception error) { runOnUiThread(() -> error(attempt, error)); }
        });
    }

    private void error(int attempt, Exception error) {
        if (attempt != generation || isFinishing() || isDestroyed()) return;
        busy = false;
        refresh();
        Tasks.showError(this, error);
    }

    private void browse(List<String> requested) {
        if (busy || remote == null) return;
        List<String> destination = List.copyOf(requested);
        RemoteStorage client = remote;
        perform(() -> client.list(destination), result -> { path = destination; entries = result; });
    }

    private void open(RemoteStorage.Entry entry) {
        if (busy || remote == null) return;
        try {
            List<String> target = RemoteStorage.child(path, entry.name());
            if (entry.directory()) { browse(target); return; }
            downloadPath = target;
            openPicker(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE, entry.name()), 2);
        } catch (IOException error) { Tasks.showError(this, error); }
    }

    private void openPicker(Intent intent, int request) {
        try { startActivityForResult(intent, request); }
        catch (RuntimeException error) { Tasks.showError(this, error); }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null || busy || remote == null) return;
        Uri document = data.getData();
        RemoteStorage client = remote;
        List<String> folder = path;
        if (request == 1) perform(() -> {
            String name = displayName(document);
            List<String> target = RemoteStorage.child(folder, name);
            try (InputStream input = getContentResolver().openInputStream(document)) {
                if (input == null) throw new IOException("无法读取待上传文件");
                client.upload(target, input);
            }
            return client.list(folder);
        }, resultEntries -> { entries = resultEntries; Tasks.toast(this, "上传完成"); });
        else if (request == 2 && downloadPath != null) {
            List<String> source = downloadPath;
            downloadPath = null;
            perform(() -> {
                File stage = File.createTempFile("network-download-", ".tmp", getCacheDir());
                try {
                    try (OutputStream output = new FileOutputStream(stage)) { client.download(source, output); }
                    try (InputStream input = new FileInputStream(stage);
                         OutputStream output = getContentResolver().openOutputStream(document, "wt")) {
                        if (output == null) throw new IOException("无法写入下载目标");
                        FileStore.pump(input, output);
                    }
                    return true;
                } finally { java.nio.file.Files.deleteIfExists(stage.toPath()); }
            }, done -> Tasks.toast(this, "下载完成"));
        }
    }

    private String displayName(Uri uri) throws IOException {
        try (var row = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (row != null && row.moveToFirst() && row.getString(0) != null) return row.getString(0);
            throw new IOException("无法读取文件名称");
        }
    }

    private void disconnect() {
        generation++;
        if (work != null) work.cancel(true);
        RemoteStorage previous = remote;
        remote = null;
        busy = false;
        path = List.of();
        entries = List.of();
        if (previous != null) Tasks.CONTROL.execute(() -> close(previous));
        refresh();
    }

    private static void close(RemoteStorage client) {
        if (client != null) try { client.close(); } catch (Exception ignored) { }
    }

    @Override protected void handleBack() {
        if (remote != null && !busy && !path.isEmpty()) browse(path.subList(0, path.size() - 1));
        else finish();
    }

    @Override protected void onDestroy() {
        disconnect();
        super.onDestroy();
    }
}
