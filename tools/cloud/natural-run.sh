#!/usr/bin/env bash
# Natural CTOV test run: fresh world (seed 4242), no fixtures, server-side tick sprints (no player).
# Linux/macOS/Git-Bash port of the PowerShell driver used so far. UNTESTED on Linux; tested once in Git Bash on Windows.
#
# Usage: tools/cloud/natural-run.sh <label> [sprints=10] [ticks=2400] [continue-tag]
#   label         world folder run/<label>, must match ^vp-natural111-[a-z0-9-]+$
#   continue-tag  continue the EXISTING world run/<label> (new log run/<label>-<tag>.log)
# Environment: PYTHON (default python3), GRADLE_ARGS (extra gradle arguments), VP_SERVER_PORT (25565), VP_RCON_PORT (25575),
#              VP_FORCELOAD="x1 z1 x2 z2;x1 z1 x2 z2" (default: taiga + jungle village of seed 4242)
# Prerequisites: JDK 25 on JAVA_HOME, run/eula.txt containing eula=true (YOUR decision, see tools/cloud/README.md).
# Run it in the background and read the log: a run of 10 sprints takes about 5-10 minutes.
set -euo pipefail
cd "$(dirname "$0")/../.."

label="${1:?usage: natural-run.sh <label> [sprints] [ticks] [continue-tag]}"
sprints="${2:-10}"
ticks="${3:-2400}"
cont="${4:-}"
py="${PYTHON:-python3}"
port="${VP_SERVER_PORT:-25565}"
rport="${VP_RCON_PORT:-25575}"
forceload="${VP_FORCELOAD:-40 -440 190 -290;1430 -475 1575 -310}"
export VP_RCON_PORT="$rport"
unset VP_AUTOTEST

[[ "$label" =~ ^vp-natural111-[a-z0-9-]+$ ]] || { echo "invalid label: $label" >&2; exit 2; }
[[ -z "$cont" || "$cont" =~ ^[a-z0-9]+$ ]] || { echo "invalid continue tag: $cont" >&2; exit 2; }
[[ -f run/eula.txt ]] && grep -q '^eula=true' run/eula.txt || { echo "run/eula.txt with eula=true is missing (see tools/cloud/README.md)" >&2; exit 2; }
[[ ! -e run/vp-autotest.txt ]] || { echo "run/vp-autotest.txt exists: remove it first" >&2; exit 2; }
# Only servers started from THIS checkout/worktree count (other worktrees use their own ports).
if pgrep -f "(net.fabricmc.devlaunchinjector|KnotServer|fabric-server).*$PWD/|$PWD/.*(net.fabricmc.devlaunchinjector|KnotServer)" >/dev/null 2>&1; then echo "a server process is already running in $PWD" >&2; exit 2; fi

if [[ -n "$cont" ]]; then
  [[ -d "run/$label" ]] || { echo "world run/$label does not exist" >&2; exit 2; }
  log="run/$label-$cont.log"
else
  [[ ! -e "run/$label" ]] || { echo "world run/$label already exists: use a new label or pass a continue tag" >&2; exit 2; }
  log="run/$label.log"
fi
[[ ! -e "$log" ]] || { echo "log $log already exists" >&2; exit 2; }

mkdir -p run
props_backup="$(mktemp)"
[[ -f run/server.properties ]] && cp run/server.properties "$props_backup" || : > "$props_backup"
restore_props() { if [[ -s "$props_backup" ]]; then cp "$props_backup" run/server.properties; else rm -f run/server.properties; fi; rm -f "$props_backup"; echo "PROPERTIES RESTORED"; }
server_pid=""
cleanup() {
  if [[ -n "$server_pid" ]] && kill -0 "$server_pid" 2>/dev/null; then
    "$py" tools/rcon.py 'save-all flush' stop >/dev/null 2>&1 || true
    for _ in $(seq 1 45); do kill -0 "$server_pid" 2>/dev/null || break; sleep 2; done
    kill "$server_pid" 2>/dev/null || true
  fi
  restore_props
}
trap cleanup EXIT

sed -e "s/@LEVEL@/$label/" -e "s/@SEED@/4242/" -e "s/@PORT@/$port/g" -e "s/@RPORT@/$rport/" \
  tools/cloud/server.properties.template > run/server.properties

echo "NATURAL $label (log $log)"
# GRADLE_ARGS (optional, word-split on purpose), e.g. GRADLE_ARGS="-I /path/to/init.gradle"
# shellcheck disable=SC2086
./gradlew ${GRADLE_ARGS:-} runServer --no-daemon --console=plain > "$log" 2> "$log.err" &
server_pid=$!

# wait for the server (up to 10 minutes: first start downloads and decompiles Minecraft)
for _ in $(seq 1 300); do
  grep -q 'Done (' "$log" 2>/dev/null && break
  kill -0 "$server_pid" 2>/dev/null || { echo "server exited before start-up" >&2; tail -n 30 "$log" "$log.err" >&2; exit 1; }
  sleep 2
done
grep -q 'Done (' "$log" || { echo "server did not start in time" >&2; exit 1; }

IFS=';' read -ra areas <<< "$forceload"
for area in "${areas[@]}"; do "$py" tools/rcon.py "forceload add $area" >/dev/null; done
"$py" tools/rcon.py 'weather clear' 'time set 1000' 'time query gametime'

for ((i = 0; i < sprints; i++)); do
  kill -0 "$server_pid" 2>/dev/null || { echo "server exited during the run" >&2; exit 1; }
  "$py" tools/rcon.py "tick sprint $ticks" >/dev/null
  for _ in $(seq 1 150); do
    [[ "$(grep -c 'Sprint completed' "$log")" -gt "$i" ]] && break
    kill -0 "$server_pid" 2>/dev/null || { echo "server exited during a sprint" >&2; exit 1; }
    sleep 2
  done
  [[ "$(grep -c 'Sprint completed' "$log")" -gt "$i" ]] || { echo "sprint $((i + 1)) did not complete" >&2; exit 1; }
done
"$py" tools/rcon.py 'time query gametime'

echo "--- HOUSE / SITE"
grep -E "Kein Bauplatz fuer|Bauplatzsuche fuer|Bauplatz-Diagnose|Bauplan [a-z_]+ (gestartet|fertiggestellt)|Hausplan-Abbruch|steht seit" "$log" | tail -n 20 | cut -c1-300 || true
echo "--- COUNTS"
echo "Bauplan gestartet:       $(grep -c 'Bauplan [a-z_]* gestartet' "$log" || true)"
echo "Bauplan fertiggestellt:  $(grep -c 'fertiggestellt' "$log" || true)"
echo "Hausplan-Abbruch:        $(grep -c 'Hausplan-Abbruch' "$log" || true)"
echo "Langweg unreachable:     $(grep -c 'Langweg [A-Za-z]*: unreachable' "$log" || true)"
echo "Langweg unreachable <=4: $(grep -c -E 'Langweg [A-Za-z]*: unreachable .* route=[0-4] nodes' "$log" || true)"
echo "gestrandet/zurueckgeholt:$(grep -c 'war gestrandet' "$log" || true)"
echo "Steinbruch Rueckweg verworfen: $(grep -c 'kein Rueckweg vom Standplatz' "$log" || true)"
echo "Mine anlegbar-Fehler:    $(grep -c 'keine Mine anlegbar' "$log" || true)"
echo "--- ERRORS (first 8)"
grep -E "ERROR|Exception" "$log" | grep -v -E "Perflib|PerfCounter|jna|morevillagers" | head -n 8 | cut -c1-240 || true
