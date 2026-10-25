package com.urbantransport.trip.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.Instant;

public record RegisterArrivalRequest(
        @NotNull Instant actualArrivalAt,
        @NotNull @PositiveOrZero @Digits(integer = 10, fraction = 2) BigDecimal odometerEnd,
        @NotNull @PositiveOrZero @Digits(integer = 8, fraction = 2) BigDecimal fuelCost) {
}
