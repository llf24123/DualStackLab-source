package org.example.filemanager;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** One package query per cache miss, shared by both panes without blocking directory enumeration. */
final class AppIcons {
    private static final java.util.regex.Pattern USER_APPS =
            java.util.regex.Pattern.compile("/(?:data|mnt/expand/[^/]+)/user(?:_de)?/\\d+");
    private static final java.util.regex.Pattern PACKAGE =
            java.util.regex.Pattern.compile("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+");
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "directory-app-icons"); thread.setDaemon(true); return thread;
    });
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<String, Optional<Drawable.ConstantState>> cache = new LruCache<>(256);
    private final Map<String, List<Consumer<Drawable>>> pending = new HashMap<>();

    AppIcons(Context context) { this.context = context.getApplicationContext(); }

    static boolean packageDirectory(FileStore.Node parent, FileStore.Node node) {
        if (parent == null || parent.file == null || node == null || node.file == null || !node.directory) return false;
        String path = parent.file.toPath().toAbsolutePath().normalize().toString();
        boolean container = path.endsWith("/Android/data") || path.endsWith("/Android/obb")
                || path.equals("/data/data") || USER_APPS.matcher(path).matches();
        return container && PACKAGE.matcher(node.name).matches();
    }

    /** Called on the main thread; cached misses also finish so recycled rows never retain an old icon. */
    void load(String name, Consumer<Drawable> done) {
        Optional<Drawable.ConstantState> known = cache.get(name);
        if (known != null) { done.accept(drawable(known)); return; }
        List<Consumer<Drawable>> waiting = pending.get(name);
        if (waiting != null) { waiting.add(done); return; }
        pending.put(name, new ArrayList<>(List.of(done)));
        WORKER.execute(() -> {
            Drawable.ConstantState state = null;
            try { state = context.getPackageManager().getApplicationIcon(name).getConstantState(); }
            catch (PackageManager.NameNotFoundException | RuntimeException ignored) { }
            Optional<Drawable.ConstantState> result = Optional.ofNullable(state);
            main.post(() -> {
                cache.put(name, result);
                List<Consumer<Drawable>> callbacks = pending.remove(name);
                if (callbacks != null) for (Consumer<Drawable> callback : callbacks) callback.accept(drawable(result));
            });
        });
    }

    private Drawable drawable(Optional<Drawable.ConstantState> state) {
        return state.map(value -> value.newDrawable(context.getResources()).mutate()).orElse(null);
    }
}
