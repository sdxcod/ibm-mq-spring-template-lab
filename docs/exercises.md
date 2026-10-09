# Reproducible batch and recovery exercises

Use the host agent/spool unless a step explicitly says otherwise. Start MQ and mock, build the project and keep the default queue names. Stop any other consumers/agents using these queues. Test messages can coexist with unrelated messages, but deterministic batch-depth expectations require dedicated empty lab queues. No example automatically purges queues.

## 1. Exact cycle caps: 13 outward messages, 5 + 5 + 3

Do not run `agent` during this exercise. Start with an empty local ready spool or point every command at a new data directory. `once` avoids racing the scheduler while you inspect counts:

```bash
./scripts/settings.sh reset
./scripts/settings.sh patch '{"inward-batch-count":5,"outward-batch-count":5,"receive-timeout-ms":0}'
./scripts/lab.sh enqueue --lab.data-directory=data-batch-exercise --lab.count=13 --lab.message='batch test'
./scripts/lab.sh once --lab.data-directory=data-batch-exercise
./scripts/lab.sh status --lab.data-directory=data-batch-exercise
# sent=5 ready=8
./scripts/lab.sh once --lab.data-directory=data-batch-exercise
./scripts/lab.sh status --lab.data-directory=data-batch-exercise
# sent=10 ready=3
./scripts/lab.sh once --lab.data-directory=data-batch-exercise
./scripts/lab.sh status --lab.data-directory=data-batch-exercise
# sent=13 ready=0
```

Each once runs one inward cycle too; use an empty IN queue. Check OUT depth in the console. Enqueue uses unique work IDs; ordering follows filenames and is not guaranteed numerical payload order.

For inward limits, send six test messages to IN while agent is stopped. Configure `inward-batch-count=2` and no-wait; each once can journal at most two messages. Repeat three times and inspect that experiment's `data.../inward`. Manual send is one message per invocation and commit. If any unrelated IN messages are present, the handler may consume them; use dedicated lab queues.

## 2. Dynamically change caps and intervals

```bash
./scripts/lab.sh agent
# In another terminal:
./scripts/settings.sh patch '{"inward-batch-count":2,"outward-batch-count":3,"inward-interval-ms":10000,"outward-interval-ms":12000}'
./scripts/lab.sh enqueue --lab.count=11
```

Logs show `revision`, `committed` and `maximum`. Revision changes wake a cycle promptly; after that, its success delay uses the new setting. Expect at most three outward items per cycle, not exactly three when work runs out. Counts are per message transaction; any earlier successful commits remain after a later failure.

## 3. Disable and resume

```bash
./scripts/settings.sh patch '{"enabled":false}'
./scripts/lab.sh enqueue --lab.count=4
./scripts/lab.sh send --lab.queue=in --lab.message='while disabled'
./scripts/lab.sh status
./scripts/settings.sh patch '{"enabled":true}'
```

An already active transaction may finish before pausing. New messages stay ready/queued until enabled and settings are fresh. Both directions resume without restarting the application. Manual CLI operations still work while the remote scheduled flag is disabled.

## 4. Bad connection settings, then repair

With agent running and settings poll healthy:

```bash
./scripts/settings.sh patch '{"port":1,"failure-retry-ms":2000}'
./scripts/lab.sh enqueue --lab.count=3
# Observe failed cycles and successful settings revision updates.
./scripts/settings.sh patch '{"inward-batch-count":1,"outward-batch-count":2}'
./scripts/settings.sh patch '{"port":1414}'
```

No connection was available before publication, so failed outbound work is retryable/ready. After repair, a fresh scope uses the new endpoint and new caps. Do not confuse retry delay with the provider's connection timeout. A bad host name can have a slower DNS/connect failure than a refused local port.

Queue-name failure also exercises settings changes: PATCH outward-queue to an absent name such as `DEV.LAB.MISSING`, observe failure, then restore `DEV.LAB.OUT`. An invalid object name or count is rejected before it can replace the active snapshot.

