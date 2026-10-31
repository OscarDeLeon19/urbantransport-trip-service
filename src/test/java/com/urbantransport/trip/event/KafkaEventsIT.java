package com.urbantransport.trip.event;

import com.urbantransport.trip.AbstractIntegrationTest;
import com.urbantransport.trip.domain.TripEntity;
import com.urbantransport.trip.domain.TripStatus;
import com.urbantransport.trip.domain.TripType;
import com.urbantransport.trip.exception.ApiException;
import com.urbantransport.trip.exception.ErrorCodes;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class KafkaEventsIT extends AbstractIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private ConsumerFactory<String, String> consumerFactory;

    @Autowired
    private JsonMapper jsonMapper;

    @Value("${app.kafka.topic.rental-events}")
    private String rentalTopic;

    @Value("${app.kafka.topic.trip-events}")
    private String tripTopic;

    @Test
    void rentalAssigned_createsOnePrivateTripPerLegAndIgnoresDuplicatedEvent() throws Exception {
        UUID branchId = UUID.randomUUID();
        UUID busId = UUID.randomUUID();
        UUID driverId = UUID.randomUUID();
        UUID rentalId = UUID.randomUUID();
        UUID assignedEventId = UUID.randomUUID();
        when(fleetClient.getBus(busId)).thenReturn(bus(busId, branchId));
        when(fleetClient.getDriver(driverId)).thenReturn(driver(driverId, UUID.randomUUID(), branchId));
        LocalDate day = today().plusDays(5);
        Map<String, Object> payload = rentalPayload(rentalId, branchId, busId, driverId, 30, List.of(
                leg("Guatemala", "Antigua", at(day, 8), at(day, 10)),
                leg("Antigua", "Guatemala", at(day.plusDays(1), 16), at(day.plusDays(1), 18))));

        send(rentalTopic, rentalId, envelope(assignedEventId, EventTypes.RENTAL_ASSIGNED, rentalId, payload));
        send(rentalTopic, rentalId, envelope(assignedEventId, EventTypes.RENTAL_ASSIGNED, rentalId, payload));
        send(rentalTopic, rentalId, envelope(UUID.randomUUID(), EventTypes.RENTAL_ASSIGNED, rentalId, payload));

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertEquals(List.of(EventTypes.PRIVATE_TRIP_REJECTED), outboxEventTypes(rentalId)));

        List<TripEntity> trips = tripRepository.findAll().stream()
                .filter(trip -> rentalId.equals(trip.getRentalId()))
                .sorted(Comparator.comparing(TripEntity::getScheduledDeparture))
                .toList();
        assertEquals(2, trips.size());
        assertEquals(List.of("Guatemala", "Antigua"), trips.stream().map(TripEntity::getOriginText).toList());
        assertEquals(List.of(at(day, 8), at(day.plusDays(1), 16)),
                trips.stream().map(TripEntity::getScheduledDeparture).toList());
        trips.forEach(trip -> {
            assertEquals(TripType.PRIVATE, trip.getType());
            assertEquals(TripStatus.SCHEDULED, trip.getStatus());
            assertEquals(branchId, trip.getOriginBranchId());
            assertEquals(busId, trip.getBusId());
            assertEquals(USER_ID, trip.getCreatedBy());
            assertEquals(List.of(EventTypes.TRIP_SCHEDULED), outboxEventTypes(trip.getId()));
        });
        assertEquals(1, count("SELECT COUNT(*) FROM processed_events WHERE event_id = ?", assignedEventId));
        assertEquals(2, count("SELECT COUNT(*) FROM processed_events"));
        JsonNode rejection = jsonMapper.readTree(outboxPayload(rentalId, EventTypes.PRIVATE_TRIP_REJECTED))
                .get("payload");
        assertEquals(ErrorCodes.RESOURCE_OVERLAP, rejection.get("code").asString());
    }

    @Test
    void rentalAssignedOverCapacity_publishesPrivateTripRejectedToTripEvents() throws Exception {
        UUID branchId = UUID.randomUUID();
        UUID busId = UUID.randomUUID();
        UUID driverId = UUID.randomUUID();
        UUID rentalId = UUID.randomUUID();
        when(fleetClient.getBus(busId)).thenReturn(bus(busId, branchId));
        LocalDate day = today().plusDays(10);

        send(rentalTopic, rentalId, envelope(UUID.randomUUID(), EventTypes.RENTAL_ASSIGNED, rentalId,
                rentalPayload(rentalId, branchId, busId, driverId, 60,
                        List.of(leg("Guatemala", "Xela", at(day, 6), at(day, 11))))));

        ConsumerRecord<String, String> record = awaitRecord(tripTopic, rentalId.toString());

        JsonNode envelope = jsonMapper.readTree(record.value());
        assertEquals(EventTypes.PRIVATE_TRIP_REJECTED, envelope.get("eventType").asString());
        assertEquals(rentalId.toString(), envelope.get("aggregateId").asString());
        JsonNode payload = envelope.get("payload");
        assertEquals(rentalId.toString(), payload.get("rentalId").asString());
        assertEquals(busId.toString(), payload.get("busId").asString());
        assertEquals(driverId.toString(), payload.get("driverId").asString());
        assertEquals(ErrorCodes.BUS_CAPACITY_EXCEEDED, payload.get("code").asString());
        assertTrue(tripRepository.findAll().stream().noneMatch(trip -> rentalId.equals(trip.getRentalId())));
    }

    @Test
    void rentalAssignedWhileFleetIsDown_isRetriedAndEndsInDeadLetterTopic() throws Exception {
        UUID busId = UUID.randomUUID();
        UUID rentalId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        when(fleetClient.getBus(busId)).thenThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                ErrorCodes.FLEET_UNAVAILABLE, "Fleet no disponible"));
        LocalDate day = today().plusDays(15);

        send(rentalTopic, rentalId, envelope(eventId, EventTypes.RENTAL_ASSIGNED, rentalId,
                rentalPayload(rentalId, UUID.randomUUID(), busId, UUID.randomUUID(), 20,
                        List.of(leg("Guatemala", "Xela", at(day, 6), at(day, 11))))));

        ConsumerRecord<String, String> record = awaitRecord(rentalTopic + ".DLT", rentalId.toString());

        assertEquals(eventId.toString(), jsonMapper.readTree(record.value()).get("eventId").asString());
        assertEquals(0, count("SELECT COUNT(*) FROM processed_events WHERE event_id = ?", eventId));
        assertEquals(List.of(), outboxEventTypes(rentalId));
    }

    @Test
    void outboxEvent_isPublishedToTripEventsWithAggregateAsKey() throws Exception {
        UUID branchId = UUID.randomUUID();
        UUID routeId = UUID.randomUUID();
        UUID busId = UUID.randomUUID();
        UUID driverId = UUID.randomUUID();
        when(fleetClient.getRoute(routeId)).thenReturn(route(routeId, branchId, UUID.randomUUID()));
        when(fleetClient.getBus(busId)).thenReturn(bus(busId, branchId));
        when(fleetClient.getDriver(driverId)).thenReturn(driver(driverId, UUID.randomUUID(), branchId));
        LocalDate day = today().plusDays(2);

        MvcResult result = mockMvc.perform(post("/trips").with(branchAdmin(branchId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"%s","busId":"%s","driverId":"%s","scheduledDeparture":"%s",
                                 "estimatedArrival":"%s","operationAt":"%s"}
                                """.formatted(routeId, busId, driverId, at(day, 9), at(day, 12), Instant.now())))
                .andExpect(status().isCreated())
                .andReturn();
        UUID tripId = idAt(result, "$.id");

        ConsumerRecord<String, String> record = awaitRecord(tripTopic, tripId.toString());

        JsonNode envelope = jsonMapper.readTree(record.value());
        assertEquals(EventTypes.TRIP_SCHEDULED, envelope.get("eventType").asString());
        assertEquals(tripId.toString(), envelope.get("aggregateId").asString());
        assertEquals(1, envelope.get("version").asInt());
        JsonNode payload = envelope.get("payload");
        assertEquals(tripId.toString(), payload.get("tripId").asString());
        assertEquals("REGULAR", payload.get("type").asString());
        assertEquals(busId.toString(), payload.get("busId").asString());
        assertEquals(45.0, payload.get("ticketPrice").asDouble());
        assertEquals(10, payload.get("seatLayout").get("rows").asInt());
        assertEquals(day.atTime(9, 0), LocalDateTime.parse(payload.get("departureAt").asString()));
        await().atMost(TIMEOUT).untilAsserted(() -> assertNotNull(jdbcTemplate.queryForObject(
                "SELECT published_at FROM outbox_events WHERE aggregate_id = ?", Instant.class, tripId)));
    }

    @Test
    void malformedRentalEvent_endsInDeadLetterTopic() throws Exception {
        UUID key = UUID.randomUUID();

        send(rentalTopic, key, "{no es json");

        ConsumerRecord<String, String> record = awaitRecord(rentalTopic + ".DLT", key.toString());
        assertEquals("{no es json", record.value());
    }

    private void send(String topic, UUID key, String value) throws Exception {
        kafkaTemplate.send(topic, key.toString(), value).get(10, TimeUnit.SECONDS);
    }

    private String envelope(UUID eventId, String eventType, UUID aggregateId, Map<String, Object> payload) {
        return jsonMapper.writeValueAsString(Map.of(
                "eventId", eventId.toString(),
                "eventType", eventType,
                "occurredAt", Instant.now().toString(),
                "aggregateId", aggregateId.toString(),
                "version", 1,
                "correlationId", UUID.randomUUID().toString(),
                "payload", payload));
    }

    private static Map<String, Object> rentalPayload(UUID rentalId, UUID branchId, UUID busId, UUID driverId,
                                                     int passengers, List<Map<String, Object>> legs) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("rentalId", rentalId.toString());
        payload.put("branchId", branchId.toString());
        payload.put("busId", busId.toString());
        payload.put("driverId", driverId.toString());
        payload.put("passengers", passengers);
        payload.put("distanceKm", 85.5);
        payload.put("customerNit", "1234567-8");
        payload.put("customerUserId", UUID.randomUUID().toString());
        payload.put("assignedBy", USER_ID.toString());
        payload.put("legs", legs);
        return payload;
    }

    private static Map<String, Object> leg(String origin, String destination, Instant departure, Instant arrival) {
        return Map.of("originText", origin, "destinationText", destination,
                "departureAt", departure.toString(), "estimatedArrival", arrival.toString());
    }

    private ConsumerRecord<String, String> awaitRecord(String topic, String key) {
        List<ConsumerRecord<String, String>> received = new ArrayList<>();
        try (Consumer<String, String> consumer = consumerFactory.createConsumer("it-" + UUID.randomUUID(), "it")) {
            consumer.subscribe(List.of(topic));
            await().atMost(TIMEOUT).pollInSameThread().untilAsserted(() -> {
                consumer.poll(Duration.ofMillis(500)).forEach(received::add);
                assertTrue(received.stream().anyMatch(record -> key.equals(record.key())),
                        "no llegó el mensaje con key " + key + " a " + topic);
            });
        }
        return received.stream().filter(record -> key.equals(record.key())).findFirst().orElseThrow();
    }
}
