package io.github.dsheirer.airspy;

/** Error codes of {@code enum airspy_error}. */
public enum AirspyError {
    INVALID_PARAM(-2),
    NOT_FOUND(-5),
    BUSY(-6),
    NO_MEM(-11),
    UNSUPPORTED(-12),
    LIBUSB(-1000),
    THREAD(-1001),
    STREAMING_THREAD_ERR(-1002),
    STREAMING_STOPPED(-1003),
    OTHER(-9999);

    private final int code;

    AirspyError(int code) {
        this.code = code;
    }

    /** Returns the native error code. */
    public int code() {
        return code;
    }

    /** Returns the error for a native code, or {@link #OTHER} if it is unknown. */
    public static AirspyError fromCode(int code) {
        for (AirspyError error : values()) {
            if (error.code == code) {
                return error;
            }
        }
        return OTHER;
    }
}
