#!/bin/sh
# Replaces every instance of each service in SERVICES, one at a time, the way a Kubernetes rolling
# update with maxSurge 1 and maxUnavailable 0 does: start a new instance, wait until it is healthy,
# then stop an old one with SIGTERM (graceful shutdown) and remove it.
#   SERVICES   compose services to roll, space separated
#   START_AT   seconds to wait before the first replacement
set -eu

P="-p iot-telemetry -f docker-compose.yml -f docker-compose.rolling.yml"
log() { echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) rolling: $*"; }
ids() { docker ps -q --filter "label=com.docker.compose.project=iot-telemetry" --filter "label=com.docker.compose.service=$1"; }
healthy() { docker inspect -f '{{.State.Health.Status}}' $(ids "$1") | grep -c '^healthy$' || true; }

log "rolling $SERVICES at +${START_AT}s"
sleep "$START_AT"
for svc in $SERVICES; do
  old=$(ids "$svc")
  n=$(echo "$old" | wc -w)
  log "$svc: replacing $n instances"
  for id in $old; do
    docker compose $P up -d --no-deps --no-recreate --no-build --scale "$svc=$((n + 1))" "$svc" > /dev/null 2>&1
    for i in $(seq 1 120); do
      [ "$(healthy "$svc")" -ge $((n + 1)) ] && break
      sleep 1
    done
    log "$svc: new instance healthy after ${i}s, stopping $(docker inspect -f '{{.Name}}' "$id")"
    docker stop -t 40 "$id" > /dev/null
    docker rm "$id" > /dev/null
  done
  log "$svc: done"
done
log "finished"
