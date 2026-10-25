package com.urbantransport.trip.web;

import com.urbantransport.trip.dto.TripCountResponse;
import com.urbantransport.trip.service.TripService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/internal/trips")
@RequiredArgsConstructor
@PreAuthorize("hasRole('INTERNAL_SERVICE')")
public class InternalTripController {

    private final TripService tripService;

    @GetMapping("/active")
    public TripCountResponse countActive(@RequestParam(required = false) UUID busId,
                                         @RequestParam(required = false) UUID driverId) {
        return tripService.countActive(busId, driverId);
    }

    @GetMapping("/count")
    public TripCountResponse countByRoute(@RequestParam(required = false) UUID routeId) {
        return tripService.countByRoute(routeId);
    }
}
