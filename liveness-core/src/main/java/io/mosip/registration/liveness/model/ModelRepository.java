package io.mosip.registration.liveness.model;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Installed liveness model packages on a Registration Client, with safe offline updates:
 *
 * <pre>
 * root/
 *   packages/&lt;sequence&gt;/   verified copies of installed packages (the active one and the previous)
 *   active                 sequence of the package in use
 *   state.properties       highest sequence ever installed (rollback protection)
 * </pre>
 *
 * <ul>
 *   <li><b>Install</b> (from sync, USB, or the client's update channel): the source is verified, copied
 *       to a staging directory, verified <em>again</em> as copied, moved into place, and only then made
 *       active with an atomic rename. A power cut leaves either the old or the new package active,
 *       never a half-copied one.</li>
 *   <li><b>Rollback protection:</b> a package whose sequence is not higher than any installed before is
 *       refused, so an old, correctly signed package with known weaknesses cannot be replayed.</li>
 *   <li><b>Activation</b> at engine start re-verifies the active package, so files tampered with on disk
 *       are detected before they are loaded. A failure is fatal (fail closed): liveness is unavailable
 *       rather than running on unverified models.</li>
 *   <li><b>Rollback</b> to the previous installed package is an explicit administrative action, for a
 *       release found to be faulty; that package was signed and installed before, and is re-verified.</li>
 * </ul>
 *
 * Thread-safe within one process.
 */
public final class ModelRepository {

    private static final Logger LOG = Logger.getLogger(ModelRepository.class.getName());
    private static final int KEEP_PACKAGES = 2;

    /** The verified package an engine should load. */
    public static final class ActiveModel {
        private final Path directory;
        private final ModelManifest manifest;

        ActiveModel(Path directory, ModelManifest manifest) {
            this.directory = directory;
            this.manifest = manifest;
        }

        public Path getDirectory() { return directory; }
        public ModelManifest getManifest() { return manifest; }
    }

    private final Path root;
    private final Path packages;
    private final ModelPackageVerifier verifier;

    public ModelRepository(Path root, ModelPackageVerifier verifier) throws IOException {
        this.root = root;
        this.packages = root.resolve("packages");
        this.verifier = verifier;
        Files.createDirectories(packages);
    }

    /**
     * Verifies and installs the package in {@code source} and makes it active.
     *
     * @return the installed manifest (also when this exact package is already installed)
     * @throws ModelIntegrityException if verification fails or the package is not newer than every
     *                                 package installed before; nothing changes in that case
     */
    public synchronized ModelManifest install(Path source) throws ModelIntegrityException {
        ModelManifest manifest = verifier.verify(source);
        long highest = highestInstalled();
        Path target = packages.resolve(Long.toString(manifest.getSequence()));
        if (manifest.getSequence() == highest && Files.isDirectory(target) && sameManifest(source, target)) {
            return manifest;   // re-delivered by sync: nothing to do
        }
        if (manifest.getSequence() <= highest) {
            throw new ModelIntegrityException("Refusing model package " + manifest.label()
                    + ": sequence " + manifest.getSequence() + " is not newer than installed sequence " + highest);
        }
        Path staging = root.resolve("staging-" + manifest.getSequence() + "-" + System.nanoTime());
        try {
            Files.createDirectories(staging);
            for (String file : manifest.getFiles().keySet()) {
                Files.copy(source.resolve(file), staging.resolve(file));
            }
            Files.copy(source.resolve(ModelManifest.MANIFEST_FILE), staging.resolve(ModelManifest.MANIFEST_FILE));
            Files.copy(source.resolve(ModelManifest.SIGNATURE_FILE), staging.resolve(ModelManifest.SIGNATURE_FILE));
            // Verify what was written, not what was read: the source could change while being copied.
            ModelManifest copied = verifier.verify(staging);
            if (copied.getSequence() != manifest.getSequence()) {
                throw new ModelIntegrityException("Model package changed while being installed");
            }
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
            writeAtomically(root.resolve("state.properties"), "highestInstalled=" + manifest.getSequence() + "\n");
            writeAtomically(root.resolve("active"), manifest.getSequence() + "\n");
        } catch (IOException e) {
            throw new ModelIntegrityException("Could not install model package " + manifest.label(), e);
        } finally {
            deleteQuietly(staging);
        }
        prune(manifest.getSequence());
        LOG.info("Installed and activated liveness model package " + manifest.label());
        return manifest;
    }

    /** Re-verifies and returns the active package. @throws ModelIntegrityException if none is usable */
    public synchronized ActiveModel activate() throws ModelIntegrityException {
        long sequence = activeSequence();
        if (sequence < 0) {
            throw new ModelIntegrityException("No liveness model package is installed in " + root);
        }
        Path dir = packages.resolve(Long.toString(sequence));
        ModelManifest manifest = verifier.verify(dir);
        if (manifest.getSequence() != sequence) {
            throw new ModelIntegrityException("Active package directory " + sequence + " holds sequence "
                    + manifest.getSequence());
        }
        return new ActiveModel(dir, manifest);
    }

    /**
     * Activates the newest installed package older than the active one, after verifying it. The
     * rollback-protection floor is unchanged, so the faulty release can't be installed again; ship a fix
     * with a higher sequence.
     */
    public synchronized ModelManifest rollback() throws ModelIntegrityException {
        long active = activeSequence();
        long previous = -1;
        for (long s : installedSequences()) {
            if (s < active && s > previous) {
                previous = s;
            }
        }
        if (previous < 0) {
            throw new ModelIntegrityException("No earlier model package is installed to roll back to");
        }
        ModelManifest manifest = verifier.verify(packages.resolve(Long.toString(previous)));
        try {
            writeAtomically(root.resolve("active"), previous + "\n");
        } catch (IOException e) {
            throw new ModelIntegrityException("Could not roll back to " + manifest.label(), e);
        }
        LOG.warning("Rolled back liveness model package from #" + active + " to " + manifest.label());
        return manifest;
    }

    public synchronized List<Long> installedSequences() {
        List<Long> result = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(packages)) {
            for (Path entry : entries) {
                try {
                    result.add(Long.parseLong(entry.getFileName().toString()));
                } catch (NumberFormatException ignored) {
                    // not a package directory
                }
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Cannot list " + packages, e);
        }
        Collections.sort(result);
        return result;
    }

    /** @return the active sequence, or -1 if none */
    public synchronized long activeSequence() {
        Path active = root.resolve("active");
        if (!Files.isRegularFile(active)) {
            return -1;
        }
        try {
            return Long.parseLong(new String(Files.readAllBytes(active), StandardCharsets.US_ASCII).trim());
        } catch (IOException | NumberFormatException e) {
            LOG.log(Level.WARNING, "Unreadable active model pointer " + active, e);
            return -1;
        }
    }

    /** Rollback-protection floor: the highest sequence ever installed here (0 if none). */
    synchronized long highestInstalled() {
        long highest = 0;
        Path state = root.resolve("state.properties");
        if (Files.isRegularFile(state)) {
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(state)) {
                p.load(in);
                highest = Long.parseLong(p.getProperty("highestInstalled", "0").trim());
            } catch (IOException | NumberFormatException e) {
                LOG.log(Level.WARNING, "Unreadable model state " + state + "; using installed packages", e);
            }
        }
        for (long s : installedSequences()) {
            highest = Math.max(highest, s);
        }
        return highest;
    }

    private void prune(long active) {
        List<Long> installed = installedSequences();
        for (int i = 0; i < installed.size() - KEEP_PACKAGES; i++) {
            if (installed.get(i) != active) {
                deleteQuietly(packages.resolve(Long.toString(installed.get(i))));
            }
        }
    }

    private static boolean sameManifest(Path a, Path b) {
        try {
            return java.util.Arrays.equals(Files.readAllBytes(a.resolve(ModelManifest.MANIFEST_FILE)),
                    Files.readAllBytes(b.resolve(ModelManifest.MANIFEST_FILE)));
        } catch (IOException e) {
            return false;
        }
    }

    private static void writeAtomically(Path file, String content) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (OutputStream out = Files.newOutputStream(tmp)) {
            out.write(content.getBytes(StandardCharsets.US_ASCII));
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
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
