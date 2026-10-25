package com.urbantransport.trip.dto;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

public record CreateTripRequest(
        @NotNull UUID routeId,
        @NotNull UUID busId,
        @NotNull UUID driverId,
        @NotNull Instant scheduledDeparture,
        @NotNull Instant estimatedArrival,
        @NotNull Instant operationAt) {
}
