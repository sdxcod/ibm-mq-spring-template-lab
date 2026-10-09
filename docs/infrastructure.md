# Fresh IBM MQ infrastructure, containers and Kubernetes

## Prerequisites and defaults

Use JDK 21, Bash, OpenSSL, curl and Docker Compose v2. Commands run from the project root. The MQ image is `icr.io/ibm-messaging/mq:9.4.0.25-r2`, linux/amd64, configured as IBM MQ Developer with `QM1`, app channel `DEV.APP.SVRCONN`, app user `app` and web administrator `admin`. ARM requires compatible emulation. Ensure ports 1414, 9443 and 8088 are free before startup.

```bash
./scripts/init-lab.sh
./mvnw -B -ntp verify
docker compose config
docker compose up -d --wait --wait-timeout 360 mq mock
docker compose ps
docker compose logs --tail=100 mq mock
```

The Compose file sets `LICENSE=accept`, `MQ_DEV=true` and enables hashed-password authentication (`MQ_CONNAUTH_USE_HTP=true`). MQ gets credentials from `/run/secrets/mqAppPassword` and `/run/secrets/mqAdminPassword`; no plaintext passwords are embedded in source/Compose. The init script creates two random 48-hex-character secrets, without newline, and never replaces an existing file. Secret files are readable for container mounting; their host parent directory is mode 0700. Do not publish them or print them in shared logs.

Changing a password file while keeping an existing queue-manager volume is not a supported credential rotation procedure in this lab. Preserve it across restarts. For production, use IBM's credential update guidance and a tested secret-rotation policy. Do not add an arbitrary newline when manually creating a password file: Java reads the exact bytes.

## Queues and administrative inspection

`docker/mq/20-lab.mqsc` is mounted at `/etc/mqm/20-lab.mqsc` for creation during startup. It creates:

| Queue            | Purpose                                   |
|------------------|-------------------------------------------|
| `DEV.LAB.IN`     | Scheduled inward receive                  |
| `DEV.LAB.OUT`    | Scheduled outward publish                 |
| `DEV.LAB.TEST`   | One-message and rollback demo             |
| `DEV.LAB.IT.IN`  | Reserved live scheduler integration tests |
| `DEV.LAB.IT.OUT` | Reserved live scheduler integration tests |

All use `DEFPSIST(YES)`, max depth 5000 and max message length 1 MiB. The application explicitly requests persistent JMS delivery. DEV queue names use the Developer image's `DEV.**` app-authority configuration. If changing to another naming prefix or an independently configured server, provision the app user's connect/put/get/browse/inquire authorities explicitly; a remote QueueSettings change does not provision MQ objects or grants.

```bash
printf "DISPLAY QMGR QMNAME\nDISPLAY CHANNEL('DEV.APP.SVRCONN') CHLTYPE\nDISPLAY QLOCAL('DEV.LAB.*') CURDEPTH MAXDEPTH MAXMSGL\n" \
  | docker compose exec -T mq runmqsc QM1
# Reapply queue definitions after editing the lab MQSC:
docker compose exec -T mq runmqsc QM1 < docker/mq/20-lab.mqsc
```

Reapplying definitions uses REPLACE and does not clear existing messages. Changing an existing definition may still be rejected if it conflicts with MQ constraints; inspect MQSC output rather than assuming success from a shell pipeline alone.

