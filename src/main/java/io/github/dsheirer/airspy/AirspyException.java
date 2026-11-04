package io.github.dsheirer.airspy;

import io.github.dsheirer.airspy.internal.Native;

/** Thrown when a libairspy function reports an error. */
public class AirspyException extends Exception {
    private static final long serialVersionUID = 1L;

    private final int code;

    AirspyException(String function, int code) {
        super(function + " failed: " + Native.errorName(code) + " (" + code + ")");
        this.code = code;
    }

    /** Returns the {@code airspy_error} value the native function returned. */
    public int code() {
        return code;
    }

    /** Returns the error as an enum, or {@link AirspyError#OTHER} for an unknown code. */
    public AirspyError error() {
        return AirspyError.fromCode(code);
    }
}
