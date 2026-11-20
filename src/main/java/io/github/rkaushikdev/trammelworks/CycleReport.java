package io.github.rkaushikdev.trammelworks;

import io.github.rkaushikdev.trammelworks.ship.ShipReport;

/**
 * A collect and a ship, together.
 *
 * @param collect what the collect pass moved
 * @param ship what the uplink pass sent
 */
public record CycleReport(CollectReport collect, ShipReport ship) {

    /** A cycle that did nothing. */
    public CycleReport() {
        this(new CollectReport(), new ShipReport());
    }

    /** Whether neither half did anything. */
    public boolean idle() {
        return collect.idle() && ship.idle();
    }
}
