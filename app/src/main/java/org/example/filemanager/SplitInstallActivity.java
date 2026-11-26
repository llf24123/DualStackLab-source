package org.example.filemanager;

import android.app.*;
import android.content.*;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.widget.*;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Keeps installation confirmation in a foreground activity, including system callbacks. */
public class SplitInstallActivity extends LabActivity {
    private static final String RESULT = BuildConfig.APPLICATION_ID + ".SPLIT_INSTALL_RESULT";
    private static final String SINGLE="stagedSingleApk";
    private static final int ALLOW_SOURCE=91;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private TextView status;
    private int sessionId = -1;
    private boolean working, waitingPermission, committed, rootInstall;
    private volatile ShellRunner privilegedRunner;
    private SplitApks.Prepared preview;
    private ApksTargeting.Profile profile = ApksTargeting.Profile.AUTOMATIC;
    private LinearLayout body;
    private File single;
    static void startSingle(Activity activity,File staged){
        activity.startActivity(new Intent(activity,SplitInstallActivity.class).putExtra(SINGLE,staged.getAbsolutePath()));
    }
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        String path=getIntent().getStringExtra(SINGLE);
        if(path!=null)single=new File(path);
        boolean one=single!=null;
        LinearLayout root = Ui.column(this); body = root;
        root.addView(Ui.toolbar(this, one?"安装 APK":"安装 APKS", this::finish));
        status = Ui.text(this, one?"正在准备系统安装…":"正在准备拆分安装包…", 16, Ui.TEXT);
        status.setPadding(Ui.dp(this, 20), Ui.dp(this, 24), Ui.dp(this, 20), Ui.dp(this, 24));
        root.addView(status); Ui.install(this, root);
        if (RESULT.equals(getIntent().getAction())) { result(getIntent()); return; }
        if (saved != null) {
            var groups = saved.getStringArrayList("profileGroups");
            profile = new ApksTargeting.Profile(groups == null ? null : java.util.Set.copyOf(groups),
                    saved.containsKey("profileTier") ? saved.getInt("profileTier") : null,
                    saved.getString("profileCountrySet"), saved.getString("profileCountryCode"));
            sessionId = saved.getInt("session", -1); committed = saved.getBoolean("committed");
            if (committed) {
                if(sessionId>=0 && installer().getSessionInfo(sessionId)!=null)status.setText("正在等待系统安装结果…");
                else finish();
                return;
            }
            abandon();
            waitingPermission=saved.getBoolean("waitingPermission");
            if(waitingPermission){status.setText("正在等待安装来源授权…");return;}
        }
        begin();
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (RESULT.equals(intent.getAction())) result(intent);
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out); out.putInt("session", sessionId); out.putBoolean("committed", committed);
        out.putBoolean("waitingPermission",waitingPermission);
        if (profile.groups() != null) out.putStringArrayList("profileGroups", new java.util.ArrayList<>(profile.groups()));
        if (profile.tier() != null) out.putInt("profileTier", profile.tier());
        out.putString("profileCountrySet", profile.countrySet()); out.putString("profileCountryCode", profile.countryCode());
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if (request==ALLOW_SOURCE && waitingPermission) {
            waitingPermission = false;
            if (getPackageManager().canRequestPackageInstalls()) begin();
            else {Tasks.toast(this,"未允许此来源安装应用，已取消安装");finish();}
        }
    }
    private void begin() {
        if (working) return;
        working = true;
        Tasks.SHELL.execute(() -> {
            boolean root = RootAccess.available();
            runOnUiThread(() -> {
                if (cancelled.get() || isFinishing() || isDestroyed()) { working = false; return; }
                rootInstall = root;
                beginResolved();
            });
        });
    }
    private void beginResolved() {
        if (!rootInstall && !getPackageManager().canRequestPackageInstalls()) {
            working = false;
            MaterialDialogs.builder(this).setTitle("允许安装应用")
                    .setMessage("请在系统设置中允许双栈 Lab 安装应用，返回后继续安装。")
                    .setNegativeButton("取消", (d, w) -> finish()).setOnCancelListener(d -> finish())
                    .setPositiveButton("打开设置", (d, w) -> {
                        waitingPermission = true;
                        try { startActivityForResult(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())),ALLOW_SOURCE); }
                        catch (Exception e) { waitingPermission = false; error(e.getMessage()); }
                    }).show();
            return;
        }
        if(single!=null){status.setText(rootInstall?"正在通过 ROOT 静默安装 APK…":"正在交给系统安装器…");writeAndCommit(null);return;}
        ApksTargeting.Profile requested = profile;
        Tasks.SHELL.execute(() -> {
            try {
                SplitApks.Prepared prepared = SplitApks.prepare(this, new FileStore(this).fromIntent(getIntent()), cancelled, requested);
                runOnUiThread(() -> {
                    if (cancelled.get() || isFinishing() || isDestroyed()) { Tasks.IO.execute(prepared::close); return; }
                    preview = prepared; showSelection(prepared);
                });
            } catch (ApksTargeting.ProfileRequired required) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || cancelled.get()) return;
                    working = false;
                    chooseProfile(required);
                });
            } catch (Exception e) {
                runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) error(e.getMessage()); });
            }
        });
    }
    private void chooseProfile(ApksTargeting.ProfileRequired required) {
        String label = switch (required.dimension) {
            case GROUP -> "设备组";
            case TIER -> "设备层级";
            case COUNTRY_SET -> "地区组";
            case COUNTRY_CODE -> "安装地区代码";
        };
        status.setText("安装包需要指定" + label);
        AlertDialog.Builder choices = MaterialDialogs.builder(this).setTitle(label)
                .setNegativeButton("取消", (d, w) -> finish()).setOnCancelListener(d -> finish());
        if (required.dimension == ApksTargeting.Dimension.COUNTRY_CODE || required.dimension == ApksTargeting.Dimension.GROUP) {
            boolean groups = required.dimension == ApksTargeting.Dimension.GROUP;
            EditText input = Ui.input(this, groups ? "多个组名用逗号隔开，留空表示无分组" : "输入两字母地区代码，如 TW");
            if (groups && !required.options.isEmpty()) choices.setMessage("包内组名  " + String.join("、", required.options));
            AlertDialog dialog = choices.setView(input).setPositiveButton("匹配", null).show();
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
                String value = input.getText().toString().trim();
                if (groups) {
                    var names = new java.util.LinkedHashSet<String>();
                    for (String name : value.split("[,，]")) if (!name.isBlank()) names.add(name.trim());
                    profile = new ApksTargeting.Profile(java.util.Set.copyOf(names), profile.tier(), profile.countrySet(), profile.countryCode());
                } else {
                    if (!value.matches("[A-Za-z]{2}")) { input.setError("请输入两字母地区代码"); return; }
                    profile = new ApksTargeting.Profile(profile.groups(), profile.tier(), profile.countrySet(), value);
                }
                dialog.dismiss(); status.setText("正在匹配分包…"); begin();
            });
            return;
        }
        var options = new java.util.ArrayList<>(required.options);
        if (required.dimension == ApksTargeting.Dimension.COUNTRY_SET) options.add("使用默认地区");
        choices.setItems(options.toArray(String[]::new), (d, at) -> {
            profile = required.dimension == ApksTargeting.Dimension.TIER
                    ? new ApksTargeting.Profile(profile.groups(), Integer.valueOf(options.get(at)), profile.countrySet(), profile.countryCode())
                    : new ApksTargeting.Profile(profile.groups(), profile.tier(), at == required.options.size() ? "" : options.get(at), profile.countryCode());
            status.setText("正在匹配分包…"); begin();
        }).show();
    }
    private void showSelection(SplitApks.Prepared prepared) {
        String message = prepared.label + "\n" + prepared.packageName + "\n" + prepared.selection
                + "\n选中 " + prepared.parts.size() + " 个 APK · " + FileStore.size(prepared.bytes) + "\n未选中 " + prepared.excluded.size() + " 个";
        status.setText(message);
        java.util.List<String> lines = new java.util.ArrayList<>();
        for (SplitApks.Part part : prepared.parts) lines.add("安装 · " + part.name() + "\n" + FileStore.size(part.file().length()));
        for (String name : prepared.excluded) lines.add("未选择 · " + name);
        ListView list = new ListView(this); list.setContentDescription("APKS 设备选择结果");
        body.removeView(status); list.addHeaderView(status, null, false);
        list.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, lines));
        body.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        java.util.Set<SplitApks.Part> selection = new java.util.LinkedHashSet<>(prepared.parts);
        var optional = prepared.parts.stream().filter(SplitApks::optional).toList();
        if (!optional.isEmpty()) {
            Button options = new Button(this);
            options.setText("选择语言分包");
            options.setMinHeight(Ui.dp(this, 48));
            options.setOnClickListener(v -> {
                boolean[] marks = new boolean[optional.size()];
                String[] names = new String[marks.length];
                for (int i = 0; i < marks.length; i++) {
                    marks[i] = selection.contains(optional.get(i));
                    names[i] = optional.get(i).name();
                }
                MaterialDialogs.builder(this).setTitle("可选语言分包")
                        .setMultiChoiceItems(names, marks, (d, index, marked) -> marks[index] = marked)
                        .setNegativeButton("取消", null).setPositiveButton("确定", (d, w) -> {
                            for (int i = 0; i < marks.length; i++) {
                                if (marks[i]) selection.add(optional.get(i)); else selection.remove(optional.get(i));
                            }
                            for (int i = 0; i < prepared.parts.size(); i++) {
                                SplitApks.Part part = prepared.parts.get(i);
                                lines.set(i, (selection.contains(part) ? "安装 · " : "已取消 · ") + part.name()
                                        + "\n" + FileStore.size(part.file().length()));
                            }
                            ((ArrayAdapter<?>) ((HeaderViewListAdapter) list.getAdapter()).getWrappedAdapter()).notifyDataSetChanged();
                            status.setText(message + "\n本次将安装 " + selection.size() + " 个 APK");
                        }).show();
            });
            body.addView(options);
        }
        Button install = new Button(this); install.setText("确认安装所选 APK"); install.setMinHeight(Ui.dp(this, 48)); body.addView(install);
        install.setOnClickListener(v -> {
            try { SplitApks.retainSelection(prepared, selection); }
            catch (IOException error) { error(error.getMessage()); return; }
            install.setEnabled(false); preview = null; status.setText(message + (rootInstall
                    ? "\n正在通过 ROOT 静默安装所选分包…" : "\n正在交给系统安装器…"));
            writeAndCommit(prepared);
        });
    }
    private void writeAndCommit(SplitApks.Prepared prepared) {
        Tasks.SHELL.execute(()->{
            try(SplitApks.Prepared owned=prepared){
                if (rootInstall) {
                    ShellRunner runner = new ShellRunner(); privilegedRunner = runner;
                    try {
                        if (cancelled.get()) return;
                        if (owned == null) ApkInstall.silent(single, runner);
                        else SplitApks.installSilently(owned.parts.stream().map(SplitApks.Part::file).toList(),
                                SplitApks.currentUserId(), cancelled, runner);
                        runOnUiThread(() -> {
                            if (!isFinishing() && !isDestroyed()) {
                                if (single != null) ApkInstall.discardStage(this, single);
                                Tasks.toast(this, "安装成功"); finish();
                            }
                        });
                    } finally { privilegedRunner = null; }
                    return;
                }
                int ready=owned==null?SplitApks.writeSingleSession(this,single,cancelled):SplitApks.writeSession(this,owned,cancelled);
                runOnUiThread(()->{
                    if(cancelled.get()||isFinishing()||isDestroyed()){
                        try{installer().abandonSession(ready);}catch(RuntimeException ignored){}return;
                    }
                    sessionId=ready;commit();
                });
            }catch(Exception e){runOnUiThread(()->{if(!cancelled.get()&&!isFinishing()&&!isDestroyed())error(e.getMessage());});}
        });
    }
    private PackageInstaller installer() { return getPackageManager().getPackageInstaller(); }
    private void commit() {
        try (PackageInstaller.Session session = installer().openSession(sessionId)) {
            Intent callback = new Intent(this, SplitInstallActivity.class).setAction(RESULT)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            if(single!=null)callback.putExtra(SINGLE,single.getAbsolutePath());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
            Bundle options = null;
            if (Build.VERSION.SDK_INT >= 35) options = ActivityOptions.makeBasic()
                    .setPendingIntentCreatorBackgroundActivityStartMode(Build.VERSION.SDK_INT >= 36
                            ? ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
                            : ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle();
            PendingIntent result = PendingIntent.getActivity(this, sessionId, callback, flags, options);
            session.commit(result.getIntentSender()); committed = true;
        } catch (Exception e) { abandon(); error(e.getMessage()); }
    }
    private void result(Intent intent) {
        sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId);
        int result = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (result == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm == null) { abandon(); error("系统未返回安装确认界面"); return; }
            status.setText("请在系统安装器中确认安装");
            try { startActivity(confirm); } catch (Exception e) { abandon(); error(e.getMessage()); }
        } else if (result == PackageInstaller.STATUS_SUCCESS) {
            sessionId = -1; Tasks.toast(this, "安装成功"); finish();
        } else {
            sessionId = -1;
            if (result == PackageInstaller.STATUS_FAILURE_ABORTED) { Tasks.toast(this, "已取消安装"); finish(); }
            else error("安装失败\n" + intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
        }
    }
    private void abandon() {
        if (sessionId == -1) return;
        try { installer().abandonSession(sessionId); } catch (RuntimeException ignored) { }
        sessionId = -1;
    }
    private void error(String message) {
        status.setText("无法安装");
        MaterialDialogs.builder(this).setTitle("安装失败").setMessage(message == null ? "无法读取安装包" : message)
                .setPositiveButton("确定", (d, w) -> finish()).setOnCancelListener(d -> finish()).show();
    }
    @Override protected void onDestroy() {
        cancelled.set(true);
        ShellRunner runner = privilegedRunner;
        if (runner != null && isFinishing()) Tasks.CONTROL.execute(runner::stop);
        if (preview != null) { SplitApks.Prepared close = preview; preview = null; Tasks.IO.execute(close::close); }
        if (isFinishing()){abandon();ApkInstall.discardStage(this,single);}
        super.onDestroy();
    }
}
