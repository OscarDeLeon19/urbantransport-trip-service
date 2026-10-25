package com.urbantransport.trip.dto;

import com.urbantransport.trip.domain.TripType;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

public record UpdateTripRequest(
        TripType type,
        @NotNull UUID busId,
        @NotNull UUID driverId,
        @NotNull Instant scheduledDeparture,
        @NotNull Instant estimatedArrival,
        @NotNull Instant operationAt) {
}
