package edu.eci.arsw.warehouse.core;

import edu.eci.arsw.warehouse.model.Parcel;

import java.util.ArrayList;
import java.util.List;

/**
 * Thread-safe pending-parcel queue with an atomic take operation.
 */
public class PackageQueue {

    private final List<Parcel> pending = new ArrayList<>();

    public PackageQueue(List<Parcel> parcels) {
        pending.addAll(parcels);
    }

    public Parcel takeNext() {
        synchronized (pending) {
            if (pending.isEmpty()) {
                return null;
            }

            return pending.remove(0);
        }
    }

    public int pendingCount() {
        synchronized (pending) {
            return pending.size();
        }
    }
}