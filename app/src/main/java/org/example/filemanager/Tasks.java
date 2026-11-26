package org.example.filemanager;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.Toast;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

final class Tasks {
    static final ExecutorService IO = Executors.newFixedThreadPool(3);
    static final ExecutorService FILES = Executors.newSingleThreadExecutor();
    static final ExecutorService BROWSE = Executors.newFixedThreadPool(2);
    static final ExecutorService FILTER = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "directory-filter"); thread.setDaemon(true); return thread;
    });
    static final ExecutorService SHELL = Executors.newCachedThreadPool();
    static final ExecutorService CONTROL = Executors.newCachedThreadPool();
    static final ExecutorService INTEGRITY = Executors.newFixedThreadPool(2);
    /** Mutation state survives an Activity recreation while the worker continues. */
    record Transfer(java.util.List<FileStore.Node> sources, FileStore.Node destination,
                    boolean sourceLeft, boolean targetLeft, boolean move) {
        Transfer { sources = java.util.List.copyOf(sources); }
    }
    static final class Mutation {
        final Progress progress = new Progress();
        final java.lang.ref.WeakReference<Activity> owner;
        volatile boolean finished;
        volatile Object result;
        volatile Exception error;
        Transfer transfer;
        boolean showProgress;
        Mutation(Activity owner) { this.owner = new java.lang.ref.WeakReference<>(owner); }
    }
    private static volatile Mutation activeMutation;
    static Mutation activeMutation() { return activeMutation; }
    static Mutation beginMutation(Activity owner) { Mutation value = new Mutation(owner); activeMutation = value; return value; }
    static void finishMutation(Mutation value, Object result, Exception error) {
        value.result = result; value.error = error; value.finished = true;
    }
    static void clearMutation(Mutation value) { if (activeMutation == value) activeMutation = null; }
    interface Job<T> { T run() throws Exception; }
    interface Done<T> { void accept(T value); }
    static <T> void run(Activity a, Job<T> work, Done<T> done) {
        run(a, work, done, e -> showError(a, e));
    }
    static <T> void run(Activity a, Job<T> work, Done<T> done, Done<Exception> failed) {
        run(a, IO, work, done, failed);
    }
    static <T> void run(Activity a, Executor executor, Job<T> work, Done<T> done, Done<Exception> failed) {
        executor.execute(() -> {
            try {
                T value = work.run();
                a.runOnUiThread(() -> { if (!a.isFinishing() && !a.isDestroyed()) done.accept(value); });
            } catch (Exception e) {
                a.runOnUiThread(() -> { if (!a.isFinishing() && !a.isDestroyed()) failed.accept(e); });
            }
        });
    }
    static void showError(Activity a, Exception e) {
        SafRecovery.RecoveryException recovery = SafRecovery.findRecovery(e);
        if (recovery != null && SafRecovery.enabled(a)) { SafRecovery.showRecovery(a, recovery); return; }
        Throwable shown = recovery != null && recovery.getCause() != null ? recovery.getCause() : e;
        MaterialDialogs.builder(a).setTitle("操作失败")
                .setMessage(shown.getMessage() == null ? shown.getClass().getSimpleName() : shown.getMessage())
                .setPositiveButton("确定", null).show();
    }
    static void toast(Activity a, String text) { Toast.makeText(a, text, Toast.LENGTH_SHORT).show(); }
    /** Raised at an item boundary after the user stops a long operation. */
    static final class CancelledException extends java.io.IOException {
        CancelledException() { super("已取消"); }
    }
    static final class PartialTransferException extends java.io.IOException {
        final java.util.List<FileStore.Node> completed;
        final java.util.List<FileStore.Node> remaining;
        final java.util.List<FileStore.Node> published;
        final FileStore.ArchiveMoveRecovery archiveMove;
        PartialTransferException(java.util.List<FileStore.Node> completed, java.util.List<FileStore.Node> remaining, Exception cause) {
            super("已成功 " + completed.size() + " 项，剩余 " + remaining.size() + " 项未完成：" + cause.getMessage(), cause);
            this.completed = java.util.List.copyOf(completed);
            this.remaining = java.util.List.copyOf(remaining);
            this.published = this.completed;
            this.archiveMove = null;
        }
        PartialTransferException(FileStore.ArchiveMoveRecovery recovery, Exception cause) {
            super("已复制 " + recovery.published.size() + " 项，来源尚未删除，移动未完成：" + cause.getMessage(), cause);
            this.completed = java.util.List.of();
            this.remaining = recovery.sources;
            this.published = java.util.List.copyOf(recovery.published.values());
            this.archiveMove = recovery;
        }
    }
    /** Item-level progress for one worker thread; file loops call {@link #step} once per entry they handle. */
    static final class Progress {
        private static final ThreadLocal<Progress> CURRENT = new ThreadLocal<>();
        final java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        final java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicLong transferred = new java.util.concurrent.atomic.AtomicLong();
        volatile String current = "";
        volatile String phase = "准备";
        volatile boolean finished;
        private boolean planned, unknownSize;
        private final java.util.Set<String> pendingDirectories = new java.util.HashSet<>();
        private long expected, segmentStart, startedAt = android.os.SystemClock.elapsedRealtime();
        private long sampleAt = startedAt, sampleBytes, lastByteAt = startedAt;
        private double rate;
        private String samplePhase = "准备";
        record Snapshot(String phase, String current, long bytes, long total, int percent,
                        double bytesPerSecond, long remainingSeconds, boolean cancelled, boolean finished) { }
        /** Uses browser metadata; directories are discovered during the existing copy walk. */
        static void plan(java.util.Collection<FileStore.Node> sources, String label) {
            Progress progress = CURRENT.get();
            if (progress != null) progress.planSources(sources, label, false);
        }
        static void replan(java.util.Collection<FileStore.Node> sources, String label) {
            Progress progress = CURRENT.get();
            if (progress != null) progress.planSources(sources, label, true);
        }
        private synchronized void planSources(java.util.Collection<FileStore.Node> sources, String label, boolean reset) {
            if (planned && !reset) return;
            planned = true; expected = 0; unknownSize = false; pendingDirectories.clear();
            segmentStart = transferred.get(); phase = label;
            for (FileStore.Node source : sources) account(source);
            resetRate();
        }
        private void account(FileStore.Node source) {
            if (source.directory) pendingDirectories.add(directoryKey(source));
            else if (source.size < 0 || expected > Long.MAX_VALUE - source.size) unknownSize = true;
            else expected += source.size;
        }
        private static String directoryKey(FileStore.Node node) {
            return node.container == null && node.file != null ? node.file.getAbsolutePath() : node.key();
        }
        static void discovered(FileStore.Node source, java.util.List<FileStore.Node> children) {
            Progress progress = CURRENT.get();
            if (progress == null) return;
            synchronized (progress) {
                if (!progress.pendingDirectories.remove(directoryKey(source))) return;
                for (FileStore.Node child : children) progress.account(child);
            }
        }
        static void budget(String label, long bytes) {
            Progress progress = CURRENT.get();
            if (progress == null) return;
            synchronized (progress) {
                progress.planned = true; progress.expected = Math.max(0, bytes);
                progress.unknownSize = bytes < 0; progress.pendingDirectories.clear();
                progress.segmentStart = progress.transferred.get(); progress.phase = label; progress.resetRate();
            }
        }
        private void resetRate() {
            startedAt = sampleAt = lastByteAt = android.os.SystemClock.elapsedRealtime();
            sampleBytes = Math.max(0, transferred.get() - segmentStart); rate = 0; samplePhase = phase;
        }
        synchronized Snapshot snapshot(long now) {
            long bytes = Math.max(0, transferred.get() - segmentStart);
            long elapsed = Math.max(0, now - sampleAt);
            if (!java.util.Objects.equals(samplePhase, phase)) {
                samplePhase = phase; startedAt = sampleAt = now; sampleBytes = bytes; lastByteAt = now; rate = 0; elapsed = 0;
            }
            if (elapsed >= 200) {
                long delta = Math.max(0, bytes - sampleBytes);
                if (delta > 0) {
                    double fresh = delta * 1000d / elapsed;
                    rate = rate == 0 ? fresh : rate * .65d + fresh * .35d;
                    lastByteAt = now;
                }
                sampleAt = now; sampleBytes = bytes;
            }
            boolean stalled = now - lastByteAt >= 2000;
            if (stalled) rate = 0;
            boolean measuring = phase.contains("复制") || phase.contains("传输") || phase.contains("解压") || phase.contains("写入") || phase.equals("压缩文件");
            long total = planned && !unknownSize && pendingDirectories.isEmpty() && measuring && bytes <= expected ? expected : -1;
            int percent = total > 0 ? (int)Math.min(99, bytes * 100d / total) : -1;
            double speed = stalled || !measuring ? 0 : rate;
            long remaining = total > bytes && speed > 0 && now - startedAt >= 750 && !cancelled.get()
                    ? Math.max(1, (long)Math.ceil((total - bytes) / speed)) : -1;
            return new Snapshot(phase, current, bytes, total, percent, speed, remaining, cancelled.get(), finished);
        }
        Snapshot snapshot() { return snapshot(android.os.SystemClock.elapsedRealtime()); }
        static void phase(String value) {
            Progress progress = CURRENT.get();
            if (progress != null) progress.phase = value;
        }
        static Phase stage(String value) { return new Phase(value); }
        static final class Phase implements AutoCloseable {
            private final Progress progress = CURRENT.get();
            private final String previous;
            private final long previousExpected, previousStart, stageStart;
            private final boolean previousPlanned, previousUnknown;
            private final java.util.Set<String> previousDirectories;
            private Phase(String value) {
                if (progress == null) {
                    previous = ""; previousExpected = previousStart = stageStart = 0;
                    previousPlanned = previousUnknown = false; previousDirectories = java.util.Set.of(); return;
                }
                synchronized (progress) {
                    previous = progress.phase; previousExpected = progress.expected; previousStart = progress.segmentStart;
                    previousPlanned = progress.planned; previousUnknown = progress.unknownSize;
                    previousDirectories = new java.util.HashSet<>(progress.pendingDirectories);
                    stageStart = progress.transferred.get();
                    progress.segmentStart = stageStart; progress.expected = 0; progress.planned = true;
                    progress.unknownSize = true; progress.pendingDirectories.clear(); progress.phase = value; progress.resetRate();
                }
            }
            public void close() {
                if (progress == null) return;
                synchronized (progress) {
                    // Preparation/rewrite bytes have a different denominator from the copied members.
                    progress.segmentStart = previousStart + Math.max(0, progress.transferred.get() - stageStart);
                    progress.expected = previousExpected; progress.planned = previousPlanned; progress.unknownSize = previousUnknown;
                    progress.pendingDirectories.clear(); progress.pendingDirectories.addAll(previousDirectories);
                    progress.phase = previous; progress.resetRate();
                }
            }
        }
        static void bytes(long count) {
            Progress progress = CURRENT.get();
            if (progress != null && count > 0) progress.transferred.addAndGet(count);
        }
        static Runnable bind(Runnable work) {
            Progress inherited = CURRENT.get();
            return () -> {
                Progress old = CURRENT.get();
                if (inherited != null) CURRENT.set(inherited); else CURRENT.remove();
                try { work.run(); } finally { if (old != null) CURRENT.set(old); else CURRENT.remove(); }
            };
        }
        static void check() throws CancelledException {
            if (Thread.currentThread().isInterrupted()) throw new CancelledException();
            Progress progress = CURRENT.get();
            if (progress != null && progress.cancelled.get()) throw new CancelledException();
        }
        static void step(String name) throws CancelledException {
            check();
            Progress progress = CURRENT.get();
            if (progress == null) return;
            progress.current = name == null ? "" : name; progress.done.incrementAndGet();
        }
        interface Cleanup { void run() throws java.io.IOException; }
        static void cleanup(Cleanup work) throws java.io.IOException {
            Progress previous = CURRENT.get(); CURRENT.remove();
            boolean interrupted = Thread.interrupted();
            try { work.run(); }
            finally { if (previous != null) CURRENT.set(previous); if (interrupted) Thread.currentThread().interrupt(); }
        }
        <T> T run(Job<T> job) throws Exception {
            Progress previous = CURRENT.get(); CURRENT.set(this);
            try { return job.run(); } finally { finished = true; if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
        }
    }
    private Tasks() {}
}
