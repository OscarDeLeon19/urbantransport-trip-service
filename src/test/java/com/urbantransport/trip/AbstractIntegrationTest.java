package com.urbantransport.trip;

import com.jayway.jsonpath.JsonPath;
import com.urbantransport.trip.client.FleetBusResponse;
import com.urbantransport.trip.client.FleetClient;
import com.urbantransport.trip.client.FleetDriverResponse;
import com.urbantransport.trip.client.FleetRouteResponse;
import com.urbantransport.trip.client.TicketingClient;
import com.urbantransport.trip.domain.TripArrivalEntity;
import com.urbantransport.trip.domain.TripDepartureEntity;
import com.urbantransport.trip.domain.TripEntity;
import com.urbantransport.trip.domain.TripStatus;
import com.urbantransport.trip.domain.TripType;
import com.urbantransport.trip.repository.TripArrivalRepository;
import com.urbantransport.trip.repository.TripDepartureRepository;
import com.urbantransport.trip.repository.TripRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
public abstract class AbstractIntegrationTest {

    protected static final ZoneId ZONE = ZoneId.of("America/Guatemala");
    protected static final UUID USER_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");
    protected static final BigDecimal BUS_ODOMETER = new BigDecimal("1000.00");
    protected static final BigDecimal DRIVER_SALARY = new BigDecimal("150.00");
    protected static final BigDecimal TICKET_PRICE = new BigDecimal("45.00");
    protected static final BigDecimal DISTANCE_KM = new BigDecimal("120.50");

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected TripRepository tripRepository;

    @Autowired
    protected TripDepartureRepository tripDepartureRepository;

    @Autowired
    protected TripArrivalRepository tripArrivalRepository;

    @MockitoBean
    protected FleetClient fleetClient;

    @MockitoBean
    protected TicketingClient ticketingClient;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.execute("TRUNCATE trip_arrivals, trip_departures, trips, outbox_events, processed_events CASCADE");
    }

    protected static RequestPostProcessor branchAdmin(UUID branchId) {
        return userWithRole("BRANCH_ADMIN", branchId);
    }

    protected static RequestPostProcessor systemAdmin() {
        return userWithRole("SYSTEM_ADMIN", null);
    }

    protected static RequestPostProcessor driverUser() {
        return userWithRole("DRIVER", null);
    }

    protected static RequestPostProcessor userWithRole(String role, UUID branchId) {
        return jwt()
                .jwt(token -> {
                    token.subject(USER_ID.toString()).claim("roles", List.of(role));
                    if (branchId != null) {
                        token.claim("branchId", branchId.toString());
                    }
                })
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    protected static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    protected static Instant at(LocalDate date, int hour) {
        return date.atTime(hour, 0).atZone(ZONE).toInstant();
    }

    protected static UUID idAt(MvcResult result, String path) throws Exception {
        return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), path));
    }

    protected static FleetRouteResponse route(UUID id, UUID originBranchId, UUID destinationBranchId) {
        return new FleetRouteResponse(id, originBranchId, "Central", destinationBranchId, "Xela", DISTANCE_KM,
                TICKET_PRICE);
    }

    protected static FleetBusResponse bus(UUID id, UUID branchId) {
        return bus(id, branchId, 40, (short) 4, (short) 2);
    }

    protected static FleetBusResponse bus(UUID id, UUID branchId, int capacity, Short columns, Short aisle) {
        return new FleetBusResponse(id, "P-" + id.toString().substring(0, 4), capacity, (short) 10, columns, aisle,
                "DISPONIBLE", BUS_ODOMETER, branchId);
    }

    protected static FleetDriverResponse driver(UUID id, UUID userId, UUID branchId) {
        return new FleetDriverResponse(id, userId, "Chofer " + id.toString().substring(0, 4),
                today().plusDays(365), DRIVER_SALARY, branchId, true);
    }

    protected TripEntity saveTrip(TripType type, TripStatus status, UUID branchId, UUID routeId, UUID busId,
                                  UUID driverId, Instant departure) {
        return saveTrip(type, status, branchId, routeId, busId, driverId, departure,
                departure.plus(Duration.ofHours(3)));
    }

    protected TripEntity saveTrip(TripType type, TripStatus status, UUID branchId, UUID routeId, UUID busId,
                                  UUID driverId, Instant departure, Instant arrival) {
        boolean regular = type == TripType.REGULAR;
        return tripRepository.save(TripEntity.builder()
                .type(type)
                .routeId(routeId)
                .originBranchId(branchId)
                .destinationBranchId(regular ? UUID.randomUUID() : null)
                .originText(regular ? null : "Guatemala")
                .destinationText(regular ? null : "Antigua")
                .busId(busId)
                .driverId(driverId)
                .scheduledDeparture(departure)
                .estimatedArrival(arrival)
                .ticketPrice(regular ? TICKET_PRICE : null)
                .distanceKm(DISTANCE_KM)
                .status(status)
                .rentalId(regular ? null : UUID.randomUUID())
                .createdBy(USER_ID)
                .build());
    }

    protected TripDepartureEntity saveDeparture(TripEntity trip, BigDecimal odometerStart) {
        return tripDepartureRepository.save(TripDepartureEntity.builder()
                .tripId(trip.getId())
                .actualDepartureAt(trip.getScheduledDeparture())
                .busId(trip.getBusId())
                .driverId(trip.getDriverId())
                .odometerStart(odometerStart)
                .registeredBy(USER_ID)
                .build());
    }

    protected TripArrivalEntity saveArrival(TripEntity trip, BigDecimal odometerEnd) {
        return tripArrivalRepository.save(TripArrivalEntity.builder()
                .tripId(trip.getId())
                .actualArrivalAt(trip.getEstimatedArrival())
                .odometerEnd(odometerEnd)
                .fuelCost(new BigDecimal("300.00"))
                .driverPay(DRIVER_SALARY)
                .registeredBy(USER_ID)
                .build());
    }

    protected List<String> outboxEventTypes(UUID aggregateId) {
        return jdbcTemplate.queryForList(
                "SELECT event_type FROM outbox_events WHERE aggregate_id = ? ORDER BY created_at",
                String.class, aggregateId);
    }

    protected String outboxPayload(UUID aggregateId, String eventType) {
        return jdbcTemplate.queryForObject(
                "SELECT payload::text FROM outbox_events WHERE aggregate_id = ? AND event_type = ?",
                String.class, aggregateId, eventType);
    }

    protected int count(String sql, Object... args) {
        Integer total = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return total == null ? 0 : total;
    }
}
