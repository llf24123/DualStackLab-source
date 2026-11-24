package org.example.filemanager;

import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class DeviceIdentifiersInstrumentedTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private static final String DESCRIPTOR = "com.zui.deviceidservice.IDeviceidInterface";
    private static final String VALUE = "abcd1234-1234-5678-9012-abcdef123456";
    private static final String UFS_PATH = "/sys/bus/platform/devices/1d84000.ufshc/string_descriptors/serial_number";

    @Test public void ufsSerialRequiresARealSerialNodeAndValue() {
        DeviceInfo.UfsSerial serial = DeviceInfo.parseUfsSerial("cat: Permission denied\n" + UFS_PATH + "\tSAMSUNG123456\n");
        assertNotNull(serial); assertEquals("SAMSUNG123456", serial.value()); assertEquals(UFS_PATH, serial.path());
        assertNull(DeviceInfo.parseUfsSerial("/sys/class/block/mmcblk0/device/cid\tABCD1234\n"));
        assertNull(DeviceInfo.parseUfsSerial(UFS_PATH + "\t000000000000\n"));
        assertNull(DeviceInfo.parseUfsSerial(UFS_PATH + "\tunknown\n"));
        assertNull(DeviceInfo.parseUfsSerial(UFS_PATH + "\tBAD\u0000VALUE\n"));
        serial = DeviceInfo.parseUfsSerial(UFS_PATH + "\tunknown\n" + UFS_PATH + "\tACTUAL-SERIAL\n");
        assertNotNull(serial); assertEquals("ACTUAL-SERIAL", serial.value());
    }

    @Test public void unavailableOaidNeverProducesAnIdentifier() {
        for (String value : new String[]{"", "unknown", "null", "00000000-0000-0000-0000-000000000000", "--------", "bad\nvalue"})
            assertNull(value, Oaid.normalize(value));
        assertNull(Oaid.normalize(null)); assertEquals(VALUE, Oaid.normalize(" " + VALUE + " "));
    }

    @Test public void binderChecksSupportAndReadsOnlyOaid() throws Exception {
        AtomicInteger queries = new AtomicInteger();
        Binder service = new Binder() {
            { attachInterface(null, DESCRIPTOR); }
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
                data.enforceInterface(DESCRIPTOR); reply.writeNoException();
                if (code == 3) reply.writeInt(1);
                else { assertEquals(1, code); queries.incrementAndGet(); reply.writeString(VALUE); }
                return true;
            }
        };
        Oaid.Result result = Oaid.query(context, service, lenovo());
        assertEquals(VALUE, result.value()); assertEquals(1, queries.get());
        assertEquals("com.zui.deviceidservice", result.source());
    }

    @Test public void disabledAdvertisingIdentifierDoesNotQueryTheId() throws Exception {
        String descriptor = "com.uodis.opendevice.aidl.OpenDeviceIdentifierService";
        Binder service = new Binder() {
            { attachInterface(null, descriptor); }
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
                data.enforceInterface(descriptor); assertEquals(2, code);
                reply.writeNoException(); reply.writeInt(1); return true;
            }
        };
        Oaid.Result result = Oaid.query(context, service,
                new Oaid.Service("com.huawei.hwid", null, "test", descriptor, 1, 0, 2, false));
        assertNull(result.value()); assertEquals("系统已限制广告标识读取", result.status());
    }

    @Test public void oppoUsesTheCallingPackageAndCertificateForOuid() throws Exception {
        String descriptor = "com.oplus.stdid.IStdID";
        Binder service = new Binder() {
            { attachInterface(null, descriptor); }
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
                data.enforceInterface(descriptor); assertEquals(1, code);
                assertEquals(context.getPackageName(), data.readString());
                String signature = data.readString(); assertNotNull(signature); assertTrue(signature.matches("[0-9a-f]{40}"));
                assertEquals("OUID", data.readString());
                reply.writeNoException(); reply.writeString(VALUE); return true;
            }
        };
        Oaid.Result result = Oaid.query(context, service,
                new Oaid.Service("com.coloros.mcs", "com.oplus.stdid.IdentifyService", null, descriptor, 1, 0, 0, true));
        assertEquals(VALUE, result.value());
    }

    @Test public void unsupportedAndWrongInterfacesDoNotSupplyAnId() throws Exception {
        Binder unsupported = new Binder() {
            { attachInterface(null, DESCRIPTOR); }
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
                data.enforceInterface(DESCRIPTOR); assertEquals(3, code);
                reply.writeNoException(); reply.writeInt(0); return true;
            }
        };
        assertNull(Oaid.query(context, unsupported, lenovo()).value());
        Binder wrong = new Binder(); wrong.attachInterface(null, "another.interface");
        try { Oaid.query(context, wrong, lenovo()); fail("Must reject an unrelated service"); }
        catch (IOException expected) { }
    }

    @Test public void timedOutAndLateServiceConnectionsAreReleased() throws Exception {
        AtomicInteger unbound = new AtomicInteger();
        Context wrapper = new ContextWrapper(context) {
            @Override public boolean bindService(Intent intent, ServiceConnection connection, int flags) { return true; }
            @Override public void unbindService(ServiceConnection connection) { unbound.incrementAndGet(); }
        };
        Oaid.Connection connection = new Oaid.Connection(wrapper);
        try { connection.connect(lenovo().intent(), 20); fail("Must time out"); }
        catch (TimeoutException expected) { }
        finally { connection.close(); }
        assertEquals(1, unbound.get());
        connection.onServiceConnected(new ComponentName("test", "test.Service"), new Binder());
        connection.close(); assertEquals(1, unbound.get());
        try { connection.connect(lenovo().intent(), 20); fail("Must not reconnect after close"); }
        catch (IOException expected) { }
    }

    @Test public void nullBindingIsReleasedAndReported() throws Exception {
        AtomicInteger unbound = new AtomicInteger();
        Context wrapper = new ContextWrapper(context) {
            @Override public boolean bindService(Intent intent, ServiceConnection connection, int flags) {
                connection.onNullBinding(new ComponentName("test", "test.Service")); return true;
            }
            @Override public void unbindService(ServiceConnection connection) { unbound.incrementAndGet(); }
        };
        Oaid.Connection connection = new Oaid.Connection(wrapper);
        try { connection.connect(lenovo().intent(), 100); fail("Null binding must not yield an ID"); }
        catch (IOException expected) { }
        finally { connection.close(); }
        assertEquals(1, unbound.get());
    }

    @Test public void rejectedBindingsAndCloseDuringBindReleaseTheDispatcher() throws Exception {
        for (int mode = 0; mode < 3; mode++) {
            final int behavior = mode;
            AtomicInteger unbound = new AtomicInteger();
            Context wrapper = new ContextWrapper(context) {
                @Override public boolean bindService(Intent intent, ServiceConnection connection, int flags) {
                    if (behavior == 0) return false;
                    if (behavior == 1) throw new SecurityException("vendor denied access");
                    ((Oaid.Connection) connection).close(); return true;
                }
                @Override public void unbindService(ServiceConnection connection) { unbound.incrementAndGet(); }
            };
            Oaid.Connection connection = new Oaid.Connection(wrapper);
            try { connection.connect(lenovo().intent(), 50); fail("Binding must fail"); }
            catch (IOException | SecurityException expected) { }
            finally { connection.close(); }
            connection.close(); assertEquals(1, unbound.get());
        }
    }

    @Test public void requestedFieldsCanBeFilteredAndOpenedInTheDevicePage() throws Exception {
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        try (ActivityScenario<DeviceInfoActivity> scenario = ActivityScenario.launch(DeviceInfoActivity.class)) {
            for (String name : new String[]{"ro.boot.cpuid", "UFS Serial", "OAID"}) {
                UiObject2 filter = device.wait(Until.findObject(By.desc("筛选设备信息")), 10000); assertNotNull(filter);
                filter.setText(name);
                UiObject2 row = device.wait(Until.findObject(By.descStartsWith(name + "：")), 20000);
                assertNotNull("Requested field missing from page: " + name, row);
                device.waitForIdle();
                assertTrue(device.takeScreenshot(new File(context.getExternalFilesDir(null), "device-" + name.replace(' ', '-').replace('.', '-') + "-0626.png")));
                row.click();
                assertTrue(device.wait(Until.hasObject(By.text(name)), 2000));
                UiObject2 close = device.wait(Until.findObject(By.text("关闭")), 2000); assertNotNull(close); close.click();
            }
        }
    }
    private static Oaid.Service lenovo() {
        return new Oaid.Service("com.zui.deviceidservice", "com.zui.deviceidservice.DeviceidService", null, DESCRIPTOR, 1, 3, 0, false);
    }
}
