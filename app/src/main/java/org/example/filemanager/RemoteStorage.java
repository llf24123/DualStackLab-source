package org.example.filemanager;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;
import jcifs.config.PropertyConfiguration;
import jcifs.context.BaseContext;
import jcifs.smb.NtlmPasswordAuthenticator;
import jcifs.smb.SmbFile;
import jcifs.smb.SmbException;
import jcifs.smb.NtStatus;
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
    default void upload(List<String> path, InputStream source) throws Exception { upload(path, source, -1); }
    void upload(List<String> path, InputStream source, long size) throws Exception;
    void mkdir(List<String> path) throws Exception;
    void renameFile(List<String> path, List<String> target) throws Exception;
    void deleteFile(List<String> path) throws Exception;
    @Override default void close() throws Exception { }

    static String connectionScheme(String scheme) {
        String normalized = scheme.toLowerCase(Locale.ROOT);
        return normalized.equals("ssh") ? "sftp" : normalized;
    }
    static URI endpoint(String address) throws IOException {
        String value = address.trim();
        if (value.isEmpty()) throw new IOException("请输入服务器地址");
        URI endpoint;
        try { endpoint = new URI(value.contains("://") ? value : "sftp://" + value).parseServerAuthority(); }
        catch (java.net.URISyntaxException error) { throw new IOException("服务器地址格式无效，路径中的空格请写成 %20"); }
        if (endpoint.getHost() == null || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null
                || endpoint.getPort() == 0 || endpoint.getPort() > 65535)
            throw new IOException("请输入完整服务器地址，账号和密码请分别填写");
        String scheme = connectionScheme(endpoint.getScheme());
        return scheme.equals(endpoint.getScheme()) ? endpoint
                : URI.create(scheme + endpoint.toASCIIString().substring(endpoint.getScheme().length()));
    }
    static RemoteStorage connect(String address, String user, String password, String fingerprint) throws Exception {
        return connect(address, user, password, fingerprint, null);
    }
    static RemoteStorage connect(String address, String user, String password, String fingerprint, byte[] key) throws Exception {
        URI endpoint = endpoint(address);
        return switch (endpoint.getScheme().toLowerCase(Locale.ROOT)) {
            case "https" -> new Dav(endpoint, user, password);
            case "sftp" -> new Sftp(endpoint, user, password, fingerprint, key);
            case "smb" -> new Smb(endpoint, user, password);
            case "ftp" -> new RemoteFtp(endpoint, user, password);
            default -> throw new IOException("支持 FTP、SSH（SFTP）、HTTPS WebDAV 和 SMB");
        };
    }

    final class SshServiceDetected extends IOException {
        SshServiceDetected(int port) {
            super("端口 " + port + " 提供 SSH 服务，无法用 FTP 连接。请选择 SSH（SFTP）或填写实际的 FTP 端口。");
        }
    }
    final class HostKeyRequired extends IOException {
        final String fingerprint;
        HostKeyRequired(String fingerprint) { super("请核对 SFTP 服务器的主机指纹"); this.fingerprint = fingerprint; }
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
                if (code == 401 || code == 403) throw new IOException("WebDAV 账号验证失败或没有权限（HTTP " + code + "）");
                if (code == 404) throw new IOException("未找到 WebDAV 路径，请检查服务器地址和共享目录（HTTP 404）");
                if (code == 409 || code == 412) throw new IOException("目标已存在或目录已变化");
                if (code >= 300 && code < 400) throw new IOException("WebDAV 返回跳转，请填写最终的 HTTPS WebDAV 地址（HTTP " + code + "）");
                throw new IOException("WebDAV 请求失败（HTTP " + code + "）");
            }
            return result;
        }
        @Override public List<Entry> list(List<String> path) throws Exception {
            HttpUrl folder = url(path, true);
            String query = "<d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/><d:getcontentlength/></d:prop></d:propfind>";
            try (Response response = execute(request(folder).header("Depth", "1")
                    .method("PROPFIND", RequestBody.create(query, XML)).build())) {
                if (response.code() != 207) throw new IOException("该地址未返回 WebDAV 目录，请填写 WebDAV 服务路径");
                XmlPullParser parser = android.util.Xml.newPullParser();
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false);
                byte[] body = FileStore.readBytes(Objects.requireNonNull(response.body()).byteStream(), 8 * 1024 * 1024 + 1);
                if (body.length > 8 * 1024 * 1024) throw new IOException("WebDAV 目录结果超过 8 MiB");
                parser.setInput(new ByteArrayInputStream(body), null);
                Map<String, Entry> entries = new LinkedHashMap<>();
                String href = null;
                long size = -1;
                boolean directory = false;
                boolean inPropstat = false, propDirectory = false;
                long propSize = -1;
                int status = 0, propStatus = 0;
                for (int event = parser.next(); event != XmlPullParser.END_DOCUMENT; event = parser.next()) {
                    Tasks.Progress.check();
                    if (!"DAV:".equals(parser.getNamespace())) continue;
                    if (event == XmlPullParser.START_TAG) {
                        switch (parser.getName()) {
                            case "response" -> { href = null; size = -1; directory = false; status = 0; }
                            case "href" -> { if (href == null && !inPropstat) href = parser.nextText().trim(); }
                            case "propstat" -> { inPropstat = true; propDirectory = false; propSize = -1; propStatus = 0; }
                            case "status" -> { if (inPropstat) propStatus = statusCode(parser.nextText()); else status = statusCode(parser.nextText()); }
                            case "collection" -> { if (inPropstat) propDirectory = true; else directory = true; }
                            case "getcontentlength" -> {
                                long value;
                                try { value = Math.max(-1, Long.parseLong(parser.nextText().trim())); }
                                catch (NumberFormatException ignored) { value = -1; }
                                if (inPropstat) propSize = value; else size = value;
                            }
                        }
                    } else if (event == XmlPullParser.END_TAG && "propstat".equals(parser.getName())) {
                        if (propStatus >= 200 && propStatus < 300) {
                            directory |= propDirectory;
                            if (propSize >= 0) size = propSize;
                        }
                        inPropstat = false;
                    } else if (event == XmlPullParser.END_TAG && "response".equals(parser.getName()) && href != null) {
                        if (status != 0 && (status < 200 || status >= 300)) continue;
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
                            entries.putIfAbsent(name, new Entry(name, directory, directory ? -1 : size));
                        } catch (IOException invalidName) { /* Unrepresentable name cannot be a target. */ }
                    }
                }
                return sorted(new ArrayList<>(entries.values()));
            }
        }
        private static int statusCode(String line) {
            String[] parts = line.trim().split("\\s+", 3);
            try { return parts.length >= 2 ? Integer.parseInt(parts[1]) : -1; }
            catch (NumberFormatException invalid) { return -1; }
        }
        @Override public void download(List<String> path, OutputStream target) throws IOException {
            try (Response response = execute(request(url(path, false)).get().build())) {
                FileStore.pump(Objects.requireNonNull(response.body()).byteStream(), target);
            }
        }
        @Override public void upload(List<String> path, InputStream source, long size) throws IOException {
            RequestBody content = new RequestBody() {
                @Override public MediaType contentType() { return DATA; }
                @Override public long contentLength() { return size; }
                @Override public boolean isOneShot() { return true; }
                @Override public void writeTo(okio.BufferedSink sink) throws IOException { FileStore.pump(source, sink.outputStream(), size); }
            };
            mutate(request(url(path, false)).header("If-None-Match", "*").put(content).build());
        }
        private void mutate(Request request) throws IOException {
            try (Response response = execute(request)) {
                if (response.code() == 202 || response.code() == 207) throw new IOException("服务器未确认操作全部完成，请刷新目录");
            }
        }
        @Override public void mkdir(List<String> path) throws IOException {
            mutate(request(url(path, true)).method("MKCOL", null).build());
        }
        @Override public void renameFile(List<String> path, List<String> target) throws IOException {
            mutate(request(url(path, false)).header("Destination", url(target, false).toString())
                    .header("Overwrite", "F").method("MOVE", null).build());
        }
        @Override public void deleteFile(List<String> path) throws IOException {
            mutate(request(url(path, false)).delete().build());
        }
        @Override public void close() { client.dispatcher().cancelAll(); client.connectionPool().evictAll(); }
    }

    final class Sftp implements RemoteStorage {
        private final Session session;
        private final ChannelSftp channel;
        private final String root;
        Sftp(URI endpoint, String user, String password, String fingerprint, byte[] keyFile) throws Exception {
            if (user.isBlank()) throw new IOException("请填写 SSH 用户名");
            String expected = fingerprint.trim().replaceFirst("=$", "");
            if (!expected.isEmpty() && !expected.matches("SHA256:[A-Za-z0-9+/]{43}"))
                throw new IOException("SFTP 主机指纹格式应为 SHA256:…");
            String[] observed = {null};
            JSch ssh = new JSch();
            ssh.setHostKeyRepository(new HostKeyRepository() {
                @Override public int check(String host, byte[] key) {
                    try {
                        String actual = "SHA256:" + android.util.Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(key),
                                android.util.Base64.NO_PADDING | android.util.Base64.NO_WRAP);
                        observed[0] = actual;
                        return expected.isEmpty() ? NOT_INCLUDED : expected.equals(actual) ? OK : CHANGED;
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
            session.setConfig("StrictHostKeyChecking", "yes");
            session.setConfig("PreferredAuthentications", keyFile == null ? "password,keyboard-interactive" : "publickey");
            try {
                if (keyFile == null) {
                    session.setPassword(password);
                } else {
                    byte[] passphrase = password.isEmpty() ? null : password.getBytes(StandardCharsets.UTF_8);
                    try { ssh.addIdentity("selected-key", keyFile, null, passphrase); }
                    catch (JSchException error) { throw new IOException("无法读取 SSH 私钥，请检查私钥格式和口令", error); }
                    finally { if (passphrase != null) Arrays.fill(passphrase, (byte) 0); }
                }
                try { session.connect(10_000); }
                catch (JSchException error) { throw loginFailure(error, keyFile != null); }
                session.setServerAliveInterval(15_000);
                session.setServerAliveCountMax(2);
                session.setTimeout(15_000);
                channel = (ChannelSftp) session.openChannel("sftp");
                try { channel.connect(10_000); }
                catch (JSchException error) {
                    throw new IOException("SSH 登录成功，但无法打开 SFTP 文件服务。请检查服务器是否为此账号启用了 SFTP。", error);
                }
                root = channel.realpath(endpoint.getPath().isEmpty() ? "." : endpoint.getPath());
            } catch (Exception error) {
                session.disconnect();
                if (error instanceof JSchException && observed[0] != null && !expected.equals(observed[0])) {
                    if (expected.isEmpty()) throw new HostKeyRequired(observed[0]);
                    throw new JSchException("SFTP 主机指纹与记录不一致，请通过服务器控制台核对", error);
                }
                throw error;
            } finally { try { ssh.removeAllIdentity(); } catch (JSchException ignored) { } }
        }
        private static IOException loginFailure(JSchException error, boolean usingKey) throws JSchException {
            String message = Objects.toString(error.getMessage(), "");
            if (!message.startsWith("Auth fail")) throw error;
            String prefix = "Auth fail for methods '";
            if (!usingKey && message.startsWith(prefix) && message.endsWith("'")) {
                List<String> methods = Arrays.asList(message.substring(prefix.length(), message.length() - 1).split(","));
                if (methods.equals(List.of("publickey")))
                    return new IOException("该 SSH 账号只允许公钥登录。请选择私钥，或请服务器管理员为账号配置密码登录。", error);
            }
            return new IOException(usingKey ? "SSH 私钥登录失败，请核对用户名、私钥口令及服务器是否已授权对应公钥。"
                    : "SSH 密码登录失败，请核对用户名、密码及服务器是否允许密码登录。", error);
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
        private void requireAbsent(String target) throws Exception {
            try {
                channel.lstat(target);
                throw new IOException("同名远程文件已存在");
            } catch (SftpException missing) {
                if (missing.id != ChannelSftp.SSH_FX_NO_SUCH_FILE) throw missing;
            }
        }
        private boolean linkOrRename(String source, String target) throws Exception {
            Tasks.Progress.check();
            requireAbsent(target);
            if ("1".equals(channel.getExtension("posix-rename@openssh.com"))) {
                if (!"1".equals(channel.getExtension("hardlink@openssh.com")))
                    throw new IOException("此 SFTP 服务器不支持安全发布同名文件");
                channel.hardlink(source, target);
                return true;
            }
            channel.rename(source, target);
            return false;
        }
        @Override public void upload(List<String> path, InputStream source, long size) throws Exception {
            String target = globSafe(path(path));
            requireAbsent(target);
            String temporaryName = ".dualstack-upload-" + UUID.randomUUID();
            List<String> parent = path.subList(0, path.size() - 1);
            String temporary = globSafe(path(child(parent, temporaryName)));
            try {
                try (OutputStream output = channel.put(temporary)) { FileStore.pump(source, output, size); }
                linkOrRename(temporary, target);
            } finally { try { channel.rm(temporary); } catch (SftpException ignored) { } }
        }
        @Override public void mkdir(List<String> path) throws Exception { channel.mkdir(path(path)); }
        @Override public void renameFile(List<String> path, List<String> target) throws Exception {
            String before = globSafe(path(path));
            if (!channel.lstat(before).isReg()) throw new IOException("只能重命名普通文件");
            if (linkOrRename(before, globSafe(path(target)))) {
                try { channel.rm(before); }
                catch (SftpException error) { throw new IOException("新名称已创建，原文件未能移除，请刷新目录", error); }
            }
        }
        @Override public void deleteFile(List<String> path) throws Exception {
            String target = globSafe(path(path));
            if (!channel.lstat(target).isReg()) throw new IOException("只能删除普通文件");
            channel.rm(target);
        }
        @Override public void close() { channel.disconnect(); session.disconnect(); }
    }

    final class Smb implements RemoteStorage {
        private record FileVersion(long size, long created, long modified) { }
        private final jcifs.CIFSContext context;
        private final String hostAddress;
        private final List<String> base;
        Smb(URI endpoint, String user, String password) throws Exception {
            hostAddress = "smb://" + endpoint.getRawAuthority() + "/";
            List<String> root = new ArrayList<>();
            for (String name : endpoint.getRawPath().split("/")) {
                if (!name.isEmpty()) root.add(component(android.net.Uri.decode(name)));
            }
            base = List.copyOf(root);
            Properties properties = new Properties();
            properties.setProperty("jcifs.smb.client.minVersion", "SMB202");
            properties.setProperty("jcifs.smb.client.maxVersion", "SMB311");
            properties.setProperty("jcifs.smb.client.connTimeout", "10000");
            properties.setProperty("jcifs.smb.client.soTimeout", "15000");
            properties.setProperty("jcifs.smb.client.responseTimeout", "15000");
            // Some SMB2 servers cannot decode a compound open/close when the open returns an error.
            properties.setProperty("jcifs.smb.client.disallowCompound",
                    "Smb2SessionSetupRequest,Smb2TreeConnectRequest,Smb2CreateRequest,Smb2CloseRequest,Smb2SetInfoRequest");
            BaseContext common = new BaseContext(new PropertyConfiguration(properties));
            String[] account = user.split("\\\\", 2);
            context = common.withCredentials(new NtlmPasswordAuthenticator(account.length == 2 ? account[0] : "",
                    account[account.length - 1], password));
        }
        private SmbFile file(List<String> path, boolean directory) throws Exception {
            List<String> parts = new ArrayList<>(base);
            for (String segment : path) parts.add(component(segment));
            if (parts.isEmpty()) return new SmbFile(hostAddress, context);
            String relative = String.join("/", parts) + (directory ? "/" : "");
            // jcifs-ng treats segments passed in a full URL as literal. Resolve from the
            // parent so spaces, Unicode, percent signs and hashes reach the SMB path intact.
            try (SmbFile host = new SmbFile(hostAddress, context)) {
                return new SmbFile(host, relative);
            }
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
        @Override public void upload(List<String> path, InputStream source, long size) throws Exception {
            List<String> parent = path.subList(0, path.size() - 1);
            List<String> staged = child(parent, ".dualstack-upload-" + UUID.randomUUID());
            try (SmbFile target = file(path, false);
                 SmbFile temporary = file(staged, false)) {
                if (target.exists()) throw new IOException("同名远程文件已存在");
                temporary.createNewFile();
                try {
                    try (OutputStream out = temporary.openOutputStream()) { FileStore.pump(source, out, size); }
                    Tasks.Progress.check();
                    rename(staged, path, temporary, target);
                } catch (Exception error) {
                    try { if (temporary.exists()) temporary.delete(); }
                    catch (Exception cleanup) { error.addSuppressed(cleanup); }
                    throw error;
                }
            }
        }
        @Override public void mkdir(List<String> path) throws Exception {
            try (SmbFile folder = file(path, true)) { folder.mkdir(); }
        }
        @Override public void renameFile(List<String> path, List<String> target) throws Exception {
            try (SmbFile source = file(path, false); SmbFile destination = file(target, false)) {
                if (!source.isFile()) throw new IOException("只能重命名普通文件");
                if (destination.exists()) throw new IOException("同名远程文件已存在");
                rename(path, target, source, destination);
            }
        }
        private FileVersion version(List<String> path) throws Exception {
            try (SmbFile item = file(path, false)) {
                return item.isFile() ? new FileVersion(item.length(), item.createTime(), item.lastModified()) : null;
            }
        }
        private void rename(List<String> before, List<String> after, SmbFile source, SmbFile target) throws Exception {
            FileVersion original = version(before);
            if (original == null) throw new IOException("服务器上的来源文件已变化，请刷新目录");
            try { source.renameTo(target, false); }
            catch (SmbException error) {
                // An SMB server can apply SET_INFO successfully, then report the old name
                // as missing when closing its handle. Confirm the outcome with fresh handles.
                if (error.getNtStatus() != NtStatus.NT_STATUS_OBJECT_NAME_NOT_FOUND
                        && error.getNtStatus() != NtStatus.NT_STATUS_NO_SUCH_FILE) throw error;
                try (SmbFile old = file(before, false)) {
                    if (!old.exists() && original.equals(version(after))) return;
                } catch (Exception verify) { error.addSuppressed(verify); }
                throw error;
            }
        }
        @Override public void deleteFile(List<String> path) throws Exception {
            try (SmbFile item = file(path, false)) {
                if (!item.isFile()) throw new IOException("只能删除普通文件");
                item.delete();
            }
        }
        @Override public void close() throws Exception { context.close(); }
    }
}
