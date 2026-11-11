# Villagers: Repurposed 0.9.0, Playtest 2

**Das ist ein Playtest-Build, kein fertiges Release.** Bitte sichere deine Welt, bevor du ihn benutzt.
*This is a playtest build, not a finished release. Back up your world first.*

## Voraussetzungen
- Minecraft Java Edition **26.2**
- **Fabric Loader** 0.20.1 oder neuer und **Fabric API**
- **Java 25**
- Optional, aber empfohlen für Dörfer: CTOV und Lithostitched

## Installation
Die Datei `villagers-repurposed-0.9.0-playtest.2.jar` in den `mods`-Ordner legen und das Spiel mit Fabric starten.

## Neu seit Playtest 1
- **Große Dorftafel** als eigener Block (`/give @s villagerplus:dorftafel_gross`, Kreativ-Tab „Funktionale Blöcke“, Rezept und Drop vorhanden).
- **Dorfmitte und Tafel** werden vom berechneten Siedlungsmittelpunkt aus gesucht und liegen auch in CTOV-Dörfern mit Randglocke näher an der Mitte; die Tafel braucht nur zwei freie Blockzellen.
- **Hausplatzsuche:** 12 Blöcke Abstand zu bestehenden Häusern, schrittweise Suche in bis zu 96 Kandidaten je Durchgang, bessere Vorbereitung natürlichen Geländes (Rodung, Einebnen), Haus-Vorlagen für kleine Häuser.
- **Steinbruch:** Arbeiter wählen keine Bruchstellen mehr, aus denen sie nicht zurück zur Dorfstraße kommen (Schluchten, Gruben); kleine Steinmengen bleiben beim Bauleiter, bis ein Puffer von 12 erreicht ist.
- Diverse Korrekturen an Plaza-Dekoration, Wegsuche und Materialversorgung.

## Bekannte Einschränkungen
- Automatisch geprüft wurde in einer Test-Welt (Dev-Server) und in einzelnen frischen CTOV-Läufen ohne Spieler. Eine Abnahme im Client und ein langer Lauf mit Spieler fehlen.
- Kleine Dörfer können beim Bau lange stocken, besonders wenn Stein, Holz oder Nahrung knapp sind; im Testlauf wurde in dieser Zeit noch kein Haus fertig.
- Der Kornspeicher findet in manchen Dörfern keinen Bauplatz.
- Einzelne Arbeiter können in tiefen Senken hängen bleiben; eine allgemeine Rückholung ist noch nicht umgesetzt.
- Fischer angeln nur als Darstellung (keine echte Angel-Mechanik).
- Die Kompatibilität mit einzelnen Modpacks ist **nicht** geprüft.

## Rückmeldungen
Fehler und Eindrücke bitte als Issue im Repository melden, am besten mit Screenshot und Koordinaten (F3).

**SHA-256:** `9C384F85FB7E6796769D5A7141FD4CD6D871F1192EC35519416CA0F585EB2D56`
