package org.example.filemanager;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import jcifs.config.PropertyConfiguration;
import jcifs.context.BaseContext;
import jcifs.smb.NtlmPasswordAuthenticator;
import jcifs.smb.SmbFile;
import okhttp3.*;
import org.xmlpull.v1.XmlPullParser;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Authenticated clients for one user-selected endpoint. Paths below the root are segmented. */
interface RemoteStorage extends AutoCloseable {
    record Entry(String name, boolean directory, long size) { }
    List<Entry> list(List<String> path) throws Exception;
    void download(List<String> path, OutputStream target) throws Exception;
    void upload(List<String> path, InputStream source) throws Exception;
    @Override default void close() throws Exception { }

    static RemoteStorage connect(String address, String user, String password, String fingerprint) throws Exception {
        URI endpoint = new URI(address.trim());
        if (endpoint.getHost() == null || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null)
            throw new IOException("请输入完整服务器地址，账号和密码请分别填写");
        return switch (endpoint.getScheme().toLowerCase(Locale.ROOT)) {
            case "https" -> new Dav(endpoint, user, password);
            case "sftp" -> new Sftp(endpoint, user, password, fingerprint);
            case "smb" -> new Smb(endpoint, user, password);
            default -> throw new IOException("支持 https://、sftp:// 和 smb:// 地址");
        };
    }

    static String component(String value) throws IOException {
        FileStore.checkName(value);
        if (value.indexOf('\\') >= 0 || value.indexOf('\0') >= 0) throw new IOException("文件名含不支持的路径字符");
        return value;
    }
    static List<String> child(List<String> path, String name) throws IOException {
        List<String> target = new ArrayList<>(path);
        target.add(component(name));
        return List.copyOf(target);
    }
    static List<Entry> sorted(List<Entry> list) {
        list.sort(Comparator.comparing((Entry entry) -> !entry.directory).thenComparing(Entry::name, NaturalOrder::compare));
        return List.copyOf(list);
    }

    final class Dav implements RemoteStorage {
        private static final MediaType XML = MediaType.get("application/xml; charset=utf-8");
        private static final MediaType DATA = MediaType.get("application/octet-stream");
        private final HttpUrl base;
        private final String authorization;
        private final OkHttpClient client;
        Dav(URI endpoint, String user, String password) throws IOException {
            this(endpoint, user, password, new OkHttpClient.Builder());
        }
        Dav(URI endpoint, String user, String password, OkHttpClient.Builder connection) throws IOException {
            client = connection.connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
                    .writeTimeout(15, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build();
            HttpUrl parsed = HttpUrl.get(endpoint.toASCIIString());
            base = parsed.encodedPath().endsWith("/") ? parsed : parsed.newBuilder().addPathSegment("").build();
            if (user.contains(":")) throw new IOException("WebDAV 用户名不能包含冒号");
            authorization = user.isEmpty() ? null : Credentials.basic(user, password, StandardCharsets.UTF_8);
        }
        private HttpUrl url(List<String> path, boolean directory) throws IOException {
            HttpUrl.Builder result = base.newBuilder();
            for (String item : path) result.addPathSegment(component(item));
            if (directory && !path.isEmpty()) result.addPathSegment("");
            return result.build();
        }
        private Request.Builder request(HttpUrl url) {
            Request.Builder request = new Request.Builder().url(url);
            if (authorization != null) request.header("Authorization", authorization);
            return request;
        }
        private Response execute(Request request) throws IOException {
            Response result = client.newCall(request).execute();
            if (!result.isSuccessful()) {
                int code = result.code();
                result.close();
                if (code == 409 || code == 412) throw new IOException("目标已存在或目录已变化");
                throw new IOException("WebDAV 请求失败（HTTP " + code + "）");
            }
            return result;
        }
        @Override public List<Entry> list(List<String> path) throws Exception {
            HttpUrl folder = url(path, true);
            String query = "<d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/><d:getcontentlength/></d:prop></d:propfind>";
            try (Response response = execute(request(folder).header("Depth", "1")
                    .method("PROPFIND", RequestBody.create(query, XML)).build())) {
                XmlPullParser parser = android.util.Xml.newPullParser();
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false);
                byte[] body = FileStore.readBytes(Objects.requireNonNull(response.body()).byteStream(), 8 * 1024 * 1024 + 1);
                if (body.length > 8 * 1024 * 1024) throw new IOException("WebDAV 目录结果超过 8 MiB");
                parser.setInput(new ByteArrayInputStream(body), null);
                Map<String, Entry> entries = new LinkedHashMap<>();
                String href = null;
                long size = -1;
                boolean directory = false;
                for (int event = parser.next(); event != XmlPullParser.END_DOCUMENT; event = parser.next()) {
                    Tasks.Progress.check();
                    if (event == XmlPullParser.START_TAG) {
                        switch (parser.getName()) {
                            case "response" -> { href = null; size = -1; directory = false; }
                            case "href" -> href = parser.nextText();
                            case "collection" -> directory = true;
                            case "getcontentlength" -> {
                                try { size = Long.parseLong(parser.nextText()); }
                                catch (NumberFormatException ignored) { size = -1; }
                            }
                        }
                    } else if (event == XmlPullParser.END_TAG && "response".equals(parser.getName()) && href != null) {
                        HttpUrl item = folder.resolve(href);
                        if (item == null || !item.scheme().equals(folder.scheme()) || !item.host().equals(folder.host())
                                || item.port() != folder.port() || !item.username().isEmpty() || !item.password().isEmpty()) continue;
                        List<String> prefix = new ArrayList<>(folder.pathSegments());
                        prefix.remove(prefix.size() - 1);
                        List<String> itemPath = new ArrayList<>(item.pathSegments());
                        if (itemPath.get(itemPath.size() - 1).isEmpty()) itemPath.remove(itemPath.size() - 1);
                        if (itemPath.size() != prefix.size() + 1 || !itemPath.subList(0, prefix.size()).equals(prefix)) continue;
                        try {
                            String name = component(itemPath.get(itemPath.size() - 1));
                            entries.putIfAbsent(name, new Entry(name, directory, size));
                        } catch (IOException invalidName) { /* Unrepresentable name cannot be a target. */ }
                    }
                }
                return sorted(new ArrayList<>(entries.values()));
            }
        }
        @Override public void download(List<String> path, OutputStream target) throws IOException {
            try (Response response = execute(request(url(path, false)).get().build())) {
                FileStore.pump(Objects.requireNonNull(response.body()).byteStream(), target);
            }
        }
        @Override public void upload(List<String> path, InputStream source) throws IOException {
            RequestBody content = new RequestBody() {
                @Override public MediaType contentType() { return DATA; }
                @Override public boolean isOneShot() { return true; }
                @Override public void writeTo(okio.BufferedSink sink) throws IOException { FileStore.pump(source, sink.outputStream()); }
            };
            try (Response ignored = execute(request(url(path, false)).header("If-None-Match", "*").put(content).build())) { }
        }
        @Override public void close() { client.dispatcher().cancelAll(); client.connectionPool().evictAll(); }
    }

