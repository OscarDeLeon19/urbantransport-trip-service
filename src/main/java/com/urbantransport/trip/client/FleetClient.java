package com.urbantransport.trip.client;

import java.util.UUID;

public interface FleetClient {

    FleetRouteResponse getRoute(UUID routeId);

    FleetBusResponse getBus(UUID busId);

    FleetDriverResponse getDriver(UUID driverId);

    FleetDriverResponse getDriverByUserId(UUID userId);
}
