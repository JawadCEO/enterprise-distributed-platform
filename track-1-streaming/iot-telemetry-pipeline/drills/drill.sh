#!/bin/sh
# Kills one container of the stack mid-run, keeps it down, starts it again, and logs each step.
#   TARGET     compose service to kill (kafka-2, postgres, redis, state-processor, ...)
#   DRILL_AT   seconds to wait before the kill
#   DRILL_FOR  seconds to keep it down
#   HOW        kill (SIGKILL, the default) or stop (SIGTERM, a clean shutdown)
set -eu

log() { echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) drill: $*"; }

ids=$(docker ps -aq --filter "label=com.docker.compose.project=$PROJECT" --filter "label=com.docker.compose.service=$TARGET")
if [ -z "$ids" ]; then
  log "no container for service $TARGET in project $PROJECT"
  exit 2
fi

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
  state=$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' $ids | sort -u)
  if [ "$state" = healthy ] || [ "$state" = running ]; then
    log "$TARGET $state"
    exit 0
  fi
  sleep 1
done
log "$TARGET not healthy after 120 s"
exit 1
