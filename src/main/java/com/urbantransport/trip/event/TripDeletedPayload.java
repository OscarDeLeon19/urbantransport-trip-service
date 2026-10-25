package com.urbantransport.trip.event;

import com.urbantransport.trip.domain.TripEntity;
import com.urbantransport.trip.domain.TripStatus;
import com.urbantransport.trip.domain.TripType;
import lombok.Value;

import java.util.UUID;

@Value
public class TripDeletedPayload {
    UUID tripId;
    TripType type;
    UUID routeId;
    UUID rentalId;
    UUID originBranchId;
    UUID destinationBranchId;
    UUID busId;
    UUID driverId;
    TripStatus status;

    public static TripDeletedPayload fromEntity(TripEntity trip) {
        return new TripDeletedPayload(
                trip.getId(),
                trip.getType(),
                trip.getRouteId(),
                trip.getRentalId(),
                trip.getOriginBranchId(),
                trip.getDestinationBranchId(),
                trip.getBusId(),
                trip.getDriverId(),
                trip.getStatus());
    }
}
