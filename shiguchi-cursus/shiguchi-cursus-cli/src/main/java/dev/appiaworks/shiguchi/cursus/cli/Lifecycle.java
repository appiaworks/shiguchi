package dev.appiaworks.shiguchi.cursus.cli;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Stops a running command: SIGINT, a timeout or a reached event count. Safe to call from any thread. */
public final class Lifecycle {

    private final CountDownLatch stopped = new CountDownLatch(1);
    private volatile boolean interrupted;
    private volatile Runnable abort;

    /** Stops the command; {@code interrupted} marks a SIGINT (exit code 130). */
    public void stop(boolean interrupted) {
        if (interrupted) this.interrupted = true;
        stopped.countDown();
        Runnable current = abort;
        if (current != null) {
            try {
                current.run();
            } catch (RuntimeException ignored) {
                // closing a stream that is already closed
            }
        }
    }

    public boolean isStopped() {
        return stopped.getCount() == 0;
    }

    public boolean wasInterrupted() {
        return interrupted;
    }

    /** What to run to break a blocked read when the command is stopped; runs at once if it already was. */
    void onStop(Runnable abort) {
        this.abort = abort;
        if (isStopped() && abort != null) abort.run();
    }

    /** Sleeps unless stopped first; returns false when the command was stopped. */
    boolean sleep(long millis) {
        try {
            return !stopped.await(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
