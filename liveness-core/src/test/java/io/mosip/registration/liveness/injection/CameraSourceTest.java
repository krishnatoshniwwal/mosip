package io.mosip.registration.liveness.injection;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CameraSourceTest {

    @Test
    void knownVirtualCamerasAreRecognisedByName() {
        for (String name : Arrays.asList("OBS Virtual Camera", "OBS-Camera", "ManyCam Virtual Webcam", "XSplit VCam",
                "Snap Camera", "DroidCam Source 3", "NVIDIA Broadcast", "Camo", "Dummy video device (0x0000)",
                "Unity Video Capture", "e2eSoft VCam", "AkVirtualCamera")) {
            assertNotNull(VirtualCameraSignatures.identify(name, ""), name);
        }
    }

    @Test
    void physicalCamerasAreNotFlagged() {
        assertNull(VirtualCameraSignatures.identify("Integrated Camera", "USB\\VID_5986&PID_2174&MI_00\\6&3B5246B5&1&0000"));
        assertNull(VirtualCameraSignatures.identify("Integrated IR Camera", "USB\\VID_5986&PID_2174&MI_02\\6&3B5246B5&1&0002"));
        assertNull(VirtualCameraSignatures.identify("Logitech BRIO", "USB\\VID_046D&PID_085E&MI_00\\7&1A2B&0&0000"));
        assertNull(VirtualCameraSignatures.identify("FaceTime HD Camera", "0x8020000005ac8514"));
        assertNull(VirtualCameraSignatures.identify("Camouflage Cam 1080p", "USB\\VID_1234&PID_5678"));
        assertNull(VirtualCameraSignatures.identify("HD Pro Webcam C920",
                "/sys/devices/pci0000:00/0000:00:14.0/usb1/1-2/1-2:1.0/video4linux/video0"));
    }

    @Test
    void softwareDevicesAreRecognisedWhateverTheyAreCalled() {
        assertNotNull(VirtualCameraSignatures.identify("Studio Cam", "SWD\\VCAMDEVAPI\\{ABC}"));
        assertNotNull(VirtualCameraSignatures.identify("Studio Cam", "ROOT\\IMAGE\\0000"));
        assertNotNull(VirtualCameraSignatures.identify("Studio Cam", "/sys/devices/virtual/video4linux/video4"));
    }

    /** Output captured on a Windows 11 laptop with two USB cameras and OBS installed. */
    @Test
    void windowsListingSeparatesReachableCamerasFromDirectShowOnlyOnes() {
        String pnp = "USB\\VID_5986&PID_2174&MI_02\\6&3B5246B5&1&0002|Integrated IR Camera\r\n"
                + "USB\\VID_5986&PID_2174&MI_00\\6&3B5246B5&1&0000|Integrated Camera\r\n";
        String reg = "\r\nHKEY_CLASSES_ROOT\\CLSID\\{860BB310-5D01-11d0-BD3B-00A0C911CE86}\\Instance\\"
                + "{A3FCE0F5-3493-419F-958A-ABA1250EC20B}\r\n"
                + "    FriendlyName    REG_SZ    OBS Virtual Camera\r\n\r\nEnd of search: 1 match(es) found.\r\n";
        List<CameraEndpoint> cameras = CameraListings.windowsPnp(pnp);
        cameras.addAll(CameraListings.windowsDirectShow(reg, false));
        CameraSourceReport report = CameraSourceReport.of("windows", cameras);

        assertEquals(3, cameras.size());
        assertEquals("Integrated Camera", cameras.get(1).getName());
        assertFalse(cameras.get(1).isVirtual());
        CameraEndpoint obs = cameras.get(2);
        assertEquals("OBS Virtual Camera", obs.getName());
        assertEquals("DSHOW\\{A3FCE0F5-3493-419F-958A-ABA1250EC20B}", obs.getHardwareId());
        assertTrue(obs.isVirtual());
        assertFalse(obs.isReachable(), "Media Foundation cannot open a DirectShow-only filter");
        assertTrue(report.reachableVirtualCameras().isEmpty());
        assertEquals(Arrays.asList(obs), report.virtualCameras());
    }

    @Test
    void windows11SoftwareCameraIsReachable() {
        List<CameraEndpoint> cameras = CameraListings.windowsPnp(
                "SWD\\VCAMDEVAPI\\{8F2A}|OBS Virtual Camera\nUSB\\VID_5986&PID_2174&MI_00\\6&3B5246B5|Integrated Camera\n");
        assertEquals(1, CameraSourceReport.of("windows", cameras).reachableVirtualCameras().size());
    }

    @Test
    void emptyDirectShowListingIsNotAnError() {
        assertTrue(CameraListings.windowsDirectShow("ERROR: The system was unable to find the specified registry key "
                + "or value.\r\n", false).isEmpty());
    }

    @Test
    void macListingIsParsed() {
        String out = "Camera:\n\n"
                + "    FaceTime HD Camera:\n\n"
                + "      Model ID: FaceTime HD Camera\n"
                + "      Unique ID: 0x8020000005ac8514\n\n"
                + "    OBS Virtual Camera:\n\n"
                + "      Model ID: OBS Virtual Camera\n"
                + "      Unique ID: 7626645E-4425-469E-9D8B-97E0FA59AC75\n";
        List<CameraEndpoint> cameras = CameraListings.macProfiler(out);
        assertEquals(2, cameras.size());
        assertFalse(cameras.get(0).isVirtual());
        assertEquals("0x8020000005ac8514", cameras.get(0).getHardwareId());
        assertTrue(cameras.get(1).isVirtual());
        assertTrue(cameras.get(1).isReachable());
    }
}
