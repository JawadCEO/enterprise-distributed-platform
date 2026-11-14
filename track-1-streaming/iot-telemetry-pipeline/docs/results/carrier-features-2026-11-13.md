# Bulk jobs, profile switches and webhook URL rules, local run, 2026-11-13

The three features the carrier platform still lacked: bulk jobs (FR-9), remote profile switch (FR-10, Flow E) and the rule that webhook URLs must be https to public addresses. Measured in a build container on the local stack, 3,000 simulated lines, 5 customers.

## Webhook URLs

A webhook URL must be `https`, with no user name or password, and every address its host resolves to must be public: not loopback, private (10/8, 172.16/12, 192.168/16, fc00::/7), link-local (169.254/16, which includes the cloud metadata endpoint), carrier-grade NAT (100.64/10), documentation, benchmarking or multicast, including those ranges written as IPv4-mapped, NAT64 or 6to4 IPv6. customer-api checks when an endpoint is registered or changed, and the notifier checks again before every call, because a host's addresses can change after registration. The notifier never follows redirects.

customer-api with the rule on:

| URL | Answer |
|---|---|
| `http://network-simulator:9000/hooks/x` | `400 INVALID_URL` (not https) |
| `https://169.254.169.254/latest/meta-data` | `400 PRIVATE_ADDRESS` |
| `https://localhost/hook` | `400 PRIVATE_ADDRESS` |
| `https://10.1.2.3/hook` | `400 PRIVATE_ADDRESS` |
| `https://user:pw@93.184.216.34/hook` | `400 INVALID_URL` |
| `https://no-such-host.invalid/hook` | `400 UNRESOLVABLE_HOST` |
| `https://93.184.216.34/hook` | `201` |

The rule is on by default. The local stack turns it off (`WEBHOOK_ALLOW_INSECURE_URLS: "true"` in `docker-compose.yml`) because the simulated customers' endpoints are plain http on the Docker network. Never set it anywhere else.

What remains: the notifier's HTTP client resolves the host again when it connects, so a host that changes its DNS answer from public to private within the same second could slip past the check. An egress proxy that applies the same rule at connect time closes that gap in production.

## Profile switch (FR-10)

`POST /v1/lines/{iccid}/profile` with `{"profile": "local-eu"}` and an `Idempotency-Key`. The checks, in order:

| Case | Answer |
|---|---|
| No profile, or one the platform doesn't offer | `400 PROFILE_REQUIRED` or `400 INVALID_PROFILE`, with the list of profiles |
| A profile sent with any other action | `400 UNEXPECTED_PROFILE` |
| Removable SIM | `409 NOT_SUPPORTED_FOR_SIM_TYPE` |
| The line already runs that profile | `409 PROFILE_ALREADY_ACTIVE` |
| Then the usual checks | line state (`ACTIVE` or `INVENTORY`), one request at a time per line, account standing |

The simulated network makes the device detach and re-attach while it enables the new profile, then reports the result. A line during a switch:

```
22:13:57.320  LINE_STATE_CHANGED  ACTIVE to PENDING        not sent
22:13:57.320  REQUEST_ACCEPTED    SWITCH_PROFILE local-us  not sent
22:13:57.701  DETACHED                                     not sent
22:13:57.702  ATTACHED, ONLINE                             not sent (switch in progress, FR-20)
22:13:58.071  REQUEST_COMPLETED   profile local-us         sent
22:13:58.071  LINE_STATE_CHANGED  PENDING to ACTIVE        not sent
```

On success the line records its new profile. A failed switch (the device fell back to its old profile) fails the request with `PROFILE_FAILED` and leaves the line in its old state on its old profile; the first switch in this run failed that way.

Two simplifications: the allowed profiles are one list for every customer (`iot.requests.profiles`), not per plan; and the network's separate `PROFILE_DOWNLOADED` and `PROFILE_ENABLED` events are folded into its one `PROVISIONING_RESULT`.

## Bulk jobs (FR-9)

`POST /v1/jobs` with `{"action": "SUSPEND", "iccids": [...]}` (or `"SWITCH_PROFILE"` with a `"profile"`) and an `Idempotency-Key`; up to 50,000 lines. The answer is `202` with the job at once; `GET /v1/jobs/{jobId}` gives the counts and `GET /v1/jobs/{jobId}/failures` the failed lines with their reasons.

Each line goes through exactly the same checks and path as a single request. The runner keeps at most 200 of a job's requests waiting on the network, so a large job neither runs past the requests' 2-minute time limit nor takes the whole network while other customers wait. provisioning-worker paces every call to the network at 100 a second. When every line has an outcome, the customer gets one `BULK_JOB_SUMMARY` webhook with the counts, plus a `REQUEST_FAILED` for each line that failed on the network. Lines that succeed send no webhook of their own.

Two jobs started together, then a third:

| Job | Lines | Succeeded | Failed on the network | Refused before the network |
|---|---|---|---|---|
| Suspend, customer-002 | 605 | 542 | 28 (rejected, or never answered and timed out) | 35: 30 lines in `INVENTORY` (`INVALID_STATE`), 5 of another customer's lines (`NOT_FOUND`) |
| Switch to `local-apac`, customer-003 | 600 | 224 | 16 | 360 removable SIMs (`NOT_SUPPORTED_FOR_SIM_TYPE`) |
| Restore, customer-002 | 50 | 48 | 2 | 0 |

- **Network rate:** calls to the network per second while the first two jobs ran: 14, 86, 76, 80, 17, 64, 81, 80, 59, 59, 87, 89, 54. That is never above the 100 limit.
- **Webhooks:** 44 `REQUEST_FAILED` (28 + 16), 0 `REQUEST_COMPLETED`, and one `BULK_JOB_SUMMARY` per job for endpoints subscribed to it. The summary reads `{"action": "RESTORE", "succeeded": 48, "failed": 2, "rejected": 0, "jobId": "..."}`.
- **Idempotency:** a second `POST` with the same key returned the same job with `Idempotent-Replay: true`.
- **Instances:** a job is held by one customer-api instance at a time through a 30 s lease, so several instances can run without feeding the same job twice.

## Reproduce

```
./mvnw -DskipTests package
docker compose down -v --remove-orphans
docker compose up -d --build --wait
mkdir -p target/ledger
docker compose --profile sim run --rm -e LINES=3000 -e DURATION=40s -e REQUEST_RATE=0 network-simulator
```

Then, with the operator key from `docker-compose.yml`, call `http://localhost:18084` with `Authorization: ApiKey <key>` and `X-Customer-Id: customer-002`, for example:

```
curl -X POST http://localhost:18084/v1/jobs -H "Authorization: ApiKey iot_localop_local-dev-only-operator-key" -H "X-Customer-Id: customer-002" -H "Idempotency-Key: job-1" -H "Content-Type: application/json" -d "{\"action\":\"SUSPEND\",\"iccids\":[\"8901400000000000002\",\"8901400000000000007\"]}"
```

`./mvnw test`: all pass, including the new URL rule and profile-switch tests.
