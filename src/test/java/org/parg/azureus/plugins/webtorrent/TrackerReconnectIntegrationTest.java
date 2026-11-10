package org.parg.azureus.plugins.webtorrent;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayOutputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;
import org.glassfish.tyrus.server.Server;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class TrackerReconnectIntegrationTest {
    private static final String HASH_A = "ABCDEFGHIJKLMNOPQRST";
    private static final String HASH_B = "QRSTUVWXYZABCDEFGHIJ";
    private static final String PEER_ID = "-MOCK01-123456789012";
    private static final String OFFER_ID = "0123456789abcdefghij";

    private int port;
    private Server server;
    private TrackerProxy proxy;
    private MockHost host;
    private String oldTimeout;
    private final MockTracker tracker = new MockTracker();

    @BeforeEach void start() throws Exception {
        oldTimeout = System.getProperty("sun.net.client.defaultReadTimeout");
        System.setProperty("sun.net.client.defaultReadTimeout", "30000");
        try (ServerSocket portSocket = new ServerSocket(0)) { port = portSocket.getLocalPort(); }
        Endpoint.current = tracker;
        server = new Server("127.0.0.1", port, "/", null, Endpoint.class);
        server.start();
        host = new MockHost();
        proxy = new TrackerProxy(null, host);
    }

    @AfterEach void stop() {
        if (proxy != null) proxy.destroy();
        if (server != null) server.stop();
        Endpoint.current = null;
        if (oldTimeout == null) System.clearProperty("sun.net.client.defaultReadTimeout");
        else System.setProperty("sun.net.client.defaultReadTimeout", oldTimeout);
    }

    private String request(String hash, int offers) throws Exception {
        String url = "ws://127.0.0.1:" + port + "/tracker"
            + "?numwant=" + offers + "&uploaded=0&downloaded=0&left=1"
            + "&event=started&info_hash=" + hash + "&peer_id=" + PEER_ID;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        proxy.handleRequest(url, bytes);
        return new String(bytes.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    private static void until(java.util.function.BooleanSupplier ready) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!ready.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(20);
        assertTrue(ready.getAsBoolean(), "Timed out waiting for tracker");
    }

    @Test @Timeout(20)
    void reconnectReplaysOtherTorrentWithoutNewRtcOffers() throws Exception {
        assertFalse(request(HASH_A, 1).contains("failure reason"));
        assertFalse(request(HASH_B, 1).contains("failure reason"));
        assertEquals(2, host.offers.get());
        assertEquals(1, tracker.connections.get());
        assertEquals(2, tracker.messages.size());

        for (Session session : new ArrayList<>(tracker.sessions)) session.close();
        until(() -> tracker.sessions.isEmpty());
        assertFalse(request(HASH_A, 0).contains("failure reason"));
        until(() -> tracker.messages.size() >= 4);
        assertEquals(2, tracker.connections.get());
        assertEquals(2, host.offers.get(), "Replay should not generate more RTC offers");

        List<JSONObject> replay = new ArrayList<>(tracker.messages.subList(2, tracker.messages.size()));
        assertTrue(replay.stream().anyMatch(m -> HASH_A.equals(m.get("info_hash"))));
        assertTrue(replay.stream().anyMatch(m -> HASH_B.equals(m.get("info_hash"))));
        // HASH_A is the explicit triggering request and may legitimately have
        // event=started. The replayed HASH_B announce must be a silent refresh.
        JSONObject restored = replay.stream().filter(m -> HASH_B.equals(m.get("info_hash")))
            .findFirst().orElseThrow(() -> new AssertionError("Replayed HASH_B announce missing"));
        assertNull(restored.get("event"), "Replayed announce must not start a new session");
        assertEquals(0, ((Number) restored.get("numwant")).intValue());
        assertTrue(((JSONArray) restored.get("offers")).isEmpty());
    }

    private static class MockTracker {
        final AtomicInteger connections = new AtomicInteger();
        final CopyOnWriteArrayList<JSONObject> messages = new CopyOnWriteArrayList<>();
        final Set<Session> sessions = ConcurrentHashMap.newKeySet();
    }

    @ServerEndpoint("/tracker")
    public static class Endpoint {
        static volatile MockTracker current;
        @OnOpen public void open(Session session) {
            current.connections.incrementAndGet();
            current.sessions.add(session);
        }
        @OnClose public void close(Session session) { current.sessions.remove(session); }
        @OnMessage public void message(String text, Session session) throws Exception {
            JSONObject json = (JSONObject)new JSONParser()
                .parse(new java.io.StringReader(text));
            current.messages.add(json);
            JSONObject reply = new JSONObject();
            reply.put("info_hash", json.get("info_hash"));
            reply.put("complete", 1);
            reply.put("incomplete", 0);
            reply.put("interval", 125);
            session.getBasicRemote().sendText(com.biglybt.util.JSONUtils.encodeToJSON(reply));
        }
    }

    private static class MockHost implements TrackerProxy.Listener {
        final AtomicInteger offers = new AtomicInteger();
        @Override public void getOffer(byte[] hash, long timeout, WebRTCProvider.OfferListener l) {
            offers.incrementAndGet();
            l.gotOffer(new WebRTCProvider.Offer() {
                @Override public String getOfferID() { return OFFER_ID; }
                @Override public String getSDP() { return "v=0\\r\\n"; }
            });
        }
        @Override public void gotAnswer(byte[] hash, String offerId, String sdp) { }
        @Override public void gotOffer(byte[] hash, String offerId, String sdp,
                WebRTCProvider.AnswerListener l) { l.failed(); }
    }
}
