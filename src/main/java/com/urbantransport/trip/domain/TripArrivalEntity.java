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
@Table(name = "trip_arrivals")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TripArrivalEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "trip_id", nullable = false, unique = true)
    private UUID tripId;

    @Column(name = "actual_arrival_at", nullable = false)
    private Instant actualArrivalAt;

    @Column(name = "odometer_end", nullable = false, precision = 12, scale = 2)
    private BigDecimal odometerEnd;

    @Column(name = "fuel_cost", nullable = false, precision = 10, scale = 2)
    private BigDecimal fuelCost;

    @Column(name = "driver_pay", nullable = false, precision = 10, scale = 2)
    private BigDecimal driverPay;

    @Column(name = "registered_by", nullable = false)
    private UUID registeredBy;

    @CreationTimestamp
    @Column(name = "registered_at", nullable = false, updatable = false)
    private Instant registeredAt;
}
