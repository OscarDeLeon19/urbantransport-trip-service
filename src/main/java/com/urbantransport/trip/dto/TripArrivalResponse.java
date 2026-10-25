package com.urbantransport.trip.dto;

import com.urbantransport.trip.domain.TripArrivalEntity;
import com.urbantransport.trip.domain.TripDepartureEntity;
import lombok.Value;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Value
public class TripArrivalResponse {
    UUID id;
    UUID tripId;
    Instant actualArrivalAt;
    BigDecimal odometerStart;
    BigDecimal odometerEnd;
    BigDecimal kmTraveled;
    BigDecimal fuelCost;
    BigDecimal driverPay;
    UUID registeredBy;
    Instant registeredAt;

    public static TripArrivalResponse fromEntity(TripArrivalEntity arrival, TripDepartureEntity departure) {
        return new TripArrivalResponse(
                arrival.getId(),
                arrival.getTripId(),
                arrival.getActualArrivalAt(),
                departure.getOdometerStart(),
                arrival.getOdometerEnd(),
                arrival.getOdometerEnd().subtract(departure.getOdometerStart()),
                arrival.getFuelCost(),
                arrival.getDriverPay(),
                arrival.getRegisteredBy(),
                arrival.getRegisteredAt());
    }
}
