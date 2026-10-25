package com.urbantransport.trip.client;

import java.util.UUID;

public interface TicketingClient {

    TicketingSoldSummaryResponse getSoldSummary(UUID tripId, String accessToken);
}
