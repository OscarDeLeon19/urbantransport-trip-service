CREATE TABLE trips (
                       id                     UUID PRIMARY KEY,
                       type                   VARCHAR(10)   NOT NULL,
                       route_id               UUID,
                       origin_branch_id       UUID          NOT NULL,
                       destination_branch_id  UUID,
                       origin_text            VARCHAR(200),
                       destination_text       VARCHAR(200),
                       bus_id                 UUID          NOT NULL,
                       driver_id              UUID          NOT NULL,
                       scheduled_departure    TIMESTAMPTZ   NOT NULL,
                       estimated_arrival      TIMESTAMPTZ   NOT NULL,
                       ticket_price           NUMERIC(10,2),
                       distance_km            NUMERIC(8,2),
                       status                 VARCHAR(20)   NOT NULL,
                       rental_id              UUID,
                       created_by             UUID,
                       created_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),
                       updated_at             TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE TABLE trip_departures (
                                 id                   UUID PRIMARY KEY,
                                 trip_id              UUID          NOT NULL UNIQUE REFERENCES trips (id),
                                 actual_departure_at  TIMESTAMPTZ   NOT NULL,
                                 bus_id               UUID          NOT NULL,
                                 driver_id            UUID          NOT NULL,
                                 odometer_start       NUMERIC(12,2) NOT NULL,
                                 registered_by        UUID          NOT NULL,
                                 registered_at        TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE TABLE trip_arrivals (
                               id                 UUID PRIMARY KEY,
                               trip_id            UUID          NOT NULL UNIQUE REFERENCES trips (id),
                               actual_arrival_at  TIMESTAMPTZ   NOT NULL,
                               odometer_end       NUMERIC(12,2) NOT NULL,
                               fuel_cost          NUMERIC(10,2) NOT NULL,
                               driver_pay         NUMERIC(10,2) NOT NULL,
                               registered_by      UUID          NOT NULL,
                               registered_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE TABLE outbox_events (
                               id              UUID PRIMARY KEY,
                               aggregate_type  VARCHAR(50)  NOT NULL,
                               aggregate_id    UUID         NOT NULL,
                               event_type      VARCHAR(80)  NOT NULL,
                               topic           VARCHAR(80)  NOT NULL,
                               payload         JSONB        NOT NULL,
                               created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
                               published_at    TIMESTAMPTZ,
                               attempts        INTEGER      NOT NULL DEFAULT 0,
                               last_error      TEXT
);

CREATE TABLE processed_events (
                                  event_id      UUID PRIMARY KEY,
                                  event_type    VARCHAR(80) NOT NULL,
                                  processed_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
