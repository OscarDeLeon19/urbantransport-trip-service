package com.urbantransport.trip.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RentalAssignedPayload(
        UUID rentalId,
        UUID branchId,
        UUID busId,
        UUID driverId,
        Integer passengers,
        BigDecimal distanceKm,
        UUID assignedBy,
        List<Leg> legs) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Leg(
            String originText,
            String destinationText,
            Instant departureAt,
            Instant estimatedArrival) {
    }
}
