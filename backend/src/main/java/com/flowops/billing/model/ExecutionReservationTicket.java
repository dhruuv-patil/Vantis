package com.flowops.billing.model;

import java.time.Instant;

/**
 * Result ticket for execution reservation.
 * @param reserved true if a counter was actually incremented in the DB
 * @param periodStart the start of the billing period in which it was reserved
 */
public record ExecutionReservationTicket(
        boolean reserved,
        Instant periodStart) {

    public static ExecutionReservationTicket notReserved() {
        return new ExecutionReservationTicket(false, null);
    }

    public static ExecutionReservationTicket reserved(Instant periodStart) {
        return new ExecutionReservationTicket(true, periodStart);
    }
}
