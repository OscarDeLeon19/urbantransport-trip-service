package com.urbantransport.trip.event;

import com.urbantransport.trip.domain.TripArrivalEntity;
import com.urbantransport.trip.domain.TripDepartureEntity;
import com.urbantransport.trip.domain.TripEntity;
import com.urbantransport.trip.domain.TripStatus;
import com.urbantransport.trip.domain.TripType;
import lombok.Value;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Value
public class TripCompletedPayload {
    UUID tripId;
    TripType type;
    UUID routeId;
    UUID rentalId;
    UUID originBranchId;
    UUID destinationBranchId;
    UUID busId;
    UUID driverId;
    Instant actualDepartureAt;
    Instant actualArrivalAt;
    BigDecimal odometerStart;
    BigDecimal odometerEnd;
    BigDecimal kmTraveled;
    BigDecimal fuelCost;
    BigDecimal driverPay;
    TripStatus status;

    public static TripCompletedPayload from(TripEntity trip, TripDepartureEntity departure, TripArrivalEntity arrival) {
        return new TripCompletedPayload(
                trip.getId(),
                trip.getType(),
                trip.getRouteId(),
                trip.getRentalId(),
                trip.getOriginBranchId(),
                trip.getDestinationBranchId(),
                departure.getBusId(),
                departure.getDriverId(),
                departure.getActualDepartureAt(),
                arrival.getActualArrivalAt(),
                departure.getOdometerStart(),
                arrival.getOdometerEnd(),
                arrival.getOdometerEnd().subtract(departure.getOdometerStart()),
                arrival.getFuelCost(),
                arrival.getDriverPay(),
                trip.getStatus());
    }
}
