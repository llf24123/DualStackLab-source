package org.example.filemanager;

import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import rikka.shizuku.Shizuku;

final class ShizukuStorage {
    private static final Object LOCK = new Object();
    private static Binding binding;
    static {
        Shizuku.addBinderDeadListener(ShizukuStorage::disconnected);
    }
    static boolean running() { return Shizuku.pingBinder(); }
    static boolean granted() {
        try { return running() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED; }
        catch (RuntimeException unavailable) { return false; }
    }
    private static void disconnected() {
        synchronized (LOCK) { if (binding != null) invalidate(binding, "Shizuku 服务已断开"); }
    }
    private static void invalidate(Binding attempt, String reason) {
        synchronized (LOCK) {
            if (binding != attempt) return;
            binding = null; attempt.error = reason; attempt.ready.countDown();
        }
        new Handler(Looper.getMainLooper()).post(() -> {
            try { Shizuku.unbindUserService(attempt.arguments, attempt, false); }
            catch (RuntimeException disconnected) { }
        });
    }
    private static final class Binding implements ServiceConnection {
        final CountDownLatch ready = new CountDownLatch(1);
        final Shizuku.UserServiceArgs arguments;
        IDataAccess service;
        String error;
        Binding(Context context) {
            arguments = new Shizuku.UserServiceArgs(new ComponentName(context, ShizukuDataService.class))
                    .daemon(false).processNameSuffix("android_data").tag("android-data").version(2);
        }
        void start() {
            synchronized (LOCK) { if (binding != this) return; }
            try { Shizuku.bindUserService(arguments, this); }
            catch (RuntimeException error) { invalidate(this, error.getMessage()); }
        }
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            synchronized (LOCK) {
                if (binding != this) return;
                service = IDataAccess.Stub.asInterface(binder); ready.countDown();
            }
        }
        @Override public void onServiceDisconnected(ComponentName name) { invalidate(this, "Shizuku 文件服务已断开"); }
        @Override public void onNullBinding(ComponentName name) { invalidate(this, "Shizuku 未返回文件接口"); }
        @Override public void onBindingDied(ComponentName name) { invalidate(this, "Shizuku 文件服务已断开"); }
    }
    static IDataAccess remote(Context context) throws IOException {
        return remote(context, 8000);
    }
    static IDataAccess remote(Context context, long timeoutMillis) throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IOException("目录访问必须在后台执行");
        if (!granted()) throw new IOException("请启动 Shizuku 并授权双栈 Lab");
        Binding attempt;
        synchronized (LOCK) {
            if (binding != null && binding.service != null && !binding.service.asBinder().isBinderAlive())
                invalidate(binding, "Shizuku 文件服务已断开");
            if (binding == null) {
                binding = new Binding(context.getApplicationContext());
                new Handler(Looper.getMainLooper()).post(binding::start);
            }
            attempt = binding;
        }
        try {
            if (!attempt.ready.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
                invalidate(attempt, "Shizuku 文件服务连接超时"); throw new IOException("Shizuku 文件服务连接超时，请重试");
            }
        } catch (InterruptedException interrupted) {
            invalidate(attempt, "目录读取已中断"); Thread.currentThread().interrupt(); throw new IOException("目录读取已中断", interrupted);
        }
        synchronized (LOCK) {
            if (binding != attempt || attempt.service == null || !attempt.service.asBinder().isBinderAlive())
                throw new IOException(attempt.error == null ? "Shizuku 文件服务不可用" : attempt.error);
            return attempt.service;
        }
    }
    private ShizukuStorage() { }
}
