package com.urbantransport.trip.web;

import com.urbantransport.trip.AbstractIntegrationTest;
import com.urbantransport.trip.client.TicketingSoldSummaryResponse;
import com.urbantransport.trip.domain.TripEntity;
import com.urbantransport.trip.domain.TripStatus;
import com.urbantransport.trip.domain.TripType;
import com.urbantransport.trip.exception.ApiException;
import com.urbantransport.trip.exception.ErrorCodes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TripApiIT extends AbstractIntegrationTest {

    private static final UUID BRANCH_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_BRANCH_ID = UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID DESTINATION_ID = UUID.fromString("10000000-0000-0000-0000-000000000003");
    private static final UUID ROUTE_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_ROUTE_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID BUS_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_BUS_ID = UUID.fromString("30000000-0000-0000-0000-000000000002");
    private static final UUID DRIVER_ID = UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_DRIVER_ID = UUID.fromString("40000000-0000-0000-0000-000000000002");

    private final LocalDate tomorrow = today().plusDays(1);
    private final Instant departure = at(tomorrow, 8);
    private final Instant arrival = at(tomorrow, 11);

    @Autowired
    private JsonMapper jsonMapper;

    @BeforeEach
    void stubFleet() {
        when(fleetClient.getRoute(ROUTE_ID)).thenReturn(route(ROUTE_ID, BRANCH_ID, DESTINATION_ID));
        when(fleetClient.getBus(BUS_ID)).thenReturn(bus(BUS_ID, BRANCH_ID));
        when(fleetClient.getDriver(DRIVER_ID)).thenReturn(driver(DRIVER_ID, USER_ID, BRANCH_ID));
        when(fleetClient.getDriver(OTHER_DRIVER_ID)).thenReturn(driver(OTHER_DRIVER_ID, UUID.randomUUID(), BRANCH_ID));
    }

    @Test
    void create_persistsTripWithRouteSnapshotAndRecordsEvent() throws Exception {
        MvcResult result = mockMvc.perform(post("/trips").with(branchAdmin(BRANCH_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson(departure, arrival)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("REGULAR"))
                .andExpect(jsonPath("$.status").value("SCHEDULED"))
                .andExpect(jsonPath("$.originBranchId").value(BRANCH_ID.toString()))
                .andExpect(jsonPath("$.destinationBranchId").value(DESTINATION_ID.toString()))
                .andExpect(jsonPath("$.ticketPrice").value(45.0))
                .andExpect(jsonPath("$.distanceKm").value(120.5))
                .andReturn();

        UUID tripId = idAt(result, "$.id");
        TripEntity saved = tripRepository.findById(tripId).orElseThrow();
        assertEquals(TripType.REGULAR, saved.getType());
        assertEquals(ROUTE_ID, saved.getRouteId());
        assertEquals(BUS_ID, saved.getBusId());
        assertEquals(DRIVER_ID, saved.getDriverId());
        assertEquals(departure, saved.getScheduledDeparture());
        assertEquals(arrival, saved.getEstimatedArrival());
        assertEquals(0, TICKET_PRICE.compareTo(saved.getTicketPrice()));
        assertEquals(0, DISTANCE_KM.compareTo(saved.getDistanceKm()));
        assertEquals(USER_ID, saved.getCreatedBy());
        assertEquals(List.of("TripScheduled"), outboxEventTypes(tripId));

        JsonNode payload = jsonMapper.readTree(outboxPayload(tripId, "TripScheduled")).get("payload");
        assertEquals("Central", payload.get("originName").asString());
        assertEquals("Xela", payload.get("destinationName").asString());
        assertEquals(40, payload.get("capacity").asInt());
        assertEquals(4, payload.get("seatLayout").get("columns").asInt());
        assertEquals(2, payload.get("seatLayout").get("aisleAfterColumn").asInt());
        assertEquals(tomorrow.atTime(8, 0), LocalDateTime.parse(payload.get("departureAt").asString()));
    }

    @Test
    void create_withInvalidBody_returnsValidationError() throws Exception {
        mockMvc.perform(post("/trips").with(branchAdmin(BRANCH_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"routeId\":\"%s\"}".formatted(ROUTE_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCodes.VALIDATION_ERROR))
                .andExpect(jsonPath("$.message", containsString("busId")))
                .andExpect(jsonPath("$.message", containsString("driverId")))
                .andExpect(jsonPath("$.message", containsString("scheduledDeparture")))
                .andExpect(jsonPath("$.message", containsString("operationAt")))
                .andExpect(jsonPath("$.path").value("/trips"));

        assertEquals(0, tripRepository.count());
        verify(fleetClient, never()).getRoute(any());
    }

    @Test
    void create_withMalformedJson_returnsValidationError() throws Exception {
        mockMvc.perform(post("/trips").with(branchAdmin(BRANCH_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"routeId\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCodes.VALIDATION_ERROR));

        assertEquals(0, tripRepository.count());
    }

    @Test
    void create_withoutToken_returnsUnauthorized() throws Exception {
        mockMvc.perform(post("/trips")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson(departure, arrival)))
                .andExpect(status().isUnauthorized());

        assertEquals(0, tripRepository.count());
    }

    @ParameterizedTest
    @ValueSource(strings = {"SYSTEM_ADMIN", "CASHIER", "DRIVER", "CUSTOMER"})
    void create_withOtherRole_returnsAccessDenied(String role) throws Exception {
        mockMvc.perform(post("/trips").with(userWithRole(role, BRANCH_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson(departure, arrival)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCodes.ACCESS_DENIED));

        assertEquals(0, tripRepository.count());
    }

    @Test
    void create_withTokenWithoutBranch_returnsBranchRequired() throws Exception {
        mockMvc.perform(post("/trips").with(userWithRole("BRANCH_ADMIN", null))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson(departure, arrival)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCodes.BRANCH_REQUIRED));

        assertEquals(0, tripRepository.count());
    }

    @Test
    void create_onRouteFromOtherBranch_returnsForbidden() throws Exception {
        when(fleetClient.getRoute(OTHER_ROUTE_ID)).thenReturn(route(OTHER_ROUTE_ID, OTHER_BRANCH_ID, BRANCH_ID));

        mockMvc.perform(post("/trips").with(branchAdmin(BRANCH_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson(OTHER_ROUTE_ID, BUS_ID, DRIVER_ID, departure, arrival)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCodes.ROUTE_NOT_IN_BRANCH));

        assertEquals(0, tripRepository.count());
    }

    @Test
    void create_withBusAlreadyBusy_returnsOverlapAndSavesNothing() throws Exception {
        saveTrip(TripType.REGULAR, TripStatus.IN_TRANSIT, BRANCH_ID, ROUTE_ID, BUS_ID, OTHER_DRIVER_ID,
                departure.minus(Duration.ofHours(1)), departure.plus(Duration.ofHours(1)));

        mockMvc.perform(post("/trips").with(branchAdmin(BRANCH_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson(departure, arrival)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ErrorCodes.RESOURCE_OVERLAP));

        assertEquals(1, tripRepository.count());
        assertEquals(0, count("SELECT COUNT(*) FROM outbox_events"));
    }

    @Test
    void create_withDriverAlreadyBusy_returnsOverlap() throws Exception {
        saveTrip(TripType.PRIVATE, TripStatus.SCHEDULED, BRANCH_ID, null, OTHER_BUS_ID, DRIVER_ID,
                arrival.minus(Duration.ofMinutes(1)), arrival.plus(Duration.ofHours(2)));

        mockMvc.perform(post("/trips").with(branchAdmin(BRANCH_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson(departure, arrival)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ErrorCodes.RESOURCE_OVERLAP));

        assertEquals(1, tripRepository.count());
    }

    @Test
    void create_nextToAdjacentOrFinishedTrips_isNotAnOverlap() throws Exception {
        saveTrip(TripType.REGULAR, TripStatus.SCHEDULED, BRANCH_ID, ROUTE_ID, BUS_ID, DRIVER_ID,
                departure.minus(Duration.ofHours(3)), departure);
        saveTrip(TripType.REGULAR, TripStatus.SCHEDULED, BRANCH_ID, ROUTE_ID, BUS_ID, DRIVER_ID,
                arrival, arrival.plus(Duration.ofHours(3)));
        saveTrip(TripType.REGULAR, TripStatus.COMPLETED, BRANCH_ID, ROUTE_ID, BUS_ID, DRIVER_ID, departure, arrival);
        saveTrip(TripType.REGULAR, TripStatus.DELETED, BRANCH_ID, ROUTE_ID, BUS_ID, DRIVER_ID, departure, arrival);

        mockMvc.perform(post("/trips").with(branchAdmin(BRANCH_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson(departure, arrival)))
                .andExpect(status().isCreated());

        assertEquals(5, tripRepository.count());
    }

    @Test
    void create_whenFleetUnavailable_returnsServiceUnavailableAndSavesNothing() throws Exception {
        when(fleetClient.getRoute(ROUTE_ID)).thenThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                ErrorCodes.FLEET_UNAVAILABLE, "Fleet no disponible"));

        mockMvc.perform(post("/trips").with(branchAdmin(BRANCH_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson(departure, arrival)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(ErrorCodes.FLEET_UNAVAILABLE));

        assertEquals(0, tripRepository.count());
        assertEquals(0, count("SELECT COUNT(*) FROM outbox_events"));
    }

    @Test
    void update_changesScheduleAndRecordsEvent() throws Exception {
        TripEntity trip = saveTrip(TripType.REGULAR, TripStatus.SCHEDULED, BRANCH_ID, ROUTE_ID, BUS_ID, DRIVER_ID,
                departure);
        Instant newDeparture = departure.plus(Duration.ofMinutes(30));
        Instant newArrival = arrival.plus(Duration.ofMinutes(30));

        mockMvc.perform(put("/trips/{id}", trip.getId()).with(branchAdmin(BRANCH_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson(BUS_ID, newDeparture, newArrival)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(trip.getId().toString()));

        TripEntity updated = tripRepository.findById(trip.getId()).orElseThrow();
        assertEquals(newDeparture, updated.getScheduledDeparture());
        assertEquals(newArrival, updated.getEstimatedArrival());
        assertEquals(List.of("TripUpdated"), outboxEventTypes(trip.getId()));
        verifyNoInteractions(ticketingClient);
    }

    @Test
    void update_busChangeWithIncompatibleLayout_forwardsTokenAndKeepsTrip() throws Exception {
        TripEntity trip = saveTrip(TripType.REGULAR, TripStatus.SCHEDULED, BRANCH_ID, ROUTE_ID, BUS_ID, DRIVER_ID,
                departure);
        when(fleetClient.getBus(OTHER_BUS_ID)).thenReturn(bus(OTHER_BUS_ID, BRANCH_ID, 50, (short) 5, (short) 2));
        when(ticketingClient.getSoldSummary(eq(trip.getId()), any()))
                .thenReturn(new TicketingSoldSummaryResponse(trip.getId(), 3, 12));

        mockMvc.perform(put("/trips/{id}", trip.getId()).with(branchAdmin(BRANCH_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson(OTHER_BUS_ID, departure, arrival)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value(ErrorCodes.LAYOUT_INCOMPATIBLE));

        verify(ticketingClient).getSoldSummary(trip.getId(), "token");
        assertEquals(BUS_ID, tripRepository.findById(trip.getId()).orElseThrow().getBusId());
        assertEquals(List.of(), outboxEventTypes(trip.getId()));
    }

    @Test
    void update_busChangeCompatibleWithSoldSeats_isApplied() throws Exception {
        TripEntity trip = saveTrip(TripType.REGULAR, TripStatus.SCHEDULED, BRANCH_ID, ROUTE_ID, BUS_ID, DRIVER_ID,
                departure);
        when(fleetClient.getBus(OTHER_BUS_ID)).thenReturn(bus(OTHER_BUS_ID, BRANCH_ID, 36, (short) 4, (short) 2));
        when(ticketingClient.getSoldSummary(eq(trip.getId()), any()))
                .thenReturn(new TicketingSoldSummaryResponse(trip.getId(), 3, 36));

        mockMvc.perform(put("/trips/{id}", trip.getId()).with(branchAdmin(BRANCH_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson(OTHER_BUS_ID, departure, arrival)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.busId").value(OTHER_BUS_ID.toString()));

        assertEquals(OTHER_BUS_ID, tripRepository.findById(trip.getId()).orElseThrow().getBusId());
        assertEquals(List.of("TripUpdated"), outboxEventTypes(trip.getId()));
    }

    @Test
    void update_overlappingOtherTrip_returnsConflictAndKeepsSchedule() throws Exception {
        TripEntity trip = saveTrip(TripType.REGULAR, TripStatus.SCHEDULED, BRANCH_ID, ROUTE_ID, BUS_ID, DRIVER_ID,
                departure);
        saveTrip(TripType.REGULAR, TripStatus.SCHEDULED, BRANCH_ID, ROUTE_ID, BUS_ID, OTHER_DRIVER_ID,
                at(tomorrow, 14), at(tomorrow, 17));

        mockMvc.perform(put("/trips/{id}", trip.getId()).with(branchAdmin(BRANCH_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson(BUS_ID, at(tomorrow, 12), at(tomorrow, 15))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ErrorCodes.RESOURCE_OVERLAP));

        assertEquals(departure, tripRepository.findById(trip.getId()).orElseThrow().getScheduledDeparture());
    }

    @Test
    void update_tripFromOtherBranch_returnsForbidden() throws Exception {
        TripEntity trip = saveTrip(TripType.REGULAR, TripStatus.SCHEDULED, OTHER_BRANCH_ID, ROUTE_ID, BUS_ID,
                DRIVER_ID, departure);

        mockMvc.perform(put("/trips/{id}", trip.getId()).with(branchAdmin(BRANCH_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson(BUS_ID, departure, arrival)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCodes.TRIP_NOT_IN_BRANCH));
    }

    @Test
    void update_startedTrip_returnsAlreadyStarted() throws Exception {
        TripEntity trip = saveTrip(TripType.REGULAR, TripStatus.IN_TRANSIT, BRANCH_ID, ROUTE_ID, BUS_ID, DRIVER_ID,
                departure);

        mockMvc.perform(put("/trips/{id}", trip.getId()).with(branchAdmin(BRANCH_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson(BUS_ID, departure, arrival)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value(ErrorCodes.TRIP_ALREADY_STARTED));

        assertEquals(List.of(), outboxEventTypes(trip.getId()));
    }

    @Test
    void delete_marksTripDeletedHidesItAndRecordsEvent() throws Exception {
        TripEntity trip = saveTrip(TripType.REGULAR, TripStatus.SCHEDULED, BRANCH_ID, ROUTE_ID, BUS_ID, DRIVER_ID,
                departure);

        mockMvc.perform(delete("/trips/{id}", trip.getId()).with(branchAdmin(BRANCH_ID)))
                .andExpect(status().isNoContent());

        assertEquals(TripStatus.DELETED, tripRepository.findById(trip.getId()).orElseThrow().getStatus());
        assertEquals(List.of("TripDeleted"), outboxEventTypes(trip.getId()));
        mockMvc.perform(get("/trips/{id}", trip.getId()).with(branchAdmin(BRANCH_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCodes.TRIP_NOT_FOUND));
        mockMvc.perform(get("/trips").with(branchAdmin(BRANCH_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void delete_privateTrip_returnsNotEditable() throws Exception {
        TripEntity trip = saveTrip(TripType.PRIVATE, TripStatus.SCHEDULED, BRANCH_ID, null, BUS_ID, DRIVER_ID,
                departure);

        mockMvc.perform(delete("/trips/{id}", trip.getId()).with(branchAdmin(BRANCH_ID)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value(ErrorCodes.PRIVATE_TRIP_NOT_EDITABLE));

        assertEquals(TripStatus.SCHEDULED, tripRepository.findById(trip.getId()).orElseThrow().getStatus());
    }

    @Test
    void departure_asBranchAdmin_startsTripOnceAndRecordsEvent() throws Exception {
        TripEntity trip = saveTrip(TripType.REGULAR, TripStatus.SCHEDULED, BRANCH_ID, ROUTE_ID, BUS_ID, DRIVER_ID,
                departure);

        mockMvc.perform(post("/trips/{id}/departure", trip.getId()).with(branchAdmin(BRANCH_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(departureJson(departure.minus(Duration.ofMinutes(10)), "1000.00")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tripId").value(trip.getId().toString()))
                .andExpect(jsonPath("$.busId").value(BUS_ID.toString()))
                .andExpect(jsonPath("$.odometerStart").value(1000.0))
                .andExpect(jsonPath("$.registeredBy").value(USER_ID.toString()));

        assertEquals(TripStatus.IN_TRANSIT, tripRepository.findById(trip.getId()).orElseThrow().getStatus());
        assertEquals(List.of("TripStarted"), outboxEventTypes(trip.getId()));

        mockMvc.perform(post("/trips/{id}/departure", trip.getId()).with(branchAdmin(BRANCH_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(departureJson(departure, "1000.00")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ErrorCodes.TRIP_ALREADY_STARTED));

        assertEquals(1, tripDepartureRepository.count());
    }

    @Test
    void departure_asAssignedDriver_isRegisteredByTheDriver() throws Exception {
        TripEntity trip = saveTrip(TripType.REGULAR, TripStatus.SCHEDULED, OTHER_BRANCH_ID, ROUTE_ID, BUS_ID,
                DRIVER_ID, departure);

        mockMvc.perform(post("/trips/{id}/departure", trip.getId()).with(driverUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(departureJson(departure, "1005.00")))
                .andExpect(status().isCreated());

        assertEquals(USER_ID, tripDepartureRepository.findByTripId(trip.getId()).orElseThrow().getRegisteredBy());
    }

    @Test
    void departure_asOtherDriver_returnsNotAssigned() throws Exception {
        TripEntity trip = saveTrip(TripType.REGULAR, TripStatus.SCHEDULED, BRANCH_ID, ROUTE_ID, BUS_ID,
                OTHER_DRIVER_ID, departure);

        mockMvc.perform(post("/trips/{id}/departure", trip.getId()).with(driverUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(departureJson(departure, "1000.00")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCodes.TRIP_NOT_ASSIGNED_TO_DRIVER));

        assertEquals(0, tripDepartureRepository.count());
        assertEquals(TripStatus.SCHEDULED, tripRepository.findById(trip.getId()).orElseThrow().getStatus());
    }

    @Test
    void departure_withOdometerBelowBus_returnsUnprocessable() throws Exception {
        TripEntity trip = saveTrip(TripType.REGULAR, TripStatus.SCHEDULED, BRANCH_ID, ROUTE_ID, BUS_ID, DRIVER_ID,
                departure);

        mockMvc.perform(post("/trips/{id}/departure", trip.getId()).with(branchAdmin(BRANCH_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(departureJson(departure, "999.99")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value(ErrorCodes.ODOMETER_BELOW_BUS));

        assertEquals(0, tripDepartureRepository.count());
    }

    @Test
    void departure_withInvalidBody_returnsValidationError() throws Exception {
        TripEntity trip = saveTrip(TripType.REGULAR, TripStatus.SCHEDULED, BRANCH_ID, ROUTE_ID, BUS_ID, DRIVER_ID,
                departure);

        mockMvc.perform(post("/trips/{id}/departure", trip.getId()).with(branchAdmin(BRANCH_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"odometerStart\":-5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCodes.VALIDATION_ERROR))
                .andExpect(jsonPath("$.message", containsString("actualDepartureAt")))
                .andExpect(jsonPath("$.message", containsString("odometerStart")));

        assertEquals(0, tripDepartureRepository.count());
    }

    @Test
    void departure_asCashier_returnsAccessDenied() throws Exception {
        TripEntity trip = saveTrip(TripType.REGULAR, TripStatus.SCHEDULED, BRANCH_ID, ROUTE_ID, BUS_ID, DRIVER_ID,
                departure);

        mockMvc.perform(post("/trips/{id}/departure", trip.getId()).with(userWithRole("CASHIER", BRANCH_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(departureJson(departure, "1000.00")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCodes.ACCESS_DENIED));

        assertEquals(0, tripDepartureRepository.count());
    }

    @Test
    void arrival_completesPrivateTripWithKmAndSurchargedPay() throws Exception {
        TripEntity trip = saveTrip(TripType.PRIVATE, TripStatus.IN_TRANSIT, BRANCH_ID, null, BUS_ID, DRIVER_ID,
                departure);
        saveDeparture(trip, BUS_ODOMETER);

        mockMvc.perform(post("/trips/{id}/arrival", trip.getId()).with(branchAdmin(BRANCH_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(arrivalJson(arrival, "1120.50")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.odometerStart").value(1000.0))
                .andExpect(jsonPath("$.odometerEnd").value(1120.5))
                .andExpect(jsonPath("$.kmTraveled").value(120.5))
                .andExpect(jsonPath("$.driverPay").value(172.5));

        assertEquals(TripStatus.COMPLETED, tripRepository.findById(trip.getId()).orElseThrow().getStatus());
        assertEquals(1, tripArrivalRepository.count());
        assertEquals(List.of("TripCompleted"), outboxEventTypes(trip.getId()));

        JsonNode payload = jsonMapper.readTree(outboxPayload(trip.getId(), "TripCompleted")).get("payload");
        assertEquals(trip.getRentalId().toString(), payload.get("rentalId").asString());
        assertEquals(BUS_ID.toString(), payload.get("busId").asString());
        assertEquals("PRIVATE", payload.get("type").asString());
        assertEquals(0, new BigDecimal("1120.50").compareTo(payload.get("odometerEnd").decimalValue()));
        assertEquals(0, new BigDecimal("120.50").compareTo(payload.get("kmTraveled").decimalValue()));
        assertEquals(0, new BigDecimal("172.50").compareTo(payload.get("driverPay").decimalValue()));
    }

    @Test
    void arrival_beforeDeparture_returnsBadRequestAndKeepsTripInTransit() throws Exception {
        TripEntity trip = saveTrip(TripType.REGULAR, TripStatus.IN_TRANSIT, BRANCH_ID, ROUTE_ID, BUS_ID, DRIVER_ID,
                departure);
        saveDeparture(trip, BUS_ODOMETER);

        mockMvc.perform(post("/trips/{id}/arrival", trip.getId()).with(driverUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(arrivalJson(departure.minus(Duration.ofMinutes(5)), "1120.50")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCodes.ARRIVAL_BEFORE_DEPARTURE));

        assertEquals(0, tripArrivalRepository.count());
        assertEquals(TripStatus.IN_TRANSIT, tripRepository.findById(trip.getId()).orElseThrow().getStatus());
    }

    @Test
    void arrival_onScheduledTrip_returnsNotInTransit() throws Exception {
        TripEntity trip = saveTrip(TripType.REGULAR, TripStatus.SCHEDULED, BRANCH_ID, ROUTE_ID, BUS_ID, DRIVER_ID,
                departure);

        mockMvc.perform(post("/trips/{id}/arrival", trip.getId()).with(branchAdmin(BRANCH_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(arrivalJson(arrival, "1120.50")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ErrorCodes.TRIP_NOT_IN_TRANSIT));

        assertEquals(0, tripArrivalRepository.count());
    }

    @Test
    void departureAndArrival_haveNoUpdateNorDeleteEndpoints() throws Exception {
        TripEntity trip = saveTrip(TripType.REGULAR, TripStatus.COMPLETED, BRANCH_ID, ROUTE_ID, BUS_ID, DRIVER_ID,
                departure);
        saveDeparture(trip, BUS_ODOMETER);
        saveArrival(trip, new BigDecimal("1120.50"));

        for (String path : List.of("/trips/{id}/departure", "/trips/{id}/arrival")) {
            mockMvc.perform(put(path, trip.getId()).with(branchAdmin(BRANCH_ID))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(departureJson(departure, "1.00")))
                    .andExpect(status().isMethodNotAllowed());
            mockMvc.perform(delete(path, trip.getId()).with(branchAdmin(BRANCH_ID)))
                    .andExpect(status().isMethodNotAllowed());
        }

        assertEquals(0, BUS_ODOMETER.compareTo(
                tripDepartureRepository.findByTripId(trip.getId()).orElseThrow().getOdometerStart()));
        assertEquals(1, tripArrivalRepository.count());
    }

    @Test
    void search_asBranchAdmin_returnsOwnTripsByLatestDepartureWithoutDeleted() throws Exception {
        TripEntity first = saveTrip(TripType.REGULAR, TripStatus.SCHEDULED, BRANCH_ID, ROUTE_ID, BUS_ID, DRIVER_ID,
                departure);
        TripEntity second = saveTrip(TripType.PRIVATE, TripStatus.SCHEDULED, BRANCH_ID, null, BUS_ID, DRIVER_ID,
                at(tomorrow.plusDays(1), 8));
        saveTrip(TripType.REGULAR, TripStatus.SCHEDULED, OTHER_BRANCH_ID, ROUTE_ID, BUS_ID, DRIVER_ID, departure);
        saveTrip(TripType.REGULAR, TripStatus.DELETED, BRANCH_ID, ROUTE_ID, BUS_ID, DRIVER_ID, departure);

        mockMvc.perform(get("/trips").with(branchAdmin(BRANCH_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].id").value(second.getId().toString()))
                .andExpect(jsonPath("$.content[1].id").value(first.getId().toString()))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20));
    }

    @Test
    void search_withEachFilter_returnsMatchingTrips() throws Exception {
        TripEntity scheduled = saveTrip(TripType.REGULAR, TripStatus.SCHEDULED, BRANCH_ID, ROUTE_ID, BUS_ID,
                DRIVER_ID, departure);
        TripEntity completed = saveTrip(TripType.REGULAR, TripStatus.COMPLETED, BRANCH_ID, OTHER_ROUTE_ID,
                OTHER_BUS_ID, OTHER_DRIVER_ID, at(today().minusDays(2), 8));

        mockMvc.perform(get("/trips").param("status", "COMPLETED").with(branchAdmin(BRANCH_ID)))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(completed.getId().toString()));
        mockMvc.perform(get("/trips").param("routeId", ROUTE_ID.toString()).with(branchAdmin(BRANCH_ID)))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(scheduled.getId().toString()));
        mockMvc.perform(get("/trips").param("busId", OTHER_BUS_ID.toString()).with(branchAdmin(BRANCH_ID)))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(completed.getId().toString()));
        mockMvc.perform(get("/trips").param("driverId", DRIVER_ID.toString()).with(branchAdmin(BRANCH_ID)))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(scheduled.getId().toString()));
        mockMvc.perform(get("/trips").param("from", today().toString()).with(branchAdmin(BRANCH_ID)))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(scheduled.getId().toString()));
        mockMvc.perform(get("/trips").param("to", today().toString()).with(branchAdmin(BRANCH_ID)))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(completed.getId().toString()));
    }

    @Test
    void search_dateRange_usesWholeGuatemalaDays() throws Exception {
        TripEntity lateNight = saveTrip(TripType.REGULAR, TripStatus.SCHEDULED, BRANCH_ID, ROUTE_ID, BUS_ID, DRIVER_ID,
                tomorrow.atTime(23, 30).atZone(ZONE).toInstant());
        saveTrip(TripType.REGULAR, TripStatus.SCHEDULED, BRANCH_ID, ROUTE_ID, BUS_ID, DRIVER_ID,
                tomorrow.plusDays(1).atTime(0, 30).atZone(ZONE).toInstant());
        saveTrip(TripType.REGULAR, TripStatus.SCHEDULED, BRANCH_ID, ROUTE_ID, BUS_ID, DRIVER_ID,
                tomorrow.minusDays(1).atTime(23, 59).atZone(ZONE).toInstant());

        mockMvc.perform(get("/trips").param("from", tomorrow.toString()).param("to", tomorrow.toString())
                        .with(branchAdmin(BRANCH_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(lateNight.getId().toString()));
    }

    @Test
    void search_invalidDateRange_returnsBadRequest() throws Exception {
        mockMvc.perform(get("/trips").param("from", tomorrow.toString()).param("to", today().toString())
                        .with(branchAdmin(BRANCH_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCodes.INVALID_DATE_RANGE));
    }

    @Test
    void search_branchAdminAskingOtherBranch_returnsBranchForbidden() throws Exception {
        mockMvc.perform(get("/trips").param("branchId", OTHER_BRANCH_ID.toString()).with(branchAdmin(BRANCH_ID)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCodes.BRANCH_FORBIDDEN));
    }

    @Test
    void search_asSystemAdmin_filtersAnyBranchOrSeesAll() throws Exception {
        saveTrip(TripType.REGULAR, TripStatus.SCHEDULED, BRANCH_ID, ROUTE_ID, BUS_ID, DRIVER_ID, departure);
        TripEntity other = saveTrip(TripType.REGULAR, TripStatus.SCHEDULED, OTHER_BRANCH_ID, ROUTE_ID, BUS_ID,
                DRIVER_ID, departure);

        mockMvc.perform(get("/trips").with(systemAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
        mockMvc.perform(get("/trips").param("branchId", OTHER_BRANCH_ID.toString()).with(systemAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(other.getId().toString()));
    }

    @Test
    void search_asDriver_returnsOnlyOwnTripsAndRejectsOtherDriver() throws Exception {
        when(fleetClient.getDriverByUserId(USER_ID)).thenReturn(driver(DRIVER_ID, USER_ID, BRANCH_ID));
        TripEntity own = saveTrip(TripType.REGULAR, TripStatus.SCHEDULED, OTHER_BRANCH_ID, ROUTE_ID, BUS_ID, DRIVER_ID,
                departure);
        saveTrip(TripType.REGULAR, TripStatus.SCHEDULED, BRANCH_ID, ROUTE_ID, BUS_ID, OTHER_DRIVER_ID, departure);

        mockMvc.perform(get("/trips").param("branchId", BRANCH_ID.toString()).with(driverUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(own.getId().toString()));
        mockMvc.perform(get("/trips").param("driverId", OTHER_DRIVER_ID.toString()).with(driverUser()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCodes.DRIVER_FORBIDDEN));
    }

    @Test
    void search_sizeAboveLimit_isClampedToOneHundred() throws Exception {
        mockMvc.perform(get("/trips").param("size", "500").param("page", "-3").with(systemAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100))
                .andExpect(jsonPath("$.page").value(0));
    }

    @Test
    void search_asCashier_returnsAccessDenied() throws Exception {
        mockMvc.perform(get("/trips").with(userWithRole("CASHIER", BRANCH_ID)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCodes.ACCESS_DENIED));
    }

    @Test
    void findById_completedTrip_returnsDepartureAndArrival() throws Exception {
        TripEntity trip = saveTrip(TripType.REGULAR, TripStatus.COMPLETED, OTHER_BRANCH_ID, ROUTE_ID, BUS_ID,
                DRIVER_ID, departure);
        saveDeparture(trip, BUS_ODOMETER);
        saveArrival(trip, new BigDecimal("1120.50"));

        mockMvc.perform(get("/trips/{id}", trip.getId()).with(systemAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trip.id").value(trip.getId().toString()))
                .andExpect(jsonPath("$.trip.status").value("COMPLETED"))
                .andExpect(jsonPath("$.departure.odometerStart").value(1000.0))
                .andExpect(jsonPath("$.arrival.kmTraveled").value(120.5))
                .andExpect(jsonPath("$.arrival.driverPay").value(150.0));
    }

    @Test
    void findById_branchAdminOfOtherBranch_returnsForbidden() throws Exception {
        TripEntity trip = saveTrip(TripType.REGULAR, TripStatus.SCHEDULED, OTHER_BRANCH_ID, ROUTE_ID, BUS_ID,
                DRIVER_ID, departure);

        mockMvc.perform(get("/trips/{id}", trip.getId()).with(branchAdmin(BRANCH_ID)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCodes.TRIP_NOT_IN_BRANCH));
    }

    @Test
    void findById_asDriver_onlySeesAssignedTrips() throws Exception {
        TripEntity own = saveTrip(TripType.REGULAR, TripStatus.SCHEDULED, BRANCH_ID, ROUTE_ID, BUS_ID, DRIVER_ID,
                departure);
        TripEntity other = saveTrip(TripType.REGULAR, TripStatus.SCHEDULED, BRANCH_ID, ROUTE_ID, BUS_ID,
                OTHER_DRIVER_ID, departure);

        mockMvc.perform(get("/trips/{id}", own.getId()).with(driverUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trip.id").value(own.getId().toString()))
                .andExpect(jsonPath("$.departure").doesNotExist());
        mockMvc.perform(get("/trips/{id}", other.getId()).with(driverUser()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCodes.TRIP_NOT_ASSIGNED_TO_DRIVER));
    }

    @Test
    void findById_unknownTrip_returnsNotFound() throws Exception {
        mockMvc.perform(get("/trips/{id}", UUID.randomUUID()).with(systemAdmin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCodes.TRIP_NOT_FOUND));
    }

    @Test
    void findById_withoutToken_returnsUnauthorized() throws Exception {
        mockMvc.perform(get("/trips/{id}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());

        assertTrue(tripRepository.findAll().isEmpty());
    }

    private String createJson(Instant scheduledDeparture, Instant estimatedArrival) {
        return createJson(ROUTE_ID, BUS_ID, DRIVER_ID, scheduledDeparture, estimatedArrival);
    }

    private static String createJson(UUID routeId, UUID busId, UUID driverId, Instant scheduledDeparture,
                                     Instant estimatedArrival) {
        return """
                {"routeId":"%s","busId":"%s","driverId":"%s","scheduledDeparture":"%s","estimatedArrival":"%s",
                 "operationAt":"%s"}
                """.formatted(routeId, busId, driverId, scheduledDeparture, estimatedArrival, Instant.now());
    }

    private static String updateJson(UUID busId, Instant scheduledDeparture, Instant estimatedArrival) {
        return """
                {"busId":"%s","driverId":"%s","scheduledDeparture":"%s","estimatedArrival":"%s","operationAt":"%s"}
                """.formatted(busId, DRIVER_ID, scheduledDeparture, estimatedArrival, Instant.now());
    }

    private static String departureJson(Instant actualDepartureAt, String odometerStart) {
        return "{\"actualDepartureAt\":\"%s\",\"odometerStart\":%s}".formatted(actualDepartureAt, odometerStart);
    }

    private static String arrivalJson(Instant actualArrivalAt, String odometerEnd) {
        return "{\"actualArrivalAt\":\"%s\",\"odometerEnd\":%s,\"fuelCost\":300.00}".formatted(actualArrivalAt,
                odometerEnd);
    }
}
