package org.example.filemanager;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.database.ContentObserver;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.lang.ref.WeakReference;
import rikka.shizuku.Shizuku;

public class MainActivity extends LabActivity {
    private static volatile WeakReference<MainActivity> foreground = new WeakReference<>(null);
    private static final int TREE = 100, ARTIFACT = 200, INSTALLED = 400, BACKUP = 401, SETTINGS = 402, SHIZUKU = 808;
    private FileStore store;
    private AppIcons appIcons;
    private String selectionSession;
    private DirectoryPlaces directoryPlaces;
    private Pane left, right, active;
    private TextView title, subtitle, status;
    private LinearLayout actions, permission;
    private final View[][] actionButtons = new View[2][];
    private FrameLayout headerHost;
    private BrowserChrome chrome;
    private LinearLayout normalHeader;
    private InlineFilterBar inlineFilter;
    private Pane filteringPane;
    private View moreButton;
    private Dialog drawer;
    private DirectorySheet sheet;
    private PopupWindow popupMenu;
    private Dialog frostMore;
    private Dialog fileMenu;
    private long exitArmedAt;
    private int externalGeneration;
    private String handledExternal;
    private boolean showHidden, comfortable;
    private boolean busy, resumed;
    private static final java.util.concurrent.atomic.AtomicBoolean MUTATING = new java.util.concurrent.atomic.AtomicBoolean();
    private Runnable closeMutation;
    private OperationProgressView operationProgress;
    private Boolean selectionActions;
    private final Map<String, DirectorySnapshot> directorySnapshots = new LinkedHashMap<>(24, .75f, true);
    private final Map<String, String> archiveParents = new LinkedHashMap<>();
    private record DirectorySnapshot(List<FileStore.Node> nodes, Map<String, String> metadata, long space, long totalSpace, long time) {}
    private record TransferPreflight(Set<String> names, List<String> conflicts) {}
    private DirectoryPlaces.Entry pendingBookmarkGrant;
    private File pendingDataDirectory;
    private Pane pendingDataPane;
    private boolean requestingDataPermission;
    private final Shizuku.OnRequestPermissionResultListener shizukuPermission = (request, result) -> {
        if (request != SHIZUKU) return;
        requestingDataPermission = false;
        if (result == PackageManager.PERMISSION_GRANTED) resumeDataAccess();
        else { pendingDataDirectory = null; pendingDataPane = null; Tasks.toast(this, "未授予 Shizuku 文件访问权限"); }
    };
    private final Shizuku.OnBinderReceivedListener shizukuReceived = () -> runOnUiThread(this::resumeDataAccess);

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        store = new FileStore(this); appIcons = new AppIcons(this);
        Shizuku.addRequestPermissionResultListener(shizukuPermission);
        Shizuku.addBinderReceivedListenerSticky(shizukuReceived);
        directoryPlaces = new DirectoryPlaces(this);
        showHidden = getPreferences(0).getBoolean("showHidden", true);
        comfortable = getPreferences(0).getBoolean("comfortable", false);
        LinearLayout root = Ui.column(this);
        LinearLayout top = Ui.row(this);
        normalHeader = top;
        top.addView(Ui.icon(this, R.drawable.ic_menu, "位置与工具", Color.WHITE, this::places), lp(48, 58));
        LinearLayout labels = Ui.column(this);
        title = Ui.single(this, "双栈 Lab", 18, Color.WHITE);
        labels.setOnClickListener(v -> jump()); labels.setFocusable(true); labels.setContentDescription("路径跳转");
        labels.setOnLongClickListener(v -> {
            if (active == null || active.location == null) return true;
            ((android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(
                    ClipData.newPlainText("目录路径", active.location.path()));
            Tasks.toast(this, "路径已复制"); return true;
        });
        labels.setTooltipText("路径跳转");
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        subtitle = Ui.single(this, "文件管理", 12, Ui.material == ThemeMaterial.CLASSIC ? 0xffc0d0cd : 0xffe3ecef);
        labels.addView(title, new LinearLayout.LayoutParams(-1, scaled(29)));
        labels.addView(subtitle, new LinearLayout.LayoutParams(-1, scaled(20)));
        top.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        moreButton = Ui.icon(this, R.drawable.ic_more, "更多操作", Color.WHITE, this::more);
        top.addView(moreButton, lp(48, 54));
        headerHost = new FrameLayout(this); ThemeMaterial.paint(headerHost, ThemeMaterial.Surface.HEADER);
        headerHost.addView(top, new FrameLayout.LayoutParams(-1, -1));
        permission = Ui.row(this);
        permission.setPadding(dp(12), 0, dp(6), 0); permission.setBackgroundColor(Ui.WARNING);
        TextView explain = Ui.text(this, "存储访问权限未开启", 12, Ui.TEXT);
        permission.addView(explain, new LinearLayout.LayoutParams(0, dp(48), 1));
        TextView allow = Ui.text(this, "开启权限", 12, Ui.ACCENT);
        allow.setPadding(dp(10), 0, dp(10), 0); allow.setOnClickListener(v -> storagePermission());
        permission.addView(allow, lp(-2, 48));
        LinearLayout panes = new DualPaneLayout(this); panes.setBackgroundColor(Ui.SURFACE);
        left = new Pane(true); right = new Pane(false);
        panes.addView(left, new LinearLayout.LayoutParams(0, -1, 1));
        panes.addView(right, new LinearLayout.LayoutParams(0, -1, 1));
        status = Ui.single(this, "", 12, Ui.MUTED); status.setVisibility(View.GONE);
        status.setPadding(dp(12), 0, dp(12), 0); status.setBackgroundColor(Ui.PANEL);
        DirectorySheet.SwipeLayout actionGestures = new DirectorySheet.SwipeLayout(this, true, this::directorySheet);
        actions = actionGestures;
        actionGestures.setDragCallbacks(() -> {
                    if (FileManagerSettings.prefs(this).getBoolean("gesture-side", false))
                        activate(actionGestures.startFraction() < .5f ? left : right);
                    directorySheet(true);
                },
                distance -> { if (sheet != null) sheet.drag(distance); },
                commit -> { if (sheet != null) sheet.finishDrag(commit); });
        actions.setOrientation(LinearLayout.HORIZONTAL); actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setContentDescription("底部操作栏"); ThemeMaterial.paint(actions, ThemeMaterial.Surface.FOOTER);
        chrome=new BrowserChrome(this,panes,headerHost,normalHeader,labels,title,subtitle,permission,status,actions,
                Math.max(dp(58),scaled(49)+dp(9)),(topInset,bottomInset)->{
                    left.chromeInsets(topInset,bottomInset);right.chromeInsets(topInset,bottomInset);
                });
        root.addView(chrome,new LinearLayout.LayoutParams(-1,0,1));
        operationProgress = new OperationProgressView(this); chrome.progress(operationProgress);
        Ui.install(this, root);
        chrome.apply(root.getChildAt(0));
        int indicatorVisibility=Ui.material==ThemeMaterial.FROST?View.INVISIBLE:View.VISIBLE;
        left.indicator.setVisibility(indicatorVisibility);right.indicator.setVisibility(indicatorVisibility);
        activate(left);
        selectionSession = saved == null ? null : saved.getString("selectionSession");
        if (selectionSession == null) selectionSession = UUID.randomUUID().toString();
        if (saved == null) { restoreFilter(left); restoreFilter(right); }
        restore(left, saved); restore(right, saved);
        if (saved != null) {
            Bundle parents = saved.getBundle("archiveParents");
            if (parents != null) for (String key : parents.keySet()) archiveParents.put(key, parents.getString(key));
            restorePaneState(saved, "leftPane", left); restorePaneState(saved, "rightPane", right);
            if (saved.getBoolean("activeRight")) activate(right);
        }
        if (saved != null) handledExternal = saved.getString("handledExternal");
        if (saved == null || ExternalFiles.uri(getIntent()) != null) navigateIntent(getIntent());
        if (saved == null) {
            EditorDrafts.WORKER.execute(() -> EditorDrafts.prune(getApplicationContext(), 14L * 24 * 60 * 60 * 1000));
            Tasks.IO.execute(() -> SplitApks.cleanAbandonedWorkspaces(getCacheDir()));
            SafRecovery.offerPending(this);
            RenameJournal.offerPending(this);
        }
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent); externalGeneration++; handledExternal = null; navigateIntent(intent);
    }
    private void navigateIntent(Intent intent) {
        if (intent == null) return;
        String resultKey = intent.getStringExtra("searchResult");
        if (resultKey != null) {
            intent.removeExtra("searchResult");
            Pane target = active;
            int token = ++externalGeneration;
            Tasks.run(this, () -> store.fromKey(resultKey), node -> {
                if (token != externalGeneration) return;
                FileStore.Node parent = store.parent(node);
                if (parent != null) {
                    clearSearchMarks();
                    target.navigate(parent);
                    target.mark(node);
                } else if (node.directory) target.navigate(node);
                else Tasks.toast(this, "来源未提供可定位的目录");
            });
            return;
        }
        Uri external = ExternalFiles.uri(intent);
        if (external != null) {
            String key = intent.getAction() + ":" + external;
            if (key.equals(handledExternal)) return;
            int token = ++externalGeneration;
            subtitle.setText("读取外部文件…");
            Tasks.IO.execute(() -> {
                try {
                    ExternalFiles.Received received = ExternalFiles.receive(this, external, intent.getFlags());
                    runOnUiThread(() -> {
                        if (token != externalGeneration || isDestroyed() || isFinishing()) return;
                        handledExternal = key;
                        activate(left);
                        if (received.directory() != null) {
                            left.reveal = received.node().name;
                            left.navigate(FileStore.directory(received.directory(), received.node().root));
                        } else {
                            status.setVisibility(View.GONE);
                            Tasks.toast(this, "来源未提供可访问的实际目录，直接打开原文件");
                            open(received.node(), true);
                        }
                    });
                } catch (Exception error) {
                    runOnUiThread(() -> {
                        if (token != externalGeneration || isDestroyed() || isFinishing()) return;
                        status.setText("外部文件读取失败"); Tasks.showError(this, error);
                    });
                }
            });
            return;
        }
        String path = intent.getStringExtra("navigatePath");
        if (path != null) { activate(left); left.navigate(FileStore.directory(new File(path), false)); }
    }
    private void restore(Pane pane, Bundle saved) {
        String key = pane.isLeft ? "left" : "right";
        String path = saved != null ? saved.getString(key) : FileManagerSettings.prefs(this).getString("startup-" + key, "home").equals("last")
                ? getPreferences(0).getString(key, null) : getPreferences(0).getString("home-" + key, getPreferences(0).getString(key, null));
        if (path != null && (path.startsWith("zip:") || path.startsWith("content:"))) {
            pane.location = FileStore.directory(Environment.getExternalStorageDirectory(), false);
            int token = ++pane.generation;
            Tasks.run(this, () -> store.fromKey(path), node -> {
                if (token != pane.generation) return;
                pane.location = StorageNavigation.location(node); pane.reload();
            }, error -> { if (token == pane.generation) {
                pane.reload(); Tasks.toast(this, "保存的位置不可读取，已打开内部存储");
            } });
            return;
        }
        try {
            if (path != null && path.startsWith("root:")) {
                pane.location = FileStore.directory(new File(path.substring(5)), true);
            } else pane.location = FileStore.directory(new File(path == null ?
                    Environment.getExternalStorageDirectory().getAbsolutePath() : path), false);
        } catch (Exception e) { pane.location = FileStore.directory(Environment.getExternalStorageDirectory(), false); }
        pane.reload();
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        if (inlineFilter != null) inlineFilter.finishLastValid();
        super.onSaveInstanceState(out);
        out.putString("selectionSession", selectionSession);
        out.putString("left", locationKey(left.location)); out.putString("right", locationKey(right.location));
        out.putBoolean("activeRight", active == right);
        Bundle parents = new Bundle();
        archiveParents.forEach(parents::putString); out.putBundle("archiveParents", parents);
        savePaneState(out, "leftPane", left); savePaneState(out, "rightPane", right);
        out.putString("handledExternal", handledExternal);
    }
    private void savePaneState(Bundle out, String key, Pane pane) {
        out.putString(key + ".filter", pane.filter); out.putBoolean(key + ".regex", pane.regex); out.putInt(key + ".sort", pane.sort);
        PaneSelections.save(this, out, key + ".selected", pane.selected, selectionSession);
        int[] pending = pane.location != null && (pane.pendingRestoreKey != null || pane.loading != null)
                ? pane.positions.get(pane.location.key()) : null;
        out.putInt(key + ".position", pending == null ? pane.list.getFirstVisiblePosition() : pending[0]);
        View first = pane.list.getChildAt(0);
        out.putInt(key + ".top", pending != null ? pending[1] : first == null ? 0 : first.getTop()-pane.list.getPaddingTop());
        ArrayList<String> back = new ArrayList<>(), forward = new ArrayList<>();
        for (FileStore.Node node : pane.backHistory) back.add(node.key());
        for (FileStore.Node node : pane.forwardHistory) forward.add(node.key());
        out.putStringArrayList(key + ".back", back); out.putStringArrayList(key + ".forward", forward);
    }
    private void restorePaneState(Bundle saved, String key, Pane pane) {
        pane.filter = saved.getString(key + ".filter", ""); pane.regex = saved.getBoolean(key + ".regex", false); pane.sort = saved.getInt(key + ".sort");
        PaneSelections.restore(this, saved, key + ".selected", pane.selected);
        String restoredKey = saved.getString(pane.isLeft ? "left" : "right");
        if (restoredKey != null) pane.positions.put(restoredKey, new int[]{saved.getInt(key + ".position"), saved.getInt(key + ".top")});
        pane.restoreHistory(saved.getStringArrayList(key + ".back"), saved.getStringArrayList(key + ".forward"));
    }
    private void restoreFilter(Pane pane) {
        String suffix = pane.isLeft ? "left" : "right";
        pane.filter = getPreferences(0).getString("filter-" + suffix, "");
        pane.regex = getPreferences(0).getBoolean("regex-" + suffix, false);
        pane.sort = getPreferences(0).getInt("sort-" + suffix, 0);
    }
    private void persistFilter(Pane pane) {
        String suffix = pane.isLeft ? "left" : "right";
        getPreferences(0).edit().putString("filter-" + suffix, pane.filter)
                .putBoolean("regex-" + suffix, pane.regex).apply();
    }
    @Override protected void onPause() {
        super.onPause();
        if (foreground.get() == this) foreground = new WeakReference<>(null);
        if (left != null) left.stopWatching(); if (right != null) right.stopWatching();
        exitArmedAt = 0;
        getPreferences(0).edit().putString("left", locationKey(left.location))
                .putString("right", locationKey(right.location)).apply();
    }
    /** Saved keys keep shared-storage paths ROOT-free so a revoked su grant never hides reachable files. */
    private static String locationKey(FileStore.Node node) {
        if (node != null && DataDocumentsProvider.owns(node.uri)) return DataDocumentsProvider.path(node.uri);
        return node != null && node.root && node.file != null ? node.path() : node == null ? "" : node.key();
    }
    @Override protected void onResume() {
        super.onResume();
        foreground = new WeakReference<>(this);
        Tasks.Mutation persistedMutation = Tasks.activeMutation();
        if (persistedMutation != null && persistedMutation.owner.get() != this) {
            if (persistedMutation.finished) recoverMutation(persistedMutation);
            else {
                busy = true; header();
                if (persistedMutation.showProgress && closeMutation == null)
                    closeMutation = showMutationProgress(persistedMutation.progress);
            }
        }
        resumeDataAccess();
        if (permission != null) {
            permission.setVisibility(hasStorage() ? View.GONE : View.VISIBLE);
            if (resumed) { directorySnapshots.clear(); left.reload(); right.reload(); }
            resumed = true;
        }
    }
    private boolean hasStorage() {
        return Build.VERSION.SDK_INT >= 30 ? Environment.isExternalStorageManager() :
                checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }
    private void storagePermission() {
        if (Build.VERSION.SDK_INT >= 30) {
            try { startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName()))); }
            catch (ActivityNotFoundException e) { startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)); }
        } else requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request == 1) {
            permission.setVisibility(hasStorage() ? View.GONE : View.VISIBLE);
            left.reload(); right.reload();
        }
    }
    private int dp(int n) { return Ui.dp(this, n); }
    /** Row sizes follow the user's font scale so larger system text is not clipped. */
    private int scaled(int n) {
        return Math.round(dp(n) * Math.max(1f, getResources().getConfiguration().fontScale));
    }
    private int rowHeight() { return scaled(comfortable ? 52 : 48); }
    private LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w < 0 ? w : dp(w), h < 0 ? h : dp(h));
    }
    private void activate(Pane p) {
        if (active == p) return;
        if (inlineFilter != null) inlineFilter.finishLastValid();
        active = p;
        Ui.fadeBackground(left.indicator, p == left ? Ui.ACCENT : Ui.LINE);
        Ui.fadeBackground(right.indicator, p == right ? Ui.ACCENT : Ui.LINE);
        if (chrome!=null) chrome.active(p==left);
        header(); actionBar();
    }
    private void header() {
        if (active == null || active.location == null) return;
        int folders = active.folderCount;
        subtitle.setText("目录 " + folders + "  文件 " + (active.entries.size() - folders)
                + (active.totalSpace > 0 ? " · 可用 " + FileStore.size(active.usableSpace)
                + "/" + FileStore.size(active.totalSpace) : ""));
        title.setText(active.location.path() + (active.location.file != null
                && !active.location.path().endsWith("/") ? "/" : ""));
        title.setContentDescription("双栈 Lab · " + active.location.path());
    }
    private void actionBar() {
        if (active == null) return;
        boolean selection = !active.selected.isEmpty();
        if (!selection) { active.rangeSelecting = false; active.selectionAnchor = null; }
        boolean rebuild = selectionActions == null || selectionActions != selection;
        boolean modeChanged = selectionActions != null && rebuild;
        selectionActions = selection;
        int mode = selection ? 1 : 0;
        boolean buildButtons = rebuild && actionButtons[mode] == null;
        if (rebuild) {
            actions.removeAllViews();
            if (!buildButtons) for (View button : actionButtons[mode]) actions.addView(button);
        }
        if (modeChanged && actions.isShown()) Ui.fadeIn(actions);
        if (selection) {
            if (buildButtons) {
                View all = addSelectionAction(R.drawable.ic_select_all, "全选", () -> active.selectAll());
                all.setOnLongClickListener(v -> { conditionalSelection(); return true; });
                addSelectionAction(R.drawable.ic_swap, "反选", this::invertVisibleSelection);
                addSelectionAction(R.drawable.ic_stop, "取消", () -> { active.resetSelection(); active.selectionChanged(); });
                View range = addSelectionAction(R.drawable.ic_sort, "连选", () -> active.beginRange());
                range.setTooltipText("点击连选，长按按条件选择");
                range.setOnLongClickListener(v -> { conditionalSelection(); return true; });
                addSelectionAction(R.drawable.ic_more, "操作", this::selectionMenu);
            }
            applyActionSelection(actions.getChildAt(3), active.rangeSelecting);
            subtitle.setText("已选择 " + active.selected.size() + " 项");
        } else {
            header();
            if (buildButtons) {
                addAction(R.drawable.ic_back, "后退", () -> active.back());
                addAction(R.drawable.ic_back, "前进", () -> active.forward());
                ((LinearLayout) actions.getChildAt(1)).getChildAt(0).setRotation(180);
                for (int i = 0; i < 2; i++) {
                    boolean forward = i == 1;
                    View button = actions.getChildAt(i);
                    button.setTooltipText(forward ? "前进，长按选择历史目录" : "后退，长按选择历史目录");
                    button.setOnLongClickListener(v -> { showHistory(active, forward); return true; });
                }
                addAction(R.drawable.ic_plus, "新建", this::createDialog);
                addAction(R.drawable.ic_swap, "同步另一窗口", this::synchronizeOtherPane);
                View sync = actions.getChildAt(3);
                sync.setTooltipText("点击同步另一窗口，长按快速过滤");
                sync.setOnLongClickListener(v -> { quickFilter(); return true; });
                addAction(R.drawable.ic_up, "上一级", () -> active.up());
                actions.getChildAt(4).setTooltipText("点击返回上级，长按输入路径");
                actions.getChildAt(4).setOnLongClickListener(v -> { jump(); return true; });
            }
            actions.getChildAt(0).setEnabled(!active.backHistory.isEmpty());
            actions.getChildAt(0).setAlpha(active.backHistory.isEmpty() ? .28f : 1f);
            actions.getChildAt(1).setEnabled(!active.forwardHistory.isEmpty());
            actions.getChildAt(1).setAlpha(active.forwardHistory.isEmpty() ? .28f : 1f);
            applyActionSelection(actions.getChildAt(3), !active.filter.isEmpty());
            status.setVisibility(View.GONE);
        }
        if (buildButtons) {
            View[] buttons = new View[actions.getChildCount()];
            for (int i = 0; i < buttons.length; i++) buttons[i] = actions.getChildAt(i);
            actionButtons[mode] = buttons;
        }
    }
    private static void applyActionSelection(View button, boolean on) {
        if (button.isSelected() == on) return;
        button.setSelected(on);
        BrowserChrome.commandFeedback(button,on);
    }
    private void synchronizeOtherPane() {
        if (active == null || active.location == null) return;
        Pane target = other();
        if (active.location.container != null) {
            FileStore.Node archive = active.location.container;
            FileStore.Node parent = StorageNavigation.parent(store, archive);
            if (parent == null) {
                String parentKey = archiveParents.get(archive.key());
                if (parentKey == null) { Tasks.toast(this, "未找到压缩包所在目录，请从目录重新打开压缩包"); return; }
                int targetGeneration = target.generation;
                Tasks.run(this, Tasks.BROWSE, () -> store.fromKey(parentKey), folder -> {
                    if (target.generation == targetGeneration) target.locate(folder, archive.key(), archive.name);
                }, error -> Tasks.showError(this, error));
                return;
            }
            target.locate(parent, archive.key(), archive.name);
        } else if (target.location == null || !target.location.key().equals(active.location.key())) target.navigate(active.location);
    }
    private void quickFilter() {
        if (inlineFilter != null) { inlineFilter.focus(); return; }
        Pane pane = active;
        int first = pane.list.getFirstVisiblePosition();
        View child = pane.list.getChildAt(0); int top = child == null ? 0 : child.getTop()-pane.list.getPaddingTop();
        String original = pane.filter; boolean originalRegex = pane.regex;
        Set<String> selection = new HashSet<>(pane.selected);
        filteringPane = pane;
        inlineFilter = new InlineFilterBar(this, pane.filter, pane.regex, new InlineFilterBar.Listener() {
            public void apply(String text, boolean regex) { pane.filter = text; pane.regex = regex; pane.apply(); actionBar(); }
            public void close(boolean cancel) {
                InlineFilterBar closing = inlineFilter; inlineFilter = null; filteringPane = null;
                if (closing != null) headerHost.removeView(closing);
                normalHeader.setVisibility(View.VISIBLE); Ui.fadeIn(normalHeader);
                chrome.filter(null);
                if (cancel) {
                    pane.filter = original; pane.regex = originalRegex;
                    pane.selected.clear(); pane.selected.addAll(selection);
                    pane.apply(() -> pane.list.setSelectionFromTop(first, top));
                } else persistFilter(pane);
                actionBar();
            }
        });
        normalHeader.setVisibility(View.GONE);
        headerHost.addView(inlineFilter, new FrameLayout.LayoutParams(-1, -1));
        chrome.filter(inlineFilter);
        Ui.fadeIn(inlineFilter); inlineFilter.focus();
    }
    private void filterDialog(boolean live) {
        if (inlineFilter != null) inlineFilter.finishLastValid();
        Pane pane = active;
        int first = pane.list.getFirstVisiblePosition();
        View child = pane.list.getChildAt(0); int top = child == null ? 0 : child.getTop()-pane.list.getPaddingTop();
        String original = pane.filter; boolean originalRegex = pane.regex;
        LinearLayout body = Ui.column(this); body.setPadding(dp(20), dp(8), dp(20), 0);
        EditText input = Ui.input(this, "输入文件名关键字"); input.setSingleLine(true);
        input.setContentDescription("快速过滤关键字"); input.setText(pane.filter);
        CheckBox regex = new CheckBox(this); regex.setText("正则表达式"); regex.setChecked(pane.regex);
        regex.setMinHeight(dp(48)); regex.setTextColor(Ui.TEXT);
        TextView help = Ui.text(this, "!关键字：排除  /表达式：正则  !/表达式：排除正则", 12, Ui.MUTED);
        help.setPadding(0, dp(4), 0, dp(8));
        body.addView(input); body.addView(regex); body.addView(help);
        android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        java.util.function.BooleanSupplier valid = () -> {
            try { FileNameFilter.compile(input.getText().toString(), regex.isChecked()); input.setError(null); return true; }
            catch (java.util.regex.PatternSyntaxException error) { input.setError("正则表达式无效，请检查括号和转义"); return false; }
        };
        Runnable apply = () -> {
            if (!valid.getAsBoolean()) return;
            pane.filter = input.getText().toString(); pane.regex = regex.isChecked(); pane.apply(); actionBar();
        };
        Runnable restore = () -> {
            handler.removeCallbacks(apply); pane.filter = original; pane.regex = originalRegex;
            pane.apply(() -> pane.list.setSelectionFromTop(first, top));
        };
        AlertDialog dialog = MaterialDialogs.builder(this).setTitle(live ? "快速过滤" : "当前目录过滤").setView(body)
                .setNegativeButton("取消", (d, w) -> restore.run())
                .setNeutralButton("清除", (d, w) -> { pane.filter = ""; pane.regex = false; persistFilter(pane); pane.apply(); })
                .setPositiveButton(live ? "完成" : "应用", null).create();
        dialog.setOnCancelListener(d -> restore.run());
        dialog.setOnDismissListener(d -> { handler.removeCallbacksAndMessages(null); actionBar(); });
        Runnable changed = () -> { handler.removeCallbacks(apply); if (live) handler.postDelayed(apply, 100); else valid.getAsBoolean(); };
        watch(input, changed); regex.setOnCheckedChangeListener((button, checked) -> changed.run());
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                handler.removeCallbacks(apply); if (!valid.getAsBoolean()) return;
                apply.run(); persistFilter(pane); dialog.dismiss();
            });
            input.requestFocus(); input.setSelection(input.length());
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        });
        dialog.show();
    }
    private void addAction(int icon, String text, Runnable task) {
        String label = text.equals("同步另一窗口") ? "同步" : text;
        View button = Ui.command(this, icon, label, task);
        BrowserChrome.command(button);
        button.setContentDescription(text);
        actions.addView(button, new LinearLayout.LayoutParams(0, -1, 1));
    }
    private void showHistory(Pane pane, boolean forward) {
        ArrayDeque<FileStore.Node> history = forward ? pane.forwardHistory : pane.backHistory;
        List<FileStore.Node> options = new ArrayList<>(history);
        if (options.isEmpty()) return;
        String[] paths = options.stream().map(FileStore.Node::path).toArray(String[]::new);
        MaterialDialogs.builder(this).setTitle(forward ? "前进到目录" : "后退到目录")
                .setItems(paths, (dialog, index) -> pane.travel(forward, options.get(index)))
                .setNegativeButton("取消", null).show();
    }
    private View addSelectionAction(int icon, String text, Runnable task) {
        View button = Ui.command(this, icon, text, task);
        BrowserChrome.command(button);
        actions.addView(button, new LinearLayout.LayoutParams(0, -1, 1));
        return button;
    }
    private void conditionalSelection() {
        Pane pane = active;
        pane.rangeSelecting = false;
        FileStore.Node reference = pane.selectionReference();
        fileMenu = SelectionTools.show(this, pane.entries, reference, result -> {
            if (!result.append()) pane.selected.clear();
            pane.selected.addAll(result.keys());
            pane.selectionChanged();
        });
        actionBar();
    }
    private Pane other() { return active == left ? right : left; }
    private void places() {
        drawer = NavigationDrawer.show(this, () -> navigateDirectory(new File("/"), true),
                () -> active.navigate(FileStore.directory(Environment.getExternalStorageDirectory(), false)), this::installed,
                this::openRemovable, this::chooseDirectory, entry -> openBookmark(active, entry));
    }

    private void openRemovable(RemovableStorage.Volume volume) {
        Pane target = active;
        if (!volume.mounted()) { Tasks.toast(this, "系统尚未挂载此存储，请先在系统文件管理器中打开"); return; }
        Tasks.run(this, () -> RemovableStorage.open(this, store, volume), node -> {
            if (node != null) target.navigate(node);
            else try { startActivityForResult(volume.authorization(), TREE + (target == left ? 0 : 1)); }
            catch (ActivityNotFoundException error) { Tasks.showError(this, error); }
        });
    }

    private void navigateDirectory(File path, boolean preferRoot) {
        Pane target = active;
        Tasks.run(this, () -> RootAccess.directory(path, preferRoot), node -> {
            target.navigate(node);
            if (preferRoot) Tasks.toast(this, node.root ? "已使用 ROOT 访问" : "su 不可用，使用普通权限访问");
        });
    }
    private void directorySheet() { directorySheet(false); }
    private void directorySheet(boolean dragging) {
        Pane target = active;
        if (target == null || target.location == null) return;
        if (sheet != null) sheet.dismiss();
        sheet = new DirectorySheet(this, directoryPlaces, target.location, entry -> openBookmark(target, entry));
        if (dragging) sheet.showForDrag(); else sheet.show();
    }
    private void openBookmark(Pane target, DirectoryPlaces.Entry entry) {
            if (SettingsBackup.needsGrant(this, entry) && !DataDocumentsProvider.owns(Uri.parse(entry.key()))) {
                pendingBookmarkGrant = entry.key().startsWith("content:") ? entry : null;
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                String source = entry.key(); int depth = 0;
                while (source.startsWith("zip:") && depth++ < 8) source = Uri.parse(source).getQueryParameter("source");
                if (source != null) intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(source));
                try { startActivityForResult(intent, TREE + (target.isLeft ? 0 : 1)); }
                catch (Exception error) { pendingBookmarkGrant = null; Tasks.showError(this, error); }
            } else {
                int generation = target.generation;
                Tasks.run(this, () -> entry.destination(store), destination -> {
                    if (target.generation != generation) return;
                    activate(target);
                    if (destination.focusKey() == null) target.navigate(destination.directory());
                    else target.locate(destination.directory(), destination.focusKey(), destination.focusName());
                });
            }
    }
    private void chooseDirectory() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        File home = Environment.getExternalStorageDirectory();
        File current = active.location.file;
        if (current != null && current.getAbsolutePath().startsWith(home.getAbsolutePath() + "/")) {
            String relative = current.getAbsolutePath().substring(home.getAbsolutePath().length() + 1);
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUri(
                    "com.android.externalstorage.documents", "primary:" + relative));
        }
        try { startActivityForResult(intent, TREE + (active == left ? 0 : 1)); }
        catch (ActivityNotFoundException error) { Tasks.showError(this, error); }
    }
    private void dataAccess(Pane pane, File directory) {
        MaterialDialogs.builder(this).setTitle("访问 Android/data")
                .setMessage("此目录受系统限制。可通过 Shizuku 授权访问，部分系统也支持目录授权。")
                .setItems(new String[]{"使用 Shizuku（无 ROOT）", "系统目录授权", "所有文件访问权限"}, (dialog, which) -> {
                    if (which == 0) {
                        FileManagerSettings.prefs(this).edit().putBoolean("auto-shizuku", true).apply();
                        pendingDataPane = pane; pendingDataDirectory = directory;
                        if (ShizukuStorage.running()) resumeDataAccess();
                        else {
                            Intent manager = getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
                            if (manager != null) startActivity(manager);
                            else startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")));
                            Tasks.toast(this, "启动 Shizuku 后返回，继续授权访问");
                        }
                    } else if (which == 1) {
                        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                        String home = Environment.getExternalStorageDirectory().getPath();
                        String relative = directory.getPath().startsWith(home + "/") ? directory.getPath().substring(home.length() + 1) : "Android/data";
                        intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUri(
                                "com.android.externalstorage.documents", "primary:" + relative));
                        try { startActivityForResult(intent, TREE + (pane == left ? 0 : 1)); }
                        catch (ActivityNotFoundException error) { Tasks.showError(this, error); }
                    } else storagePermission();
                }).setNegativeButton("取消", null).show();
    }
    private void resumeDataAccess() {
        if (pendingDataDirectory == null || pendingDataPane == null || isFinishing() || isDestroyed() || !ShizukuStorage.running()) return;
        if (!ShizukuStorage.granted()) {
            if (!requestingDataPermission) {
                requestingDataPermission = true;
                try { Shizuku.requestPermission(SHIZUKU); }
                catch (RuntimeException disconnected) { requestingDataPermission = false; Tasks.toast(this, "Shizuku 已断开，请重新启动"); }
            }
            return;
        }
        File directory = pendingDataDirectory; Pane pane = pendingDataPane;
        pendingDataDirectory = null; pendingDataPane = null;
        pane.navigate(FileStore.directory(directory, false));
    }
    private void more() {
        String[] names = {"刷新", "搜索", "全选", "反选当前列表", "过滤", "排序方式", "打开终端", "隐藏文件", "添加书签", "设为首页", "交换窗口", "设置", "退出", "最近与书签", "按条件选择"};
        int[] icons = {R.drawable.ic_refresh, R.drawable.ic_search, R.drawable.ic_select_all, R.drawable.ic_check,
                R.drawable.ic_filter, R.drawable.ic_sort, R.drawable.ic_terminal, R.drawable.ic_eye, R.drawable.ic_bookmark,
                R.drawable.ic_home, R.drawable.ic_swap, R.drawable.ic_settings, R.drawable.ic_exit, R.drawable.ic_bookmark, R.drawable.ic_filter};
        names = Arrays.copyOf(names, names.length + 1); names[names.length - 1] = "比较另一窗口";
        icons = Arrays.copyOf(icons, icons.length + 1); icons[icons.length - 1] = R.drawable.ic_search;
        names = Arrays.copyOf(names, names.length + 2); names[16] = "目录占用排行"; names[17] = "重复文件查找";
        icons = Arrays.copyOf(icons, icons.length + 2); icons[16] = R.drawable.ic_sort; icons[17] = R.drawable.ic_search;
        if (active.location.container != null) {
            names = Arrays.copyOf(names, names.length + 1); names[names.length - 1] = "解压当前目录";
            icons = Arrays.copyOf(icons, icons.length + 1); icons[icons.length - 1] = R.drawable.ic_download;
        }
        if (Ui.material == ThemeMaterial.FROST) {
            if (frostMore != null && frostMore.isShowing()) return;
            Dialog menu = FrostMoreMenu.show(this, moreButton, names, icons, this::menuAction);
            frostMore = menu;
            menu.setOnDismissListener(d -> { if (frostMore == menu) frostMore = null; });
            return;
        }
        LinearLayout menu = Ui.column(this); menu.setPadding(dp(8), dp(8), dp(8), dp(10));
        ScrollView scroll = new ScrollView(this); scroll.setContentDescription("更多操作列表"); scroll.addView(menu);
        scroll.setBackground(Ui.material.card(this,Ui.SURFACE,SettingsDesign.radius(20))); scroll.setClipToOutline(true);
        android.graphics.Rect visible = new android.graphics.Rect();
        moreButton.getWindowVisibleDisplayFrame(visible);
        int[] anchor = new int[2]; moreButton.getLocationOnScreen(anchor);
        int room = visible.bottom - anchor[1] - moreButton.getHeight() - dp(8);
        PopupWindow popup = new PopupWindow(scroll, Math.max(1, Math.min(dp(292), visible.width() - dp(16))),
                Math.max(dp(48), Math.min(dp(names.length * 52 + 120), room)), true);
        popupMenu = popup;
        popup.setBackgroundDrawable(Ui.material.card(this,Ui.SURFACE,SettingsDesign.radius(20))); popup.setElevation(dp(12)); popup.setOutsideTouchable(true);
        if (Ui.motionEnabled()) {
            android.transition.Fade fade = new android.transition.Fade();
            fade.setDuration(Ui.MOTION_PANEL); fade.setInterpolator(Ui.EASING);
            popup.setEnterTransition(fade); popup.setExitTransition(fade.clone());
        }
        else { popup.setEnterTransition(null); popup.setExitTransition(null); }
        String[] groups = {"查找与显示", "选择与整理", "位置与应用"};
        int[][] sections = {{1, 4, 5, 7, 0}, {2, 3, 14, 15, 16, 17, 18}, {13, 8, 9, 10, 6, 11, 12}};
        for (int group = 0; group < sections.length; group++) {
            TextView label = Ui.text(this, groups[group], 11, Ui.MUTED); label.setTypeface(null, android.graphics.Typeface.BOLD);
            label.setPadding(dp(12), dp(group == 0 ? 6 : 16), 0, dp(6)); menu.addView(label);
            for (int which : sections[group]) {
                if (which >= names.length) continue;
                LinearLayout row = Ui.row(this); row.setPadding(dp(12), dp(8), dp(10), dp(8)); row.setMinimumHeight(dp(52));
                ImageView icon = Ui.image(this, icons[which], Ui.ACCENT); icon.setPadding(dp(6), dp(6), dp(6), dp(6));
                icon.setBackground(Ui.background(Ui.TINT, dp(10))); row.addView(icon, lp(32, 32));
                TextView text = Ui.text(this, names[which], 15, Ui.TEXT); text.setPadding(dp(12), 0, 0, 0);
                row.addView(text, new LinearLayout.LayoutParams(0, -2, 1)); row.setBackground(MenuSurfaces.surface(this,Ui.material==ThemeMaterial.CLASSIC?Ui.SURFACE:Color.TRANSPARENT,12));
                row.setContentDescription(names[which]); row.setFocusable(true);
                row.setOnClickListener(v -> { popup.dismiss(); menuAction(which); }); menu.addView(row);
            }
        }
        popup.showAsDropDown(moreButton, -dp(4), 0, Gravity.END);
    }
    private void menuAction(int which) {
        switch (which) {
            case 0 -> active.reload(true);
            case 1 -> searchDialog();
            case 2 -> active.selectAll();
            case 3 -> invertVisibleSelection();
            case 4 -> filterDialog();
            case 5 -> MaterialDialogs.builder(this).setTitle("排序方式").setSingleChoiceItems(new String[]{"名称", "时间", "大小"},
                    active.sort, (d, n) -> { active.sort = n; getPreferences(0).edit().putInt("sort-" + (active.isLeft ? "left" : "right"), n).apply(); active.apply(); d.dismiss(); }).show();
            case 6 -> shellDialog();
            case 7 -> hiddenOptions();
            case 8 -> { directoryPlaces.bookmark(DirectoryPlaces.Entry.from(active.location)); Tasks.toast(this, "已添加书签"); }
            case 9 -> { getPreferences(0).edit().putString("home-" + (active == left ? "left" : "right"), locationKey(active.location)).apply(); Tasks.toast(this, "已设为当前窗口首页"); }
            case 10 -> { FileStore.Node a = left.location, b = right.location; left.navigate(b); right.navigate(a); }
            case 11 -> settings();
            case 12 -> finish();
            case 13 -> directorySheet();
            case 14 -> conditionalSelection();
            case 15 -> comparePanes();
            case 16, 17 -> startActivity(new Intent(this, StorageAnalysisActivity.class).putExtra("directory", active.location.key())
                    .putExtra("label", active.location.path()).putExtra("duplicates", which == 17));
            case 18 -> extractDialog(List.of(active.location));
        }
    }
    private void comparePanes() {
        FileStore.Node first = active.location, second = other().location;
        Tasks.run(this, Tasks.BROWSE, () -> {
            return DirectoryCompare.compare(store.list(first), store.list(second));
        }, differences -> {
            LinearLayout body = Ui.column(this); body.setPadding(dp(16), dp(6), dp(16), dp(6));
            TextView summary = Ui.text(this, differences.isEmpty() ? "两窗口当前层元数据一致" : "发现 " + differences.size() + " 项差异", 14, Ui.TEXT);
            summary.setPadding(0, dp(8), 0, dp(10)); body.addView(summary);
            List<String> lines = new ArrayList<>();
            for (DirectoryCompare.Difference difference : differences)
                lines.add(difference.label() + " · " + difference.name());
            ListView detail = new ListView(this); detail.setContentDescription("窗口差异列表");
            detail.setDivider(null);
            detail.setAdapter(new BaseAdapter() {
                @Override public int getCount() { return lines.size(); }
                @Override public Object getItem(int position) { return lines.get(position); }
                @Override public long getItemId(int position) { return position; }
                @Override public View getView(int position, View recycled, ViewGroup parent) {
                    TextView row = recycled instanceof TextView text ? text : Ui.text(MainActivity.this, "", 13, Ui.TEXT);
                    String line = lines.get(position); row.setText(line); row.setContentDescription(line);
                    row.setGravity(Gravity.CENTER_VERTICAL); row.setMinHeight(dp(48)); row.setPadding(0, dp(8), 0, dp(8));
                    return row;
                }
            });
            detail.setOnItemLongClickListener((parent, view, position, id) -> {
                ((android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("窗口差异", lines.get(position)));
                Tasks.toast(this, "已复制此项差异"); return true;
            });
            int height = Math.min(dp(360), getResources().getDisplayMetrics().heightPixels / 2);
            if (!lines.isEmpty()) body.addView(detail, new LinearLayout.LayoutParams(-1, height));
            String note = "只比较当前层的名称、类型、大小和修改时间，不读取文件内容。";
            AlertDialog.Builder report = MaterialDialogs.builder(this).setTitle("窗口差异").setMessage(note).setView(body)
                    .setPositiveButton("关闭", null).setNegativeButton("内容校验…", (dialog, which) -> startActivity(
                            new Intent(this, DirectoryCompareActivity.class).putExtra("left", first.key()).putExtra("right", second.key())));
            if (!lines.isEmpty()) report.setNeutralButton("复制全部", (dialog, which) -> {
                try {
                    ((android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(
                            ClipData.newPlainText("窗口差异", String.join("\n", lines)));
                    Tasks.toast(this, "已复制 " + lines.size() + " 项差异");
                } catch (RuntimeException unavailable) { Tasks.showError(this, new IOException("差异清单超过系统剪贴板限制", unavailable)); }
            });
            report.show();
        }, error -> Tasks.showError(this, error));
    }
    private void invertVisibleSelection() {
        for (FileStore.Node node : active.entries) {
            if (!showHidden && node.name.startsWith(".")) continue;
            if (!active.selected.remove(node.key())) active.selected.add(node.key());
        }
        active.rangeSelecting = false;
        active.selectionChanged();
    }
    private void hiddenOptions() {
        MaterialDialogs.builder(this).setTitle("隐藏文件").setSingleChoiceItems(new String[]{"显示隐藏文件", "不显示隐藏文件"},
                showHidden ? 0 : 1, (d, n) -> {
                    showHidden = n == 0; getPreferences(0).edit().putBoolean("showHidden", showHidden).apply();
                    left.apply(); right.apply(); d.dismiss();
                }).show();
    }
    private void settings() {
        startActivityForResult(new Intent(this, SettingsActivity.class), SETTINGS);
    }
    private void densityOptions() {
        MaterialDialogs.builder(this).setTitle("列表密度")
                .setSingleChoiceItems(new String[]{"紧凑", "舒适（行高与图标更大）"}, comfortable ? 1 : 0, (d, n) -> {
                    d.dismiss();
                    if ((n == 1) == comfortable) return;
                    getPreferences(0).edit().putBoolean("comfortable", n == 1).apply();
                    recreate();
                }).setNegativeButton("取消", null).show();
    }
    @Override public void onDestroy() {
        if (inlineFilter != null) { inlineFilter.dispose(); inlineFilter = null; filteringPane = null; }
        Shizuku.removeRequestPermissionResultListener(shizukuPermission);
        Shizuku.removeBinderReceivedListener(shizukuReceived);
        if (closeMutation != null) { closeMutation.run(); closeMutation = null; }
        for (Pane pane : new Pane[]{left, right}) if (pane != null) {
            pane.generation++;
            pane.cancelFilter();
            pane.stopWatching();
            pane.cancelListing();
            pane.list.animate().cancel();
        }
        if (drawer != null) drawer.dismiss();
        if (sheet != null) sheet.dismiss();
        if (popupMenu != null) popupMenu.dismiss();
        if (frostMore != null) frostMore.dismiss();
        if (fileMenu != null) fileMenu.dismiss();
        super.onDestroy();
    }
    private void recoverMutation(Tasks.Mutation mutation) {
        if (Tasks.activeMutation() != mutation) return;
        if (closeMutation != null) closeMutation.run();
        Tasks.clearMutation(mutation); busy = false; directorySnapshots.clear();
        highlightMutationResult(mutation.result);
        if (mutation.error != null && mutation.transfer != null) {
            Tasks.Transfer transfer = mutation.transfer;
            left.reload(); right.reload(); actionBar();
            transferFailure(mutation.error, transfer.sources(), transfer.sourceLeft() ? left : right,
                    transfer.targetLeft() ? left : right, transfer.destination(), transfer.move());
            return;
        }
        left.resetSelection(); right.resetSelection(); left.reload(); right.reload(); actionBar();
        if (mutation.error != null) Tasks.showError(this, mutation.error);
        else if (mutation.result instanceof BatchFileAttributes.Result result) FileProperties.showResult(this, result);
        else Tasks.toast(this, "后台操作已完成，目录已刷新");
    }
    private void highlightMutationResult(Object result) {
        if (result instanceof FileStore.Node node) addResultHighlight(node);
        else if (result instanceof List<?> values) for (Object value : values)
            if (value instanceof FileStore.Node node) addResultHighlight(node);
    }
    private void addResultHighlight(FileStore.Node output) {
        for (Pane pane : new Pane[]{left, right}) {
            if (pane.location == null) continue;
            try {
                FileStore.Node parent = store.parent(output);
                boolean same = parent != null && parent.key().equals(pane.location.key());
                if (!same && output.file != null && pane.location.file != null) {
                    File outputParent = output.file.getAbsoluteFile().getParentFile();
                    same = outputParent != null && outputParent.getPath().equals(pane.location.file.getAbsoluteFile().getPath());
                }
                if (same) pane.resultHighlights.add(output.key());
            } catch (RuntimeException ignored) { }
        }
    }
    private void jump() {
        Pane pane = active;
        LinearLayout body = Ui.column(this); body.setPadding(dp(20), dp(4), dp(20), dp(4));
        EditText input = Ui.input(this, "/storage/emulated/0/Download");
        input.setText(pane.location.file != null ? pane.location.path() : "");
        input.setContentDescription("跳转路径");
        input.setTextColor(Ui.ACCENT);
        body.addView(input);
        AlertDialog dialog = MaterialDialogs.builder(this).setTitle("跳转").setView(body)
                .setNeutralButton("粘贴", null).setNegativeButton("取消", null).setPositiveButton("确定", null).create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                android.content.ClipboardManager clipboard =
                        (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                ClipData clip = clipboard.getPrimaryClip();
                CharSequence text = clip != null && clip.getItemCount() > 0 ? clip.getItemAt(0).coerceToText(this) : null;
                if (!TextUtils.isEmpty(text)) input.setText(text.toString().trim());
            });
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                File f = new File(input.getText().toString().trim());
                if (!f.isAbsolute()) { input.setError("请输入绝对路径"); return; }
                dialog.dismiss();
                int expectedGeneration = pane.historyGeneration;
                Tasks.run(this, () -> StorageNavigation.jump(f), node -> {
                    if (pane.historyGeneration != expectedGeneration) return;
                    pane.navigate(node);
                    if (!node.file.toPath().normalize().equals(f.toPath().normalize()))
                        Tasks.toast(this, "目录不存在，已进入上级目录");
                }, error -> { if (pane.historyGeneration == expectedGeneration) Tasks.showError(this, error); });
            });
        }); dialog.show();
    }
    private void filterDialog() {
        filterDialog(false);
    }
    private void createDialog() {
        Pane pane = active;
        LinearLayout body = Ui.column(this); EditText name = Ui.input(this, "名称");
        Spinner type = new Spinner(this);
        type.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{"文件", "目录"}));
        body.addView(name); body.addView(type);
        AlertDialog dialog = MaterialDialogs.builder(this).setTitle("新建").setView(body)
                .setNegativeButton("取消", null).setPositiveButton("创建", null).create();
        dialog.setOnShowListener(v -> dialog.getButton(-1).setOnClickListener(x -> {
            String value = name.getText().toString(); boolean folder = type.getSelectedItemPosition() == 1;
            mutate(() -> store.create(pane.location, value, folder), n -> { dialog.dismiss(); pane.reload(); });
        })); dialog.show();
    }
    private void selectionMenu() {
        Pane source = active;
        List<FileStore.Node> nodes = source.chosen();
        if (nodes.isEmpty()) return;
        FileStore.Node node = nodes.get(0);
        boolean single = nodes.size() == 1, files = nodes.stream().noneMatch(n -> n.directory);
        Pane target = source == left ? right : left;
        List<FileStore.Node> compareNodes = nodes.size() == 2 ? nodes : single && target.chosen().size() == 1
                ? List.of(node, target.chosen().get(0)) : List.of();
        Dialog dialog = GlassWindow.dialog(this); fileMenu = dialog;
        LinearLayout body = Ui.column(this); body.setPadding(0, dp(4), 0, dp(12));
        Runnable clear = () -> { source.resetSelection(); source.selectionChanged(); };
        body.addView(MenuSurfaces.heading(this, single ? node.name : "已选择 " + nodes.size() + " 项",
                "文件操作 · " + (source == left ? "左窗口" : "右窗口"), "关闭文件操作", () -> { clear.run(); dialog.dismiss(); }));
        String[] labels = {"复制", "移动", "删除", single ? "重命名" : "批量重命名", "工具", "压缩", "解压", "属性", "分享", "打开方式…", "添加书签", "文件对比"};
        int[] icons = {R.drawable.ic_copy, R.drawable.ic_cut, R.drawable.ic_trash, R.drawable.ic_edit,
                R.drawable.ic_tools, R.drawable.ic_download, R.drawable.ic_archive, R.drawable.ic_info,
                R.drawable.ic_share, R.drawable.ic_check, R.drawable.ic_bookmark, R.drawable.ic_swap};
        // Archives keep both choices: packing the archive itself is as valid as unpacking it.
        boolean extraction = nodes.stream().allMatch(n -> n.container != null)
                || single && !node.directory && (isArchive(node.name.toLowerCase(Locale.ROOT)) || ApkInstall.isApkName(node.name));
        boolean[] enabled = {!busy, !busy, !busy, !busy, single, !busy, extraction && !busy, true, files,
                single && !node.directory, single, compareNodes.size() == 2 && compareNodes.get(0).directory == compareNodes.get(1).directory};
        Runnable[] run = {() -> transfer(nodes, source, target, false), () -> transfer(nodes, source, target, true),
                this::deleteSelection, () -> { if (single) rename(node); else BatchRename.show(this, store, source.location, nodes,
                        plan -> mutate(() -> BatchRename.execute(store, plan), done -> {
                            source.resetSelection(); source.reload(); target.reload(); actionBar(); Tasks.toast(this, "已重命名 " + done.size() + " 项");
                        })); }, () -> fileTools(node), () -> compress(nodes),
                () -> extractDialog(nodes), () -> properties(List.copyOf(nodes)), () -> shareFiles(nodes), () -> openWith(node),
                () -> { directoryPlaces.bookmark(DirectoryPlaces.Entry.from(node, source.location)); Tasks.toast(this, "已添加书签"); },
                () -> FileCompareActivity.choose(this, compareNodes.get(0), compareNodes.get(1))};
        List<Integer> menuOrder = FileMenuOrder.load(this);
        for (int row = 0; row < (labels.length + 1) / 2; row++) {
            LinearLayout line = Ui.row(this);
            for (int column = 0; column < 2; column++) {
                int position = row * 2 + column;
                if (position >= labels.length) { line.addView(new View(this), new LinearLayout.LayoutParams(0, dp(58), 1)); continue; }
                int index = menuOrder.get(position);
                String detail = index < 2 ? (source == left ? "到右窗口" : "到左窗口") : "";
                LinearLayout cell = MenuSurfaces.action(this, icons[index], labels[index], detail, enabled[index], index == 2);
                cell.setContentDescription("文件操作 " + labels[index]); cell.setFocusable(enabled[index]);
                cell.setOnClickListener(v -> {
                    dialog.dismiss(); run[index].run();
                    if (index >= 4) clear.run();
                });
                if (index < 2) cell.setOnLongClickListener(v -> {
                    dialog.dismiss(); transferWithinPane(nodes, source, index == 1); return true;
                });
                LinearLayout.LayoutParams cellParams = new LinearLayout.LayoutParams(0, -2, 1);
                cellParams.setMargins(dp(column == 0 ? 12 : 4), dp(4), dp(column == 0 ? 4 : 12), dp(4));
                line.addView(cell, cellParams);
            }
            body.addView(line);
        }
        LinearLayout destination = Ui.row(this); destination.setPadding(dp(12), dp(6), dp(12), 0);
        for (int i = 0; i < 2; i++) {
            final boolean move = i == 1;
            TextView choose = Ui.text(this, move ? "移动到指定目录…" : "复制到指定目录…", 13, Ui.ACCENT);
            choose.setGravity(Gravity.CENTER); choose.setMinHeight(dp(48)); choose.setPadding(dp(8), dp(6), dp(8), dp(6));
            choose.setBackground(MenuSurfaces.surface(this, Ui.SURFACE, 12)); choose.setEnabled(!busy); choose.setFocusable(!busy);
            choose.setOnClickListener(v -> { dialog.dismiss(); transferWithinPane(nodes, source, move); });
            destination.addView(choose, new LinearLayout.LayoutParams(0, -2, 1));
        }
        body.addView(destination);
        ScrollView scroll = MenuSurfaces.scroll(this, body, "文件操作列表");
        dialog.setContentView(scroll); dialog.setOnCancelListener(d -> clear.run());
        dialog.setOnDismissListener(d -> { if (fileMenu == dialog) fileMenu = null; }); MenuSurfaces.show(this, dialog,scroll);
    }
    private void transferWithinPane(List<FileStore.Node> nodes, Pane source, boolean move) {
        EditText path = Ui.input(this, "目标目录完整路径"); path.setText(source.location.file != null ? source.location.path() : "");
        AlertDialog dialog = MaterialDialogs.builder(this).setTitle(move ? "单窗口移动" : "单窗口复制").setView(path)
                .setNegativeButton("取消", null).setPositiveButton(move ? "移动" : "复制", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(-1).setOnClickListener(v -> {
            File target = new File(path.getText().toString().trim());
            if (!target.isAbsolute()) { path.setError("请输入绝对路径"); return; }
            FileStore.Node destination = FileStore.directory(target, source.location.root);
            dialog.dismiss();
            transfer(nodes, source, source == left ? right : left, destination, move);
        })); dialog.show();
    }
    private void fileTools(FileStore.Node node) {
        String[] items = node.directory ? new String[]{"打开目录", "统计大小", "属性"} : new String[]{"打开", "执行 .sh / ELF", "SHA-256", "属性"};
        if (!node.directory && ArchiveToolsActivity.supports(node.name)) {
            items = Arrays.copyOf(items, items.length + 1); items[items.length - 1] = "ZIP 高级工具";
        }
        MaterialDialogs.builder(this).setTitle("工具").setItems(items, (d, index) -> {
            if (index == 4) ArchiveToolsActivity.open(this, node);
            else if (index == 0) { if (node.directory) active.navigate(node); else open(node); }
            else if (node.directory && index == 1) directoryStats(node);
            else if (node.directory) properties(node);
            else if (!node.directory && index == 1) executeFile(node);
            else if (!node.directory && index == 2) Tasks.run(this, () -> {
                java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
                try (InputStream input = store.read(node)) { byte[] buffer = new byte[65536]; int size;
                    while ((size = input.read(buffer)) != -1) digest.update(buffer, 0, size); }
                StringBuilder hex = new StringBuilder(); for (byte b : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
                return hex.toString();
            }, hash -> MaterialDialogs.builder(this).setTitle("SHA-256").setMessage(hash)
                    .setPositiveButton("复制", (x, w) -> ((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE))
                            .setPrimaryClip(ClipData.newPlainText(node.name, hash))).setNegativeButton("关闭", null).show());
            else properties(node);
        }).show();
    }
    private void directoryStats(FileStore.Node node) {
        properties(node);
    }
    private void shareFiles(List<FileStore.Node> nodes) {
        Tasks.run(this, () -> {
            ArrayList<Uri> streams = new ArrayList<>();
            for (FileStore.Node node : nodes) {
                File file = ApkInstall.stageShared(this, node, "-" + node.name);
                streams.add(androidx.core.content.FileProvider.getUriForFile(this, getPackageName() + ".files", file));
            }
            return streams;
        }, streams -> {
            Intent intent = new Intent(streams.size() == 1 ? Intent.ACTION_SEND : Intent.ACTION_SEND_MULTIPLE)
                    .setType("*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (streams.size() == 1) intent.putExtra(Intent.EXTRA_STREAM, streams.get(0));
            else intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, streams);
            ClipData clip = ClipData.newRawUri("files", streams.get(0));
            for (int i = 1; i < streams.size(); i++) clip.addItem(new ClipData.Item(streams.get(i)));
            intent.setClipData(clip);
            try { startActivity(Intent.createChooser(intent, "分享文件")); } catch (Exception e) { Tasks.showError(this, e); }
        });
    }
    private void rename(FileStore.Node node) {
        Pane pane = active; EditText name = Ui.input(this, "文件名"); name.setText(node.name);
        AlertDialog dialog = MaterialDialogs.builder(this).setTitle("重命名").setView(name)
                .setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        dialog.setOnShowListener(v -> dialog.getButton(-1).setOnClickListener(x ->
                mutate(() -> store.rename(node, name.getText().toString()), done -> {
                    dialog.dismiss(); pane.selected.clear(); pane.reload(); actionBar();
                }))); dialog.show();
    }
    private void properties(FileStore.Node node) {
        properties(List.of(node));
    }
    private void properties(List<FileStore.Node> nodes) {
        FileProperties.show(this, List.copyOf(nodes), () -> { });
    }
    void applyFileAttributes(List<FileStore.Node> nodes, BatchFileAttributes.Change change,
                             boolean childFiles, boolean childDirectories, Runnable changed) {
        List<FileStore.Node> selected = List.copyOf(nodes);
        mutate(() -> BatchFileAttributes.apply(this, selected, change, childFiles, childDirectories), result -> {
            left.reload(); right.reload(); actionBar();
            if (changed != null) changed.run();
            List<FileStore.Node> pending = new ArrayList<>(result.remaining());
            for (BatchFileAttributes.ItemResult item : result.items()) if (!item.success()) pending.add(item.node());
            FileProperties.showResult(this, result, pending.isEmpty() ? null : () ->
                    applyFileAttributes(pending, change, childFiles, childDirectories, changed));
        }, true);
    }
    private void deleteSelection() {
        Pane pane = active; List<FileStore.Node> nodes = pane.chosen();
        MaterialDialogs.builder(this).setTitle("删除 " + nodes.size() + " 项？")
                .setMessage("将永久删除所选文件及目录。").setNegativeButton("取消", null)
                .setPositiveButton("删除", (d, w) -> mutate(() -> {
                    try { store.deleteAll(nodes); return nodes.size(); }
                    finally { runOnUiThread(() -> {
                        if (isDestroyed() || isFinishing()) return;
                        pane.selected.clear(); directorySnapshots.clear(); left.reload(); right.reload(); actionBar();
                    }); }
                }, n -> Tasks.toast(this, "已删除 " + n + " 项"))).show();
    }
    private void transfer(Pane source, Pane target, boolean move) {
        transfer(source.chosen(), source, target, move);
    }
    private void transfer(List<FileStore.Node> nodes, Pane source, Pane target, boolean move) {
        transfer(nodes, source, target, target.location, move);
    }
    private void transfer(List<FileStore.Node> nodes, Pane source, Pane target, FileStore.Node destination, boolean move) {
        if (nodes.isEmpty()) { Tasks.toast(this, "先长按选择文件"); return; }
        if (move && source.location.key().equals(destination.key())) {
            Tasks.toast(this, "来源与目标目录相同"); return;
        }
        Tasks.run(this, Tasks.BROWSE, () -> {
            Set<String> existing = store.transferNames(destination, nodes);
            Set<String> incoming = new HashSet<>(); List<String> conflicts = new ArrayList<>();
            for (FileStore.Node node : nodes)
                if (existing.contains(node.name) || !incoming.add(node.name)) conflicts.add(node.name);
            return new TransferPreflight(existing, conflicts);
        }, preflight -> {
            if (preflight.conflicts().isEmpty())
                executeTransfer(nodes, source, target, destination, move, preflight.names(), FileStore.ConflictPolicy.KEEP_BOTH);
            else {
                TransferPlan.resolve(this, store, nodes, destination, plan -> executeTransferPlan(plan, nodes, source, target, destination, move));
            }
        }, error -> Tasks.showError(this, error));
    }
    private void executeTransferPlan(TransferPlan plan, List<FileStore.Node> nodes, Pane source, Pane target,
                                     FileStore.Node destination, boolean move) {
        mutate(() -> store.executePlan(plan, move), outputs -> {
            source.resetSelection();
            for (FileStore.Node output : outputs) addResultHighlight(output);
            source.reload(); target.reload(); actionBar();
            Tasks.toast(this, (move ? "移动" : "复制") + "完成 · " + outputs.size() + " 项" + (plan.skipped.isEmpty() ? "" : " · 跳过 " + plan.skipped.size() + " 项")
                    + retainedDirectoriesMessage(plan.retainedEmptyDirectories + plan.retainedSourceDirectories));
        }, true, error -> transferFailure(error, nodes, source, target, destination, move),
                new Tasks.Transfer(nodes, destination, source.isLeft, target.isLeft, move));
    }
    private void executeTransfer(List<FileStore.Node> nodes, Pane source, Pane target, FileStore.Node destination,
                                 boolean move, Set<String> existing, FileStore.ConflictPolicy conflicts) {
        executeTransfer(nodes, source, target, destination, move, existing, conflicts, null);
    }
    private void executeTransfer(List<FileStore.Node> nodes, Pane source, Pane target, FileStore.Node destination,
                                 boolean move, Set<String> existing, FileStore.ConflictPolicy conflicts,
                                 FileStore.ArchiveMoveRecovery recovery) {
        int[] retained = {0};
        mutate(() -> recovery == null ? store.copyAll(nodes, destination, move, conflicts, new HashSet<>(existing), node -> retained[0]++)
                        : store.resumeArchiveMove(recovery),
                outputs -> {
                    source.selected.clear();
                    for (FileStore.Node output : outputs) addResultHighlight(output);
                    if (move || source.location.container == null || destination.container != null) source.reload();
                    else { source.resetSelection(); source.selectionChanged(); }
                    target.reload(); actionBar();
                    int skipped = nodes.size() - outputs.size();
                    Tasks.toast(this, (move ? "移动" : "复制") + "完成 · " + outputs.size() + " 项"
                            + (skipped == 0 ? "" : " · 跳过 " + skipped + " 项") + retainedDirectoriesMessage(retained[0]));
                }, true, error -> transferFailure(error, nodes, source, target, destination, move),
                new Tasks.Transfer(nodes, destination, source.isLeft, target.isLeft, move));
    }
    private static String retainedDirectoriesMessage(int count) {
        return count == 0 ? "" : " · 已保留 " + count + " 个来源目录";
    }
    private void transferFailure(Exception error, List<FileStore.Node> nodes, Pane source, Pane target,
                                 FileStore.Node destination, boolean move) {
        if (error instanceof TransferPlan.Incomplete incomplete) {
            source.reload(); target.reload();
            MaterialDialogs.builder(this).setTitle("传输未完成").setMessage(incomplete.getMessage())
                    .setNegativeButton("关闭", null).setPositiveButton("重试未完成项", (d, w) ->
                            executeTransferPlan(incomplete.plan, nodes, source, target, destination, move)).show();
        } else if (error instanceof Tasks.CancelledException) {
            Tasks.toast(this, "已取消，已完成的项目保留");
        } else if (error instanceof Tasks.PartialTransferException partial && !partial.remaining.isEmpty()) {
            Set<String> remainingKeys = new HashSet<>();
            for (FileStore.Node node : partial.remaining) remainingKeys.add(node.key());
            for (FileStore.Node node : nodes) if (!remainingKeys.contains(node.key())) source.selected.remove(node.key());
            if (target.location.key().equals(destination.key())) {
                for (FileStore.Node output : partial.published) target.resultHighlights.add(output.key());
                target.reload();
            }
            if (source != target) {
                if (move) source.reload(); else source.selectionChanged();
            } else if (!move) source.selectionChanged();
            MaterialDialogs.builder(this).setTitle("传输未完成")
                    .setMessage(partial.getMessage())
                    .setNegativeButton("关闭", null)
                    .setPositiveButton("重试未完成项", (d, w) -> {
                        if (partial.archiveMove != null)
                            executeTransfer(partial.remaining, source, target, destination, true, Set.of(),
                                    FileStore.ConflictPolicy.KEEP_BOTH, partial.archiveMove);
                        else transfer(partial.remaining, source, target, destination, move);
                    }).show();
        } else Tasks.showError(this, error);
    }
    private <T> void mutate(Tasks.Job<T> job, Tasks.Done<T> done) {
        mutate(job, done, true);
    }
    private <T> void mutate(Tasks.Job<T> job, Tasks.Done<T> done, boolean showProgress) {
        mutate(job, done, showProgress, null);
    }
    private <T> void mutate(Tasks.Job<T> job, Tasks.Done<T> done, boolean showProgress, Tasks.Done<Exception> customFailure) {
        mutate(job, done, showProgress, customFailure, null);
    }
    private <T> void mutate(Tasks.Job<T> job, Tasks.Done<T> done, boolean showProgress,
                            Tasks.Done<Exception> customFailure, Tasks.Transfer transfer) {
        if (!MUTATING.compareAndSet(false, true)) { Tasks.toast(this, "请等待当前操作完成"); return; }
        busy = true; header();
        Tasks.Mutation mutation = Tasks.beginMutation(this);
        mutation.transfer = transfer;
        mutation.showProgress = showProgress;
        Tasks.Progress progress = mutation.progress;
        Runnable close = showProgress ? showMutationProgress(progress) : () -> closeMutation = null;
        closeMutation = close;
        Tasks.run(this, Tasks.FILES, () -> {
            try { T result = progress.run(() -> {
                if (transfer != null) Tasks.Progress.plan(transfer.sources(), transfer.move() ? "移动文件" : "复制文件");
                return job.run();
            }); Tasks.finishMutation(mutation, result, null); return result; }
            catch (Exception error) { Tasks.finishMutation(mutation, null, error); throw error; }
            finally {
                MUTATING.set(false);
                runOnUiThread(() -> {
                    MainActivity resumedActivity = foreground.get();
                    if (resumedActivity != null && resumedActivity != MainActivity.this)
                        resumedActivity.recoverMutation(mutation);
                    busy = false;
                    if (isDestroyed() || isFinishing()) return;
                    directorySnapshots.clear(); header();
                });
            }
        }, value -> { Tasks.clearMutation(mutation); close.run(); done.accept(value); }, error -> {
            Tasks.clearMutation(mutation);
            close.run();
            directorySnapshots.clear(); left.reload(); right.reload(); actionBar();
            if (error instanceof Tasks.CancelledException) {
                Tasks.toast(this, "已取消，已完成的项目保留");
            } else if (customFailure != null) customFailure.accept(error);
            else Tasks.showError(this, error);
        });
    }
    private Runnable showMutationProgress(Tasks.Progress progress) {
        operationProgress.bind(progress);
        return () -> {
            operationProgress.clear();
            closeMutation = null;
        };
    }
    private void compress(List<FileStore.Node> nodes) {
        Pane pane = active, opposite = other();
        LinearLayout body = Ui.column(this); body.setPadding(dp(16), dp(4), dp(16), dp(8));
        EditText name = Ui.input(this, "归档名称"); name.setText("archive.zip"); name.setContentDescription("压缩文件名称");
        Spinner formats = new Spinner(this); formats.setContentDescription("压缩格式");
        formats.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, Arrays.stream(ArchiveFormats.PACK_FORMATS).map(s->s.toUpperCase(Locale.ROOT)).toArray(String[]::new)));
        Spinner levels = new Spinner(this); levels.setContentDescription("压缩级别");
        levels.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{"仅打包", "快速压缩", "标准压缩", "最高压缩"})); levels.setSelection(2);
        CheckBox otherPane = new CheckBox(this); otherPane.setText("压缩到另一窗口路径"); otherPane.setMinHeight(dp(48));
        CheckBox removeSources=new CheckBox(this);removeSources.setText("校验压缩包成功后删除源文件");removeSources.setMinHeight(dp(48));
        EditText split=Ui.input(this,"ZIP 分卷大小（MiB，留空不分卷）");split.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);split.setContentDescription("ZIP 分卷大小");
        EditText password = Ui.input(this, "压缩密码（留空不加密）"); password.setContentDescription("压缩密码");
        password.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        TextView note = Ui.text(this, "ZIP 密码使用 AES-256 加密", 12, Ui.MUTED);
        body.addView(name);
        LinearLayout options = Ui.row(this); options.addView(formats,new LinearLayout.LayoutParams(0,dp(48),1)); options.addView(levels,new LinearLayout.LayoutParams(0,dp(48),1));
        body.addView(options); body.addView(password); body.addView(note); body.addView(split);body.addView(otherPane);body.addView(removeSources);
        String[] extensions = Arrays.stream(ArchiveFormats.PACK_FORMATS).map(s->"."+s).toArray(String[]::new);
        formats.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String value = name.getText().toString().replaceFirst("(?i)\\.(?:tar\\.(?:gz|bz2|xz|lz4|zst)|zip|tar|tgz|tbz2?|txz|7z|gz|bz2|xz|lz4|zst)$", "");
                name.setText(value + extensions[position]);
                int selected=levels.getSelectedItemPosition();String format=ArchiveFormats.PACK_FORMATS[position];
                levels.setAdapter(new ArrayAdapter<>(MainActivity.this,android.R.layout.simple_spinner_dropdown_item,new String[]{position==0||format.equals("7z")?"仅打包":"最低压缩","快速压缩","标准压缩","最高压缩"}));
                levels.setSelection(Math.max(0,selected));levels.setEnabled(!format.equals("tar")&&!format.endsWith("lz4"));
                boolean splitSupported = position == 0 || format.equals("7z");
                split.setEnabled(splitSupported);if(!splitSupported)split.setText("");
                password.setEnabled(splitSupported);
                split.setHint(format.equals("7z") ? "7Z 分卷大小（MiB，留空不分卷）" : "ZIP 分卷大小（MiB，留空不分卷）");
                if (!splitSupported) password.setText("");
                note.setText(position == 0 ? "ZIP 密码使用 AES-256；所有分卷需放在同一目录"
                        : format.equals("7z") ? "7Z 密码使用 AES-256 加密文件内容，文件名可见"
                        : position>=8?"独立压缩流只支持一个文件":"文件夹与多个文件可一起压缩");
            }
            public void onNothingSelected(AdapterView<?> parent) { }
        });
        ScrollView form=new ScrollView(this);form.addView(body);
        AlertDialog dialog = MaterialDialogs.builder(this).setTitle("压缩文件").setView(form).setNegativeButton("取消", null)
                .setPositiveButton("压缩", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(-1).setOnClickListener(view -> {
            String fileName = name.getText().toString();
            try { FileStore.checkName(fileName); } catch (IOException error) { name.setError(error.getMessage()); return; }
            String format = ArchiveFormats.PACK_FORMATS[formats.getSelectedItemPosition()];
            long splitBytes;
            try{String value=split.getText().toString().trim();splitBytes=value.isEmpty()?0:Math.multiplyExact(Long.parseLong(value),1048576L);if(!value.isEmpty()&&splitBytes<1048576)throw new IllegalArgumentException();}
            catch(IllegalArgumentException|ArithmeticException error){split.setError("请输入至少 1 MiB 的整数，留空不分卷");return;}
            if(formats.getSelectedItemPosition()>=8&&(nodes.size()!=1||nodes.get(0).directory)){name.setError("此格式仅支持单个文件，请选择 TAR 系列或 ZIP");return;}
            int level = new int[]{0,1,6,9}[levels.getSelectedItemPosition()];
            Pane outputPane = otherPane.isChecked() ? opposite : pane;
            FileStore.Node destination = outputPane.location;
            char[] secret = password.getText().toString().toCharArray();
            boolean deleteOriginals=removeSources.isChecked();
            dialog.dismiss();
            mutate(() -> {
                try { return ArchiveCreation.create(this,store,nodes,destination,fileName,format,secret,level,splitBytes,deleteOriginals); }
                finally { Arrays.fill(secret, '\0'); }
            }, out -> { outputPane.reveal = out.name; outputPane.reload(); if (pane != outputPane) pane.reload(); Tasks.toast(this, "压缩完成"); });
        })); dialog.show();
    }
    private void extractDialog(List<FileStore.Node> nodes) {
        Pane source = active, target = other();
        FileStore.Node defaultDestination = target.location;
        if (defaultDestination.container != null) defaultDestination = FileStore.directory(Environment.getExternalStorageDirectory(), false);
        FileStore.Node initial = defaultDestination;
        LinearLayout body = Ui.column(this); body.setPadding(dp(16), dp(4), dp(16), 0);
        EditText path = Ui.input(this, "解压到目录"); path.setContentDescription("解压目标目录");
        path.setText(initial.path()); path.setEnabled(initial.file != null); body.addView(path);
        String base = nodes.size() == 1 ? nodes.get(0).name.replaceFirst("(?i)\\.(zip|rar|jar|apk(?:\\.1)?|apks|xapk|tar\\.(?:gz|bz2|xz|lz4|zst)|tar|tgz|tbz2?|txz|7z|gz|bz2|xz|lz4|zst)$", "") : "extracted";
        if (base.isBlank()) base = "extracted";
        EditText name = Ui.input(this, "新建文件夹"); name.setContentDescription("解压文件夹名称"); name.setText(base); body.addView(name);
        AlertDialog dialog = MaterialDialogs.builder(this).setTitle("解压").setView(body)
                .setNegativeButton("取消", null).setPositiveButton("解压", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(-1).setOnClickListener(v -> {
            String folder = name.getText().toString().trim();
            try { FileStore.checkName(folder); } catch (IOException error) { name.setError(error.getMessage()); return; }
            FileStore.Node destination = initial;
            if (initial.file != null) {
                File value = new File(path.getText().toString().trim());
                if (!value.isAbsolute()) { path.setError("请输入绝对路径"); return; }
                destination = FileStore.directory(value, initial.root && initial.file.equals(value));
            }
            FileStore.Node outputParent = destination;
            dialog.dismiss();
            mutate(() -> store.extract(nodes, outputParent, folder), output -> {
                source.selected.clear(); source.reload();
                target.reveal = output.name; target.navigate(outputParent);
                Tasks.toast(this, "解压完成 · " + output.path()); actionBar();
            });
        })); dialog.show();
    }
    private void open(FileStore.Node node) {
        open(node, false);
    }
    private void open(FileStore.Node node, boolean external) {
        OpenMethods.Choice preferred = OpenMethods.find(this, node.name);
        if (preferred != null) {
            if (preferred.external()) OpenMethods.launch(this, node, preferred, () -> openWith(node));
            else openMethod(node, preferred.action());
            return;
        }
        String name = node.name.toLowerCase(Locale.ROOT);
        if (name.endsWith(".mtsx")) {
            MaterialDialogs.builder(this).setTitle("语法高亮文件").setMessage(node.name).setPositiveButton("安装语法",(d,w)->SyntaxFiles.install(this,node))
                    .setNegativeButton("编辑文本",(d,w)->editText(node)).setNeutralButton("取消",null).show();return;
        }
        if (SplitApks.matches(name)) {
            MaterialDialogs.builder(this).setTitle("APKS 安装包").setMessage(node.name)
                    .setPositiveButton("安装", (d, w) -> {
                        Intent install = new Intent(this, SplitInstallActivity.class); node.put(install); startActivity(install);
                    }).setNegativeButton("查看", (d, w) -> browseArchive(node)).setNeutralButton("取消", null).show();
            return;
        }
        if (ApkInstall.isApkName(name)) { apkDialog(node); return; }
        if (isArchive(name)) {
            Pane target = active;
            browseArchive(node);
            int generation = target.generation;
            if (node.file != null) Tasks.run(this, Tasks.BROWSE, () -> ArchiveOps.isEncrypted(node.file)
                    && !ArchiveOps.hasPassword(node.file), required -> {
                if (!required || active != target || target.generation != generation) return;
                promptPassword(node.file, () -> { if (target.generation == generation) target.reload(true); });
            }, error -> { });
            return;
        }
        if (node.container != null && name.endsWith(".xml")) {
            if (node.container.name.toLowerCase(Locale.ROOT).endsWith(".rar")) { readOnlyText(node); return; }
            Tasks.run(this, () -> {
                try (InputStream input = store.read(node)) {
                    return com.reandroid.arsc.chunk.xml.ResXmlDocument.isResXmlBlock(FileStore.readBytes(input, 8));
                }
            }, binary -> { if (binary) structuredArchive(node, "xml"); else editText(node); }); return;
        }
        if (node.container != null && name.equals("resources.arsc")) {
            if (node.container.name.toLowerCase(Locale.ROOT).endsWith(".rar")) readOnlyText(node);
            else structuredArchive(node, "arsc");
            return;
        }
        if (name.endsWith(".elf")) {
            if (external) {
                MaterialDialogs.builder(this).setTitle("运行外部文件？")
                        .setMessage(node.name + "\n\n此文件由其他应用提供，将使用本应用的普通权限运行。")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("运行", (dialog, which) -> launchExecutable(node, false, false, true)).show();
            } else executeFile(node);
            return;
        }
        if (name.endsWith(".sh")) {
            scriptDialog(node);
            return;
        }
        MediaKind media = MediaKind.of(name);
        if (media != null) { preview(node, media.preview); return; }
        if (name.endsWith(".html") || name.endsWith(".htm")) { preview(node, "html"); return; }
        if (name.endsWith(".svg")) { preview(node, "svg"); return; }
        if (!isText(name) && !name.endsWith(".dex") && !name.endsWith(".arsc")) { openWith(node); return; }
        Class<?> type = name.endsWith(".dex") || name.endsWith(".arsc") ? ArchiveActivity.class : EditorActivity.class;
        Intent i = new Intent(this, type); node.put(i); startActivity(i);
    }
    private static boolean isArchive(String name) {
        return name.endsWith(".zip") || name.endsWith(".rar") || name.endsWith(".jar") || name.endsWith(".apks") || name.endsWith(".xapk")
                || ArchiveFormats.supported(new File(name));
    }
    private static boolean knownType(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return isText(lower) || isArchive(lower) || ApkInstall.isApkName(lower)
                || MediaKind.of(lower) != null || lower.matches(".*\\.(elf|dex|arsc|htm|svg)$");
    }
    private void editText(FileStore.Node node) {
        Intent intent = new Intent(this, EditorActivity.class); node.put(intent); startActivity(intent);
    }
    private void readOnlyText(FileStore.Node node) {
        Intent intent = new Intent(this, EditorActivity.class); node.put(intent);
        intent.putExtra("readOnly", true).putExtra("displayName", node.name); startActivity(intent);
    }
    private void archiveTools(FileStore.Node node) {
        Intent intent = new Intent(this, ArchiveActivity.class); node.put(intent); startActivity(intent);
    }
    private void structuredArchive(FileStore.Node node, String mode) {
        Intent intent = new Intent(this, EditorActivity.class);
        node.container.put(intent);
        intent.putExtra("archiveEntry", node.entry).putExtra("displayName", node.name).putExtra("mode", mode);
        startActivity(intent);
    }
    private void promptPassword(File file, Runnable after) {
        EditText input = Ui.input(this, "归档密码");
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        MaterialDialogs.builder(this).setTitle("加密归档")
                .setMessage("输入密码后浏览、查看和解压都会使用本次密码")
                .setView(input).setNegativeButton("取消", null)
                .setPositiveButton("打开", (d, w) -> {
                    String value = input.getText().toString();
                    if (value.isEmpty()) { Tasks.toast(this, "密码不能为空"); return; }
                    ArchiveOps.setPassword(file, value.toCharArray()); after.run();
                }).show();
    }
    private void browseArchive(FileStore.Node node) {
        Pane pane = active;
        if (pane.location != null && pane.all.stream().anyMatch(n -> n.key().equals(node.key()))) {
            archiveParents.put(node.key(), pane.location.key());
            if (archiveParents.size() > 64) archiveParents.remove(archiveParents.keySet().iterator().next());
        }
        FileStore.Node directory = FileStore.archiveNode(node, "", true, 0, 0);
        pane.navigate(directory);
    }
    private void preview(FileStore.Node node, String kind) {
        Intent intent = new Intent(this, PreviewActivity.class); node.put(intent); intent.putExtra("kind", kind);
        if ("image".equals(kind)) {
            intent.putExtra("imageSort", active.sort).putExtra("imageShowHidden", showHidden);
            if (active.entries.stream().anyMatch(entry -> entry.key().equals(node.key()))) {
                intent.putExtra("imageParentKey", active.location.key());
                intent.putExtra("imageFilter", active.filter).putExtra("imageRegex", active.regex);
            }
        }
        startActivity(intent);
    }
    private void apkDialog(FileStore.Node node) {
        ApkDialog.show(this, node, () -> browseArchive(node), () -> archiveTools(node),
                this::startNormalInstall, this::installPrivileged, path -> navigateDirectory(path, false));
    }
    private void installPrivileged(File file) {
        AlertDialog progress = MaterialDialogs.builder(this).setTitle("准备安装")
                .setMessage("正在处理 " + file.getName()).setCancelable(false).create(); progress.show();
        Tasks.run(this, Tasks.SHELL, () -> {
            if(!RootAccess.available())return false;
            try{ApkInstall.silent(file);ApkInstall.discardStage(this,file);return true;}
            catch(IOException error){RootAccess.reset();if(!RootAccess.available())return false;ApkInstall.discardStage(this,file);throw error;}
        }, installed -> {
            progress.dismiss();if(installed)Tasks.toast(this,"安装成功");else startNormalInstall(file);
        }, error -> { progress.dismiss(); Tasks.showError(this, error); });
    }
    private void startNormalInstall(File file) {
        try { SplitInstallActivity.startSingle(this, file); }
        catch (Exception e) { ApkInstall.discardStage(this,file);Tasks.showError(this, e); }
    }
    private void openWith(FileStore.Node node) {
        OpenMethods.show(this, node, action -> openMethod(node, action));
    }
    private void openMethod(FileStore.Node node, int action) {
        switch (action) {
            case 0 -> editText(node);
            case 1, 6 -> browseArchive(node);
            case 2 -> preview(node, "image");
            case 3 -> preview(node, "audio");
            case 4 -> preview(node, "video");
            case 5 -> apkDialog(node);
            case 7 -> scriptDialog(node);
            case 8 -> preview(node, "html");
            case 9 -> preview(node, "svg");
            case 10 -> startActivity(new Intent(this, HexActivity.class).putExtra("node", node.key()).putExtra("name", node.name));
            case 11 -> active.toggle(node);
        }
    }
    private void executeFile(FileStore.Node node) {
        if (node.directory) { Tasks.toast(this, "请选择 .sh 或 ELF 文件"); return; }
        if (node.name.toLowerCase(Locale.ROOT).endsWith(".sh")) { scriptDialog(node); return; }
        launchExecutable(node, node.root, false, true);
    }
    private void launchExecutable(FileStore.Node node, boolean root, boolean pipe, boolean autoRun) {
        Intent script = new Intent(this, TerminalActivity.class);
        node.put(script); script.putExtra("script", true);
        script.putExtra("executionRoot", root).putExtra("pipe", pipe).putExtra("autorun", autoRun);
        script.putExtra("forceScript", !node.name.toLowerCase(Locale.ROOT).endsWith(".elf"));
        if (node.file != null) script.putExtra("cwd", node.file.getParent());
        startActivity(script);
    }
    private void scriptDialog(FileStore.Node node) {
        android.content.SharedPreferences prefs = getSharedPreferences("script-execution", 0);
        boolean root = node.root || prefs.getBoolean("root", false), pipe = prefs.getBoolean("pipe", false);
        LinearLayout body = Ui.column(this); body.setPadding(dp(24), dp(8), dp(24), dp(12));
        TextView prompt = Ui.text(this, "请选择要执行的操作", 17, Ui.TEXT); body.addView(prompt);
        TextView mode = Ui.text(this, pipe ? "管道执行" : "普通执行", 13, Ui.MUTED);
        mode.setPadding(0, dp(10), 0, dp(4)); body.addView(mode);
        if (root) {
            TextView warning = Ui.text(this, "已选择使用 ROOT 权限执行，请确认脚本内容。", 13, 0xffd04b53);
            body.addView(warning);
        }
        AlertDialog dialog = MaterialDialogs.builder(this).setTitle(node.name).setView(body)
                .setNeutralButton("设置", (d, w) -> scriptSettings(node))
                .setNegativeButton("编辑", (d, w) -> {
                    Intent editor = new Intent(this, EditorActivity.class); node.put(editor); startActivity(editor);
                }).setPositiveButton("执行", (d, w) -> launchExecutable(node, root, pipe, true)).create();
        dialog.show();
    }
    private void scriptSettings(FileStore.Node node) {
        android.content.SharedPreferences prefs = getSharedPreferences("script-execution", 0);
        LinearLayout body = Ui.column(this); body.setPadding(dp(18), 0, dp(18), dp(8));
        CheckBox root = new CheckBox(this); root.setText("使用 ROOT 执行"); root.setChecked(node.root || prefs.getBoolean("root", false));
        RadioGroup methods = new RadioGroup(this);
        RadioButton normal = new RadioButton(this); normal.setId(View.generateViewId()); normal.setText("普通执行");
        RadioButton pipe = new RadioButton(this); pipe.setId(View.generateViewId()); pipe.setText("管道执行");
        methods.addView(normal); methods.addView(pipe); methods.check(prefs.getBoolean("pipe", false) ? pipe.getId() : normal.getId());
        body.addView(root); body.addView(methods);
        TextView note = Ui.text(this, "管道方式将脚本送入 Shell，运行时不能追加标准输入。", 12, Ui.MUTED);
        note.setPadding(dp(6), dp(8), dp(6), dp(4)); body.addView(note);
        MaterialDialogs.builder(this).setTitle("脚本执行设置").setView(body)
                .setNegativeButton("取消", (d, w) -> scriptDialog(node))
                .setPositiveButton("保存", (d, w) -> {
                    prefs.edit().putBoolean("root", root.isChecked()).putBoolean("pipe", methods.getCheckedRadioButtonId() == pipe.getId())
                            .remove("elfLaunchMode").apply();
                    scriptDialog(node);
                }).show();
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == SETTINGS) { recreate(); return; }
        if (request == BACKUP && result == RESULT_OK) {
            restoreFilter(left); restoreFilter(right); recreate(); return;
        }
        if (request == INSTALLED && result == RESULT_OK && data != null) {
            String path = data.getStringExtra("navigatePath");
            if (path != null) navigateDirectory(new File(path), data.getBooleanExtra("preferRoot", false));
            if (data.getBooleanExtra("extracted", false)) active.reload(true);
            return;
        }
        if (result != RESULT_OK || data == null || data.getData() == null) {
            if (request == TREE || request == TREE + 1) pendingBookmarkGrant = null;
            return;
        }
        Uri uri = data.getData();
        if (request == TREE || request == TREE + 1) {
            try {
                int grants = Intent.FLAG_GRANT_READ_URI_PERMISSION;
                if ((data.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0)
                    grants |= Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                try { getContentResolver().takePersistableUriPermission(uri, grants); }
                catch (SecurityException temporary) { Tasks.toast(this, "此目录仅本次授权有效，重新打开应用后可能需要再次授权"); }
                Uri document = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri));
                Pane pane = request == TREE ? left : right;
                DirectoryPlaces.Entry reauthorize = pendingBookmarkGrant; pendingBookmarkGrant = null;
                Tasks.run(this, () -> {
                    FileStore.Node node = store.document(document);
                    if (!node.directory) throw new IOException("授权位置不是目录");
                    return node;
                }, node -> {
                    if (reauthorize != null && reauthorize.directory()) {
                        directoryPlaces.remove(true, reauthorize.key());
                        DirectoryPlaces.Entry fresh = DirectoryPlaces.Entry.from(node);
                        directoryPlaces.bookmark(new DirectoryPlaces.Entry(fresh.key(), reauthorize.label(), fresh.path(), fresh.uri(), fresh.root()));
                    }
                    if (reauthorize != null && !reauthorize.directory()) Tasks.toast(this, "已打开授权目录，文件书签已保留");
                    pane.navigate(node);
                });
            } catch (Exception e) { Tasks.toast(this, "无法打开授权目录：" + e.getMessage()); }
        } else if (request == ARTIFACT) Tasks.run(this, () -> store.document(uri), this::open);
    }
    private void installed() {
        Intent intent = new Intent(this, InstalledAppsActivity.class); active.location.put(intent);
        startActivityForResult(intent, INSTALLED);
    }
    private void shellDialog() {
        Intent intent = new Intent(this, ShellActivity.class);
        intent.putExtra("cwd", active.location.file == null ? getFilesDir().getAbsolutePath() : active.location.path());
        intent.putExtra("root", active.location.root); startActivity(intent);
    }
    private void searchDialog() {
        startActivity(new Intent(this, SearchActivity.class)
                .putExtra("rootKey", active.location.key()).putExtra("rootPath", active.location.path()));
    }
    static boolean isText(String name) {
        return name.toLowerCase(Locale.ROOT).matches(".*\\.(txt|xml|json|smali|java|kt|gradle|properties|md|js|html|css|yml|yaml|sh|ini|cfg|log|csv)$");
    }
    static void watch(EditText v, Runnable action) {
        v.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            public void onTextChanged(CharSequence s, int st, int b, int c) { action.run(); }
            public void afterTextChanged(Editable e) {}
        });
    }
    private void clearSearchMarks() {
        if (left != null) left.clearMark();
        if (right != null) right.clearMark();
    }
    private static String rowIdentity(FileStore.Node node) {
        return node.file != null ? node.path() : node.key();
    }
    @Override protected void onPaletteChanged() {
        if (subtitle != null) subtitle.setTextColor(Ui.material == ThemeMaterial.CLASSIC ? 0xffc0d0cd : 0xffe3ecef);
        if(chrome!=null)chrome.apply(null);
        for (Pane pane : new Pane[]{left, right}) if (pane != null) {
            pane.indicator.setVisibility(Ui.material==ThemeMaterial.FROST?View.INVISIBLE:View.VISIBLE);
            pane.indicator.setBackgroundColor(pane == active ? Ui.ACCENT : Ui.LINE);
            pane.adapter.notifyDataSetChanged(); pane.pullEdge.invalidate();
            pane.spinnerRing.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        }
        // The alternate toolbar is detached, so the global view-tree repaint cannot update it.
        Arrays.fill(actionButtons, null);
        selectionActions = null;
        if (active != null) actionBar();
    }
    @Override protected void handleBack() {
        if (inlineFilter != null) { inlineFilter.cancel(); return; }
        if (active != null) active.clearMark();
        if (sheet != null && sheet.isShowing()) { sheet.close(); return; }
        if (fileMenu != null && fileMenu.isShowing()) { fileMenu.cancel(); return; }
        if (popupMenu != null && popupMenu.isShowing()) { popupMenu.dismiss(); return; }
        if (frostMore != null && frostMore.isShowing()) { frostMore.dismiss(); return; }
        if (drawer != null && drawer.isShowing()) { drawer.dismiss(); return; }
        if (active.rangeSelecting) { active.rangeSelecting = false; actionBar(); return; }
        if (!active.selected.isEmpty()) { active.resetSelection(); active.selectionChanged(); exitArmedAt = 0; return; }
        FileStore.Node parent = StorageNavigation.parent(store, active.location);
        if (parent != null) { exitArmedAt = 0; active.up(); return; }
        if ((active.location.uri != null || active.location.container != null) && !active.backHistory.isEmpty()) {
            exitArmedAt = 0; active.back(); return;
        }
        long now = SystemClock.uptimeMillis();
        if (exitArmedAt != 0 && now - exitArmedAt < 2000) finish();
        else { exitArmedAt = now; Tasks.toast(this, "再按一次返回退出"); }
    }
    private final class Pane extends LinearLayout {
        final boolean isLeft;
        final View indicator;
        final TextView empty, summary, hint;
        final SwipeSelectListView list;
        final FrameLayout refresh;
        final PullEdgeView pullEdge;
        final ProgressBar spinnerRing;
        final Adapter adapter;
        android.os.FileObserver observer;
        ContentObserver documentObserver;
        String observedPath;
        FileStore.Node probeNode;
        String probeRevision;
        boolean probing;
        int probeGeneration;
        final Runnable externalProbe = this::probeExternal;
        final Runnable externalRefresh = this::refreshExternal;
        void probeExternal() {
            if (foreground.get() != MainActivity.this || probeNode == null || isDestroyed()) return;
            Tasks.Mutation mutation = Tasks.activeMutation();
            if (probing || mutation != null && !mutation.finished) { list.postDelayed(externalProbe, 5000); return; }
            int token = probeGeneration; FileStore.Node watched = probeNode; boolean inspectChildren = all.size() <= 1200; probing = true;
            Tasks.BROWSE.execute(() -> {
                String revision;
                try {
                    if (watched.directory && inspectChildren) {
                        List<FileStore.Node> children = store.list(watched);
                        children.sort(Comparator.comparing(FileStore.Node::key));
                        StringBuilder value = new StringBuilder();
                        for (FileStore.Node child : children) value.append(child.key()).append(':').append(child.size)
                                .append(':').append(child.modified).append(':').append(child.directory).append('\n');
                        revision = value.toString();
                    } else revision = store.identity(watched);
                } catch (IOException | RuntimeException error) { revision = "unavailable:" + error.getClass().getName(); }
                String result = revision;
                runOnUiThread(() -> {
                    if (token != probeGeneration || isDestroyed()) return;
                    probing = false;
                    boolean changed = probeRevision != null && !probeRevision.equals(result);
                    probeRevision = result;
                    if (changed) reload(false);
                    list.removeCallbacks(externalProbe); list.postDelayed(externalProbe, 5000);
                });
            });
        }
        void refreshExternal() {
            Tasks.Mutation mutation = Tasks.activeMutation();
            if (mutation != null && !mutation.finished) { list.postDelayed(externalRefresh, 900); return; }
            if (location != null && (location.file != null && location.file.getAbsolutePath().equals(observedPath)
                    || location.uri != null && location.uri.toString().equals(observedPath))) reload(true);
        }
        final List<FileStore.Node> all = new ArrayList<>(), entries = new ArrayList<>();
        final Set<String> selected = new HashSet<>();
        final Set<String> resultHighlights = new HashSet<>();
        long highlightUntil;
        final ArrayDeque<FileStore.Node> backHistory = new ArrayDeque<>(), forwardHistory = new ArrayDeque<>();
        FileStore.Node location;
        String filter = "";
        String reveal, marked, selectionAnchor;
        String pendingRestoreKey;
        boolean regex, rangeSelecting;
        int filterGeneration, navigationMotion;
        java.util.concurrent.Future<?> filterJob;
        int historyGeneration;
        volatile int generation;
        int sort, folderCount;
        java.util.concurrent.Future<?> loading;
        android.os.CancellationSignal listingSignal;
        long usableSpace, totalSpace;
        String locatedKey, locatedName;
        String recordedDirectory;
        Map<String, String> metadata = Collections.emptyMap();
        NavigationState navigation;
        DirectorySnapshot displayed;
        private record NavigationState(FileStore.Node location, List<FileStore.Node> back, List<FileStore.Node> forward,
                                       String filter, Set<String> selected) { }
        void restoreNavigation() {
            if (navigation == null) return;
            generation++;
            locatedKey = null; locatedName = null;
            cancelListing();
            location = navigation.location(); filter = navigation.filter(); persistFilter(this);
            backHistory.clear(); backHistory.addAll(navigation.back());
            forwardHistory.clear(); forwardHistory.addAll(navigation.forward());
            selected.clear(); selected.addAll(navigation.selected()); navigation = null;
            spinnerRing.setVisibility(GONE);
            if (displayed != null) show(location, displayed, null, 0);
        }
        void restoreHistory(ArrayList<String> backKeys, ArrayList<String> forwardKeys) {
            if (backKeys == null && forwardKeys == null) return;
            int token = ++historyGeneration;
            Tasks.BROWSE.execute(() -> {
                List<FileStore.Node> back = resolveHistory(backKeys), forward = resolveHistory(forwardKeys);
                runOnUiThread(() -> {
                    if (token != historyGeneration || isDestroyed()) return;
                    backHistory.clear(); backHistory.addAll(back);
                    forwardHistory.clear(); forwardHistory.addAll(forward);
                    if (active == this) actionBar();
                });
            });
        }
        private List<FileStore.Node> resolveHistory(List<String> keys) {
            List<FileStore.Node> result = new ArrayList<>();
            if (keys != null) for (String key : keys) try { result.add(store.fromKey(key)); }
                catch (IOException | RuntimeException ignored) { }
            return result;
        }
        void beginNavigation() {
            if (filteringPane == this && inlineFilter != null) inlineFilter.finishLastValid();
            historyGeneration++;
            cancelFilter();
            restoreNavigation();
            locatedKey = null; locatedName = null;
            navigation = new NavigationState(location, new ArrayList<>(backHistory), new ArrayList<>(forwardHistory), filter, new HashSet<>(selected));
        }
        final Map<String, int[]> positions = new LinkedHashMap<>();
        Pane(boolean isLeft) {
            super(MainActivity.this); this.isLeft = isLeft; setOrientation(VERTICAL); setBackgroundColor(Ui.SURFACE);
            indicator = new View(MainActivity.this); addView(indicator, lp(-1, 2));
            FrameLayout frame = new FrameLayout(MainActivity.this);
            list = new SwipeSelectListView(MainActivity.this); list.setDivider(null); list.setFastScrollEnabled(false);
            list.setVerticalScrollBarEnabled(false);
            list.setScrollingCacheEnabled(false); list.setSmoothScrollbarEnabled(true);
            list.setOnScrollListener(new AbsListView.OnScrollListener(){
                @Override public void onScroll(AbsListView v,int first,int visible,int total){if(chrome!=null)chrome.refreshGlass();}
                @Override public void onScrollStateChanged(AbsListView v,int state){if(chrome!=null)chrome.refreshGlass();}
            });
            list.setContentDescription(isLeft ? "左窗口文件列表" : "右窗口文件列表");
            adapter = new Adapter(); list.setAdapter(adapter); frame.addView(list, new FrameLayout.LayoutParams(-1, -1));
            empty = Ui.text(MainActivity.this, "读取中…", 12, Ui.MUTED); empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(12), dp(12), dp(12), dp(12));
            empty.setOnClickListener(view -> {
                activate(this);
                File directory = AndroidDataAccess.path(location);
                if (directory != null) dataAccess(this, directory);
                else if (StorageNavigation.parent(store, location) != null) up();
                else navigate(FileStore.directory(Environment.getExternalStorageDirectory(), false));
            });
            frame.addView(empty, new FrameLayout.LayoutParams(-1, -1)); list.setEmptyView(empty);
            hint = Ui.text(MainActivity.this, "", 12, Ui.MUTED); hint.setGravity(Gravity.CENTER);
            hint.setPadding(dp(12), dp(10), dp(12), dp(10)); hint.setVisibility(GONE);
            hint.setMinimumHeight(dp(48));
            hint.setOnClickListener(v -> {
                activate(this);
                if (!filter.isEmpty()) {
                    if (filteringPane == this && inlineFilter != null) { inlineFilter.clearAndFinish(); return; }
                    filter = ""; regex = false; persistFilter(this); apply();
                } else if (!showHidden) hiddenOptions();
            });
            FrameLayout.LayoutParams hintParams = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
            hintParams.topMargin = rowHeight() + dp(8); frame.addView(hint, hintParams);
            pullEdge = new PullEdgeView(MainActivity.this);
            frame.addView(pullEdge, new FrameLayout.LayoutParams(-1, dp(132), Gravity.TOP));
            spinnerRing = new ProgressBar(MainActivity.this, null, android.R.attr.progressBarStyleSmall);
            spinnerRing.setIndeterminate(true);
            spinnerRing.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
            spinnerRing.setVisibility(View.GONE);
            FrameLayout.LayoutParams spinnerParams = new FrameLayout.LayoutParams(dp(26), dp(26));
            spinnerParams.gravity = Gravity.CENTER_HORIZONTAL | Gravity.TOP; spinnerParams.topMargin = dp(8);
            frame.addView(spinnerRing, spinnerParams);
            refresh = new FrameLayout(MainActivity.this);
            refresh.addView(frame);
            addView(refresh, new LinearLayout.LayoutParams(-1, 0, 1));
            list.setSelectionListener(new SwipeSelectListView.Selection() {
                public boolean selected(int position) {
                    return position > 0 && position <= entries.size() && selected.contains(entries.get(position - 1).key());
                }
                public void change(int first, int last, boolean checked) {
                    activate(Pane.this);
                    rangeSelecting = false;
                    for (int i = Math.max(1, first); i <= Math.min(last, entries.size()); i++) {
                        String key = entries.get(i - 1).key();
                        if (checked) { selected.add(key); selectionAnchor = key; } else selected.remove(key);
                    }
                    selectionChanged();
                }
                public void gesture(boolean active) { refresh.setEnabled(!active); }
                public void pull(float distance) { pullEdge.setDepth(distance);if(chrome!=null)chrome.refreshGlass(); }
                public void refresh() { activate(Pane.this); reload(true); }
            });
            summary = Ui.single(MainActivity.this, "", 10, Ui.MUTED);
            summary.setPadding(dp(10), 0, dp(6), 0); summary.setBackgroundColor(Ui.PANEL);
            summary.setVisibility(GONE); addView(summary, lp(-1, 18));
            list.setOnTouchListener((v, e) -> {
                if (e.getAction() == MotionEvent.ACTION_DOWN) activate(this);
                return false;
            });

            list.setOnItemClickListener((p, v, pos, id) -> {
                if (!list.isEnabled()) return;
                activate(this); clearSearchMarks(); if (pos == 0) { up(); return; }
                FileStore.Node n = entries.get(pos - 1);
                if (!selected.isEmpty()) toggle(n); else if (n.directory) navigate(n); else open(n);
            });
            list.setOnItemLongClickListener((p, v, pos, id) -> {
                if (!list.isEnabled()) return true;
                activate(this); clearSearchMarks();
                if (pos > 0) {
                    FileStore.Node node = entries.get(pos - 1);
                    if (!selected.contains(node.key())) selected.add(node.key());
                    selectionAnchor = node.key(); rangeSelecting = false;
                    selectionChanged(); selectionMenu();
                }
                return true;
            });
            list.setOnDragListener((v, event) -> {
                if (!(event.getLocalState() instanceof DragItems drag)) return false;
                if (event.getAction() == DragEvent.ACTION_DRAG_STARTED) { refresh.setEnabled(false); return true; }
                if (event.getAction() == DragEvent.ACTION_DRAG_ENDED) {
                    refresh.setEnabled(true); setBackgroundColor(Ui.SURFACE); return true;
                }
                if (drag.source == this) return false;
                switch (event.getAction()) {
                    case DragEvent.ACTION_DRAG_ENTERED -> setBackgroundColor(Ui.TINT);
                    case DragEvent.ACTION_DRAG_EXITED -> setBackgroundColor(Ui.SURFACE);
                    case DragEvent.ACTION_DROP -> MaterialDialogs.builder(MainActivity.this)
                            .setTitle("传入 " + drag.nodes.size() + " 项")
                            .setMessage(location.path()).setNegativeButton("取消", null)
                            .setNeutralButton("移动", (d, w) -> transfer(drag.nodes, drag.source, this, true))
                            .setPositiveButton("复制", (d, w) -> transfer(drag.nodes, drag.source, this, false)).show();
                }
                return true;
            });
        }
        void resetSelection() {
            selected.clear(); selectionAnchor = null; rangeSelecting = false;
        }
        /** The path identity deliberately ignores whether the listing used ROOT. */
        void mark(FileStore.Node node) {
            marked = rowIdentity(node);
            adapter.refreshVisibleStates(false);
            scrollToMark();
        }
        void scrollToMark() {
            if (marked == null) return;
            for (int i = 0; i < entries.size(); i++) if (rowIdentity(entries.get(i)).equals(marked)) {
                list.setSelectionFromTop(i + 1, dp(12)); break;
            }
        }
        void clearMark() {
            if (marked == null) return;
            marked = null; adapter.refreshVisibleStates(false);
        }
        void selectionChanged() {
            if (selected.isEmpty()) { selectionAnchor = null; rangeSelecting = false; }
            if (selectionAnchor != null && !selected.contains(selectionAnchor)) selectionAnchor = null;
            adapter.refreshVisibleStates(true);
            if(chrome!=null)chrome.refreshGlass();
            if (active == this) actionBar();
        }
        void selectAll() {
            rangeSelecting = false;
            for (FileStore.Node node : entries) selected.add(node.key());
            selectionChanged();
        }
        FileStore.Node selectionReference() {
            for (FileStore.Node node : entries)
                if (node.key().equals(selectionAnchor) && selected.contains(node.key())) return node;
            for (FileStore.Node node : entries) if (selected.contains(node.key())) return node;
            return null;
        }
        void beginRange() {
            if (rangeSelecting) { rangeSelecting = false; actionBar(); return; }
            FileStore.Node reference = selectionReference();
            if (reference == null) return;
            selectionAnchor = reference.key(); rangeSelecting = true; actionBar();
            Tasks.toast(MainActivity.this, "以 " + reference.name + " 为起点，点击范围终点；长按连选可按条件选择");
        }
        void toggle(FileStore.Node node) {
            clearSearchMarks();
            if (rangeSelecting) {
                int start = -1, end = -1;
                for (int i = 0; i < entries.size(); i++) {
                    String key = entries.get(i).key();
                    if (key.equals(selectionAnchor)) start = i;
                    if (key.equals(node.key())) end = i;
                }
                if (start >= 0 && end >= 0) for (int i = Math.min(start, end); i <= Math.max(start, end); i++)
                    selected.add(entries.get(i).key());
                rangeSelecting = false;
            } else if (selected.add(node.key())) selectionAnchor = node.key();
            else selected.remove(node.key());
            selectionChanged();
        }
        List<FileStore.Node> chosen() {
            List<FileStore.Node> out = new ArrayList<>();
            // Numbered renames and conflict prompts follow the order visible to the user.
            for (FileStore.Node n : entries) if (selected.contains(n.key())) out.add(n); return out;
        }
        void navigate(FileStore.Node n) {
            exitArmedAt = 0;
            beginNavigation();
            rememberPosition();
            if (location != null) backHistory.push(location); forwardHistory.clear();
            clearMark();
            location = StorageNavigation.location(n); resetSelection(); filter = ""; persistFilter(this); reload(false, true, 1); if (active == this) actionBar();
        }
        void locate(FileStore.Node directory, String key, String name) {
            // Set the requested item after beginNavigation, before a cached listing can be shown.
            beginNavigation(); rememberPosition();
            boolean same = location != null && location.key().equals(directory.key());
            if (!same && location != null) { backHistory.push(location); forwardHistory.clear(); }
            clearMark(); location = StorageNavigation.location(directory); resetSelection();
            filter = ""; persistFilter(this); locatedKey = key; locatedName = name;
            reload(false, false, same ? 0 : 1);
            if (active == this) actionBar();
        }
        void back() {
            restoreNavigation();
            if (backHistory.isEmpty()) return;
            beginNavigation();
            exitArmedAt = 0;
            rememberPosition(); clearMark(); forwardHistory.push(location); location = backHistory.pop(); resetSelection(); filter = ""; persistFilter(this);
            reload(false, true, -1); actionBar();
        }
        void forward() {
            restoreNavigation();
            if (forwardHistory.isEmpty()) return;
            beginNavigation();
            exitArmedAt = 0;
            rememberPosition(); clearMark(); backHistory.push(location); location = forwardHistory.pop(); resetSelection(); filter = ""; persistFilter(this);
            reload(false, true, 1); actionBar();
        }
        void travel(boolean forward, FileStore.Node destination) {
            restoreNavigation();
            ArrayDeque<FileStore.Node> from = forward ? forwardHistory : backHistory;
            ArrayDeque<FileStore.Node> to = forward ? backHistory : forwardHistory;
            if (!from.contains(destination)) return;
            beginNavigation(); rememberPosition(); clearMark(); exitArmedAt = 0;
            do { to.push(location); location = from.pop(); } while (location != destination);
            resetSelection(); filter = ""; persistFilter(this);
            reload(false, true, forward ? 1 : -1); actionBar();
        }
        void up() {
            exitArmedAt = 0;
            FileStore.Node parent = StorageNavigation.parent(store, location);
            if (parent != null) {
                if (!backHistory.isEmpty() && backHistory.peek().key().equals(parent.key())) back();
                else navigate(parent);
            } else if (location.uri != null || location.container != null) back();
        }
        void rememberPosition() {
            if (pendingRestoreKey != null) return;
            if (location != null && location.key().equals(recordedDirectory) && list.getVisibility() == VISIBLE) {
                View first = list.getChildAt(0);
                positions.put(location.key(), new int[]{list.getFirstVisiblePosition(), first == null ? 0 : first.getTop()-list.getPaddingTop()});
                if (positions.size() > 64) positions.remove(positions.keySet().iterator().next());
            }
        }
        void chromeInsets(int top,int bottom) {
            if(list.getPaddingTop()==top && list.getPaddingBottom()==bottom)return;
            View first=list.getChildAt(0);int index=list.getFirstVisiblePosition();
            int offset=first==null?0:first.getTop()-list.getPaddingTop();
            list.setChromeInsets(top,bottom);
            if(pendingRestoreKey==null)list.setSelectionFromTop(index,offset);
            ((FrameLayout.LayoutParams)hint.getLayoutParams()).topMargin=top+rowHeight()+dp(8);
            ((FrameLayout.LayoutParams)spinnerRing.getLayoutParams()).topMargin=top+dp(8);
            ((FrameLayout.LayoutParams)pullEdge.getLayoutParams()).topMargin=top;
            hint.requestLayout();spinnerRing.requestLayout();pullEdge.requestLayout();
        }
        void reload() {
            reload(false);
        }
        void cancelListing() {
            if (listingSignal != null) { listingSignal.cancel(); listingSignal = null; }
            if (loading != null) { loading.cancel(false); loading = null; }
        }
        void reload(boolean feedback) {
            rememberPosition();
            if (location != null) directorySnapshots.remove(location.key());
            reload(feedback, false, 0);
        }
        void reload(boolean feedback, boolean allowCache, int direction) {
            if (location == null) return;
            // A directory already opened as ROOT keeps that handle: upgrading it would discard the cached listing.
            if (!location.root) location = RootAccess.cachedAccess(StorageNavigation.location(location));
            cancelFilter(); pendingRestoreKey = null;
            int token = ++generation; FileStore.Node current = location;
            cancelListing();
            long started = SystemClock.uptimeMillis();
            if (feedback) list.stopForRefresh(); else list.stopForNavigation();
            if (feedback) spinnerRing.setVisibility(VISIBLE);
            DirectorySnapshot cached = allowCache ? directorySnapshots.get(current.key()) : null;
            if (cached != null && started - cached.time < 15000) {
                show(current, cached, null, direction); spinnerRing.setVisibility(GONE);
                return;
            }
            if (direction != 0) {
                list.finishGesture(); list.setEnabled(false);
                empty.setText("读取中…");
                if (list.getChildCount() == 0) { list.setVisibility(GONE); empty.setVisibility(VISIBLE); }
                else {
                    spinnerRing.postDelayed(() -> {
                        if (token == generation && !list.isEnabled()) spinnerRing.setVisibility(VISIBLE);
                    }, 120);
                }
                if (active == this) header();
            }
            android.os.CancellationSignal signal = new android.os.CancellationSignal();
            listingSignal = signal;
            loading = Tasks.BROWSE.submit(() -> {
                if (token != generation || isDestroyed()) return;
                List<FileStore.Node> found; String error = null; FileStore.Node resolved = current;
                try {
                    resolved = AndroidDataAccess.resolve(MainActivity.this, store, current);
                    resolved = RootAccess.automatic(resolved);
                    if (feedback && resolved.container != null && resolved.container.file != null)
                        ArchiveOps.forget(resolved.container.file);
                    found = store.list(resolved, signal);
                }
                catch (Exception e) { found = Collections.emptyList(); error = Objects.toString(e.getMessage(), "读取目录失败"); }
                if (token != generation) return;
                Map<String, String> descriptions = new HashMap<>();
                SimpleDateFormat formatter = FileListDisplay.dates(MainActivity.this);
                boolean permissions = FileManagerSettings.prefs(MainActivity.this).getBoolean("list-permissions", false);
                for (FileStore.Node n : found) {
                    if (token != generation) return;
                    descriptions.put(n.key(), FileListDisplay.metadata(permissions, n, formatter));
                }
                File storagePath = resolved.file;
                FileStore.Node outer = resolved;
                while (outer.container != null) outer = outer.container;
                if (storagePath == null) storagePath = outer.file;
                long space = storagePath == null ? 0 : storagePath.getUsableSpace();
                long capacity = storagePath == null ? 0 : storagePath.getTotalSpace();
                List<FileStore.Node> result = found; String message = error; FileStore.Node loadedDirectory = resolved;
                runOnUiThread(() -> {
                    if (token != generation || isDestroyed()) return;
                    loading = null; listingSignal = null;
                    location = loadedDirectory;
                    int[] position = positions.get(current.key());
                    if (position != null) positions.put(loadedDirectory.key(), position);
                    if (feedback) {
                        spinnerRing.postDelayed(() -> { if (token == generation) spinnerRing.setVisibility(GONE); },
                                Math.max(0, 380 - (SystemClock.uptimeMillis() - started)));
                    } else spinnerRing.setVisibility(GONE);
                    DirectorySnapshot snapshot = new DirectorySnapshot(result, descriptions, space, capacity, SystemClock.uptimeMillis());
                    if (message == null && result.size() <= 12000) {
                        directorySnapshots.put(loadedDirectory.key(), snapshot);
                        int cachedNodes = 0;
                        for (DirectorySnapshot item : directorySnapshots.values()) cachedNodes += item.nodes.size();
                        Iterator<DirectorySnapshot> oldest = directorySnapshots.values().iterator();
                        while ((directorySnapshots.size() > 24 || cachedNodes > 24000) && oldest.hasNext()) {
                            cachedNodes -= oldest.next().nodes.size(); oldest.remove();
                        }
                    }
                    show(loadedDirectory, snapshot, message, direction);
                });
            });
        }
        void show(FileStore.Node current, DirectorySnapshot snapshot, String message, int direction) {
                    if (message != null && navigation != null && displayed != null) {
                        File denied = AndroidDataAccess.path(current);
                        restoreNavigation();
                        if (denied != null && !RootAccess.known() && !ShizukuStorage.granted()) dataAccess(this, denied);
                        else Tasks.toast(MainActivity.this, "无法读取，已返回原目录：" + message);
                        return;
                    }
                    if (message == null) { displayed = snapshot; navigation = null; }
                    list.setEnabled(true);
                    list.animate().cancel(); list.setAlpha(1f); list.setTranslationX(0f);
                    usableSpace = snapshot.space;
                    totalSpace = snapshot.totalSpace;
                    metadata = snapshot.metadata;
                    all.clear(); all.addAll(snapshot.nodes);
                    if (!selected.isEmpty()) {
                        Set<String> existing = new HashSet<>(); for (FileStore.Node n : all) existing.add(n.key());
                        selected.retainAll(existing);
                    }
                    pendingRestoreKey = message == null ? current.key() : null;
                    navigationMotion = message == null ? direction : 0;
                    apply();
                    empty.setText(message != null ? "无法读取：" + message + "\n点按此处授权或返回上级目录" : "此目录为空");
                    if (message != null) {
                        status.setText("目录不可读取 · 请检查权限");
                        list.setVisibility(GONE); empty.setVisibility(VISIBLE);
                    } else {
                        list.setVisibility(VISIBLE);
                        empty.setVisibility(GONE);
                        if (!current.key().equals(recordedDirectory)) {
                            directoryPlaces.visit(current); recordedDirectory = current.key();
                        }
                    }
                    if (message == null) startWatching(current);
                    if (active == this) actionBar();
                    updateHint();
        }
        void startWatching(FileStore.Node node) {
            watchEvents(node);
            FileStore.Node watched = node;
            while (watched.container != null) watched = watched.container;
            if (watched.root || watched.uri != null || node.container != null) {
                if (probeNode == null || !probeNode.key().equals(watched.key())) {
                    probeNode = watched; probeRevision = null; probeGeneration++;
                }
                list.removeCallbacks(externalProbe); list.postDelayed(externalProbe, 1000);
            }
        }
        private void watchEvents(FileStore.Node node) {
            if (node.uri != null) {
                String uriValue = node.uri.toString();
                if (documentObserver != null && uriValue.equals(observedPath)) return;
                stopWatching(); observedPath = uriValue;
                documentObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
                    @Override public void onChange(boolean selfChange, Uri uri) { scheduleExternalRefresh(); }
                    @Override public void onChange(boolean selfChange) { scheduleExternalRefresh(); }
                };
                try { getContentResolver().registerContentObserver(node.uri, true, documentObserver); }
                catch (RuntimeException unavailable) { documentObserver = null; observedPath = null; }
                return;
            }
            if (node.file == null || !node.directory) { stopWatching(); return; }
            String pathValue = node.file.getAbsolutePath();
            if (observer != null && pathValue.equals(observedPath)) return;
            stopWatching(); observedPath = pathValue;
            observer = new android.os.FileObserver(pathValue, android.os.FileObserver.ALL_EVENTS) {
                @Override public void onEvent(int event, String file) {
                    if ((event & (CREATE | DELETE | MOVED_FROM | MOVED_TO | CLOSE_WRITE | ATTRIB | DELETE_SELF | MOVE_SELF)) == 0) return;
                    scheduleExternalRefresh();
                }
            };
            try { observer.startWatching(); } catch (RuntimeException unavailable) { observer = null; observedPath = null; }
        }
        void scheduleExternalRefresh() {
            list.post(() -> { list.removeCallbacks(externalRefresh); list.postDelayed(externalRefresh, 650); });
        }
        void stopWatching() {
            list.removeCallbacks(externalRefresh);
            list.removeCallbacks(externalProbe);
            probeGeneration++; probeNode = null; probeRevision = null; probing = false;
            if (observer != null) { observer.stopWatching(); observer = null; }
            if (documentObserver != null) {
                try { getContentResolver().unregisterContentObserver(documentObserver); } catch (RuntimeException ignored) { }
                documentObserver = null;
            }
            observedPath = null;
        }
        void updateHint() {
            boolean showing = entries.isEmpty() && list.getVisibility() == VISIBLE && empty.getVisibility() != VISIBLE;
            if (!showing) { hint.setVisibility(GONE); hint.setClickable(false); return; }
            int hidden = 0;
            if (!showHidden) for (FileStore.Node n : all) if (n.name.startsWith(".")) hidden++;
            if (!filter.isEmpty()) hint.setText("没有匹配“" + filter + "”的项目 · 点按清除过滤");
            else if (hidden > 0) hint.setText("仅含 " + hidden + " 个隐藏项目 · 点按查看");
            else hint.setText("此目录为空");
            hint.setClickable(!filter.isEmpty() || hidden > 0);
            hint.setFocusable(hint.isClickable());
            if (hint.getVisibility() != VISIBLE) { hint.setVisibility(VISIBLE); if (hint.isShown()) Ui.fadeIn(hint); }
        }
        void apply() { apply(null); }
        void apply(Runnable applied) {
            list.finishGesture();
            cancelFilter();
            FileNameFilter compiled;
            try { compiled = FileNameFilter.compile(filter, regex); }
            catch (java.util.regex.PatternSyntaxException error) {
                // A malformed imported/saved rule must not leave a newly opened pane unusable.
                filter = ""; regex = false; persistFilter(this); compiled = FileNameFilter.compile("", false);
                Tasks.toast(MainActivity.this, "过滤表达式无效，已显示全部项目");
            }
            FileNameFilter match = compiled;
            int token = ++filterGeneration;
            boolean includeHidden = showHidden; int order = sort; String focus = locatedKey;
            List<FileStore.Node> source = new ArrayList<>(all);
            if (source.size() > 1200 || match.background()) {
                summary.setText("正在筛选 " + source.size() + " 项…");
                filterJob = Tasks.FILTER.submit(() -> {
                    try {
                        List<FileStore.Node> filtered = filterNodes(source, match, includeHidden, order, focus);
                        if (filtered == null || Thread.currentThread().isInterrupted()) return;
                        runOnUiThread(() -> { if (token == filterGeneration && !isDestroyed()) { applyResults(filtered); if (applied != null) applied.run(); } });
                    } catch (java.util.concurrent.CancellationException cancelled) { }
                });
                return;
            }
            applyResults(filterNodes(source, match, includeHidden, order, focus));
            if (applied != null) applied.run();
        }
        private void cancelFilter() {
            filterGeneration++;
            if (filterJob != null) { filterJob.cancel(true); filterJob = null; }
        }
        private List<FileStore.Node> filterNodes(List<FileStore.Node> source, FileNameFilter match, boolean includeHidden, int order, String focus) {
            List<FileStore.Node> filtered = new ArrayList<>();
            for (int i = 0; i < source.size(); i++) {
                if ((i & 127) == 0 && Thread.currentThread().isInterrupted()) return null;
                FileStore.Node n = source.get(i);
                if (matchesFocus(n, focus) || (includeHidden || !n.name.startsWith(".")) && match.matches(n.name)) filtered.add(n);
            }
            if (Thread.currentThread().isInterrupted()) return null;
            // FileStore already delivers natural name order with directories first.
            if (order != 0) filtered.sort((a, b) -> {
                if (a.directory != b.directory) return a.directory ? -1 : 1;
                int value = order == 1 ? Long.compare(b.modified, a.modified) : order == 2 ? Long.compare(b.size, a.size) : 0;
                return value != 0 ? value : NaturalOrder.compare(a.name, b.name);
            });
            if (Thread.currentThread().isInterrupted()) return null;
            return filtered;
        }
        private boolean matchesFocus(FileStore.Node node, String key) {
            if (key == null) return false;
            return node.key().equals(key) || node.file != null && node.file.getAbsolutePath().equals(key.startsWith("root:") ? key.substring(5) : key);
        }
        private void restoreResultPosition() {
            if (location == null || !location.key().equals(pendingRestoreKey)) return;
            pendingRestoreKey = null;
            int[] position = positions.get(location.key());
            list.setSelectionFromTop(position == null ? 0 : Math.min(position[0], entries.size()), position == null ? 0 : position[1]);
            if (reveal != null) {
                for (int i = 0; i < entries.size(); i++) if (entries.get(i).name.equals(reveal)) {
                    list.setSelectionFromTop(i + 1, dp(12)); break;
                }
                reveal = null;
            }
            scrollToMark();
            if (locatedKey != null) {
                FileStore.Node found = null;
                for (FileStore.Node node : entries) if (matchesFocus(node, locatedKey)) { found = node; break; }
                resultHighlights.clear();
                if (found != null) resultHighlights.add(found.key());
                else Tasks.toast(MainActivity.this, "未找到文件：" + locatedName + "，已打开可访问的上级目录");
                locatedKey = null; locatedName = null;
            }
            if (!resultHighlights.isEmpty()) {
                for (int i = 0; i < entries.size(); i++) if (resultHighlights.contains(entries.get(i).key()) || resultHighlights.contains(rowIdentity(entries.get(i)))) {
                    list.setSelectionFromTop(i + 1, dp(12)); break;
                }
                long stamp = highlightUntil = SystemClock.uptimeMillis() + 1400;
                adapter.refreshVisibleStates(false);
                list.postDelayed(() -> {
                    if (highlightUntil != stamp) return;
                    resultHighlights.clear(); highlightUntil = 0; adapter.refreshVisibleStates(false);
                }, 1400);
            }
        }
        private void applyResults(List<FileStore.Node> filtered) {
            entries.clear(); entries.addAll(filtered);
            if (!selected.isEmpty()) {
                Set<String> visibleKeys = new HashSet<>();
                for (FileStore.Node node : entries) visibleKeys.add(node.key());
                selected.retainAll(visibleKeys);
            }
            if (selectionAnchor != null && !selected.contains(selectionAnchor)) { selectionAnchor = null; rangeSelecting = false; }
            if (selected.isEmpty()) rangeSelecting = false;
            adapter.notifyDataSetChanged(); int folders = 0;
            if (navigationMotion != 0) { Ui.enterDirectory(list, navigationMotion); navigationMotion = 0; }
            for (FileStore.Node n : entries) if (n.directory) folders++;
            restoreResultPosition();
            folderCount = folders;
            summary.setText(folders + " 目录 · " + (entries.size() - folders) + " 文件" + (!filter.isEmpty() ? " · 过滤" : ""));
            updateHint();
            if (active == this) { header(); actionBar(); }
            if(chrome!=null)chrome.refreshGlass();
        }
        private final class Adapter extends BaseAdapter {
            public int getCount() { return entries.size() + 1; }
            public Object getItem(int pos) { return pos == 0 ? null : entries.get(pos - 1); }
            public long getItemId(int pos) { return pos; }
            void refreshVisibleStates(boolean animate) {
                int first = list.getFirstVisiblePosition();
                for (int index = 0; index < list.getChildCount(); index++) {
                    if (!(list.getChildAt(index).getTag() instanceof Row holder)) continue;
                    int position = first + index;
                    if (position > entries.size() || holder.node != getItem(position)) continue;
                    holder.bindState(true, animate);
                }
            }
            public View getView(int pos, View reuse, ViewGroup parent) {
                FileStore.Node n = pos == 0 ? null : entries.get(pos - 1);
                Row holder;
                if (reuse == null) holder = new Row();
                else holder = (Row) reuse.getTag();
                holder.node = n;
                holder.kind = FileIconTheme.kind(n == null ? "" : n.name,n == null || n.directory,FileIconTheme.EntryPoint.BROWSER);
                holder.icon.setContentDescription(n == null ? "上级目录" : n.name);
                holder.iconFrame.setContentDescription(n == null ? "上级目录" : n.name);
                holder.name.setText(n == null ? ".." : n.name);
                FileIconTheme.nameWeight(holder.name,n==null||n.directory);
                holder.meta.setText(n == null ? "" : metadata.get(n.key()));
                if(holder.meta.getCurrentTextColor()!=Ui.MUTED)holder.meta.setTextColor(Ui.MUTED);
                holder.meta.setVisibility(n == null ? View.GONE : View.VISIBLE);
                holder.bindState(false, false);
                holder.bindAppBadge(n, holder.checked);
                return holder.row;
            }
            private final class Row {
                final LinearLayout row = Ui.row(MainActivity.this);
                final ImageView icon = Ui.image(MainActivity.this, R.drawable.ic_folder, Color.WHITE);
                final FrameLayout iconFrame = new FrameLayout(MainActivity.this);
                final ImageView appBadge = new ImageView(MainActivity.this);
                final TextView name = Ui.single(MainActivity.this, "", 13, Ui.TEXT);
                final TextView meta = Ui.single(MainActivity.this, "", 10, Ui.MUTED);
                final android.graphics.drawable.GradientDrawable background = Ui.background(Ui.SURFACE, 0);
                final android.graphics.drawable.GradientDrawable iconBackground = Ui.background(Ui.ICON_BG, dp(5));
                final FileIconTheme.Binding iconBinding = new FileIconTheme.Binding(icon,iconBackground,comfortable?7:5,5);
                FileStore.Node node;
                FileIconTheme.Kind kind=FileIconTheme.Kind.FOLDER;
                int badgeGeneration, tintTarget;
                boolean checked;
                android.animation.ValueAnimator tint;
                void bindState(boolean attached, boolean animate) {
                    boolean selectedNow = node != null && selected.contains(node.key());
                    boolean markedNow = node != null && rowIdentity(node).equals(Pane.this.marked);
                    boolean result = node != null && SystemClock.uptimeMillis() < highlightUntil
                            && (resultHighlights.contains(node.key()) || resultHighlights.contains(rowIdentity(node)));
                    boolean toggled = checked != selectedNow;
                    checked = selectedNow;
                    showSelection(checked || result, attached, animate && toggled);
                    int color = markedNow || result ? Ui.HIT : Ui.TEXT;
                    if (name.getCurrentTextColor() != color) name.setTextColor(color);
                    iconBinding.bind(kind,checked);
                    iconFrame.setSelected(checked);
                    icon.setSelected(checked);
                    if (attached && toggled) bindAppBadge(node, checked);
                }
                void showSelection(boolean on, boolean attached, boolean animate) {
                    int to = on ? Ui.TINT : Ui.SURFACE;
                    if (attached && tint != null && tintTarget == to) return;
                    if (tint != null) { tint.cancel(); tint = null; }
                    tintTarget = to;
                    android.content.res.ColorStateList current = background.getColor();
                    int from = current == null ? to : current.getDefaultColor();
                    if (!animate || from == to || !row.isShown() || !Ui.motionEnabled()) {
                        background.setColor(to); icon.animate().cancel(); icon.setScaleX(1f); icon.setScaleY(1f); return;
                    }
                    tint = android.animation.ValueAnimator.ofArgb(from, to).setDuration(Ui.MOTION_SHORT);
                    tint.setInterpolator(Ui.EASING);
                    tint.addUpdateListener(a -> background.setColor((int) a.getAnimatedValue()));
                    tint.addListener(new android.animation.AnimatorListenerAdapter() {
                        @Override public void onAnimationEnd(android.animation.Animator a) { if (tint == a) tint = null; }
                    });
                    tint.start();
                    if (on) {
                        icon.animate().cancel(); icon.setScaleX(.78f); icon.setScaleY(.78f);
                        icon.animate().scaleX(1f).scaleY(1f).setDuration(Ui.MOTION_SHORT).setInterpolator(Ui.EASING).start();
                    }
                }
                void bindAppBadge(FileStore.Node entry, boolean checked) {
                    int token = ++badgeGeneration;
                    appBadge.setImageDrawable(null); appBadge.setVisibility(View.GONE);
                    if (checked || !FileManagerSettings.prefs(MainActivity.this).getBoolean("app-badges", true)
                            || !AppIcons.packageDirectory(location, entry)) return;
                    appIcons.load(entry.name, image -> {
                        if (isDestroyed() || token != badgeGeneration || node != entry) return;
                        appBadge.setImageDrawable(image);
                        appBadge.setVisibility(image == null ? View.GONE : View.VISIBLE);
                        if(chrome!=null)chrome.refreshGlass();
                    });
                }
                Row() {
                    int iconSize = comfortable ? 36 : 28, frameWidth = iconSize + 4, badgeSize = comfortable ? 22 : 18;
                    name.setTextSize(comfortable ? 15 : 13); meta.setTextSize(comfortable ? 11 : 10);
                    row.setPadding(dp(5), dp(2), dp(5), dp(2));
                    int lines = FileListDisplay.lines(MainActivity.this);
                    row.setMinimumHeight(rowHeight());
                    row.setLayoutParams(new AbsListView.LayoutParams(-1, lines > 1 ? -2 : rowHeight())); row.setTag(this);
                    row.setBackground(new android.graphics.drawable.RippleDrawable(
                            android.content.res.ColorStateList.valueOf(Ui.rippleColor()), background, null));
                    iconFrame.addView(icon, new FrameLayout.LayoutParams(dp(iconSize), dp(iconSize), Gravity.CENTER_VERTICAL | Gravity.START));
                    appBadge.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    appBadge.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                    appBadge.setVisibility(View.GONE);
                    // The frame spans the row height for a larger tap target; the badge stays on the icon's corner.
                    FrameLayout.LayoutParams badge = new FrameLayout.LayoutParams(dp(badgeSize), dp(badgeSize), Gravity.BOTTOM | Gravity.END);
                    iconFrame.addView(appBadge, badge);
                    row.addView(iconFrame, new LinearLayout.LayoutParams(dp(frameWidth), -1));
                    LinearLayout text = Ui.column(MainActivity.this); name.setEllipsize(TextUtils.TruncateAt.END);
                    if (lines > 1) { name.setSingleLine(false); name.setMaxLines(lines); name.setMinHeight(scaled(comfortable ? 24 : 21)); }
                    text.addView(name, new LinearLayout.LayoutParams(-1, lines > 1 ? -2 : scaled(comfortable ? 24 : 21)));
                    text.addView(meta, new LinearLayout.LayoutParams(-1, scaled(comfortable ? 17 : 15)));
                    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1);
                    p.leftMargin = dp(comfortable ? 8 : 4); p.gravity = Gravity.CENTER_VERTICAL;
                    row.addView(text, p);
                    icon.setClickable(false); icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                    iconFrame.setFocusable(false);
                    // Let ListView own the whole row gesture, including the icon. Rebinding a
                    // separately clickable icon during long press can turn its release into a tap.
                    iconFrame.setClickable(false);
                    iconFrame.setLongClickable(false);
                }
            }
        }
    }
    private record DragItems(Pane source, List<FileStore.Node> nodes) {}
}
