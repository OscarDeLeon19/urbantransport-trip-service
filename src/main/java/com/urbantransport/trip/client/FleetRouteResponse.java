package com.urbantransport.trip.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FleetRouteResponse(
        UUID id,
        UUID originBranchId,
        String originBranchName,
        UUID destinationBranchId,
        String destinationBranchName,
        BigDecimal distanceKm,
        BigDecimal ticketPrice) {
}
