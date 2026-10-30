package com.urbantransport.trip.web;

import com.urbantransport.trip.config.InternalProperties;
import com.urbantransport.trip.config.SecurityConfig;
import com.urbantransport.trip.dto.TripCountResponse;
import com.urbantransport.trip.security.InternalApiKeyFilter;
import com.urbantransport.trip.service.TripService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(InternalTripController.class)
@Import(SecurityConfig.class)
@EnableConfigurationProperties(InternalProperties.class)
class InternalTripControllerTest {

    private static final UUID BUS_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DRIVER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ROUTE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Autowired
    private MockMvc mockMvc;

    @Value("${app.internal.api-key}")
    private String internalApiKey;

    @MockitoBean
    private TripService tripService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void countActive_passesBothFiltersToService() throws Exception {
        when(tripService.countActive(BUS_ID, null)).thenReturn(new TripCountResponse(3));
        when(tripService.countActive(null, DRIVER_ID)).thenReturn(new TripCountResponse(1));

        mockMvc.perform(get("/internal/trips/active").param("busId", BUS_ID.toString())
                        .header(InternalApiKeyFilter.HEADER, internalApiKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(3));
        mockMvc.perform(get("/internal/trips/active").param("driverId", DRIVER_ID.toString())
                        .header(InternalApiKeyFilter.HEADER, internalApiKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1));
    }

    @Test
    void countByRoute_returnsServiceCount() throws Exception {
        when(tripService.countByRoute(ROUTE_ID)).thenReturn(new TripCountResponse(7));

        mockMvc.perform(get("/internal/trips/count").param("routeId", ROUTE_ID.toString())
                        .header(InternalApiKeyFilter.HEADER, internalApiKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(7));
    }

    @Test
    void withoutKey_returnsUnauthorizedWithoutCallingService() throws Exception {
        mockMvc.perform(get("/internal/trips/count").param("routeId", ROUTE_ID.toString()))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(tripService);
    }
}
