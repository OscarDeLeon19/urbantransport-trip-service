package com.urbantransport.trip.event;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class EventTypes {

    public static final String AGGREGATE_TRIP = "Trip";

    public static final String TRIP_SCHEDULED = "TripScheduled";
    public static final String TRIP_UPDATED = "TripUpdated";
    public static final String TRIP_DELETED = "TripDeleted";
    public static final String TRIP_STARTED = "TripStarted";
    public static final String TRIP_COMPLETED = "TripCompleted";
    public static final String PRIVATE_TRIP_REJECTED = "PrivateTripRejected";

    public static final String RENTAL_ASSIGNED = "RentalAssigned";
}
