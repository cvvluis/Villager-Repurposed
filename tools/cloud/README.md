# Dev server scripts for Linux / cloud VMs

These scripts start the mod dev server (`./gradlew runServer`) without Windows tooling. They are meant for cloud sessions
(Ubuntu, 4 vCPU, 16 GB RAM) but also run locally in Bash/Git Bash.

| File | Purpose |
|---|---|
| `setup-env.sh` | JDK 25 to `/opt/jdk25`, `~/.gradle/gradle.properties`, permissions. Keep it short (setup scripts should finish in about 5 minutes). |
| `autotest.sh <group>` | Run one test group or a bundle (`housesearch`, `builderchecks`, `mining` ...) in the dev server and print the result lines. Runs in the world `vp-featurebundle59-final4-live` (the groups accept only world names from a list in `dev/Checks090.java`). |
| `natural-run.sh <label> [sprints] [ticks] [tag]` | Natural CTOV run (seed 4242, no fixtures, server-side `tick sprint`) with a summary. |
| `server.properties.template` | Template of the dev server settings (the RCON password is a local test value). |

## Requirements

1. **JDK 25** (`JAVA_HOME`), Python 3 (for `tools/rcon.py`), internet for Maven (`maven.fabricmc.net`, `api.modrinth.com`,
   `libraries.minecraft.net`, `piston-meta.mojang.com`, `piston-data.mojang.com`, `launchermeta.mojang.com`,
   `resources.download.minecraft.net`, `services.gradle.org`, `plugins.gradle.org`, `repo1.maven.org`).
2. **Minecraft EULA:** The dev server starts only if `run/eula.txt` contains `eula=true`. Read the
   [EULA](https://aka.ms/MinecraftEULA) and create the file **yourself**. The scripts do not do this for you.
3. On first start, Loom downloads and decompiles Minecraft; this takes several minutes.

## Examples

```bash
bash tools/cloud/setup-env.sh
echo "eula=true" > run/eula.txt          # only after reading and accepting the EULA
bash tools/cloud/autotest.sh housesearch
bash tools/cloud/natural-run.sh vp-natural111-test1 10 2400
```

Parallel runs (several Git worktrees) need different ports: `VP_SERVER_PORT=25566 VP_RCON_PORT=25576 …`.
`tools/rcon.py` reads the port from `VP_RCON_PORT` (default 25575).

## Limits

- No Minecraft client: visuals, board rendering, skins and controls can **not** be checked this way.
- Natural runs use `forceload` as a stand-in for players. They are observations, not acceptance.
- The scripts were tested on Windows (Git Bash), not on Linux.
