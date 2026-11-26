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
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import org.json.JSONArray;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class RemoteStorageInstrumentedTest {
    @Test public void serverAddressSupportsSftpShorthandAndRejectsEmbeddedCredentials() throws Exception {
        assertEquals("sftp://server.example:2222/home/me", RemoteStorage.endpoint(" server.example:2222/home/me ").toString());
        assertEquals("example.com", RemoteStorage.endpoint("https://example.com/dav/").getHost());
        assertEquals("sftp://server.example:22/dir%20a", RemoteStorage.endpoint("SSH://server.example:22/dir%20a").toASCIIString());
        for (String invalid : List.of("https://name:password@example.com/dav", "ssh://name:password@example.com", "sftp://example.com:65536",
                "https://example.com/dav?token=secret", "sftp://example.com/home/my folder", "")) {
            try { RemoteStorage.endpoint(invalid); fail("Invalid address was accepted"); }
            catch (IOException expected) { assertNotNull(expected.getMessage()); }
        }
    }

    @Test public void webDavUsesSuccessfulPropertiesAndSendsSizedUploadsAndNonReplacingRenames() throws Exception {
        HeldCertificate certificate = new HeldCertificate.Builder().addSubjectAlternativeName("localhost").build();
        HandshakeCertificates serverTls = new HandshakeCertificates.Builder().heldCertificate(certificate).build();
        HandshakeCertificates clientTls = new HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate()).build();
        OkHttpClient.Builder client = new OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager());
        try (MockWebServer server = new MockWebServer()) {
            server.useHttps(serverTls.sslSocketFactory(), false);
            server.start();
            server.enqueue(new MockResponse().setResponseCode(207).setBody("""
                    <d:multistatus xmlns:d="DAV:">
                      <d:response><d:href>/share/docs/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
                      <d:response><d:href>/share/hello%20world.txt</d:href>
                        <d:propstat><d:prop><d:getcontentlength>999</d:getcontentlength><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat>
                        <d:propstat><d:prop><d:getcontentlength>3</d:getcontentlength></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
                      </d:response>
                      <d:response><d:href>/share/denied</d:href><d:status>HTTP/1.1 403 Forbidden</d:status></d:response>
                      <d:response><d:href>/outside</d:href></d:response>
                    </d:multistatus>
                    """));
            for (int status : new int[]{201, 201, 201, 204, 302, 200})
                server.enqueue(new MockResponse().setResponseCode(status).addHeader("Location", "https://elsewhere.invalid/"));
            try (RemoteStorage remote = new RemoteStorage.Dav(server.url("/share").uri(), "fixture", "password", client)) {
                assertEquals(List.of(new RemoteStorage.Entry("docs", true, -1),
                        new RemoteStorage.Entry("hello world.txt", false, 3)), remote.list(List.of()));
                remote.mkdir(List.of("new folder"));
                remote.upload(List.of("hello world.txt"), new ByteArrayInputStream(new byte[]{1, 2, 3}), 3);
                remote.renameFile(List.of("hello world.txt"), List.of("renamed.txt"));
                remote.deleteFile(List.of("renamed.txt"));
                try { remote.list(List.of()); fail("Redirect was followed"); }
                catch (IOException known) { assertTrue(known.getMessage().contains("跳转")); }
                try { remote.list(List.of()); fail("An ordinary HTTP page was accepted as a WebDAV directory"); }
                catch (IOException known) { assertTrue(known.getMessage().contains("WebDAV")); }
            }
            assertEquals("1", server.takeRequest().getHeader("Depth"));
            var mkdir = server.takeRequest();
            assertEquals("MKCOL", mkdir.getMethod());
            assertEquals("/share/new%20folder/", mkdir.getPath());
            var upload = server.takeRequest();
            assertEquals("PUT", upload.getMethod());
            assertEquals("3", upload.getHeader("Content-Length"));
            assertEquals("*", upload.getHeader("If-None-Match"));
            assertArrayEquals(new byte[]{1, 2, 3}, upload.getBody().readByteArray());
            var move = server.takeRequest();
            assertEquals("MOVE", move.getMethod());
            assertEquals("F", move.getHeader("Overwrite"));
            assertEquals(server.url("/share/renamed.txt").toString(), move.getHeader("Destination"));
            assertEquals("DELETE", server.takeRequest().getMethod());
            assertEquals(7, server.getRequestCount());
        }
    }

    @Test public void sftpRequiresHostVerificationAndAcceptsPasswordAndEncryptedKey() throws Exception {
        var arguments = InstrumentationRegistry.getArguments();
        String port = arguments.getString("sftpPort");
        String pin = arguments.getString("sftpFingerprint");
        String keyPath = arguments.getString("sftpKeyPath");
        Assume.assumeTrue("Requires an isolated SSH server and a test key", port != null && pin != null && keyPath != null);
        String address = "127.0.0.1:" + port;
        try (RemoteStorage ignored = RemoteStorage.connect(address, "fixture", "fixture-password", "")) {
            fail("Server was trusted without a pin");
        } catch (RemoteStorage.HostKeyRequired challenge) {
            assertEquals(pin, challenge.fingerprint);
        }
        try (RemoteStorage ignored = RemoteStorage.connect(address, "fixture", "fixture-password", "SHA256:" + "A".repeat(43))) {
            fail("Changed fingerprint was accepted");
        } catch (com.jcraft.jsch.JSchException expected) {
            assertTrue(expected.getMessage().contains("指纹"));
        }
        try (RemoteStorage remote = RemoteStorage.connect(address, "fixture", "fixture-password", pin)) {
            exerciseFileOperations(remote);
        }
        byte[] key = Files.readAllBytes(Paths.get(keyPath));
        try (RemoteStorage remote = RemoteStorage.connect(address, "fixture", "fixture-key-passphrase", pin, key)) {
            assertTrue(remote.list(List.of()).stream().anyMatch(entry -> entry.name().equals("fixture.txt")));
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    @Test public void smbNewFolderAndFileMutationsWorkOnAnIsolatedShare() throws Exception {
        String port = InstrumentationRegistry.getArguments().getString("smbPort");
        Assume.assumeTrue("Requires an isolated SMB2 share", port != null);
        try (RemoteStorage remote = RemoteStorage.connect("smb://127.0.0.1:" + port + "/fixture/",
                "fixture", "fixture-password", "")) {
            exerciseFileOperations(remote);
        }
    }

    @Test public void sftpScreenRequiresFingerprintConfirmationBeforeBrowsing() throws Exception {
        var arguments = InstrumentationRegistry.getArguments();
        String port = arguments.getString("sftpPort");
        String pin = arguments.getString("sftpFingerprint");
        Assume.assumeTrue("Requires an isolated SSH server", port != null && pin != null);
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        try (var scenario = ActivityScenario.launch(RemoteStorageActivity.class)) {
            var previous = new HashMap<String, Object>();
            try {
                scenario.onActivity(activity -> {
                    previous.putAll(activity.getPreferences(0).getAll());
                    field(activity, "serverName", EditText.class).setText("本地 SFTP 测试");
                    field(activity, "endpoint", EditText.class).setText("ssh://127.0.0.1:" + port);
                    assertEquals("协议 · SSH（SFTP）", field(activity, "protocol", Button.class).getText().toString());
                    assertEquals(View.GONE, field(activity, "key", Button.class).getVisibility());
                    assertEquals("登录方式 · 密码", field(activity, "authentication", Button.class).getText().toString());
                    field(activity, "account", EditText.class).setText("fixture");
                    field(activity, "password", EditText.class).setText("fixture-password");
                    field(activity, "fingerprint", EditText.class).setText("");
                    assertTrue(field(activity, "connect", Button.class).performClick());
                });
                assertTrue(device.wait(Until.hasObject(By.textContains(pin)), 15000));
                assertFalse(device.hasObject(By.desc("远程文件列表")));
                var confirm = device.wait(Until.findObject(By.text("指纹一致，连接")), 5000);
                assertNotNull(confirm);
                confirm.click();
                assertTrue(device.wait(Until.hasObject(By.textStartsWith("fixture.txt")), 15000));
                scenario.onActivity(activity -> {
                    assertEquals(pin, activity.getPreferences(0).getString("fingerprint", ""));
                    assertEquals("sftp://127.0.0.1:" + port, activity.getPreferences(0).getString("endpoint", ""));
                    assertEquals("", field(activity, "password", EditText.class).getText().toString());
                });
            } finally {
                scenario.onActivity(activity -> restorePreferences(activity, previous));
            }
        }
    }

    @Test public void sshPasswordReportsWhenAccountRequiresPublicKey() throws Exception {
        var arguments = InstrumentationRegistry.getArguments();
        String port = arguments.getString("sftpPort");
        String pin = arguments.getString("sftpFingerprint");
        String keyOnly = arguments.getString("sftpKeyOnlyUser");
        Assume.assumeTrue("Requires a key-only fixture account", port != null && pin != null && keyOnly != null);
        var error = assertThrows(IOException.class, () -> RemoteStorage.connect(
                "ssh://127.0.0.1:" + port, keyOnly, "fixture-password", pin));
        assertTrue(error.getMessage(), error.getMessage().contains("只允许公钥登录"));
    }

    @Test public void ftpOfferSwitchesToSshAndKeepsThePort() throws Exception {
        String port = InstrumentationRegistry.getArguments().getString("sftpPort");
        Assume.assumeTrue("Requires an isolated SSH server", port != null);
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        try (var scenario = ActivityScenario.launch(RemoteStorageActivity.class)) {
            scenario.onActivity(activity -> {
                field(activity, "endpoint", EditText.class).setText("ftp://127.0.0.1:" + port);
                field(activity, "account", EditText.class).setText("fixture");
                field(activity, "password", EditText.class).setText("fixture-password");
                assertTrue(field(activity, "connect", Button.class).performClick());
            });
            var switchButton = device.wait(Until.findObject(By.text("切换到 SSH")), 15000);
            assertNotNull(switchButton);
            switchButton.click();
            scenario.onActivity(activity -> {
                assertEquals("sftp://127.0.0.1:" + port, field(activity, "endpoint", EditText.class).getText().toString());
                assertEquals("fixture", field(activity, "account", EditText.class).getText().toString());
                assertEquals("", field(activity, "password", EditText.class).getText().toString());
                assertTrue(field(activity, "authentication", Button.class).performClick());
            });
            var privateKey = device.wait(Until.findObject(By.text("SSH 私钥")), 5000);
            assertNotNull(privateKey);
            privateKey.click();
            scenario.onActivity(activity -> {
                assertEquals(View.VISIBLE, field(activity, "key", Button.class).getVisibility());
                assertEquals("私钥口令（可选）", field(activity, "password", EditText.class).getHint().toString());
            });
        }
    }

    private void exerciseFileOperations(RemoteStorage remote) throws Exception {
        String folder = "资料 " + UUID.randomUUID();
        List<String> directory = List.of(folder);
        List<String> before = List.of(folder, "上传数据 #100%.txt"), after = List.of(folder, "已重命名.txt");
        byte[] bytes = "网络存储验证".getBytes(StandardCharsets.UTF_8);
        remote.mkdir(directory);
        assertTrue(remote.list(List.of()).stream().anyMatch(entry -> entry.directory() && entry.name().equals(folder)));
        remote.upload(before, new ByteArrayInputStream(bytes), bytes.length);
        try { remote.upload(before, new ByteArrayInputStream(new byte[]{1}), 1); fail("Overwriting a file was allowed"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("同名")); }
        remote.renameFile(before, after);
        assertEquals(List.of(new RemoteStorage.Entry("已重命名.txt", false, bytes.length)), remote.list(directory));
        ByteArrayOutputStream received = new ByteArrayOutputStream();
        remote.download(after, received);
        assertArrayEquals(bytes, received.toByteArray());
        remote.deleteFile(after);
        assertTrue(remote.list(directory).isEmpty());
    }

    @Test public void savedServerProfilesExcludeCredentialsAndRestoreTheLastAddress() throws Exception {
        try (var scenario = ActivityScenario.launch(RemoteStorageActivity.class)) {
            var previous = new HashMap<String, Object>();
            try {
                scenario.onActivity(activity -> {
                    var settings = activity.getPreferences(0);
                    previous.putAll(settings.getAll());
                    settings.edit().clear().commit();
                    saveProfile(activity, "测试服务器一", "one.example:2222");
                    saveProfile(activity, "测试服务器二", "sftp://two.example");
                    for (var entry : settings.getAll().values()) {
                        assertFalse(entry.toString().contains("test-secret-never-persist"));
                    }
                    try { assertEquals(2, new JSONArray(settings.getString("servers", "[]")).length()); }
                    catch (org.json.JSONException invalid) { throw new AssertionError(invalid); }
                });
                scenario.recreate();
                scenario.onActivity(activity -> {
                    assertEquals("sftp://two.example", field(activity, "endpoint", EditText.class).getText().toString());
                    assertEquals("", field(activity, "password", EditText.class).getText().toString());
                });
            } finally {
                scenario.onActivity(activity -> restorePreferences(activity, previous));
            }
        }
    }

    private void restorePreferences(RemoteStorageActivity activity, HashMap<String, Object> previous) {
        var restore = activity.getPreferences(0).edit().clear();
        previous.forEach((key, value) -> { if (value instanceof String text) restore.putString(key, text); });
        restore.commit();
    }

    private void saveProfile(RemoteStorageActivity activity, String name, String address) {
        field(activity, "serverName", EditText.class).setText(name);
        field(activity, "endpoint", EditText.class).setText(address);
        field(activity, "account", EditText.class).setText("fixture");
        field(activity, "password", EditText.class).setText("test-secret-never-persist");
        assertTrue(field(activity, "save", Button.class).performClick());
    }

    private static <T> T field(RemoteStorageActivity activity, String name, Class<T> type) {
        try {
            var field = RemoteStorageActivity.class.getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(activity));
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
}
