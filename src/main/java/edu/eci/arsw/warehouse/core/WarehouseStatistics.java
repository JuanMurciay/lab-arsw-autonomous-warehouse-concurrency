package edu.eci.arsw.warehouse.core;

/** Thread-safe aggregate of warehouse processing statistics. */
public class WarehouseStatistics {

    private final Object lock = new Object();
    private int processedParcels;
    private long totalProcessingMillis;

    public void recordProcessed(long elapsedMillis) {
        synchronized (lock) {
            processedParcels++;
            totalProcessingMillis += elapsedMillis;
        }
    }

    public int processedParcels() {
        synchronized (lock) {
            return processedParcels;
        }
    }

    public long totalProcessingMillis() {
        synchronized (lock) {
            return totalProcessingMillis;
        }
    }
}
