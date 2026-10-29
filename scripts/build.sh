#!/usr/bin/env bash
#
# Serialises Gradle across concurrent agents working in this checkout.
#
# Call it exactly as you would call the wrapper:
#
#     scripts/build.sh :app:installDebug --offline
#
# Only one Gradle ever runs against the build directory. A request is satisfied by any build of
# that task set which *started after the request was made*, so an agent asking while an equivalent
# build is already pending rides along instead of adding another.
#
#   * nothing running                  -> claim the slot and run, draining everything queued
#   * running for less than YOUNG_SECS -> cancel it and start again, so the restart picks up the
#                                         edits that arrived since it started
#   * running for longer than that     -> queue and wait; the runner reaches our task set before
#                                         it lets go
#
# The pending set *is* the queue: whichever waiter next sees an idle slot becomes the runner and
# drains every task set anybody asked for. That needs no leader election and leaves no orphaned
# queue entry behind if an agent is killed mid-wait.
#
# Cancelling a young build is worth it, but killing the wrapper is only half of it: that
# disconnects the client, and the daemon carries on executing the current task until it notices.
# Starting the replacement in that window put two builds in `app/build/intermediates` and both died
# on "Unable to delete directory ... after 10 attempts". So a kill is followed by waiting for the
# daemon to report itself idle again, which is the point the cancellation has actually landed.
set -uo pipefail

ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
STATE=$ROOT/.buildlock
MUTEX=$STATE/mutex

# Below this, a running build is young enough that cancelling it costs less than the stale result
# would. Raise it if your builds spend a long time in configuration.
YOUNG_SECS=${BUILD_YOUNG_SECS:-45}
# How long to let a daemon finish cancelling before giving up and stopping it outright.
DAEMON_CANCEL_SECS=${BUILD_DAEMON_CANCEL_SECS:-30}
# A mutex older than this belonged to an agent that died holding it.
MUTEX_STALE_SECS=${BUILD_MUTEX_STALE_SECS:-60}
POLL_SECS=${BUILD_POLL_SECS:-2}

SUPERSEDED=0

now() { date +%s; }
log() { printf '[build.sh %s] %s\n' "$(date +%H:%M:%S)" "$*" >&2; }

if [ "$#" -eq 0 ]; then
  echo "usage: scripts/build.sh <gradle args...>" >&2
  exit 2
fi

# Gradle picks the daemon JVM by scanning for any Java 21, and a JRE satisfies that scan. It then
# fails in JdkImageTransform, which needs jlink and so needs a full JDK:
#
#   jlink executable ...\redhat.java-1.55.0\jre\21.0.11\bin\jlink.exe does not exist
#
# Whichever Java the editor happens to have installed can win that scan, so pin JAVA_HOME to the
# first candidate that actually ships jlink rather than leaving it to discovery order.
has_jlink() { [ -n "$1" ] && { [ -x "$1/bin/jlink" ] || [ -x "$1/bin/jlink.exe" ]; }; }

