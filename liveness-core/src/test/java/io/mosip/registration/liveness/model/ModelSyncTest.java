package io.mosip.registration.liveness.model;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Sync against a real loopback HTTP server publishing a package directory. */
class ModelSyncTest {

    @TempDir
    Path tmp;

    private KeyPair release;
    private ModelPackageVerifier verifier;
    private ModelRepository repo;
    private HttpServer server;
    /** Directory the server publishes; null = respond 404. */
    private volatile Path published;
    private final List<String> requests = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void setUp() throws Exception {
        release = ModelSigning.generateKeyPair();
        verifier = new ModelPackageVerifier(Collections.singletonList(release.getPublic()), "1.2.0");
        repo = new ModelRepository(tmp.resolve("repo"), verifier);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/models/", exchange -> {
            String name = exchange.getRequestURI().getPath().substring("/models/".length());
            requests.add(name);
            Path file = published == null ? null : published.resolve(name);
            if (file == null || !Files.isRegularFile(file)) {
                exchange.sendResponseHeaders(404, -1);
            } else {
                byte[] body = Files.readAllBytes(file);
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void newerPackageIsDownloadedVerifiedAndActivated() throws Exception {
        repo.install(pkg("v6", 6, "detector-v6"));
        published = pkg("v7", 7, "detector-v7");

        ModelSync.Result result = sync().sync();

        assertEquals(ModelSync.Status.UPDATED, result.getStatus(), result.toString());
        ModelRepository.ActiveModel active = repo.activate();
        assertEquals(7, active.getManifest().getSequence());
        assertEquals("detector-v7", new String(Files.readAllBytes(active.getDirectory().resolve("detector.onnx")),
                StandardCharsets.UTF_8));
        assertNoDownloadsLeft();
    }

    @Test
    void samePackageIsNotDownloadedAgain() throws Exception {
        published = pkg("v7", 7, "detector-v7");
        repo.install(published);

        ModelSync.Result result = sync().sync();

        assertEquals(ModelSync.Status.UP_TO_DATE, result.getStatus());
        assertFalse(requests.contains("detector.onnx"), "only the manifest is fetched: " + requests);
    }

    @Test
    void unreachableServerKeepsTheInstalledPackage() throws Exception {
        repo.install(pkg("v7", 7, "detector-v7"));
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/models/");
        server.stop(0);

        ModelSync.Result result = new ModelSync(base, repo, verifier, tmp.resolve("work")).sync();

        assertEquals(ModelSync.Status.OFFLINE, result.getStatus());
        assertEquals(7, repo.activate().getManifest().getSequence());
    }

    @Test
    void missingPackageOnTheServerCountsAsOffline() throws Exception {
        published = null;
        assertEquals(ModelSync.Status.OFFLINE, sync().sync().getStatus());
    }

    @Test
    void forgedPackageIsRefusedBeforeAnyModelIsDownloaded() throws Exception {
        repo.install(pkg("v6", 6, "detector-v6"));
        Path rogue = tmp.resolve("rogue");
        Files.createDirectories(rogue);
        Files.write(rogue.resolve("detector.onnx"), "backdoor".getBytes(StandardCharsets.UTF_8));
        ModelSigning.signPackage(rogue, "mosip-face-liveness", "9.9.9", 99, "1.2.0", ModelSigning.generateKeyPair().getPrivate());
        published = rogue;

        ModelSync.Result result = sync().sync();

        assertEquals(ModelSync.Status.REJECTED, result.getStatus());
        assertTrue(result.getDetail().contains("signature"), result.getDetail());
        assertFalse(requests.contains("detector.onnx"), "nothing past the manifest: " + requests);
        assertEquals(6, repo.activate().getManifest().getSequence());
    }

    @Test
    void replayedOlderPackageIsRefusedWithoutDownloading() throws Exception {
        Path v6 = pkg("v6", 6, "detector-v6");
        repo.install(pkg("v7", 7, "detector-v7"));
        published = v6;

        ModelSync.Result result = sync().sync();

        assertEquals(ModelSync.Status.REJECTED, result.getStatus());
        assertTrue(result.getDetail().contains("older"), result.getDetail());
        assertFalse(requests.contains("detector.onnx"));
        assertEquals(7, repo.activate().getManifest().getSequence());
    }

    @Test
    void modelSwappedOnTheServerIsRefusedAndNothingChanges() throws Exception {
        repo.install(pkg("v6", 6, "detector-v6"));
        published = pkg("v7", 7, "detector-v7");
        Files.write(published.resolve("detector.onnx"), "detector-v8".getBytes(StandardCharsets.UTF_8));

        ModelSync.Result result = sync().sync();

        assertEquals(ModelSync.Status.REJECTED, result.getStatus(), result.toString());
        assertEquals(6, repo.activate().getManifest().getSequence());
        assertNoDownloadsLeft();
    }

    @Test
    void oversizedModelIsCutOffAtItsSignedSize() throws Exception {
        repo.install(pkg("v6", 6, "detector-v6"));
        published = pkg("v7", 7, "detector-v7");
        Files.write(published.resolve("detector.onnx"), new byte[1 << 20]);

        ModelSync.Result result = sync().sync();

        assertEquals(ModelSync.Status.REJECTED, result.getStatus());
        assertTrue(result.getDetail().contains("larger than expected"), result.getDetail());
        assertEquals(6, repo.activate().getManifest().getSequence());
    }

    @Test
    void plainHttpIsOnlyAllowedForLoopback() {
        assertThrows(IllegalArgumentException.class,
                () -> new ModelSync(URI.create("http://updates.example.org/models/"), repo, verifier, tmp));
        new ModelSync(URI.create("https://updates.example.org/models"), repo, verifier, tmp);
    }

    private ModelSync sync() {
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/models/");
        return new ModelSync(base, repo, verifier, tmp.resolve("work"));
    }

    private void assertNoDownloadsLeft() throws Exception {
        Path work = tmp.resolve("work");
        assertTrue(!Files.exists(work) || Files.list(work).count() == 0, "staging cleaned up");
    }

    private Path pkg(String dirName, long sequence, String detector) throws Exception {
        Path dir = tmp.resolve("src-" + dirName);
        Files.createDirectories(dir);
        Files.write(dir.resolve("detector.onnx"), detector.getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve("antispoof.onnx"), ("antispoof-" + sequence).getBytes(StandardCharsets.UTF_8));
        ModelSigning.signPackage(dir, "mosip-face-liveness", "1.0." + sequence, sequence, "1.2.0", release.getPrivate());
        return dir;
    }
}
