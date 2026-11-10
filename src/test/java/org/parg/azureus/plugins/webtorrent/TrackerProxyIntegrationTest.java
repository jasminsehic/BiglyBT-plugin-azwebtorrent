package org.parg.azureus.plugins.webtorrent;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import jakarta.websocket.RemoteEndpoint;
import java.util.Map;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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

/**
 * Exercises the real TrackerProxy with a minimal fake WebRTC host and an in-process
 * WebSocket tracker. No external tracker, BiglyBT installation or native WebRTC
 * library is needed. The BiglyBT API classes are provided by the test classpath.
 */
class TrackerProxyIntegrationTest {
    private static final String HASH_A = "ABCDEFGHIJKLMNOPQRST";
    private static final String HASH_B = "QRSTUVWXYZABCDEFGHIJ";
    private static final String PEER_ID = "-MOCK01-123456789012";
    private static final String OFFER_ID = "0123456789abcdefghij";

    private String previousReadTimeout;
    private Server server;
    private MockTracker tracker;
    private TrackerProxy proxy;
    private MockHost host;
    private int port;

    @BeforeEach
    void startServer() throws Exception {
        previousReadTimeout = System.getProperty("sun.net.client.defaultReadTimeout");
        System.setProperty("sun.net.client.defaultReadTimeout", "30000");
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        tracker = new MockTracker();
        MockEndpoint.current.set(tracker);
        server = new Server("127.0.0.1", port, "/", null, MockEndpoint.class);
        server.start();
        host = new MockHost();
        proxy = new TrackerProxy(null, host);
    }

    @AfterEach
    void stopServer() {
        if (proxy != null) proxy.destroy();
        if (server != null) server.stop();
        MockEndpoint.current.set(null);
        if (previousReadTimeout == null)
            System.clearProperty("sun.net.client.defaultReadTimeout");
        else
            System.setProperty("sun.net.client.defaultReadTimeout", previousReadTimeout);
    }

    private String request(String hash, int numwant) throws Exception {
        return requestTo("ws://127.0.0.1:" + port + "/tracker", hash, numwant);
    }

