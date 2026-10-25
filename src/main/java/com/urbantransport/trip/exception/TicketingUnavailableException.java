package com.urbantransport.trip.exception;

public class TicketingUnavailableException extends RuntimeException {

    public TicketingUnavailableException(String message) {
        super(message);
    }

    public TicketingUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
