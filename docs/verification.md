# Verification record

Prepared on 2026-10-09. This records observed checks, not a claim that unexecuted environments were tested.

| Check                                                               | Observed result                                                                                                                                                |
|---------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Maven `verify`, JDK 21                                              | BUILD SUCCESS; 34 unit tests, zero failures/errors/skips                                                                                                       |
| Real Spring JmsTemplate/TransactionManager with mocked JMS provider | Commit, rollback, transaction-bound session, cleanup and ambiguous commit classification verified                                                              |
| Dynamic settings and scheduling                                     | Caps, empty receive, disable between messages, expiry, recovery, non-overlap, polling during a blocked MQ worker and `once` without background cycles verified |
| Local storage                                                       | Claim/retry/quarantine, process lock, crash recovery and journal idempotency verified                                                                          |
| Python mock HTTP API                                                | 3 tests passed, including revision changes, network views, 503/recovery/reset and invalid PATCH keys                                                           |
| Repackaged Spring Boot JAR                                          | Help, shell quit, enqueue/status and disabled once executed successfully                                                                                       |
| Repackaged agent with actual IBM client against refused local port  | MQ failure observed; settings changed while offline; pause and graceful stop succeeded; 13 work items remained ready, zero uncertain                           |
| Docker Compose                                                      | Configuration parsed successfully with Compose CLI                                                                                                             |
| Kubernetes                                                          | Kustomize rendered; kubeconform strict validation: 9 valid resources, zero invalid/errors/skips                                                                |
| Bash scripts                                                        | Syntax checks passed                                                                                                                                           |
| Packaged runtime dependency inspection                              | Versions below present in BOOT-INF/lib                                                                                                                         |

The packaged JAR included:

- `spring-jms` and `spring-tx`: 7.0.9;
- IBM Jakarta client: 9.4.0.25;
- `logback-classic` and `logback-core`: 1.6.5;
- `bcprov-jdk18on`, `bcpkix-jdk18on`, `bcutil-jdk18on`: 1.85.

## Included live tests requiring your MQ server

`./mvnw -B -ntp -Pmq-it verify` enables 5 live integration test cases:

1. Persistent UTF-8 send/receive and removal after commit.
2. Rollback-only send is not visible.
3. Handler rejection rolls back and causes redelivery.
4. Per-message transactions preserve earlier commit after a later handler fails.
5. Real HTTP QueueSettings changes: bad connection port, repaired endpoint, direction caps, disable and changed caps on resume.

Case 5 drives cycles deterministically rather than waiting for wall-clock scheduler timing. Unit tests separately exercise actual scheduler/poller concurrency. The integration suite requires the supplied MQSC, app secrets and unused empty `DEV.LAB.IT.IN/OUT`. Test-created IDs are cleaned up; queues are never automatically purged. After an abrupt test crash, manually review any remaining dedicated IT messages before rerunning.

## Execution limits

There was no Docker daemon or Kubernetes cluster available during preparation. Consequently the Docker image build, live MQ round-trips, live server stop/start, Web Console UI and Kubernetes runtime were **not executed here**. The live suite and manual exercises are provided for execution on your server. A refused-port test uses the actual IBM client and proves failure handling, not successful MQ interoperability.

No claim is made that a current CVE scanner reports zero findings. `scripts/check-dependencies.sh` prints the resolved dependency tree; repeat your actual dependency/security scanner and review official advisories. The pinned versions are a deliberate baseline, not a promise to be the latest future releases.

## Reproduce

```bash
./scripts/init-lab.sh
./mvnw -B -ntp verify
python3 -m unittest discover -s mock/tests -v
docker compose up -d --wait --wait-timeout 360 mq mock
./mvnw -B -ntp -Pmq-it verify
./scripts/lab.sh demo
./scripts/check-dependencies.sh
```

GitHub Actions supplies a separate real-MQ integration job using a fresh server. Its result is determined when you run it; this preparation record does not assert that an unrun workflow has passed.
