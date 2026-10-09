# QueueSettings API and local configuration

## Remote settings

`GET /queue-settings` returns the complete JSON document in `mock/queue-settings.json`. The Java application validates it before accepting an immutable snapshot. All listed fields are required. Numbers must be JSON numbers representing exact integers; booleans must be JSON booleans. Unknown extra response fields are ignored by the Java parser, but the mock rejects unknown PATCH fields.

| JSON field            |         Default | Validation / meaning                                       |
|-----------------------|----------------:|------------------------------------------------------------|
| `revision`            |               1 | Integer ≥ 1; changed documents require a higher revision   |
| `enabled`             |            true | Both scheduled directions enabled/paused                   |
| `host`                |       localhost | Nonblank host, at most 255 characters                      |
| `port`                |            1414 | 1–65535                                                    |
| `queue-manager`       |             QM1 | MQ object name, 1–48 characters                            |
| `channel`             | DEV.APP.SVRCONN | MQ object name, 1–48 characters                            |
| `inward-queue`        |      DEV.LAB.IN | Receive destination                                        |
| `outward-queue`       |     DEV.LAB.OUT | Send destination                                           |
| `inward-batch-count`  |               5 | 1–1000; maximum received/processed messages per cycle      |
| `outward-batch-count` |               5 | 1–1000; maximum sent ready work items per cycle            |
| `inward-interval-ms`  |            2000 | 10–3,600,000; fixed delay after a successful inward cycle  |
| `outward-interval-ms` |            3000 | 10–3,600,000; fixed delay after a successful outward cycle |
| `failure-retry-ms`    |            3000 | 10–3,600,000; delay after a failed MQ cycle                |
| `receive-timeout-ms`  |            1000 | 0–5000; zero = no-wait, positive = per-receive wait        |

Accepted object-name characters: letters, digits, `.`, `_`, `/`, `%`. Names still need to exist on the server with app-user authorities; accepting a settings document is not a live MQ connectivity/authorization test. Counts apply separately to each direction and do not include an atomic whole-batch transaction.

Credentials are deliberately local, not part of this remotely mutable lab schema. Use password files and `lab.user`. Remote changes can update the endpoint, queue names and scheduling policy; they do not alter a password halfway through a transaction.

## Mock endpoints

| Method / path                   | Function                                                             |
|---------------------------------|----------------------------------------------------------------------|
| `GET /health`                   | Mock process health; remains healthy during settings faults          |
| `GET /queue-settings`           | Host view of complete settings                                       |
| `GET /queue-settings/container` | Same settings; localhost/127.0.0.1 MQ host translated to `mq`        |
| `PATCH /queue-settings`         | Merge known fields, increment revision automatically                 |
| `POST /control`                 | Simulate settings HTTP 503 or response delay                         |
| `POST /reset`                   | Restore fixture values with a newer revision, clear simulated faults |

```bash
./scripts/settings.sh get
./scripts/settings.sh patch '{"inward-batch-count":3,"outward-batch-count":4}'
./scripts/settings.sh patch '{"enabled":false}'
./scripts/settings.sh fail
./scripts/settings.sh recover
./scripts/settings.sh reset
# HTTP timeout scenario (default Java HTTP timeout is 2 seconds):
curl --fail-with-body -sS -X POST http://localhost:8088/control \
  -H 'Content-Type: application/json' --data '{"status":200,"delay-ms":5000}'
./scripts/settings.sh recover
```

The PATCH mock intentionally permits invalid values (e.g. a zero batch count) so the application validation can be exercised. It forbids PATCH of `revision`, and forbids unknown keys. Reset increases the current revision rather than returning to 1. Delay/status controls affect settings GET only; PATCH and recovery remain available.

Compose state is persisted in `mock-state`. A host mock writes `mock/state/queue-settings.json`. The supplied Kubernetes mock has ephemeral state in `emptyDir`; pod recreation can revert its revision. An existing agent rejects that older revision. Restore a sufficiently high revision through repeated PATCH/reset, or intentionally restart the agent after confirming the fixture. Production settings storage should retain monotonic revision durably.

The mock has unauthenticated administrative endpoints for local exercises. Docker binds its published port to loopback. Do not expose it publicly. For host-only mock, stop Compose mock first to free port 8088, then run `./scripts/mock.sh` in a terminal. Settings changes must be made against the mock instance your agent uses.

## Local Spring configuration

`src/main/resources/application.yml` contains defaults. Command-line arguments override them. Available explicit environment shortcuts:

| Environment variable   | Default                                |
|------------------------|----------------------------------------|
| `LAB_SETTINGS_URL`     | `http://localhost:8088/queue-settings` |
| `LAB_DATA_DIRECTORY`   | `data`                                 |
| `LAB_MQ_USER`          | `app`                                  |
| `LAB_MQ_PASSWORD_FILE` | `.secrets/mqAppPassword`               |
| `LAB_MQ_PASSWORD`      | empty; nonempty value overrides file   |
| `LAB_MQ_HOST`          | `localhost`                            |
| `LAB_MQ_PORT`          | `1414`                                 |
| `LAB_MQ_QMGR`          | `QM1`                                  |
| `LAB_MQ_CHANNEL`       | `DEV.APP.SVRCONN`                      |
| `LAB_MQ_IN_QUEUE`      | `DEV.LAB.IN`                           |
| `LAB_MQ_OUT_QUEUE`     | `DEV.LAB.OUT`                          |
| `LAB_MQ_TEST_QUEUE`    | `DEV.LAB.TEST`                         |

`LAB_MQ_HOST/PORT/QMGR/CHANNEL/IN_QUEUE/OUT_QUEUE` select manual CLI endpoints. Scheduled agent/once use remote QueueSettings instead. The password file is read when a new scope is constructed; preserve exact bytes, with no unwanted newline.

| Local property            | Default | Meaning                                     |
|---------------------------|--------:|---------------------------------------------|
| `lab.settings-poll-ms`    |    2000 | Delay after successful settings GET         |
| `lab.settings-failure-ms` |    3000 | Delay after failed/invalid settings GET     |
| `lab.settings-max-age-ms` |   15000 | Maximum age since a valid accepted response |
| `lab.http-timeout-ms`     |    2000 | HTTP request/connect timeout                |
| `lab.idle-delay-ms`       |     500 | MQ loop delay while absent/disabled/stale   |
| `lab.reject-prefix`       |   FAIL: | Inward journal handler rejects this prefix  |

Local delay values must be at least 10ms; max-age must be at least the polling delay. They are startup settings, not dynamically supplied by the mock. Example:

```bash
./scripts/lab.sh agent --lab.settings-poll-ms=1000 \
  --lab.settings-max-age-ms=10000 --lab.http-timeout-ms=1500
```

Timing is fixed delay after completion, not fixed rate. Each direction is serialized with the other. A revision change wakes loops at the next possible boundary; an identical response only refreshes age. MQ failure retry is independent from HTTP failure retry.
