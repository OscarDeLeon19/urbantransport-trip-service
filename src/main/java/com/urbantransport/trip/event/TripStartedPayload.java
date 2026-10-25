package com.urbantransport.trip.event;

import com.urbantransport.trip.domain.TripDepartureEntity;
import com.urbantransport.trip.domain.TripEntity;
import com.urbantransport.trip.domain.TripStatus;
import com.urbantransport.trip.domain.TripType;
import lombok.Value;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Value
public class TripStartedPayload {
    UUID tripId;
    TripType type;
    UUID rentalId;
    UUID originBranchId;
    UUID busId;
    UUID driverId;
    Instant actualDepartureAt;
    BigDecimal odometerStart;
    TripStatus status;

    public static TripStartedPayload from(TripEntity trip, TripDepartureEntity departure) {
        return new TripStartedPayload(
                trip.getId(),
                trip.getType(),
                trip.getRentalId(),
                trip.getOriginBranchId(),
                departure.getBusId(),
                departure.getDriverId(),
                departure.getActualDepartureAt(),
                departure.getOdometerStart(),
                trip.getStatus());
    }
}