## 5. Real server outage

```bash
docker compose stop mq
./scripts/lab.sh enqueue --lab.count=3
# Keep the existing agent running; see connection failures.
docker compose start mq
```

Once MQ is healthy, ready work resumes. A failure during an active send/commit can instead leave an `uncertain` item; it is deliberately not automatically replayed. Incoming uncommitted messages may be redelivered. A previously committed receive is not redelivered merely because a later message failed.

## 6. Mock unavailable, invalid or too slow

```bash
./scripts/settings.sh fail
# Wait beyond the default 15-second settings lease.
./scripts/settings.sh recover
```

During failure, the last valid snapshot is used only until max-age expiry. After expiry, no new messages begin. Polling continues and a valid same-revision response renews the lease and resumes enabled work.

Validation scenario:

```bash
./scripts/settings.sh patch '{"inward-batch-count":0}'
# New snapshot rejected; last valid one ages without renewal.
./scripts/settings.sh patch '{"inward-batch-count":5}'
```

HTTP timeout scenario:

```bash
curl --fail-with-body -sS -X POST http://localhost:8088/control \
  -H 'Content-Type: application/json' --data '{"status":200,"delay-ms":5000}'
# Java request timeout is 2000ms by default.
./scripts/settings.sh recover
```

The mock keeps `/health` healthy during faults; Docker health here means process up, not successful QueueSettings responses. The mock allows intentionally invalid PATCH values for tests. Do not PATCH revision directly.

## 7. Handler error and rollback

```bash
./scripts/settings.sh patch '{"enabled":false}'
./scripts/lab.sh send --lab.queue=in --lab.message='FAIL: deliberate business rejection'
# Save the exact returned Message ID.
./scripts/settings.sh patch '{"enabled":true}'
```

The journal handler rejects that prefix, Spring rolls back and the cycle stops with failure delay. The message remains/redelivers and can block progress of later messages. No automatic BOQ move is implemented.

To resolve this deliberate test, pause new work and use the saved ID with manual receive (whose handler prints rather than rejects):

```bash
./scripts/settings.sh patch '{"enabled":false}'
./scripts/lab.sh receive --lab.queue=in --lab.message-id=ID:YOUR_48_HEX_DIGITS
./scripts/settings.sh patch '{"enabled":true}'
```

Wait for the active scheduler transaction to end before the manual consume, or stop the agent to avoid competing receives. `get-rollback` in shell provides the simpler single-message rollback/redelivery exercise.

## 8. Uncertain send and crash recovery

A clean connection-refused error before send should be retryable, not uncertain. To observe uncertainty, transport must fail while a send/commit is in progress; exact timing is nondeterministic and the live tests do not claim to reproduce that fault. Unit tests inject a commit failure and verify quarantine instead of replay.

If a process is hard-killed after it claimed local work, the next agent start moves orphaned `in-flight` to `uncertain`. Inspect the JSON work-id and, if available, mq-message-id/commit marker. Pause the scheduler, check MQ and downstream records, and explicitly requeue only after deciding replay is appropriate:

```bash
./scripts/lab.sh status
./scripts/lab.sh requeue --lab.work-id=THE_ACTUAL_UUID
```

Do not use blind requeue as a recovery loop. If a confirmed send had only its local recording fail, MQ may already contain the message. A downstream idempotency key is needed to tolerate an approved replay.

## 9. Independent tests

```bash
./mvnw -B -ntp verify
python3 -m unittest discover -s mock/tests -v
./mvnw -B -ntp -Pmq-it verify
```

The opt-in MQ tests use TEST and dedicated IT queues. Keep `DEV.LAB.IT.IN/OUT` empty/unused and do not point the agent at them. The scheduler integration case uses a real local HTTP server, deliberately wrong port, corrected revision, enabled changes and new batch counts. The command drives cycles deterministically to assert counts, while a separate unit test covers actual scheduling/poller concurrency.
