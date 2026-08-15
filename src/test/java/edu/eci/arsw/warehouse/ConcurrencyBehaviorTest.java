package edu.eci.arsw.warehouse;

import edu.eci.arsw.warehouse.app.WarehouseSimulation;
import edu.eci.arsw.warehouse.model.WarehouseSnapshot;
import edu.eci.arsw.warehouse.verification.InvariantChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConcurrencyBehaviorTest {

    @Test
    void preservesInvariantsUnderConcurrentLoad() throws InterruptedException {
        for (int run = 0; run < 10; run++) {
            WarehouseSimulation simulation = new WarehouseSimulation(32, 500);

            simulation.start();
            simulation.awaitCompletion();

            assertTrue(InvariantChecker.check(simulation.snapshot()).valid());
        }
    }

    @Test
    void pauseReturnsOnlyAfterWorkersReachASafePoint() throws Exception {
        WarehouseSimulation simulation = new WarehouseSimulation(12, 180);
        simulation.start();

        simulation.pause();
        WarehouseSnapshot first = simulation.snapshot();
        Thread.sleep(100);
        WarehouseSnapshot second = simulation.snapshot();

        assertTrue(simulation.isPaused());
        assertEquals(first.pendingParcels(), second.pendingParcels());
        assertEquals(first.processedParcels(), second.processedParcels());
        assertEquals(first.deliveries(), second.deliveries());
        assertEquals(first.deliveries().size(), first.processedParcels());

        simulation.resume();
        simulation.awaitCompletion();

        assertTrue(InvariantChecker.check(simulation.snapshot()).valid());
    }
}
