package com.urbantransport.trip.event;

import com.urbantransport.trip.service.TripService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class RentalEventsListenerTest {

    private static final ZoneId ZONE = ZoneId.of("America/Guatemala");
    private static final Instant DEPARTURE = LocalDate.now(ZONE).plusDays(3).atTime(8, 0).atZone(ZONE).toInstant();
    private static final Instant ARRIVAL = DEPARTURE.plus(Duration.ofHours(2));
    private static final UUID EVENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID RENTAL_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID BRANCH_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID BUS_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID DRIVER_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID ADMIN_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Mock
    private TripService tripService;

    @Captor
    private ArgumentCaptor<RentalAssignedPayload> payloadCaptor;

    private RentalEventsListener listener;

    @BeforeEach
    void setUp() {
        listener = new RentalEventsListener(jsonMapper, tripService);
    }

    @Test
    void rentalAssigned_createsPrivateTripsWithConvertedPayload() {
        listener.onRentalEvent(message(EventTypes.RENTAL_ASSIGNED, rentalPayload(120.5)));

        verify(tripService).createPrivateFromRental(eq(EVENT_ID), eq(EventTypes.RENTAL_ASSIGNED), payloadCaptor.capture());
        RentalAssignedPayload payload = payloadCaptor.getValue();
        assertEquals(RENTAL_ID, payload.rentalId());
        assertEquals(BRANCH_ID, payload.branchId());
        assertEquals(BUS_ID, payload.busId());
        assertEquals(DRIVER_ID, payload.driverId());
        assertEquals(30, payload.passengers());
        assertEquals(0, new BigDecimal("120.5").compareTo(payload.distanceKm()));
        assertEquals(ADMIN_ID, payload.assignedBy());
        assertEquals(List.of(
                new RentalAssignedPayload.Leg("Guatemala", "Antigua", DEPARTURE, ARRIVAL),
                new RentalAssignedPayload.Leg("Antigua", "Guatemala", DEPARTURE.plus(Duration.ofDays(1)),
                        ARRIVAL.plus(Duration.ofDays(1)))), payload.legs());
    }

    @Test
    void rentalAssignedWithIntegerDistance_isConverted() {
        listener.onRentalEvent(message(EventTypes.RENTAL_ASSIGNED, rentalPayload(85)));

        verify(tripService).createPrivateFromRental(eq(EVENT_ID), eq(EventTypes.RENTAL_ASSIGNED), payloadCaptor.capture());
        assertEquals(0, new BigDecimal("85").compareTo(payloadCaptor.getValue().distanceKm()));
    }

    @Test
    void rentalAssignedWithNullFields_passesNulls() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("rentalId", RENTAL_ID.toString());
        payload.put("passengers", null);
        payload.put("distanceKm", null);

        listener.onRentalEvent(message(EventTypes.RENTAL_ASSIGNED, payload));

        verify(tripService).createPrivateFromRental(eq(EVENT_ID), eq(EventTypes.RENTAL_ASSIGNED), payloadCaptor.capture());
        assertEquals(RENTAL_ID, payloadCaptor.getValue().rentalId());
        assertNull(payloadCaptor.getValue().passengers());
        assertNull(payloadCaptor.getValue().distanceKm());
        assertNull(payloadCaptor.getValue().legs());
    }

    @Test
    void rentalAssignedWithoutPayload_passesNullToService() {
        listener.onRentalEvent("{\"eventId\":\"%s\",\"eventType\":\"RentalAssigned\"}".formatted(EVENT_ID));

        verify(tripService).createPrivateFromRental(EVENT_ID, EventTypes.RENTAL_ASSIGNED, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"RentalRequested", "RentalQuoted", "PaymentRequested", "RentalPaid", "RentalRejected"})
    void otherRentalEvent_isIgnored(String eventType) {
        listener.onRentalEvent(message(eventType, Map.of("rentalId", RENTAL_ID.toString())));

        verifyNoInteractions(tripService);
    }

    @Test
    void eventWithoutType_isIgnored() {
        listener.onRentalEvent("{\"eventId\":\"%s\",\"payload\":{}}".formatted(EVENT_ID));

        verifyNoInteractions(tripService);
    }

    @Test
    void malformedMessage_throwsSoItGoesToRetryAndDlt() {
        assertThrows(JacksonException.class, () -> listener.onRentalEvent("{no es json"));

        verifyNoInteractions(tripService);
    }

    private static Map<String, Object> rentalPayload(Object distanceKm) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("rentalId", RENTAL_ID.toString());
        payload.put("branchId", BRANCH_ID.toString());
        payload.put("busId", BUS_ID.toString());
        payload.put("driverId", DRIVER_ID.toString());
        payload.put("passengers", 30);
        payload.put("distanceKm", distanceKm);
        payload.put("customerNit", "1234567-8");
        payload.put("customerUserId", UUID.randomUUID().toString());
        payload.put("assignedBy", ADMIN_ID.toString());
        payload.put("legs", List.of(
                leg("Guatemala", "Antigua", DEPARTURE, ARRIVAL),
                leg("Antigua", "Guatemala", DEPARTURE.plus(Duration.ofDays(1)), ARRIVAL.plus(Duration.ofDays(1)))));
        return payload;
    }

    private static Map<String, Object> leg(String origin, String destination, Instant departure, Instant arrival) {
        return Map.of("originText", origin, "destinationText", destination,
                "departureAt", departure.toString(), "estimatedArrival", arrival.toString(), "order", 1);
    }

    private String message(String eventType, Map<String, Object> payload) {
        return jsonMapper.writeValueAsString(Map.of(
                "eventId", EVENT_ID.toString(),
                "eventType", eventType,
                "occurredAt", "2026-10-29T12:00:00Z",
                "aggregateId", RENTAL_ID.toString(),
                "version", 1,
                "payload", payload));
    }
}
