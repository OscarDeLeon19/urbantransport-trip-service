package com.urbantransport.trip.service;

import com.urbantransport.trip.domain.TripStatus;
import com.urbantransport.trip.dto.CreateTripRequest;
import com.urbantransport.trip.dto.PageResponse;
import com.urbantransport.trip.dto.RegisterArrivalRequest;
import com.urbantransport.trip.dto.RegisterDepartureRequest;
import com.urbantransport.trip.dto.TripArrivalResponse;
import com.urbantransport.trip.dto.TripCountResponse;
import com.urbantransport.trip.dto.TripDepartureResponse;
import com.urbantransport.trip.dto.TripDetailResponse;
import com.urbantransport.trip.dto.TripResponse;
import com.urbantransport.trip.dto.UpdateTripRequest;

import java.time.LocalDate;
import java.util.UUID;

public interface TripService {

    TripResponse createRegular(CreateTripRequest request, UUID branchId, UUID createdBy);

    TripResponse update(UUID id, UpdateTripRequest request, UUID branchId, String accessToken);

    void delete(UUID id, UUID branchId);

    TripDepartureResponse registerDepartureAsAdmin(UUID id, RegisterDepartureRequest request, UUID branchId,
                                                   UUID registeredBy);

    TripDepartureResponse registerDepartureAsDriver(UUID id, RegisterDepartureRequest request, UUID userId);

    TripArrivalResponse registerArrivalAsAdmin(UUID id, RegisterArrivalRequest request, UUID branchId,
                                               UUID registeredBy);

    TripArrivalResponse registerArrivalAsDriver(UUID id, RegisterArrivalRequest request, UUID userId);

    PageResponse<TripResponse> search(TripStatus status, UUID routeId, UUID busId, UUID driverId, UUID branchId,
                                      LocalDate from, LocalDate to, int page, int size);

    PageResponse<TripResponse> searchForDriver(UUID userId, UUID requestedDriverId, TripStatus status, UUID routeId,
                                               UUID busId, LocalDate from, LocalDate to, int page, int size);

    TripDetailResponse findById(UUID id, UUID scopeBranchId);

    TripDetailResponse findByIdForDriver(UUID id, UUID userId);

    TripCountResponse countActive(UUID busId, UUID driverId);

    TripCountResponse countByRoute(UUID routeId);
}
