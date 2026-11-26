package org.example.filemanager;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.webkit.MimeTypeMap;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.URI;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.Future;

/** Connection profiles and file operations for one remote share. */
public final class RemoteStorageActivity extends LabActivity {
    private static final int UPLOAD = 1, DOWNLOAD = 2, KEY = 3;
    private static final int MAX_KEY_BYTES = 1024 * 1024;
    private static final List<String> SCHEMES = List.of("ftp", "sftp", "https", "smb");
    private static final List<String> PROTOCOLS = List.of("FTP", "SSH（SFTP）", "HTTPS WebDAV", "SMB");
    private record Profile(String name, String address, String user, String fingerprint) { }
    private record Connection(Profile profile, String secret, Uri key) { }
    private record RemoteFile(List<String> path, RemoteStorage.Entry entry) { }
    private record LocalFile(Uri uri, String name, long size) { }

    private RemoteStorage remote;
    private Profile activeProfile;
    private List<String> path = List.of();
    private List<RemoteStorage.Entry> entries = List.of();
    private RemoteFile pendingDownload;
    private Uri privateKey;
    private boolean usePrivateKey;
    private ScrollView formScroll;
    private LinearLayout commands;
    private TextView location, hint;
    private ListView list;
    private ArrayAdapter<String> adapter;
    private Button profiles, protocol, authentication, connect, save, key, parent, upload, more, disconnect;
    private EditText serverName, endpoint, account, password, fingerprint;
    private OperationProgressView progressView;
    private Tasks.Progress transfer;
    private String phase = "";
    private String selectedScheme = "sftp";
    private Future<?> work;
    private int generation;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout body = Ui.column(this);
        LinearLayout toolbar = Ui.toolbar(this, "网络存储", this::handleBack);
        disconnect = button("断开", this::disconnect);
        toolbar.addView(disconnect);
        body.addView(toolbar);
        int pad = Ui.dp(this, 16);
        LinearLayout form = Ui.column(this);
        form.setPadding(pad, pad, pad, pad);
        profiles = button("已保存的服务器", this::chooseProfile);
        form.addView(profiles);
        serverName = input("服务器名称（可选）");
        endpoint = input("服务器地址");
        endpoint.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        account = input("用户名");
        password = input("密码");
        password.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setSaveEnabled(false);
        fingerprint = input("SSH 主机指纹（可首次连接时核对）");
        authentication = button("登录方式 · 密码", this::chooseAuthentication);
        key = button("选择 SSH 私钥", this::launchKeyPicker);
        form.addView(serverName);
        protocol = button("协议", this::chooseProtocol);
        form.addView(protocol);
        form.addView(endpoint);
        form.addView(account);
        form.addView(authentication);
        form.addView(key);
        form.addView(password);
        form.addView(fingerprint);
        hint = Ui.text(this, "", 13, Ui.MUTED);
        hint.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        form.addView(hint);
        LinearLayout connectRow = Ui.row(this);
        save = button("保存服务器", this::saveCurrentProfile);
        connect = button("连接", this::connect);
        connectRow.addView(save, weighted());
        connectRow.addView(connect, weighted());
        form.addView(connectRow);
        formScroll = new ScrollView(this);
        formScroll.setFillViewport(true);
        formScroll.addView(form);
        body.addView(formScroll, new LinearLayout.LayoutParams(-1, 0, 1));

