package com.urbantransport.trip.repository;

import com.urbantransport.trip.domain.TripArrivalEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TripArrivalRepository extends JpaRepository<TripArrivalEntity, UUID> {

    Optional<TripArrivalEntity> findByTripId(UUID tripId);
}