    final class Sftp implements RemoteStorage {
        private final Session session;
        private final ChannelSftp channel;
        private final String root;
        Sftp(URI endpoint, String user, String password, String fingerprint) throws Exception {
            if (user.isBlank() || !fingerprint.trim().matches("SHA256:[A-Za-z0-9+/]{43}=?"))
                throw new IOException("请填写 SFTP 用户名与可信的 SHA256 主机指纹");
            String expected = fingerprint.trim().replaceFirst("=$", "");
            JSch ssh = new JSch();
            ssh.setHostKeyRepository(new HostKeyRepository() {
                @Override public int check(String host, byte[] key) {
                    try {
                        String actual = "SHA256:" + android.util.Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(key),
                                android.util.Base64.NO_PADDING | android.util.Base64.NO_WRAP);
                        return expected.equals(actual) ? OK : CHANGED;
                    } catch (java.security.NoSuchAlgorithmException impossible) { return CHANGED; }
                }
                @Override public void add(com.jcraft.jsch.HostKey key, com.jcraft.jsch.UserInfo info) { }
                @Override public void remove(String host, String type) { }
                @Override public void remove(String host, String type, byte[] key) { }
                @Override public String getKnownHostsRepositoryID() { return "server fingerprint"; }
                @Override public com.jcraft.jsch.HostKey[] getHostKey() { return new com.jcraft.jsch.HostKey[0]; }
                @Override public com.jcraft.jsch.HostKey[] getHostKey(String host, String type) { return getHostKey(); }
            });
            session = ssh.getSession(user, endpoint.getHost(), endpoint.getPort() < 0 ? 22 : endpoint.getPort());
            session.setPassword(password);
            session.setConfig("StrictHostKeyChecking", "yes");
            session.setConfig("PreferredAuthentications", "password,keyboard-interactive");
            try {
                session.connect(10_000);
                session.setTimeout(15_000);
                channel = (ChannelSftp) session.openChannel("sftp");
                channel.connect(10_000);
                root = channel.realpath(endpoint.getPath().isEmpty() ? "." : endpoint.getPath());
            } catch (Exception error) { session.disconnect(); throw error; }
        }
        private String path(List<String> parts) throws IOException {
            StringBuilder value = new StringBuilder(root.endsWith("/") ? root.substring(0, root.length() - 1) : root);
            for (String part : parts) value.append('/').append(component(part));
            return value.length() == 0 ? "/" : value.toString();
        }
        private String globSafe(String path) { return path.replace("\\", "\\\\").replace("*", "\\*").replace("?", "\\?"); }
        @Override public List<Entry> list(List<String> path) throws Exception {
            List<Entry> result = new ArrayList<>();
            for (ChannelSftp.LsEntry item : channel.ls(globSafe(path(path)))) {
                Tasks.Progress.check();
                String name = item.getFilename();
                if (name.equals(".") || name.equals("..") || item.getAttrs().isLink()) continue;
                try { result.add(new Entry(component(name), item.getAttrs().isDir(), item.getAttrs().getSize())); }
                catch (IOException invalidName) { }
            }
            return sorted(result);
        }
        @Override public void download(List<String> path, OutputStream target) throws Exception {
            String remote = globSafe(path(path));
            try (InputStream input = channel.get(remote)) { FileStore.pump(input, target); }
        }
        @Override public void upload(List<String> path, InputStream source) throws Exception {
            String target = globSafe(path(path));
            String temporaryName = ".dualstack-upload-" + UUID.randomUUID();
            List<String> parent = path.subList(0, path.size() - 1);
            String temporary = globSafe(path(child(parent, temporaryName)));
            try {
                try (OutputStream output = channel.put(temporary)) { FileStore.pump(source, output); }
                try {
                    channel.lstat(target);
                    throw new IOException("同名远程文件已存在");
                } catch (com.jcraft.jsch.SftpException missing) {
                    if (missing.id != ChannelSftp.SSH_FX_NO_SUCH_FILE) throw missing;
                }
                // JSch prefers the POSIX extension, which can replace a newly created target.
                if ("1".equals(channel.getExtension("posix-rename@openssh.com"))) {
                    if (!"1".equals(channel.getExtension("hardlink@openssh.com")))
                        throw new IOException("此 SFTP 服务器无法保证上传时不覆盖同名文件");
                    channel.hardlink(temporary, target);
                } else {
                    channel.rename(temporary, target);
                }
            } finally { try { channel.rm(temporary); } catch (com.jcraft.jsch.SftpException ignored) { } }
        }
        @Override public void close() { channel.disconnect(); session.disconnect(); }
    }

    final class Smb implements RemoteStorage {
        private final jcifs.CIFSContext context;
        private final String base;
        Smb(URI endpoint, String user, String password) throws Exception {
            Properties properties = new Properties();
            properties.setProperty("jcifs.smb.client.minVersion", "SMB202");
            properties.setProperty("jcifs.smb.client.maxVersion", "SMB311");
            properties.setProperty("jcifs.smb.client.connTimeout", "10000");
            properties.setProperty("jcifs.smb.client.soTimeout", "15000");
            properties.setProperty("jcifs.smb.client.responseTimeout", "15000");
            BaseContext common = new BaseContext(new PropertyConfiguration(properties));
            String[] account = user.split("\\\\", 2);
            context = common.withCredentials(new NtlmPasswordAuthenticator(account.length == 2 ? account[0] : "",
                    account[account.length - 1], password));
            String address = endpoint.toASCIIString();
            base = address.endsWith("/") ? address : address + "/";
        }
        private SmbFile file(List<String> path, boolean directory) throws Exception {
            StringBuilder address = new StringBuilder(base);
            for (int i = 0; i < path.size(); i++) {
                if (i > 0) address.append('/');
                address.append(android.net.Uri.encode(component(path.get(i))));
            }
            if (directory && !path.isEmpty()) address.append('/');
            return new SmbFile(address.toString(), context);
        }
        @Override public List<Entry> list(List<String> path) throws Exception {
            List<Entry> result = new ArrayList<>();
            try (SmbFile folder = file(path, true)) {
                for (SmbFile entry : folder.listFiles()) try (entry) {
                    Tasks.Progress.check();
                    boolean directory = entry.isDirectory();
                    String name = entry.getName().replaceFirst("/$", "");
                    try { result.add(new Entry(component(name), directory, directory ? -1 : entry.length())); }
                    catch (IOException invalidName) { }
                }
            }
            return sorted(result);
        }
        @Override public void download(List<String> path, OutputStream target) throws Exception {
            try (SmbFile item = file(path, false); InputStream input = item.openInputStream()) { FileStore.pump(input, target); }
        }
        @Override public void upload(List<String> path, InputStream source) throws Exception {
            List<String> parent = path.subList(0, path.size() - 1);
            try (SmbFile target = file(path, false);
                 SmbFile temporary = file(child(parent, ".dualstack-upload-" + UUID.randomUUID()), false)) {
                if (target.exists()) throw new IOException("同名远程文件已存在");
                try {
                    temporary.createNewFile();
                    try (OutputStream out = temporary.openOutputStream()) { FileStore.pump(source, out); }
                    temporary.renameTo(target, false);
                } finally { if (temporary.exists()) temporary.delete(); }
            }
        }
        @Override public void close() throws Exception { context.close(); }
    }
}
