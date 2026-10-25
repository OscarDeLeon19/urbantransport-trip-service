package com.urbantransport.trip.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FleetDriverResponse(
        UUID id,
        UUID userId,
        String fullName,
        LocalDate licenseExpiresOn,
        BigDecimal baseSalaryPerTrip,
        UUID branchId,
        boolean active) {
}
