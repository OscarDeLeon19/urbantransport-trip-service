package com.urbantransport.trip.exception;

public class FleetUnavailableException extends RuntimeException {

    public FleetUnavailableException(String message) {
        super(message);
    }

    public FleetUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
