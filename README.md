# IBM MQ Spring JmsTemplate Lab

A standalone Java 21 / Spring Boot console application for **explicit synchronous receive and send** with Spring `JmsTemplate`. Two configurable scheduling loops use remote QueueSettings from a small Python HTTP mock. There is no `@JmsListener` or listener container.

[راهنمای کامل فارسی و IBM MQ Web Console](docs/quickstart-fa.md) · [Infrastructure](docs/infrastructure.md) · [Transactions and recovery](docs/architecture.md) · [QueueSettings API](docs/queue-settings.md) · [Exercises](docs/exercises.md) · [Verification](docs/verification.md)

## What the lab does

- Polls QueueSettings independently of MQ, including while MQ connections fail.
- Opens a fresh Spring connection scope for each inward/outward cycle; shares one physical connection across that cycle's message transactions.
- Receives at most `inward-batch-count` messages; sends at most `outward-batch-count` local work items. Stops early on empty input, no ready work, an error, a settings change, disable or expiry.
- Commits **one message at a time** using `JmsTransactionManager` and `TransactionTemplate`. Earlier commits survive a later failure in the same batch.
- Retries known failed/rolled-back sends; quarantines uncertain send outcomes to avoid blind duplicate publication.
- Provides one-message commands, rollback demonstrations, an interactive shell, Docker Compose and Kubernetes manifests, and an opt-in live MQ integration suite.

`inward` means receive from `DEV.LAB.IN` into an idempotent local journal. `outward` means send local outbox work to `DEV.LAB.OUT`. There is no automatic forwarding from IN to OUT and no default consumer draining OUT.

## Fresh local setup

Requirements: JDK **21**, Docker with Compose v2 (`--wait` support), Bash, OpenSSL, curl. Python 3.11+ is needed only for a host mock or Python tests. Maven 3.9.9 is supplied through the Maven Wrapper. All examples run from the project root on Linux, macOS or Windows WSL. Allow Docker at least 4 GiB RAM. MQ is an amd64 development image; ARM hosts require emulation.

```bash
./scripts/init-lab.sh
./mvnw -B -ntp verify
docker compose up -d --wait --wait-timeout 360 mq mock
./scripts/lab.sh demo
```

Initialization creates independent random passwords in `.secrets/mqAppPassword` and `.secrets/mqAdminPassword`, without a newline, and preserves existing files. MQ reads these mounted secret files. Do not edit/regenerate them against an existing MQ volume without deliberately updating the server credentials. The lab uses IBM MQ Developer configuration; accepting the image's license is part of starting the supplied Compose file.

| Endpoint                | Default                                              |
|-------------------------|------------------------------------------------------|
| MQ client               | `localhost:1414`                                     |
| Queue manager / channel | `QM1` / `DEV.APP.SVRCONN`                            |
| Application user        | `app`, password from `.secrets/mqAppPassword`        |
| MQ Web Console          | `https://localhost:9443/ibmmq/console`, user `admin` |
| QueueSettings mock      | `http://localhost:8088/queue-settings`               |
| Queues                  | `DEV.LAB.IN`, `DEV.LAB.OUT`, `DEV.LAB.TEST`          |

