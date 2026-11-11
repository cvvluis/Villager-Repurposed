# CLOUD-LOG (Branch claude/village-growth-1111)

Nur belegte Fakten. "In-world geprueft" = Dev-Server-Lauf; "Scratch" = reine Logik in einer Wegwerf-JVM; "kompiliert" = kein Nachweis.

## Umgebung (P0)
- Branch von origin/codex/playtest-2026-11-11 (01db13e); Skripte, Sites.walksBackTo vorhanden.
- JDK 25.0.5 per tools/cloud/setup-env.sh installiert (idempotent, ok). Python 3.13. 4 vCPU / 15 GB.
- ./gradlew build: 1. Versuch scheiterte mit HTTP 429 von repo.maven.apache.org (Ratelimit, kein Hostblock); 2. Versuch BUILD SUCCESSFUL (49 s).
- **BLOCKER: run/eula.txt fehlt** (EULA ist Sache des Nutzers, wird nicht von mir geschrieben). Damit sind alle Dev-Server-Laeufe
  (autotest.sh, natural-run.sh) gesperrt. P0-Referenzwerte (66/140/318/30/22) konnten NICHT geprueft werden.
- tools/cloud/*.sh gelesen: autotest.sh/setup-env.sh unter Linux plausibel (setup-env lief); autotest/natural-run ungetestet (EULA).

## P1 - Rueckholung ohne Route
Geaendert: job/JobUtil.java (StrandWatch: noteRouteFailure/routeStrandSuspected/markRescued, Hooks in approachLong),
job/JobManager.java (rescueStranded: neues Kriterium route, jetzt package-private), job/StrandRescueChecks.java (neue Fixture),
dev/Checks090.java (Gruppe `strandrescue`).
Regel: >= 3 Fehlschlaege (unreachable, Route <= 4 Knoten) in >= 600 Ticks, Luecke <= 400 Ticks, Position +-2 unveraendert; dann
einmal Sites.walksBackTo(Position, villageReturn): nur wenn KEIN Rueckweg -> Teleport zur Dorfstrasse, Cooldown 1200 Ticks.
Ausnahmen: Expedition (Holz), Bergleute (miningUntil+400, insideMine), Karawane (carryingFor). villageReturn wird nur im Verdachtsfall berechnet.
Geprueft: Scratch (reine Zustandslogik) 10 Pruefungen PASS; kompiliert. NICHT in-world geprueft (EULA), Fixture `strandrescue` nicht ausgefuehrt,
Bundles housesearch/roles/mining/miningcycle/woodcuttinggroup nicht gelaufen, kein Naturlauf.
Naechster Schritt: nach EULA `bash tools/cloud/autotest.sh strandrescue` und housesearch.
