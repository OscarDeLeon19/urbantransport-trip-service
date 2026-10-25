package com.urbantransport.trip.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FleetBusResponse(
        UUID id,
        String plate,
        Integer capacity,
        Short seatRows,
        Short seatColumns,
        Short aisleAfterColumn,
        String status,
        BigDecimal odometerKm,
        UUID branchId) {
}
