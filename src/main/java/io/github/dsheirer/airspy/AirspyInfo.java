package io.github.dsheirer.airspy;

import java.util.Arrays;
import java.util.Locale;

/** Command line tool that prints the library version and the connected devices. */
public final class AirspyInfo {
    private AirspyInfo() {
    }

    /** Runs the tool. */
    public static void main(String[] args) {
        System.out.println("libairspy " + Airspy.libraryVersion());
        long[] serials;
        try {
            serials = Airspy.listDevices();
        } catch (AirspyException e) {
            // Machines without USB access, such as containers, end up here.
            System.out.println("Unable to list devices: " + e.getMessage());
            return;
        }
        System.out.println("Devices found: " + serials.length);
        for (long serial : serials) {
            System.out.println(String.format(Locale.ROOT, "Serial 0x%016X", serial));
            try (AirspyDevice device = Airspy.open(serial)) {
                PartIdSerialNumber id = device.partIdSerialNumber();
                System.out.println("  Board:        " + device.boardName() + " (" + device.boardId() + ")");
                System.out.println("  Firmware:     " + device.firmwareVersion());
                System.out.println(String.format(Locale.ROOT, "  Part id:      0x%08X 0x%08X",
                    id.partId1(), id.partId2()));
                device.setSampleType(SampleType.FLOAT32_IQ);
                System.out.println("  Sample rates: " + Arrays.toString(device.sampleRates()) + " (I/Q)");
            } catch (AirspyException e) {
                System.out.println("  Unable to open: " + e.getMessage());
            }
        }
    }
}
