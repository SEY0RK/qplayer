package dev.t1m3.qplayer.sync;

/** A sync failure with a message safe to show in the UI. */
public final class SyncException extends Exception {

    public SyncException(String message) {
        super(message);
    }

    public SyncException(String message, Throwable cause) {
        super(message, cause);
    }
}
