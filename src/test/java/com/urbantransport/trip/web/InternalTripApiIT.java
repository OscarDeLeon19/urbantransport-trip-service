package com.urbantransport.trip.web;

import com.urbantransport.trip.AbstractIntegrationTest;
import com.urbantransport.trip.domain.TripStatus;
import com.urbantransport.trip.domain.TripType;
import com.urbantransport.trip.exception.ErrorCodes;
import com.urbantransport.trip.security.InternalApiKeyFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InternalTripApiIT extends AbstractIntegrationTest {

    private static final UUID BRANCH_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ROUTE_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_ROUTE_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID BUS_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_BUS_ID = UUID.fromString("30000000-0000-0000-0000-000000000002");
    private static final UUID DRIVER_ID = UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_DRIVER_ID = UUID.fromString("40000000-0000-0000-0000-000000000002");

    @Value("${app.internal.api-key}")
    private String internalApiKey;

    @BeforeEach
    void seedTrips() {
        Instant departure = at(today().plusDays(1), 8);
        saveTrip(TripType.REGULAR, TripStatus.SCHEDULED, BRANCH_ID, ROUTE_ID, BUS_ID, DRIVER_ID, departure);
        saveTrip(TripType.PRIVATE, TripStatus.IN_TRANSIT, BRANCH_ID, null, BUS_ID, DRIVER_ID, departure);
        saveTrip(TripType.REGULAR, TripStatus.COMPLETED, BRANCH_ID, ROUTE_ID, BUS_ID, DRIVER_ID, departure);
        saveTrip(TripType.REGULAR, TripStatus.DELETED, BRANCH_ID, ROUTE_ID, BUS_ID, DRIVER_ID, departure);
        saveTrip(TripType.REGULAR, TripStatus.SCHEDULED, BRANCH_ID, OTHER_ROUTE_ID, OTHER_BUS_ID, OTHER_DRIVER_ID,
                departure);
    }

    @Test
    void countActive_byBus_countsOnlyScheduledAndInTransit() throws Exception {
        mockMvc.perform(get("/internal/trips/active").param("busId", BUS_ID.toString())
                        .header(InternalApiKeyFilter.HEADER, internalApiKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(2));
    }

    @Test
    void countActive_byDriver_countsOnlyScheduledAndInTransit() throws Exception {
        mockMvc.perform(get("/internal/trips/active").param("driverId", OTHER_DRIVER_ID.toString())
                        .header(InternalApiKeyFilter.HEADER, internalApiKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1));
    }

    @Test
    void countActive_unknownBus_returnsZero() throws Exception {
        mockMvc.perform(get("/internal/trips/active").param("busId", UUID.randomUUID().toString())
                        .header(InternalApiKeyFilter.HEADER, internalApiKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));
    }

    @Test
    void countActive_withBothOrNoFilter_returnsValidationError() throws Exception {
        mockMvc.perform(get("/internal/trips/active").header(InternalApiKeyFilter.HEADER, internalApiKey))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCodes.VALIDATION_ERROR));

        mockMvc.perform(get("/internal/trips/active").param("busId", BUS_ID.toString())
                        .param("driverId", DRIVER_ID.toString())
                        .header(InternalApiKeyFilter.HEADER, internalApiKey))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCodes.VALIDATION_ERROR));
    }

    @Test
    void countByRoute_countsEveryTripExceptDeleted() throws Exception {
        mockMvc.perform(get("/internal/trips/count").param("routeId", ROUTE_ID.toString())
                        .header(InternalApiKeyFilter.HEADER, internalApiKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(2));
    }

    @Test
    void countByRoute_withoutRoute_returnsValidationError() throws Exception {
        mockMvc.perform(get("/internal/trips/count").header(InternalApiKeyFilter.HEADER, internalApiKey))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCodes.VALIDATION_ERROR));
    }

    @Test
    void internalEndpoints_withoutOrWithWrongKey_returnUnauthorized() throws Exception {
        mockMvc.perform(get("/internal/trips/active").param("busId", BUS_ID.toString()))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/internal/trips/count").param("routeId", ROUTE_ID.toString())
                        .header(InternalApiKeyFilter.HEADER, "otra-clave"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void internalEndpoints_withUserToken_returnForbidden() throws Exception {
        mockMvc.perform(get("/internal/trips/active").param("busId", BUS_ID.toString()).with(systemAdmin()))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/internal/trips/count").param("routeId", ROUTE_ID.toString())
                        .with(branchAdmin(BRANCH_ID)))
                .andExpect(status().isForbidden());
    }
}
