package io.mosip.registration.liveness.regclient;

import io.mosip.registration.liveness.config.LivenessConfig;
import io.mosip.registration.liveness.mock.SimulatedLivenessEngine;
import io.mosip.registration.liveness.model.ModelPackageVerifier;
import io.mosip.registration.liveness.model.ModelRepository;
import io.mosip.registration.liveness.model.ModelSigning;
import io.mosip.registration.liveness.model.ModelSync;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Connectivity only informs the badge and model sync; liveness itself never depends on it. */
class RegClientConnectivityTest {

    @TempDir
    Path tmp;

    private RegClientLiveness liveness;

    @BeforeEach
    void setUp() {
        liveness = new RegClientLiveness(LivenessConfig.fromProperties(new Properties()), new SimulatedLivenessEngine(),
                event -> { }, null);
    }

    @AfterEach
    void tearDown() {
        liveness.close();
    }

    @Test
    void connectivityIsUnknownUntilTheClientProvidesACheck() {
        assertNull(liveness.isOnline());
        AtomicBoolean online = new AtomicBoolean(true);
        liveness.setConnectivity(online::get);
        assertTrue(liveness.isOnline());
        online.set(false);
        assertFalse(liveness.isOnline());
    }

    @Test
    void aFailingConnectivityCheckCountsAsOffline() {
        liveness.setConnectivity(() -> {
            throw new IllegalStateException("health check failed");
        });
        assertFalse(liveness.isOnline());
    }

    @Test
    void modelSyncIsOnlyAttemptedWhileOnline() throws Exception {
        AtomicBoolean online = new AtomicBoolean(false);
        liveness.setConnectivity(online::get);
        ModelPackageVerifier verifier = new ModelPackageVerifier(
                Collections.singletonList(ModelSigning.generateKeyPair().getPublic()), "1.2.0");
        // Port 1 on loopback refuses at once: an "online" client whose update server is down.
        liveness.setModelSync(new ModelSync(URI.create("http://127.0.0.1:1/models/"),
                new ModelRepository(tmp.resolve("repo"), verifier), verifier, tmp.resolve("work")));

        assertNull(liveness.syncModelsIfOnline(), "offline: nothing attempted");
        assertNull(liveness.lastModelSync());

        online.set(true);
        ModelSync.Result result = liveness.syncModelsIfOnline();
        assertEquals(ModelSync.Status.OFFLINE, result.getStatus(), "server down: installed models stay");
        assertEquals(result, liveness.lastModelSync());
    }

    @Test
    void withoutASyncConfiguredNothingIsAttempted() {
        liveness.setConnectivity(() -> true);
        assertNull(liveness.syncModelsIfOnline());
    }
}
