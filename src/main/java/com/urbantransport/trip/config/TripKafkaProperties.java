package com.urbantransport.trip.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.kafka.topic")
public record TripKafkaProperties(String tripEvents, String rentalEvents) {
}
