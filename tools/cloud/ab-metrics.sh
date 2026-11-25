#!/usr/bin/env bash
# One line of A/B metrics for a natural-run log: bash tools/cloud/ab-metrics.sh <log>
log="$1"; repo="$(cd "$(dirname "$0")/../.." && pwd)"
st=$(grep -c 'Bauplan [a-z_]* gestartet' "$log"); fin=$(grep -c 'Bauplan [a-z_]* fertiggestellt' "$log")
steps=$(grep -oE 'Bauplan [a-z_]+ gestartet.*\(Tick [0-9]+, [0-9]+ Schritte' "$log" | sed -E 's/Bauplan ([a-z_]+) gestartet.*, ([0-9]+) Schritte/\1:\2/' | tr '\n' ' ')
fins=$(grep -oE 'Bauplan [a-z_]+ fertiggestellt bei .*Schritt [0-9]+/[0-9]+' "$log" | sed -E 's/Bauplan ([a-z_]+) fertiggestellt.*Schritt ([0-9]+)\/([0-9]+)/\1:\2\/\3/' | tr '\n' ' ')
lw=$(grep -c 'Langweg [A-Za-z]*: unreachable' "$log"); str=$(grep -c 'war gestrandet' "$log"); sb=$(grep -c 'kein Rueckweg vom Standplatz' "$log")
cad=$(python3 $repo/tools/cloud/cadence.py "$log" 2>&1)
sec=$(echo "$cad" | grep -oE 'mine sections dug: [0-9]+' | grep -oE '[0-9]+')
idle=$(echo "$cad" | grep -oE 'median [0-9]+, p90 [0-9]+, max [0-9]+' | head -1)
ms=$(grep -oE 'or [0-9.]+ ms per tick' "$log" | awk '{s+=$2;n++} END{printf "%.2f", s/n}')
echo -e "$(basename $log .log)\tstarted=$st fin=$fin\tsteps[$steps]\tfin[$fins]\tLangweg=$lw\tstranded=$str\tquarryDiscard=$sb\tmineSections=${sec:-0}\tidle($idle)\tms/tick=$ms"
