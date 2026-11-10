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

## Cross-platform CI

GitHub Actions runs `mvn clean verify` on Ubuntu and Windows, checks the shaded JAR contains `plugin.properties` and the tracker proxy class, and checks that the Windows JAR contains `webrtc-java.dll`. The BiglyBT host API is downloaded from the supported 4.1.0.0 release and is not bundled in the plugin. Each platform uploads its installable JAR as a build artifact.
