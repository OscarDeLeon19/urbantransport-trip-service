package com.urbantransport.trip.dto;

import com.urbantransport.trip.domain.TripArrivalEntity;
import com.urbantransport.trip.domain.TripDepartureEntity;
import com.urbantransport.trip.domain.TripEntity;
import lombok.Value;

@Value
public class TripDetailResponse {
    TripResponse trip;
    TripDepartureResponse departure;
    TripArrivalResponse arrival;

    public static TripDetailResponse fromEntity(TripEntity trip, TripDepartureEntity departure,
                                                TripArrivalEntity arrival) {
        return new TripDetailResponse(
                TripResponse.fromEntity(trip),
                departure == null ? null : TripDepartureResponse.fromEntity(departure),
                arrival == null || departure == null ? null : TripArrivalResponse.fromEntity(arrival, departure));
    }
}
