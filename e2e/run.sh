#!/usr/bin/env bash
# End-to-end: the app (its e2e build, an app of its own) on the device or
# emulator adb sees, against a throwaway host: a container (e2e/host; podman,
# else docker, or ZAGAL_E2E_ENGINE)
# with sshd and herdr-core, published on 127.0.0.1 and reached through
# `adb reverse`. No volumes: removing the container removes all of it, and
# nothing here touches your sessions, your state or ~/.ssh.
#
#   mise run e2e                     # every flow in e2e/flows
#   mise run e2e e2e/flows/x.yaml
#   ZAGAL_E2E_HOLD=1 mise run e2e    # then keep the host up to poke at by hand
set -euo pipefail
cd "$(dirname "$0")/.."

port=${ZAGAL_E2E_PORT:-2222}
app=io.github.osc100.zagal.e2e
mark="zagal-e2e-$RANDOM$RANDOM"
host=zagal-e2e-$$
engine=${ZAGAL_E2E_ENGINE:-$(command -v podman || command -v docker || true)}
[[ -n $engine ]] || { echo "e2e: needs podman or docker" >&2; exit 1; }

# The core and git, as the host's user.
core() { "$engine" exec -u e2e "$host" hc "$@"; }
on_host() { "$engine" exec -u e2e -w /home/e2e "$host" "$@"; }
cleanup() {
  "$engine" rm -f "$host" >/dev/null 2>&1 || true
  adb reverse --remove "tcp:$port" 2>/dev/null || true
}
trap cleanup EXIT
# Stopped by a signal: exit, so the cleanup above runs.
trap 'exit 130' INT
trap 'exit 143' TERM HUP

# Docker looks for a Dockerfile: name it.
"$engine" build -q -t zagal-e2e-host -f e2e/host/Containerfile e2e/host >/dev/null
"$engine" run -d --rm --name "$host" -p "127.0.0.1:$port:22" zagal-e2e-host >/dev/null

./gradlew -q :app:assembleE2e
adb install -r app/build/outputs/apk/e2e/app-e2e.apk >/dev/null
adb shell pm clear "$app" >/dev/null
# Android 17's local network permission; older versions have none to grant.
adb shell pm grant "$app" android.permission.ACCESS_LOCAL_NETWORK 2>/dev/null || true
adb reverse "tcp:$port" "tcp:$port" >/dev/null

flows() {
  maestro test --test-output-dir "${ZAGAL_E2E_OUT:-build/e2e}" \
    -e HOST="e2e@127.0.0.1:$port" -e MARK="$mark" "$@"
}

# Given flows: just those. Else all of them, in two steps: connecting first,
# then the rest, once the host has what they need.
if [[ $# -gt 0 ]]; then
  flows "$@"
else
  flows e2e/flows/connect.yaml

  # What connecting typed, on the host's side.
  typed=
  for t in $(core ls | grep -oE '"address":"[^"]+"' | cut -d'"' -f4); do
    if core read "$t" | grep -q "$mark"; then typed=$t; break; fi
  done
  if [[ -z $typed ]]; then
    echo "e2e: no terminal on the throwaway core shows '$mark'" >&2
    exit 1
  fi
  echo "e2e: $typed shows what the app typed"

  # What the rest need on the host: a git checkout, and an agent: a shell that
  # reports itself as one (OSC 7501), so no real agent runs. Labeled into the
  # space connecting made, as the desktop labels its own, which agent lists need.
  on_host git init -q -b main repo
  on_host git -C repo -c user.email=e2e@zagal -c user.name=e2e commit -q --allow-empty -m init
  agent=$(core run --bg --name fake sh -c 'printf "\033]0;Fake agent\007\033]7501;state=working:app=claude-code\033\\"; exec sleep 100000' |
    grep -oE '"address":"[^"]+"' | cut -d'"' -f4)
  core label "$agent" $(core get "$typed" | grep -oE '"herdr\.(space|tab)":"[^"]+"' | tr -d '"' | tr : =) >/dev/null

  flows e2e/flows/tabs.yaml e2e/flows/spaces.yaml e2e/flows/pin.yaml e2e/flows/worktree.yaml

  # The worktree flow made a checkout and removed it: git agrees.
  if [[ $(on_host git -C repo worktree list | wc -l) -ne 1 ]]; then
    echo "e2e: the worktree is still there:" >&2
    on_host git -C repo worktree list >&2
    exit 1
  fi
  echo "e2e: the worktree came and went"
fi

# Keep the throwaway host up for poking at by hand, until Ctrl-C.
if [[ ${ZAGAL_E2E_HOLD:-} == 1 ]]; then
  echo "e2e: holding e2e@127.0.0.1:$port ($(basename "$engine") container $host); Ctrl-C ends it"
  # In the background, so a signal doesn't wait out the sleep.
  while :; do sleep 3600 & wait $!; done
fi
