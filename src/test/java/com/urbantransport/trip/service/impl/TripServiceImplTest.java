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
import com.urbantransport.trip.service.OutboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TripServiceImplTest {

    private static final ZoneId ZONE = ZoneId.of("America/Guatemala");
    private static final LocalDate TODAY = LocalDate.now(ZONE);
    private static final Instant OPERATION_AT = TODAY.atTime(7, 0).atZone(ZONE).toInstant();
    private static final Instant DEPARTURE = TODAY.plusDays(1).atTime(8, 0).atZone(ZONE).toInstant();
    private static final Instant ARRIVAL = DEPARTURE.plus(Duration.ofHours(3));
    private static final Instant NEW_DEPARTURE = DEPARTURE.plus(Duration.ofHours(1));
    private static final Instant NEW_ARRIVAL = ARRIVAL.plus(Duration.ofHours(1));
    private static final LocalDate LICENSE_EXPIRES_ON = TODAY.plusDays(90);
    private static final List<TripStatus> ACTIVE_STATUSES = List.of(TripStatus.SCHEDULED, TripStatus.IN_TRANSIT);

    private static final UUID TRIP_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID BRANCH_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID OTHER_BRANCH_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID DESTINATION_BRANCH_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID ROUTE_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID BUS_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final UUID NEW_BUS_ID = UUID.fromString("77777777-7777-7777-7777-777777777777");
    private static final UUID DRIVER_ID = UUID.fromString("88888888-8888-8888-8888-888888888888");
    private static final UUID DRIVER_USER_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");
    private static final UUID USER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID RENTAL_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID EVENT_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID DEPARTURE_ID = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
    private static final UUID ARRIVAL_ID = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");
    private static final String ACCESS_TOKEN = "token-del-usuario";

    private static final BigDecimal TICKET_PRICE = new BigDecimal("45.00");
    private static final BigDecimal DISTANCE_KM = new BigDecimal("120.50");
    private static final BigDecimal BUS_ODOMETER = new BigDecimal("1000.00");
    private static final BigDecimal SALARY = new BigDecimal("150.00");

    @Mock
    private TripRepository tripRepository;

    @Mock
    private TripDepartureRepository tripDepartureRepository;

    @Mock
    private TripArrivalRepository tripArrivalRepository;

    @Mock
    private OutboxService outboxService;

    @Mock
    private FleetClient fleetClient;

    @Mock
    private TicketingClient ticketingClient;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Captor
    private ArgumentCaptor<TripEntity> tripCaptor;

    @Captor
    private ArgumentCaptor<TripDepartureEntity> departureCaptor;

    @Captor
    private ArgumentCaptor<TripArrivalEntity> arrivalCaptor;

    @Captor
    private ArgumentCaptor<ProcessedEventEntity> processedCaptor;

    @Captor
    private ArgumentCaptor<Object> payloadCaptor;

    @Captor
    private ArgumentCaptor<Pageable> pageableCaptor;

    private TripServiceImpl tripService;

    @BeforeEach
    void setUp() {
        tripService = new TripServiceImpl(tripRepository, tripDepartureRepository, tripArrivalRepository,
                outboxService, fleetClient, ticketingClient, transactionTemplate,
                new TripProperties(Duration.ofMinutes(30)), processedEventRepository);
        ReflectionTestUtils.setField(tripService, "zone", ZONE);
    }

    @Nested
    class CreateRegular {

        @Test
        void validRequest_savesTripWithRouteSnapshotAndRecordsEvent() {
            stubFleetForCreate(bus());
            runTransactionsInline();
            assignIdOnSave();

            TripResponse response = tripService.createRegular(createRequest(DEPARTURE, ARRIVAL), BRANCH_ID, USER_ID);

            InOrder inOrder = inOrder(tripRepository);
            inOrder.verify(tripRepository).lockResource(1, BUS_ID);
            inOrder.verify(tripRepository).lockResource(2, DRIVER_ID);
            inOrder.verify(tripRepository).save(tripCaptor.capture());
            TripEntity saved = tripCaptor.getValue();
            assertEquals(TripType.REGULAR, saved.getType());
            assertEquals(ROUTE_ID, saved.getRouteId());
            assertEquals(BRANCH_ID, saved.getOriginBranchId());
            assertEquals(DESTINATION_BRANCH_ID, saved.getDestinationBranchId());
            assertEquals(BUS_ID, saved.getBusId());
            assertEquals(DRIVER_ID, saved.getDriverId());
            assertEquals(DEPARTURE, saved.getScheduledDeparture());
            assertEquals(ARRIVAL, saved.getEstimatedArrival());
            assertEquals(TICKET_PRICE, saved.getTicketPrice());
            assertEquals(DISTANCE_KM, saved.getDistanceKm());
            assertEquals(TripStatus.SCHEDULED, saved.getStatus());
            assertEquals(USER_ID, saved.getCreatedBy());
            assertNull(saved.getRentalId());
            verify(tripRepository).existsByBusIdAndStatusInAndScheduledDepartureBeforeAndEstimatedArrivalAfter(
                    BUS_ID, ACTIVE_STATUSES, ARRIVAL, DEPARTURE);
            verify(tripRepository).existsByDriverIdAndStatusInAndScheduledDepartureBeforeAndEstimatedArrivalAfter(
                    DRIVER_ID, ACTIVE_STATUSES, ARRIVAL, DEPARTURE);
            verify(outboxService).record(EventTypes.AGGREGATE_TRIP, TRIP_ID, EventTypes.TRIP_SCHEDULED,
                    TripEventPayload.from(saved, route(BRANCH_ID), bus(), ZONE));
            assertEquals(TRIP_ID, response.getId());
            assertEquals(TICKET_PRICE, response.getTicketPrice());
            assertEquals(DISTANCE_KM, response.getDistanceKm());
        }

        @ParameterizedTest
        @ValueSource(strings = {"DISPONIBLE", "EN_TRANSITO"})
        void busNotInactiveNorInWorkshop_isScheduled(String status) {
            stubFleetForCreate(bus(BUS_ID, BRANCH_ID, status));
            runTransactionsInline();
            assignIdOnSave();

            tripService.createRegular(createRequest(DEPARTURE, ARRIVAL), BRANCH_ID, USER_ID);

            verify(tripRepository).save(any(TripEntity.class));
        }

        @Test
        void departureEqualToOperationDate_isAllowed() {
            stubFleetForCreate(bus());
            runTransactionsInline();
            assignIdOnSave();

            tripService.createRegular(new CreateTripRequest(ROUTE_ID, BUS_ID, DRIVER_ID, DEPARTURE, ARRIVAL,
                    DEPARTURE), BRANCH_ID, USER_ID);

            verify(tripRepository).save(any(TripEntity.class));
        }

        @Test
        void licenseExpiringOnArrivalDate_isAllowed() {
            when(fleetClient.getRoute(ROUTE_ID)).thenReturn(route(BRANCH_ID));
            when(fleetClient.getBus(BUS_ID)).thenReturn(bus());
            when(fleetClient.getDriver(DRIVER_ID)).thenReturn(driver(BRANCH_ID, true, LocalDate.ofInstant(ARRIVAL, ZONE)));
            runTransactionsInline();
            assignIdOnSave();

            tripService.createRegular(createRequest(DEPARTURE, ARRIVAL), BRANCH_ID, USER_ID);

            verify(tripRepository).save(any(TripEntity.class));
        }

        @Test
        void departureBeforeOperationDate_throwsWithoutCallingFleet() {
            CreateTripRequest request = createRequest(OPERATION_AT.minusSeconds(60), ARRIVAL);

            assertApiError(HttpStatus.BAD_REQUEST, ErrorCodes.DEPARTURE_IN_PAST,
                    () -> tripService.createRegular(request, BRANCH_ID, USER_ID));

            verifyNoInteractions(fleetClient, transactionTemplate, tripRepository, outboxService);
        }

        @ParameterizedTest
        @ValueSource(longs = {0, -60})
        void arrivalNotAfterDeparture_throwsWithoutCallingFleet(long offsetMinutes) {
            CreateTripRequest request = createRequest(DEPARTURE, DEPARTURE.plus(Duration.ofMinutes(offsetMinutes)));

            assertApiError(HttpStatus.BAD_REQUEST, ErrorCodes.ARRIVAL_BEFORE_DEPARTURE,
                    () -> tripService.createRegular(request, BRANCH_ID, USER_ID));

            verifyNoInteractions(fleetClient, transactionTemplate, tripRepository, outboxService);
        }

        @Test
        void routeFromOtherBranch_throwsForbiddenWithoutCheckingBus() {
            when(fleetClient.getRoute(ROUTE_ID)).thenReturn(route(OTHER_BRANCH_ID));
            CreateTripRequest request = createRequest(DEPARTURE, ARRIVAL);

            assertApiError(HttpStatus.FORBIDDEN, ErrorCodes.ROUTE_NOT_IN_BRANCH,
                    () -> tripService.createRegular(request, BRANCH_ID, USER_ID));

            verify(fleetClient, never()).getBus(any());
            verifyNoInteractions(transactionTemplate, tripRepository, outboxService);
        }

        @ParameterizedTest
        @MethodSource("com.urbantransport.trip.service.impl.TripServiceImplTest#invalidBuses")
        void invalidBus_throwsWithoutCheckingDriver(FleetBusResponse bus, HttpStatus status, String code) {
            when(fleetClient.getRoute(ROUTE_ID)).thenReturn(route(BRANCH_ID));
            when(fleetClient.getBus(BUS_ID)).thenReturn(bus);
            CreateTripRequest request = createRequest(DEPARTURE, ARRIVAL);

            assertApiError(status, code, () -> tripService.createRegular(request, BRANCH_ID, USER_ID));

            verify(fleetClient, never()).getDriver(any());
            verifyNoInteractions(transactionTemplate, tripRepository, outboxService);
        }

        @ParameterizedTest
        @MethodSource("com.urbantransport.trip.service.impl.TripServiceImplTest#invalidDrivers")
        void invalidDriver_throwsWithoutSaving(FleetDriverResponse driver, HttpStatus status, String code) {
            when(fleetClient.getRoute(ROUTE_ID)).thenReturn(route(BRANCH_ID));
            when(fleetClient.getBus(BUS_ID)).thenReturn(bus());
            when(fleetClient.getDriver(DRIVER_ID)).thenReturn(driver);
            CreateTripRequest request = createRequest(DEPARTURE, ARRIVAL);

            assertApiError(status, code, () -> tripService.createRegular(request, BRANCH_ID, USER_ID));

            verifyNoInteractions(transactionTemplate, tripRepository, outboxService);
        }

        @Test
        void busWithOverlappingTrip_throwsConflictWithoutSaving() {
            stubFleetForCreate(bus());
            runTransactionsInline();
            when(tripRepository.existsByBusIdAndStatusInAndScheduledDepartureBeforeAndEstimatedArrivalAfter(
                    BUS_ID, ACTIVE_STATUSES, ARRIVAL, DEPARTURE)).thenReturn(true);
            CreateTripRequest request = createRequest(DEPARTURE, ARRIVAL);

            assertApiError(HttpStatus.CONFLICT, ErrorCodes.RESOURCE_OVERLAP,
                    () -> tripService.createRegular(request, BRANCH_ID, USER_ID));

            verify(tripRepository, never()).existsByDriverIdAndStatusInAndScheduledDepartureBeforeAndEstimatedArrivalAfter(
                    any(), any(), any(), any());
            verify(tripRepository, never()).save(any(TripEntity.class));
            verifyNoInteractions(outboxService);
        }

        @Test
        void driverWithOverlappingTrip_throwsConflictWithoutSaving() {
            stubFleetForCreate(bus());
            runTransactionsInline();
            when(tripRepository.existsByDriverIdAndStatusInAndScheduledDepartureBeforeAndEstimatedArrivalAfter(
                    DRIVER_ID, ACTIVE_STATUSES, ARRIVAL, DEPARTURE)).thenReturn(true);
            CreateTripRequest request = createRequest(DEPARTURE, ARRIVAL);

            assertApiError(HttpStatus.CONFLICT, ErrorCodes.RESOURCE_OVERLAP,
                    () -> tripService.createRegular(request, BRANCH_ID, USER_ID));

            verify(tripRepository, never()).save(any(TripEntity.class));
            verifyNoInteractions(outboxService);
        }

        @Test
        void fleetUnavailable_propagatesWithoutOpeningTransaction() {
            ApiException failure = fleetUnavailable();
            when(fleetClient.getRoute(ROUTE_ID)).thenThrow(failure);
            CreateTripRequest request = createRequest(DEPARTURE, ARRIVAL);

            ApiException thrown = assertThrows(ApiException.class,
                    () -> tripService.createRegular(request, BRANCH_ID, USER_ID));

            assertSame(failure, thrown);
            verifyNoInteractions(transactionTemplate, tripRepository, outboxService);
        }
    }

    @Nested
    class CreatePrivateFromRental {

        @Test
        void roundTrip_createsOnePrivateTripPerLegAndMarksEvent() {
            when(fleetClient.getBus(BUS_ID)).thenReturn(bus());
            when(fleetClient.getDriver(DRIVER_ID)).thenReturn(driver());
            runVoidTransactionsInline();
            assignIdOnSave();
            List<RentalAssignedPayload.Leg> legs = roundTrip();

            tripService.createPrivateFromRental(EVENT_ID, EventTypes.RENTAL_ASSIGNED, rental(30, legs));

            verify(tripRepository).lockResource(1, BUS_ID);
            verify(tripRepository).lockResource(2, DRIVER_ID);
            verify(tripRepository, times(2)).save(tripCaptor.capture());
            List<TripEntity> trips = tripCaptor.getAllValues();
            assertEquals(List.of("Guatemala", "Antigua"), trips.stream().map(TripEntity::getOriginText).toList());
            assertEquals(List.of("Antigua", "Guatemala"), trips.stream().map(TripEntity::getDestinationText).toList());
            assertEquals(legs.stream().map(RentalAssignedPayload.Leg::departureAt).toList(),
                    trips.stream().map(TripEntity::getScheduledDeparture).toList());
            assertEquals(legs.stream().map(RentalAssignedPayload.Leg::estimatedArrival).toList(),
                    trips.stream().map(TripEntity::getEstimatedArrival).toList());
            for (TripEntity trip : trips) {
                assertEquals(TripType.PRIVATE, trip.getType());
                assertNull(trip.getRouteId());
                assertNull(trip.getTicketPrice());
                assertNull(trip.getDestinationBranchId());
                assertEquals(BRANCH_ID, trip.getOriginBranchId());
                assertEquals(BUS_ID, trip.getBusId());
                assertEquals(DRIVER_ID, trip.getDriverId());
                assertEquals(DISTANCE_KM, trip.getDistanceKm());
                assertEquals(TripStatus.SCHEDULED, trip.getStatus());
                assertEquals(RENTAL_ID, trip.getRentalId());
                assertEquals(USER_ID, trip.getCreatedBy());
                verify(outboxService).record(EventTypes.AGGREGATE_TRIP, TRIP_ID, EventTypes.TRIP_SCHEDULED,
                        TripEventPayload.from(trip, null, bus(), ZONE));
            }
            for (RentalAssignedPayload.Leg leg : legs) {
                verify(tripRepository).existsByBusIdAndStatusInAndScheduledDepartureBeforeAndEstimatedArrivalAfter(
                        BUS_ID, ACTIVE_STATUSES, leg.estimatedArrival(), leg.departureAt());
                verify(tripRepository).existsByDriverIdAndStatusInAndScheduledDepartureBeforeAndEstimatedArrivalAfter(
                        DRIVER_ID, ACTIVE_STATUSES, leg.estimatedArrival(), leg.departureAt());
            }
            verify(outboxService, never()).record(any(), any(), eq(EventTypes.PRIVATE_TRIP_REJECTED), any());
            assertMarkedProcessed(EventTypes.RENTAL_ASSIGNED);
        }

        @ParameterizedTest
        @NullSource
        @ValueSource(ints = 40)
        void passengersWithinCapacity_createsTrip(Integer passengers) {
            when(fleetClient.getBus(BUS_ID)).thenReturn(bus());
            when(fleetClient.getDriver(DRIVER_ID)).thenReturn(driver());
            runVoidTransactionsInline();
            assignIdOnSave();

            tripService.createPrivateFromRental(EVENT_ID, EventTypes.RENTAL_ASSIGNED, rental(passengers, oneWay()));

            verify(tripRepository).save(any(TripEntity.class));
            assertMarkedProcessed(EventTypes.RENTAL_ASSIGNED);
        }

        @Test
        void busWithoutAisle_publishesLayoutWithAisleZero() {
            FleetBusResponse bus = new FleetBusResponse(BUS_ID, "P-001", 40, (short) 10, (short) 4, null,
                    "DISPONIBLE", BUS_ODOMETER, BRANCH_ID);
            when(fleetClient.getBus(BUS_ID)).thenReturn(bus);
            when(fleetClient.getDriver(DRIVER_ID)).thenReturn(driver());
            runVoidTransactionsInline();
            assignIdOnSave();

            tripService.createPrivateFromRental(EVENT_ID, EventTypes.RENTAL_ASSIGNED, rental(30, oneWay()));

            verify(outboxService).record(eq(EventTypes.AGGREGATE_TRIP), eq(TRIP_ID), eq(EventTypes.TRIP_SCHEDULED),
                    payloadCaptor.capture());
            TripEventPayload payload = (TripEventPayload) payloadCaptor.getValue();
            assertEquals((short) 0, payload.getSeatLayout().getAisleAfterColumn());
            assertEquals((short) 4, payload.getSeatLayout().getColumns());
            assertNull(payload.getOriginName());
            assertNull(payload.getDestinationName());
            assertEquals("Guatemala", payload.getOriginText());
            assertEquals(TODAY.plusDays(1).atTime(8, 0), payload.getDepartureAt());
        }

        @Test
        void busOverCapacity_publishesRejectionWithoutCheckingDriver() {
            when(fleetClient.getBus(BUS_ID)).thenReturn(bus());
            runVoidTransactionsInline();

            tripService.createPrivateFromRental(EVENT_ID, EventTypes.RENTAL_ASSIGNED, rental(41, oneWay()));

            assertRejected(ErrorCodes.BUS_CAPACITY_EXCEEDED);
            verify(fleetClient, never()).getDriver(any());
        }

        @ParameterizedTest
        @MethodSource("com.urbantransport.trip.service.impl.TripServiceImplTest#invalidBuses")
        void invalidBus_publishesRejection(FleetBusResponse bus, HttpStatus status, String code) {
            when(fleetClient.getBus(BUS_ID)).thenReturn(bus);
            runVoidTransactionsInline();

            tripService.createPrivateFromRental(EVENT_ID, EventTypes.RENTAL_ASSIGNED, rental(30, oneWay()));

            assertRejected(code);
        }

        @ParameterizedTest
        @MethodSource("com.urbantransport.trip.service.impl.TripServiceImplTest#invalidDrivers")
        void invalidDriver_publishesRejection(FleetDriverResponse driver, HttpStatus status, String code) {
            when(fleetClient.getBus(BUS_ID)).thenReturn(bus());
            when(fleetClient.getDriver(DRIVER_ID)).thenReturn(driver);
            runVoidTransactionsInline();

            tripService.createPrivateFromRental(EVENT_ID, EventTypes.RENTAL_ASSIGNED, rental(30, oneWay()));

            assertRejected(code);
        }

        @Test
        void licenseExpiringBeforeLastLeg_publishesRejection() {
            when(fleetClient.getBus(BUS_ID)).thenReturn(bus());
            when(fleetClient.getDriver(DRIVER_ID)).thenReturn(driver(BRANCH_ID, true,
                    LocalDate.ofInstant(ARRIVAL, ZONE)));
            runVoidTransactionsInline();

            tripService.createPrivateFromRental(EVENT_ID, EventTypes.RENTAL_ASSIGNED, rental(30, roundTrip()));

            assertRejected(ErrorCodes.LICENSE_EXPIRES_BEFORE_TRIP);
        }

        @Test
        void unknownDriver_publishesRejection() {
            when(fleetClient.getBus(BUS_ID)).thenReturn(bus());
            when(fleetClient.getDriver(DRIVER_ID)).thenThrow(
                    new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.DRIVER_NOT_FOUND, "Chofer no encontrado"));
            runVoidTransactionsInline();

            tripService.createPrivateFromRental(EVENT_ID, EventTypes.RENTAL_ASSIGNED, rental(30, oneWay()));

            assertRejected(ErrorCodes.DRIVER_NOT_FOUND);
        }

        @Test
        void overlappingLeg_publishesRejection() {
            when(fleetClient.getBus(BUS_ID)).thenReturn(bus());
            when(fleetClient.getDriver(DRIVER_ID)).thenReturn(driver());
            runVoidTransactionsInline();
            when(tripRepository.existsByDriverIdAndStatusInAndScheduledDepartureBeforeAndEstimatedArrivalAfter(
                    DRIVER_ID, ACTIVE_STATUSES, ARRIVAL, DEPARTURE)).thenReturn(true);

            tripService.createPrivateFromRental(EVENT_ID, EventTypes.RENTAL_ASSIGNED, rental(30, oneWay()));

            assertRejected(ErrorCodes.RESOURCE_OVERLAP);
        }

        @Test
        void fleetUnavailable_rethrowsWithoutRejectingOrMarking() {
            ApiException failure = fleetUnavailable();
            when(fleetClient.getBus(BUS_ID)).thenThrow(failure);
            RentalAssignedPayload payload = rental(30, oneWay());

            ApiException thrown = assertThrows(ApiException.class,
                    () -> tripService.createPrivateFromRental(EVENT_ID, EventTypes.RENTAL_ASSIGNED, payload));

            assertSame(failure, thrown);
            verifyNoInteractions(transactionTemplate, tripRepository, outboxService);
            verify(processedEventRepository, never()).save(any());
        }

        @Test
        void alreadyProcessedEvent_isIgnored() {
            when(processedEventRepository.existsById(EVENT_ID)).thenReturn(true);

            tripService.createPrivateFromRental(EVENT_ID, EventTypes.RENTAL_ASSIGNED, rental(30, oneWay()));

            verifyNoInteractions(fleetClient, transactionTemplate, tripRepository, outboxService);
            verify(processedEventRepository, never()).save(any());
        }

        @Test
        void eventProcessedWhileValidating_createsNothing() {
            when(processedEventRepository.existsById(EVENT_ID)).thenReturn(false, true);
            when(fleetClient.getBus(BUS_ID)).thenReturn(bus());
            when(fleetClient.getDriver(DRIVER_ID)).thenReturn(driver());
            runVoidTransactionsInline();

            tripService.createPrivateFromRental(EVENT_ID, EventTypes.RENTAL_ASSIGNED, rental(30, oneWay()));

            verifyNoInteractions(tripRepository, outboxService);
            verify(processedEventRepository, never()).save(any());
        }

        @Test
        void eventProcessedBeforeRejection_recordsNothing() {
            when(processedEventRepository.existsById(EVENT_ID)).thenReturn(false, true);
            when(fleetClient.getBus(BUS_ID)).thenReturn(bus());
            runVoidTransactionsInline();

            tripService.createPrivateFromRental(EVENT_ID, EventTypes.RENTAL_ASSIGNED, rental(41, oneWay()));

            verifyNoInteractions(tripRepository, outboxService);
            verify(processedEventRepository, never()).save(any());
        }

        @ParameterizedTest
        @MethodSource("com.urbantransport.trip.service.impl.TripServiceImplTest#incompleteRentals")
        void incompletePayload_throwsSoItGoesToRetryAndDlt(RentalAssignedPayload payload) {
            assertThrows(IllegalArgumentException.class,
                    () -> tripService.createPrivateFromRental(EVENT_ID, EventTypes.RENTAL_ASSIGNED, payload));

            verifyNoInteractions(fleetClient, transactionTemplate, tripRepository, outboxService);
            verify(processedEventRepository, never()).save(any());
        }

        private void assertRejected(String code) {
            verify(outboxService).record(eq(EventTypes.AGGREGATE_TRIP), eq(RENTAL_ID),
                    eq(EventTypes.PRIVATE_TRIP_REJECTED), payloadCaptor.capture());
            PrivateTripRejectedPayload payload = (PrivateTripRejectedPayload) payloadCaptor.getValue();
            assertEquals(RENTAL_ID, payload.getRentalId());
            assertEquals(BUS_ID, payload.getBusId());
            assertEquals(DRIVER_ID, payload.getDriverId());
            assertEquals(code, payload.getCode());
            assertNotNull(payload.getReason());
            verify(tripRepository, never()).save(any(TripEntity.class));
            verify(outboxService, never()).record(any(), any(), eq(EventTypes.TRIP_SCHEDULED), any());
            assertMarkedProcessed(EventTypes.RENTAL_ASSIGNED);
        }
    }

    @Nested
    class Update {

        @ParameterizedTest
        @NullSource
        @EnumSource(value = TripType.class, names = "REGULAR")
        void sameBus_updatesLockedTripWithoutCallingTicketing(TripType requestedType) {
            TripEntity current = trip(TripType.REGULAR, TripStatus.SCHEDULED);
            TripEntity locked = trip(TripType.REGULAR, TripStatus.SCHEDULED);
            when(tripRepository.findById(TRIP_ID)).thenReturn(Optional.of(current));
            stubFleetForUpdate(bus());
            runTransactionsInline();
            when(tripRepository.findByIdForUpdate(TRIP_ID)).thenReturn(Optional.of(locked));

            TripResponse response = tripService.update(TRIP_ID, updateRequest(requestedType, BUS_ID), BRANCH_ID,
                    ACCESS_TOKEN);

            assertEquals(NEW_DEPARTURE, locked.getScheduledDeparture());
            assertEquals(NEW_ARRIVAL, locked.getEstimatedArrival());
            assertEquals(BUS_ID, locked.getBusId());
            assertEquals(DRIVER_ID, locked.getDriverId());
            assertEquals(DEPARTURE, current.getScheduledDeparture());
            verify(tripRepository).lockResource(1, BUS_ID);
            verify(tripRepository).lockResource(2, DRIVER_ID);
            verify(tripRepository).existsByIdNotAndBusIdAndStatusInAndScheduledDepartureBeforeAndEstimatedArrivalAfter(
                    TRIP_ID, BUS_ID, ACTIVE_STATUSES, NEW_ARRIVAL, NEW_DEPARTURE);
            verify(tripRepository).existsByIdNotAndDriverIdAndStatusInAndScheduledDepartureBeforeAndEstimatedArrivalAfter(
                    TRIP_ID, DRIVER_ID, ACTIVE_STATUSES, NEW_ARRIVAL, NEW_DEPARTURE);
            verify(tripRepository, never()).existsByBusIdAndStatusInAndScheduledDepartureBeforeAndEstimatedArrivalAfter(
                    any(), any(), any(), any());
            verify(outboxService).record(EventTypes.AGGREGATE_TRIP, TRIP_ID, EventTypes.TRIP_UPDATED,
                    TripEventPayload.from(locked, route(BRANCH_ID), bus(), ZONE));
            verifyNoInteractions(ticketingClient);
            assertEquals(NEW_DEPARTURE, response.getScheduledDeparture());
            assertEquals(TICKET_PRICE, response.getTicketPrice());
        }

        @Test
        void typeChange_throwsWithoutCallingFleet() {
            when(tripRepository.findById(TRIP_ID)).thenReturn(Optional.of(trip(TripType.REGULAR, TripStatus.SCHEDULED)));
            UpdateTripRequest request = updateRequest(TripType.PRIVATE, BUS_ID);

            assertApiError(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCodes.TRIP_TYPE_IMMUTABLE,
                    () -> tripService.update(TRIP_ID, request, BRANCH_ID, ACCESS_TOKEN));

            verifyNoInteractions(fleetClient, transactionTemplate, outboxService);
        }

        @ParameterizedTest
        @EnumSource(value = TripStatus.class, names = {"IN_TRANSIT", "COMPLETED"})
        void startedTrip_throwsWithoutCallingFleet(TripStatus status) {
            when(tripRepository.findById(TRIP_ID)).thenReturn(Optional.of(trip(TripType.REGULAR, status)));
            UpdateTripRequest request = updateRequest(null, BUS_ID);

            assertApiError(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCodes.TRIP_ALREADY_STARTED,
                    () -> tripService.update(TRIP_ID, request, BRANCH_ID, ACCESS_TOKEN));

            verifyNoInteractions(fleetClient, transactionTemplate, outboxService);
        }

        @ParameterizedTest
        @NullSource
        @EnumSource(value = TripType.class, names = "PRIVATE")
        void privateTrip_throwsNotEditable(TripType requestedType) {
            when(tripRepository.findById(TRIP_ID)).thenReturn(Optional.of(trip(TripType.PRIVATE, TripStatus.SCHEDULED)));
            UpdateTripRequest request = updateRequest(requestedType, BUS_ID);

            assertApiError(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCodes.PRIVATE_TRIP_NOT_EDITABLE,
                    () -> tripService.update(TRIP_ID, request, BRANCH_ID, ACCESS_TOKEN));

            verifyNoInteractions(fleetClient, transactionTemplate, outboxService);
        }

        @ParameterizedTest
        @MethodSource("com.urbantransport.trip.service.impl.TripServiceImplTest#missingTrips")
        void missingOrDeletedTrip_throwsNotFound(Optional<TripEntity> found) {
            when(tripRepository.findById(TRIP_ID)).thenReturn(found);
            UpdateTripRequest request = updateRequest(null, BUS_ID);

            assertApiError(HttpStatus.NOT_FOUND, ErrorCodes.TRIP_NOT_FOUND,
                    () -> tripService.update(TRIP_ID, request, BRANCH_ID, ACCESS_TOKEN));

            verifyNoInteractions(fleetClient, transactionTemplate, outboxService);
        }

        @Test
        void tripFromOtherBranch_throwsForbidden() {
            when(tripRepository.findById(TRIP_ID)).thenReturn(Optional.of(trip(TripType.REGULAR, TripStatus.SCHEDULED)));
            UpdateTripRequest request = updateRequest(null, BUS_ID);

            assertApiError(HttpStatus.FORBIDDEN, ErrorCodes.TRIP_NOT_IN_BRANCH,
                    () -> tripService.update(TRIP_ID, request, OTHER_BRANCH_ID, ACCESS_TOKEN));

            verifyNoInteractions(fleetClient, transactionTemplate, outboxService);
        }

        @Test
        void departureBeforeOperationDate_throwsWithoutCallingFleet() {
            when(tripRepository.findById(TRIP_ID)).thenReturn(Optional.of(trip(TripType.REGULAR, TripStatus.SCHEDULED)));
            UpdateTripRequest request = new UpdateTripRequest(null, BUS_ID, DRIVER_ID, OPERATION_AT.minusSeconds(1),
                    ARRIVAL, OPERATION_AT);

            assertApiError(HttpStatus.BAD_REQUEST, ErrorCodes.DEPARTURE_IN_PAST,
                    () -> tripService.update(TRIP_ID, request, BRANCH_ID, ACCESS_TOKEN));

            verifyNoInteractions(fleetClient, transactionTemplate, outboxService);
        }

        @Test
        void newBusFromOtherBranch_throwsWithoutCallingTicketing() {
            when(tripRepository.findById(TRIP_ID)).thenReturn(Optional.of(trip(TripType.REGULAR, TripStatus.SCHEDULED)));
            when(fleetClient.getRoute(ROUTE_ID)).thenReturn(route(BRANCH_ID));
            when(fleetClient.getBus(NEW_BUS_ID)).thenReturn(bus(NEW_BUS_ID, OTHER_BRANCH_ID, "DISPONIBLE"));
            UpdateTripRequest request = updateRequest(null, NEW_BUS_ID);

            assertApiError(HttpStatus.FORBIDDEN, ErrorCodes.BUS_NOT_IN_BRANCH,
                    () -> tripService.update(TRIP_ID, request, BRANCH_ID, ACCESS_TOKEN));

            verifyNoInteractions(ticketingClient, transactionTemplate, outboxService);
        }

        @Test
        void busChangeWithoutSoldTickets_skipsLayoutCheck() {
            TripEntity locked = trip(TripType.REGULAR, TripStatus.SCHEDULED);
            FleetBusResponse newBus = layoutBus(NEW_BUS_ID, 20, (short) 2, null);
            when(tripRepository.findById(TRIP_ID)).thenReturn(Optional.of(trip(TripType.REGULAR, TripStatus.SCHEDULED)));
            stubFleetForUpdate(newBus);
            when(ticketingClient.getSoldSummary(TRIP_ID, ACCESS_TOKEN)).thenReturn(sold(0, null));
            runTransactionsInline();
            when(tripRepository.findByIdForUpdate(TRIP_ID)).thenReturn(Optional.of(locked));

            tripService.update(TRIP_ID, updateRequest(null, NEW_BUS_ID), BRANCH_ID, ACCESS_TOKEN);

            assertEquals(NEW_BUS_ID, locked.getBusId());
            verify(fleetClient, never()).getBus(BUS_ID);
            verify(tripRepository).lockResource(1, NEW_BUS_ID);
            verify(outboxService).record(EventTypes.AGGREGATE_TRIP, TRIP_ID, EventTypes.TRIP_UPDATED,
                    TripEventPayload.from(locked, route(BRANCH_ID), newBus, ZONE));
        }

        @ParameterizedTest
        @MethodSource("com.urbantransport.trip.service.impl.TripServiceImplTest#compatibleBusChanges")
        void busChangeCompatibleWithSoldTickets_isApplied(FleetBusResponse currentBus, FleetBusResponse newBus,
                                                         Integer maxSeatNumber) {
            TripEntity locked = trip(TripType.REGULAR, TripStatus.SCHEDULED);
            when(tripRepository.findById(TRIP_ID)).thenReturn(Optional.of(trip(TripType.REGULAR, TripStatus.SCHEDULED)));
            stubFleetForUpdate(newBus);
            when(ticketingClient.getSoldSummary(TRIP_ID, ACCESS_TOKEN)).thenReturn(sold(5, maxSeatNumber));
            when(fleetClient.getBus(BUS_ID)).thenReturn(currentBus);
            runTransactionsInline();
            when(tripRepository.findByIdForUpdate(TRIP_ID)).thenReturn(Optional.of(locked));

            tripService.update(TRIP_ID, updateRequest(null, NEW_BUS_ID), BRANCH_ID, ACCESS_TOKEN);

            assertEquals(NEW_BUS_ID, locked.getBusId());
            verify(outboxService).record(eq(EventTypes.AGGREGATE_TRIP), eq(TRIP_ID), eq(EventTypes.TRIP_UPDATED), any());
        }

        @ParameterizedTest
        @MethodSource("com.urbantransport.trip.service.impl.TripServiceImplTest#incompatibleBusChanges")
        void busChangeIncompatibleWithSoldTickets_throwsWithoutSaving(FleetBusResponse currentBus,
                                                                     FleetBusResponse newBus, Integer maxSeatNumber,
                                                                     String code) {
            when(tripRepository.findById(TRIP_ID)).thenReturn(Optional.of(trip(TripType.REGULAR, TripStatus.SCHEDULED)));
            stubFleetForUpdate(newBus);
            when(ticketingClient.getSoldSummary(TRIP_ID, ACCESS_TOKEN)).thenReturn(sold(5, maxSeatNumber));
            when(fleetClient.getBus(BUS_ID)).thenReturn(currentBus);
            UpdateTripRequest request = updateRequest(null, NEW_BUS_ID);

            assertApiError(HttpStatus.UNPROCESSABLE_CONTENT, code,
                    () -> tripService.update(TRIP_ID, request, BRANCH_ID, ACCESS_TOKEN));

            verifyNoInteractions(transactionTemplate, outboxService);
        }

        @Test
        void ticketingUnavailable_propagatesWithoutSaving() {
            ApiException failure = new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.TICKETING_UNAVAILABLE,
                    "Ticketing no disponible");
            when(tripRepository.findById(TRIP_ID)).thenReturn(Optional.of(trip(TripType.REGULAR, TripStatus.SCHEDULED)));
            stubFleetForUpdate(bus(NEW_BUS_ID, BRANCH_ID, "DISPONIBLE"));
            when(ticketingClient.getSoldSummary(TRIP_ID, ACCESS_TOKEN)).thenThrow(failure);
            UpdateTripRequest request = updateRequest(null, NEW_BUS_ID);

            ApiException thrown = assertThrows(ApiException.class,
                    () -> tripService.update(TRIP_ID, request, BRANCH_ID, ACCESS_TOKEN));

            assertSame(failure, thrown);
            verifyNoInteractions(transactionTemplate, outboxService);
        }

        @Test
        void tripStartedWhileValidating_throwsWithoutRecordingEvent() {
            TripEntity locked = trip(TripType.REGULAR, TripStatus.IN_TRANSIT);
            when(tripRepository.findById(TRIP_ID)).thenReturn(Optional.of(trip(TripType.REGULAR, TripStatus.SCHEDULED)));
            stubFleetForUpdate(bus());
            runTransactionsInline();
            when(tripRepository.findByIdForUpdate(TRIP_ID)).thenReturn(Optional.of(locked));
            UpdateTripRequest request = updateRequest(null, BUS_ID);

            assertApiError(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCodes.TRIP_ALREADY_STARTED,
                    () -> tripService.update(TRIP_ID, request, BRANCH_ID, ACCESS_TOKEN));

            assertEquals(DEPARTURE, locked.getScheduledDeparture());
            verifyNoInteractions(outboxService);
        }

        @Test
        void overlapWithOtherTrip_throwsConflictWithoutChanges() {
            TripEntity locked = trip(TripType.REGULAR, TripStatus.SCHEDULED);
            when(tripRepository.findById(TRIP_ID)).thenReturn(Optional.of(trip(TripType.REGULAR, TripStatus.SCHEDULED)));
            stubFleetForUpdate(bus());
            runTransactionsInline();
            when(tripRepository.findByIdForUpdate(TRIP_ID)).thenReturn(Optional.of(locked));
            when(tripRepository.existsByIdNotAndDriverIdAndStatusInAndScheduledDepartureBeforeAndEstimatedArrivalAfter(
                    TRIP_ID, DRIVER_ID, ACTIVE_STATUSES, NEW_ARRIVAL, NEW_DEPARTURE)).thenReturn(true);
            UpdateTripRequest request = updateRequest(null, BUS_ID);

            assertApiError(HttpStatus.CONFLICT, ErrorCodes.RESOURCE_OVERLAP,
                    () -> tripService.update(TRIP_ID, request, BRANCH_ID, ACCESS_TOKEN));

            assertEquals(DEPARTURE, locked.getScheduledDeparture());
            verifyNoInteractions(outboxService);
        }
    }

    @Nested
    class Delete {

        @Test
        void scheduledRegularTrip_isMarkedDeletedAndRecordsEvent() {
            TripEntity trip = trip(TripType.REGULAR, TripStatus.SCHEDULED);
            when(tripRepository.findByIdForUpdate(TRIP_ID)).thenReturn(Optional.of(trip));

            tripService.delete(TRIP_ID, BRANCH_ID);

            assertEquals(TripStatus.DELETED, trip.getStatus());
            verify(outboxService).record(EventTypes.AGGREGATE_TRIP, TRIP_ID, EventTypes.TRIP_DELETED,
                    TripDeletedPayload.fromEntity(trip));
            verify(tripRepository, never()).delete(any(TripEntity.class));
            verify(tripRepository, never()).deleteById(any());
        }

        @ParameterizedTest
        @EnumSource(value = TripStatus.class, names = {"IN_TRANSIT", "COMPLETED"})
        void startedTrip_throwsWithoutChanges(TripStatus status) {
            TripEntity trip = trip(TripType.REGULAR, status);
            when(tripRepository.findByIdForUpdate(TRIP_ID)).thenReturn(Optional.of(trip));

            assertApiError(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCodes.TRIP_ALREADY_STARTED,
                    () -> tripService.delete(TRIP_ID, BRANCH_ID));

            assertEquals(status, trip.getStatus());
            verifyNoInteractions(outboxService);
        }

        @Test
        void privateTrip_throwsNotEditable() {
            TripEntity trip = trip(TripType.PRIVATE, TripStatus.SCHEDULED);
            when(tripRepository.findByIdForUpdate(TRIP_ID)).thenReturn(Optional.of(trip));

            assertApiError(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCodes.PRIVATE_TRIP_NOT_EDITABLE,
                    () -> tripService.delete(TRIP_ID, BRANCH_ID));

            assertEquals(TripStatus.SCHEDULED, trip.getStatus());
            verifyNoInteractions(outboxService);
        }

        @Test
        void tripFromOtherBranch_throwsForbidden() {
            when(tripRepository.findByIdForUpdate(TRIP_ID)).thenReturn(Optional.of(trip(TripType.REGULAR,
                    TripStatus.SCHEDULED)));

            assertApiError(HttpStatus.FORBIDDEN, ErrorCodes.TRIP_NOT_IN_BRANCH,
                    () -> tripService.delete(TRIP_ID, OTHER_BRANCH_ID));

            verifyNoInteractions(outboxService);
        }

        @ParameterizedTest
        @MethodSource("com.urbantransport.trip.service.impl.TripServiceImplTest#missingTrips")
        void missingOrDeletedTrip_throwsNotFound(Optional<TripEntity> found) {
            when(tripRepository.findByIdForUpdate(TRIP_ID)).thenReturn(found);

            assertApiError(HttpStatus.NOT_FOUND, ErrorCodes.TRIP_NOT_FOUND, () -> tripService.delete(TRIP_ID, BRANCH_ID));

            verifyNoInteractions(outboxService);
        }
    }

    @Nested
    class RegisterDepartureAsAdmin {

        @ParameterizedTest
        @ValueSource(longs = {-30, 0, 120})
        void departureFromThirtyMinutesBefore_isRegisteredAndRecordsEvent(long offsetMinutes) {
            TripEntity locked = trip(TripType.REGULAR, TripStatus.SCHEDULED);
            Instant actualDeparture = DEPARTURE.plus(Duration.ofMinutes(offsetMinutes));
            when(tripRepository.findById(TRIP_ID)).thenReturn(Optional.of(trip(TripType.REGULAR, TripStatus.SCHEDULED)));
            when(fleetClient.getBus(BUS_ID)).thenReturn(bus());
            runTransactionsInline();
            when(tripRepository.findByIdForUpdate(TRIP_ID)).thenReturn(Optional.of(locked));
            assignIdOnDepartureSave();

            TripDepartureResponse response = tripService.registerDepartureAsAdmin(TRIP_ID,
                    new RegisterDepartureRequest(actualDeparture, BUS_ODOMETER), BRANCH_ID, USER_ID);

            verify(tripDepartureRepository).save(departureCaptor.capture());
            TripDepartureEntity saved = departureCaptor.getValue();
            assertEquals(TRIP_ID, saved.getTripId());
            assertEquals(actualDeparture, saved.getActualDepartureAt());
            assertEquals(BUS_ID, saved.getBusId());
            assertEquals(DRIVER_ID, saved.getDriverId());
            assertEquals(BUS_ODOMETER, saved.getOdometerStart());
            assertEquals(USER_ID, saved.getRegisteredBy());
            assertEquals(TripStatus.IN_TRANSIT, locked.getStatus());
            verify(outboxService).record(EventTypes.AGGREGATE_TRIP, TRIP_ID, EventTypes.TRIP_STARTED,
                    TripStartedPayload.from(locked, saved));
            assertEquals(DEPARTURE_ID, response.getId());
            assertEquals(BUS_ODOMETER, response.getOdometerStart());
        }

        @Test
        void departureTooEarly_throwsWithoutCallingFleet() {
            when(tripRepository.findById(TRIP_ID)).thenReturn(Optional.of(trip(TripType.REGULAR, TripStatus.SCHEDULED)));
            RegisterDepartureRequest request = new RegisterDepartureRequest(DEPARTURE.minus(Duration.ofMinutes(31)),
                    BUS_ODOMETER);

            assertApiError(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCodes.DEPARTURE_TOO_EARLY,
                    () -> tripService.registerDepartureAsAdmin(TRIP_ID, request, BRANCH_ID, USER_ID));

            verifyNoInteractions(fleetClient, transactionTemplate, tripDepartureRepository, outboxService);
        }

        @Test
        void odometerBelowBus_throwsWithoutSaving() {
            when(tripRepository.findById(TRIP_ID)).thenReturn(Optional.of(trip(TripType.REGULAR, TripStatus.SCHEDULED)));
            when(fleetClient.getBus(BUS_ID)).thenReturn(bus());
            RegisterDepartureRequest request = new RegisterDepartureRequest(DEPARTURE, new BigDecimal("999.99"));

            assertApiError(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCodes.ODOMETER_BELOW_BUS,
                    () -> tripService.registerDepartureAsAdmin(TRIP_ID, request, BRANCH_ID, USER_ID));

            verifyNoInteractions(transactionTemplate, tripDepartureRepository, outboxService);
        }

        @ParameterizedTest
        @EnumSource(value = TripStatus.class, names = {"IN_TRANSIT", "COMPLETED"})
        void startedTrip_throwsConflictWithoutCallingFleet(TripStatus status) {
            when(tripRepository.findById(TRIP_ID)).thenReturn(Optional.of(trip(TripType.REGULAR, status)));
            RegisterDepartureRequest request = new RegisterDepartureRequest(DEPARTURE, BUS_ODOMETER);

            assertApiError(HttpStatus.CONFLICT, ErrorCodes.TRIP_ALREADY_STARTED,
                    () -> tripService.registerDepartureAsAdmin(TRIP_ID, request, BRANCH_ID, USER_ID));

            verifyNoInteractions(fleetClient, transactionTemplate, tripDepartureRepository, outboxService);
        }

        @Test
        void tripFromOtherBranch_throwsForbidden() {
            when(tripRepository.findById(TRIP_ID)).thenReturn(Optional.of(trip(TripType.REGULAR, TripStatus.SCHEDULED)));
            RegisterDepartureRequest request = new RegisterDepartureRequest(DEPARTURE, BUS_ODOMETER);

            assertApiError(HttpStatus.FORBIDDEN, ErrorCodes.TRIP_NOT_IN_BRANCH,
                    () -> tripService.registerDepartureAsAdmin(TRIP_ID, request, OTHER_BRANCH_ID, USER_ID));

            verifyNoInteractions(fleetClient, transactionTemplate, tripDepartureRepository, outboxService);
        }

        @ParameterizedTest
        @CsvSource({"77777777-7777-7777-7777-777777777777, 88888888-8888-8888-8888-888888888888",
                "66666666-6666-6666-6666-666666666666, 12345678-1234-1234-1234-123456789012"})
        void tripModifiedWhileValidating_throwsConflictWithoutSaving(UUID lockedBusId, UUID lockedDriverId) {
            TripEntity locked = trip(TripType.REGULAR, TripStatus.SCHEDULED);
            locked.setBusId(lockedBusId);
            locked.setDriverId(lockedDriverId);
            when(tripRepository.findById(TRIP_ID)).thenReturn(Optional.of(trip(TripType.REGULAR, TripStatus.SCHEDULED)));
            when(fleetClient.getBus(BUS_ID)).thenReturn(bus());
            runTransactionsInline();
            when(tripRepository.findByIdForUpdate(TRIP_ID)).thenReturn(Optional.of(locked));
            RegisterDepartureRequest request = new RegisterDepartureRequest(DEPARTURE, BUS_ODOMETER);

            assertApiError(HttpStatus.CONFLICT, ErrorCodes.TRIP_MODIFIED,
                    () -> tripService.registerDepartureAsAdmin(TRIP_ID, request, BRANCH_ID, USER_ID));

            assertEquals(TripStatus.SCHEDULED, locked.getStatus());
            verifyNoInteractions(tripDepartureRepository, outboxService);
        }

        @Test
        void tripStartedWhileValidating_throwsConflictWithoutSaving() {
            when(tripRepository.findById(TRIP_ID)).thenReturn(Optional.of(trip(TripType.REGULAR, TripStatus.SCHEDULED)));
            when(fleetClient.getBus(BUS_ID)).thenReturn(bus());
            runTransactionsInline();
            when(tripRepository.findByIdForUpdate(TRIP_ID)).thenReturn(Optional.of(trip(TripType.REGULAR,
                    TripStatus.IN_TRANSIT)));
            RegisterDepartureRequest request = new RegisterDepartureRequest(DEPARTURE, BUS_ODOMETER);

            assertApiError(HttpStatus.CONFLICT, ErrorCodes.TRIP_ALREADY_STARTED,
                    () -> tripService.registerDepartureAsAdmin(TRIP_ID, request, BRANCH_ID, USER_ID));

            verifyNoInteractions(tripDepartureRepository, outboxService);
        }

        @Test
        void tripDeletedWhileValidating_throwsNotFound() {
            when(tripRepository.findById(TRIP_ID)).thenReturn(Optional.of(trip(TripType.REGULAR, TripStatus.SCHEDULED)));
            when(fleetClient.getBus(BUS_ID)).thenReturn(bus());
            runTransactionsInline();
            when(tripRepository.findByIdForUpdate(TRIP_ID)).thenReturn(Optional.of(trip(TripType.REGULAR,
                    TripStatus.DELETED)));
            RegisterDepartureRequest request = new RegisterDepartureRequest(DEPARTURE, BUS_ODOMETER);

            assertApiError(HttpStatus.NOT_FOUND, ErrorCodes.TRIP_NOT_FOUND,
                    () -> tripService.registerDepartureAsAdmin(TRIP_ID, request, BRANCH_ID, USER_ID));

            verifyNoInteractions(tripDepartureRepository, outboxService);
        }
    }

    @Nested
    class RegisterDepartureAsDriver {

        @Test
        void assignedDriver_registersDepartureAsTheUser() {
            TripEntity trip = trip(TripType.PRIVATE, TripStatus.SCHEDULED);
            trip.setOriginBranchId(OTHER_BRANCH_ID);
            TripEntity locked = trip(TripType.PRIVATE, TripStatus.SCHEDULED);
            when(tripRepository.findById(TRIP_ID)).thenReturn(Optional.of(trip));
            when(fleetClient.getDriver(DRIVER_ID)).thenReturn(driver());
            when(fleetClient.getBus(BUS_ID)).thenReturn(bus());
            runTransactionsInline();
            when(tripRepository.findByIdForUpdate(TRIP_ID)).thenReturn(Optional.of(locked));
            assignIdOnDepartureSave();

            tripService.registerDepartureAsDriver(TRIP_ID, new RegisterDepartureRequest(DEPARTURE, BUS_ODOMETER),
                    DRIVER_USER_ID);

            verify(tripDepartureRepository).save(departureCaptor.capture());
            assertEquals(DRIVER_USER_ID, departureCaptor.getValue().getRegisteredBy());
            assertEquals(TripStatus.IN_TRANSIT, locked.getStatus());
            verify(outboxService).record(eq(EventTypes.AGGREGATE_TRIP), eq(TRIP_ID), eq(EventTypes.TRIP_STARTED), any());
        }

        @ParameterizedTest
        @NullSource
        @ValueSource(strings = "12345678-1234-1234-1234-123456789012")
        void otherDriver_throwsForbiddenWithoutCheckingBus(UUID driverUserId) {
            when(tripRepository.findById(TRIP_ID)).thenReturn(Optional.of(trip(TripType.REGULAR, TripStatus.SCHEDULED)));
            when(fleetClient.getDriver(DRIVER_ID)).thenReturn(new FleetDriverResponse(DRIVER_ID, driverUserId,
                    "Juan Perez", LICENSE_EXPIRES_ON, SALARY, BRANCH_ID, true));
            RegisterDepartureRequest request = new RegisterDepartureRequest(DEPARTURE, BUS_ODOMETER);

            assertApiError(HttpStatus.FORBIDDEN, ErrorCodes.TRIP_NOT_ASSIGNED_TO_DRIVER,
                    () -> tripService.registerDepartureAsDriver(TRIP_ID, request, DRIVER_USER_ID));

            verify(fleetClient, never()).getBus(any());
            verifyNoInteractions(transactionTemplate, tripDepartureRepository, outboxService);
        }

        @Test
        void missingTrip_throwsNotFoundWithoutCallingFleet() {
            when(tripRepository.findById(TRIP_ID)).thenReturn(Optional.empty());
            RegisterDepartureRequest request = new RegisterDepartureRequest(DEPARTURE, BUS_ODOMETER);

            assertApiError(HttpStatus.NOT_FOUND, ErrorCodes.TRIP_NOT_FOUND,
                    () -> tripService.registerDepartureAsDriver(TRIP_ID, request, DRIVER_USER_ID));

            verifyNoInteractions(fleetClient, transactionTemplate);
        }
    }

    @Nested
    class RegisterArrivalAsAdmin {

        @ParameterizedTest
        @CsvSource({"REGULAR, 150.00, 150.00", "PRIVATE, 150.00, 172.50", "PRIVATE, 133.33, 153.33",
                "REGULAR, 99.999, 100.00"})
        void tripInTransit_completesTripWithDriverPayAndRecordsEvent(TripType type, BigDecimal salary,
                                                                     BigDecimal expectedPay) {
            TripEntity locked = trip(type, TripStatus.IN_TRANSIT);
            TripDepartureEntity departure = departure(BUS_ODOMETER);
            Instant actualArrival = ARRIVAL.plus(Duration.ofMinutes(10));
            when(tripRepository.findById(TRIP_ID)).thenReturn(Optional.of(trip(type, TripStatus.IN_TRANSIT)));
            when(fleetClient.getDriver(DRIVER_ID)).thenReturn(new FleetDriverResponse(DRIVER_ID, DRIVER_USER_ID,
                    "Juan Perez", LICENSE_EXPIRES_ON, salary, BRANCH_ID, true));
            when(tripDepartureRepository.findByTripId(TRIP_ID)).thenReturn(Optional.of(departure));
            runTransactionsInline();
            when(tripRepository.findByIdForUpdate(TRIP_ID)).thenReturn(Optional.of(locked));
            assignIdOnArrivalSave();

            TripArrivalResponse response = tripService.registerArrivalAsAdmin(TRIP_ID,
                    new RegisterArrivalRequest(actualArrival, new BigDecimal("1120.50"), new BigDecimal("300.00")),
                    BRANCH_ID, USER_ID);

            verify(tripArrivalRepository).save(arrivalCaptor.capture());
            TripArrivalEntity saved = arrivalCaptor.getValue();
            assertEquals(TRIP_ID, saved.getTripId());
            assertEquals(actualArrival, saved.getActualArrivalAt());
            assertEquals(new BigDecimal("1120.50"), saved.getOdometerEnd());
            assertEquals(new BigDecimal("300.00"), saved.getFuelCost());
            assertEquals(expectedPay, saved.getDriverPay());
            assertEquals(USER_ID, saved.getRegisteredBy());
            assertEquals(TripStatus.COMPLETED, locked.getStatus());
            verify(outboxService).record(EventTypes.AGGREGATE_TRIP, TRIP_ID, EventTypes.TRIP_COMPLETED,
                    TripCompletedPayload.from(locked, departure, saved));
            assertEquals(ARRIVAL_ID, response.getId());
            assertEquals(new BigDecimal("120.50"), response.getKmTraveled());
            assertEquals(expectedPay, response.getDriverPay());
        }

        @Test
        void completedPayload_carriesOdometerKmAndRental() {
            TripEntity locked = trip(TripType.PRIVATE, TripStatus.IN_TRANSIT);
            when(tripRepository.findById(TRIP_ID)).thenReturn(Optional.of(trip(TripType.PRIVATE, TripStatus.IN_TRANSIT)));
            when(fleetClient.getDriver(DRIVER_ID)).thenReturn(driver());
            when(tripDepartureRepository.findByTripId(TRIP_ID)).thenReturn(Optional.of(departure(BUS_ODOMETER)));
            runTransactionsInline();
            when(tripRepository.findByIdForUpdate(TRIP_ID)).thenReturn(Optional.of(locked));
            assignIdOnArrivalSave();

            tripService.registerArrivalAsAdmin(TRIP_ID, new RegisterArrivalRequest(ARRIVAL, BUS_ODOMETER,
                    BigDecimal.ZERO), BRANCH_ID, USER_ID);

            verify(outboxService).record(eq(EventTypes.AGGREGATE_TRIP), eq(TRIP_ID), eq(EventTypes.TRIP_COMPLETED),
                    payloadCaptor.capture());
            TripCompletedPayload payload = (TripCompletedPayload) payloadCaptor.getValue();
            assertEquals(RENTAL_ID, payload.getRentalId());
            assertEquals(TripType.PRIVATE, payload.getType());
            assertEquals(BUS_ID, payload.getBusId());
            assertEquals(BUS_ODOMETER, payload.getOdometerStart());
            assertEquals(BUS_ODOMETER, payload.getOdometerEnd());
            assertEquals(0, BigDecimal.ZERO.compareTo(payload.getKmTraveled()));
            assertEquals(DEPARTURE, payload.getActualDepartureAt());
            assertEquals(ARRIVAL, payload.getActualArrivalAt());
            assertEquals(TripStatus.COMPLETED, payload.getStatus());
        }

        @ParameterizedTest
        @EnumSource(value = TripStatus.class, names = {"SCHEDULED", "COMPLETED"})
        void tripNotInTransit_throwsConflictWithoutCallingFleet(TripStatus status) {
            when(tripRepository.findById(TRIP_ID)).thenReturn(Optional.of(trip(TripType.REGULAR, status)));
            RegisterArrivalRequest request = arrivalRequest(ARRIVAL, new BigDecimal("1120.50"));

            assertApiError(HttpStatus.CONFLICT, ErrorCodes.TRIP_NOT_IN_TRANSIT,
                    () -> tripService.registerArrivalAsAdmin(TRIP_ID, request, BRANCH_ID, USER_ID));

            verifyNoInteractions(fleetClient, transactionTemplate, tripArrivalRepository, outboxService);
        }

        @Test
        void tripFromOtherBranch_throwsForbidden() {
            when(tripRepository.findById(TRIP_ID)).thenReturn(Optional.of(trip(TripType.REGULAR, TripStatus.IN_TRANSIT)));
            RegisterArrivalRequest request = arrivalRequest(ARRIVAL, new BigDecimal("1120.50"));

            assertApiError(HttpStatus.FORBIDDEN, ErrorCodes.TRIP_NOT_IN_BRANCH,
                    () -> tripService.registerArrivalAsAdmin(TRIP_ID, request, OTHER_BRANCH_ID, USER_ID));

            verifyNoInteractions(fleetClient, transactionTemplate, tripArrivalRepository, outboxService);
        }

        @ParameterizedTest
        @ValueSource(longs = {0, -1})
        void arrivalNotAfterDeparture_throwsWithoutSaving(long offsetMinutes) {
            stubArrivalPreconditions();
            RegisterArrivalRequest request = arrivalRequest(DEPARTURE.plus(Duration.ofMinutes(offsetMinutes)),
                    new BigDecimal("1120.50"));

            assertApiError(HttpStatus.BAD_REQUEST, ErrorCodes.ARRIVAL_BEFORE_DEPARTURE,
                    () -> tripService.registerArrivalAsAdmin(TRIP_ID, request, BRANCH_ID, USER_ID));

            verifyNoInteractions(transactionTemplate, tripArrivalRepository, outboxService);
        }

        @Test
        void odometerBelowStart_throwsWithoutSaving() {
            stubArrivalPreconditions();
            RegisterArrivalRequest request = arrivalRequest(ARRIVAL, new BigDecimal("999.99"));

            assertApiError(HttpStatus.BAD_REQUEST, ErrorCodes.ODOMETER_BELOW_START,
                    () -> tripService.registerArrivalAsAdmin(TRIP_ID, request, BRANCH_ID, USER_ID));

            verifyNoInteractions(transactionTemplate, tripArrivalRepository, outboxService);
        }

        @Test
        void tripInTransitWithoutDeparture_throwsIllegalState() {
            when(tripRepository.findById(TRIP_ID)).thenReturn(Optional.of(trip(TripType.REGULAR, TripStatus.IN_TRANSIT)));
            when(fleetClient.getDriver(DRIVER_ID)).thenReturn(driver());
            when(tripDepartureRepository.findByTripId(TRIP_ID)).thenReturn(Optional.empty());
            RegisterArrivalRequest request = arrivalRequest(ARRIVAL, new BigDecimal("1120.50"));

            assertThrows(IllegalStateException.class,
                    () -> tripService.registerArrivalAsAdmin(TRIP_ID, request, BRANCH_ID, USER_ID));

            verifyNoInteractions(transactionTemplate, tripArrivalRepository, outboxService);
        }

        @Test
        void tripCompletedWhileValidating_throwsConflictWithoutSaving() {
            stubArrivalPreconditions();
            runTransactionsInline();
            when(tripRepository.findByIdForUpdate(TRIP_ID)).thenReturn(Optional.of(trip(TripType.REGULAR,
                    TripStatus.COMPLETED)));
            RegisterArrivalRequest request = arrivalRequest(ARRIVAL, new BigDecimal("1120.50"));

            assertApiError(HttpStatus.CONFLICT, ErrorCodes.TRIP_NOT_IN_TRANSIT,
                    () -> tripService.registerArrivalAsAdmin(TRIP_ID, request, BRANCH_ID, USER_ID));

            verifyNoInteractions(tripArrivalRepository, outboxService);
        }

        private void stubArrivalPreconditions() {
            when(tripRepository.findById(TRIP_ID)).thenReturn(Optional.of(trip(TripType.REGULAR, TripStatus.IN_TRANSIT)));
            when(fleetClient.getDriver(DRIVER_ID)).thenReturn(driver());
            when(tripDepartureRepository.findByTripId(TRIP_ID)).thenReturn(Optional.of(departure(BUS_ODOMETER)));
        }
    }

    @Nested
    class RegisterArrivalAsDriver {

        @Test
        void assignedDriver_completesTripAsTheUser() {
            TripEntity locked = trip(TripType.REGULAR, TripStatus.IN_TRANSIT);
            when(tripRepository.findById(TRIP_ID)).thenReturn(Optional.of(trip(TripType.REGULAR, TripStatus.IN_TRANSIT)));
            when(fleetClient.getDriver(DRIVER_ID)).thenReturn(driver());
            when(tripDepartureRepository.findByTripId(TRIP_ID)).thenReturn(Optional.of(departure(BUS_ODOMETER)));
            runTransactionsInline();
            when(tripRepository.findByIdForUpdate(TRIP_ID)).thenReturn(Optional.of(locked));
            assignIdOnArrivalSave();

            tripService.registerArrivalAsDriver(TRIP_ID, arrivalRequest(ARRIVAL, new BigDecimal("1120.50")),
                    DRIVER_USER_ID);

            verify(tripArrivalRepository).save(arrivalCaptor.capture());
            assertEquals(DRIVER_USER_ID, arrivalCaptor.getValue().getRegisteredBy());
            assertEquals(SALARY, arrivalCaptor.getValue().getDriverPay());
            assertEquals(TripStatus.COMPLETED, locked.getStatus());
        }

        @Test
        void otherDriverOnScheduledTrip_throwsForbiddenBeforeStatusCheck() {
            when(tripRepository.findById(TRIP_ID)).thenReturn(Optional.of(trip(TripType.REGULAR, TripStatus.SCHEDULED)));
            when(fleetClient.getDriver(DRIVER_ID)).thenReturn(driver());
            RegisterArrivalRequest request = arrivalRequest(ARRIVAL, new BigDecimal("1120.50"));

            assertApiError(HttpStatus.FORBIDDEN, ErrorCodes.TRIP_NOT_ASSIGNED_TO_DRIVER,
                    () -> tripService.registerArrivalAsDriver(TRIP_ID, request, USER_ID));

            verifyNoInteractions(tripDepartureRepository, transactionTemplate, tripArrivalRepository, outboxService);
        }

        @Test
        void assignedDriverOnScheduledTrip_throwsConflict() {
            when(tripRepository.findById(TRIP_ID)).thenReturn(Optional.of(trip(TripType.REGULAR, TripStatus.SCHEDULED)));
            when(fleetClient.getDriver(DRIVER_ID)).thenReturn(driver());
            RegisterArrivalRequest request = arrivalRequest(ARRIVAL, new BigDecimal("1120.50"));

            assertApiError(HttpStatus.CONFLICT, ErrorCodes.TRIP_NOT_IN_TRANSIT,
                    () -> tripService.registerArrivalAsDriver(TRIP_ID, request, DRIVER_USER_ID));

            verifyNoInteractions(tripDepartureRepository, transactionTemplate, tripArrivalRepository, outboxService);
        }
    }

    @Nested
    class Search {

        @Test
        void outOfRangePaging_isClampedAndSortedBySoonestDepartureLast() {
            when(tripRepository.findAll(ArgumentMatchers.<Specification<TripEntity>>any(), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(trip(TripType.REGULAR, TripStatus.SCHEDULED)),
                            PageRequest.of(0, 100), 1));

            PageResponse<TripResponse> result = tripService.search(TripStatus.SCHEDULED, ROUTE_ID, BUS_ID, DRIVER_ID,
                    BRANCH_ID, TODAY, TODAY.plusDays(7), -1, 500);

            verify(tripRepository).findAll(ArgumentMatchers.<Specification<TripEntity>>any(), pageableCaptor.capture());
            Pageable pageable = pageableCaptor.getValue();
            assertEquals(0, pageable.getPageNumber());
            assertEquals(100, pageable.getPageSize());
            assertEquals(Sort.by(Sort.Direction.DESC, "scheduledDeparture"), pageable.getSort());
            assertEquals(1, result.getContent().size());
            assertEquals(TRIP_ID, result.getContent().getFirst().getId());
            assertEquals(1, result.getTotalElements());
        }

        @Test
        void zeroSizeAndSameDayRange_usesSizeOne() {
            when(tripRepository.findAll(ArgumentMatchers.<Specification<TripEntity>>any(), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(), PageRequest.of(2, 1), 0));

            tripService.search(null, null, null, null, null, TODAY, TODAY, 2, 0);

            verify(tripRepository).findAll(ArgumentMatchers.<Specification<TripEntity>>any(), pageableCaptor.capture());
            assertEquals(2, pageableCaptor.getValue().getPageNumber());
            assertEquals(1, pageableCaptor.getValue().getPageSize());
        }

        @Test
        void toBeforeFrom_throwsWithoutQuerying() {
            assertApiError(HttpStatus.BAD_REQUEST, ErrorCodes.INVALID_DATE_RANGE,
                    () -> tripService.search(null, null, null, null, null, TODAY, TODAY.minusDays(1), 0, 20));

            verifyNoInteractions(tripRepository);
        }
    }

    @Nested
    class SearchForDriver {

        @ParameterizedTest
        @NullSource
        @ValueSource(strings = "88888888-8888-8888-8888-888888888888")
        void ownOrUnspecifiedDriver_queriesTrips(UUID requestedDriverId) {
            when(fleetClient.getDriverByUserId(DRIVER_USER_ID)).thenReturn(driver());
            when(tripRepository.findAll(ArgumentMatchers.<Specification<TripEntity>>any(), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(trip(TripType.REGULAR, TripStatus.SCHEDULED)),
                            PageRequest.of(0, 20), 1));

            PageResponse<TripResponse> result = tripService.searchForDriver(DRIVER_USER_ID, requestedDriverId, null,
                    null, null, null, null, 0, 20);

            assertEquals(1, result.getContent().size());
            assertEquals(DRIVER_ID, result.getContent().getFirst().getDriverId());
        }

        @Test
        void otherDriver_throwsForbiddenWithoutQuerying() {
            when(fleetClient.getDriverByUserId(DRIVER_USER_ID)).thenReturn(driver());

            assertApiError(HttpStatus.FORBIDDEN, ErrorCodes.DRIVER_FORBIDDEN,
                    () -> tripService.searchForDriver(DRIVER_USER_ID, NEW_BUS_ID, null, null, null, null, null, 0, 20));

            verifyNoInteractions(tripRepository);
        }

        @Test
        void userWithoutDriver_propagatesNotFound() {
            ApiException failure = new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.DRIVER_NOT_FOUND,
                    "Tu usuario no esta registrado como chofer");
            when(fleetClient.getDriverByUserId(DRIVER_USER_ID)).thenThrow(failure);

            ApiException thrown = assertThrows(ApiException.class,
                    () -> tripService.searchForDriver(DRIVER_USER_ID, null, null, null, null, null, null, 0, 20));

            assertSame(failure, thrown);
            verifyNoInteractions(tripRepository);
        }
    }

    @Nested
    class FindById {

        @Test
        void withoutScope_returnsTripFromAnyBranchWithDepartureAndArrival() {
            TripEntity trip = trip(TripType.REGULAR, TripStatus.COMPLETED);
            trip.setOriginBranchId(OTHER_BRANCH_ID);
            when(tripRepository.findById(TRIP_ID)).thenReturn(Optional.of(trip));
            when(tripDepartureRepository.findByTripId(TRIP_ID)).thenReturn(Optional.of(departure(BUS_ODOMETER)));
            when(tripArrivalRepository.findByTripId(TRIP_ID)).thenReturn(Optional.of(arrival()));

            TripDetailResponse detail = tripService.findById(TRIP_ID, null);

            assertEquals(TRIP_ID, detail.getTrip().getId());
            assertEquals(DEPARTURE_ID, detail.getDeparture().getId());
            assertEquals(ARRIVAL_ID, detail.getArrival().getId());
            assertEquals(new BigDecimal("120.50"), detail.getArrival().getKmTraveled());
        }

        @Test
        void scheduledTrip_returnsDetailWithoutDepartureNorArrival() {
            when(tripRepository.findById(TRIP_ID)).thenReturn(Optional.of(trip(TripType.REGULAR, TripStatus.SCHEDULED)));
            when(tripDepartureRepository.findByTripId(TRIP_ID)).thenReturn(Optional.empty());
            when(tripArrivalRepository.findByTripId(TRIP_ID)).thenReturn(Optional.empty());

            TripDetailResponse detail = tripService.findById(TRIP_ID, BRANCH_ID);

            assertEquals(TripStatus.SCHEDULED, detail.getTrip().getStatus());
            assertNull(detail.getDeparture());
            assertNull(detail.getArrival());
        }

        @Test
        void tripFromOtherBranch_throwsForbidden() {
            when(tripRepository.findById(TRIP_ID)).thenReturn(Optional.of(trip(TripType.REGULAR, TripStatus.SCHEDULED)));

            assertApiError(HttpStatus.FORBIDDEN, ErrorCodes.TRIP_NOT_IN_BRANCH,
                    () -> tripService.findById(TRIP_ID, OTHER_BRANCH_ID));

            verifyNoInteractions(tripDepartureRepository, tripArrivalRepository);
        }

        @ParameterizedTest
        @MethodSource("com.urbantransport.trip.service.impl.TripServiceImplTest#missingTrips")
        void missingOrDeletedTrip_throwsNotFound(Optional<TripEntity> found) {
            when(tripRepository.findById(TRIP_ID)).thenReturn(found);

            assertApiError(HttpStatus.NOT_FOUND, ErrorCodes.TRIP_NOT_FOUND, () -> tripService.findById(TRIP_ID, null));

            verifyNoInteractions(tripDepartureRepository, tripArrivalRepository);
        }
    }

    @Nested
    class FindByIdForDriver {

        @Test
        void assignedDriver_returnsDetail() {
            when(tripRepository.findById(TRIP_ID)).thenReturn(Optional.of(trip(TripType.REGULAR, TripStatus.SCHEDULED)));
            when(fleetClient.getDriver(DRIVER_ID)).thenReturn(driver());
            when(tripDepartureRepository.findByTripId(TRIP_ID)).thenReturn(Optional.empty());
            when(tripArrivalRepository.findByTripId(TRIP_ID)).thenReturn(Optional.empty());

            TripDetailResponse detail = tripService.findByIdForDriver(TRIP_ID, DRIVER_USER_ID);

            assertEquals(TRIP_ID, detail.getTrip().getId());
        }

        @Test
        void otherDriver_throwsForbiddenWithoutLoadingDetail() {
            when(tripRepository.findById(TRIP_ID)).thenReturn(Optional.of(trip(TripType.REGULAR, TripStatus.SCHEDULED)));
            when(fleetClient.getDriver(DRIVER_ID)).thenReturn(driver());

            assertApiError(HttpStatus.FORBIDDEN, ErrorCodes.TRIP_NOT_ASSIGNED_TO_DRIVER,
                    () -> tripService.findByIdForDriver(TRIP_ID, USER_ID));

            verifyNoInteractions(tripDepartureRepository, tripArrivalRepository);
        }
    }

    @Nested
    class CountActive {

        @Test
        void byBus_countsScheduledAndInTransit() {
            when(tripRepository.countByBusIdAndStatusIn(BUS_ID, ACTIVE_STATUSES)).thenReturn(3L);

            assertEquals(3L, tripService.countActive(BUS_ID, null).getCount());

            verify(tripRepository, never()).countByDriverIdAndStatusIn(any(), any());
        }

        @Test
        void byDriver_countsScheduledAndInTransit() {
            when(tripRepository.countByDriverIdAndStatusIn(DRIVER_ID, ACTIVE_STATUSES)).thenReturn(2L);

            assertEquals(2L, tripService.countActive(null, DRIVER_ID).getCount());

            verify(tripRepository, never()).countByBusIdAndStatusIn(any(), any());
        }

        @ParameterizedTest
        @MethodSource("com.urbantransport.trip.service.impl.TripServiceImplTest#busAndDriverBothOrNone")
        void bothOrNoneFilters_throwsValidationError(UUID busId, UUID driverId) {
            assertApiError(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_ERROR,
                    () -> tripService.countActive(busId, driverId));

            verifyNoInteractions(tripRepository);
        }
    }

    @Nested
    class CountByRoute {

        @Test
        void route_countsTripsNotDeleted() {
            when(tripRepository.countByRouteIdAndStatusNot(ROUTE_ID, TripStatus.DELETED)).thenReturn(7L);

            assertEquals(7L, tripService.countByRoute(ROUTE_ID).getCount());
        }

        @Test
        void missingRoute_throwsValidationError() {
            assertApiError(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_ERROR, () -> tripService.countByRoute(null));

            verifyNoInteractions(tripRepository);
        }
    }

    static Stream<Arguments> invalidBuses() {
        return Stream.of(
                Arguments.of(bus(BUS_ID, OTHER_BRANCH_ID, "DISPONIBLE"), HttpStatus.FORBIDDEN,
                        ErrorCodes.BUS_NOT_IN_BRANCH),
                Arguments.of(bus(BUS_ID, BRANCH_ID, "INACTIVO"), HttpStatus.UNPROCESSABLE_CONTENT,
                        ErrorCodes.BUS_INACTIVE),
                Arguments.of(bus(BUS_ID, BRANCH_ID, "EN_TALLER"), HttpStatus.UNPROCESSABLE_CONTENT,
                        ErrorCodes.BUS_IN_WORKSHOP));
    }

    static Stream<Arguments> invalidDrivers() {
        return Stream.of(
                Arguments.of(driver(OTHER_BRANCH_ID, true, LICENSE_EXPIRES_ON), HttpStatus.FORBIDDEN,
                        ErrorCodes.DRIVER_NOT_IN_BRANCH),
                Arguments.of(driver(BRANCH_ID, false, LICENSE_EXPIRES_ON), HttpStatus.UNPROCESSABLE_CONTENT,
                        ErrorCodes.DRIVER_INACTIVE),
                Arguments.of(driver(BRANCH_ID, true, LocalDate.ofInstant(ARRIVAL, ZONE).minusDays(1)),
                        HttpStatus.UNPROCESSABLE_CONTENT, ErrorCodes.LICENSE_EXPIRES_BEFORE_TRIP));
    }

    static Stream<Arguments> compatibleBusChanges() {
        return Stream.of(
                Arguments.of(layoutBus(BUS_ID, 40, (short) 4, (short) 2), layoutBus(NEW_BUS_ID, 48, (short) 4, (short) 2), 40),
                Arguments.of(layoutBus(BUS_ID, 40, (short) 4, (short) 2), layoutBus(NEW_BUS_ID, 36, (short) 4, (short) 2), 36),
                Arguments.of(layoutBus(BUS_ID, 40, (short) 4, (short) 2), layoutBus(NEW_BUS_ID, 20, (short) 4, (short) 2), null),
                Arguments.of(layoutBus(BUS_ID, 40, (short) 4, null), layoutBus(NEW_BUS_ID, 40, (short) 4, (short) 0), 10));
    }

    static Stream<Arguments> incompatibleBusChanges() {
        return Stream.of(
                Arguments.of(layoutBus(BUS_ID, 40, (short) 4, (short) 2), layoutBus(NEW_BUS_ID, 50, (short) 5, (short) 2),
                        10, ErrorCodes.LAYOUT_INCOMPATIBLE),
                Arguments.of(layoutBus(BUS_ID, 40, (short) 4, (short) 2), layoutBus(NEW_BUS_ID, 40, (short) 4, (short) 3),
                        10, ErrorCodes.LAYOUT_INCOMPATIBLE),
                Arguments.of(layoutBus(BUS_ID, 40, (short) 4, (short) 2), layoutBus(NEW_BUS_ID, 40, (short) 4, null),
                        10, ErrorCodes.LAYOUT_INCOMPATIBLE),
                Arguments.of(layoutBus(BUS_ID, 40, (short) 4, (short) 2), layoutBus(NEW_BUS_ID, 32, (short) 4, (short) 2),
                        33, ErrorCodes.CAPACITY_BELOW_SOLD));
    }

    static Stream<Optional<TripEntity>> missingTrips() {
        return Stream.of(Optional.empty(), Optional.of(trip(TripType.REGULAR, TripStatus.DELETED)));
    }

    static Stream<RentalAssignedPayload> incompleteRentals() {
        return Stream.of(
                null,
                new RentalAssignedPayload(null, BRANCH_ID, BUS_ID, DRIVER_ID, 30, DISTANCE_KM, USER_ID, oneWay()),
                new RentalAssignedPayload(RENTAL_ID, BRANCH_ID, BUS_ID, DRIVER_ID, 30, DISTANCE_KM, USER_ID, null),
                new RentalAssignedPayload(RENTAL_ID, BRANCH_ID, BUS_ID, DRIVER_ID, 30, DISTANCE_KM, USER_ID, List.of()));
    }

    static Stream<Arguments> busAndDriverBothOrNone() {
        return Stream.of(Arguments.of(null, null), Arguments.of(BUS_ID, DRIVER_ID));
    }

    private static FleetRouteResponse route(UUID originBranchId) {
        return new FleetRouteResponse(ROUTE_ID, originBranchId, "Central", DESTINATION_BRANCH_ID, "Xela", DISTANCE_KM,
                TICKET_PRICE);
    }

    private static FleetBusResponse bus() {
        return bus(BUS_ID, BRANCH_ID, "DISPONIBLE");
    }

    private static FleetBusResponse bus(UUID id, UUID branchId, String status) {
        return new FleetBusResponse(id, "P-001", 40, (short) 10, (short) 4, (short) 2, status, BUS_ODOMETER, branchId);
    }

    private static FleetBusResponse layoutBus(UUID id, int capacity, Short columns, Short aisle) {
        return new FleetBusResponse(id, "P-002", capacity, (short) 10, columns, aisle, "DISPONIBLE", BUS_ODOMETER,
                BRANCH_ID);
    }

    private static FleetDriverResponse driver() {
        return driver(BRANCH_ID, true, LICENSE_EXPIRES_ON);
    }

    private static FleetDriverResponse driver(UUID branchId, boolean active, LocalDate licenseExpiresOn) {
        return new FleetDriverResponse(DRIVER_ID, DRIVER_USER_ID, "Juan Perez", licenseExpiresOn, SALARY, branchId,
                active);
    }

    private static TicketingSoldSummaryResponse sold(long activeTickets, Integer maxSeatNumber) {
        return new TicketingSoldSummaryResponse(TRIP_ID, activeTickets, maxSeatNumber);
    }

    private static TripEntity trip(TripType type, TripStatus status) {
        boolean regular = type == TripType.REGULAR;
        return TripEntity.builder()
                .id(TRIP_ID)
                .type(type)
                .routeId(regular ? ROUTE_ID : null)
                .originBranchId(BRANCH_ID)
                .destinationBranchId(regular ? DESTINATION_BRANCH_ID : null)
                .originText(regular ? null : "Guatemala")
                .destinationText(regular ? null : "Antigua")
                .busId(BUS_ID)
                .driverId(DRIVER_ID)
                .scheduledDeparture(DEPARTURE)
                .estimatedArrival(ARRIVAL)
                .ticketPrice(regular ? TICKET_PRICE : null)
                .distanceKm(DISTANCE_KM)
                .status(status)
                .rentalId(regular ? null : RENTAL_ID)
                .createdBy(USER_ID)
                .build();
    }

    private static TripDepartureEntity departure(BigDecimal odometerStart) {
        return TripDepartureEntity.builder()
                .id(DEPARTURE_ID)
                .tripId(TRIP_ID)
                .actualDepartureAt(DEPARTURE)
                .busId(BUS_ID)
                .driverId(DRIVER_ID)
                .odometerStart(odometerStart)
                .registeredBy(USER_ID)
                .build();
    }

    private static TripArrivalEntity arrival() {
        return TripArrivalEntity.builder()
                .id(ARRIVAL_ID)
                .tripId(TRIP_ID)
                .actualArrivalAt(ARRIVAL)
                .odometerEnd(new BigDecimal("1120.50"))
                .fuelCost(new BigDecimal("300.00"))
                .driverPay(SALARY)
                .registeredBy(USER_ID)
                .build();
    }

    private static CreateTripRequest createRequest(Instant departure, Instant arrival) {
        return new CreateTripRequest(ROUTE_ID, BUS_ID, DRIVER_ID, departure, arrival, OPERATION_AT);
    }

    private static UpdateTripRequest updateRequest(TripType type, UUID busId) {
        return new UpdateTripRequest(type, busId, DRIVER_ID, NEW_DEPARTURE, NEW_ARRIVAL, OPERATION_AT);
    }

    private static RegisterArrivalRequest arrivalRequest(Instant actualArrival, BigDecimal odometerEnd) {
        return new RegisterArrivalRequest(actualArrival, odometerEnd, new BigDecimal("300.00"));
    }

    private static List<RentalAssignedPayload.Leg> oneWay() {
        return List.of(new RentalAssignedPayload.Leg("Guatemala", "Antigua", DEPARTURE, ARRIVAL));
    }

    private static List<RentalAssignedPayload.Leg> roundTrip() {
        Instant returnDeparture = DEPARTURE.plus(Duration.ofDays(1));
        return List.of(
                new RentalAssignedPayload.Leg("Guatemala", "Antigua", DEPARTURE, ARRIVAL),
                new RentalAssignedPayload.Leg("Antigua", "Guatemala", returnDeparture,
                        returnDeparture.plus(Duration.ofHours(3))));
    }

    private static RentalAssignedPayload rental(Integer passengers, List<RentalAssignedPayload.Leg> legs) {
        return new RentalAssignedPayload(RENTAL_ID, BRANCH_ID, BUS_ID, DRIVER_ID, passengers, DISTANCE_KM, USER_ID,
                legs);
    }

    private static ApiException fleetUnavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.FLEET_UNAVAILABLE, "Fleet no disponible");
    }

    private void stubFleetForCreate(FleetBusResponse bus) {
        when(fleetClient.getRoute(ROUTE_ID)).thenReturn(route(BRANCH_ID));
        when(fleetClient.getBus(BUS_ID)).thenReturn(bus);
        when(fleetClient.getDriver(DRIVER_ID)).thenReturn(driver());
    }

    private void stubFleetForUpdate(FleetBusResponse newBus) {
        when(fleetClient.getRoute(ROUTE_ID)).thenReturn(route(BRANCH_ID));
        when(fleetClient.getBus(newBus.id())).thenReturn(newBus);
        when(fleetClient.getDriver(DRIVER_ID)).thenReturn(driver());
    }

    private void assignIdOnSave() {
        when(tripRepository.save(any(TripEntity.class))).thenAnswer(invocation -> {
            TripEntity trip = invocation.getArgument(0);
            trip.setId(TRIP_ID);
            return trip;
        });
    }

    private void assignIdOnDepartureSave() {
        when(tripDepartureRepository.save(any(TripDepartureEntity.class))).thenAnswer(invocation -> {
            TripDepartureEntity departure = invocation.getArgument(0);
            ReflectionTestUtils.setField(departure, "id", DEPARTURE_ID);
            return departure;
        });
    }

    private void assignIdOnArrivalSave() {
        when(tripArrivalRepository.save(any(TripArrivalEntity.class))).thenAnswer(invocation -> {
            TripArrivalEntity arrival = invocation.getArgument(0);
            ReflectionTestUtils.setField(arrival, "id", ARRIVAL_ID);
            return arrival;
        });
    }

    private void assertMarkedProcessed(String eventType) {
        verify(processedEventRepository).save(processedCaptor.capture());
        assertEquals(EVENT_ID, processedCaptor.getValue().getEventId());
        assertEquals(eventType, processedCaptor.getValue().getEventType());
    }

    private void runTransactionsInline() {
        when(transactionTemplate.execute(any())).thenAnswer(invocation ->
                invocation.<TransactionCallback<?>>getArgument(0).doInTransaction(null));
    }

    private void runVoidTransactionsInline() {
        doAnswer(invocation -> {
            invocation.<Consumer<TransactionStatus>>getArgument(0).accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    private static void assertApiError(HttpStatus status, String code, Executable action) {
        ApiException exception = assertThrows(ApiException.class, action);
        assertEquals(status, exception.getStatus());
        assertEquals(code, exception.getCode());
    }
}
