package com.urbantransport.trip.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.trips")
public record TripProperties(Duration departureEarlyMargin) {
}
