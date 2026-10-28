package com.urbantransport.trip.event;

import com.urbantransport.trip.service.TripService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class RentalEventsListener {

    private final JsonMapper jsonMapper;
    private final TripService tripService;

    @KafkaListener(topics = "${app.kafka.topic.rental-events}")
    public void onRentalEvent(String message) {
        IncomingEvent event = jsonMapper.readValue(message, IncomingEvent.class);
        String eventType = event.eventType() == null ? "" : event.eventType();

        switch (eventType) {
            case EventTypes.RENTAL_ASSIGNED -> tripService.createPrivateFromRental(event.eventId(), eventType,
                    jsonMapper.convertValue(event.payload(), RentalAssignedPayload.class));
            default -> log.debug("Evento {} ignorado", eventType);
        }
    }
}
