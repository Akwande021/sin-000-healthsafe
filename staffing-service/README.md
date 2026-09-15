# StaffingServiceApp

## Overview

Provides on-call schedules for doctors based on ward and status.

Part of the [HealthSafe](../README.md) project. Independent Maven module, no
parent pom.

MQ: this service publishes to the ActiveMQ topic `staffing-events-topic` — see [`../common/`](../common). Broker URL and topic name come from the common `co.wethinkcode.healthsafe.mq.MqConfig` class alongside it in this module.

REST: calls `ward-service` (`../ward-service`) to validate the ward and
`alert-level-service` (`../alert-level-service`) to read the current Emergency
Status before computing a schedule — see [Integration contracts](../README.md#integration-contracts)
in the root README for the endpoint shapes.

## Project structure

```
staffing-service/
├── pom.xml
└── src/main/java/co/wethinkcode/healthsafe/
    ├── StaffingServiceApp.java
    └── mq/
        └── MqConfig.java
```

## Build

```
mvn package
```

## Run

```
java -jar target/staffing-service.jar
```

Listens on port `7033`.

## Endpoints

- `GET /on-call/{wardId}` — validates the ward against `ward-service`
  (`404` if unknown, `502` if `ward-service` is unreachable/erroring), reads the
  current Emergency Status from `alert-level-service` (`502` on failure there
  too), and returns the on-call doctors for that ward:
  `{ "wardId", "department", "alertLevel", "onCallDoctors" }`.

  On-call headcount scales with Emergency Status: 1 doctor at level 0-2, 2 at
  3-5, 3 at 6-8. The doctor names themselves are an invented sample roster —
  there's no doctor data source anywhere else in this repo.

## Test

No automated tests yet. Manually verify it's up (start `ingestion-service`,
`ward-service`, and `alert-level-service` first):

```
curl http://localhost:7033/health              # -> OK
curl http://localhost:7033/on-call/W-01        # -> on-call doctors for W-01
curl http://localhost:7033/on-call/unknown-id  # -> 404
```

To add real tests, add JUnit 5 + the Surefire plugin to `pom.xml`, put tests under
`src/test/java/co/wethinkcode/healthsafe/`, and run `mvn test`.
