package com.urbantransport.trip.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.Instant;

public record RegisterDepartureRequest(
        @NotNull Instant actualDepartureAt,
        @NotNull @PositiveOrZero @Digits(integer = 10, fraction = 2) BigDecimal odometerStart) {
}
