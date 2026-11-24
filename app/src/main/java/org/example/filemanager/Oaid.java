package org.example.filemanager;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Vendor OAID protocols verified against Android_CN_OAID (Mulan PSL v2).
 * https://github.com/gzu-liyujiang/Android_CN_OAID/tree/master/aidl
 * Copyright (c) 2016-present. 贵州纳雍穿青人李裕江 and All Contributors.
 * Protocol adapters rewritten for bounded background inventory. License in assets/licenses.
 */
final class Oaid {
    record Result(String value, String source, String status) { }
    record Service(String packageName, String className, String action, String descriptor,
                   int idCode, int supportCode, int limitCode, boolean signed) {
        Intent intent() {
            Intent intent = new Intent(action).setPackage(packageName);
            if (className != null) intent.setComponent(new ComponentName(packageName, className));
            return intent;
        }
    }
    private static final Service[] SERVICES = {
            new Service("com.heytap.openid", "com.heytap.openid.IdentifyService", "action.com.heytap.openid.OPEN_ID_SERVICE",
                    "com.heytap.openid.IOpenID", 1, 0, 0, true),
            new Service("com.coloros.mcs", "com.oplus.stdid.IdentifyService", "action.com.oplus.stdid.ID_SERVICE",
                    "com.oplus.stdid.IStdID", 1, 0, 0, true),
            new Service("com.zui.deviceidservice", "com.zui.deviceidservice.DeviceidService", null,
                    "com.zui.deviceidservice.IDeviceidInterface", 1, 3, 0, false),
            new Service("com.samsung.android.deviceidservice", "com.samsung.android.deviceidservice.DeviceIdService", null,
                    "com.samsung.android.deviceidservice.IDeviceIdService", 1, 0, 0, false),
            new Service("com.asus.msa.SupplementaryDID", "com.asus.msa.SupplementaryDID.SupplementaryDIDService", "com.asus.msa.action.ACCESS_DID",
                    "com.asus.msa.SupplementaryDID.IDidAidlInterface", 3, 1, 0, false),
            new Service("com.huawei.hwid", null, "com.uodis.opendevice.OPENIDS_SERVICE",
                    "com.uodis.opendevice.aidl.OpenDeviceIdentifierService", 1, 0, 2, false),
            new Service("com.huawei.hwid.tv", null, "com.uodis.opendevice.OPENIDS_SERVICE",
                    "com.uodis.opendevice.aidl.OpenDeviceIdentifierService", 1, 0, 2, false),
            new Service("com.huawei.hms", null, "com.uodis.opendevice.OPENIDS_SERVICE",
                    "com.uodis.opendevice.aidl.OpenDeviceIdentifierService", 1, 0, 2, false)
    };
    // A stalled vendor transaction occupies at most one daemon. Refresh cannot spawn unlimited workers.
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1), work -> { Thread thread = new Thread(work, "device-oaid"); thread.setDaemon(true); return thread; });

    static Result read(Context context) {
        if (Looper.myLooper() == Looper.getMainLooper()) return new Result(null, "", "请在后台读取");
        Lookup lookup = new Lookup(context.getApplicationContext());
        Future<Result> result;
        try { result = WORKER.submit(lookup::read); }
        catch (RejectedExecutionException busy) { return new Result(null, "", "厂商接口忙，请稍后刷新"); }
        try { return result.get(4, TimeUnit.SECONDS); }
        catch (TimeoutException timeout) { return new Result(null, "", "厂商接口读取超时"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return new Result(null, "", "读取已中断"); }
        catch (ExecutionException error) { return new Result(null, "", "不可读取（" + error.getCause().getClass().getSimpleName() + "）"); }
        finally { result.cancel(true); lookup.close(); WORKER.purge(); }
    }

    private static final class Lookup implements AutoCloseable {
        final Context context;
        final AtomicBoolean closed = new AtomicBoolean();
        final AtomicReference<Connection> active = new AtomicReference<>();
        Lookup(Context context) { this.context = context; }
        @android.annotation.SuppressLint("PrivateApi") // Optional Xiaomi extension; absence and access denial are handled.
        Result read() throws Exception {
            String failure = "厂商未提供可读取的 OAID 接口";
            for (String[] provider : new String[][]{
                    {"content://com.vivo.vms.IdProvider/IdentifierId/OAID", null},
                    {"content://com.meizu.flyme.openidsdk/", "oaid"}}) {
                if (closed.get()) throw new InterruptedException();
                Uri uri = Uri.parse(provider[0]);
                if (context.getPackageManager().resolveContentProvider(uri.getAuthority(), 0) == null) continue;
                try (Cursor cursor = context.getContentResolver().query(uri, null, null,
                        provider[1] == null ? null : new String[]{provider[1]}, null)) {
                    String value = null;
                    if (cursor != null && cursor.moveToFirst())
                        value = normalize(cursor.getString(cursor.getColumnIndexOrThrow("value")));
                    if (value != null) return new Result(value, uri.getAuthority(), "已读取");
                    failure = "厂商接口未返回有效 OAID，或广告标识已关闭";
                } catch (Exception error) { failure = "厂商接口不可读取（" + error.getClass().getSimpleName() + "）"; }
            }
            try {
                Class<?> provider = Class.forName("com.android.id.impl.IdProviderImpl");
                Object implementation = provider.getDeclaredConstructor().newInstance();
                String value = normalize((String) provider.getMethod("getOAID", Context.class).invoke(implementation, context));
                if (value != null) return new Result(value, "小米 IdProvider", "已读取");
            } catch (ClassNotFoundException absent) { /* This vendor extension is optional. */ }
            catch (ReflectiveOperationException | RuntimeException error) { failure = "小米接口不可读取（" + error.getClass().getSimpleName() + "）"; }

            for (Service service : SERVICES) {
                if (closed.get()) throw new InterruptedException();
                if (context.getPackageManager().resolveService(service.intent(), 0) == null) continue;
                Connection connection = new Connection(context);
                active.set(connection);
                if (closed.get()) { connection.close(); throw new InterruptedException(); }
                try {
                    IBinder binder = connection.connect(service.intent(), 1500);
                    Result value = query(context, binder, service);
                    if (value.value() != null || value.status().equals("系统已限制广告标识读取")) return value;
                    failure = value.status();
                } catch (Exception error) { failure = "厂商接口不可读取（" + error.getClass().getSimpleName() + "）"; }
                finally { connection.close(); active.compareAndSet(connection, null); }
            }
            return new Result(null, "", failure);
        }
        public void close() {
            closed.set(true);
            Connection connection = active.get();
            if (connection != null) connection.close();
        }
    }

    static Result query(Context context, IBinder binder, Service service) throws Exception {
        if (!service.descriptor().equals(binder.getInterfaceDescriptor())) throw new IOException("厂商接口类型不匹配");
        if (service.supportCode() != 0 && !booleanCall(binder, service, service.supportCode()))
            return new Result(null, service.packageName(), "厂商接口不支持 OAID");
        if (service.limitCode() != 0 && booleanCall(binder, service, service.limitCode()))
            return new Result(null, service.packageName(), "系统已限制广告标识读取");
        Parcel request = Parcel.obtain(), response = Parcel.obtain();
        try {
            request.writeInterfaceToken(service.descriptor());
            if (service.signed()) {
                request.writeString(context.getPackageName());
                request.writeString(signature(context));
                request.writeString("OUID");
            }
            if (!binder.transact(service.idCode(), request, response, 0)) throw new IOException("厂商接口未实现 OAID 读取");
            response.readException();
            String value = normalize(response.readString());
            return new Result(value, service.packageName(), value == null ? "厂商接口未返回有效 OAID，或广告标识已关闭" : "已读取");
        } finally { request.recycle(); response.recycle(); }
    }
    private static boolean booleanCall(IBinder binder, Service service, int code) throws IOException, RemoteException {
        Parcel request = Parcel.obtain(), response = Parcel.obtain();
        try {
            request.writeInterfaceToken(service.descriptor());
            if (!binder.transact(code, request, response, 0)) throw new IOException("厂商接口未实现状态读取");
            response.readException(); return response.readInt() != 0;
        } finally { request.recycle(); response.recycle(); }
    }
    @SuppressWarnings("deprecation")
    private static String signature(Context context) throws Exception {
        android.content.pm.PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(),
                android.os.Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES);
        android.content.pm.Signature[] certificates = android.os.Build.VERSION.SDK_INT >= 28 && info.signingInfo != null
                ? info.signingInfo.getApkContentsSigners() : info.signatures;
        if (certificates == null || certificates.length == 0) throw new IOException("应用签名不可读取");
        return DeviceInfo.hex(MessageDigest.getInstance("SHA-1").digest(certificates[0].toByteArray()));
    }
    static String normalize(String value) {
        if (value == null) return null;
        String text = value.trim();
        if (!text.matches("[A-Za-z0-9_-]{8,256}") || text.equalsIgnoreCase("unknown") || text.equalsIgnoreCase("null")
                || text.replace("-", "").replace("_", "").matches("0*")) return null;
        return text;
    }

    static final class Connection implements ServiceConnection, AutoCloseable {
        private final Context context;
        private final CountDownLatch ready = new CountDownLatch(1);
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean released = new AtomicBoolean();
        private volatile boolean attempted, bindingFinished;
        private volatile IBinder binder;
        Connection(Context context) { this.context = context; }
        IBinder connect(Intent intent, long timeoutMillis) throws Exception {
            if (closed.get()) throw new IOException("读取已结束");
            boolean bound;
            attempted = true;
            try { bound = context.bindService(intent, this, Context.BIND_AUTO_CREATE); }
            finally {
                bindingFinished = true;
                if (closed.get()) closeBinding();
            }
            if (closed.get()) throw new IOException("读取已结束");
            if (!bound) throw new IOException("厂商服务无法绑定");
            if (!ready.await(timeoutMillis, TimeUnit.MILLISECONDS)) throw new TimeoutException("厂商服务连接超时");
            if (closed.get() || binder == null) throw new IOException("厂商服务未返回接口");
            return binder;
        }
        public void onServiceConnected(ComponentName name, IBinder binder) { if (!closed.get()) this.binder = binder; ready.countDown(); }
        public void onServiceDisconnected(ComponentName name) { binder = null; ready.countDown(); }
        public void onNullBinding(ComponentName name) { ready.countDown(); }
        public void onBindingDied(ComponentName name) { binder = null; ready.countDown(); }
        public void close() { closed.set(true); ready.countDown(); closeBinding(); }
        private void closeBinding() {
            // Android registers the dispatcher even when bindService returns false or denies access.
            // A close during bind is completed by connect's finally block after registration finishes.
            if (attempted && bindingFinished && released.compareAndSet(false, true)) try { context.unbindService(this); }
            catch (IllegalArgumentException ignored) { }
        }
    }
}
