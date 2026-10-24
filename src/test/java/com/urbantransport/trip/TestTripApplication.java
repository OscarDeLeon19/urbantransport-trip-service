package com.urbantransport.trip;

import org.springframework.boot.SpringApplication;

public class TestTripApplication {

	public static void main(String[] args) {
		SpringApplication.from(TripApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
