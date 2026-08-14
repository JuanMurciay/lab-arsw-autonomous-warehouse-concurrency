package edu.eci.arsw.warehouse.core;

import edu.eci.arsw.warehouse.model.DeliveryRecord;

import java.util.ArrayList;
import java.util.List;


public class DeliveryRegistry {

    private int nextPosition = 1;
    private final List<DeliveryRecord> deliveries = new ArrayList<>();

    public void register(int robotId, int parcelId, long elapsedMillis) {
        synchronized (deliveries) {
            int assignedPosition = nextPosition++;
            deliveries.add(new DeliveryRecord(assignedPosition, robotId, parcelId, elapsedMillis));
        }
    }

    public List<DeliveryRecord> snapshot() {
        synchronized (deliveries) {
            return List.copyOf(deliveries);
        }
    }
}
