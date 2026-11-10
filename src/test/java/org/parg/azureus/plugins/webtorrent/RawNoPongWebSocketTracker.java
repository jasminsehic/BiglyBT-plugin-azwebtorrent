package org.parg.azureus.plugins.webtorrent;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.DataInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.SocketException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

/**
 * Minimal RFC6455 loopback tracker, deliberately omitting automatic pong replies.
 * Container-based WebSocket servers send pongs automatically and cannot exercise
 * the missing-pong failure path in TrackerProxy.
 */
final class RawNoPongWebSocketTracker implements AutoCloseable {
    final AtomicInteger connections = new AtomicInteger();
    final AtomicInteger pings = new AtomicInteger();
    final AtomicInteger closed = new AtomicInteger();
    private final ServerSocket listener;
    private final Set<Socket> clients = ConcurrentHashMap.newKeySet();
    private final Set<Thread> clientThreads = ConcurrentHashMap.newKeySet();
    private final AtomicReference<Throwable> asyncFailure = new AtomicReference<>();
    private final Thread acceptThread;
    private volatile boolean running = true;

    RawNoPongWebSocketTracker() throws Exception {
        listener = new ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"));
        acceptThread = new Thread(this::accept, "mock-raw-tracker-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    String url() { return "ws://127.0.0.1:" + listener.getLocalPort() + "/tracker"; }

    private void accept() {
        while (running) {
            try {
                Socket socket = listener.accept();
                clients.add(socket);
                Thread thread = new Thread(() -> {
                    try { serve(socket); }
                    finally { clientThreads.remove(Thread.currentThread()); }
                }, "mock-raw-tracker-peer");
                thread.setDaemon(true);
                clientThreads.add(thread);
                thread.start();
            } catch (Exception e) {
                if (running) asyncFailure.compareAndSet(null, e);
            }
        }
    }

    private void serve(Socket socket) {
        try (Socket connection = socket) {
            connection.setSoTimeout(5000);
            InputStream in = connection.getInputStream();
            OutputStream out = connection.getOutputStream();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            while (bytes.size() < 16384) {
                int next = in.read();
                if (next < 0) return;
                bytes.write(next);
                if (endsWithDoubleNewline(bytes)) break;
            }
            String headers = new String(bytes.toByteArray(), StandardCharsets.ISO_8859_1);
            String key = null;
            for (String line : headers.split("\r\n")) {
                if (line.toLowerCase(java.util.Locale.ROOT).startsWith("sec-websocket-key:")) {
                    key = line.substring(line.indexOf(':') + 1).trim();
                }
            }
            if (key == null) return;
            byte[] digest = MessageDigest.getInstance("SHA-1")
                .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")
                    .getBytes(StandardCharsets.ISO_8859_1));
            String response = "HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + Base64.getEncoder().encodeToString(digest)
                + "\r\n\r\n";
            out.write(response.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            connections.incrementAndGet();

            DataInputStream data = new DataInputStream(in);
            while (running) {
                int first = data.readUnsignedByte();
                int second = data.readUnsignedByte();
                int opcode = first & 0xf;
                long length = second & 127;
                if (length == 126) length = data.readUnsignedShort();
                else if (length == 127) length = data.readLong();
                if (length < 0 || length > 1024 * 1024) return;
                byte[] mask = new byte[4];
                boolean masked = (second & 128) != 0;
                if (masked) data.readFully(mask);
                byte[] payload = new byte[(int) length];
                data.readFully(payload);
                if (masked) for (int i = 0; i < payload.length; ++i)
                    payload[i] ^= mask[i % 4];
                if (opcode == 9) {
                    pings.incrementAndGet(); // Deliberately never reply with a pong.
                } else if (opcode == 1) {
                    JSONObject message = (JSONObject) new JSONParser()
                        .parse(new java.io.StringReader(new String(payload, StandardCharsets.UTF_8)));
                    JSONObject announce = new JSONObject();
                    announce.put("info_hash", message.get("info_hash"));
                    announce.put("complete", 1);
                    announce.put("incomplete", 0);
                    announce.put("interval", 125);
                    byte[] reply = com.biglybt.util.JSONUtils.encodeToJSON(announce)
                        .getBytes(StandardCharsets.UTF_8);
                    out.write(0x81); // FIN + TEXT; unmasked server frame
                    if (reply.length < 126) out.write(reply.length);
                    else {
                        out.write(126);
                        out.write((reply.length >> 8) & 255);
                        out.write(reply.length & 255);
                    }
                    out.write(reply);
                    out.flush();
                } else if (opcode == 8) return;
            }
        } catch (EOFException expected) {
            // An orderly peer disconnect can terminate a partially read frame.
        } catch (SocketException e) {
            // Our own close() shuts down sockets; an unexpected live socket error
            // must still fail JUnit instead of disappearing on a worker thread.
            if (running && !socket.isClosed()) asyncFailure.compareAndSet(null, e);
        } catch (Throwable failure) {
            asyncFailure.compareAndSet(null, failure);
        } finally {
            clients.remove(socket);
            closed.incrementAndGet();
        }
    }

    private static boolean endsWithDoubleNewline(ByteArrayOutputStream bytes) {
        byte[] b = bytes.toByteArray();
        int n = b.length;
        return n >= 4 && b[n - 4] == '\r' && b[n - 3] == '\n'
            && b[n - 2] == '\r' && b[n - 1] == '\n';
    }

    void assertNoAsyncFailures() {
        Throwable failure = asyncFailure.get();
        if (failure != null)
            throw new AssertionError("Unexpected raw WebSocket tracker worker exception", failure);
    }

    @Override public void close() throws Exception {
        running = false;
        try {
            listener.close();
        } finally {
            for (Socket socket : new ArrayList<>(clients)) socket.close();
            acceptThread.join(2000);
            for (Thread worker : new ArrayList<>(clientThreads)) worker.join(2000);
            if (acceptThread.isAlive() || clientThreads.stream().anyMatch(Thread::isAlive))
                throw new AssertionError("Raw WebSocket tracker worker did not terminate");
            assertNoAsyncFailures();
        }
    }
}
