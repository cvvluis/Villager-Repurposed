# Villagers: Repurposed 0.10.0 (Playtest 3)

Minecraft 26.2, Fabric, Java 25. Jar: `villagers-repurposed-0.10.0.jar`
SHA-256: `f093eee054ddf384abb0d3d9bb5621a543b64e2c4f0b4d009f596b2058ca265d`
Quelle: Branch `claude/village-growth-1111` (aufbauend auf `codex/playtest-2026-11-11`).

**Wichtig:** Nur auf dem Dev-Server geprüft (43 Prüfgruppen grün, einzeln gelaufen, plus Naturläufe ohne Spieler).
**Nicht** im Client oder Vollpack erprobt. In keinem Naturlauf (Seed 4242, 48 000 Ticks) wurde ein Bau fertig; Bruchstein und Holz sind der Engpass.

## Neu
- Rückholung von Bewohnern ohne Weg zur Dorfstraße (Zaunpfosten, Mulden); tiefes Gelände erst nach 3600 Ticks oder nach einem Sturz.
- Steinbruch und Mine: nächste Steinzellen zuerst, Bruchstein-Lieferung nach Bedarf, Mine ohne Eiche-Brett-Blockade, Bewegungssperre nur im Stollen, Bäume nur mit Rückweg.
- Bauplätze: terrassierter Platz mit Baumräumung für Kornspeicher und Zivilbauten, Pläne starten mit beschaffbarer Palette, Haussuche mit gespeichertem Cursor.
- Berufe: Metzger räuchert Rohfleisch aus dem Kornspeicher; Hausschilder werden nicht doppelt gesetzt; Farmer beachtet Serene Seasons (erstes Feld darf Weizen auch außerhalb der Saison säen).
- Modpack: Laufzeit-Prüfung für More Villagers, Village Taverns, Serene Seasons und Towns and Towers; Server läuft ohne Farmer's Delight, Chef's Delight und Serene Seasons ohne Klassenladefehler.

## Bekannte Lücken
Kein fertiges Gebäude im Naturlauf, Dschungel-Kornspeicher, Client/Optik, Vollpack. Details: CLOUD-LOG.md im Branch.