    private String requestTo(String trackerUrl, String hash, int numwant) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String url = trackerUrl
            + "?numwant=" + numwant + "&uploaded=0&downloaded=0&left=1"
            + "&event=started&info_hash=" + hash + "&peer_id=" + PEER_ID;
        proxy.handleRequest(url, out);
        String response = new String(out.toByteArray(), StandardCharsets.ISO_8859_1);
        assertTrue(response.startsWith("HTTP/1.1 200 OK"), response);
        return response;
    }

    private static void await(Condition condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.met() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(condition.met(), "Timed out waiting for local tracker state");
    }

    private interface Condition { boolean met(); }

    private Map<?, ?> clientSessionMap() throws Exception {
        Field field = TrackerProxy.class.getDeclaredField("client_sessions");
        field.setAccessible(true);
        return (Map<?, ?>) field.get(proxy);
    }

    @Test @Timeout(20)
    void announcesToLocalTrackerAndReusesOneSocket() throws Exception {
        assertFalse(request(HASH_A, 0).contains("failure reason"));
        assertFalse(request(HASH_B, 0).contains("failure reason"));
        assertEquals(1, tracker.connections.get());
        assertEquals(2, tracker.received.size());
        assertEquals(HASH_A, tracker.received.get(0).get("info_hash"));
        assertEquals(HASH_B, tracker.received.get(1).get("info_hash"));
    }

    @Test @Timeout(20)
    void fakeHostProducesWebTorrentOffersAndReceivesAnswers() throws Exception {
        assertFalse(request(HASH_A, 1).contains("failure reason"));
        assertEquals(1, host.offersRequested.get());
        assertEquals(1, tracker.received.size());
        JSONArray offers = (JSONArray) tracker.received.get(0).get("offers");
        assertNotNull(offers);
        assertEquals(1, offers.size());
        assertEquals(OFFER_ID, ((JSONObject) offers.get(0)).get("offer_id"));
        await(() -> host.answersReceived.get() == 1);
    }

    @Test @Timeout(20)
    void reconnectReplaysRegistrationsWithoutGeneratingExtraOffers() throws Exception {
        assertFalse(request(HASH_A, 1).contains("failure reason"));
        assertFalse(request(HASH_B, 1).contains("failure reason"));
        assertEquals(2, host.offersRequested.get());
        tracker.closeConnections();
        await(() -> tracker.connectionsClosed.get() >= 1);
        // The server-side close must reach the client endpoint before reusing the URL.
        Map<?, ?> sessions = clientSessionMap();
        await(() -> { synchronized (sessions) { return sessions.isEmpty(); } });
        String reopened = request(HASH_A, 0);
        assertFalse(reopened.contains("failure reason"), reopened);
        await(() -> tracker.connections.get() == 2 && tracker.received.size() >= 4);
        List<JSONObject> replayed = new ArrayList<>(tracker.received.subList(2, tracker.received.size()));
        assertTrue(replayed.stream().anyMatch(m -> HASH_A.equals(m.get("info_hash"))));
        assertTrue(replayed.stream().anyMatch(m -> HASH_B.equals(m.get("info_hash"))));
        assertEquals(2, host.offersRequested.get(), "Reconnect must not create new WebRTC offers");
        assertTrue(replayed.stream().allMatch(m -> ((JSONArray)m.get("offers")).isEmpty()));
    }

    @Test @Timeout(20)
    void trackerFailureUnblocksAndIsReturnedToCaller() throws Exception {
        tracker.reject = true;
        String response = request(HASH_A, 0);
        assertTrue(response.contains("failure reason"), response);
        assertTrue(response.contains("simulated rejection"), response);
        assertEquals(1, tracker.received.size());
    }

    @Test @Timeout(20)
    void concurrentAnnouncesShareAConnection() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<String> first = pool.submit(() -> { start.await(); return request(HASH_A, 0); });
            Future<String> second = pool.submit(() -> { start.await(); return request(HASH_B, 0); });
            start.countDown();
            assertFalse(first.get(12, TimeUnit.SECONDS).contains("failure reason"));
            assertFalse(second.get(12, TimeUnit.SECONDS).contains("failure reason"));
            assertEquals(1, tracker.connections.get(), "Only one concurrent WS handshake expected");
            // During a concurrent connect one request may also be replayed from the
            // registration registry: assert identity, not an exact message count.
            assertTrue(tracker.received.size() >= 2);
            assertTrue(tracker.received.stream().anyMatch(m -> HASH_A.equals(m.get("info_hash"))));
            assertTrue(tracker.received.stream().anyMatch(m -> HASH_B.equals(m.get("info_hash"))));
        } finally {
            pool.shutdownNow();
        }
    }

    private void useFastFailureTimeouts() {
        proxy.destroy();
        System.setProperty("sun.net.client.defaultReadTimeout", "6000");
        proxy = new TrackerProxy(null, host, 100, 250, 120, 6000);
    }

    @Test @Timeout(20)
    void silentAnnounceTimesOutAndRecoversOnFreshConnection() throws Exception {
        useFastFailureTimeouts();
        tracker.silent = true;
        String failed = request(HASH_A, 0);
        assertTrue(failed.contains("failure reason"), failed);
        assertTrue(failed.contains("timed out"), failed);
        Map<?, ?> sessions = clientSessionMap();
        synchronized (sessions) {
            assertTrue(sessions.isEmpty(), "Timed-out session must be evicted");
        }
        tracker.silent = false;
        assertFalse(request(HASH_A, 0).contains("failure reason"));
        assertEquals(2, tracker.connections.get(), "Retry must create a new connection");
    }

    @Test @Timeout(20)
    void unansweredPingInvalidatesOpenSocketAndAllowsReconnect() throws Exception {
        useFastFailureTimeouts();
        try (RawNoPongWebSocketTracker raw = new RawNoPongWebSocketTracker()) {
            String url = raw.url();
            assertFalse(requestTo(url, HASH_A, 0).contains("failure reason"));
            await(() -> raw.pings.get() >= 1);
            Map<?, ?> sessions = clientSessionMap();
            await(() -> { synchronized (sessions) { return sessions.isEmpty(); } });
            await(() -> raw.closed.get() >= 1);
            assertFalse(requestTo(url, HASH_B, 0).contains("failure reason"));
            assertEquals(2, raw.connections.get(), "Next announce must reconnect");
        }
    }

    @Test @Timeout(20)
    void blockedAsyncWriteReturnsFailureThenNextAnnounceRecovers() throws Exception {
        useFastFailureTimeouts();
        String url = "ws://127.0.0.1:" + port + "/tracker";
        AtomicBoolean closed = new AtomicBoolean();
        CompletableFuture<Void> blocked = new CompletableFuture<>();
        RemoteEndpoint.Async async = (RemoteEndpoint.Async) Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[]{RemoteEndpoint.Async.class},
            (p, method, args) -> method.getName().equals("sendText") ? blocked : null);
        Session socket = (Session) Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[]{Session.class},
            (p, method, args) -> {
                if (method.getName().equals("getAsyncRemote")) return async;
                if (method.getName().equals("isOpen")) return !closed.get();
                if (method.getName().equals("close")) { closed.set(true); return null; }
                return null;
            });
        Class<?> clientSession = Class.forName(TrackerProxy.class.getName() + "$ClientSession");
        Constructor<?> constructor = clientSession.getDeclaredConstructor(
            TrackerProxy.class, Session.class, String.class);
        constructor.setAccessible(true);
        Object stalled = constructor.newInstance(proxy, socket, url);
        @SuppressWarnings("unchecked")
        Map<String, Object> sessions = (Map<String, Object>) clientSessionMap();
        synchronized (sessions) { sessions.put(url, stalled); }

        String failure = request(HASH_A, 0);
        assertTrue(failure.contains("failure reason"), failure);
        assertTrue(failure.contains("send timed out"), failure);
        assertTrue(closed.get(), "Timed-out write must close socket");
        assertTrue(blocked.isCancelled(), "Pending write must be cancelled");
        assertFalse(request(HASH_B, 0).contains("failure reason"),
            "Subsequent announce must not reuse the stalled socket");
    }

    @Test @Timeout(30)
    void wssHandshakeAgainstNonTlsTrackerFailsWithoutHanging() throws Exception {
        useFastFailureTimeouts();
        String failure = requestTo("wss://127.0.0.1:" + port + "/tracker", HASH_A, 0);
        assertTrue(failure.contains("failure reason"), failure);
        Map<?, ?> sessions = clientSessionMap();
        synchronized (sessions) {
            assertTrue(sessions.isEmpty(), "TLS handshake failure must not register a session");
        }
        assertFalse(request(HASH_B, 0).contains("failure reason"),
            "An unrelated plain WebSocket tracker must still work");
    }

    @Test @Timeout(20)
    void concurrentDisconnectAndShutdownReleasesWaitingAnnounces() throws Exception {
        useFastFailureTimeouts();
        tracker.silent = true;
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            Future<String> first = pool.submit(() -> request(HASH_A, 0));
            await(() -> tracker.received.size() == 1);
            Future<String> second = pool.submit(() -> request(HASH_B, 0));
            await(() -> tracker.received.size() >= 2);
            Future<?> close = pool.submit(() -> { tracker.closeConnections(); return null; });
            Future<?> shutdown = pool.submit(() -> { proxy.destroy(); return null; });
            close.get(5, TimeUnit.SECONDS);
            shutdown.get(5, TimeUnit.SECONDS);
            assertTrue(first.get(5, TimeUnit.SECONDS).contains("failure reason"));
            assertTrue(second.get(5, TimeUnit.SECONDS).contains("failure reason"));
            Map<?, ?> sessions = clientSessionMap();
            synchronized (sessions) {
                assertTrue(sessions.isEmpty(), "Shutdown must clear every tracked socket");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @ServerEndpoint("/tracker")
    public static class MockEndpoint {
        static final AtomicReference<MockTracker> current = new AtomicReference<>();

        @OnOpen
        public void open(Session session) {
            MockTracker t = current.get();
            if (t != null) {
                t.connections.incrementAndGet();
                t.sessions.add(session);
            }
        }

        @OnClose
        public void close(Session session) {
            MockTracker t = current.get();
            if (t != null) {
                t.sessions.remove(session);
                t.connectionsClosed.incrementAndGet();
            }
        }

        @OnMessage
        public void onMessage(String message, Session session) throws Exception {
            MockTracker t = current.get();
            JSONObject request = (JSONObject) new JSONParser().parse(new java.io.StringReader(message));
            t.received.add(request);
            if (t.silent) return;
            String hash = (String) request.get("info_hash");
            JSONObject response = new JSONObject();
            response.put("info_hash", hash);
            if (t.reject) {
                response.put("failure reason", "simulated rejection");
            } else {
                response.put("complete", 1);
                response.put("incomplete", 0);
                response.put("interval", 125);
            }
            session.getBasicRemote().sendText(com.biglybt.util.JSONUtils.encodeToJSON(response));
            JSONArray offers = (JSONArray) request.get("offers");
            if (!t.reject && offers != null && !offers.isEmpty()) {
                JSONObject first = (JSONObject) offers.get(0);
                JSONObject answer = new JSONObject();
                answer.put("type", "answer");
                answer.put("sdp", "v=0\\r\\n");
                JSONObject envelope = new JSONObject();
                envelope.put("answer", answer);
                envelope.put("info_hash", hash);
                envelope.put("offer_id", first.get("offer_id"));
                session.getBasicRemote().sendText(com.biglybt.util.JSONUtils.encodeToJSON(envelope));
            }
        }
    }

    private static class MockTracker {
        final AtomicInteger connections = new AtomicInteger();
        final AtomicInteger connectionsClosed = new AtomicInteger();
        final List<JSONObject> received = new CopyOnWriteArrayList<>();
        final Set<Session> sessions = ConcurrentHashMap.newKeySet();
        volatile boolean reject;
        volatile boolean silent;

        void closeConnections() throws Exception {
            for (Session session : new ArrayList<>(sessions)) session.close();
        }
    }

    private static class MockHost implements TrackerProxy.Listener {
        final AtomicInteger offersRequested = new AtomicInteger();
        final AtomicInteger answersReceived = new AtomicInteger();

        @Override
        public void getOffer(byte[] hash, long timeout, WebRTCProvider.OfferListener listener) {
            offersRequested.incrementAndGet();
            listener.gotOffer(new WebRTCProvider.Offer() {
                @Override public String getOfferID() { return OFFER_ID; }
                @Override public String getSDP() { return "v=0\\r\\n"; }
            });
        }

        @Override
        public void gotAnswer(byte[] hash, String offerId, String sdp) {
            if (OFFER_ID.equals(offerId)) answersReceived.incrementAndGet();
        }

        @Override
        public void gotOffer(byte[] hash, String offerId, String sdp,
                WebRTCProvider.AnswerListener listener) {
            listener.gotAnswer(new WebRTCProvider.Answer() {
                @Override public String getOfferID() { return offerId; }
                @Override public String getSDP() { return "v=0\\r\\n"; }
            });
        }
    }
}
