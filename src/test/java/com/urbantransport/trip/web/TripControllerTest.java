package com.urbantransport.trip.web;

import com.urbantransport.trip.config.InternalProperties;
import com.urbantransport.trip.config.SecurityConfig;
import com.urbantransport.trip.domain.TripEntity;
import com.urbantransport.trip.domain.TripStatus;
import com.urbantransport.trip.domain.TripType;
import com.urbantransport.trip.dto.CreateTripRequest;
import com.urbantransport.trip.dto.PageResponse;
import com.urbantransport.trip.dto.RegisterArrivalRequest;
import com.urbantransport.trip.dto.RegisterDepartureRequest;
import com.urbantransport.trip.dto.TripDetailResponse;
import com.urbantransport.trip.dto.TripResponse;
import com.urbantransport.trip.dto.UpdateTripRequest;
import com.urbantransport.trip.exception.ErrorCodes;
import com.urbantransport.trip.service.TripService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TripController.class)
@Import(SecurityConfig.class)
@EnableConfigurationProperties(InternalProperties.class)
class TripControllerTest {

    private static final ZoneId ZONE = ZoneId.of("America/Guatemala");
    private static final LocalDate TODAY = LocalDate.now(ZONE);
    private static final Instant DEPARTURE = TODAY.plusDays(1).atTime(8, 0).atZone(ZONE).toInstant();
    private static final Instant ARRIVAL = DEPARTURE.plus(Duration.ofHours(3));
    private static final Instant OPERATION_AT = TODAY.atTime(7, 0).atZone(ZONE).toInstant();
    private static final UUID TRIP_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID BRANCH_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID OTHER_BRANCH_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID ROUTE_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID BUS_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID DRIVER_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final UUID USER_ID = UUID.fromString("77777777-7777-7777-7777-777777777777");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TripService tripService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void create_passesBranchAndUserFromToken() throws Exception {
        when(tripService.createRegular(any(), eq(BRANCH_ID), eq(USER_ID))).thenReturn(tripResponse());

        mockMvc.perform(post("/trips").with(user("BRANCH_ADMIN", BRANCH_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"%s","busId":"%s","driverId":"%s","scheduledDeparture":"%s",
                                 "estimatedArrival":"%s","operationAt":"%s","branchId":"%s"}
                                """.formatted(ROUTE_ID, BUS_ID, DRIVER_ID, DEPARTURE, ARRIVAL, OPERATION_AT,
                                OTHER_BRANCH_ID)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(TRIP_ID.toString()));

        verify(tripService).createRegular(new CreateTripRequest(ROUTE_ID, BUS_ID, DRIVER_ID, DEPARTURE, ARRIVAL,
                OPERATION_AT), BRANCH_ID, USER_ID);
    }

    @Test
    void create_withoutBranchClaim_returnsBranchRequiredWithoutCallingService() throws Exception {
        mockMvc.perform(post("/trips").with(user("BRANCH_ADMIN", null))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"%s","busId":"%s","driverId":"%s","scheduledDeparture":"%s",
                                 "estimatedArrival":"%s","operationAt":"%s"}
                                """.formatted(ROUTE_ID, BUS_ID, DRIVER_ID, DEPARTURE, ARRIVAL, OPERATION_AT)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCodes.BRANCH_REQUIRED));

        verifyNoInteractions(tripService);
    }

