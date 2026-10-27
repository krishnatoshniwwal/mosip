package io.mosip.registration.liveness.model;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Fetches a newer signed model package from the update server when the client is online. Offline, or
 * when the server is unreachable, nothing changes and liveness keeps running on the installed package,
 * so connectivity never decides whether liveness is enforced.
 *
 * <p>The server publishes one package directory as static files:
 * {@code <base>/manifest.properties}, {@code <base>/manifest.sig} and {@code <base>/<file>} for every
 * file the manifest lists (the output of {@code models sign}). A sync:
 *
 * <ol>
 *   <li>downloads only the manifest and its signature, and checks the signature, the engine version and
 *       the rollback-protection sequence <em>before</em> downloading any model, so a forged, replayed or
 *       incompatible package costs a few kilobytes and is refused;</li>
 *   <li>downloads each listed file into a private staging directory, never past its signed size;</li>
 *   <li>hands the staging directory to {@link ModelRepository#install}, which verifies every byte again
 *       and activates the package atomically. The engine picks it up at its next start.</li>
 * </ol>
 *
 * Transport security is a second line of defence: HTTPS is required (plain HTTP only for loopback test
 * servers), but a compromised server still cannot install anything the release key did not sign.
 */
public final class ModelSync {

    /** Outcome of one sync attempt. */
    public enum Status {
        /** A newer package was downloaded, verified and activated. */
        UPDATED,
        /** The server offers nothing newer than what is installed. */
        UP_TO_DATE,
        /** The server could not be reached; the installed package stays active. */
        OFFLINE,
        /** The server offered a package that failed verification; nothing changed. */
        REJECTED
    }

    /** Result of {@link #sync()}: the status and, for UPDATED/UP_TO_DATE/REJECTED, the offered manifest. */
    public static final class Result {
        private final Status status;
        private final ModelManifest offered;
        private final String detail;

        Result(Status status, ModelManifest offered, String detail) {
            this.status = status;
            this.offered = offered;
            this.detail = detail;
        }

        public Status getStatus() { return status; }
        /** The package the server offered, or null if its manifest could not be fetched or verified. */
        public ModelManifest getOffered() { return offered; }
        public String getDetail() { return detail; }

        @Override
        public String toString() {
            return status + ": " + detail;
        }
    }

    private static final Logger LOG = Logger.getLogger(ModelSync.class.getName());
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    /** Upper bound for any single model file, whatever the manifest says. */
    private static final long MAX_FILE_BYTES = 256L << 20;

    private final URI base;
    private final ModelRepository repository;
    private final ModelPackageVerifier verifier;
    private final Path workDir;

    /**
     * @param base       update server directory holding the published package (must end with '/')
     * @param repository where verified packages are installed
     * @param verifier   the same trusted keys and engine version the repository uses
     * @param workDir    private directory for downloads in progress (cleared after each sync)
     */
    public ModelSync(URI base, ModelRepository repository, ModelPackageVerifier verifier, Path workDir) {
        String scheme = base.getScheme() == null ? "" : base.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !(scheme.equals("http") && isLoopback(base.getHost()))) {
            throw new IllegalArgumentException("Model updates need an https:// server: " + base);
        }
        this.base = base.getPath().endsWith("/") ? base : URI.create(base + "/");
        this.repository = repository;
        this.verifier = verifier;
        this.workDir = workDir;
    }

    /** One sync attempt. Never throws: every failure leaves the installed package active. */
    public synchronized Result sync() {
        byte[] manifestBytes;
        byte[] signature;
        try {
            manifestBytes = fetch(ModelManifest.MANIFEST_FILE, ModelPackageVerifier.maxManifestBytes());
            signature = fetch(ModelManifest.SIGNATURE_FILE, ModelPackageVerifier.maxSignatureBytes());
        } catch (IOException e) {
            LOG.info("Model update server unreachable (" + e.getMessage() + "); keeping the installed package");
            return new Result(Status.OFFLINE, null, "update server unreachable: " + e.getMessage());
        } catch (ModelIntegrityException e) {
            LOG.warning("Model update refused: " + e.getMessage());
            return new Result(Status.REJECTED, null, e.getMessage());
        }

        ModelManifest offered;
        try {
            offered = verifier.verifyManifest(manifestBytes, signature, base.toString());
        } catch (ModelIntegrityException e) {
            LOG.warning("Model update refused: " + e.getMessage());
            return new Result(Status.REJECTED, null, e.getMessage());
        }
        long installed = repository.highestInstalled();
        if (offered.getSequence() < installed) {
            String why = "offered " + offered.label() + " is older than installed sequence " + installed;
            LOG.warning("Model update refused: " + why);
            return new Result(Status.REJECTED, offered, why);
        }
        if (offered.getSequence() == installed) {
            return new Result(Status.UP_TO_DATE, offered, offered.label() + " is already installed");
        }

        Path staging = workDir.resolve("download-" + offered.getSequence() + "-" + System.nanoTime());
        try {
            Files.createDirectories(staging);
            Files.write(staging.resolve(ModelManifest.MANIFEST_FILE), manifestBytes);
            Files.write(staging.resolve(ModelManifest.SIGNATURE_FILE), signature);
            for (Map.Entry<String, ModelManifest.Entry> file : offered.getFiles().entrySet()) {
                download(file.getKey(), file.getValue().getSize(), staging.resolve(file.getKey()));
            }
            ModelManifest activated = repository.install(staging);
            LOG.info("Model update installed: " + activated.label());
            return new Result(Status.UPDATED, activated, "installed " + activated.label() + "; active from the next engine start");
        } catch (IOException e) {
            LOG.info("Model download interrupted (" + e.getMessage() + "); keeping the installed package");
            return new Result(Status.OFFLINE, offered, "download interrupted: " + e.getMessage());
        } catch (ModelIntegrityException e) {
            LOG.warning("Model update refused: " + e.getMessage());
            return new Result(Status.REJECTED, offered, e.getMessage());
        } finally {
            deleteQuietly(staging);
        }
    }

    private byte[] fetch(String name, int maxBytes) throws IOException, ModelIntegrityException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        copy(name, maxBytes, out);
        return out.toByteArray();
    }

    private void download(String name, long size, Path target) throws IOException, ModelIntegrityException {
        if (size > MAX_FILE_BYTES) {
            throw new ModelIntegrityException("Model file " + name + " is larger than allowed: " + size);
        }
        long written;
        try (OutputStream out = Files.newOutputStream(target)) {
            written = copy(name, size, out);
        }
        if (written != size) {
            throw new ModelIntegrityException("Model file " + name + " has " + written + " bytes, manifest says " + size);
        }
    }

    /**
     * Streams {@code name} from the server into {@code out}. More than {@code limit} bytes is not a network
     * fault but a server sending something other than the signed package, so it is an integrity failure.
     */
    private long copy(String name, long limit, OutputStream out) throws IOException, ModelIntegrityException {
        URL url = base.resolve(name).toURL();
        HttpURLConnection http = (HttpURLConnection) url.openConnection();
        http.setConnectTimeout(CONNECT_TIMEOUT_MS);
        http.setReadTimeout(READ_TIMEOUT_MS);
        http.setInstanceFollowRedirects(false);
        http.setUseCaches(false);
        try {
            int code = http.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + code + " for " + name);
            }
            long total = 0;
            byte[] buffer = new byte[64 << 10];
            try (InputStream in = http.getInputStream()) {
                for (int n; (n = in.read(buffer)) > 0; ) {
                    total += n;
                    if (total > limit) {
                        throw new ModelIntegrityException(name + " is larger than expected (" + limit + " bytes)");
                    }
                    out.write(buffer, 0, n);
                }
            }
            return total;
        } finally {
            http.disconnect();
        }
    }

    private static boolean isLoopback(String host) {
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "[::1]".equals(host);
    }

    private static void deleteQuietly(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
            for (Path entry : entries) {
                Files.deleteIfExists(entry);
            }
            Files.deleteIfExists(dir);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not delete " + dir, e);
        }
    }
}
