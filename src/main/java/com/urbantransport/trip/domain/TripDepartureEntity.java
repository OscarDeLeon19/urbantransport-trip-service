package com.urbantransport.trip.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Immutable
@Table(name = "trip_departures")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TripDepartureEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "trip_id", nullable = false, unique = true)
    private UUID tripId;

    @Column(name = "actual_departure_at", nullable = false)
    private Instant actualDepartureAt;

    @Column(name = "bus_id", nullable = false)
    private UUID busId;

    @Column(name = "driver_id", nullable = false)
    private UUID driverId;

    @Column(name = "odometer_start", nullable = false, precision = 12, scale = 2)
    private BigDecimal odometerStart;

    @Column(name = "registered_by", nullable = false)
    private UUID registeredBy;

    @CreationTimestamp
    @Column(name = "registered_at", nullable = false, updatable = false)
    private Instant registeredAt;
}
