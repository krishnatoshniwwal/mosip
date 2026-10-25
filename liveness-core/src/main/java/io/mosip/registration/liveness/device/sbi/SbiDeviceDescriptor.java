package io.mosip.registration.liveness.device.sbi;

import io.mosip.registration.liveness.device.DeviceInfo;
import io.mosip.registration.liveness.device.DeviceLevel;
import io.mosip.registration.liveness.device.PixelFormat;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

/** One device reported by SBI discovery ({@code MOSIPDISC}): where it is and what it says about itself. */
public final class SbiDeviceDescriptor {
    private final String host;
    private final int port;
    private final String deviceId;
    private final String deviceStatus;
    private final DeviceLevel level;
    private final String purpose;
    private final List<String> deviceSubIds;
    private final List<String> specVersions;
    private final String serialNo;
    private final String make;
    private final String model;
    private final String type;

    SbiDeviceDescriptor(String host, int port, String deviceId, String deviceStatus, DeviceLevel level, String purpose,
                        List<String> deviceSubIds, List<String> specVersions, String serialNo, String make,
                        String model, String type) {
        this.host = host;
        this.port = port;
        this.deviceId = deviceId;
        this.deviceStatus = deviceStatus;
        this.level = level;
        this.purpose = purpose;
        this.deviceSubIds = Collections.unmodifiableList(deviceSubIds);
        this.specVersions = Collections.unmodifiableList(specVersions);
        this.serialNo = serialNo;
        this.make = make;
        this.model = model;
        this.type = type;
    }

    /** SBI reports "Ready", "Busy", "Not Ready" or "Not Registered"; only a ready device can stream. */
    public boolean isReady() {
        return "Ready".equalsIgnoreCase(deviceStatus);
    }

    /** First sub-ID (a face device usually has one), or "0" if the device listed none. */
    public String defaultSubId() {
        return deviceSubIds.isEmpty() ? "0" : deviceSubIds.get(0);
    }

    public DeviceInfo toDeviceInfo() {
        return new DeviceInfo("SBI-" + deviceId + "@" + port, make.isEmpty() ? "SBI" : make,
                model.isEmpty() ? "Face device" : model, serialNo.isEmpty() ? "unknown" : serialNo, level,
                specVersions.isEmpty() ? "unknown" : "SBI " + specVersions.get(specVersions.size() - 1),
                EnumSet.of(PixelFormat.JPEG));
    }

    /** A stream adapter for this device and sub-ID. */
    public SbiFaceStreamDevice newStreamDevice(String subId) {
        return new SbiFaceStreamDevice(host, port, deviceId, subId, toDeviceInfo());
    }

    public String getHost() { return host; }
    public int getPort() { return port; }
    public String getDeviceId() { return deviceId; }
    public String getDeviceStatus() { return deviceStatus; }
    public DeviceLevel getLevel() { return level; }
    /** "Registration" or "Auth". SBI only streams from registration-purpose devices. */
    public String getPurpose() { return purpose; }
    public List<String> getDeviceSubIds() { return deviceSubIds; }
    public List<String> getSpecVersions() { return specVersions; }
    public String getSerialNo() { return serialNo; }
    public String getMake() { return make; }
    public String getModel() { return model; }
    public String getType() { return type; }

    @Override
    public String toString() {
        return (make + " " + model).trim() + " [" + level + ", " + purpose + ", " + deviceStatus + "] serial "
                + serialNo + " deviceId " + deviceId + " subIds " + deviceSubIds + " at " + host + ":" + port;
    }
}
