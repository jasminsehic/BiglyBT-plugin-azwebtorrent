package org.parg.azureus.plugins.webtorrent.webrtc.local;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IceDiagnosticsTest {
    @Test
    void classifiesCandidateTypesWithoutReturningAddresses() {
        String srflx = "candidate:abcd 1 UDP 1686052607 203.0.113.12 54000 typ srflx raddr 192.168.1.5 rport 12345";
        assertEquals("srflx", IceDiagnostics.candidateType(srflx));
        assertEquals("udp", IceDiagnostics.candidateTransport(srflx));
        assertEquals("relay", IceDiagnostics.candidateType("candidate:1 1 tcp 42 198.51.100.25 9 typ relay tcptype passive"));
        assertEquals("host", IceDiagnostics.candidateType("candidate:1 1 udp 42 192.168.1.5 22222 typ host"));
        assertEquals("prflx", IceDiagnostics.candidateType("candidate:1 1 udp 42 203.0.113.5 33333 typ prflx"));
        assertEquals("unknown", IceDiagnostics.candidateType("candidate:1 1 udp 42 203.0.113.5 33333 typ secret-token"));
        assertEquals("unknown", IceDiagnostics.candidateType(null));
        assertEquals("unknown", IceDiagnostics.candidateTransport(null));
        assertFalse(IceDiagnostics.candidateType(srflx).contains("203.0.113"));
    }

    @Test
    void resolvesSelectedPairOnlyAndNeverLeaksStatsIdentifiers() {
        Map<String, Map<String, Object>> stats = new HashMap<>();
        stats.put("transport", map("selectedCandidatePairId", "pair-private"));
        stats.put("pair-private", map("localCandidateId", "local-192.0.2.1", "remoteCandidateId", "remote-secret"));
        stats.put("local-192.0.2.1", map("candidateType", "srflx", "address", "192.0.2.1"));
        stats.put("remote-secret", map("candidateType", "relay", "address", "198.51.100.3"));
        assertEquals("local=srflx remote=relay", IceDiagnostics.selectedPath(stats));
        assertFalse(IceDiagnostics.selectedPath(stats).contains("192.0.2.1"));
    }

    @Test
    void unknownSelectedPairIsNotInferredFromGatheredCandidates() {
        Map<String, Map<String, Object>> stats = new HashMap<>();
        stats.put("host", map("candidateType", "host"));
        stats.put("relay", map("candidateType", "relay"));
        assertEquals("local=unknown remote=unknown", IceDiagnostics.selectedPath(stats));
        stats.put("pair", map("selected", true, "state", "succeeded",
            "localCandidateId", "host", "remoteCandidateId", "relay"));
        assertEquals("local=host remote=relay", IceDiagnostics.selectedPath(stats));
    }

    private static Map<String, Object> map(Object... items) {
        Map<String, Object> values = new HashMap<>();
        for (int i = 0; i < items.length; i += 2) {
            values.put((String) items[i], items[i + 1]);
        }
        return values;
    }
}
