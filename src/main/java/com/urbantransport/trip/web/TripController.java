package com.urbantransport.trip.web;

import com.urbantransport.trip.domain.TripStatus;
import com.urbantransport.trip.dto.CreateTripRequest;
import com.urbantransport.trip.dto.PageResponse;
import com.urbantransport.trip.dto.RegisterArrivalRequest;
import com.urbantransport.trip.dto.RegisterDepartureRequest;
import com.urbantransport.trip.dto.TripArrivalResponse;
import com.urbantransport.trip.dto.TripDepartureResponse;
import com.urbantransport.trip.dto.TripDetailResponse;
import com.urbantransport.trip.dto.TripResponse;
import com.urbantransport.trip.dto.UpdateTripRequest;
import com.urbantransport.trip.exception.ApiException;
import com.urbantransport.trip.exception.ErrorCodes;
import com.urbantransport.trip.service.TripService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/trips")
@RequiredArgsConstructor
public class TripController {

    private static final String DRIVER = "DRIVER";
    private static final String SYSTEM_ADMIN = "SYSTEM_ADMIN";

    private final TripService tripService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('BRANCH_ADMIN')")
    public TripResponse create(@Valid @RequestBody CreateTripRequest request, @AuthenticationPrincipal Jwt jwt) {
        return tripService.createRegular(request, branchIdOf(jwt), UUID.fromString(jwt.getSubject()));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('BRANCH_ADMIN')")
    public TripResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateTripRequest request,
                               @AuthenticationPrincipal Jwt jwt) {
        return tripService.update(id, request, branchIdOf(jwt), jwt.getTokenValue());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('BRANCH_ADMIN')")
    public void delete(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        tripService.delete(id, branchIdOf(jwt));
    }

    @PostMapping("/{id}/departure")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('BRANCH_ADMIN', 'DRIVER')")
    public TripDepartureResponse registerDeparture(@PathVariable UUID id,
                                                   @Valid @RequestBody RegisterDepartureRequest request,
                                                   @AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        if (isDriver(jwt)) {
            return tripService.registerDepartureAsDriver(id, request, userId);
        }
        return tripService.registerDepartureAsAdmin(id, request, branchIdOf(jwt), userId);
    }

    @PostMapping("/{id}/arrival")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('BRANCH_ADMIN', 'DRIVER')")
    public TripArrivalResponse registerArrival(@PathVariable UUID id,
                                               @Valid @RequestBody RegisterArrivalRequest request,
                                               @AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        if (isDriver(jwt)) {
            return tripService.registerArrivalAsDriver(id, request, userId);
        }
        return tripService.registerArrivalAsAdmin(id, request, branchIdOf(jwt), userId);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('BRANCH_ADMIN', 'SYSTEM_ADMIN', 'DRIVER')")
    public PageResponse<TripResponse> search(@RequestParam(required = false) TripStatus status,
                                             @RequestParam(required = false) UUID routeId,
                                             @RequestParam(required = false) UUID busId,
                                             @RequestParam(required = false) UUID driverId,
                                             @RequestParam(required = false) UUID branchId,
                                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "20") int size,
                                             @AuthenticationPrincipal Jwt jwt) {
        if (isDriver(jwt)) {
            return tripService.searchForDriver(UUID.fromString(jwt.getSubject()), driverId, status, routeId, busId,
                    from, to, page, size);
        }
        return tripService.search(status, routeId, busId, driverId, resolveBranchFilter(jwt, branchId),
                from, to, page, size);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('BRANCH_ADMIN', 'SYSTEM_ADMIN', 'DRIVER')")
    public TripDetailResponse findById(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        if (isDriver(jwt)) {
            return tripService.findByIdForDriver(id, UUID.fromString(jwt.getSubject()));
        }
        return tripService.findById(id, isSystemAdmin(jwt) ? null : branchIdOf(jwt));
    }

    private UUID resolveBranchFilter(Jwt jwt, UUID requestedBranchId) {
        if (isSystemAdmin(jwt)) {
            return requestedBranchId;
        }
        UUID ownBranchId = branchIdOf(jwt);
        if (requestedBranchId != null && !requestedBranchId.equals(ownBranchId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.BRANCH_FORBIDDEN,
                    "Solo puedes consultar viajes de tu sucursal");
        }
        return ownBranchId;
    }

    private boolean isSystemAdmin(Jwt jwt) {
        return hasRole(jwt, SYSTEM_ADMIN);
    }

    private boolean isDriver(Jwt jwt) {
        return hasRole(jwt, DRIVER);
    }

    private boolean hasRole(Jwt jwt, String role) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        return roles != null && roles.contains(role);
    }

    private UUID branchIdOf(Jwt jwt) {
        String branchId = jwt.getClaimAsString("branchId");
        if (branchId == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.BRANCH_REQUIRED, "El usuario no tiene sucursal asignada");
        }
        return UUID.fromString(branchId);
    }
}
