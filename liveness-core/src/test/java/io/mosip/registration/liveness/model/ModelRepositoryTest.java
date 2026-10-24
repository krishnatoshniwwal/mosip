package io.mosip.registration.liveness.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelRepositoryTest {

    @TempDir
    Path tmp;

    private KeyPair release;
    private ModelPackageVerifier verifier;

    @BeforeEach
    void keys() throws Exception {
        release = ModelSigning.generateKeyPair();
        verifier = new ModelPackageVerifier(Collections.singletonList(release.getPublic()), "1.2.0");
    }

    @Test
    void signedPackageVerifies() throws Exception {
        Path pkg = pkg("v7", 7, "detector-v7");

        ModelManifest manifest = verifier.verify(pkg);

        assertEquals(7, manifest.getSequence());
        assertEquals("mosip-face-liveness 1.0.7 #7", manifest.label());
        assertEquals(2, manifest.getFiles().size());
    }

    @Test
    void tamperedModelFileIsRejected() throws Exception {
        Path pkg = pkg("v7", 7, "detector-v7");
        Files.write(pkg.resolve("detector.onnx"), "detector-v7-backdoor".getBytes(StandardCharsets.UTF_8));

        assertRejected(pkg, "size differs");
        Files.write(pkg.resolve("detector.onnx"), "detector-v8".getBytes(StandardCharsets.UTF_8));
        assertRejected(pkg, "hash differs");
    }

    @Test
    void unlistedFileIsRejected() throws Exception {
        Path pkg = pkg("v7", 7, "detector-v7");
        Files.write(pkg.resolve("extra.onnx"), new byte[] {1});

        assertRejected(pkg, "Unlisted file");
    }

    @Test
    void editedManifestBreaksTheSignature() throws Exception {
        Path pkg = pkg("v7", 7, "detector-v7");
        Path manifest = pkg.resolve(ModelManifest.MANIFEST_FILE);
        String text = new String(Files.readAllBytes(manifest), StandardCharsets.UTF_8);
        Files.write(manifest, text.replace("package.sequence=7", "package.sequence=70").getBytes(StandardCharsets.UTF_8));

        assertRejected(pkg, "signature");
    }

    @Test
    void packageSignedWithAnotherKeyIsRejected() throws Exception {
        Path pkg = tmp.resolve("rogue");
        Files.createDirectories(pkg);
        Files.write(pkg.resolve("detector.onnx"), new byte[] {9, 9});
        ModelSigning.signPackage(pkg, "mosip-face-liveness", "9.9.9", 99, "1.0.0", ModelSigning.generateKeyPair().getPrivate());

        assertRejected(pkg, "signature");
    }

    @Test
    void keyRotationAcceptsAnyTrustedKey() throws Exception {
        KeyPair next = ModelSigning.generateKeyPair();
        Path pkg = tmp.resolve("next");
        Files.createDirectories(pkg);
        Files.write(pkg.resolve("detector.onnx"), new byte[] {4});
        ModelSigning.signPackage(pkg, "mosip-face-liveness", "2.0.0", 20, "1.0.0", next.getPrivate());

        ModelPackageVerifier rotating = new ModelPackageVerifier(
                Arrays.asList(release.getPublic(), next.getPublic()), "1.2.0");
        assertEquals(20, rotating.verify(pkg).getSequence());
    }

    @Test
    void packageNeedingANewerEngineIsRejected() throws Exception {
        Path pkg = tmp.resolve("future");
        Files.createDirectories(pkg);
        Files.write(pkg.resolve("detector.onnx"), new byte[] {4});
        ModelSigning.signPackage(pkg, "mosip-face-liveness", "3.0.0", 30, "1.10.0", release.getPrivate());

        assertRejected(pkg, "needs engine 1.10.0");
    }

    @Test
    void illegalFileNamesAreRefusedWhenSigning() throws Exception {
        Path pkg = tmp.resolve("bad");
        Files.createDirectories(pkg.resolve("sub"));
        assertThrows(ModelIntegrityException.class,
                () -> ModelSigning.signPackage(pkg, "n", "1", 1, "1.0", release.getPrivate()));
    }

    @Test
    void installActivatesAndKeepsThePreviousPackage() throws Exception {
        ModelRepository repo = new ModelRepository(tmp.resolve("repo"), verifier);
        repo.install(pkg("v5", 5, "detector-v5"));
        repo.install(pkg("v6", 6, "detector-v6"));
        repo.install(pkg("v7", 7, "detector-v7"));

        ModelRepository.ActiveModel active = repo.activate();
        assertEquals(7, active.getManifest().getSequence());
        assertEquals("detector-v7", new String(Files.readAllBytes(active.getDirectory().resolve("detector.onnx")),
                StandardCharsets.UTF_8));
        assertEquals(Arrays.asList(6L, 7L), repo.installedSequences(), "older packages are pruned");
    }

    @Test
    void olderPackageCannotBeReplayed() throws Exception {
        ModelRepository repo = new ModelRepository(tmp.resolve("repo"), verifier);
        Path v6 = pkg("v6", 6, "detector-v6");
        repo.install(pkg("v7", 7, "detector-v7"));

        ModelIntegrityException e = assertThrows(ModelIntegrityException.class, () -> repo.install(v6));
        assertTrue(e.getMessage().contains("not newer"), e.getMessage());
        assertEquals(7, repo.activate().getManifest().getSequence());
    }

    @Test
    void rollbackFloorSurvivesDeletingPackages() throws Exception {
        Path root = tmp.resolve("repo");
        ModelRepository repo = new ModelRepository(root, verifier);
        repo.install(pkg("v7", 7, "detector-v7"));
        for (Path p : Files.list(root.resolve("packages")).toArray(Path[]::new)) {
            for (Path f : Files.list(p).toArray(Path[]::new)) {
                Files.delete(f);
            }
            Files.delete(p);
        }

        assertThrows(ModelIntegrityException.class, () -> new ModelRepository(root, verifier).install(pkg("v6", 6, "x")));
    }

    @Test
    void redeliveryOfTheSamePackageIsANoOp() throws Exception {
        ModelRepository repo = new ModelRepository(tmp.resolve("repo"), verifier);
        Path v7 = pkg("v7", 7, "detector-v7");
        repo.install(v7);

        assertEquals(7, repo.install(v7).getSequence());
    }

    @Test
    void failedInstallLeavesTheActivePackageUntouched() throws Exception {
        ModelRepository repo = new ModelRepository(tmp.resolve("repo"), verifier);
        repo.install(pkg("v7", 7, "detector-v7"));
        Path v8 = pkg("v8", 8, "detector-v8");
        Files.write(v8.resolve("detector.onnx"), "corrupted!!".getBytes(StandardCharsets.UTF_8));

        assertThrows(ModelIntegrityException.class, () -> repo.install(v8));
        assertEquals(7, repo.activate().getManifest().getSequence());
        assertEquals(Collections.singletonList(7L), repo.installedSequences());
        assertTrue(Files.list(tmp.resolve("repo")).noneMatch(p -> p.getFileName().toString().startsWith("staging")));
    }

    @Test
    void tamperingAfterInstallIsDetectedAtActivation() throws Exception {
        ModelRepository repo = new ModelRepository(tmp.resolve("repo"), verifier);
        repo.install(pkg("v7", 7, "detector-v7"));
        Path installed = repo.activate().getDirectory();
        Files.write(installed.resolve("detector.onnx"), "detector-v8".getBytes(StandardCharsets.UTF_8));

        ModelIntegrityException e = assertThrows(ModelIntegrityException.class, repo::activate);
        assertTrue(e.getMessage().contains("hash differs"), e.getMessage());
    }

    @Test
    void explicitRollbackReturnsToThePreviousVerifiedPackage() throws Exception {
        ModelRepository repo = new ModelRepository(tmp.resolve("repo"), verifier);
        repo.install(pkg("v6", 6, "detector-v6"));
        repo.install(pkg("v7", 7, "detector-v7"));

        assertEquals(6, repo.rollback().getSequence());
        assertEquals(6, repo.activate().getManifest().getSequence());
        assertThrows(ModelIntegrityException.class, () -> repo.install(pkg("v7b", 7, "detector-v7")),
                "the faulty sequence cannot simply be installed again");
        assertEquals(8, repo.install(pkg("v8", 8, "detector-v8-fixed")).getSequence());
    }

    @Test
    void emptyRepositoryFailsClosed() throws Exception {
        ModelRepository repo = new ModelRepository(tmp.resolve("repo"), verifier);
        assertThrows(ModelIntegrityException.class, repo::activate);
    }

    @Test
    void keysRoundTripThroughPem() throws Exception {
        Path pub = tmp.resolve("pub.pem");
        Path priv = tmp.resolve("priv.pem");
        Files.write(pub, ModelSigning.toPem(release.getPublic()).getBytes(StandardCharsets.US_ASCII));
        Files.write(priv, ModelSigning.toPem(release.getPrivate()).getBytes(StandardCharsets.US_ASCII));

        Path pkg = tmp.resolve("pem");
        Files.createDirectories(pkg);
        Files.write(pkg.resolve("detector.onnx"), new byte[] {1, 2, 3});
        ModelSigning.signPackage(pkg, "n", "1.0", 1, "1.0", ModelSigning.readPrivateKey(priv));
        new ModelPackageVerifier(Collections.singletonList(ModelSigning.readPublicKey(pub)), "1.2.0").verify(pkg);
    }

    @Test
    void versionsCompareNumerically() {
        assertTrue(ModelManifest.compareVersions("1.10.0", "1.9.2") > 0);
        assertEquals(0, ModelManifest.compareVersions("1.2", "1.2.0"));
        assertTrue(ModelManifest.compareVersions("1.2.0", "1.2.1-rc1") < 0);
    }

    private Path pkg(String dirName, long sequence, String detector) throws Exception {
        Path dir = tmp.resolve("src-" + dirName);
        Files.createDirectories(dir);
        Files.write(dir.resolve("detector.onnx"), detector.getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve("antispoof.onnx"), ("antispoof-" + sequence).getBytes(StandardCharsets.UTF_8));
        ModelSigning.signPackage(dir, "mosip-face-liveness", "1.0." + sequence, sequence, "1.2.0", release.getPrivate());
        return dir;
    }

    private void assertRejected(Path pkg, String reason) {
        ModelIntegrityException e = assertThrows(ModelIntegrityException.class, () -> verifier.verify(pkg));
        assertTrue(e.getMessage().contains(reason), e.getMessage());
    }
}
