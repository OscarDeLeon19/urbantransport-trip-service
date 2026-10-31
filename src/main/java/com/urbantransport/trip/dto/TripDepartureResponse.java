package com.urbantransport.trip.dto;

import com.urbantransport.trip.domain.TripDepartureEntity;
import lombok.Value;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Value
public class TripDepartureResponse {
    UUID id;
    UUID tripId;
    Instant actualDepartureAt;
    UUID busId;
    UUID driverId;
    BigDecimal odometerStart;
    UUID registeredBy;
    Instant registeredAt;

    public static TripDepartureResponse fromEntity(TripDepartureEntity departure) {
        return new TripDepartureResponse(
                departure.getId(),
                departure.getTripId(),
                departure.getActualDepartureAt(),
                departure.getBusId(),
                departure.getDriverId(),
                departure.getOdometerStart(),
                departure.getRegisteredBy(),
                departure.getRegisteredAt());
    }
}
