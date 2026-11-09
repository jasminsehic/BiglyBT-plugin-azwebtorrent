package org.parg.azureus.plugins.webtorrent;

// Tracks unanswered pings independently of connection state.
final class TrackerPingState {
    private long sentAt = -1;
    synchronized boolean startPing(long now) {
        if (sentAt != -1) return false;
        sentAt = now;
        return true;
    }
    synchronized boolean isExpired(long now, long timeout) {
        return sentAt != -1 && now - sentAt >= timeout;
    }
    synchronized void pong() { sentAt = -1; }
}
