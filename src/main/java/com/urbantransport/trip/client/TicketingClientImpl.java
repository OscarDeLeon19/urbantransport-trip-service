package com.urbantransport.trip.client;

import com.urbantransport.trip.exception.ApiException;
import com.urbantransport.trip.exception.ErrorCodes;
import com.urbantransport.trip.exception.TicketingUnavailableException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class TicketingClientImpl implements TicketingClient {

    private static final String SOLD_SUMMARY_PATH = "/internal/trips/{id}/sold-summary";
    private static final String UNAVAILABLE_MESSAGE =
            "El servicio de boletos no está disponible, intenta más tarde";

    private final RestClient ticketingRestClient;
    private final CircuitBreaker ticketingCircuitBreaker;

    @Override
    public TicketingSoldSummaryResponse getSoldSummary(UUID tripId, String accessToken) {
        try {
            return ticketingCircuitBreaker.executeSupplier(() -> fetchSoldSummary(tripId, accessToken));
        } catch (CallNotPermittedException | TicketingUnavailableException exception) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.TICKETING_UNAVAILABLE, UNAVAILABLE_MESSAGE);
        }
    }

    private TicketingSoldSummaryResponse fetchSoldSummary(UUID tripId, String accessToken) {
        try {
            TicketingSoldSummaryResponse summary = ticketingRestClient.get()
                    .uri(SOLD_SUMMARY_PATH, tripId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .onStatus(status -> status.is5xxServerError(), (req, res) -> {
                        throw new TicketingUnavailableException("Ticketing respondió " + res.getStatusCode());
                    })
                    .onStatus(status -> status.is4xxClientError(), (req, res) -> {
                        log.error("Ticketing rechazó el resumen de vendidos del viaje {}: {}", tripId, res.getStatusCode());
                        throw new ApiException(HttpStatus.BAD_GATEWAY, ErrorCodes.TICKETING_ERROR,
                                "No se pudieron consultar los boletos vendidos, intenta más tarde");
                    })
                    .body(TicketingSoldSummaryResponse.class);
            if (summary == null) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, ErrorCodes.TICKETING_ERROR,
                        "No se pudieron consultar los boletos vendidos, intenta más tarde");
            }
            return summary;
        } catch (ResourceAccessException exception) {
            throw new TicketingUnavailableException("No se pudo contactar a ticketing", exception);
        }
    }
}
