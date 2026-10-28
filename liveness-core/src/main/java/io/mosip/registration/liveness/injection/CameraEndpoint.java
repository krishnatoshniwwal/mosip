package io.mosip.registration.liveness.injection;

/** One video capture source the operating system offers, as found by a {@link CameraSourceCheck}. */
public final class CameraEndpoint {

    private final String name;
    private final String hardwareId;
    private final String virtualReason;
    private final boolean reachable;

    /**
     * @param name       the name the operating system shows
     * @param hardwareId PnP instance ID, sysfs path or similar; empty if unknown
     * @param reachable  whether the capture backend in use can open it (a DirectShow-only filter cannot be opened
     *                   through Media Foundation, for instance)
     */
    public CameraEndpoint(String name, String hardwareId, boolean reachable) {
        this.name = name == null ? "" : name.trim();
        this.hardwareId = hardwareId == null ? "" : hardwareId.trim();
        this.virtualReason = VirtualCameraSignatures.identify(this.name, this.hardwareId);
        this.reachable = reachable;
    }

    public String getName() { return name; }
    public String getHardwareId() { return hardwareId; }
    public boolean isVirtual() { return virtualReason != null; }
    /** Why the source is considered virtual, e.g. {@code "name matches OBS Virtual Camera"}; null if physical. */
    public String getVirtualReason() { return virtualReason; }
    public boolean isReachable() { return reachable; }

    @Override
    public String toString() {
        return name + (isVirtual() ? " [virtual: " + virtualReason + "]" : "") + (reachable ? "" : " [not reachable]");
    }
}
