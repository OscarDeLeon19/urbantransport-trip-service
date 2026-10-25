package com.urbantransport.trip.exception;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ErrorCodes {

    public static final String VALIDATION_ERROR = "VALIDATION_ERROR";
    public static final String ACCESS_DENIED = "ACCESS_DENIED";
    public static final String BRANCH_REQUIRED = "BRANCH_REQUIRED";
    public static final String BRANCH_FORBIDDEN = "BRANCH_FORBIDDEN";
    public static final String DRIVER_FORBIDDEN = "DRIVER_FORBIDDEN";
    public static final String INVALID_DATE_RANGE = "INVALID_DATE_RANGE";
    public static final String FLEET_UNAVAILABLE = "FLEET_UNAVAILABLE";
    public static final String FLEET_ERROR = "FLEET_ERROR";
    public static final String DEPARTURE_IN_PAST = "DEPARTURE_IN_PAST";
    public static final String ARRIVAL_BEFORE_DEPARTURE = "ARRIVAL_BEFORE_DEPARTURE";
    public static final String ROUTE_NOT_FOUND = "ROUTE_NOT_FOUND";
    public static final String ROUTE_NOT_IN_BRANCH = "ROUTE_NOT_IN_BRANCH";
    public static final String BUS_NOT_FOUND = "BUS_NOT_FOUND";
    public static final String BUS_NOT_IN_BRANCH = "BUS_NOT_IN_BRANCH";
    public static final String BUS_INACTIVE = "BUS_INACTIVE";
    public static final String BUS_IN_WORKSHOP = "BUS_IN_WORKSHOP";
    public static final String DRIVER_NOT_FOUND = "DRIVER_NOT_FOUND";
    public static final String DRIVER_NOT_IN_BRANCH = "DRIVER_NOT_IN_BRANCH";
    public static final String DRIVER_INACTIVE = "DRIVER_INACTIVE";
    public static final String LICENSE_EXPIRES_BEFORE_TRIP = "LICENSE_EXPIRES_BEFORE_TRIP";
    public static final String RESOURCE_OVERLAP = "RESOURCE_OVERLAP";
    public static final String TRIP_NOT_FOUND = "TRIP_NOT_FOUND";
    public static final String TRIP_NOT_IN_BRANCH = "TRIP_NOT_IN_BRANCH";
    public static final String TRIP_TYPE_IMMUTABLE = "TRIP_TYPE_IMMUTABLE";
    public static final String PRIVATE_TRIP_NOT_EDITABLE = "PRIVATE_TRIP_NOT_EDITABLE";
    public static final String TRIP_ALREADY_STARTED = "TRIP_ALREADY_STARTED";
    public static final String TRIP_NOT_ASSIGNED_TO_DRIVER = "TRIP_NOT_ASSIGNED_TO_DRIVER";
    public static final String TRIP_MODIFIED = "TRIP_MODIFIED";
    public static final String DEPARTURE_TOO_EARLY = "DEPARTURE_TOO_EARLY";
    public static final String ODOMETER_BELOW_BUS = "ODOMETER_BELOW_BUS";
    public static final String TRIP_NOT_IN_TRANSIT = "TRIP_NOT_IN_TRANSIT";
    public static final String ODOMETER_BELOW_START = "ODOMETER_BELOW_START";
    public static final String CAPACITY_BELOW_SOLD = "CAPACITY_BELOW_SOLD";
    public static final String LAYOUT_INCOMPATIBLE = "LAYOUT_INCOMPATIBLE";
    public static final String TICKETING_UNAVAILABLE = "TICKETING_UNAVAILABLE";
    public static final String TICKETING_ERROR = "TICKETING_ERROR";
}
