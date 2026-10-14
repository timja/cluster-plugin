package io.jenkins.plugins.cnj.store;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Logs in blob storage: each one a stream that only grows while its build runs, written by one controller at a time
 * (the build's owner) and read by any. How a log is laid out in blobs is up to the backend.
 */
public interface LogBlobs {

    /** How long a log is (what was appended to it), or 0 if there is none. */
    long size(@NonNull String log) throws IOException;

    /**
     * Appends to a log, creating it if needed, if it is as long as this writer knows it to be.
     *
     * @param position the length of the log as this writer knows it
     * @return false if the log has another length: someone else appended to it (or deleted it)
     */
    boolean appendAt(@NonNull String log, @NonNull byte[] bytes, int off, int len, long position) throws IOException;

    /**
     * Copies a whole log to a stream.
     *
     * @return false if there is no such log
     */
    boolean download(@NonNull String log, @NonNull OutputStream out) throws IOException;

    /**
     * Reads a log from an offset to its current end, as for following a log which is still being appended to.
     *
     * @return the bytes, empty if there are none beyond {@code offset}, or null if there is no such log
     */
    @CheckForNull
    byte[] readFrom(@NonNull String log, long offset) throws IOException;

    /** Deletes a log. */
    void delete(@NonNull String log) throws IOException;

    /** Called once nothing more will be appended to a log (its build completed), to tidy up how it is stored. */
    default void complete(@NonNull String log) throws IOException {}
}
