package org.example.filemanager;

import org.apache.commons.net.DefaultSocketFactory;
import org.apache.commons.net.MalformedServerReplyException;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Password-based FTP with passive data connections. One worker uses the session at a time. */
final class RemoteFtp implements RemoteStorage {
    private static final int TIMEOUT = 10_000;
    private final URI endpoint;
    private final String user;
    private final String password;
    private final String initialDirectory;
    private Session session;
    private boolean closed;

    private static final class Session extends DefaultSocketFactory {
        final FTPClient client = new FTPClient();
        private Socket control, data;
        private boolean stopped;
        String root;
        boolean machineListing;

        @Override public synchronized Socket createSocket() throws IOException {
            if (stopped) throw new IOException("FTP 连接已关闭");
            Socket socket = super.createSocket();
            if (control == null) control = socket;
            else data = socket;
            return socket;
        }

        synchronized boolean stopped() { return stopped; }

        synchronized void stop() {
            stopped = true;
            closeSocket(data);
            closeSocket(control);
        }

        private static void closeSocket(Socket socket) {
            if (socket != null) try { socket.close(); } catch (IOException ignored) { }
        }
    }

    RemoteFtp(URI endpoint, String user, String password) throws IOException {
        this.endpoint = endpoint;
        if (user.isBlank()) throw new IOException("请填写 FTP 用户名");
        this.user = argument(user);
        this.password = argument(password);
        List<String> segments = new ArrayList<>();
        for (String part : endpoint.getRawPath().split("/")) {
            if (!part.isEmpty()) {
                String decoded = URI.create("ftp://localhost/" + part).getPath().substring(1);
                segments.add(name(decoded));
            }
        }
        initialDirectory = segments.isEmpty() && endpoint.getRawPath().isEmpty()
                ? "" : "/" + String.join("/", segments);
        openSession();
    }

    private static String argument(String value) throws IOException {
        if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\0') >= 0)
            throw new IOException("FTP 参数不能包含换行或空字符");
        return value;
    }

    private static String name(String value) throws IOException {
        return argument(RemoteStorage.component(value));
    }

    private Session openSession() throws IOException {
        Session next;
        synchronized (this) {
            if (closed) throw new IOException("FTP 连接已关闭");
            if (session != null && !session.stopped()) return session;
            next = new Session();
            session = next;
        }
        FTPClient client = next.client;
        int port = endpoint.getPort() < 0 ? FTP.DEFAULT_PORT : endpoint.getPort();
        try {
            client.setSocketFactory(next);
            client.setControlEncoding(StandardCharsets.UTF_8.name());
            client.setConnectTimeout(TIMEOUT);
            client.setDefaultTimeout(TIMEOUT);
            client.setDataTimeout(Duration.ofMillis(TIMEOUT));
            client.setUseEPSVwithIPv4(true);
            client.setIpAddressFromPasvResponse(false);
            try {
                client.connect(endpoint.getHost(), port);
            } catch (MalformedServerReplyException invalidGreeting) {
                String message = invalidGreeting.getMessage();
                if (message != null && message.contains("\nServer Reply: SSH-"))
                    throw new RemoteStorage.SshServiceDetected(port);
                throw new IOException("端口 " + port + " 未返回有效的 FTP 握手，请核对协议和端口。");
            }
            require(FTPReply.isPositiveCompletion(client.getReplyCode()), client, "握手");
            require(client.login(user, password), client, "登录");
            client.enterLocalPassiveMode();
            require(client.setFileType(FTP.BINARY_FILE_TYPE), client, "启用二进制传输");
            if (!initialDirectory.isEmpty())
                require(client.changeWorkingDirectory(initialDirectory), client, "打开初始目录");
            String root = client.printWorkingDirectory();
            require(root != null && root.startsWith("/"), client, "读取起始目录");
            next.root = argument(root);
            next.machineListing = client.hasFeature("MLST") || client.hasFeature("MLSD");
            return next;
        } catch (IOException | RuntimeException failure) {
            next.stop();
            if (failure instanceof ConnectException)
                throw new IOException("FTP 连接被拒绝，请检查服务器是否启用 FTP 并监听端口 " + port, failure);
            if (failure instanceof SocketTimeoutException)
                throw new IOException("FTP 连接超时，请检查服务器地址、端口和防火墙", failure);
            if (failure instanceof UnknownHostException)
                throw new IOException("无法解析 FTP 服务器地址", failure);
            throw failure;
        }
    }

    private static String path(Session session, List<String> parts) throws IOException {
        StringBuilder result = new StringBuilder(session.root);
        for (String part : parts) {
            if (result.charAt(result.length() - 1) != '/') result.append('/');
            result.append(name(part));
        }
        return result.toString();
    }

    private static List<String> parent(List<String> parts) throws IOException {
        if (parts.isEmpty()) throw new IOException("请选择远程文件");
        return parts.subList(0, parts.size() - 1);
    }

    private static void require(boolean ok, FTPClient client, String action) throws IOException {
        if (ok) return;
        int code = client.getReplyCode();
        String detail = switch (code) {
            case 425, 426 -> "，请检查服务器被动模式端口范围和防火墙";
            case 530 -> "，请核对 FTP 用户名、密码和登录权限";
            case 550 -> "，路径不存在、无权限或服务器拒绝此操作";
            default -> "";
        };
        throw new IOException("FTP " + action + "失败（" + code + "）" + detail);
    }

