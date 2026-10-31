package com.urbantransport.trip.event;

import com.urbantransport.trip.exception.ApiException;
import lombok.Value;

import java.util.UUID;

@Value
public class PrivateTripRejectedPayload {
    UUID rentalId;
    UUID busId;
    UUID driverId;
    String code;
    String reason;

    public static PrivateTripRejectedPayload from(RentalAssignedPayload rental, ApiException cause) {
        return new PrivateTripRejectedPayload(
                rental.rentalId(),
                rental.busId(),
                rental.driverId(),
                cause.getCode(),
                cause.getMessage());
    }
}
