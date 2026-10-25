package com.urbantransport.trip.repository;

import com.urbantransport.trip.domain.TripEntity;
import com.urbantransport.trip.domain.TripStatus;
import jakarta.persistence.criteria.Predicate;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class TripSpecifications {

    public static Specification<TripEntity> filter(TripStatus status, UUID routeId, UUID busId, UUID driverId,
                                                   UUID branchId, Instant departureFrom, Instant departureBefore) {
        return (root, criteriaQuery, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.notEqual(root.get("status"), TripStatus.DELETED));
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (routeId != null) {
                predicates.add(cb.equal(root.get("routeId"), routeId));
            }
            if (busId != null) {
                predicates.add(cb.equal(root.get("busId"), busId));
            }
            if (driverId != null) {
                predicates.add(cb.equal(root.get("driverId"), driverId));
            }
            if (branchId != null) {
                predicates.add(cb.equal(root.get("originBranchId"), branchId));
            }
            if (departureFrom != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("scheduledDeparture"), departureFrom));
            }
            if (departureBefore != null) {
                predicates.add(cb.lessThan(root.get("scheduledDeparture"), departureBefore));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
