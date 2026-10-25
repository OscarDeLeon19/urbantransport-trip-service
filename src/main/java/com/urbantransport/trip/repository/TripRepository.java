package com.urbantransport.trip.repository;

import com.urbantransport.trip.domain.TripEntity;
import com.urbantransport.trip.domain.TripStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface TripRepository extends JpaRepository<TripEntity, UUID>, JpaSpecificationExecutor<TripEntity> {

    boolean existsByBusIdAndStatusInAndScheduledDepartureBeforeAndEstimatedArrivalAfter(
            UUID busId, Collection<TripStatus> statuses, Instant arrival, Instant departure);

    boolean existsByDriverIdAndStatusInAndScheduledDepartureBeforeAndEstimatedArrivalAfter(
            UUID driverId, Collection<TripStatus> statuses, Instant arrival, Instant departure);

    boolean existsByIdNotAndBusIdAndStatusInAndScheduledDepartureBeforeAndEstimatedArrivalAfter(
            UUID excludedTripId, UUID busId, Collection<TripStatus> statuses, Instant arrival, Instant departure);

    boolean existsByIdNotAndDriverIdAndStatusInAndScheduledDepartureBeforeAndEstimatedArrivalAfter(
            UUID excludedTripId, UUID driverId, Collection<TripStatus> statuses, Instant arrival, Instant departure);

    long countByBusIdAndStatusIn(UUID busId, Collection<TripStatus> statuses);

    long countByDriverIdAndStatusIn(UUID driverId, Collection<TripStatus> statuses);

    long countByRouteIdAndStatusNot(UUID routeId, TripStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TripEntity t where t.id = :id")
    Optional<TripEntity> findByIdForUpdate(@Param("id") UUID id);

    @Query(value = "select 1 from (select pg_advisory_xact_lock(:namespace, hashtext(cast(:resourceId as text)))) l",
            nativeQuery = true)
    Integer lockResource(@Param("namespace") int namespace, @Param("resourceId") UUID resourceId);
}
