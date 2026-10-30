package com.urbantransport.trip.client;

import com.urbantransport.trip.exception.ApiException;
import com.urbantransport.trip.exception.ErrorCodes;
import com.urbantransport.trip.exception.TicketingUnavailableException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TicketingClientImplTest {

    private static final String BASE_URL = "http://ticketing.test";
    private static final String ACCESS_TOKEN = "token-del-usuario";
    private static final UUID TRIP_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String SUMMARY_URL = BASE_URL + "/internal/trips/" + TRIP_ID + "/sold-summary";

    private MockRestServiceServer server;
    private CircuitBreaker circuitBreaker;
    private TicketingClientImpl ticketingClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        circuitBreaker = CircuitBreaker.of("ticketing", CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(2)
                .minimumNumberOfCalls(2)
                .failureRateThreshold(50)
                .recordExceptions(TicketingUnavailableException.class)
                .build());
        ticketingClient = new TicketingClientImpl(builder.build(), circuitBreaker);
    }

    @AfterEach
    void verifyRequests() {
        server.verify();
    }

    @Test
    void getSoldSummary_forwardsUserTokenAndReadsSummary() {
        server.expect(requestTo(SUMMARY_URL))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
                .andRespond(withSuccess("{\"tripId\":\"%s\",\"activeTickets\":12,\"maxSeatNumber\":38,\"extra\":1}"
                        .formatted(TRIP_ID), MediaType.APPLICATION_JSON));

        TicketingSoldSummaryResponse summary = ticketingClient.getSoldSummary(TRIP_ID, ACCESS_TOKEN);

        assertEquals(TRIP_ID, summary.tripId());
        assertEquals(12, summary.activeTickets());
        assertEquals(38, summary.maxSeatNumber());
    }

    @Test
    void summaryWithoutSoldSeats_hasNullMaxSeat() {
        server.expect(requestTo(SUMMARY_URL))
                .andRespond(withSuccess("{\"tripId\":\"%s\",\"activeTickets\":0,\"maxSeatNumber\":null}"
                        .formatted(TRIP_ID), MediaType.APPLICATION_JSON));

        TicketingSoldSummaryResponse summary = ticketingClient.getSoldSummary(TRIP_ID, ACCESS_TOKEN);

        assertEquals(0, summary.activeTickets());
        assertNull(summary.maxSeatNumber());
    }

    @Test
    void emptyBody_throwsBadGateway() {
        server.expect(requestTo(SUMMARY_URL)).andRespond(withSuccess());

        assertApiError(HttpStatus.BAD_GATEWAY, ErrorCodes.TICKETING_ERROR,
                () -> ticketingClient.getSoldSummary(TRIP_ID, ACCESS_TOKEN));
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 409})
    void clientError_throwsBadGateway(int status) {
        server.expect(requestTo(SUMMARY_URL)).andRespond(withStatus(HttpStatus.valueOf(status)));

        assertApiError(HttpStatus.BAD_GATEWAY, ErrorCodes.TICKETING_ERROR,
                () -> ticketingClient.getSoldSummary(TRIP_ID, ACCESS_TOKEN));
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 502, 503})
    void serverError_throwsServiceUnavailable(int status) {
        server.expect(requestTo(SUMMARY_URL)).andRespond(withStatus(HttpStatus.valueOf(status)));

        assertApiError(HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.TICKETING_UNAVAILABLE,
                () -> ticketingClient.getSoldSummary(TRIP_ID, ACCESS_TOKEN));
    }

    @Test
    void timeout_throwsServiceUnavailable() {
        server.expect(requestTo(SUMMARY_URL)).andRespond(withException(new SocketTimeoutException("Read timed out")));

        assertApiError(HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.TICKETING_UNAVAILABLE,
                () -> ticketingClient.getSoldSummary(TRIP_ID, ACCESS_TOKEN));
    }

    @Test
    void openCircuit_failsFastWithoutCallingTicketing() {
        circuitBreaker.transitionToOpenState();

        assertApiError(HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.TICKETING_UNAVAILABLE,
                () -> ticketingClient.getSoldSummary(TRIP_ID, ACCESS_TOKEN));
    }

    @Test
    void repeatedServerErrors_openCircuit() {
        server.expect(ExpectedCount.times(2), requestTo(SUMMARY_URL)).andRespond(withServerError());

        assertApiError(HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.TICKETING_UNAVAILABLE,
                () -> ticketingClient.getSoldSummary(TRIP_ID, ACCESS_TOKEN));
        assertApiError(HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.TICKETING_UNAVAILABLE,
                () -> ticketingClient.getSoldSummary(TRIP_ID, ACCESS_TOKEN));

        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState());
        assertApiError(HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.TICKETING_UNAVAILABLE,
                () -> ticketingClient.getSoldSummary(TRIP_ID, ACCESS_TOKEN));
    }

    @Test
    void repeatedClientErrors_doNotOpenCircuit() {
        server.expect(ExpectedCount.times(2), requestTo(SUMMARY_URL)).andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertApiError(HttpStatus.BAD_GATEWAY, ErrorCodes.TICKETING_ERROR,
                () -> ticketingClient.getSoldSummary(TRIP_ID, ACCESS_TOKEN));
        assertApiError(HttpStatus.BAD_GATEWAY, ErrorCodes.TICKETING_ERROR,
                () -> ticketingClient.getSoldSummary(TRIP_ID, ACCESS_TOKEN));

        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState());
    }

    private static void assertApiError(HttpStatus status, String code, Executable action) {
        ApiException exception = assertThrows(ApiException.class, action);
        assertEquals(status, exception.getStatus());
        assertEquals(code, exception.getCode());
    }
}
