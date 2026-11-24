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
        static void phase(String value) {
            Progress progress = CURRENT.get();
            if (progress != null) progress.phase = value;
        }
        static Phase stage(String value) { return new Phase(value); }
        static final class Phase implements AutoCloseable {
            private final Progress progress = CURRENT.get();
            private final String previous;
            private Phase(String value) {
                previous = progress == null ? "" : progress.phase;
                phase(value);
            }
            public void close() { if (progress != null) progress.phase = previous; }
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
            CURRENT.set(this);
            try { return job.run(); } finally { CURRENT.remove(); }
        }
    }
    private Tasks() {}
}
