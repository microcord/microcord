#!/usr/bin/env bash
#
# Serialises Gradle across concurrent agents working in this checkout.
#
# Call it exactly as you would call the wrapper:
#
#     scripts/build.sh :app:installDebug --offline
#
# Only one Gradle invocation ever runs, and a request is satisfied by any build that started
# after it was made — so an agent that asks while an equivalent build is already pending simply
# rides along instead of adding another. Three rules decide what happens to a request:
#
#   * nothing running                  -> run it now
#   * running for less than YOUNG_SECS -> kill it and start again, so the restart picks up the
#                                         edits that arrived since it started
#   * running for longer than that     -> let it finish and take the next slot; an agent whose
#                                         task set is already waiting there just blocks on it
#
# The pending set *is* the queue: whichever waiter next sees an idle slot becomes the runner and
# drains every task set anybody asked for. That needs no leader election and leaves no orphaned
# queue entry behind if an agent is killed mid-wait.
set -uo pipefail

ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
STATE=$ROOT/.buildlock
MUTEX=$STATE/mutex

# Below this, a running build is young enough that restarting it costs less than the stale result
# would. Raise it if your builds spend a long time in configuration.
YOUNG_SECS=${BUILD_YOUNG_SECS:-45}
# A mutex older than this belonged to an agent that died holding it.
MUTEX_STALE_SECS=${BUILD_MUTEX_STALE_SECS:-60}
POLL_SECS=${BUILD_POLL_SECS:-2}
# How long a claimed-but-not-yet-spawned slot may sit before we assume its owner died.
CLAIM_GRACE_SECS=${BUILD_CLAIM_GRACE_SECS:-30}

SUPERSEDED=0

now() { date +%s; }
log() { printf '[build.sh %s] %s\n' "$(date +%H:%M:%S)" "$*" >&2; }

if [ "$#" -eq 0 ]; then
  echo "usage: scripts/build.sh <gradle args...>" >&2
  exit 2
fi

mkdir -p "$STATE/pending" "$STATE/results" "$STATE/logs"

# One line per argument, so arguments containing spaces survive the round trip.
REQUEST=$(printf '%s\n' "$@")
KEY=$(printf '%s' "$REQUEST" | md5sum | cut -c1-12)
printf '%s\n' "$REQUEST" >"$STATE/pending/$KEY"

# ----------------------------------------------------------------------------------------------
# mutex — held only across state transitions, never across a build
# ----------------------------------------------------------------------------------------------

lock() {
  local waited=0
  until mkdir "$MUTEX" 2>/dev/null; do
    local stamp age
    stamp=$(cat "$MUTEX/stamp" 2>/dev/null || echo 0)
    age=$(( $(now) - stamp ))
    if [ "$age" -gt "$MUTEX_STALE_SECS" ]; then
      log "breaking a mutex held for ${age}s"
      rm -rf "$MUTEX"
      continue
    fi
    sleep 0.2
    waited=$((waited + 1))
    if [ "$waited" -gt 1500 ]; then
      log "gave up waiting for the mutex"
      return 1
    fi
  done
  now >"$MUTEX/stamp"
}

unlock() { rm -rf "$MUTEX"; }

# ----------------------------------------------------------------------------------------------
# running slot
# ----------------------------------------------------------------------------------------------

clear_slot() {
  rm -f "$STATE/running.pid" "$STATE/running.winpid" "$STATE/running.start" "$STATE/running.id"
}

# Clears the slot when its owner died without releasing it.
#
# The claim (running.id) is taken under the mutex but the process (running.pid) only appears once
# Gradle has been spawned, so an id with no pid is either a claim from the last second or an agent
# that died in between. Only the second is worth reaping, hence the grace period.
reap_running() {
  [ -f "$STATE/running.id" ] || return 0
  local pid age
  pid=$(cat "$STATE/running.pid" 2>/dev/null || echo)
  if [ -n "$pid" ]; then
    kill -0 "$pid" 2>/dev/null && return 0
    log "clearing the slot left by dead build $pid"
    clear_slot
    return 0
  fi
  age=$(( $(now) - $(cat "$STATE/running.start" 2>/dev/null || now) ))
  if [ "$age" -gt "$CLAIM_GRACE_SECS" ]; then
    log "clearing a slot claimed ${age}s ago that never started"
    clear_slot
  fi
}

# Kills the whole Windows process tree. Killing the wrapper alone would leave the Gradle client
# attached; taskkill /T takes the java child with it, and the daemon cancels the build once its
# client disconnects.
kill_running() {
  local pid winpid
  pid=$(cat "$STATE/running.pid" 2>/dev/null || echo)
  winpid=$(cat "$STATE/running.winpid" 2>/dev/null || echo)
  [ -n "$winpid" ] && taskkill //F //T //PID "$winpid" >/dev/null 2>&1
  [ -n "$pid" ] && kill -9 "$pid" 2>/dev/null
  clear_slot
  # Give the daemon a moment to notice, so the next build does not race its cancellation.
  sleep 1
}

# ----------------------------------------------------------------------------------------------
# results
# ----------------------------------------------------------------------------------------------

# A result counts only when the build that produced it started after we asked for it.
result_is_fresh() {
  local key=$1 since=$2 started
  [ -f "$STATE/results/$key.exit" ] || return 1
  started=$(cat "$STATE/results/$key.start" 2>/dev/null || echo 0)
  [ "$started" -ge "$since" ]
}

deliver() {
  local key=$1
  [ -f "$STATE/logs/$key.log" ] && cat "$STATE/logs/$key.log"
  return "$(cat "$STATE/results/$key.exit" 2>/dev/null || echo 1)"
}

# ----------------------------------------------------------------------------------------------
# running a build
# ----------------------------------------------------------------------------------------------