if ! has_jlink "${JAVA_HOME:-}"; then
  for candidate in \
    "$HOME"/.gradle/jdks/*/ \
    "/c/Program Files/Android/Android Studio/jbr" \
    "/c/Program Files/Eclipse Adoptium"/*/ \
    "/c/Program Files/Java"/*/
  do
    candidate=${candidate%/}
    if has_jlink "$candidate"; then
      export JAVA_HOME=$candidate
      break
    fi
  done
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
  local waited=0 stampless=0
  until mkdir "$MUTEX" 2>/dev/null; do
    local stamp age
    stamp=$(cat "$MUTEX/stamp" 2>/dev/null || echo)
    if [ -n "$stamp" ]; then
      stampless=0
      age=$(( $(now) - stamp ))
      if [ "$age" -gt "$MUTEX_STALE_SECS" ]; then
        log "breaking a mutex held for ${age}s"
        rm -rf "$MUTEX"
        continue
      fi
    else
      # The directory exists but its stamp does not yet: the holder took it a moment ago and is
      # still writing. Reading that as age-since-the-epoch used to break a mutex that was one
      # instruction old, which let two agents run as the runner at once. Only give up on it after
      # waiting the same stale window a stamped one would get.
      stampless=$((stampless + 1))
      if [ "$stampless" -gt $(( MUTEX_STALE_SECS * 5 )) ]; then
        log "breaking a mutex that never stamped itself"
        rm -rf "$MUTEX"
        stampless=0
        continue
      fi
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
# The slot belongs to the agent holding it, not to whichever Gradle that agent happens to be
# running: a runner drains several task sets in a row, so between them `running.pid` names a build
# that has already exited while the runner is very much alive. Reaping on that made a waiter clear
# a live slot and start a second Gradle beside it. The owner's own pid is the front of `running.id`
# and is alive for as long as the claim is real — including the moment before the first build has
# been spawned, which is why no grace period is needed either.
reap_running() {
  [ -f "$STATE/running.id" ] || return 0
  local id owner
  id=$(cat "$STATE/running.id" 2>/dev/null || echo)
  owner=${id%%-*}
  [ -n "$owner" ] && kill -0 "$owner" 2>/dev/null && return 0
  log "clearing the slot left by dead runner ${owner:-unknown}"
  clear_slot
}

# Waits until no daemon is mid-build.
#
# A disconnected client makes the daemon cancel, but only once it reaches a point it can stop at.
# `--status` is the supported way to ask, and BUSY means exactly "still running a build", so this
# blocks for as long as the cancellation actually takes rather than guessing at a sleep.
wait_for_daemons_idle() {
  local waited=0
  while [ "$waited" -lt "$DAEMON_CANCEL_SECS" ]; do
    ( cd "$ROOT" && ./gradlew --status ) 2>/dev/null \
      | grep -qE '^[[:space:]]*[0-9]+[[:space:]]+BUSY' || return 0
    sleep 2
    waited=$((waited + 2))
  done
  # It never let go. A daemon that will not cancel is worse than no daemon; the next build starts
  # a fresh one.
  log "a daemon was still busy ${waited}s after the kill; stopping daemons"
  ( cd "$ROOT" && ./gradlew --stop ) >/dev/null 2>&1
}

# Kills the whole Windows process tree. Killing the wrapper alone would leave the Gradle client
# attached; taskkill /T takes the java child with it, and the daemon cancels once its client
# disconnects — which is what wait_for_daemons_idle then waits out.
kill_running() {
  local pid winpid
  pid=$(cat "$STATE/running.pid" 2>/dev/null || echo)
  winpid=$(cat "$STATE/running.winpid" 2>/dev/null || echo)
  [ -n "$winpid" ] && taskkill //F //T //PID "$winpid" >/dev/null 2>&1
  [ -n "$pid" ] && kill -9 "$pid" 2>/dev/null
  clear_slot
  wait_for_daemons_idle
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

# Reads the log snapshot taken when the result was published, not the live one: a later build of
# the same task set truncates the live log, and a waiter reading it mid-run would hand back a torn
# transcript that does not match the exit code beside it.
deliver() {
  local key=$1
  [ -f "$STATE/results/$key.log" ] && cat "$STATE/results/$key.log"
  return "$(cat "$STATE/results/$key.exit" 2>/dev/null || echo 1)"
}

# ----------------------------------------------------------------------------------------------
# running a build
# ----------------------------------------------------------------------------------------------

# Spawns Gradle, streams it, and returns its exit code. Sets SUPERSEDED when the slot changed
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
  #
  # A runner drains every queued task set, not just its own, so most of what it streams may belong
  # to somebody else. Left unlabelled those lines read as this agent's build, and another agent's
  # compile error looks like our own tree failing to build — the exit code we return is still ours,
  # taken from this request's own result file.
  local tailpid
  if [ "$key" = "$KEY" ]; then
    tail -n +1 -f "$STATE/logs/$key.log" --pid="$pid" 2>/dev/null &
    tailpid=$!
  else
    tail -n +1 -f "$STATE/logs/$key.log" --pid="$pid" 2>/dev/null \
      | sed "s/^/[another agent's build $key] /" &
    tailpid=$!
  fi

  wait "$pid"
  local code=$?
  kill "$tailpid" 2>/dev/null

  SUPERSEDED=0
  [ "$(cat "$STATE/running.id" 2>/dev/null || echo)" = "$id" ] || SUPERSEDED=1
  return "$code"
}

# A daemon that ran out of heap or vanished says nothing about the code, so it is not a result
# worth handing back. Concurrent builds were the usual cause of this and are now serialised,
# but a stale daemon from an earlier session still has to be cleared out once. Case-insensitive:
# Gradle reports a killed daemon as "Gradle build daemon disappeared unexpectedly".
is_daemon_failure() {
  grep -qiE "OutOfMemoryError|GC overhead limit exceeded|daemon (has )?disappeared|Unable to start the daemon" "$1" 2>/dev/null
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

  # Someone within YOUNG_SECS cancelled us and took the slot; their build is the real answer, so
  # publishing this aborted run would hand every waiter a result that no longer describes the tree.
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

  # Snapshot before the result, so a waiter that sees the exit code always finds the matching log.
  cp -f "$STATE/logs/$key.log" "$STATE/results/$key.log" 2>/dev/null || :
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
  # just-cancelled runner walk back in and start a second Gradle alongside ours.
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

  # It began after we asked, so it already sees our edits — cancelling it would only make two
  # agents starting together take turns restarting each other forever.
  if [ "$started" -ge "$REQUESTED_AT" ]; then
    unlock
    sleep "$POLL_SECS"
    continue
  fi

  age=$(( $(now) - started ))
  if [ "$age" -lt "$YOUNG_SECS" ]; then
    log "cancelling a build that is only ${age}s old so it picks up the current tree"
    kill_running
    become_runner "$$-$(now)"
    continue
  fi

  # Old enough to be worth finishing. Ours is queued behind it and will be run by whoever holds the
  # slot before they let go.
  log "waiting on a build ${age}s in; ours is queued behind it"
  unlock
  sleep "$POLL_SECS"
done
