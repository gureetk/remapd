package ca.gureet.remapd.adb;

import java.io.IOException;

/** adbd didn't accept the app's key, or wasn't asked to. */
public final class AdbAuthException extends IOException {
    public enum Reason {
        /** adbd doesn't know the key (it was never allowed, or the TV forgot it). */
        KEY_REJECTED,
        /** The TV asked the user, who didn't allow it in time (or said no). */
        NOT_ALLOWED,
        /** adbd asked for something this connection refuses to do, like signing on a TLS port. */
        REFUSED,
    }

    private static final long serialVersionUID = 1;

    public final Reason reason;

    AdbAuthException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }
}
