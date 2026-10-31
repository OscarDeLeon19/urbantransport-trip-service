package com.urbantransport.trip.service.impl;

import com.urbantransport.trip.client.FleetBusResponse;
import com.urbantransport.trip.client.FleetClient;
import com.urbantransport.trip.client.FleetDriverResponse;
import com.urbantransport.trip.client.FleetRouteResponse;
import com.urbantransport.trip.client.TicketingClient;
import com.urbantransport.trip.client.TicketingSoldSummaryResponse;
import com.urbantransport.trip.config.TripProperties;
import com.urbantransport.trip.domain.ProcessedEventEntity;
import com.urbantransport.trip.domain.TripArrivalEntity;
import com.urbantransport.trip.domain.TripDepartureEntity;
import com.urbantransport.trip.domain.TripEntity;
import com.urbantransport.trip.domain.TripStatus;
import com.urbantransport.trip.domain.TripType;
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
import com.urbantransport.trip.event.EventTypes;
import com.urbantransport.trip.event.PrivateTripRejectedPayload;
import com.urbantransport.trip.event.RentalAssignedPayload;
import com.urbantransport.trip.event.TripCompletedPayload;
import com.urbantransport.trip.event.TripDeletedPayload;
import com.urbantransport.trip.event.TripEventPayload;
import com.urbantransport.trip.event.TripStartedPayload;
import com.urbantransport.trip.exception.ApiException;
import com.urbantransport.trip.exception.ErrorCodes;
import com.urbantransport.trip.repository.ProcessedEventRepository;
import com.urbantransport.trip.repository.TripArrivalRepository;
import com.urbantransport.trip.repository.TripDepartureRepository;
import com.urbantransport.trip.repository.TripRepository;
import com.urbantransport.trip.repository.TripSpecifications;
import com.urbantransport.trip.service.OutboxService;
import com.urbantransport.trip.service.TripService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TripServiceImpl implements TripService {

    private static final String BUS_INACTIVE = "INACTIVO";
    private static final String BUS_IN_WORKSHOP = "EN_TALLER";
    private static final List<TripStatus> ACTIVE_STATUSES = List.of(TripStatus.SCHEDULED, TripStatus.IN_TRANSIT);
    private static final int BUS_LOCK = 1;
    private static final int DRIVER_LOCK = 2;
    private static final DateTimeFormatter DISPLAY_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final BigDecimal PRIVATE_TRIP_SURCHARGE = new BigDecimal("1.15");
    private static final int MAX_PAGE_SIZE = 100;

    private final TripRepository tripRepository;
    private final TripDepartureRepository tripDepartureRepository;
    private final TripArrivalRepository tripArrivalRepository;
    private final OutboxService outboxService;
    private final FleetClient fleetClient;
    private final TicketingClient ticketingClient;
    private final TransactionTemplate transactionTemplate;
    private final TripProperties tripProperties;
    private final ProcessedEventRepository processedEventRepository;

    @Value("${app.zone:America/Guatemala}")
    private ZoneId zone;

    @Override
    public TripResponse createRegular(CreateTripRequest request, UUID branchId, UUID createdBy) {
        Instant departure = request.scheduledDeparture();
        Instant arrival = request.estimatedArrival();
        validateSchedule(departure, arrival, request.operationAt());

        FleetRouteResponse route = fleetClient.getRoute(request.routeId());
        if (!route.originBranchId().equals(branchId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.ROUTE_NOT_IN_BRANCH,
                    "Solo el administrador de la sucursal de origen puede programar viajes en esta ruta");
        }

        FleetBusResponse bus = fleetClient.getBus(request.busId());
        validateBus(bus, branchId);

        FleetDriverResponse driver = fleetClient.getDriver(request.driverId());
        validateDriver(driver, branchId, arrival);

        TripEntity trip = transactionTemplate.execute(status -> {
            lockResources(bus.id(), driver.id());
            assertNoOverlap(null, bus.id(), driver.id(), departure, arrival);

            TripEntity saved = tripRepository.save(TripEntity.builder()
                    .type(TripType.REGULAR)
                    .routeId(route.id())
                    .originBranchId(route.originBranchId())
                    .destinationBranchId(route.destinationBranchId())
                    .busId(bus.id())
                    .driverId(driver.id())
                    .scheduledDeparture(departure)
                    .estimatedArrival(arrival)
                    .ticketPrice(route.ticketPrice())
                    .distanceKm(route.distanceKm())
                    .status(TripStatus.SCHEDULED)
                    .createdBy(createdBy)
                    .build());

            outboxService.record(EventTypes.AGGREGATE_TRIP, saved.getId(), EventTypes.TRIP_SCHEDULED,
                    TripEventPayload.from(saved, route, bus, zone));
            return saved;
        });

        return TripResponse.fromEntity(trip);
    }

    @Override
    public void createPrivateFromRental(UUID eventId, String eventType, RentalAssignedPayload payload) {
        if (processedEventRepository.existsById(eventId)) {
            log.debug("Evento {} ya procesado, se ignora", eventId);
            return;
        }
        if (payload == null || payload.rentalId() == null || payload.legs() == null || payload.legs().isEmpty()) {
            throw new IllegalArgumentException("RentalAssigned " + eventId + " sin rentalId o sin tramos");
        }

        try {
            FleetBusResponse bus = fleetClient.getBus(payload.busId());
            validateBus(bus, payload.branchId());
            validateCapacity(bus, payload.passengers());

            FleetDriverResponse driver = fleetClient.getDriver(payload.driverId());
            validateDriver(driver, payload.branchId(), payload.legs().getLast().estimatedArrival());

            transactionTemplate.executeWithoutResult(status -> {
                if (processedEventRepository.existsById(eventId)) {
                    return;
                }
                lockResources(bus.id(), driver.id());
                for (RentalAssignedPayload.Leg leg : payload.legs()) {
                    assertNoOverlap(null, bus.id(), driver.id(), leg.departureAt(), leg.estimatedArrival());

                    TripEntity saved = tripRepository.save(TripEntity.builder()
                            .type(TripType.PRIVATE)
                            .originBranchId(payload.branchId())
                            .originText(leg.originText())
                            .destinationText(leg.destinationText())
                            .busId(bus.id())
                            .driverId(driver.id())
                            .scheduledDeparture(leg.departureAt())
                            .estimatedArrival(leg.estimatedArrival())
                            .distanceKm(payload.distanceKm())
                            .status(TripStatus.SCHEDULED)
                            .rentalId(payload.rentalId())
                            .createdBy(payload.assignedBy())
                            .build());

                    outboxService.record(EventTypes.AGGREGATE_TRIP, saved.getId(), EventTypes.TRIP_SCHEDULED,
                            TripEventPayload.from(saved, null, bus, zone));
                }
                markProcessed(eventId, eventType);
            });
        } catch (ApiException exception) {
            if (exception.getStatus().is5xxServerError()) {
                throw exception;
            }
            rejectPrivateTrip(eventId, eventType, payload, exception);
        }
    }

    private void rejectPrivateTrip(UUID eventId, String eventType, RentalAssignedPayload payload,
                                   ApiException cause) {
        log.info("Viaje privado del alquiler {} rechazado: {}", payload.rentalId(), cause.getMessage());
        transactionTemplate.executeWithoutResult(status -> {
            if (processedEventRepository.existsById(eventId)) {
                return;
            }
            outboxService.record(EventTypes.AGGREGATE_TRIP, payload.rentalId(), EventTypes.PRIVATE_TRIP_REJECTED,
                    PrivateTripRejectedPayload.from(payload, cause));
            markProcessed(eventId, eventType);
        });
    }

    private void validateCapacity(FleetBusResponse bus, Integer passengers) {
        if (passengers != null && bus.capacity() < passengers) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCodes.BUS_CAPACITY_EXCEEDED,
                    "El bus " + bus.plate() + " tiene " + bus.capacity() + " asientos y el alquiler es para "
                            + passengers + " pasajeros");
        }
    }

    private void markProcessed(UUID eventId, String eventType) {
        processedEventRepository.save(ProcessedEventEntity.builder()
                .eventId(eventId)
                .eventType(eventType)
                .build());
    }

    @Override
    public TripResponse update(UUID id, UpdateTripRequest request, UUID branchId, String accessToken) {
        TripEntity current = getOwnedOrThrow(id, branchId);
        assertEditable(current, request.type());

        Instant departure = request.scheduledDeparture();
        Instant arrival = request.estimatedArrival();
        validateSchedule(departure, arrival, request.operationAt());

        FleetRouteResponse route = fleetClient.getRoute(current.getRouteId());

        FleetBusResponse bus = fleetClient.getBus(request.busId());
        validateBus(bus, branchId);

        FleetDriverResponse driver = fleetClient.getDriver(request.driverId());
        validateDriver(driver, branchId, arrival);

        if (!bus.id().equals(current.getBusId())) {
            assertBusChangeAllowed(id, current.getBusId(), bus, accessToken);
        }

        TripEntity trip = transactionTemplate.execute(status -> {
            lockResources(bus.id(), driver.id());
            TripEntity locked = getLockedOwnedOrThrow(id, branchId);
            assertEditable(locked, request.type());
            assertNoOverlap(id, bus.id(), driver.id(), departure, arrival);

            locked.setBusId(bus.id());
            locked.setDriverId(driver.id());
            locked.setScheduledDeparture(departure);
            locked.setEstimatedArrival(arrival);

            outboxService.record(EventTypes.AGGREGATE_TRIP, locked.getId(), EventTypes.TRIP_UPDATED,
                    TripEventPayload.from(locked, route, bus, zone));
            return locked;
        });

        return TripResponse.fromEntity(trip);
    }

    @Override
    @Transactional
    public void delete(UUID id, UUID branchId) {
        TripEntity trip = getLockedOwnedOrThrow(id, branchId);
        if (trip.getStatus() != TripStatus.SCHEDULED) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCodes.TRIP_ALREADY_STARTED,
                    "El viaje ya inició o terminó, no se puede eliminar");
        }
        if (trip.getType() == TripType.PRIVATE) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCodes.PRIVATE_TRIP_NOT_EDITABLE,
                    "Los viajes privados se gestionan desde el alquiler");
        }

        trip.setStatus(TripStatus.DELETED);

        outboxService.record(EventTypes.AGGREGATE_TRIP, trip.getId(), EventTypes.TRIP_DELETED,
                TripDeletedPayload.fromEntity(trip));
    }

    @Override
    public TripDepartureResponse registerDepartureAsAdmin(UUID id, RegisterDepartureRequest request, UUID branchId,
                                                          UUID registeredBy) {
        TripEntity trip = getOwnedOrThrow(id, branchId);
        return registerDeparture(trip, request, registeredBy);
    }

    @Override
    public TripDepartureResponse registerDepartureAsDriver(UUID id, RegisterDepartureRequest request, UUID userId) {
        TripEntity trip = getOrThrow(id);
        FleetDriverResponse driver = fleetClient.getDriver(trip.getDriverId());
        assertAssignedDriver(driver, userId);
        return registerDeparture(trip, request, userId);
    }

    @Override
    public TripArrivalResponse registerArrivalAsAdmin(UUID id, RegisterArrivalRequest request, UUID branchId,
                                                      UUID registeredBy) {
        TripEntity trip = getOwnedOrThrow(id, branchId);
        assertInTransit(trip);
        FleetDriverResponse driver = fleetClient.getDriver(trip.getDriverId());
        return registerArrival(trip, driver, request, registeredBy);
    }

    @Override
    public TripArrivalResponse registerArrivalAsDriver(UUID id, RegisterArrivalRequest request, UUID userId) {
        TripEntity trip = getOrThrow(id);
        FleetDriverResponse driver = fleetClient.getDriver(trip.getDriverId());
        assertAssignedDriver(driver, userId);
        assertInTransit(trip);
        return registerArrival(trip, driver, request, userId);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<TripResponse> search(TripStatus status, UUID routeId, UUID busId, UUID driverId,
                                             UUID branchId, LocalDate from, LocalDate to, int page, int size) {
        return findPage(status, routeId, busId, driverId, branchId, from, to, page, size);
    }

    @Override
    public PageResponse<TripResponse> searchForDriver(UUID userId, UUID requestedDriverId, TripStatus status,
                                                      UUID routeId, UUID busId, LocalDate from, LocalDate to,
                                                      int page, int size) {
        UUID driverId = fleetClient.getDriverByUserId(userId).id();
        if (requestedDriverId != null && !requestedDriverId.equals(driverId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.DRIVER_FORBIDDEN,
                    "Solo puedes consultar tus viajes");
        }
        return findPage(status, routeId, busId, driverId, null, from, to, page, size);
    }

    @Override
    @Transactional(readOnly = true)
    public TripDetailResponse findById(UUID id, UUID scopeBranchId) {
        TripEntity trip = scopeBranchId == null ? getOrThrow(id) : getOwnedOrThrow(id, scopeBranchId);
        return toDetail(trip);
    }

    @Override
    public TripDetailResponse findByIdForDriver(UUID id, UUID userId) {
        TripEntity trip = getOrThrow(id);
        assertAssignedDriver(fleetClient.getDriver(trip.getDriverId()), userId);
        return toDetail(trip);
    }

    @Override
    @Transactional(readOnly = true)
    public TripCountResponse countActive(UUID busId, UUID driverId) {
        if ((busId == null) == (driverId == null)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_ERROR,
                    "Envía busId o driverId, solo uno de los dos");
        }
        long count = busId != null
                ? tripRepository.countByBusIdAndStatusIn(busId, ACTIVE_STATUSES)
                : tripRepository.countByDriverIdAndStatusIn(driverId, ACTIVE_STATUSES);
        return new TripCountResponse(count);
    }

    @Override
    @Transactional(readOnly = true)
    public TripCountResponse countByRoute(UUID routeId) {
        if (routeId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_ERROR, "routeId es obligatorio");
        }
        return new TripCountResponse(tripRepository.countByRouteIdAndStatusNot(routeId, TripStatus.DELETED));
    }

    private PageResponse<TripResponse> findPage(TripStatus status, UUID routeId, UUID busId, UUID driverId,
                                                UUID branchId, LocalDate from, LocalDate to, int page, int size) {
        if (from != null && to != null && to.isBefore(from)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.INVALID_DATE_RANGE,
                    "La fecha final no puede ser anterior a la inicial");
        }
        PageRequest pageable = PageRequest.of(
                Math.max(page, 0),
                Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "scheduledDeparture"));
        Instant departureFrom = from == null ? null : from.atStartOfDay(zone).toInstant();
        Instant departureBefore = to == null ? null : to.plusDays(1).atStartOfDay(zone).toInstant();
        return PageResponse.of(tripRepository
                .findAll(TripSpecifications.filter(status, routeId, busId, driverId, branchId,
                        departureFrom, departureBefore), pageable)
                .map(TripResponse::fromEntity));
    }

    private TripDetailResponse toDetail(TripEntity trip) {
        return TripDetailResponse.fromEntity(trip,
                tripDepartureRepository.findByTripId(trip.getId()).orElse(null),
                tripArrivalRepository.findByTripId(trip.getId()).orElse(null));
    }

    private TripArrivalResponse registerArrival(TripEntity trip, FleetDriverResponse driver,
                                                RegisterArrivalRequest request, UUID registeredBy) {
        TripDepartureEntity departure = tripDepartureRepository.findByTripId(trip.getId())
                .orElseThrow(() -> new IllegalStateException("El viaje " + trip.getId() + " está en tránsito sin salida registrada"));

        if (!request.actualArrivalAt().isAfter(departure.getActualDepartureAt())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.ARRIVAL_BEFORE_DEPARTURE,
                    "La llegada debe ser posterior a la salida registrada ("
                            + DISPLAY_FORMAT.format(LocalDateTime.ofInstant(departure.getActualDepartureAt(), zone)) + ")");
        }
        if (request.odometerEnd().compareTo(departure.getOdometerStart()) < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.ODOMETER_BELOW_START,
                    "El kilometraje final (" + request.odometerEnd() + ") es menor al inicial ("
                            + departure.getOdometerStart() + ")");
        }

        BigDecimal driverPay = calculateDriverPay(driver.baseSalaryPerTrip(), trip.getType());

        TripArrivalEntity arrival = transactionTemplate.execute(status -> {
            TripEntity locked = getLockedOrThrow(trip.getId());
            assertInTransit(locked);

            TripArrivalEntity saved = tripArrivalRepository.save(TripArrivalEntity.builder()
                    .tripId(locked.getId())
                    .actualArrivalAt(request.actualArrivalAt())
                    .odometerEnd(request.odometerEnd())
                    .fuelCost(request.fuelCost())
                    .driverPay(driverPay)
                    .registeredBy(registeredBy)
                    .build());

            locked.setStatus(TripStatus.COMPLETED);

            outboxService.record(EventTypes.AGGREGATE_TRIP, locked.getId(), EventTypes.TRIP_COMPLETED,
                    TripCompletedPayload.from(locked, departure, saved));
            return saved;
        });

        return TripArrivalResponse.fromEntity(arrival, departure);
    }

    private BigDecimal calculateDriverPay(BigDecimal baseSalaryPerTrip, TripType type) {
        BigDecimal pay = type == TripType.PRIVATE
                ? baseSalaryPerTrip.multiply(PRIVATE_TRIP_SURCHARGE)
                : baseSalaryPerTrip;
        return pay.setScale(2, RoundingMode.HALF_UP);
    }

    private void assertAssignedDriver(FleetDriverResponse driver, UUID userId) {
        if (!userId.equals(driver.userId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.TRIP_NOT_ASSIGNED_TO_DRIVER,
                    "Este viaje no está asignado a ti");
        }
    }

    private void assertInTransit(TripEntity trip) {
        if (trip.getStatus() != TripStatus.IN_TRANSIT) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.TRIP_NOT_IN_TRANSIT,
                    "Solo se puede registrar la llegada de un viaje en tránsito");
        }
    }

    private TripDepartureResponse registerDeparture(TripEntity trip, RegisterDepartureRequest request,
                                                    UUID registeredBy) {
        assertNotStarted(trip);

        Instant actualDeparture = request.actualDepartureAt();
        validateDepartureTime(trip, actualDeparture);

        FleetBusResponse bus = fleetClient.getBus(trip.getBusId());
        if (request.odometerStart().compareTo(bus.odometerKm()) < 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCodes.ODOMETER_BELOW_BUS,
                    "El kilometraje inicial (" + request.odometerStart() + ") es menor al registrado para el bus "
                            + bus.plate() + " (" + bus.odometerKm() + ")");
        }

        TripDepartureEntity departure = transactionTemplate.execute(status -> {
            TripEntity locked = getLockedOrThrow(trip.getId());
            assertNotStarted(locked);
            if (!locked.getBusId().equals(trip.getBusId()) || !locked.getDriverId().equals(trip.getDriverId())) {
                throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.TRIP_MODIFIED,
                        "El viaje fue modificado mientras se registraba la salida, intenta de nuevo");
            }

            TripDepartureEntity saved = tripDepartureRepository.save(TripDepartureEntity.builder()
                    .tripId(locked.getId())
                    .actualDepartureAt(actualDeparture)
                    .busId(locked.getBusId())
                    .driverId(locked.getDriverId())
                    .odometerStart(request.odometerStart())
                    .registeredBy(registeredBy)
                    .build());

            locked.setStatus(TripStatus.IN_TRANSIT);

            outboxService.record(EventTypes.AGGREGATE_TRIP, locked.getId(), EventTypes.TRIP_STARTED,
                    TripStartedPayload.from(locked, saved));
            return saved;
        });

        return TripDepartureResponse.fromEntity(departure);
    }

    private void assertNotStarted(TripEntity trip) {
        if (trip.getStatus() != TripStatus.SCHEDULED) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.TRIP_ALREADY_STARTED,
                    "El viaje ya inició o terminó");
        }
    }

    private void validateDepartureTime(TripEntity trip, Instant actualDeparture) {
        Instant earliest = trip.getScheduledDeparture().minus(tripProperties.departureEarlyMargin());
        if (actualDeparture.isBefore(earliest)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCodes.DEPARTURE_TOO_EARLY,
                    "La salida se puede registrar a partir del "
                            + DISPLAY_FORMAT.format(LocalDateTime.ofInstant(earliest, zone)));
        }
    }

    private TripEntity getOrThrow(UUID id) {
        return tripRepository.findById(id)
                .filter(found -> found.getStatus() != TripStatus.DELETED)
                .orElseThrow(this::tripNotFound);
    }

    private TripEntity getLockedOrThrow(UUID id) {
        return tripRepository.findByIdForUpdate(id)
                .filter(found -> found.getStatus() != TripStatus.DELETED)
                .orElseThrow(this::tripNotFound);
    }

    private TripEntity getOwnedOrThrow(UUID id, UUID branchId) {
        TripEntity trip = getOrThrow(id);
        assertBelongsTo(trip, branchId);
        return trip;
    }

    private TripEntity getLockedOwnedOrThrow(UUID id, UUID branchId) {
        TripEntity trip = getLockedOrThrow(id);
        assertBelongsTo(trip, branchId);
        return trip;
    }

    private void assertBelongsTo(TripEntity trip, UUID branchId) {
        if (!trip.getOriginBranchId().equals(branchId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.TRIP_NOT_IN_BRANCH,
                    "El viaje no pertenece a tu sucursal");
        }
    }

    private ApiException tripNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.TRIP_NOT_FOUND, "Viaje no encontrado");
    }

    private void assertEditable(TripEntity trip, TripType requestedType) {
        if (requestedType != null && requestedType != trip.getType()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCodes.TRIP_TYPE_IMMUTABLE,
                    "El tipo de viaje no se puede cambiar");
        }
        if (trip.getStatus() != TripStatus.SCHEDULED) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCodes.TRIP_ALREADY_STARTED,
                    "Solo se pueden modificar viajes programados; este viaje ya inició o terminó");
        }
        if (trip.getType() == TripType.PRIVATE) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCodes.PRIVATE_TRIP_NOT_EDITABLE,
                    "Los viajes privados se gestionan desde el alquiler");
        }
    }

    private void assertBusChangeAllowed(UUID tripId, UUID currentBusId, FleetBusResponse newBus, String accessToken) {
        TicketingSoldSummaryResponse sold = ticketingClient.getSoldSummary(tripId, accessToken);
        if (sold.activeTickets() == 0) {
            return;
        }
        FleetBusResponse currentBus = fleetClient.getBus(currentBusId);
        if (!Objects.equals(currentBus.seatColumns(), newBus.seatColumns())
                || aisleOf(currentBus) != aisleOf(newBus)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCodes.LAYOUT_INCOMPATIBLE,
                    "El viaje tiene " + sold.activeTickets() + " boletos vendidos: el bus " + newBus.plate()
                            + " debe tener " + currentBus.seatColumns() + " columnas y el pasillo después de la columna "
                            + aisleOf(currentBus) + ", igual que el bus actual");
        }
        if (sold.maxSeatNumber() != null && newBus.capacity() < sold.maxSeatNumber()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCodes.CAPACITY_BELOW_SOLD,
                    "El bus " + newBus.plate() + " tiene " + newBus.capacity() + " asientos y el asiento "
                            + sold.maxSeatNumber() + " ya está vendido");
        }
    }

    private int aisleOf(FleetBusResponse bus) {
        return bus.aisleAfterColumn() == null ? 0 : bus.aisleAfterColumn();
    }

    private void validateSchedule(Instant departure, Instant arrival, Instant operationAt) {
        if (departure.isBefore(operationAt)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.DEPARTURE_IN_PAST,
                    "La fecha de salida no puede ser anterior a la fecha de operación");
        }
        if (!arrival.isAfter(departure)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.ARRIVAL_BEFORE_DEPARTURE,
                    "La llegada estimada debe ser posterior a la salida");
        }
    }

    private void validateBus(FleetBusResponse bus, UUID branchId) {
        if (!bus.branchId().equals(branchId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.BUS_NOT_IN_BRANCH,
                    "El bus no pertenece a tu sucursal");
        }
        if (BUS_INACTIVE.equals(bus.status())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCodes.BUS_INACTIVE,
                    "El bus está inactivo");
        }
        if (BUS_IN_WORKSHOP.equals(bus.status())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCodes.BUS_IN_WORKSHOP,
                    "El bus está en el taller");
        }
    }

    private void validateDriver(FleetDriverResponse driver, UUID branchId, Instant arrival) {
        if (!driver.branchId().equals(branchId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.DRIVER_NOT_IN_BRANCH,
                    "El chofer no pertenece a tu sucursal");
        }
        if (!driver.active()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCodes.DRIVER_INACTIVE,
                    "El chofer está inactivo");
        }
        LocalDate tripEndDate = LocalDate.ofInstant(arrival, zone);
        if (driver.licenseExpiresOn().isBefore(tripEndDate)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCodes.LICENSE_EXPIRES_BEFORE_TRIP,
                    "La licencia del chofer vence el " + driver.licenseExpiresOn() + ", antes de que termine el viaje");
        }
    }

    private void lockResources(UUID busId, UUID driverId) {
        tripRepository.lockResource(BUS_LOCK, busId);
        tripRepository.lockResource(DRIVER_LOCK, driverId);
    }

    private void assertNoOverlap(UUID excludedTripId, UUID busId, UUID driverId, Instant departure, Instant arrival) {
        boolean busBusy = excludedTripId == null
                ? tripRepository.existsByBusIdAndStatusInAndScheduledDepartureBeforeAndEstimatedArrivalAfter(
                        busId, ACTIVE_STATUSES, arrival, departure)
                : tripRepository.existsByIdNotAndBusIdAndStatusInAndScheduledDepartureBeforeAndEstimatedArrivalAfter(
                        excludedTripId, busId, ACTIVE_STATUSES, arrival, departure);
        if (busBusy) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.RESOURCE_OVERLAP,
                    "El bus ya tiene un viaje programado o en tránsito en ese horario");
        }
        boolean driverBusy = excludedTripId == null
                ? tripRepository.existsByDriverIdAndStatusInAndScheduledDepartureBeforeAndEstimatedArrivalAfter(
                        driverId, ACTIVE_STATUSES, arrival, departure)
                : tripRepository.existsByIdNotAndDriverIdAndStatusInAndScheduledDepartureBeforeAndEstimatedArrivalAfter(
                        excludedTripId, driverId, ACTIVE_STATUSES, arrival, departure);
        if (driverBusy) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.RESOURCE_OVERLAP,
                    "El chofer ya tiene un viaje programado o en tránsito en ese horario");
        }
    }
}
