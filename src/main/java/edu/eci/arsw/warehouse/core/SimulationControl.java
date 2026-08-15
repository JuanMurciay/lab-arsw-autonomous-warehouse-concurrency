package edu.eci.arsw.warehouse.core;

/** Coordinates pause/resume requests through a common monitor. */
public class SimulationControl {

    private boolean paused;
    private int activeWorkers;
    private int pausedWorkers;

    public SimulationControl(int workerCount) {
        if (workerCount < 1) {
            throw new IllegalArgumentException("workerCount must be positive");
        }
        activeWorkers = workerCount;
    }

    /**
     * Requests a pause and waits until every worker that is still active has
     * reached {@link #awaitIfPaused()}. Waiting here is intentional: when this
     * method returns, callers may take a stable cross-object snapshot.
     */
    public synchronized void pause() {
        paused = true;

        boolean interrupted = false;
        while (pausedWorkers < activeWorkers) {
            try {
                wait();
            } catch (InterruptedException ex) {
                interrupted = true;
            }
        }

        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    public synchronized void resume() {
        paused = false;
        notifyAll();
    }

    public synchronized void awaitIfPaused() {
        if (!paused) {
            return;
        }

        pausedWorkers++;
        notifyAll();

        boolean interrupted = false;
        while (paused) {
            try {
                wait();
            } catch (InterruptedException ex) {
                interrupted = true;
            }
        }

        pausedWorkers--;
        notifyAll();

        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** Removes a terminating worker from the pause barrier. */
    public synchronized void workerFinished() {
        if (activeWorkers == 0) {
            throw new IllegalStateException("All workers were already marked as finished");
        }
        activeWorkers--;
        notifyAll();
    }

    public synchronized boolean isPaused() {
        return paused;
    }
}
