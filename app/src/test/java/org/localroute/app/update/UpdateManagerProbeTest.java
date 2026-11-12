package org.localroute.app.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/** 线路探测只在 JVM 里验证：用本地 HTTP 服务模拟快镜像、慢镜像和 HTML 门户。 */
public class UpdateManagerProbeTest {

    private static final int SAMPLE = 96 * 1024;

    private ServerSocket serverSocket;
    private String base;
    private volatile boolean running;

    @Before
    public void setUp() throws IOException {
        byte[] apk = new byte[SAMPLE];
        apk[0] = 'P';
        apk[1] = 'K';
        byte[] html = "<html><body>镜像门户</body></html>".getBytes(StandardCharsets.UTF_8);

        serverSocket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        base = "http://127.0.0.1:" + serverSocket.getLocalPort();
        running = true;
        Thread acceptor = new Thread(() -> {
            while (running) {
                try {
                    Socket socket = serverSocket.accept();
                    Thread handler = new Thread(() -> serve(socket, apk, html));
                    handler.setDaemon(true);
                    handler.start();
                } catch (IOException error) {
                    return;
                }
            }
        }, "probe-test-server");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    @After
    public void tearDown() {
        running = false;
        try {
            serverSocket.close();
        } catch (IOException ignored) {
            // 测试结束关闭失败不影响结果。
        }
    }

    @Test
    public void picksFastestApkSource() throws IOException {
        List<String> candidates = Arrays.asList(
                base + "/slow", base + "/html", base + "/fast");
        assertEquals(base + "/fast", UpdateManager.chooseFastestSource(candidates));
    }

    @Test
    public void rejectsHtmlPortalWhenNoRealApk() {
        try {
            UpdateManager.chooseFastestSource(Arrays.asList(base + "/html"));
            fail("HTML 门户不应被当成 APK");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("连不上"));
        }
    }

    private static void serve(Socket socket, byte[] apk, byte[] html) {
        try (Socket client = socket) {
            InputStream input = new BufferedInputStream(client.getInputStream());
            String path = readPath(input);
            OutputStream output = client.getOutputStream();
            if (path == null) {
                return;
            }
            if (path.startsWith("/html")) {
                writeResponse(output, "text/html; charset=utf-8", html, 0);
            } else if (path.startsWith("/slow")) {
                writeResponse(output,
                        "application/vnd.android.package-archive", apk, 60);
            } else {
                writeResponse(output,
                        "application/vnd.android.package-archive", apk, 0);
            }
        } catch (IOException ignored) {
            // 客户端提前断开时忽略。
        }
    }

    private static String readPath(InputStream input) throws IOException {
        StringBuilder head = new StringBuilder();
        int state = 0;
        while (state < 4) {
            int value = input.read();
            if (value < 0) {
                return null;
            }
            head.append((char) value);
            switch (state) {
                case 0:
                    state = value == '\r' ? 1 : 0;
                    break;
                case 1:
                    state = value == '\n' ? 2 : 0;
                    break;
                case 2:
                    state = value == '\r' ? 3 : 0;
                    break;
                default:
                    state = value == '\n' ? 4 : 0;
                    break;
            }
        }
        String[] lines = head.toString().split("\r\n");
        if (lines.length == 0) {
            return null;
        }
        String[] parts = lines[0].split(" ");
        return parts.length >= 2 ? parts[1] : null;
    }

    private static void writeResponse(OutputStream output, String type, byte[] body,
                                      long delayMs) throws IOException {
        String header = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        output.write(header.getBytes(StandardCharsets.US_ASCII));
        int chunk = 16 * 1024;
        for (int index = 0; index < body.length; index += chunk) {
            output.write(body, index, Math.min(chunk, body.length - index));
            output.flush();
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }
}
