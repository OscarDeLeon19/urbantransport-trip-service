package com.urbantransport.trip.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TicketingSoldSummaryResponse(UUID tripId, long activeTickets, Integer maxSeatNumber) {
}