Web Console login and queue inspection steps are in [quickstart-fa](docs/quickstart-fa.md#ibm-mq-web-console). Console depth is distinct from the lab's bounded JMS browse observation.

## Single-message tests and shell

```bash
./scripts/lab.sh send --lab.queue=in --lab.message='سلام Spring JMS'
./scripts/lab.sh receive --lab.queue=in
./scripts/lab.sh send --lab.queue=test --lab.message='discard me' --lab.outcome=rollback
./scripts/lab.sh shell
```

Shell examples:

```text
put in Hello from shell
get-rollback in
get in
put-rollback test Not published
browse test
quit
```

Each `put`/`get` completes its own transaction before the prompt returns. No `begin`/`commit` command leaves pending work between prompts. `--lab.outcome=backout` is an alias for rollback. The shell shares one physical connection; restart the shell after a broken connection or uncertain send.

To select only a message you created, copy its returned `ID:` plus 48 hexadecimal digits and use `--lab.message-id=ID:...` with `receive` or `browse`. `depth` is an alias for bounded JMS browse count, **not exact administrative queue depth**. `--lab.browse-limit=100` defaults to 100. Manual receive prints within the transaction; final success still depends on the following MQ commit.

Manual commands use local `lab.mq.*` settings, independent of the mock and its `enabled` flag. Pause the agent before manually consuming a queue it uses. `demo` uses only its own IDs on TEST and prints three PASS results.

## Host scheduler

```bash
./scripts/settings.sh get
./scripts/lab.sh enqueue --lab.count=13 --lab.message='Outgoing scheduled work'
./scripts/lab.sh status
./scripts/lab.sh agent
```

In a second terminal:

```bash
./scripts/lab.sh send --lab.queue=in --lab.message='Incoming scheduled work'
./scripts/settings.sh patch '{"inward-batch-count":2,"outward-batch-count":3,"inward-interval-ms":4000,"outward-interval-ms":5000}'
./scripts/lab.sh status
./scripts/settings.sh patch '{"enabled":false}'
./scripts/settings.sh patch '{"enabled":true}'
```

The Python mock increments `revision` automatically for every PATCH. Changes apply at message boundaries and wake both scheduling loops. Ctrl+C stops the agent. The outbox lives in `data/outward/{ready,in-flight,sent,uncertain}`; received messages live in `data/inward`. One scheduler process owns a data directory, enforced by a process file lock. CLI enqueue/status may use that same directory while it runs.

`once` fetches current remote settings and runs **one inward cycle and one outward cycle**, without starting background loops:

```bash
./scripts/lab.sh once
```

This command records MQ failures in logs and then exits; it is an observation tool, not a connectivity health-check exit-code contract. No remote settings at startup means no MQ work. Invalid/unavailable settings retain the last valid snapshot for at most the configured age, then pause new work.

## Dynamic failure/recovery controls

```bash
# Bad port: MQ cycles fail, settings polling continues.
./scripts/settings.sh patch '{"port":1}'
# Correct it: new cycles use a fresh connection automatically.
./scripts/settings.sh patch '{"port":1414}'
# Mock outage: last valid snapshot expires after 15 seconds by default.
./scripts/settings.sh fail
./scripts/settings.sh recover
# Real MQ outage / return; keep the agent running.
docker compose stop mq
docker compose start mq
```

Use [the exercises](docs/exercises.md) for reproducible batch, poison-message and timeout scenarios. Disabling or changing settings does not cancel a transaction already in progress. Provider connection calls can outlast the scheduler's retry delay: that delay starts **after the failed cycle finishes**.

## Container scheduler

Host `data/` and Compose `agent-data` are separate spools. Seed and inspect the container spool with the container CLI:

```bash
docker compose --profile agent build agent
docker compose --profile agent run --rm agent --lab.command=enqueue --lab.count=13 --lab.message='Container outgoing work'
docker compose --profile agent up -d agent
docker compose logs -f agent
# Another terminal:
docker compose --profile agent run --rm agent --lab.command=status
```

The container uses `/queue-settings/container`, which translates a mock `localhost` MQ host to `mq`. Both HTTP views share the same revision and PATCH controls. Direct manual CLI commands in the container use `LAB_MQ_HOST=mq`.

## Important transaction behavior

Spring manages JMS resource acquisition, commit and rollback **because the project supplies a transaction manager and explicit transaction boundaries**. Merely using JmsTemplate or looping over messages is not enough to include receive, business processing and a whole batch in one transaction.

This project wraps each receive **and its handler** in one Spring transaction. Successful processing commits; a thrown handler exception rolls back. Each send is persistent and returns success only after Spring commit completes. The batch count is a scheduling cap, **not a batch-sized atomic transaction**. No application code calls `Session.commit()` or `Session.rollback()` directly. Details and alternatives are in [architecture](docs/architecture.md).

The filesystem journal/outbox and MQ are not a distributed transaction. A lost send commit reply can leave publication uncertain. Such work goes to `uncertain`, not automatic replay. After checking the MQ/downstream result by `work-id`, explicitly replay if appropriate:

```bash
./scripts/lab.sh requeue --lab.work-id=YOUR-UUID
```

Pause the agent before reconciliation. Duplicate prevention across systems requires durable application idempotency or an appropriate transactional design; this lab does not promise exactly-once delivery.

## Configuration and tests

Remote schema and timing semantics: [queue-settings.md](docs/queue-settings.md). Local overrides use Spring arguments (`--lab.settings-poll-ms=1000`) or the documented environment variables. `LAB_MQ_PASSWORD` takes precedence over the password file; use the file to keep credentials out of shell history.

```bash
./mvnw -B -ntp verify
python3 -m unittest discover -s mock/tests -v
# Real MQ must be healthy, with the supplied MQSC queues and local secrets:
./mvnw -B -ntp -Pmq-it verify
./scripts/check-dependencies.sh
```

The live tests include HTTP QueueSettings updates, connection failure/recovery and batch caps. Reserved `DEV.LAB.IT.IN/OUT` must be empty and unused; tests never purge shared queues. See [verification](docs/verification.md) for the checks actually executed when this source was prepared.

Kubernetes setup, MQSC inspection, password rules, cleanup, and GitHub commands are in [infrastructure](docs/infrastructure.md).

## Dependency baseline and scope

Pinned baseline: Spring Boot 4.1.1, IBM Jakarta client 9.4.0.25, Jakarta Messaging API 3.0.0, Logback 1.6.5 and Bouncy Castle 1.85. The logging/crypto pins are deliberate security updates; `check-dependencies.sh` shows Maven's resolved versions. This is not an assertion that a current scanner will find zero CVEs. Review official security advisories and repeat your scanner when changing dependencies. The development MQ channel, local HTTP mock and local file spool are a teaching configuration; production requires authenticated settings, TLS, suitable business storage, monitoring and tested recovery policies.

License: MIT for lab code, Apache 2.0 notice for the Maven Wrapper. IBM MQ image licensing remains governed by IBM.
