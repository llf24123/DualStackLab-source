package org.example.filemanager;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import androidx.exifinterface.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.WebView;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class PreviewActivity extends LabActivity {
    private FileStore store;
    private FileStore.Node source;
    private File file;
    private VideoView player;
    private boolean activityResumed, mediaPrepared, resumeVideoOnResume = true;
    private int mediaPosition;
    private WebView web;
    private TextView status;
    private LinearLayout body;
    private LinearLayout toolbar;
    private volatile Bitmap bitmap;
    private final Object pictureCacheLock = new Object();
    private final LinkedHashMap<String, CachedPicture> pictureCache = new LinkedHashMap<>(4, .75f, true);
    private volatile boolean destroyed;
    private TextView title, previous, next;
    private LinearLayout imageControls;
    private ZoomImageView imageView;
    private final List<FileStore.Node> pictures = new ArrayList<>();
    private int pictureIndex = -1;
    private boolean galleryLoading;
    private volatile int pictureGeneration;
    private ExecutorService pictureWorker;
    private Future<?> pictureJob, detailJob;
    private volatile String requestedPictureKey;
    private final Map<String, Future<?>> prefetchJobs = new HashMap<>();
    private Picture displayedPicture;
    private String displayedVersion;
    private int detailGeneration = -1, pagingDirection = 1;
    private android.database.ContentObserver pictureObserver;
    private Uri observedPictureUri;
    private final java.util.concurrent.atomic.AtomicLong pictureSourceChanges = new java.util.concurrent.atomic.AtomicLong();
    private boolean immersive;
    private volatile boolean thumbnails;
    private HorizontalScrollView thumbnailStrip;
    private LinearLayout thumbnailItems;
    private final Map<String, Thumbnail> thumbnailCache = new HashMap<>();
    private static final class Thumbnail {
        final ImageView view;
        Bitmap bitmap; // Owned and released only on the UI thread.
        volatile String version;
        Thumbnail(ImageView view) { this.view = view; }
        void release() {
            version = null; view.setImageDrawable(null);
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
            bitmap = null;
        }
    }
    private final List<Future<?>> backgroundPictures = new ArrayList<>();
    private File animationFile;
    private FileStore.Node animationSource;
    // Only the serial image worker owns these handles.
    private FileStore.ArchiveReads galleryReads;
    private FileStore.ArchiveSnapshot gallerySnapshot;
    private String galleryArchiveKey, galleryArchiveVersion;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved); store = new FileStore(this);
        if (saved != null) {
            mediaPosition = saved.getInt("mediaPosition", 0);
            resumeVideoOnResume = saved.getBoolean("mediaPlaying", true);
        }
        body = Ui.column(this);
        toolbar = Ui.toolbar(this, getIntent().getStringExtra("name") == null ? "预览" : getIntent().getStringExtra("name"), this::finish);
        title = (TextView) toolbar.getChildAt(1); body.addView(toolbar);
        status = Ui.text(this, "读取中…", 13, Ui.MUTED); status.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 12));
        body.addView(status); Ui.install(this, body);
        String kind = getIntent().getStringExtra("kind");
        if ("image".equals(kind)) { images(saved); return; }
        Tasks.run(this, () -> {
            FileStore.Node node = store.fromIntent(getIntent());
            File content = store.materialize(node);
            synchronized (this) {
                if (destroyed) { release(node, content); return null; }
                source = node; file = content;
            }
            return new Source(node, content);
        }, result -> {
            if (result == null) return;
            if ("html".equals(kind) || "svg".equals(kind)) document(kind);
            else media("audio".equals(kind));
        }, error -> status.setText("无法预览：" + error.getMessage()));
    }
    private record Source(FileStore.Node node, File file) {}
    static boolean isImageName(String name) { return MediaKind.of(name) == MediaKind.IMAGE; }
    private void images(Bundle saved) {
        pictureWorker = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        imageView = new ZoomImageView(this); imageView.setContentDescription("图片预览");
        imageView.setOnPageChangeListener(this::changePicture);
        imageView.setOnZoomListener(this::loadPictureDetail);
        imageView.setCanChangePageListener(this::canChangePicture);
        imageView.setOnSingleTapListener(() -> {
            setPictureFullscreen(!immersive);
        });
        body.addView(imageView, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout controls = Ui.row(this);
        imageControls = controls;
        String[] labels = {"上一张", "缩小", "适屏", "放大", "下一张"};
        for (int i = 0; i < labels.length; i++) {
            int action = i; TextView button = Ui.text(this, labels[i], 13, Ui.ACCENT);
            button.setGravity(android.view.Gravity.CENTER); button.setBackground(Ui.ripple(Ui.PANEL)); button.setFocusable(true);
            button.setContentDescription("图片" + labels[i]); button.setOnClickListener(v -> {
                if (action == 0 || action == 4) changePicture(action == 0 ? -1 : 1);
                else if (action == 2) imageView.reset();
                else imageView.zoom(action == 1 ? 1f / 1.5f : 1.5f, imageView.getWidth() / 2f, imageView.getHeight() / 2f);
            });
            button.setMinHeight(Ui.dp(this, 48)); button.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
            controls.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
            if (i == 0) previous = button; if (i == 4) next = button;
        }
        body.addView(controls);
        TextView thumbnailToggle = Ui.text(this, "缩略图", 14, Ui.ACCENT);
        thumbnailToggle.setMinWidth(Ui.dp(this, 64)); thumbnailToggle.setMinHeight(Ui.dp(this, 48));
        thumbnailToggle.setGravity(android.view.Gravity.CENTER); thumbnailToggle.setBackground(Ui.ripple(Ui.PANEL));
        thumbnailToggle.setContentDescription("切换图片缩略图"); thumbnailToggle.setFocusable(true);
        thumbnailToggle.setOnClickListener(v -> {
            thumbnails = !thumbnails; getPreferences(0).edit().putBoolean("thumbnails", thumbnails).apply();
            thumbnailStrip.setVisibility(thumbnails ? View.VISIBLE : View.GONE); loadThumbnails(pictureGeneration);
        });
        toolbar.addView(thumbnailToggle);
        thumbnailStrip = new HorizontalScrollView(this); thumbnailStrip.setVisibility(View.GONE);
        thumbnailStrip.setContentDescription("图片缩略图列表");
        thumbnailItems = Ui.row(this); thumbnailStrip.addView(thumbnailItems);
        body.addView(thumbnailStrip, new LinearLayout.LayoutParams(-1, Ui.dp(this, 64)));
        thumbnails = getPreferences(0).getBoolean("thumbnails", false);
        thumbnailStrip.setVisibility(thumbnails ? View.VISIBLE : View.GONE);
        updateNavigation();
        String restored = saved == null ? null : saved.getString("pictureKey");
        galleryLoading = true;
        Tasks.run(this, () -> restored == null ? store.fromIntent(getIntent()) : store.fromKey(restored), selected -> {
            pictures.add(selected); showPicture(0);
            loadGallery(selected);
        }, error -> status.setText("无法预览：" + error.getMessage()));
    }
    private void loadGallery(FileStore.Node selected) {
        Tasks.run(this, Tasks.BROWSE, () -> {
            List<FileStore.Node> siblings = new ArrayList<>();
            try {
                String parentKey = getIntent().getStringExtra("imageParentKey");
                FileStore.Node parent = parentKey == null ? store.parent(selected) : store.fromKey(parentKey);
                String filter = getIntent().getStringExtra("imageFilter");
                FileNameFilter match = FileNameFilter.compile(filter == null ? "" : filter, getIntent().getBooleanExtra("imageRegex", false));
                if (parent != null) for (FileStore.Node child : store.list(parent)) {
                    if (!child.directory && isImageName(child.name) && match.matches(child.name)
                            && (getIntent().getBooleanExtra("imageShowHidden", true) || !child.name.startsWith(".") || child.key().equals(selected.key())))
                        siblings.add(child);
                }
            } catch (IOException | SecurityException unavailable) { /* A standalone URI can still display its selected image. */ }
            if (siblings.stream().noneMatch(n -> n.key().equals(selected.key()))) siblings.add(selected);
            int sort = getIntent().getIntExtra("imageSort", 0);
            siblings.sort((a, b) -> {
                int order = sort == 1 ? Long.compare(b.modified, a.modified) : sort == 2 ? Long.compare(b.size, a.size) : 0;
                return order == 0 ? NaturalOrder.compare(a.name, b.name) : order;
            });
            return new Gallery(selected.key(), siblings);
        }, gallery -> {
            String oldPosition = position();
            String selectedKey = source == null ? gallery.selected : source.key();
            galleryLoading = false; pictures.clear(); pictures.addAll(gallery.nodes);
            int index = 0;
            for (int i = 0; i < pictures.size(); i++) if (pictures.get(i).key().equals(selectedKey)) index = i;
            pictureIndex = index; updateNavigation();
            String message = status.getText().toString();
            if (message.startsWith(oldPosition)) status.setText(position() + message.substring(oldPosition.length()));
            if (displayedPicture != null) { prefetchPictures(pictureGeneration, index); loadThumbnails(pictureGeneration); }
        }, error -> { galleryLoading = false; updateNavigation(); });
    }
    private record Gallery(String selected, List<FileStore.Node> nodes) { }
    private void setPictureFullscreen(boolean value) {
        immersive = value;
        toolbar.setVisibility(value ? View.GONE : View.VISIBLE);
        status.setVisibility(value ? View.GONE : View.VISIBLE);
        imageControls.setVisibility(value ? View.GONE : View.VISIBLE);
        thumbnailStrip.setVisibility(!value && thumbnails ? View.VISIBLE : View.GONE);
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                if (value) controller.hide(WindowInsets.Type.systemBars()); else controller.show(WindowInsets.Type.systemBars());
            }
        } else getWindow().getDecorView().setSystemUiVisibility(value
                ? View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                : Ui.dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    }
    @Override protected void handleBack() { if (imageView != null && immersive) setPictureFullscreen(false); else super.handleBack(); }
    private boolean canChangePicture(int delta) {
        int index = pictureIndex + delta;
        return pictureIndex >= 0 && index >= 0 && index < pictures.size();
    }
    private void changePicture(int delta) {
        if (canChangePicture(delta)) showPicture(pictureIndex + delta);
    }
    private void updateNavigation() {
        if (previous == null) return;
        previous.setEnabled(pictureIndex > 0); next.setEnabled(pictureIndex >= 0 && pictureIndex + 1 < pictures.size());
        previous.setAlpha(previous.isEnabled() ? 1 : .35f); next.setAlpha(next.isEnabled() ? 1 : .35f);
    }
    private String position() { return (pictureIndex + 1) + " / " + (galleryLoading ? "…" : pictures.size()); }
    private boolean currentPicture(int token) { return !destroyed && !isFinishing() && token == pictureGeneration; }
    private void showPicture(int index) {
        int direction = pictureIndex < 0 ? 0 : Integer.compare(index, pictureIndex);
        if (direction != 0) pagingDirection = direction;
        pictureIndex = index; source = pictures.get(index); requestedPictureKey = source.key();
        int token = ++pictureGeneration;
        displayedPicture = null; displayedVersion = null; detailGeneration = -1;
        if (detailJob != null) { detailJob.cancel(true); detailJob = null; }
        prefetchJobs.entrySet().removeIf(entry -> {
            if (entry.getKey().equals(requestedPictureKey) && !entry.getValue().isDone()) return false;
            entry.getValue().cancel(true); return true;
        });
        if (pictureJob != null) pictureJob.cancel(true);
        for (Future<?> pending : backgroundPictures) pending.cancel(true);
        backgroundPictures.clear();
        if (pictureWorker instanceof ThreadPoolExecutor pool) pool.purge();
        title.setText(source.name); status.setText(position() + " · 读取中…"); updateNavigation();
        imageView.waitForPage(direction);
        FileStore.Node selected = source;
        pictureJob = pictureWorker.submit(() -> {
            File content = null;
            try {
                if (!currentPicture(token)) return;
                preparePictureSource(selected);
                String version = pictureVersion(selected);
                CachedPicture cachedPicture = cachedPicture(selected.key(), version);
                Bitmap cached = cachedPicture == null ? null : cachedPicture.picture.bitmap;
                if (cached == null || cached.isRecycled()) content = pictureFile(selected);
                if (!currentPicture(token) || Thread.currentThread().isInterrupted()) return;
                Picture loaded = cached != null && !cached.isRecycled()
                        ? cachedPicture.picture : decodePicture(content, selected.name);
                if (!version.equals(pictureVersion(selected))) {
                    if (loaded.bitmap != null && loaded.bitmap != cached) loaded.bitmap.recycle();
                    throw new IOException("图片读取期间发生变化，请重新打开");
                }
                if (destroyed || Thread.currentThread().isInterrupted()) {
                    if (loaded.bitmap != null && loaded.bitmap != cached && !loaded.bitmap.isRecycled()) loaded.bitmap.recycle();
                    return;
                }
                List<Bitmap> evicted = new ArrayList<>();
                synchronized (pictureCacheLock) {
                    if (destroyed) {
                        if (loaded.bitmap != null && loaded.bitmap != cached && !loaded.bitmap.isRecycled()) loaded.bitmap.recycle();
                        return;
                    }
                    if (loaded.bitmap != null) cachePictureLocked(selected.key(), new CachedPicture(version, loaded), evicted, false);
                }
                for (Bitmap old : evicted) if (!old.isRecycled()) old.recycle();
                Picture result = loaded;
                File animationContent = result.animated == null ? null : content;
                if (animationContent != null) content = null;
                runOnUiThread(() -> {
                    if (!currentPicture(token)) {
                        recycleIfUnused(result.bitmap);
                        if (animationContent != null) Tasks.IO.execute(() -> release(selected, animationContent));
                        return;
                    }
                    displayedPicture = result; displayedVersion = version;
                    Bitmap previous;
                    synchronized (pictureCacheLock) { previous = bitmap; bitmap = result.bitmap; }
                    File oldAnimationFile = animationFile; FileStore.Node oldAnimationSource = animationSource;
                    animationFile = animationContent; animationSource = selected;
                    boolean first = imageView.getDrawable() == null;
                    if (result.animated != null) {
                        Drawable previousDrawable = imageView.getDrawable();
                        if (previousDrawable instanceof Animatable oldAnimation) oldAnimation.stop();
                        imageView.setImageDrawable(result.animated);
                        imageView.setLayerType(result.animated instanceof GifDrawable ? View.LAYER_TYPE_SOFTWARE : View.LAYER_TYPE_NONE, null);
                        if (result.animated instanceof Animatable animated) animated.start();
                    } else { imageView.setLayerType(View.LAYER_TYPE_NONE, null); imageView.setImageBitmap(result.bitmap); }
                    imageView.presentPage(direction, first);
                    if (oldAnimationFile != null) Tasks.IO.execute(() -> release(oldAnimationSource, oldAnimationFile));
                    recycleIfUnused(previous);
                    String dimensions = result.dimensions;
                    if (dimensions.isEmpty() && result.bitmap != null) dimensions = result.bitmap.getWidth() + " × " + result.bitmap.getHeight();
                    status.setText(position() + " · " + dimensions + " · " + FileStore.size(result.bytes));
                    prefetchPictures(token, pictureIndex);
                    loadThumbnails(token);
                });
            } catch (Exception error) {
                runOnUiThread(() -> { if (currentPicture(token)) {
                    Drawable stale = imageView.getDrawable();
                    if (stale instanceof Animatable animation) animation.stop();
                    imageView.setImageDrawable(null); imageView.setLayerType(View.LAYER_TYPE_NONE, null);
                    Bitmap previous;
                    synchronized (pictureCacheLock) { previous = bitmap; bitmap = null; }
                    recycleIfUnused(previous);
                    File oldAnimation = animationFile; FileStore.Node oldSource = animationSource;
                    animationFile = null; animationSource = null;
                    if (oldAnimation != null) Tasks.IO.execute(() -> release(oldSource, oldAnimation));
                    imageView.presentPage(0, false);
                    status.setText(position() + " · 无法预览：" + error.getMessage());
                    loadThumbnails(token);
                } });
            } finally { if (content != null) release(selected, content); }
        });
    }
    private void loadPictureDetail() {
        if (displayedPicture == null || !displayedPicture.reduced || displayedPicture.bitmap == null
                || detailGeneration == pictureGeneration || destroyed) return;
        int token = detailGeneration = pictureGeneration;
        FileStore.Node selected = source; String version = displayedVersion;
        for (Future<?> job : prefetchJobs.values()) job.cancel(true);
        prefetchJobs.clear();
        for (Future<?> job : backgroundPictures) job.cancel(true);
        backgroundPictures.clear();
        if (pictureWorker instanceof ThreadPoolExecutor pool) pool.purge();
        detailJob = pictureWorker.submit(() -> {
            File content = null; Bitmap decoded = null;
            try {
                if (!currentPicture(token) || !version.equals(pictureVersion(selected))) return;
                content = pictureFile(selected);
                Picture detailed = decodePicture(content, selected.name, true); decoded = detailed.bitmap;
                if (decoded == null || !currentPicture(token) || !version.equals(pictureVersion(selected))) return;
                Bitmap result = decoded; decoded = null;
                runOnUiThread(() -> {
                    if (!currentPicture(token) || imageView.zoomFactor() <= 1.05f) {
                        result.recycle(); if (currentPicture(token)) detailGeneration = -1; return;
                    }
                    Bitmap old;
                    synchronized (pictureCacheLock) { old = bitmap; bitmap = result; }
                    displayedPicture = detailed;
                    imageView.replaceWithDetail(result);
                    recycleIfUnused(old);
                });
            } catch (IOException | RuntimeException ignored) {
                // The fitted preview stays usable if the optional detail read fails.
                runOnUiThread(() -> { if (currentPicture(token)) detailGeneration = -1; });
            } finally {
                if (decoded != null && !decoded.isRecycled()) decoded.recycle();
                if (content != null) release(selected, content);
            }
        });
    }
    private static Picture decodePicture(File content, String name) throws IOException {
        return decodePicture(content, name, false);
    }
    private static Picture decodePicture(File content, String name, boolean detail) throws IOException {
        long decodeBudget = detail ? Math.min(48L << 20, Runtime.getRuntime().maxMemory() / 4)
                : Math.min(12L << 20, PICTURE_CACHE_BUDGET / 3);
        try {
            Tasks.Progress.check();
            String lower = name.toLowerCase(Locale.ROOT);
            if (android.os.Build.VERSION.SDK_INT < 28 && lower.endsWith(".gif")) {
                android.graphics.Movie movie = android.graphics.Movie.decodeFile(content.getPath());
                if (movie != null) return new Picture(null, new GifDrawable(movie),
                        movie.width() + " × " + movie.height() + " · 动图", content.length());
            }
            if (android.os.Build.VERSION.SDK_INT >= 28 && (lower.endsWith(".gif") || lower.endsWith(".webp"))) {
                int[] original = new int[2];
                Drawable decoded = android.graphics.ImageDecoder.decodeDrawable(
                        android.graphics.ImageDecoder.createSource(content), (decoder, info, source) -> {
                            decoder.setAllocator(android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE);
                            decoder.setMutableRequired(false);
                            int width = info.getSize().getWidth(), height = info.getSize().getHeight();
                            original[0] = width; original[1] = height;
                            double scale = Math.min(1d, Math.min(4096d / Math.max(width, height),
                                    Math.sqrt(decodeBudget / (4d * width * height))));
                            decoder.setTargetSize(Math.max(1, (int) (width * scale)), Math.max(1, (int) (height * scale)));
                        });
                if (decoded instanceof AnimatedImageDrawable) return new Picture(null, decoded,
                        decoded.getIntrinsicWidth() + " × " + decoded.getIntrinsicHeight() + " · 动图", content.length());
                if (decoded instanceof android.graphics.drawable.BitmapDrawable still) {
                    Bitmap decodedBitmap = still.getBitmap();
                    if (decodedBitmap != null) return new Picture(decodedBitmap, null,
                            original[0] + " × " + original[1], content.length(),
                            decodedBitmap.getWidth() < original[0] || decodedBitmap.getHeight() < original[1]);
                }
            }
            BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(content.getAbsolutePath(), options);
            if (options.outWidth <= 0) throw new IOException("文件不是可识别图片");
            int originalWidth = options.outWidth, originalHeight = options.outHeight;
            options.inJustDecodeBounds = false; options.inSampleSize = 1;
            // Fit previews must leave room for both neighbours. Larger detail is decoded only on zoom.
            while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 4096
                    || (long) (options.outWidth / options.inSampleSize) * (options.outHeight / options.inSampleSize) * 4 > decodeBudget)
                options.inSampleSize *= 2;
            Bitmap image = BitmapFactory.decodeFile(content.getAbsolutePath(), options);
            if (image == null) throw new IOException("图片解码失败");
            if (Thread.currentThread().isInterrupted()) { image.recycle(); throw new Tasks.CancelledException(); }
            int orientation = imageOrientation(content);
            image = orientBitmap(image, orientation);
            boolean swapsDimensions = orientation == ExifInterface.ORIENTATION_TRANSPOSE
                    || orientation == ExifInterface.ORIENTATION_ROTATE_90
                    || orientation == ExifInterface.ORIENTATION_TRANSVERSE
                    || orientation == ExifInterface.ORIENTATION_ROTATE_270;
            String dimensions = swapsDimensions ? originalHeight + " × " + originalWidth
                    : originalWidth + " × " + originalHeight;
            return new Picture(image, null, dimensions, content.length(), options.inSampleSize > 1);
        } catch (OutOfMemoryError memory) { throw new IOException("图片过大，内存不足", memory); }
    }
    private static int imageOrientation(File file) {
        try { return new ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL); }
        catch (IOException ignored) { return ExifInterface.ORIENTATION_NORMAL; }
    }
    private static Bitmap orientBitmap(Bitmap image, int orientation) {
        Matrix transform = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> transform.setScale(-1, 1);
            case ExifInterface.ORIENTATION_ROTATE_180 -> transform.setRotate(180);
            case ExifInterface.ORIENTATION_FLIP_VERTICAL -> transform.setScale(1, -1);
            case ExifInterface.ORIENTATION_TRANSPOSE -> { transform.setRotate(90); transform.postScale(-1, 1); }
            case ExifInterface.ORIENTATION_ROTATE_90 -> transform.setRotate(90);
            case ExifInterface.ORIENTATION_TRANSVERSE -> { transform.setRotate(-90); transform.postScale(-1, 1); }
            case ExifInterface.ORIENTATION_ROTATE_270 -> transform.setRotate(-90);
        }
        if (transform.isIdentity()) return image;
        Bitmap result = Bitmap.createBitmap(image, 0, 0, image.getWidth(), image.getHeight(), transform, true);
        if (result != image) image.recycle();
        return result;
    }
    private static Bitmap decodeThumbnail(File file) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), options);
        if (options.outWidth <= 0 || options.outHeight <= 0) throw new IOException("文件不是可识别图片");
        options.inJustDecodeBounds = false; options.inSampleSize = 1;
        while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 128) options.inSampleSize *= 2;
        Bitmap image = BitmapFactory.decodeFile(file.getPath(), options);
        return image == null ? null : orientBitmap(image, imageOrientation(file));
    }
    private record Picture(Bitmap bitmap, Drawable animated, String dimensions, long bytes, boolean reduced) {
        Picture(Bitmap bitmap, Drawable animated, String dimensions, long bytes) { this(bitmap, animated, dimensions, bytes, false); }
    }
    private File pictureFile(FileStore.Node node) throws IOException {
        // A read-only preview can use the readable app view even when browsing selected a ROOT handle.
        if (node.container == null && node.file != null && node.file.isFile() && node.file.canRead()) return node.file;
        return store.materialize(node);
    }
    private record CachedPicture(String version, Picture picture) {}
    private String pictureVersion(FileStore.Node node) throws IOException {
        FileStore.Node outer = node;
        String suffix = "";
        while (outer.container != null) { suffix = "!/" + outer.entry + suffix; outer = outer.container; }
        String version;
        if (outer.file != null && outer.file.canRead()) {
            try { version = FileStore.statIdentity(android.system.Os.stat(outer.path())) + ":"
                    + java.nio.file.Files.getLastModifiedTime(outer.file.toPath()).to(TimeUnit.NANOSECONDS); }
            catch (android.system.ErrnoException error) { throw new IOException(error); }
        } else version = store.identity(outer);
        return version + suffix + ":notification:" + pictureSourceChanges.get();
    }

    private CachedPicture cachedPicture(String key, String version) {
        synchronized (pictureCacheLock) {
            CachedPicture cached = pictureCache.get(key);
            if (cached == null) return null;
            Bitmap image = cached.picture.bitmap;
            if (cached.version.equals(version) && !image.isRecycled()) return cached;
            pictureCache.remove(key); pictureCacheBytes -= image.getAllocationByteCount();
            if (image != bitmap && !image.isRecycled()) image.recycle();
            return null;
        }
    }
    private boolean cachedBitmap(Bitmap image) {
        for (CachedPicture value : pictureCache.values()) if (value.picture.bitmap == image) return true;
        return false;
    }
    private void preparePictureSource(FileStore.Node selected) throws IOException {
        if (selected.container == null) return;
        FileStore.Node outer = selected.container;
        while (outer.container != null) outer = outer.container;
        observePictureSource(outer);
        String version = pictureVersion(outer);
        boolean changed = !outer.key().equals(galleryArchiveKey) || !version.equals(galleryArchiveVersion);
        if (!changed && galleryReads != null) return;
        closeGalleryReads();
        synchronized (pictureCacheLock) {
            for (CachedPicture cached : pictureCache.values())
                if (cached.picture.bitmap != bitmap && !cached.picture.bitmap.isRecycled()) cached.picture.bitmap.recycle();
            pictureCache.clear(); pictureCacheBytes = 0;
        }
        if (outer.file == null || !outer.file.isFile() || !outer.file.canRead())
            gallerySnapshot = store.new ArchiveSnapshot(outer, null);
        galleryReads = store.new ArchiveReads();
        galleryArchiveKey = outer.key(); galleryArchiveVersion = version;
    }
    private void closeGalleryReads() throws IOException {
        try { if (galleryReads != null) galleryReads.close(); }
        finally {
            galleryReads = null;
            if (gallerySnapshot != null) { gallerySnapshot.close(); gallerySnapshot = null; }
        }
    }
    private void observePictureSource(FileStore.Node outer) {
        if (Objects.equals(observedPictureUri, outer.uri)) return;
        if (pictureObserver != null) getContentResolver().unregisterContentObserver(pictureObserver);
        pictureObserver = null; observedPictureUri = outer.uri;
        if (outer.uri == null) return;
        pictureObserver = new android.database.ContentObserver(new android.os.Handler(android.os.Looper.getMainLooper())) {
            @Override public void onChange(boolean selfChange) { pictureSourceChanges.incrementAndGet(); }
        };
        try { getContentResolver().registerContentObserver(outer.uri, true, pictureObserver); }
        catch (SecurityException ignored) { pictureObserver = null; }
    }
    private static final long PICTURE_CACHE_BUDGET = Math.max(12L << 20,
            Math.min(48L << 20, Runtime.getRuntime().maxMemory() / 5));
    private long pictureCacheBytes;
    private void cachePictureLocked(String key, CachedPicture entry, List<Bitmap> evicted, boolean prefetch) {
        Bitmap candidate = entry.picture.bitmap;
        CachedPicture previous = pictureCache.remove(key);
        Bitmap replaced = previous == null ? null : previous.picture.bitmap;
        if (replaced != null) {
            pictureCacheBytes -= replaced.getAllocationByteCount();
            if (replaced != candidate && replaced != bitmap) evicted.add(replaced);
        }
        int size = candidate.getAllocationByteCount();
        long budget = PICTURE_CACHE_BUDGET - (prefetch && bitmap != null && !cachedBitmap(bitmap)
                ? bitmap.getAllocationByteCount() : 0);
        if (size > budget) return;
        while (pictureCacheBytes + size > budget && !pictureCache.isEmpty()) {
            Iterator<Map.Entry<String, CachedPicture>> values = pictureCache.entrySet().iterator();
            Bitmap eldest = null;
            while (values.hasNext()) {
                Bitmap value = values.next().getValue().picture.bitmap;
                if (prefetch && value == bitmap) continue;
                eldest = value; values.remove(); break;
            }
            if (eldest == null) return;
            pictureCacheBytes -= eldest.getAllocationByteCount();
            if (eldest != candidate && eldest != bitmap) evicted.add(eldest);
        }
        pictureCache.put(key, entry); pictureCacheBytes += size;
    }
    private void prefetchPictures(int token, int current) {
        if (pictureWorker == null || destroyed) return;
        prefetchJobs.entrySet().removeIf(entry -> entry.getValue().isDone());
        for (int delta : new int[]{pagingDirection, -pagingDirection}) {
            int index = current + delta;
            if (index < 0 || index >= pictures.size()) continue;
            FileStore.Node candidate = pictures.get(index);
            if (candidate.size > 64L * 1024 * 1024 || candidate.name.toLowerCase(Locale.ROOT).endsWith(".gif")) continue;
            if (!prefetchJobs.containsKey(candidate.key()))
                prefetchJobs.put(candidate.key(), pictureWorker.submit(() -> prefetchPicture(token, candidate)));
        }
    }
    private boolean usefulPrefetch(int token, FileStore.Node node) {
        return !destroyed && !isFinishing() && (token == pictureGeneration || node.key().equals(requestedPictureKey));
    }
    private void prefetchPicture(int token, FileStore.Node candidate) {
        if (!usefulPrefetch(token, candidate)) return;
        File content = null; Bitmap decoded = null;
        try {
            preparePictureSource(candidate);
            String version = pictureVersion(candidate);
            if (cachedPicture(candidate.key(), version) != null) return;
            content = pictureFile(candidate);
            if (!usefulPrefetch(token, candidate) || Thread.currentThread().isInterrupted()) return;
            if (candidate.name.toLowerCase(Locale.ROOT).endsWith(".gif")) return;
            Picture loaded = decodePicture(content, candidate.name);
            decoded = loaded.bitmap;
            if (decoded == null || !version.equals(pictureVersion(candidate))) return;
            List<Bitmap> evicted = new ArrayList<>();
            synchronized (pictureCacheLock) {
                if (!usefulPrefetch(token, candidate) || destroyed || pictureCache.containsKey(candidate.key())) return;
                long visible = bitmap == null ? 0 : bitmap.getAllocationByteCount();
                if (visible + decoded.getAllocationByteCount() > PICTURE_CACHE_BUDGET) return;
                cachePictureLocked(candidate.key(), new CachedPicture(version, loaded), evicted, true);
                if (cachedBitmap(decoded)) decoded = null;
            }
            for (Bitmap old : evicted) if (!old.isRecycled()) old.recycle();
        } catch (IOException | RuntimeException ignored) {
            // Prefetch is opportunistic; selecting the image still reports the actual read error.
        } finally {
            if (decoded != null && !decoded.isRecycled()) decoded.recycle();
            if (content != null) release(candidate, content);
        }
    }
    private void recycleIfUnused(Bitmap candidate) {
        if (candidate == null) return;
        synchronized (pictureCacheLock) {
            if (candidate == bitmap || cachedBitmap(candidate)) return;
            if (!candidate.isRecycled()) candidate.recycle();
        }
    }
    private void loadThumbnails(int token) {
        if (thumbnailItems == null) return;
        if (!thumbnails || pictureIndex < 0 || destroyed) return;
        int start = Math.max(0, pictureIndex - 3), end = Math.min(pictures.size() - 1, pictureIndex + 3);
        Set<String> visible = new HashSet<>();
        for (int i = start; i <= end; i++) visible.add(pictures.get(i).key());
        thumbnailCache.entrySet().removeIf(entry -> {
            if (visible.contains(entry.getKey())) return false;
            thumbnailItems.removeView(entry.getValue().view); entry.getValue().release(); return true;
        });
        for (int i = start; i <= end; i++) {
            final int index = i; FileStore.Node node = pictures.get(i);
            Thumbnail thumbnail = thumbnailCache.computeIfAbsent(node.key(), key -> {
                ImageView view = new ImageView(this); view.setScaleType(ImageView.ScaleType.CENTER_CROP);
                view.setPadding(Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3));
                return new Thumbnail(view);
            });
            ImageView thumb = thumbnail.view;
            thumb.setBackgroundColor(i == pictureIndex ? Ui.ACCENT : Ui.PANEL); thumb.setFocusable(true);
            thumb.setContentDescription("图片缩略图 " + (i + 1) + " " + node.name);
            thumb.setOnClickListener(v -> { if (index != pictureIndex) showPicture(index); });
            if (thumbnailItems.indexOfChild(thumb) != i - start) {
                thumbnailItems.removeView(thumb);
                thumbnailItems.addView(thumb, i - start, new LinearLayout.LayoutParams(Ui.dp(this, 64), Ui.dp(this, 64)));
            }
            backgroundPictures.add(pictureWorker.submit(() -> {
                File materialized = null; Bitmap decoded = null;
                try {
                    if (!currentPicture(token) || !thumbnails) return;
                    preparePictureSource(node);
                    String version = pictureVersion(node);
                    if (version.equals(thumbnail.version)) return;
                    CachedPicture cached = cachedPicture(node.key(), version);
                    if (cached != null) {
                        synchronized (pictureCacheLock) {
                            Bitmap original = cached.picture.bitmap;
                            if (!original.isRecycled()) {
                                float ratio = Math.min(1f, 128f / Math.max(original.getWidth(), original.getHeight()));
                                decoded = Bitmap.createScaledBitmap(original, Math.max(1, (int)(original.getWidth()*ratio)), Math.max(1, (int)(original.getHeight()*ratio)), true);
                                if (decoded == original) decoded = original.copy(Bitmap.Config.ARGB_8888, false);
                            }
                        }
                    }
                    if (decoded == null) {
                        materialized = pictureFile(node);
                        Tasks.Progress.check();
                        decoded = decodeThumbnail(materialized);
                    }
                    if (decoded == null) return;
                    if (!version.equals(pictureVersion(node))) return;
                    Bitmap result = decoded; decoded = null;
                    runOnUiThread(() -> {
                        if (!currentPicture(token) || !thumbnails || thumb.getParent() != thumbnailItems) { result.recycle(); return; }
                        Bitmap old = thumbnail.bitmap;
                        thumbnail.bitmap = result; thumb.setImageBitmap(result); thumbnail.version = version;
                        if (old != null && !old.isRecycled()) old.recycle();
                    });
                } catch (IOException | RuntimeException ignored) { }
                finally {
                    if (decoded != null) decoded.recycle();
                    if (materialized != null) release(node, materialized);
                }
            }));
        }
    }
    private void document(String kind) {
        Tasks.run(this, () -> {
            try (InputStream in = new FileInputStream(file)) {
                byte[] bytes = FileStore.readBytes(in, 8 * 1024 * 1024 + 1);
                if (bytes.length > 8 * 1024 * 1024) throw new IOException("预览文件超过 8 MiB，请使用文本编辑器");
                return TextCodec.decode(bytes, null).text();
            }
        }, text -> {
            status.setText(source.name); web = new WebView(this); web.setContentDescription("文档预览");
            web.getSettings().setJavaScriptEnabled(false); web.getSettings().setAllowFileAccess(false);
            web.getSettings().setAllowContentAccess(false); web.getSettings().setBlockNetworkLoads(true);
            web.getSettings().setBuiltInZoomControls(true); web.getSettings().setDisplayZoomControls(false);
            web.setWebViewClient(new android.webkit.WebViewClient() {
                @Override public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) { return true; }
            });
            body.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
            if (kind.equals("svg")) {
                String encoded = android.util.Base64.encodeToString(text.getBytes(java.nio.charset.StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
                web.loadData("<html><body style='margin:0'><img style='width:100%;height:90vh;object-fit:contain' src='data:image/svg+xml;base64," + encoded + "'></body></html>", "text/html", "UTF-8");
            } else web.loadDataWithBaseURL(null, text, "text/html", "UTF-8", null);
        }, error -> status.setText("无法预览：" + error.getMessage()));
    }
    private void media(boolean audio) {
        FrameLayout stage = new FrameLayout(this); stage.setBackgroundColor(Color.BLACK);
        player = new VideoView(this); player.setContentDescription(audio ? "音频播放器" : "视频播放器");
        // SurfaceView needs its transparent window hole; a View background would cover decoded frames.
        stage.addView(player, new FrameLayout.LayoutParams(-1, -1, android.view.Gravity.CENTER));
        body.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));
        MediaController controls = new MediaController(this); controls.setAnchorView(player); player.setMediaController(controls);
        player.setOnPreparedListener(media -> {
            mediaPrepared = true; status.setText(source.name); controls.show(0);
            if (mediaPosition > 0) player.seekTo(mediaPosition);
            if (activityResumed && resumeVideoOnResume) player.start();
        });
        player.setOnCompletionListener(media -> { resumeVideoOnResume = false; mediaPosition = 0; });
        player.setOnErrorListener((media, what, extra) -> {
            mediaPrepared = false; resumeVideoOnResume = false;
            status.setText("系统无法解码此媒体（" + what + ", " + extra + "）"); return true;
        });
        player.setVideoURI(Uri.fromFile(file));
    }
    @Override protected void onPause() {
        activityResumed = false;
        if (player != null && mediaPrepared) {
            resumeVideoOnResume = player.isPlaying(); mediaPosition = player.getCurrentPosition(); player.pause();
        }
        super.onPause();
        if (imageView != null && imageView.getDrawable() instanceof Animatable animation) animation.stop();
    }
    @Override protected void onResume() {
        super.onResume(); activityResumed = true;
        if (player != null && mediaPrepared && resumeVideoOnResume) player.start();
        if (imageView != null && imageView.getDrawable() instanceof Animatable animation) animation.start();
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (imageView != null && source != null) out.putString("pictureKey", source.key());
        if (player != null) {
            out.putInt("mediaPosition", activityResumed && mediaPrepared ? player.getCurrentPosition() : mediaPosition);
            out.putBoolean("mediaPlaying", activityResumed && mediaPrepared ? player.isPlaying() : resumeVideoOnResume);
        }
    }
    @Override protected void onDestroy() {
        destroyed = true; pictureGeneration++;
        if (detailJob != null) detailJob.cancel(true);
        for (Future<?> job : prefetchJobs.values()) job.cancel(true);
        prefetchJobs.clear();
        if (pictureJob != null) pictureJob.cancel(true);
        for (Future<?> pending : backgroundPictures) pending.cancel(true);
        backgroundPictures.clear();
        if (pictureWorker != null) {
            if (pictureWorker instanceof ThreadPoolExecutor pool) pool.purge();
            pictureWorker.execute(() -> {
                try { closeGalleryReads(); } catch (IOException ignored) { }
                if (pictureObserver != null) { getContentResolver().unregisterContentObserver(pictureObserver); pictureObserver = null; }
            });
            pictureWorker.shutdown();
        }
        if (thumbnailItems != null) thumbnailItems.removeAllViews();
        for (Thumbnail thumb : thumbnailCache.values()) thumb.release();
        thumbnailCache.clear();
        if (animationFile != null) {
            File ownedAnimation = animationFile; FileStore.Node ownedSource = animationSource;
            animationFile = null; Tasks.IO.execute(() -> release(ownedSource, ownedAnimation));
        }
        synchronized (pictureCacheLock) {
            Set<Bitmap> owned = Collections.newSetFromMap(new IdentityHashMap<>());
            for (CachedPicture cached : pictureCache.values()) owned.add(cached.picture.bitmap);
            if (bitmap != null) owned.add(bitmap);
            pictureCache.clear(); bitmap = null;
            pictureCacheBytes = 0;
            for (Bitmap cached : owned) if (!cached.isRecycled()) cached.recycle();
        }
        if (player != null) player.stopPlayback();
        if (imageView != null && imageView.getDrawable() instanceof Animatable animation) animation.stop();
        if (web != null) web.destroy();
        if (imageView != null) imageView.setImageBitmap(null);
        FileStore.Node owned; File staged;
        synchronized (this) { destroyed = true; owned = source; staged = file; }
        if (owned != null && staged != null) Tasks.IO.execute(() -> release(owned, staged));
        super.onDestroy();
    }
    private void release(FileStore.Node node, File content) {
        try { store.release(node, content); } catch (IOException ignored) { }
    }
}
