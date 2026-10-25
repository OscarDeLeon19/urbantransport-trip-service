package com.urbantransport.trip.repository;

import com.urbantransport.trip.domain.TripDepartureEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TripDepartureRepository extends JpaRepository<TripDepartureEntity, UUID> {

    Optional<TripDepartureEntity> findByTripId(UUID tripId);
}
