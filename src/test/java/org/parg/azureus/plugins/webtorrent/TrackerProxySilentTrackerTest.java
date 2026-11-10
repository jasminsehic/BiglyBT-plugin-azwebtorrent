package org.parg.azureus.plugins.webtorrent;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayOutputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;
import org.glassfish.tyrus.server.Server;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Proves silent tracker timeouts release sockets and allow clean recovery. */
class TrackerProxySilentTrackerTest {
    private Server server;
    private TrackerProxy proxy;
    private int port;
    private String previous;

    @BeforeEach void open() throws Exception {
        previous = System.getProperty("sun.net.client.defaultReadTimeout");
        System.setProperty("sun.net.client.defaultReadTimeout", "6000");
        try (ServerSocket available = new ServerSocket(0)) { port = available.getLocalPort(); }
        Endpoint.silent = true;
        Endpoint.connections.set(0);
        server = new Server("127.0.0.1", port, "/", null, Endpoint.class);
        server.start();
        proxy = new TrackerProxy(null, new NoOffers(), 6000);
    }

    @AfterEach void close() {
        if (proxy != null) proxy.destroy();
        if (server != null) server.stop();
        if (previous == null) System.clearProperty("sun.net.client.defaultReadTimeout");
        else System.setProperty("sun.net.client.defaultReadTimeout", previous);
    }

    private String announce() throws Exception {
        String url = "ws://127.0.0.1:" + port + "/tracker"
            + "?numwant=0&uploaded=0&downloaded=0&left=1&event=started"
            + "&info_hash=ABCDEFGHIJKLMNOPQRST&peer_id=-MOCK01-123456789012";
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        proxy.handleRequest(url, bytes);
        return bytes.toString(StandardCharsets.ISO_8859_1);
    }

    @Test @Timeout(15)
    void missingAnnounceResponseEvictsSocketAndNextAttemptRecovers() throws Exception {
        String failure = announce();
        assertTrue(failure.contains("failure reason"), failure);
        assertEquals(1, Endpoint.connections.get());
        Endpoint.silent = false;
        assertFalse(announce().contains("failure reason"));
        assertEquals(2, Endpoint.connections.get(),
            "The timed-out connection must not be reused");
    }

    @ServerEndpoint("/tracker")
    public static class Endpoint {
        static volatile boolean silent;
        static final AtomicInteger connections = new AtomicInteger();
        @OnOpen public void onOpen(Session session) { connections.incrementAndGet(); }

        @OnMessage public void onMessage(String payload, Session session) throws Exception {
            if (silent) return;
            JSONObject request = (JSONObject) new JSONParser()
                .parse(new java.io.StringReader(payload));
            JSONObject response = new JSONObject();
            response.put("info_hash", request.get("info_hash"));
            response.put("complete", 1);
            response.put("incomplete", 0);
            response.put("interval", 125);
            session.getBasicRemote().sendText(
                com.biglybt.util.JSONUtils.encodeToJSON(response));
        }
    }

    private static class NoOffers implements TrackerProxy.Listener {
        @Override public void getOffer(byte[] hash, long timeout, WebRTCProvider.OfferListener l) {
            l.failed();
        }
        @Override public void gotAnswer(byte[] hash, String offerId, String sdp) {}
        @Override public void gotOffer(byte[] hash, String offerId, String sdp,
                WebRTCProvider.AnswerListener l) { l.failed(); }
    }
}
