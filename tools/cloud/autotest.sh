#!/usr/bin/env bash
# Run an in-world check group or bundle on a dev server and print the result lines.
# Usage: tools/cloud/autotest.sh <group-or-bundle> [world-label]
#   group-or-bundle  e.g. housesearch, builderchecks, mining (see src/main/java/de/villagerplus/dev/AutoTest.java)
#   world-label      default vp-featurebundle59-final4-live (creative world, seed 8675309; fixtures restore themselves).
#                    The check groups refuse to run in any world whose name is not on the allowlist in
#                    src/main/java/de/villagerplus/dev/Checks090.java (line with Set.of("ctov", ...)): keep the default
#                    or add a new name there.
# Environment: GRADLE_ARGS (extra gradle arguments), VP_SERVER_PORT (25565), VP_RCON_PORT (25575)
# Prerequisites: JDK 25, run/eula.txt containing eula=true (YOUR decision, see tools/cloud/README.md).
# Exit codes: 0 = every group passed with at least one check, 1 = a group failed, 3 = no check ran (invalid name?), 2 = setup problem.
# UNTESTED on Linux; tested once in Git Bash on Windows.
set -uo pipefail
cd "$(dirname "$0")/../.."

group="${1:?usage: autotest.sh <group-or-bundle> [world-label]}"
label="${2:-vp-featurebundle59-final4-live}"
port="${VP_SERVER_PORT:-25565}"
rport="${VP_RCON_PORT:-25575}"
[[ "$group" =~ ^[a-z0-9_]+$ ]] || { echo "invalid group name: $group" >&2; exit 2; }
[[ "$label" =~ ^[a-z0-9-]+$ ]] || { echo "invalid world label: $label" >&2; exit 2; }
[[ -f run/eula.txt ]] && grep -q '^eula=true' run/eula.txt || { echo "run/eula.txt with eula=true is missing (see tools/cloud/README.md)" >&2; exit 2; }
[[ ! -e run/vp-autotest.txt ]] || { echo "run/vp-autotest.txt exists: remove it first" >&2; exit 2; }

mkdir -p run
props_backup="$(mktemp)"
[[ -f run/server.properties ]] && cp run/server.properties "$props_backup" || : > "$props_backup"
restore_props() { if [[ -s "$props_backup" ]]; then cp "$props_backup" run/server.properties; else rm -f run/server.properties; fi; rm -f "$props_backup"; }
trap restore_props EXIT

sed -e "s/@LEVEL@/$label/" -e "s/@SEED@/8675309/" -e "s/@PORT@/$port/g" -e "s/@RPORT@/$rport/" -e "s/^gamemode=.*/gamemode=creative/" \
  tools/cloud/server.properties.template > run/server.properties

stamp="$(date +%H%M%S)"
log="run/autotest-$group-$stamp.log"
echo "AUTOTEST $group in world $label (log $log)"
export VP_AUTOTEST="$group"
# shellcheck disable=SC2086
./gradlew ${GRADLE_ARGS:-} runServer --no-daemon --console=plain > "$log" 2> "$log.err"
rc=$?

echo "--- RESULT LINES"
grep -E "VP_AUTOTEST|CHECK FAILED|VP090 .* (PASS|FAIL)" "$log" | cut -c1-260
grep -h -E "BUILD (SUCCESSFUL|FAILED)|Fehler:|error:" "$log" "$log.err" | head -n 5
passed_groups="$(grep -c -E 'VP_AUTOTEST GROUP PASS [A-Za-z0-9_]+ [1-9][0-9]*' "$log" || true)"
failed_groups="$(grep -c -E 'VP_AUTOTEST GROUP FAIL' "$log" || true)"
echo "--- groups passed with >0 checks: $passed_groups, failed: $failed_groups, gradle exit: $rc"
if [[ "$failed_groups" -gt 0 ]]; then exit 1; fi
if [[ "$passed_groups" -eq 0 ]]; then echo "NO CHECK RAN: group name invalid, or the server did not start (see $log.err)" >&2; exit 3; fi
exit 0
