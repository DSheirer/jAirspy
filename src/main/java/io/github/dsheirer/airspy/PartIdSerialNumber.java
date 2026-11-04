package io.github.dsheirer.airspy;

import java.util.Locale;

/**
 * Identification read from the device's microcontroller.
 *
 * @param partId1 first word of the microcontroller part id
 * @param partId2 second word of the microcontroller part id
 * @param serialNumber the 64-bit serial number, as used by {@link Airspy#open(long)}
 */
public record PartIdSerialNumber(long partId1, long partId2, long serialNumber) {
    @Override
    public String toString() {
        return String.format(Locale.ROOT, "part id 0x%08X 0x%08X, serial 0x%016X", partId1, partId2, serialNumber);
    }
}
