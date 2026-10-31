package com.urbantransport.trip.event;

import com.urbantransport.trip.client.FleetBusResponse;
import com.urbantransport.trip.client.FleetRouteResponse;
import com.urbantransport.trip.domain.TripEntity;
import com.urbantransport.trip.domain.TripStatus;
import com.urbantransport.trip.domain.TripType;
import lombok.Value;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

@Value
public class TripEventPayload {

    private static final Short NO_AISLE = 0;

    UUID tripId;
    TripType type;
    UUID routeId;
    UUID originBranchId;
    UUID destinationBranchId;
    String originName;
    String destinationName;
    String originText;
    String destinationText;
    UUID busId;
    String busPlate;
    Integer capacity;
    SeatLayout seatLayout;
    UUID driverId;
    BigDecimal ticketPrice;
    BigDecimal distanceKm;
    LocalDateTime departureAt;
    LocalDateTime estimatedArrival;
    TripStatus status;

    public static TripEventPayload from(TripEntity trip, FleetRouteResponse route, FleetBusResponse bus,
                                            ZoneId zone) {
        return new TripEventPayload(
                trip.getId(),
                trip.getType(),
                trip.getRouteId(),
                trip.getOriginBranchId(),
                trip.getDestinationBranchId(),
                route == null ? null : route.originBranchName(),
                route == null ? null : route.destinationBranchName(),
                trip.getOriginText(),
                trip.getDestinationText(),
                trip.getBusId(),
                bus.plate(),
                bus.capacity(),
                new SeatLayout(bus.seatRows(), bus.seatColumns(),
                        bus.aisleAfterColumn() == null ? NO_AISLE : bus.aisleAfterColumn()),
                trip.getDriverId(),
                trip.getTicketPrice(),
                trip.getDistanceKm(),
                LocalDateTime.ofInstant(trip.getScheduledDeparture(), zone),
                LocalDateTime.ofInstant(trip.getEstimatedArrival(), zone),
                trip.getStatus());
    }

    @Value
    public static class SeatLayout {
        Short rows;
        Short columns;
        Short aisleAfterColumn;
    }
}
