# Dev-Server-Skripte für Linux / Cloud-VMs

Diese Skripte starten den Mod-Dev-Server (`./gradlew runServer`) ohne Windows-Werkzeuge. Sie sind für Cloud-Sessions
(Ubuntu, 4 vCPU, 16 GB RAM) gedacht, laufen aber auch lokal in Bash/Git Bash.

| Datei | Zweck |
|---|---|
| `setup-env.sh` | JDK 25 nach `/opt/jdk25`, `~/.gradle/gradle.properties`, Rechte. Kurz halten (Setup-Skripte sollten in etwa 5 Minuten fertig sein). |
| `autotest.sh <gruppe>` | Eine Prüfgruppe oder ein Bündel (`housesearch`, `builderchecks`, `mining` …) im Dev-Server ausführen und die Ergebniszeilen drucken. Läuft in der Welt `vp-featurebundle59-final4-live` (die Gruppen akzeptieren nur Weltnamen aus einer Liste in `dev/Checks090.java`). |
| `natural-run.sh <label> [sprints] [ticks] [tag]` | Natürlicher CTOV-Lauf (Seed 4242, ohne Fixtures, serverseitige `tick sprint`) mit Zusammenfassung. |
| `server.properties.template` | Vorlage der Dev-Server-Einstellungen (RCON-Passwort ist ein lokaler Testwert). |

## Voraussetzungen

1. **JDK 25** (`JAVA_HOME`), Python 3 (für `tools/rcon.py`), Internet für Maven (`maven.fabricmc.net`, `api.modrinth.com`,
   `libraries.minecraft.net`, `piston-meta.mojang.com`, `piston-data.mojang.com`, `launchermeta.mojang.com`,
   `resources.download.minecraft.net`, `services.gradle.org`, `plugins.gradle.org`, `repo1.maven.org`).
2. **Minecraft-EULA:** Der Dev-Server startet nur, wenn `run/eula.txt` den Inhalt `eula=true` hat. Lies die
   [EULA](https://aka.ms/MinecraftEULA) und lege die Datei **selbst** an. Die Skripte tun das nicht für dich.
3. Beim ersten Start lädt und dekompiliert Loom Minecraft; das dauert mehrere Minuten.

## Beispiele

```bash
bash tools/cloud/setup-env.sh
echo "eula=true" > run/eula.txt          # nur nach Lesen und Akzeptieren der EULA
bash tools/cloud/autotest.sh housesearch
bash tools/cloud/natural-run.sh vp-natural111-test1 10 2400
```

Parallele Läufe (mehrere Git-Worktrees) brauchen unterschiedliche Ports: `VP_SERVER_PORT=25566 VP_RCON_PORT=25576 …`.
`tools/rcon.py` liest den Port aus `VP_RCON_PORT` (Standard 25575).

## Grenzen

- Kein Minecraft-Client: Optik, Tafel-Darstellung, Skins und Bedienung lassen sich so **nicht** prüfen.
- Natürliche Läufe nutzen `forceload` als Spielerersatz. Sie sind Beobachtungen, keine Abnahme.
- Die Skripte wurden unter Windows (Git Bash) getestet, nicht unter Linux.
