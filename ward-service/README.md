# WardServiceApp

## Overview

Provides lists of wards and departments.

Part of the [HealthSafe](../README.md) project. Independent Maven module, no
parent pom.

MQ: this service subscribes to the ActiveMQ topic `staffing-events-topic` — see [`../common/`](../common) — and publishes to the ActiveMQ queue `equipment-failure-queue` when it detects an equipment failure on one of its wards, consumed by [`../equipment-alert-service`](../equipment-alert-service). Broker URL, topic name, and queue name come from the common `co.wethinkcode.healthsafe.mq.MqConfig` class alongside it in this module.

REST: called by `staffing-service` (`../staffing-service`) and `alert-level-service` (`../alert-level-service`) — see [Integration contracts](../README.md#integration-contracts) in the root README for the endpoint shapes.

## Project structure

```
ward-service/
├── pom.xml
└── src/main/java/co/wethinkcode/healthsafe/
    ├── WardServiceApp.java
    └── mq/
        └── MqConfig.java
```

## Build

```
mvn package
```

## Run

```
java -jar target/ward-service.jar
```

Listens on port `7031`.

## Endpoints

- `GET /wards` — full list of cleaned ward records, sourced from `ingestion-service`
  at startup (and re-fetched lazily if the cache is empty, e.g. ingestion-service
  wasn't up yet).
- `GET /wards/{id}` — a single ward by ID (case-insensitive), `404` if unknown.
- `GET /departments` — sorted list of distinct department names.
- `GET /wards/{id}/staffing` — the most recent on-call schedule broadcast for
  that ward, received asynchronously from `staffing-service` over
  `staffing-events-topic` (see [`../common/`](../common)) rather than polling it
  directly. `404` if no broadcast has been received yet for that ward.
- `POST /wards/{id}/equipment-failure` — reports an equipment failure on a ward,
  body `{ "equipment": "...", "description": "..." }` (both optional). `404` if
  the ward is unknown. Publishes a **persistent** message to
  `equipment-failure-queue` for [`../equipment-alert-service`](../equipment-alert-service)
  to consume with guaranteed delivery. There's no equipment/sensor data source
  anywhere in this repo, so this endpoint is a manual stand-in for "detecting" a
  failure. Unlike the topic broadcast above, a publish failure here is a real
  failure of the delivery guarantee, so it's `502`, not a soft flag.

Subscribes to the topic over a `failover:` transport, so a broker that isn't up
yet (or drops briefly) doesn't stop this service from starting or serving the
REST endpoints above — it just keeps retrying in the background.

## Test

No automated tests yet. Manually verify it's up (start `ingestion-service` first;
`cd ../common && docker compose up -d` for the broker, then `POST
http://localhost:7033/on-call/W-01` on `staffing-service` to trigger a broadcast):

```
curl http://localhost:7031/health              # -> OK
curl http://localhost:7031/wards               # -> cleaned ward records
curl http://localhost:7031/wards/W-01          # -> single record, or 404 if unknown
curl http://localhost:7031/departments         # -> distinct department names
curl http://localhost:7031/wards/W-01/staffing # -> 404 until staffing-service broadcasts one
curl -X POST -H "Content-Type: application/json" \
  -d '{"equipment":"MRI Scanner","description":"overheating"}' \
  http://localhost:7031/wards/W-01/equipment-failure   # -> 202, and equipment-alert-service /alerts picks it up
```

To add real tests, add JUnit 5 + the Surefire plugin to `pom.xml`, put tests under
`src/test/java/co/wethinkcode/healthsafe/`, and run `mvn test`.
