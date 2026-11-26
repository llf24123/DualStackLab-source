package org.example.filemanager;

import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.Until;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class RemoteFtpInstrumentedTest {
    private static final String USER = "fixture";
    private static final String PASSWORD = "fixture-password";

    private static String endpoint() {
        String address = InstrumentationRegistry.getArguments().getString("ftpEndpoint");
        Assume.assumeTrue("Requires an isolated writable FTP fixture", address != null);
        return address;
    }

    @Test public void sshGreetingReportsWrongProtocolWithoutSendingCredentials() throws Exception {
        try (var listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            listener.setSoTimeout(5000);
            FutureTask<Integer> firstClientByte = new FutureTask<>(() -> {
                try (var connection = listener.accept()) {
                    connection.setSoTimeout(5000);
                    connection.getOutputStream().write("SSH-2.0-Fixture\r\n".getBytes(StandardCharsets.US_ASCII));
                    connection.getOutputStream().flush();
                    return connection.getInputStream().read();
                }
            });
            new Thread(firstClientByte, "ftp-ssh-greeting-fixture").start();
            var error = assertThrows(IOException.class, () -> RemoteStorage.connect(
                    "ftp://127.0.0.1:" + listener.getLocalPort(), USER, PASSWORD, ""));
            assertTrue(error.getMessage().contains("提供 SSH 服务"));
            assertEquals(Integer.valueOf(-1), firstClientByte.get(6, TimeUnit.SECONDS));
        }
    }

    @Test public void passwordLoginPassiveTransfersAndFileMutations() throws Exception {
        String address = endpoint();
        try (RemoteStorage remote = RemoteStorage.connect(address, USER, PASSWORD, "")) {
            assertTrue(remote.list(List.of()).contains(new RemoteStorage.Entry("fixture.txt", false, 7)));
            String folder = "资料 " + UUID.randomUUID();
            remote.mkdir(List.of(folder));
            List<String> original = List.of(folder, "上传 #100%.bin");
            List<String> renamed = List.of(folder, "新名称.bin");
            byte[] data = {0, 1, 2, 10, 13, (byte) 255};
            var progress = new Tasks.Progress();
            progress.run(() -> { remote.upload(original, new ByteArrayInputStream(data), data.length); return null; });
            assertEquals(data.length, progress.transferred.get());
            IOException duplicate = assertThrows(IOException.class,
                    () -> remote.upload(original, new ByteArrayInputStream(new byte[] {1}), 1));
            assertTrue(duplicate.getMessage().contains("同名"));
            remote.renameFile(original, renamed);
            assertEquals(List.of(new RemoteStorage.Entry("新名称.bin", false, data.length)), remote.list(List.of(folder)));
            ByteArrayOutputStream received = new ByteArrayOutputStream();
            remote.download(renamed, received);
            assertArrayEquals(data, received.toByteArray());
            remote.deleteFile(renamed);
            assertTrue(remote.list(List.of(folder)).isEmpty());
        }
    }

    @Test public void badPasswordAndInitialDirectoryHaveDistinctErrors() throws Exception {
        String address = endpoint();
        var badPassword = assertThrows(IOException.class,
                () -> RemoteStorage.connect(address, USER, "bad-fixture-password", ""));
        assertTrue(badPassword.getMessage().contains("530"));
        assertFalse(badPassword.getMessage().contains("bad-fixture-password"));
        var badPath = assertThrows(IOException.class,
                () -> RemoteStorage.connect(address + "/missing-directory", USER, PASSWORD, ""));
        assertTrue(badPath.getMessage().contains("初始目录"));
    }

    @Test public void cancelledUploadCleansTemporaryFileAndCanStartAgain() throws Exception {
        try (RemoteStorage remote = RemoteStorage.connect(endpoint(), USER, PASSWORD, "")) {
            String folder = "cancel-" + UUID.randomUUID();
            remote.mkdir(List.of(folder));
            List<String> destination = List.of(folder, "result.bin");
            var progress = new Tasks.Progress();
            InputStream stoppedSource = new InputStream() {
                boolean payloadWritten;
                @Override public int read() { throw new AssertionError("Expected buffer reads"); }
                @Override public int read(byte[] bytes, int offset, int length) {
                    if (!payloadWritten) {
                        payloadWritten = true;
                        bytes[offset] = 7;
                        return 1;
                    }
                    progress.cancelled.set(true);
                    ((RemoteFtp) remote).cancelTransfer();
                    return -1;
                }
            };
            assertThrows(Tasks.CancelledException.class,
                    () -> progress.run(() -> { remote.upload(destination, stoppedSource, -1); return null; }));
            assertEquals(1, progress.transferred.get());
            assertTrue(remote.list(List.of(folder)).isEmpty());
            remote.upload(destination, new ByteArrayInputStream(new byte[] {8}), 1);
            assertEquals(List.of(new RemoteStorage.Entry("result.bin", false, 1)), remote.list(List.of(folder)));
        }
    }

    @Test public void protocolPickerAcceptsBareHostAndHidesSshControls() throws Exception {
        String address = endpoint();
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        try (var scenario = ActivityScenario.launch(RemoteStorageActivity.class)) {
            var previous = new HashMap<String, Object>();
            try {
                scenario.onActivity(activity -> {
                    previous.putAll(activity.getPreferences(0).getAll());
                    field(activity, "endpoint", EditText.class).setText("");
                    field(activity, "protocol", Button.class).performClick();
                });
                var ftp = device.wait(Until.findObject(By.text("FTP")), 5000);
                assertNotNull(ftp);
                ftp.click();
                scenario.onActivity(activity -> {
                    field(activity, "serverName", EditText.class).setText("本地 FTP 测试");
                    field(activity, "endpoint", EditText.class).setText(address.substring("ftp://".length()));
                    assertEquals(View.GONE, field(activity, "key", Button.class).getVisibility());
                    assertEquals(View.GONE, field(activity, "fingerprint", EditText.class).getVisibility());
                    field(activity, "account", EditText.class).setText(USER);
                    field(activity, "password", EditText.class).setText(PASSWORD);
                    field(activity, "connect", Button.class).performClick();
                });
                assertTrue(device.wait(Until.hasObject(By.textStartsWith("fixture.txt")), 15000));
                scenario.onActivity(activity -> {
                    var preferences = activity.getPreferences(0);
                    assertEquals(address, preferences.getString("endpoint", ""));
                    assertFalse(preferences.getAll().toString().contains(PASSWORD));
                    assertEquals("", field(activity, "password", EditText.class).getText().toString());
                });
            } finally {
                scenario.onActivity(activity -> {
                    var editor = activity.getPreferences(0).edit().clear();
                    previous.forEach((name, value) -> { if (value instanceof String text) editor.putString(name, text); });
                    editor.commit();
                });
            }
        }
    }

    private static <T> T field(RemoteStorageActivity activity, String name, Class<T> type) {
        try {
            var field = RemoteStorageActivity.class.getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(activity));
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
}
