#!/bin/sh
# Kills one container of the stack mid-run, keeps it down, starts it again, and logs each step.
#   TARGET     compose service(s) to kill, space separated (kafka-2, postgres, redis, state-processor, ...)
#   DRILL_AT   seconds to wait before the kill
#   DRILL_FOR  seconds to keep it down
#   HOW        kill (SIGKILL, the default) or stop (SIGTERM, a clean shutdown)
set -eu

log() { echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) drill: $*"; }

ids=""
for service in $TARGET; do
  id=$(docker ps -aq --filter "label=com.docker.compose.project=$PROJECT" --filter "label=com.docker.compose.service=$service")
  if [ -z "$id" ]; then
    log "no container for service $service in project $PROJECT"
    exit 2
  fi
  ids="$ids $id"
done

log "target $TARGET, $HOW at +${DRILL_AT}s, down for ${DRILL_FOR}s"
sleep "$DRILL_AT"
if [ "$HOW" = stop ]; then
  docker stop $ids > /dev/null
else
  docker kill $ids > /dev/null
fi
log "$TARGET down"
sleep "$DRILL_FOR"
docker start $ids > /dev/null
log "$TARGET started"

# Wait for its health check, when it has one, so the log says when it was back.
for i in $(seq 1 120); do
  # Every container healthy, or running when it has no health check.
  state=$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' $ids | sort -u | grep -vx healthy | grep -vx running || true)
  if [ -z "$state" ]; then
    log "$TARGET healthy"
    exit 0
  fi
  sleep 1
done
log "$TARGET not healthy after 120 s"
exit 1
