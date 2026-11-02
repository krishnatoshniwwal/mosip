package io.mosip.registration.liveness.android.engine;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import io.mosip.registration.liveness.model.ModelSync;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Keeps the liveness models current on an Android station: checks the update server for a newer signed package
 * now and then every {@code intervalMinutes}, but only while the device has a validated internet connection.
 * Offline nothing is attempted and the installed package keeps running, so connectivity never decides whether
 * liveness is enforced. An update is used from the next engine start.
 */
public final class AndroidModelUpdater implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(AndroidModelUpdater.class.getName());

    private final Context context;
    private final ModelSync sync;
    private final Consumer<ModelSync.Result> onResult;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "liveness-model-sync");
        t.setDaemon(true);
        return t;
    });

    /** @param onResult called on the sync thread after every attempt made while online */
    public AndroidModelUpdater(Context context, ModelSync sync, Consumer<ModelSync.Result> onResult) {
        this.context = context.getApplicationContext();
        this.sync = sync;
        this.onResult = onResult;
    }

    /** Syncs once now and then every {@code intervalMinutes}. Fixed delay: missed runs never pile up. */
    public void start(long intervalMinutes) {
        scheduler.scheduleWithFixedDelay(this::syncIfOnline, 0, intervalMinutes, TimeUnit.MINUTES);
    }

    /** One attempt (blocking); null if the device is offline, in which case nothing is attempted. */
    public ModelSync.Result syncIfOnline() {
        if (!isOnline()) {
            LOG.fine("Offline: liveness model sync skipped");
            return null;
        }
        ModelSync.Result result = sync.sync();
        LOG.info("Liveness model sync: " + result);
        onResult.accept(result);
        return result;
    }

    /** A network Android has validated as reaching the internet (not just a captive portal). */
    public boolean isOnline() {
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        Network network = cm == null ? null : cm.getActiveNetwork();
        NetworkCapabilities caps = network == null ? null : cm.getNetworkCapabilities(network);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
