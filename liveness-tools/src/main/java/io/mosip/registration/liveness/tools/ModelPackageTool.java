package io.mosip.registration.liveness.tools;

import io.mosip.registration.liveness.model.ModelIntegrityException;
import io.mosip.registration.liveness.model.ModelManifest;
import io.mosip.registration.liveness.model.ModelPackageVerifier;
import io.mosip.registration.liveness.model.ModelRepository;
import io.mosip.registration.liveness.model.ModelSigning;
import io.mosip.registration.liveness.onnx.OnnxLivenessEngine;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.util.Collections;

/** {@code models} subcommand: release-side signing and client-side install/verify/rollback. */
final class ModelPackageTool {

    private ModelPackageTool() {
    }

    static int run(String[] a) throws Exception {
        if (a.length == 0) {
            System.err.println("models keygen|sign|verify|install|status|rollback ...");
            return 2;
        }
        try {
            switch (a[0]) {
                case "keygen": {
                    KeyPair pair = ModelSigning.generateKeyPair();
                    Path priv = Paths.get(a[1]);
                    Files.write(priv, ModelSigning.toPem(pair.getPrivate()).getBytes(StandardCharsets.US_ASCII));
                    try {
                        Files.setPosixFilePermissions(priv, PosixFilePermissions.fromString("rw-------"));
                    } catch (UnsupportedOperationException ignored) {
                        // non-POSIX file system: protect the file by other means
                    }
                    Files.write(Paths.get(a[2]), ModelSigning.toPem(pair.getPublic()).getBytes(StandardCharsets.US_ASCII));
                    System.out.println("Private key (keep offline, release process only): " + a[1]);
                    System.out.println("Public key (ship to Registration Clients): " + a[2]);
                    return 0;
                }
                case "sign": {
                    ModelManifest m = ModelSigning.signPackage(Paths.get(a[1]),
                            LivenessTools.option(a, "--name", "mosip-face-liveness"),
                            required(a, "--version"), Long.parseLong(required(a, "--sequence")),
                            LivenessTools.option(a, "--min-engine", OnnxLivenessEngine.ENGINE_VERSION),
                            ModelSigning.readPrivateKey(Paths.get(a[2])));
                    System.out.println("Signed " + m.label() + " (" + m.getFiles().size() + " files)");
                    return 0;
                }
                case "verify": {
                    ModelManifest m = verifier(a[2]).verify(Paths.get(a[1]));
                    System.out.println("OK: " + m.label() + ", needs engine " + m.getEngineMinVersion());
                    return 0;
                }
                case "install": {
                    ModelManifest m = new ModelRepository(Paths.get(a[2]), verifier(a[3])).install(Paths.get(a[1]));
                    System.out.println("Installed and activated " + m.label());
                    return 0;
                }
                case "status": {
                    ModelRepository repo = new ModelRepository(Paths.get(a[1]), verifier(a[2]));
                    System.out.println("Installed: " + repo.installedSequences());
                    System.out.println("Active: " + repo.activate().getManifest().label() + " (verified)");
                    return 0;
                }
                case "rollback": {
                    ModelManifest m = new ModelRepository(Paths.get(a[1]), verifier(a[2])).rollback();
                    System.out.println("Rolled back to " + m.label());
                    return 0;
                }
                default:
                    System.err.println("Unknown models command " + a[0]);
                    return 2;
            }
        } catch (ModelIntegrityException e) {
            System.err.println("REJECTED: " + e.getMessage());
            return 1;
        } catch (ArrayIndexOutOfBoundsException e) {
            System.err.println("Missing argument; run without arguments for usage");
            return 2;
        }
    }

    private static ModelPackageVerifier verifier(String publicKey) throws Exception {
        return new ModelPackageVerifier(Collections.singletonList(ModelSigning.readPublicKey(Paths.get(publicKey))),
                OnnxLivenessEngine.ENGINE_VERSION);
    }

    private static String required(String[] a, String name) {
        String v = LivenessTools.option(a, name, null);
        if (v == null) {
            throw new IllegalArgumentException(name + " is required");
        }
        return v;
    }
}
