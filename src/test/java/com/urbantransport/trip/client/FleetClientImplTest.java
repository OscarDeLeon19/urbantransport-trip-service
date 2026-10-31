package com.urbantransport.trip.client;

import com.urbantransport.trip.config.FleetClientProperties;
import com.urbantransport.trip.exception.ApiException;
import com.urbantransport.trip.exception.ErrorCodes;
import com.urbantransport.trip.exception.FleetUnavailableException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class FleetClientImplTest {

    private static final String BASE_URL = "http://fleet.test";
    private static final String API_KEY = "clave-interna";
    private static final UUID ROUTE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID BUS_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID DRIVER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID USER_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID BRANCH_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final String BUS_URL = BASE_URL + "/internal/buses/" + BUS_ID;

    private MockRestServiceServer server;
    private RestClient restClient;
    private CircuitBreaker circuitBreaker;
    private FleetClientImpl fleetClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        restClient = builder.build();
        circuitBreaker = CircuitBreaker.of("fleet", CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(2)
                .minimumNumberOfCalls(2)
                .failureRateThreshold(50)
                .recordExceptions(FleetUnavailableException.class)
                .build());
        fleetClient = clientWithKey(API_KEY);
    }

    @AfterEach
    void verifyRequests() {
        server.verify();
    }

    @Test
    void getRoute_readsRouteSnapshot() {
        server.expect(requestTo(BASE_URL + "/internal/routes/" + ROUTE_ID))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Internal-Key", API_KEY))
                .andRespond(withSuccess("""
                        {"id":"%s","originBranchId":"%s","originBranchName":"Central","destinationBranchName":"Xela",
                         "distanceKm":120.50,"ticketPrice":45,"deleted":false}
                        """.formatted(ROUTE_ID, BRANCH_ID), MediaType.APPLICATION_JSON));

        FleetRouteResponse route = fleetClient.getRoute(ROUTE_ID);

        assertEquals(ROUTE_ID, route.id());
        assertEquals(BRANCH_ID, route.originBranchId());
        assertEquals("Central", route.originBranchName());
        assertEquals("Xela", route.destinationBranchName());
        assertEquals(0, new BigDecimal("120.50").compareTo(route.distanceKm()));
        assertEquals(0, new BigDecimal("45").compareTo(route.ticketPrice()));
    }

    @Test
    void getBus_readsLayoutAndOdometer() {
        server.expect(requestTo(BUS_URL))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Internal-Key", API_KEY))
                .andRespond(withSuccess("""
                        {"id":"%s","plate":"P-001","capacity":40,"seatRows":10,"seatColumns":4,"aisleAfterColumn":2,
                         "status":"DISPONIBLE","odometerKm":1000.5,"branchId":"%s","brand":"Volvo"}
                        """.formatted(BUS_ID, BRANCH_ID), MediaType.APPLICATION_JSON));

        FleetBusResponse bus = fleetClient.getBus(BUS_ID);

        assertEquals(BUS_ID, bus.id());
        assertEquals("P-001", bus.plate());
        assertEquals(40, bus.capacity());
        assertEquals((short) 4, bus.seatColumns());
        assertEquals((short) 2, bus.aisleAfterColumn());
        assertEquals("DISPONIBLE", bus.status());
        assertEquals(0, new BigDecimal("1000.5").compareTo(bus.odometerKm()));
        assertEquals(BRANCH_ID, bus.branchId());
    }

    @Test
    void getDriver_readsLicenseSalaryAndState() {
        server.expect(requestTo(BASE_URL + "/internal/drivers/" + DRIVER_ID))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Internal-Key", API_KEY))
                .andRespond(withSuccess(driverJson(false), MediaType.APPLICATION_JSON));

        FleetDriverResponse driver = fleetClient.getDriver(DRIVER_ID);

        assertEquals(DRIVER_ID, driver.id());
        assertEquals(USER_ID, driver.userId());
        assertEquals(LocalDate.of(2030, 1, 15), driver.licenseExpiresOn());
        assertEquals(0, new BigDecimal("150").compareTo(driver.baseSalaryPerTrip()));
        assertFalse(driver.active());
    }

    @Test
    void getDriverByUserId_queriesByUser() {
        server.expect(requestTo(BASE_URL + "/internal/drivers/by-user/" + USER_ID))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Internal-Key", API_KEY))
                .andRespond(withSuccess(driverJson(true), MediaType.APPLICATION_JSON));

        FleetDriverResponse driver = fleetClient.getDriverByUserId(USER_ID);

        assertEquals(DRIVER_ID, driver.id());
        assertTrue(driver.active());
    }

    @Test
    void missingApiKey_sendsEmptyHeader() {
        server.expect(requestTo(BUS_URL))
                .andExpect(header("X-Internal-Key", ""))
                .andRespond(withSuccess("{\"id\":\"%s\"}".formatted(BUS_ID), MediaType.APPLICATION_JSON));

        assertEquals(BUS_ID, clientWithKey(null).getBus(BUS_ID).id());
    }

    @Test
    void routeNotFound_throwsRouteNotFound() {
        server.expect(requestTo(BASE_URL + "/internal/routes/" + ROUTE_ID)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertApiError(HttpStatus.NOT_FOUND, ErrorCodes.ROUTE_NOT_FOUND, () -> fleetClient.getRoute(ROUTE_ID));
    }

    @Test
    void busNotFound_throwsBusNotFound() {
        server.expect(requestTo(BUS_URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertApiError(HttpStatus.NOT_FOUND, ErrorCodes.BUS_NOT_FOUND, () -> fleetClient.getBus(BUS_ID));
    }

    @Test
    void driverNotFound_throwsDriverNotFound() {
        server.expect(requestTo(BASE_URL + "/internal/drivers/" + DRIVER_ID)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertApiError(HttpStatus.NOT_FOUND, ErrorCodes.DRIVER_NOT_FOUND, () -> fleetClient.getDriver(DRIVER_ID));
    }

    @Test
    void userWithoutDriver_throwsDriverNotFound() {
        server.expect(requestTo(BASE_URL + "/internal/drivers/by-user/" + USER_ID))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        ApiException exception = assertThrows(ApiException.class, () -> fleetClient.getDriverByUserId(USER_ID));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
        assertEquals(ErrorCodes.DRIVER_NOT_FOUND, exception.getCode());
        assertEquals("Tu usuario no está registrado como chofer", exception.getMessage());
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 409})
    void clientError_throwsBadGateway(int status) {
        server.expect(requestTo(BUS_URL)).andRespond(withStatus(HttpStatus.valueOf(status)));

        assertApiError(HttpStatus.BAD_GATEWAY, ErrorCodes.FLEET_ERROR, () -> fleetClient.getBus(BUS_ID));
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 502, 503})
    void serverError_throwsServiceUnavailable(int status) {
        server.expect(requestTo(BUS_URL)).andRespond(withStatus(HttpStatus.valueOf(status)));

        assertApiError(HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.FLEET_UNAVAILABLE, () -> fleetClient.getBus(BUS_ID));
    }

    @Test
    void timeout_throwsServiceUnavailable() {
        server.expect(requestTo(BUS_URL)).andRespond(withException(new SocketTimeoutException("Read timed out")));

        assertApiError(HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.FLEET_UNAVAILABLE, () -> fleetClient.getBus(BUS_ID));
    }

    @Test
    void openCircuit_failsFastWithoutCallingFleet() {
        circuitBreaker.transitionToOpenState();

        assertApiError(HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.FLEET_UNAVAILABLE,
                () -> fleetClient.getRoute(ROUTE_ID));
    }

    @Test
    void repeatedServerErrors_openCircuit() {
        server.expect(ExpectedCount.times(2), requestTo(BUS_URL)).andRespond(withServerError());

        assertApiError(HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.FLEET_UNAVAILABLE, () -> fleetClient.getBus(BUS_ID));
        assertApiError(HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.FLEET_UNAVAILABLE, () -> fleetClient.getBus(BUS_ID));

        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState());
        assertApiError(HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.FLEET_UNAVAILABLE, () -> fleetClient.getBus(BUS_ID));
    }

    @Test
    void repeatedClientErrors_doNotOpenCircuit() {
        server.expect(ExpectedCount.times(2), requestTo(BUS_URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertApiError(HttpStatus.BAD_GATEWAY, ErrorCodes.FLEET_ERROR, () -> fleetClient.getBus(BUS_ID));
        assertApiError(HttpStatus.BAD_GATEWAY, ErrorCodes.FLEET_ERROR, () -> fleetClient.getBus(BUS_ID));

        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState());
    }

    @Test
    void repeatedNotFound_doNotOpenCircuit() {
        server.expect(ExpectedCount.times(2), requestTo(BUS_URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertApiError(HttpStatus.NOT_FOUND, ErrorCodes.BUS_NOT_FOUND, () -> fleetClient.getBus(BUS_ID));
        assertApiError(HttpStatus.NOT_FOUND, ErrorCodes.BUS_NOT_FOUND, () -> fleetClient.getBus(BUS_ID));

        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState());
    }

    private FleetClientImpl clientWithKey(String apiKey) {
        FleetClientProperties properties = new FleetClientProperties(BASE_URL, apiKey, Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                new FleetClientProperties.CircuitBreakerProperties(2, 2, 50f, Duration.ofSeconds(30)));
        return new FleetClientImpl(restClient, circuitBreaker, properties);
    }

    private static String driverJson(boolean active) {
        return """
                {"id":"%s","userId":"%s","fullName":"Juan Perez","licenseNumber":"LIC-001",
                 "licenseExpiresOn":"2030-01-15","baseSalaryPerTrip":150,"branchId":"%s","active":%s}
                """.formatted(DRIVER_ID, USER_ID, BRANCH_ID, active);
    }

    private static void assertApiError(HttpStatus status, String code, Executable action) {
        ApiException exception = assertThrows(ApiException.class, action);
        assertEquals(status, exception.getStatus());
        assertEquals(code, exception.getCode());
    }
}
