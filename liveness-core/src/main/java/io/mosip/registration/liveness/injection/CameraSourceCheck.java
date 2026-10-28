package io.mosip.registration.liveness.injection;

import io.mosip.registration.liveness.device.DeviceInfo;

/**
 * Inspects where a device's frames can come from before the session opens it. The desktop implementation lists the
 * station's cameras and flags software cameras; SBI devices are vouched for by the device service instead.
 * Implementations must not open the camera and should cache, as they run at the start of every session.
 */
public interface CameraSourceCheck {

    /** Skips the check: for devices without an operating-system camera behind them. */
    CameraSourceCheck NONE = device -> CameraSourceReport.notApplicable();

    CameraSourceReport inspect(DeviceInfo device);
}