# Runs one pending task set. Returns the build's exit code, or 130 when we were superseded.
# Spawns Gradle, streams it, and reports its exit code. Sets SUPERSEDED when the slot changed
# hands underneath us.
spawn_gradle() {
  local key=$1 id=$2 started=$3
  local args=()
  mapfile -t args <"$STATE/pending/$key"

  ( cd "$ROOT" && ./gradlew "${args[@]}" ) >"$STATE/logs/$key.log" 2>&1 &
  local pid=$!
  printf '%s\n' "$pid" >"$STATE/running.pid"
  cat "/proc/$pid/winpid" 2>/dev/null >"$STATE/running.winpid" || : >"$STATE/running.winpid"
  printf '%s\n' "$started" >"$STATE/running.start"

  # Stream to our own stdout as well, so whichever agent is doing the work still sees it live.
  tail -n +1 -f "$STATE/logs/$key.log" --pid="$pid" 2>/dev/null &
  local tailpid=$!

  wait "$pid"
  local code=$?
  kill "$tailpid" 2>/dev/null

  SUPERSEDED=0
  [ "$(cat "$STATE/running.id" 2>/dev/null || echo)" = "$id" ] || SUPERSEDED=1
  return "$code"
}

# A daemon that ran out of heap or vanished says nothing about the code, so it is not a result
# worth handing back. Concurrent builds were the usual cause of this and are now serialised,
# but a stale daemon from an earlier session still has to be cleared out once.
is_daemon_failure() {
  grep -qE "OutOfMemoryError|GC overhead limit exceeded|Daemon (has disappeared|disappeared)|Unable to start the daemon" "$1" 2>/dev/null
}

run_one() {
  local key=$1 id=$2 started args=()
  # Another runner may have finished this task set between the glob and here.
  [ -s "$STATE/pending/$key" ] || return 0
  mapfile -t args <"$STATE/pending/$key"
  [ "${#args[@]}" -eq 0 ] && return 0
  started=$(now)
  printf '%s\n' "$started" >"$STATE/running.start"

  log "running: ${args[*]}"
  spawn_gradle "$key" "$id" "$started"
  local code=$?

  # Someone within YOUNG_SECS killed us and took the slot; their build is the real answer, so
  # publishing this failure would hand every waiter a result that no longer describes the tree.
  if [ "$SUPERSEDED" -eq 1 ]; then
    log "superseded mid-build; discarding exit $code"
    return 130
  fi

  if [ "$code" -ne 0 ] && is_daemon_failure "$STATE/logs/$key.log"; then
    log "daemon failure, not a compile failure — stopping daemons and retrying once"
    ( cd "$ROOT" && ./gradlew --stop ) >/dev/null 2>&1
    sleep 2
    spawn_gradle "$key" "$id" "$started"
    code=$?
    if [ "$SUPERSEDED" -eq 1 ]; then
      log "superseded during the retry; discarding exit $code"
      return 130
    fi
  fi

  printf '%s\n' "$started" >"$STATE/results/$key.start"
  printf '%s\n' "$code" >"$STATE/results/$key.exit"
  rm -f "$STATE/pending/$key"
  log "finished ${args[*]} -> exit $code"
  return "$code"
}

# Claims the slot and drains everything anybody has asked for, ours first.
become_runner() {
  local id=$1 keys=() k
  # Both files land before the mutex is released: a claim visible only as an id would let a
  # just-killed runner walk back in and start a second Gradle alongside ours.
  printf '%s\n' "$id" >"$STATE/running.id"
  now >"$STATE/running.start"
  unlock

  # An earlier runner may have drained our entry with a build that started before we asked, which
  # left us unsatisfied and holding nothing to build. Restate the request so run_one has it.
  printf '%s\n' "$REQUEST" >"$STATE/pending/$KEY"
  run_one "$KEY" "$id"
  [ "$?" -eq 130 ] && return 130

  while :; do
    keys=()
    for k in "$STATE"/pending/*; do
      [ -e "$k" ] && keys+=("$(basename "$k")")
    done
    [ "${#keys[@]}" -eq 0 ] && break
    run_one "${keys[0]}" "$id"
    [ "$?" -eq 130 ] && return 130
  done

  lock
  if [ "$(cat "$STATE/running.id" 2>/dev/null || echo)" = "$id" ]; then
    clear_slot
  fi
  unlock
  return 0
}

# ----------------------------------------------------------------------------------------------
# request loop
# ----------------------------------------------------------------------------------------------

REQUESTED_AT=$(now)
trap 'rm -f "$STATE/pending/$KEY"; exit 130' INT TERM

while :; do
  if result_is_fresh "$KEY" "$REQUESTED_AT"; then
    deliver "$KEY"
    exit $?
  fi

  lock || exit 1
  reap_running

  if [ ! -f "$STATE/running.id" ]; then
    become_runner "$$-$(now)"
    continue
  fi

  started=$(cat "$STATE/running.start" 2>/dev/null || now)

  # It began after we asked, so it already sees our edits — killing it would only make two agents
  # starting together take turns restarting each other forever.
  if [ "$started" -ge "$REQUESTED_AT" ]; then
    unlock
    sleep "$POLL_SECS"
    continue
  fi

  age=$(( $(now) - started ))
  if [ "$age" -lt "$YOUNG_SECS" ]; then
    log "restarting a build that is only ${age}s old so it picks up the current tree"
    kill_running
    become_runner "$$-$(now)"
    continue
  fi

  # Old enough to be worth finishing. Our task set is already in pending, so the next agent to
  # find the slot free will build it — including us, if we get there first.
  log "waiting on a build ${age}s in; ours is queued behind it"
  unlock
  sleep "$POLL_SECS"
done
