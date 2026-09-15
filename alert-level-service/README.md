# AlertLevelServiceApp

## Overview

Tracks the hospital Emergency Status (0-8, 8 = full Code Blue).

Part of the [HealthSafe](../README.md) project. Independent Maven module, no
parent pom.

REST: called by `staffing-service` (`../staffing-service`) to read the current
status when computing on-call schedules — see [Integration contracts](../README.md#integration-contracts)
in the root README for the endpoint shapes.

## Project structure

```
alert-level-service/
├── pom.xml
└── src/main/java/co/wethinkcode/healthsafe/AlertLevelServiceApp.java
```

## Build

```
mvn package
```

## Run

```
java -jar target/alert-level-service.jar
```

Listens on port `7032`.

## Endpoints

- `GET /alert-level` — current Emergency Status, `{ "level": 0-8 }`. Starts at `0`.
- `POST /alert-level` — set the status, body `{ "level": <int> }`. `400` if
  `level` is missing, non-integer, or outside `0-8`.

## Test

No automated tests yet. Manually verify it's up:

```
curl http://localhost:7032/health                                                   # -> OK
curl http://localhost:7032/alert-level                                              # -> {"level":0}
curl -X POST -H "Content-Type: application/json" -d '{"level":5}' \
  http://localhost:7032/alert-level                                                 # -> {"level":5}
curl -X POST -H "Content-Type: application/json" -d '{"level":42}' \
  http://localhost:7032/alert-level                                                 # -> 400
```

To add real tests, add JUnit 5 + the Surefire plugin to `pom.xml`, put tests under
`src/test/java/co/wethinkcode/healthsafe/`, and run `mvn test`.
