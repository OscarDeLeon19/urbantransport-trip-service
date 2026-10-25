package com.urbantransport.trip.dto;

import com.urbantransport.trip.domain.TripEntity;
import com.urbantransport.trip.domain.TripStatus;
import com.urbantransport.trip.domain.TripType;
import lombok.Value;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Value
public class TripResponse {
    UUID id;
    TripType type;
    UUID routeId;
    UUID originBranchId;
    UUID destinationBranchId;
    String originText;
    String destinationText;
    UUID busId;
    UUID driverId;
    Instant scheduledDeparture;
    Instant estimatedArrival;
    BigDecimal ticketPrice;
    BigDecimal distanceKm;
    TripStatus status;
    UUID rentalId;
    UUID createdBy;
    Instant createdAt;

    public static TripResponse fromEntity(TripEntity trip) {
        return new TripResponse(
                trip.getId(),
                trip.getType(),
                trip.getRouteId(),
                trip.getOriginBranchId(),
                trip.getDestinationBranchId(),
                trip.getOriginText(),
                trip.getDestinationText(),
                trip.getBusId(),
                trip.getDriverId(),
                trip.getScheduledDeparture(),
                trip.getEstimatedArrival(),
                trip.getTicketPrice(),
                trip.getDistanceKm(),
                trip.getStatus(),
                trip.getRentalId(),
                trip.getCreatedBy(),
                trip.getCreatedAt());
    }
}
