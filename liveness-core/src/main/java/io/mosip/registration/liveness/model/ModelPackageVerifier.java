package io.mosip.registration.liveness.model;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Verifies a model package directory before anything in it is loaded:
 *
 * <ol>
 *   <li>The signature on {@code manifest.properties} is valid for one of the trusted public keys.</li>
 *   <li>Every listed file exists as a regular file (not a link) with exactly the listed size and SHA-256.</li>
 *   <li>Nothing else is in the directory, so no unsigned file can sit next to the models.</li>
 *   <li>The running engine is at least {@code engine.minVersion}.</li>
 * </ol>
 *
 * Verification is local and offline. Thread-safe.
 */
public final class ModelPackageVerifier {

    private static final int MAX_MANIFEST_BYTES = 1 << 20;
    private static final int MAX_SIGNATURE_BYTES = 16 << 10;

    private final List<PublicKey> trustedKeys;
    private final String engineVersion;

    /**
     * @param trustedKeys   release keys; several are allowed so a key can be rotated without a gap
     * @param engineVersion version of the engine that will load the models, checked against {@code engine.minVersion}
     */
    public ModelPackageVerifier(List<PublicKey> trustedKeys, String engineVersion) {
        if (trustedKeys.isEmpty()) {
            throw new IllegalArgumentException("At least one trusted key is required");
        }
        this.trustedKeys = Collections.unmodifiableList(new ArrayList<>(trustedKeys));
        this.engineVersion = engineVersion;
    }

    public ModelManifest verify(Path dir) throws ModelIntegrityException {
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            throw new ModelIntegrityException("Not a model package directory: " + dir);
        }
        ModelManifest manifest = verifyManifest(read(dir.resolve(ModelManifest.MANIFEST_FILE), MAX_MANIFEST_BYTES),
                read(dir.resolve(ModelManifest.SIGNATURE_FILE), MAX_SIGNATURE_BYTES), dir.toString());
        for (Map.Entry<String, ModelManifest.Entry> e : manifest.getFiles().entrySet()) {
            Path file = dir.resolve(e.getKey());
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new ModelIntegrityException("Model file missing or not a regular file: " + e.getKey());
            }
            try {
                if (Files.size(file) != e.getValue().getSize()) {
                    throw new ModelIntegrityException("Model file size differs from the manifest: " + e.getKey());
                }
                if (!ModelSigning.sha256(file).equals(e.getValue().getSha256())) {
                    throw new ModelIntegrityException("Model file hash differs from the manifest: " + e.getKey());
                }
            } catch (IOException ex) {
                throw new ModelIntegrityException("Cannot read model file " + e.getKey(), ex);
            }
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (!name.equals(ModelManifest.MANIFEST_FILE) && !name.equals(ModelManifest.SIGNATURE_FILE)
                        && !manifest.getFiles().containsKey(name)) {
                    throw new ModelIntegrityException("Unlisted file in model package: " + name);
                }
            }
        } catch (IOException e) {
            throw new ModelIntegrityException("Cannot list model package " + dir, e);
        }
        return manifest;
    }

    /**
     * Checks the signature and engine version of a manifest alone, before any model file is present (so
     * {@link ModelSync} can refuse a package without downloading it). The files still need {@link #verify}.
     */
    ModelManifest verifyManifest(byte[] manifestBytes, byte[] signatureText, String source) throws ModelIntegrityException {
        if (manifestBytes.length > MAX_MANIFEST_BYTES || signatureText.length > MAX_SIGNATURE_BYTES) {
            throw new ModelIntegrityException("Model manifest or signature is too large: " + source);
        }
        byte[] signature;
        try {
            signature = Base64.getMimeDecoder().decode(new String(signatureText, StandardCharsets.US_ASCII).trim());
        } catch (IllegalArgumentException e) {
            throw new ModelIntegrityException("Model package signature is not valid base64", e);
        }
        boolean signed = false;
        for (PublicKey key : trustedKeys) {
            if (ModelSigning.verify(manifestBytes, signature, key)) {
                signed = true;
                break;
            }
        }
        if (!signed) {
            throw new ModelIntegrityException("Model package signature does not match any trusted key: " + source);
        }

        ModelManifest manifest = ModelManifest.parse(manifestBytes);
        if (engineVersion != null && ModelManifest.compareVersions(engineVersion, manifest.getEngineMinVersion()) < 0) {
            throw new ModelIntegrityException("Model package " + manifest.label() + " needs engine "
                    + manifest.getEngineMinVersion() + " or later; this engine is " + engineVersion);
        }
        return manifest;
    }

    static int maxManifestBytes() {
        return MAX_MANIFEST_BYTES;
    }

    static int maxSignatureBytes() {
        return MAX_SIGNATURE_BYTES;
    }

    private static byte[] read(Path file, int maxBytes) throws ModelIntegrityException {
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new ModelIntegrityException("Missing " + file.getFileName() + " in model package");
            }
            if (Files.size(file) > maxBytes) {
                throw new ModelIntegrityException(file.getFileName() + " is too large");
            }
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new ModelIntegrityException("Cannot read " + file, e);
        }
    }
}
