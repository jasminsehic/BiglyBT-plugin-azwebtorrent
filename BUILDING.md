# Building and testing azwebtorrent

The plugin has its original Java sources under `src/org`. A Maven build is provided for repeatable CI compilation and a packaged plugin JAR. The initial project had no unit tests; new JUnit 5 tests belong in `src/test/java`.

## Build

Install Java 21 and Maven 3.9+, then download the BiglyBT 4.1.0.0 host runtime:

```sh
curl -fL https://github.com/BiglySoftware/BiglyBT/releases/download/v4.1.0.0/GitHub_BiglyBT.jar -o /tmp/BiglyBT.jar
mvn install:install-file -Dfile=/tmp/BiglyBT.jar -DgroupId=com.biglybt -DartifactId=biglybt-host -Dversion=4.1.0.0 -Dpackaging=jar -DgeneratePom=true
mvn clean verify
```

Output: `target/azwebtorrent.jar`. The shaded JAR contains plugin classes/resources and runtime dependencies, excluding the BiglyBT host API. GitHub Actions uploads the resulting JAR on successful builds.

No native WebRTC runtime is invoked by the unit tests; they should use local WebSocket test servers and mocked/test-only peers. The WebRTC native library is needed when actually using the plugin in BiglyBT.

## WebRTC API compatibility

The plugin calls `RTCStats.getMembers()`, present in `webrtc-java` 0.10.0 and removed by 0.11.0 (renamed to `getAttributes()`). The build pins 0.10.0 to retain the original implementation and avoid runtime API linkage mismatches. Check that the installed plugin's WebRTC native runtime/JAR version agrees before deployment; CI verifies Java compilation only, not the running BiglyBT process.

## Offline tracker integration tests

CI now runs `TrackerProxyIntegrationTest` on both Linux and Windows. It uses a
small fake host implementing only `TrackerProxy.Listener` plus a local Tyrus
WebSocket tracker on loopback. It verifies:

- A successful tracker announce and connection reuse.
- Offer JSON generation and answer callback dispatch via a fake WebRTC provider.
- Reconnect after a deliberate server close, with re-announcements and **no newly generated offers**.
- Tracker failure response propagation.
- Concurrent announces sharing a single tracker WebSocket connection.
- A tracker that silently stops replying to announces: caller timeout, socket eviction,
  and recovery on a fresh connection.
- A raw RFC6455 test tracker that deliberately ignores pings: missed-pong detection,
  session invalidation, and reconnection. Unlike Tyrus, it does not auto-respond.
- A deliberately stalled asynchronous send using a mock WebSocket session: bounded
  write failure, socket closure, and subsequent recovery with a real local tracker.
- WSS handshake attempted against a plain-WS tracker: bounded TLS/upgrade failure
  without retaining a session. This tests failure handling, not successful TLS.
- Concurrent server disconnect and plugin shutdown while two announces wait:
  both waiters return and tracker state is released.

Fast failure tests inject shorter timing values into the package-private test
constructor. Normal plugin instances retain the original announce, ping, pong,
and write timeout defaults. The connection handshake is explicitly bounded.

The fixture does **not** mock all BiglyBT APIs, test native WebRTC ICE/data
channels, or launch the full BiglyBT application. `BiglyBTHostApiSmokeTest`
class-loads the plugin against the real BiglyBT 4.1.0.0 API JAR in CI; a
separate manual BiglyBT application smoke test remains required.

## Overnight soak diagnostics

The integration branch writes structured entries via the plugin's existing
**WebTorrent** logging channel. This branch explicitly enables BiglyBT's
`LoggerChannel.setDiagnostic(16 MiB, true)` and `setForce(true)` so the
events persist in rotating diagnostic files even in non-beta releases.
The files live under the active BiglyBT user-data directory's `logs` folder,
usually `%APPDATA%\\BiglyBT\\logs\\WebTorrent_1.log` and
`WebTorrent_2.log` on Windows (a custom BiglyBT profile changes the root).
The channel also retains its ordinary plugin UI log view. Entries are prefixed `[soak][proxy]` or
`[soak][hosted-tracker]`. Look for:

- `connect-start`, `socket-open`, `connection-registered`, and
  `connection-reused-after-gate` for connection lifecycle and serialized creation.
- `heartbeat-pong`, `heartbeat-pong-timeout`,
  `heartbeat-send-failed` and `heartbeat-socket-closed` for liveness handling.
- `send-ok`, `bounded-send-failed`, `announce-timeout`,
  `announce-rejected`, `announce-response`, and `stale-response-ignored`
  for write limits, stale responses and failure propagation.
- `offer-received` and `answer-delivered` for sampled signalling activity;
  the offer/answer SDP and identifying values are not logged.
- `socket-error` and `proxy-request-failed` for failures beyond an orderly close.
- `announces-restored` with a replay count and `offersRegenerated=false`.
- `offers-generated` for offer generation counts (first three batches and each
  hundredth batch).
- `registration-stopped`, `session-cleanup`, `proxy-shutdown`,
  `session-registered`, `session-removed`, `session-rejected-during-shutdown`,
  `shutdown-start`, and `shutdown-complete` for registration and resource cleanup.

Common sends and pong events are sampled to limit noise. No SDP bodies,
peer IDs, torrent hashes, tracker URL query parameters, usernames or passwords
are included in the new messages. If the host logs don't contain
`[soak]` events, check whether the installed JAR was built from the updated
integration branch. `[soak][plugin] diagnostic-file-enabled` appears when
the logging file is activated. The five tracker reliability fixes (#3–#7)
have runtime event coverage; the Maven/CI base (#2) is a packaging change
and has no runtime tracker event to instrument.

For a manual soak, install the generated `target/azwebtorrent.jar` in a
**real** BiglyBT 4.1.0.0 Windows setup and capture the WebTorrent plugin log
throughout a torrent announce/reconnect cycle. CI's host API smoke test does
not prove native WebRTC functionality.
