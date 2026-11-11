package org.parg.azureus.plugins.webtorrent.webrtc.local;

import java.util.Locale;
import java.util.Map;

/**
 * Extracts only ICE candidate categories for diagnostics. Never retain or log
 * candidate SDP, IP addresses, ports, foundations, credentials or stats IDs.
 */
final class IceDiagnostics {
    private IceDiagnostics() {}

    static String candidateType(String sdp) {
        if (sdp == null) return "unknown";
        String[] fields = sdp.trim().split("\\s+");
        for (int i = 0; i + 1 < fields.length; i++) {
            if ("typ".equalsIgnoreCase(fields[i])) {
                return safeType(fields[i + 1]);
            }
        }
        return "unknown";
    }

    static String candidateTransport(String sdp) {
        if (sdp == null) return "unknown";
        String[] fields = sdp.trim().split("\\s+");
        if (fields.length < 3) return "unknown";
        String transport = fields[2].toLowerCase(Locale.ROOT);
        return "udp".equals(transport) || "tcp".equals(transport) ? transport : "unknown";
    }

    static String safeType(Object value) {
        if (!(value instanceof String)) return "unknown";
        String type = ((String) value).toLowerCase(Locale.ROOT);
        switch (type) {
            case "host":
            case "srflx":
            case "prflx":
            case "relay":
                return type;
            default:
                return "unknown";
        }
    }

    /**
     * Resolve a selected ICE candidate pair from standard RTCStats members.
     * If the library does not expose the selected pair, report unknown rather
     * than guessing from the list of gathered candidates.
     */
    static String selectedPath(Map<String, ? extends Map<String, ?>> stats) {
        String selectedId = null;
        for (Map<String, ?> members : stats.values()) {
            Object id = members.get("selectedCandidatePairId");
            if (id instanceof String) {
                selectedId = (String) id;
                break;
            }
        }
        if (selectedId == null) {
            for (Map.Entry<String, ? extends Map<String, ?>> entry : stats.entrySet()) {
                Map<String, ?> members = entry.getValue();
                if (Boolean.TRUE.equals(members.get("selected")) &&
                    "succeeded".equalsIgnoreCase(String.valueOf(members.get("state")))) {
                    selectedId = entry.getKey();
                    break;
                }
            }
        }
        if (selectedId == null) return "local=unknown remote=unknown";

        Map<String, ?> pair = stats.get(selectedId);
        if (pair == null) return "local=unknown remote=unknown";
        return "local=" + lookupType(stats, pair.get("localCandidateId")) +
            " remote=" + lookupType(stats, pair.get("remoteCandidateId"));
    }

    private static String lookupType(Map<String, ? extends Map<String, ?>> stats, Object id) {
        if (!(id instanceof String)) return "unknown";
        Map<String, ?> candidate = stats.get((String) id);
        return candidate == null ? "unknown" : safeType(candidate.get("candidateType"));
    }
}