    private FTPFile[] directory(Session session, List<String> parts) throws IOException {
        FTPClient client = session.client;
        require(client.changeWorkingDirectory(path(session, parts)), client, "打开目录");
        if (session.machineListing) {
            FTPFile[] files = client.mlistDir();
            int reply = client.getReplyCode();
            if (FTPReply.isPositiveCompletion(reply)) return files;
            if (reply != 500 && reply != 501 && reply != 502 && reply != 504)
                require(false, client, "读取目录");
            session.machineListing = false;
        }
        FTPFile[] files = client.listFiles();
        require(FTPReply.isPositiveCompletion(client.getReplyCode()), client, "读取目录");
        return files;
    }

    private interface Operation<T> {
        T run(Session session) throws IOException;
    }

    private <T> T perform(Operation<T> operation) throws IOException {
        Tasks.Progress.check();
        Session current = openSession();
        try {
            return operation.run(current);
        } catch (IOException | RuntimeException failure) {
            // A failed data command may leave an unread reply on the control connection.
            current.stop();
            Tasks.Progress.check();
            if (failure instanceof SocketTimeoutException)
                throw new IOException("FTP 操作超时，请检查服务器的被动模式和网络", failure);
            throw failure;
        }
    }

    @Override public List<Entry> list(List<String> parts) throws IOException {
        return perform(current -> {
            List<Entry> result = new ArrayList<>();
            for (FTPFile item : directory(current, parts)) {
                Tasks.Progress.check();
                if (item == null || (!item.isFile() && !item.isDirectory())) continue;
                try {
                    result.add(new Entry(name(item.getName()), item.isDirectory(), item.getSize()));
                } catch (IOException invalidName) { }
            }
            return RemoteStorage.sorted(result);
        });
    }

    private FTPFile find(Session current, List<String> parts) throws IOException {
        List<String> folder = parent(parts);
        if (current.machineListing) {
            FTPFile stat = current.client.mlistFile(path(current, parts));
            if (stat != null) return stat;
        }
        String leaf = name(parts.get(parts.size() - 1));
        for (FTPFile item : directory(current, folder)) {
            if (item != null && leaf.equals(item.getName())) return item;
        }
        return null;
    }

    private void requireFile(Session current, List<String> parts) throws IOException {
        FTPFile item = find(current, parts);
        if (item == null || !item.isFile()) throw new IOException("远程普通文件不存在或无法访问");
    }

    private void requireAbsent(Session current, List<String> parts) throws IOException {
        String target = path(current, parts);
        if (find(current, parts) != null || current.client.getSize(target) != null)
            throw new IOException("同名远程文件已存在");
    }

    @Override public void download(List<String> parts, OutputStream target) throws IOException {
        perform(current -> {
            requireFile(current, parts);
            FTPClient client = current.client;
            try (InputStream input = client.retrieveFileStream(path(current, parts))) {
                require(input != null, client, "打开下载");
                FileStore.pump(input, target);
            }
            require(client.completePendingCommand(), client, "完成下载");
            return null;
        });
    }

    @Override public void upload(List<String> parts, InputStream source, long size) throws IOException {
        List<String> temporary = RemoteStorage.child(parent(parts), ".dualstack-upload-" + UUID.randomUUID());
        boolean[] started = {false};
        try {
            perform(current -> {
                requireAbsent(current, parts);
                FTPClient client = current.client;
                String staged = path(current, temporary);
                started[0] = true;
                try (OutputStream output = client.storeFileStream(staged)) {
                    require(output != null, client, "打开上传");
                    FileStore.pump(source, output, size);
                }
                require(client.completePendingCommand(), client, "完成上传");
                requireAbsent(current, parts);
                Tasks.Progress.check();
                require(client.rename(staged, path(current, parts)), client, "发布文件");
                return null;
            });
        } catch (IOException | RuntimeException failure) {
            if (started[0]) cleanTemporary(temporary, failure);
            throw failure;
        }
    }

    private void cleanTemporary(List<String> temporary, Exception failure) {
        try {
            Session current = openSession();
            current.client.deleteFile(path(current, temporary));
        } catch (IOException | RuntimeException cleanup) {
            failure.addSuppressed(cleanup);
        }
    }

    @Override public void mkdir(List<String> parts) throws IOException {
        perform(current -> {
            require(current.client.makeDirectory(path(current, parts)), current.client, "创建目录");
            return null;
        });
    }

    @Override public void renameFile(List<String> parts, List<String> target) throws IOException {
        perform(current -> {
            requireFile(current, parts);
            requireAbsent(current, target);
            require(current.client.rename(path(current, parts), path(current, target)), current.client, "重命名文件");
            return null;
        });
    }

    @Override public void deleteFile(List<String> parts) throws IOException {
        perform(current -> {
            requireFile(current, parts);
            require(current.client.deleteFile(path(current, parts)), current.client, "删除文件");
            return null;
        });
    }

    synchronized void cancelTransfer() {
        if (session != null) session.stop();
    }

    @Override public synchronized void close() {
        closed = true;
        cancelTransfer();
    }
}
