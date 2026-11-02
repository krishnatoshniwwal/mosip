package io.mosip.registration.liveness.android.engine;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import androidx.core.content.pm.PackageInfoCompat;
import io.mosip.registration.liveness.model.ModelIntegrityException;
import io.mosip.registration.liveness.model.ModelManifest;
import io.mosip.registration.liveness.model.ModelPackageVerifier;
import io.mosip.registration.liveness.model.ModelRepository;
import io.mosip.registration.liveness.model.ModelSync;
import io.mosip.registration.liveness.onnx.OnnxLivenessEngine;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * The liveness models on an Android device. They ship in the APK under {@code assets/liveness-models/}; the
 * engine needs real files, so they are copied out once per app version into the app's no-backup storage.
 *
 * <p>With a signed package bundled ({@code manifest.properties} + {@code manifest.sig}), {@link #activateSigned}
 * installs it through {@link ModelRepository}, exactly as on the desktop client: signature and hashes checked,
 * a package older than one already installed refused, and the active package re-verified at every start. Without
 * one, {@link #extractBundled} gives the plain directory, for development only.
 */
public final class AndroidModelStore {

    public static final String ASSET_DIR = "liveness-models";

    private static final Logger LOG = Logger.getLogger(AndroidModelStore.class.getName());
    private static final String COMPLETE_MARKER = ".complete";

    private AndroidModelStore() {
    }

    /** Whether the APK carries a signed model package. */
    public static boolean bundlesSignedPackage(Context context) throws IOException {
        List<String> names = Arrays.asList(context.getAssets().list(ASSET_DIR));
        return names.contains(ModelManifest.MANIFEST_FILE) && names.contains(ModelManifest.SIGNATURE_FILE);
    }

    /**
     * Copies the bundled models out of the APK, once per app version, and returns their directory. A copy
     * interrupted by the app being killed is redone on the next start.
     */
    public static synchronized Path extractBundled(Context context) throws IOException {
        Path base = context.getNoBackupFilesDir().toPath().resolve("liveness-bundled");
        Path target = base.resolve(appVersion(context));
        if (Files.isRegularFile(target.resolve(COMPLETE_MARKER))) {
            return target;
        }
        deleteRecursively(base);
        Path staging = base.resolve("staging-" + System.nanoTime());
        Files.createDirectories(staging);
        AssetManager assets = context.getAssets();
        String[] names = assets.list(ASSET_DIR);
        if (names == null || names.length == 0) {
            throw new IOException("No liveness models in the APK (assets/" + ASSET_DIR + ")");
        }
        for (String name : names) {
            try (InputStream in = assets.open(ASSET_DIR + "/" + name)) {
                Files.copy(in, staging.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
        Files.createFile(target.resolve(COMPLETE_MARKER));
        LOG.info("Liveness models extracted to " + target);
        return target;
    }

    /**
     * Installs the bundled signed package (unless a newer one is already installed, e.g. by sync) and returns the
     * verified active package.
     *
     * @throws ModelIntegrityException if no verified package can be activated; the engine must not start then
     */
    public static ModelRepository.ActiveModel activateSigned(Context context, List<PublicKey> trustedKeys)
            throws IOException, ModelIntegrityException {
        ModelRepository repository = repository(context, trustedKeys);
        Path bundled = extractBundled(context);
        try {
            repository.install(bundled);
        } catch (ModelIntegrityException e) {
            if (repository.activeSequence() < 0) {
                throw e;
            }
            // Usually: a newer package already arrived by sync, so the one in the APK is older. Keep the active one.
            LOG.info("Bundled model package not installed (" + e.getMessage() + "); keeping the active package");
        }
        return repository.activate();
    }

    /** The installed signed packages (shared by {@link #activateSigned} and {@link #modelSync}). */
    public static ModelRepository repository(Context context, List<PublicKey> trustedKeys) throws IOException {
        return new ModelRepository(context.getNoBackupFilesDir().toPath().resolve("liveness-models"),
                verifier(trustedKeys));
    }

    /**
     * Fetches newer signed packages from {@code updateServer} into the same repository, with the desktop client's
     * checks: signature and rollback sequence verified before any model is downloaded, every byte verified again
     * before the atomic install. An installed update is used from the next engine start.
     */
    public static ModelSync modelSync(Context context, URI updateServer, List<PublicKey> trustedKeys)
            throws IOException {
        return new ModelSync(updateServer, repository(context, trustedKeys), verifier(trustedKeys),
                context.getNoBackupFilesDir().toPath().resolve("liveness-model-downloads"));
    }

    private static ModelPackageVerifier verifier(List<PublicKey> trustedKeys) {
        return new ModelPackageVerifier(trustedKeys, OnnxLivenessEngine.ENGINE_VERSION);
    }

    private static String appVersion(Context context) throws IOException {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            // lastUpdateTime changes on every install, so a reinstalled build with the same version is re-extracted.
            return PackageInfoCompat.getLongVersionCode(info) + "-" + info.lastUpdateTime;
        } catch (PackageManager.NameNotFoundException e) {
            throw new IOException("Cannot read this app's package info", e);
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(p);
            }
        }
    }
}