The Web Console steps, administrator password and browser certificate instructions are in [quickstart-fa.md](quickstart-fa.md#ibm-mq-web-console). URL: `https://localhost:9443/ibmmq/console`. App credentials are for JMS, admin credentials are for the console. Console CURDEPTH is an administrative measurement; CLI browse is bounded and can change under concurrent consumers.

## Standalone host mock

Compose runs the mock by default. To use Python directly instead:

```bash
docker compose stop mock
./scripts/mock.sh
```

It binds loopback port 8088 and saves state to `mock/state/queue-settings.json`. In another terminal use `scripts/settings.sh`. Do not run two mocks on that same port. The mock contains unauthenticated local exercise controls, not production configuration management.

## Container agent and persistence

```bash
docker compose --profile agent build agent
docker compose --profile agent run --rm agent --lab.command=enqueue --lab.count=12
docker compose --profile agent up -d agent
docker compose logs -f agent
```

Three named volumes have separate responsibilities: `mq-data` (queue manager), `mock-state` (remote settings), and `agent-data` (journal/outbox). The Java container runs as uid/gid 10001. Container CLI enqueue/status uses `agent-data`; host CLI uses `data/`. Do not start two agents against the same spool or share one spool between unrelated MQ endpoints.

A local JmsTransactionManager handles MQ session commit/rollback; it does not commit the volume files or HTTP settings in the same transaction. Per-cycle connection lifecycle and **per-message transaction** boundaries are defined by the application. Spring performs the low-level connection/session operations and transaction completion. Batch count is a cap, not atomic batch processing. See [architecture.md](architecture.md) before changing transaction boundaries.

## Stop, resume and deliberate reset

```bash
# Keeps messages, settings and spool:
docker compose --profile agent down
# Restart server/mock:
docker compose up -d --wait --wait-timeout 360 mq mock
```

`down` preserves named volumes. `docker compose --profile agent down --volumes` is a **destructive lab reset**: it removes queue-manager data/messages, mock settings and the container journal/outbox. Use it only when you intentionally want to discard that lab state. Host `data/` and `.secrets/` remain; deleting them is a separate deliberate action. Resetting only mock settings is available with `./scripts/settings.sh reset`, preserving monotonic revision in that existing mock state.

## Kubernetes on kind

This is a single-instance lab. Requirements: Docker, kubectl, kind, a cluster with dynamic PVC storage, and enough MQ memory. The agent is not an HTTP server; it has no HTTP readiness endpoint. A Running pod or successful rollout does not prove MQ communication; inspect cycle logs and actual messages.

```bash
kind create cluster --name spring-mq-lab
docker build -t ibm-mq-spring-template-lab:0.1.0 .
kind load docker-image ibm-mq-spring-template-lab:0.1.0 --name spring-mq-lab
./scripts/k8s-up.sh
kubectl -n ibm-mq-spring-lab get pods,pvc
kubectl -n ibm-mq-spring-lab logs -f deployment/agent
```

Manifests are self-contained in `k8s/`: namespace, headless MQ service + StatefulSet with PVC, mock service + Deployment, and agent Deployment + PVC. Both MQ and agent require amd64 nodes in these manifests. On an ARM-only cluster choose a supported architecture/image solution before deployment. Agent imagePullPolicy is Never for local kind loading; for a remote cluster push the built image to your registry and update its image/pull policy.

`k8s-up.sh` generates local password files if absent, creates Kubernetes Secret `mq-lab-passwords` only if it does not exist, applies Kustomize resources and waits for rollouts. It does not silently rotate existing Kubernetes credentials. If a Kubernetes secret already exists, **those** values are authoritative for that cluster; do not assume newly created local files match. Use a fresh namespace/cluster for a fresh independent install, or manage updates deliberately.

Port-forward in separate terminals; stop the Compose services first if host ports conflict:

```bash
kubectl -n ibm-mq-spring-lab port-forward service/mq 1414:1414 9443:9443
kubectl -n ibm-mq-spring-lab port-forward service/mock 8088:8080
```

Now localhost console and settings controls work. MQ and agent pods use internal `mq` and `mock` DNS names. QueueSettings `/container` translates fixture localhost to internal mq.

Seed/inspect the **Kubernetes** agent's spool with commands inside that pod:

```bash
kubectl -n ibm-mq-spring-lab exec deployment/agent -- \
  java -jar /app/app.jar --lab.command=enqueue --lab.count=13
kubectl -n ibm-mq-spring-lab exec deployment/agent -- \
  java -jar /app/app.jar --lab.command=status
```

Java arguments and environment overrides work identically. A second `agent`/`once` process is blocked by the same data-directory lease, but CLI enqueue/status are allowed. Do not scale replicas; this file outbox is not distributed. Recreate deployment strategy avoids concurrent agents during rollout.

Mock Kubernetes state is `emptyDir` and can reset on pod replacement; the running agent rejects older revisions. Restore an adequate revision or intentionally restart the agent after checking settings. MQ and agent use PVCs. Kubernetes secrets are base64 storage, not inherently an encrypted vault; use the cluster's normal secret protection. The in-cluster mock is a lab control and has no authentication.

Deletion is destructive for the lab's namespaced data:

```bash
kubectl delete namespace ibm-mq-spring-lab
# If the entire temporary cluster is no longer needed:
kind delete cluster --name spring-mq-lab
```

Whether underlying persistent disks are removed depends on storage reclaim policy. Inspect PVC/PV policy before deleting data you need.

## Testing and troubleshooting

Run unit tests before live tests. With a healthy fresh Compose MQ and unused reserved IT queues:

```bash
./mvnw -B -ntp verify
./mvnw -B -ntp -Pmq-it verify
./scripts/lab.sh demo
python3 -m unittest discover -s mock/tests -v
```

| Symptom                                       | Check                                                                                     |
|-----------------------------------------------|-------------------------------------------------------------------------------------------|
| MQ 2035 / authorization                       | Correct app user/password, preserved MQ volume credentials, channel and queue authorities |
| MQ 2059 / connection failure                  | Healthy queue manager, host/port routing, firewall and exact queue-manager name           |
| MQ 2085 / unknown object                      | Queue exists on the selected queue manager; apply the lab MQSC                            |
| No scheduler work                             | Valid fresh settings, enabled true, right mock endpoint, ready work or IN messages        |
| Corrected settings rejected                   | revision must increase; changed body cannot reuse an old revision                         |
| OUT not sent after recovery                   | Ready versus uncertain; uncertain intentionally requires reconciliation                   |
| host CLI status empty after container enqueue | Different spools; inspect using the same environment                                      |
| Shell stays broken                            | Exit/restart to get a new connection scope                                                |
| `mq-it` reserved queue assertion fails        | Dedicated test queues must be empty and unused; review their messages manually            |

Logs intentionally show exception class and committed counts without passwords. The interactive CLI may show provider details to the local operator. For failures inspect server/channel logs as well; keep secrets out of pasted diagnostics.

References: [IBM MQ container project](https://github.com/ibm-messaging/mq-container), [IBM MQ documentation](https://www.ibm.com/docs/en/ibm-mq/9.4.x), [Spring JMS reference](https://docs.spring.io/spring-framework/reference/integration/jms/using.html).