    @Test
    void update_forwardsTokenValue() throws Exception {
        when(tripService.update(eq(TRIP_ID), any(), eq(BRANCH_ID), eq("token"))).thenReturn(tripResponse());

        mockMvc.perform(put("/trips/{id}", TRIP_ID).with(user("BRANCH_ADMIN", BRANCH_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"REGULAR","busId":"%s","driverId":"%s","scheduledDeparture":"%s",
                                 "estimatedArrival":"%s","operationAt":"%s"}
                                """.formatted(BUS_ID, DRIVER_ID, DEPARTURE, ARRIVAL, OPERATION_AT)))
                .andExpect(status().isOk());

        verify(tripService).update(TRIP_ID, new UpdateTripRequest(TripType.REGULAR, BUS_ID, DRIVER_ID, DEPARTURE,
                ARRIVAL, OPERATION_AT), BRANCH_ID, "token");
    }

    @Test
    void delete_returnsNoContent() throws Exception {
        mockMvc.perform(delete("/trips/{id}", TRIP_ID).with(user("BRANCH_ADMIN", BRANCH_ID)))
                .andExpect(status().isNoContent());

        verify(tripService).delete(TRIP_ID, BRANCH_ID);
    }

    @Test
    void departure_asDriver_usesDriverFlow() throws Exception {
        mockMvc.perform(post("/trips/{id}/departure", TRIP_ID).with(user("DRIVER", null))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(departureJson()))
                .andExpect(status().isCreated());

        verify(tripService).registerDepartureAsDriver(TRIP_ID, departureRequest(), USER_ID);
        verify(tripService, never()).registerDepartureAsAdmin(any(), any(), any(), any());
    }

    @Test
    void departure_asBranchAdmin_usesAdminFlowWithBranch() throws Exception {
        mockMvc.perform(post("/trips/{id}/departure", TRIP_ID).with(user("BRANCH_ADMIN", BRANCH_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(departureJson()))
                .andExpect(status().isCreated());

        verify(tripService).registerDepartureAsAdmin(TRIP_ID, departureRequest(), BRANCH_ID, USER_ID);
        verify(tripService, never()).registerDepartureAsDriver(any(), any(), any());
    }

    @Test
    void departure_withoutRolesClaim_isTreatedAsAdmin() throws Exception {
        mockMvc.perform(post("/trips/{id}/departure", TRIP_ID)
                        .with(jwt().jwt(token -> token.subject(USER_ID.toString())
                                        .claim("branchId", BRANCH_ID.toString()))
                                .authorities(new SimpleGrantedAuthority("ROLE_BRANCH_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(departureJson()))
                .andExpect(status().isCreated());

        verify(tripService).registerDepartureAsAdmin(TRIP_ID, departureRequest(), BRANCH_ID, USER_ID);
    }

    @Test
    void arrival_asDriver_usesDriverFlow() throws Exception {
        mockMvc.perform(post("/trips/{id}/arrival", TRIP_ID).with(user("DRIVER", null))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(arrivalJson()))
                .andExpect(status().isCreated());

        verify(tripService).registerArrivalAsDriver(TRIP_ID, arrivalRequest(), USER_ID);
        verify(tripService, never()).registerArrivalAsAdmin(any(), any(), any(), any());
    }

    @Test
    void arrival_asBranchAdmin_usesAdminFlowWithBranch() throws Exception {
        mockMvc.perform(post("/trips/{id}/arrival", TRIP_ID).with(user("BRANCH_ADMIN", BRANCH_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(arrivalJson()))
                .andExpect(status().isCreated());

        verify(tripService).registerArrivalAsAdmin(TRIP_ID, arrivalRequest(), BRANCH_ID, USER_ID);
    }

    @Test
    void search_asDriver_passesRequestedDriverAndIgnoresBranch() throws Exception {
        when(tripService.searchForDriver(any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(emptyPage());

        mockMvc.perform(get("/trips").with(user("DRIVER", null))
                        .param("driverId", DRIVER_ID.toString())
                        .param("branchId", OTHER_BRANCH_ID.toString())
                        .param("status", "SCHEDULED")
                        .param("from", TODAY.toString())
                        .param("to", TODAY.plusDays(7).toString()))
                .andExpect(status().isOk());

        verify(tripService).searchForDriver(USER_ID, DRIVER_ID, TripStatus.SCHEDULED, null, null, TODAY,
                TODAY.plusDays(7), 0, 20);
    }

    @Test
    void search_asSystemAdmin_usesRequestedBranch() throws Exception {
        when(tripService.search(any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(emptyPage());

        mockMvc.perform(get("/trips").with(user("SYSTEM_ADMIN", null))
                        .param("branchId", OTHER_BRANCH_ID.toString())
                        .param("page", "2")
                        .param("size", "5"))
                .andExpect(status().isOk());

        verify(tripService).search(null, null, null, null, OTHER_BRANCH_ID, null, null, 2, 5);
    }

    @Test
    void search_asBranchAdmin_isFixedToOwnBranch() throws Exception {
        when(tripService.search(any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(emptyPage());

        mockMvc.perform(get("/trips").with(user("BRANCH_ADMIN", BRANCH_ID)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/trips").with(user("BRANCH_ADMIN", BRANCH_ID)).param("branchId", BRANCH_ID.toString()))
                .andExpect(status().isOk());

        verify(tripService, times(2)).search(null, null, null, null, BRANCH_ID, null, null, 0, 20);
    }

    @Test
    void search_asBranchAdminForOtherBranch_returnsBranchForbiddenWithoutCallingService() throws Exception {
        mockMvc.perform(get("/trips").with(user("BRANCH_ADMIN", BRANCH_ID))
                        .param("branchId", OTHER_BRANCH_ID.toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCodes.BRANCH_FORBIDDEN));

        verifyNoInteractions(tripService);
    }

    @Test
    void findById_asDriver_usesDriverFlow() throws Exception {
        when(tripService.findByIdForDriver(TRIP_ID, USER_ID)).thenReturn(detail());

        mockMvc.perform(get("/trips/{id}", TRIP_ID).with(user("DRIVER", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trip.id").value(TRIP_ID.toString()));
    }

    @Test
    void findById_asSystemAdmin_hasNoBranchScope() throws Exception {
        when(tripService.findById(TRIP_ID, null)).thenReturn(detail());

        mockMvc.perform(get("/trips/{id}", TRIP_ID).with(user("SYSTEM_ADMIN", null)))
                .andExpect(status().isOk());

        verify(tripService).findById(TRIP_ID, null);
    }

    @Test
    void findById_asBranchAdmin_isScopedToOwnBranch() throws Exception {
        when(tripService.findById(TRIP_ID, BRANCH_ID)).thenReturn(detail());

        mockMvc.perform(get("/trips/{id}", TRIP_ID).with(user("BRANCH_ADMIN", BRANCH_ID)))
                .andExpect(status().isOk());

        verify(tripService).findById(TRIP_ID, BRANCH_ID);
    }

    @Test
    void findById_asCashier_returnsAccessDenied() throws Exception {
        mockMvc.perform(get("/trips/{id}", TRIP_ID).with(user("CASHIER", BRANCH_ID)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCodes.ACCESS_DENIED));

        verifyNoInteractions(tripService);
    }

    private static RequestPostProcessor user(String role, UUID branchId) {
        return jwt()
                .jwt(token -> {
                    token.subject(USER_ID.toString()).claim("roles", List.of(role));
                    if (branchId != null) {
                        token.claim("branchId", branchId.toString());
                    }
                })
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    private static TripResponse tripResponse() {
        return TripResponse.fromEntity(TripEntity.builder()
                .id(TRIP_ID)
                .type(TripType.REGULAR)
                .routeId(ROUTE_ID)
                .originBranchId(BRANCH_ID)
                .busId(BUS_ID)
                .driverId(DRIVER_ID)
                .scheduledDeparture(DEPARTURE)
                .estimatedArrival(ARRIVAL)
                .status(TripStatus.SCHEDULED)
                .build());
    }

    private static TripDetailResponse detail() {
        return new TripDetailResponse(tripResponse(), null, null);
    }

    private static PageResponse<TripResponse> emptyPage() {
        return new PageResponse<>(List.of(), 0, 20, 0, 0);
    }

    private static RegisterDepartureRequest departureRequest() {
        return new RegisterDepartureRequest(DEPARTURE, new BigDecimal("1000.00"));
    }

    private static String departureJson() {
        return "{\"actualDepartureAt\":\"%s\",\"odometerStart\":1000.00}".formatted(DEPARTURE);
    }

    private static RegisterArrivalRequest arrivalRequest() {
        return new RegisterArrivalRequest(ARRIVAL, new BigDecimal("1120.50"), new BigDecimal("300.00"));
    }

    private static String arrivalJson() {
        return "{\"actualArrivalAt\":\"%s\",\"odometerEnd\":1120.50,\"fuelCost\":300.00}".formatted(ARRIVAL);
    }
}