        location = Ui.text(this, "", 14, Ui.TEXT);
        location.setPadding(pad, Ui.dp(this, 8), pad, Ui.dp(this, 8));
        location.setTextIsSelectable(true);
        body.addView(location);
        progressView = new OperationProgressView(this);
        body.addView(progressView);
        commands = Ui.row(this);
        parent = button("上一级", () -> browse(path.subList(0, path.size() - 1)));
        upload = button("上传文件", () -> openPicker(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true).setType("*/*"), UPLOAD));
        more = button("目录操作", this::directoryActions);
        for (Button control : List.of(parent, upload, more)) commands.addView(control, weighted());
        body.addView(commands);

        list = new ListView(this);
        list.setContentDescription("远程文件列表");
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        list.setAdapter(adapter);
        list.setOnItemClickListener((container, row, index, id) -> open(entries.get(index)));
        list.setOnItemLongClickListener((container, row, index, id) -> {
            RemoteStorage.Entry entry = entries.get(index);
            if (entry.directory()) return false;
            open(entry);
            return true;
        });
        body.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        Ui.install(this, body);
        SharedPreferences settings = getPreferences(MODE_PRIVATE);
        showProfile(new Profile(settings.getString("name", ""), settings.getString("endpoint", ""),
                settings.getString("account", ""), settings.getString("fingerprint", "")));
        MainActivity.watch(endpoint, this::updateForm);
        refresh();
    }

    private EditText input(String hintText) {
        EditText field = Ui.input(this, hintText);
        field.setSingleLine(true);
        field.setMinHeight(Ui.dp(this, 48));
        return field;
    }
    private Button button(String label, Runnable action) {
        Button value = new Button(this);
        value.setText(label);
        value.setMinHeight(Ui.dp(this, 48));
        value.setOnClickListener(v -> action.run());
        return value;
    }
    private LinearLayout.LayoutParams weighted() { return new LinearLayout.LayoutParams(0, -2, 1); }
    private boolean busy() { return !phase.isEmpty(); }

    private void chooseProtocol() {
        String previous = formScheme(endpoint.getText().toString().trim());
        MaterialDialogs.builder(this).setTitle("选择连接协议")
                .setSingleChoiceItems(PROTOCOLS.toArray(new String[0]), SCHEMES.indexOf(previous), (dialog, index) -> {
                    dialog.dismiss();
                    switchProtocol(SCHEMES.get(index), false);
                }).setNegativeButton("取消", null).show();
    }

    private String formScheme(String address) {
        int delimiter = address.indexOf("://");
        return delimiter < 0 ? selectedScheme : RemoteStorage.connectionScheme(address.substring(0, delimiter));
    }

    private void switchProtocol(String chosen, boolean keepPort) {
        String address = endpoint.getText().toString().trim();
        int delimiter = address.indexOf("://");
        String previous = formScheme(address);
        String rest = delimiter < 0 ? address : address.substring(delimiter + 3);
        if (!chosen.equals(previous)) {
            privateKey = null;
            usePrivateKey = false;
            fingerprint.setText("");
            password.setText("");
            if (!keepPort && !rest.isEmpty()) {
                try {
                    URI old = RemoteStorage.endpoint(previous + "://" + rest);
                    int previousDefault = switch (previous) {
                        case "ftp" -> 21;
                        case "sftp" -> 22;
                        case "https" -> 443;
                        case "smb" -> 445;
                        default -> -1;
                    };
                    if (old.getPort() == previousDefault) rest = old.getHost() + old.getRawPath();
                } catch (IOException incompleteAddress) { /* Keep the text so the user can finish editing. */ }
            }
        }
        selectedScheme = chosen;
        endpoint.setText(chosen + "://" + rest);
        endpoint.setSelection(endpoint.length());
    }

    private void updateForm() {
        String address = endpoint.getText().toString().trim();
        String scheme = formScheme(address);
        int index = SCHEMES.indexOf(scheme);
        if (index >= 0) selectedScheme = scheme;
        protocol.setText(index < 0 ? "协议 · 请选择" : "协议 · " + PROTOCOLS.get(index));
        boolean sftp = scheme.equals("sftp");
        fingerprint.setVisibility(sftp ? View.VISIBLE : View.GONE);
        authentication.setVisibility(sftp ? View.VISIBLE : View.GONE);
        authentication.setText(usePrivateKey ? "登录方式 · SSH 私钥" : "登录方式 · 密码");
        key.setVisibility(sftp && usePrivateKey ? View.VISIBLE : View.GONE);
        key.setText(privateKey == null ? "选择 SSH 私钥文件" : "已选择 SSH 私钥 · 更换");
        password.setHint(sftp && usePrivateKey ? "私钥口令（可选）" : "密码");
        hint.setText(scheme.equals("ftp") ? "FTP 默认端口 21，使用被动模式。可填 ftp://服务器地址:端口/目录。FTP 以明文传输。"
                : sftp ? "SSH 文件连接默认使用 22 端口。可填服务器地址或 ssh://服务器地址:端口/目录。密码登录不需要私钥。"
                : scheme.equals("smb") ? "SMB 示例：smb://服务器/共享名。域账号可用「域\\用户名」。"
                : "请输入 HTTPS WebDAV 服务地址，包括共享目录路径。");
    }

    private void refresh() {
        boolean connected = remote != null;
        boolean busy = busy();
        formScroll.setVisibility(connected ? View.GONE : View.VISIBLE);
        commands.setVisibility(connected ? View.VISIBLE : View.GONE);
        list.setVisibility(connected ? View.VISIBLE : View.GONE);
        location.setVisibility(connected || busy ? View.VISIBLE : View.GONE);
        disconnect.setVisibility(connected || busy ? View.VISIBLE : View.GONE);
        disconnect.setText(!busy ? "断开" : connected ? "停止并断开" : "取消连接");
        if (connected) {
            String folder = "/" + String.join("/", path);
            location.setText(activeProfile.name() + "\n" + folder + (busy ? " · " + phase : entries.isEmpty() ? " · 空目录" : ""));
        } else location.setText(phase);
        parent.setEnabled(connected && !busy && !path.isEmpty());
        upload.setEnabled(connected && !busy);
        more.setEnabled(connected && !busy);
        list.setEnabled(!busy);
        for (View view : List.of(serverName, protocol, endpoint, account, authentication, password, fingerprint, key, profiles, save, connect)) {
            view.setEnabled(!busy);
        }
        updateForm();
    }

    private void setEntries(List<RemoteStorage.Entry> result) {
        entries = result;
        adapter.setNotifyOnChange(false);
        adapter.clear();
        for (RemoteStorage.Entry entry : result) {
            adapter.add((entry.directory() ? "目录 · " : "") + entry.name()
                    + (!entry.directory() && entry.size() >= 0 ? "\n" + FileStore.size(entry.size()) : ""));
        }
        adapter.notifyDataSetChanged();
    }
    private Profile readForm() throws IOException {
        String inputAddress = endpoint.getText().toString().trim();
        if (inputAddress.isEmpty()) throw new IOException("请输入服务器地址");
        URI address = RemoteStorage.endpoint(inputAddress.contains("://") ? inputAddress : selectedScheme + "://" + inputAddress);
        String scheme = address.getScheme().toLowerCase(Locale.ROOT);
        if (!SCHEMES.contains(scheme)) throw new IOException("请选择 FTP、SSH（SFTP）、HTTPS WebDAV 或 SMB 地址");
        String user = account.getText().toString().trim();
        String pin = scheme.equals("sftp") ? fingerprint.getText().toString().trim().replaceFirst("=$", "") : "";
        if ((scheme.equals("sftp") || scheme.equals("ftp")) && user.isEmpty())
            throw new IOException(scheme.equals("sftp") ? "请填写 SSH 用户名" : "请填写 FTP 用户名");
        if (!pin.isEmpty() && !pin.matches("SHA256:[A-Za-z0-9+/]{43}")) throw new IOException("SFTP 主机指纹格式不正确");
        String name = serverName.getText().toString().trim();
        return new Profile(name.isEmpty() ? address.getHost() : name, address.toASCIIString(), user, pin);
    }
    private void showProfile(Profile profile) {
        selectedScheme = "sftp";
        privateKey = null;
        usePrivateKey = false;
        password.setText("");
        serverName.setText(profile.name());
        endpoint.setText(profile.address());
        account.setText(profile.user());
        fingerprint.setText(profile.fingerprint());
        updateForm();
    }
    private List<Profile> readProfiles() {
        List<Profile> values = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(getPreferences(MODE_PRIVATE).getString("servers", "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item != null && !item.optString("address").isBlank())
                    values.add(new Profile(item.optString("name"), item.optString("address"),
                            item.optString("user"), item.optString("fingerprint")));
            }
        } catch (org.json.JSONException ignored) { }
        return values;
    }
    private void writeProfiles(List<Profile> values) {
        JSONArray array = new JSONArray();
        for (Profile profile : values) {
            try {
                array.put(new JSONObject().put("name", profile.name()).put("address", profile.address())
                        .put("user", profile.user()).put("fingerprint", profile.fingerprint()));
            } catch (org.json.JSONException impossible) { throw new IllegalStateException(impossible); }
        }
        getPreferences(MODE_PRIVATE).edit().putString("servers", array.toString()).apply();
    }
    private static boolean sameServer(Profile a, Profile b) {
        return a.address().equals(b.address()) && a.user().equals(b.user());
    }
    private void saveProfile(Profile profile) {
        List<Profile> values = readProfiles();
        int existing = -1;
        for (int i = 0; i < values.size(); i++) if (sameServer(values.get(i), profile)) { existing = i; break; }
        if (existing >= 0) values.set(existing, profile); else values.add(0, profile);
        writeProfiles(values);
    }
    private void saveCurrentProfile() {
        try {
            Profile profile = readForm();
            saveProfile(profile);
            remember(profile);
            Tasks.toast(this, "服务器已保存");
        } catch (IOException error) { Tasks.showError(this, error); }
    }
    private void chooseProfile() {
        List<Profile> values = readProfiles();
        if (values.isEmpty()) { Tasks.toast(this, "填写地址后点按「保存服务器」"); return; }
        String[] names = values.stream().map(value -> value.name() + "\n" + value.address()
                + (value.user().isEmpty() ? "" : " · " + value.user())).toArray(String[]::new);
        MaterialDialogs.builder(this).setTitle("已保存的服务器")
                .setItems(names, (d, at) -> showProfile(values.get(at)))
                .setNeutralButton("移除记录", (d, w) -> MaterialDialogs.builder(this).setTitle("选择要移除的服务器记录")
                        .setItems(names, (dialog, at) -> {
                            values.remove(at);
                            writeProfiles(values);
                            Tasks.toast(this, "服务器记录已移除");
                        }).setNegativeButton("取消", null).show())
                .setNegativeButton("关闭", null).show();
    }
    private void chooseAuthentication() {
        MaterialDialogs.builder(this).setTitle("SSH 登录方式")
                .setSingleChoiceItems(new String[]{"账号密码", "SSH 私钥"}, usePrivateKey ? 1 : 0, (dialog, at) -> {
                    dialog.dismiss();
                    boolean next = at == 1;
                    if (next == usePrivateKey) return;
                    usePrivateKey = next;
                    privateKey = null;
                    password.setText("");
                    updateForm();
                }).setNegativeButton("取消", null).show();
    }
    private void launchKeyPicker() {
        openPicker(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), KEY);
    }
    private void remember(Profile profile) {
        getPreferences(MODE_PRIVATE).edit().putString("name", profile.name()).putString("endpoint", profile.address())
                .putString("account", profile.user()).putString("fingerprint", profile.fingerprint()).apply();
    }
    private void connect() {
        if (busy() || remote != null) return;
        try {
            Profile profile = readForm();
            boolean needsKey = profile.address().startsWith("sftp://") && usePrivateKey;
            if (needsKey && privateKey == null) throw new IOException("请先选择 SSH 私钥文件，或切换到账号密码登录");
            Uri identity = needsKey ? privateKey : null;
            connect(new Connection(profile, password.getText().toString(), identity));
        } catch (IOException error) { Tasks.showError(this, error); }
    }
    private void connect(Connection login) {
        int attempt = ++generation;
        ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                .hideSoftInputFromWindow(endpoint.getWindowToken(), 0);
        phase = "正在连接…";
        refresh();
        work = Tasks.SHELL.submit(() -> {
            RemoteStorage connected = null;
            byte[] keyBytes = null;
            try {
                if (login.key() != null) {
                    try (InputStream input = getContentResolver().openInputStream(login.key())) {
                        if (input == null) throw new IOException("无法读取 SSH 私钥");
                        keyBytes = FileStore.readBytes(input, MAX_KEY_BYTES + 1);
                    }
                    if (keyBytes.length == 0 || keyBytes.length > MAX_KEY_BYTES) throw new IOException("SSH 私钥文件为空或超过 1 MiB");
                }
                Profile profile = login.profile();
                connected = RemoteStorage.connect(profile.address(), profile.user(), login.secret(), profile.fingerprint(), keyBytes);
                Tasks.Progress.check();
                List<RemoteStorage.Entry> initial = connected.list(List.of());
                RemoteStorage ready = connected;
                runOnUiThread(() -> {
                    if (!current(attempt)) { Tasks.CONTROL.execute(() -> close(ready)); return; }
                    remote = ready;
                    activeProfile = profile;
                    setEntries(initial);
                    if (readProfiles().stream().anyMatch(saved -> sameServer(saved, profile))) saveProfile(profile);
                    remember(profile);
                    password.setText("");
                    phase = "";
                    refresh();
                });
            } catch (RemoteStorage.HostKeyRequired unknown) {
                close(connected);
                runOnUiThread(() -> confirmFingerprint(attempt, login, unknown.fingerprint));
            } catch (Exception error) {
                close(connected);
                runOnUiThread(() -> failed(attempt, error));
            } finally { if (keyBytes != null) Arrays.fill(keyBytes, (byte) 0); }
        });
    }
    private boolean current(int attempt) { return attempt == generation && !isFinishing() && !isDestroyed(); }
    private void confirmFingerprint(int attempt, Connection login, String observed) {
        if (!current(attempt)) return;
        phase = "";
        refresh();
        Profile profile = login.profile();
        MaterialDialogs.builder(this).setTitle("核对 SSH 主机指纹")
                .setMessage(profile.address() + "\n\n" + observed
                        + "\n\n请与云服务器控制台或管理员提供的主机指纹比较。一致后再继续连接。")
                .setNegativeButton("取消", null).setPositiveButton("指纹一致，连接", (d, w) -> {
                    if (!current(attempt) || busy()) return;
                    fingerprint.setText(observed);
                    connect(new Connection(new Profile(profile.name(), profile.address(), profile.user(), observed),
                            login.secret(), login.key()));
                }).show();
    }

    private <T> void perform(String label, boolean showProgress, Tasks.Job<T> job, Tasks.Done<T> done) {
        if (busy() || remote == null) return;
        int attempt = generation;
        phase = label;
        Tasks.Progress meter = showProgress ? new Tasks.Progress() : null;
        transfer = meter;
        if (meter != null) {
            Runnable cancel = remote instanceof RemoteFtp ftp ? ftp::cancelTransfer : null;
            progressView.bind(meter, cancel);
        }
        refresh();
        work = Tasks.SHELL.submit(() -> {
            try {
                T result = meter == null ? job.run() : meter.run(job);
                runOnUiThread(() -> {
                    if (!current(attempt)) return;
                    finishWork();
                    done.accept(result);
                    refresh();
                });
            } catch (Exception error) { runOnUiThread(() -> failed(attempt, error)); }
        });
    }
    private void finishWork() {
        phase = "";
        transfer = null;
        progressView.clear();
    }
    private void failed(int attempt, Exception error) {
        if (!current(attempt)) return;
        boolean cancelled = transfer != null && transfer.cancelled.get();
        finishWork();
        refresh();
        if (cancelled || error instanceof Tasks.CancelledException) {
            Tasks.toast(this, "已停止传输");
            if (remote != null) browse(path);
        } else if (error instanceof javax.net.ssl.SSLException) {
            Tasks.showError(this, new IOException("HTTPS 连接验证失败，请检查域名和服务器证书", error));
        } else if (error instanceof RemoteStorage.SshServiceDetected) {
            MaterialDialogs.builder(this).setTitle("检测到 SSH 服务").setMessage(error.getMessage())
                    .setNegativeButton("关闭", null).setPositiveButton("切换到 SSH", (d, w) -> switchProtocol("sftp", true)).show();
        } else if (error instanceof jcifs.smb.SmbException
                && Objects.toString(error.getMessage(), "").contains("Signature validation failed")) {
            Tasks.showError(this, new IOException("SMB 会话签名验证失败，请检查服务器的 SMB2/SMB3 和签名配置", error));
        } else Tasks.showError(this, error);
    }
    private void browse(List<String> requested) {
        if (busy() || remote == null) return;
        List<String> destination = List.copyOf(requested);
        RemoteStorage client = remote;
        perform("正在读取目录…", false, () -> client.list(destination), result -> {
            boolean changed = !path.equals(destination);
            path = destination;
            setEntries(result);
            if (changed) list.setSelection(0);
        });
    }
    private void directoryActions() {
        MaterialDialogs.builder(this).setTitle("目录操作").setItems(new String[]{"刷新目录", "新建文件夹"}, (d, at) -> {
            if (at == 0) browse(path);
            else changeName("新建文件夹", "", true);
        }).show();
    }
    private void open(RemoteStorage.Entry entry) {
        if (busy() || remote == null) return;
        try {
            RemoteFile selected = new RemoteFile(RemoteStorage.child(path, entry.name()), entry);
            if (entry.directory()) { browse(selected.path()); return; }
            MaterialDialogs.builder(this).setTitle(entry.name())
                    .setItems(new String[]{"下载到设备", "重命名", "删除文件"}, (d, at) -> {
                        if (at == 0) download(selected);
                        else if (at == 1) changeName("重命名文件", entry.name(), false);
                        else confirmDelete(selected);
                    }).setNegativeButton("关闭", null).show();
        } catch (IOException error) { Tasks.showError(this, error); }
    }
    private void changeName(String title, String old, boolean directory) {
        EditText input = input("名称");
        input.setText(old);
        AlertDialog dialog = MaterialDialogs.builder(this).setTitle(title).setView(input)
                .setPositiveButton("确定", null).setNegativeButton("取消", null).show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = input.getText().toString();
            try {
                List<String> target = RemoteStorage.child(path, value);
                List<String> source = directory ? null : RemoteStorage.child(path, old);
                dialog.dismiss();
                if (source != null && source.equals(target)) return;
                RemoteStorage client = remote;
                perform(directory ? "正在新建目录…" : "正在重命名…", false, () -> {
                    if (directory) client.mkdir(target); else client.renameFile(source, target);
                    return null;
                }, ignored -> changed(directory ? "文件夹已创建" : "已重命名"));
            } catch (IOException invalid) { input.setError(invalid.getMessage()); }
        });
    }
    private void confirmDelete(RemoteFile selected) {
        MaterialDialogs.builder(this).setTitle("删除服务器文件？").setMessage(selected.entry().name())
                .setNegativeButton("取消", null).setPositiveButton("删除", (d, w) -> {
                    RemoteStorage client = remote;
                    perform("正在删除…", false, () -> { client.deleteFile(selected.path()); return null; },
                            ignored -> changed("文件已删除"));
                }).show();
    }
    private void changed(String message) {
        Tasks.toast(this, message);
        browse(path);
    }
    private void download(RemoteFile selected) {
        pendingDownload = selected;
        String name = selected.entry().name();
        int dot = name.lastIndexOf('.');
        String mime = dot < 0 ? null : MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase(Locale.ROOT));
        if (!openPicker(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(mime == null ? "application/octet-stream" : mime).putExtra(Intent.EXTRA_TITLE, name), DOWNLOAD)) {
            pendingDownload = null;
        }
    }
    private boolean openPicker(Intent intent, int request) {
        try { startActivityForResult(intent, request); return true; }
        catch (RuntimeException error) { Tasks.showError(this, error); return false; }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        RemoteFile selected = request == DOWNLOAD ? pendingDownload : null;
        if (request == DOWNLOAD) pendingDownload = null;
        if (result != RESULT_OK || data == null || busy()) return;
        Uri document = data.getData();
        if (request == KEY && remote == null && usePrivateKey && formScheme(endpoint.getText().toString().trim()).equals("sftp") && document != null) {
            privateKey = document;
            password.setText("");
            updateForm();
            return;
        }
        if (remote == null) return;
        if (request == UPLOAD) uploadResult(data);
        if (request == DOWNLOAD && selected != null && document != null) downloadResult(selected, document);
    }
    private void uploadResult(Intent data) {
        Set<Uri> documents = new LinkedHashSet<>();
        if (data.getData() != null) documents.add(data.getData());
        if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++) {
            Uri uri = data.getClipData().getItemAt(i).getUri();
            if (uri != null) documents.add(uri);
        }
        if (documents.isEmpty()) return;
        RemoteStorage client = remote;
        List<String> folder = path;
        perform("正在上传文件…", true, () -> {
            List<LocalFile> sources = new ArrayList<>();
            for (Uri uri : documents) { Tasks.Progress.check(); sources.add(localFile(uri)); }
            long total = 0;
            for (LocalFile file : sources) total = ArchiveOps.addSize(total, file.size());
            Tasks.Progress.budget("上传传输", total);
            int sent = 0;
            try {
                for (LocalFile file : sources) {
                    Tasks.Progress.step(file.name());
                    try (InputStream in = getContentResolver().openInputStream(file.uri())) {
                        if (in == null) throw new IOException("无法读取 " + file.name());
                        client.upload(RemoteStorage.child(folder, file.name()), in, file.size());
                    }
                    sent++;
                }
                return sent;
            } catch (Exception error) {
                if (sent == 0 || error instanceof Tasks.CancelledException) throw error;
                throw new IOException("已上传 " + sent + " 项，其余未完成：" + error.getMessage(), error);
            }
        }, sent -> changed("已上传 " + sent + " 项"));
    }
    private void downloadResult(RemoteFile selected, Uri document) {
        RemoteStorage client = remote;
        perform("正在下载文件…", true, () -> {
            long expected = selected.entry().size();
            Tasks.Progress.budget("下载传输", expected);
            Tasks.Progress.step(selected.entry().name());
            File stage = File.createTempFile("network-download-", ".tmp", getCacheDir());
            try {
                try (OutputStream output = new FileOutputStream(stage)) { client.download(selected.path(), output); }
                if (expected >= 0 && stage.length() != expected) throw new IOException("文件大小已变化，请刷新目录后再下载");
                Tasks.Progress.check();
                try (Tasks.Progress.Phase ignored = Tasks.Progress.stage("保存到设备");
                     InputStream input = new FileInputStream(stage);
                     OutputStream output = getContentResolver().openOutputStream(document, "wt")) {
                    if (output == null) throw new IOException("无法写入下载目标");
                    FileStore.pump(input, output);
                }
                return true;
            } finally { Files.deleteIfExists(stage.toPath()); }
        }, done -> Tasks.toast(this, "下载完成"));
    }
    private LocalFile localFile(Uri uri) throws IOException {
        try (var row = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
            if (row != null && row.moveToFirst()) {
                int name = row.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int size = row.getColumnIndex(OpenableColumns.SIZE);
                if (name >= 0 && !row.isNull(name))
                    return new LocalFile(uri, RemoteStorage.component(row.getString(name)),
                            size >= 0 && !row.isNull(size) ? Math.max(-1L, row.getLong(size)) : -1);
            }
            throw new IOException("无法读取待上传文件的名称");
        }
    }

    private void disconnect() {
        generation++;
        if (transfer != null) transfer.cancelled.set(true);
        if (work != null) work.cancel(true);
        RemoteStorage previous = remote;
        remote = null;
        activeProfile = null;
        pendingDownload = null;
        path = List.of();
        finishWork();
        setEntries(List.of());
        if (previous != null) Tasks.CONTROL.execute(() -> close(previous));
        refresh();
    }
    private static void close(RemoteStorage client) {
        if (client != null) try { client.close(); } catch (Exception ignored) { }
    }
    @Override protected void handleBack() {
        if (remote != null && !busy() && !path.isEmpty()) browse(path.subList(0, path.size() - 1));
        else finish();
    }
    @Override protected void onDestroy() {
        disconnect();
        super.onDestroy();
    }
}
