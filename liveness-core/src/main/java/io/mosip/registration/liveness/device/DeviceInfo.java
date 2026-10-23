package io.mosip.registration.liveness.device;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/** Static description of a face capture device, used for capability checks and audit. */
public final class DeviceInfo {
    private final String deviceId;
    private final String make;
    private final String model;
    private final String serialNo;
    private final DeviceLevel level;
    private final String firmware;
    private final Set<PixelFormat> formats;

    public DeviceInfo(String deviceId, String make, String model, String serialNo, DeviceLevel level,
                      String firmware, Set<PixelFormat> formats) {
        this.deviceId = deviceId;
        this.make = make;
        this.model = model;
        this.serialNo = serialNo;
        this.level = level;
        this.firmware = firmware;
        this.formats = formats.isEmpty()
                ? Collections.emptySet()
                : Collections.unmodifiableSet(EnumSet.copyOf(formats));
    }

    public String getDeviceId() { return deviceId; }
    public String getMake() { return make; }
    public String getModel() { return model; }
    public String getSerialNo() { return serialNo; }
    public DeviceLevel getLevel() { return level; }
    public String getFirmware() { return firmware; }
    public Set<PixelFormat> getFormats() { return formats; }

    public String describe() {
        return make + " " + model + " (" + level + ", fw " + firmware + ")";
    }
}
