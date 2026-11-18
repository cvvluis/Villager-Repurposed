# Änderungsprotokoll

Alle genannten Versionen gelten für Fabric, Minecraft 26.2 und Java 25. Die JAR heißt `villagers-repurposed-<version>.jar`.

## 0.10.8

Dorfkarte und Relief erzeugen keine Chunks mehr. Das ist der Fix für das Einfrieren beim Betreten eines Dorfs im großen Modpack.

Beim Betreten eines Dorfs hing der Server-Thread in der Relief-Abtastung der großen Dorftafel. `hasChunkAt` an der Zellmitte reichte nicht: `getHeight` und `getBlockState` haben Nachbarspalten synchron geladen und dabei Gelände erzeugt (`getChunkBlocking`). Das Bild blieb stehen, der Server meldete „Can't keep up“.

- Karte, Relief und die Textkarte lesen nur noch Chunks, die bereits vollständig geladen sind. Pro Aufruf werden höchstens 480 Zellen mit je vier Stichproben klassifiziert. Der Rest erscheint in den folgenden Ticks. Ein fertiges Raster bleibt 10 Sekunden gültig, eines mit unbekannten Zellen 5 Sekunden. Eine fehlgeschlagene Abtastung wird nicht mehr alle 20 Ticks wiederholt.
- Dieselbe nicht blockierende Höhenlesung gilt für die übrigen Geländeabtastungen (Wege, Bauplätze, Berufe, Karawanen).
- Der Hänger-Wächter zählt `getChunkBlocking` nicht mehr als Pause, auch wenn derselbe Stapel `managedBlock` oder `pollTask` enthält. Der Client zählt Frames mit einer Schwelle von 3 Sekunden, nicht mehr Spielticks.
- Das Relief nutzt einen RenderType, den Iris unterstützt. `minecraft:pipeline/debug_quads` entfällt.

`gradle build` ist auf diesem Stand durchgelaufen. Im großen Modpack ist 0.10.8 noch nicht gespielt worden.

## 0.10.7

Der Hänger-Bericht des Clients zählt nur noch, solange eine Welt läuft. Das Erzeugen oder Laden einer Welt ist kein Fehlalarm mehr.

## 0.10.6

Friert das Spiel oder der Server länger als 8 Sekunden ein, schreibt die Mod die Thread-Stapel nach `logs/villagerplus-stall-server.txt` bzw. `logs/villagerplus-stall-client.txt` (höchstens drei Berichte je Start). Ein pausierter Einzelspieler-Server zählt nicht als Hänger. In 0.10.6 war das nur Diagnose; die Ursache ist in 0.10.8 behoben.

## 0.10.5

Drei handgebaute Vorlagen: Dorfhaus (`haus_dorf`, 14×13), Rathaus mit Veranda (`rathaus_dorf`, 22×23) und Schmiede mit Wohnhaus (`schmiede_dorf`, 19×15). Die Bauer setzen sie Block für Block aus dem Dorfvorrat. Was das Dorf nicht herstellen kann, wird ersetzt; fehlen Treppen oder Dachziegel, weichen die Treppen auf Bruchsteintreppen aus. Lava, Glocken, Leitern, Töpfe, Teppiche, Schilder und Arbeitsblöcke werden nicht mitgebaut. Im Spiel verwendet wird bisher das Dorfhaus für neue hohe, zweigeschossige Entwürfe. Laufende Pläne behalten ihre Form. Rathaus und Schmiede liegen in der Bibliothek.

## 0.10.4

Das Dorf-Toast fängt Fehler ab und schaltet sich dann selbst aus. Die Chatnachricht bleibt. `-Dvillagerplus.toast=false` schaltet das Toast ganz ab.

## 0.10.3

Keine dauerhaften `/forceload`-Chunks mehr. Das nächste Dorf wird nur mit kurzlebigen Tickets gehalten, nur für bereits geladene Chunks, nicht solange ein Spieler in der Nähe ist und nicht, wenn der Server schon überlastet ist (mittlere Tickzeit über 40 ms). Alte Zwangsladungen aus 0.10.0 bis 0.10.2 werden freigegeben. `-Dvillagerplus.keptVillages=0` schaltet das Festhalten ab.

## 0.10.2

Höchstens ein Dorf pro Dimension bleibt geladen, das dem nächsten Spieler am nächsten liegt. Alle anderen Dörfer, auch alte Zwangsladungen, werden freigegeben und arbeiten nur noch, solange ein Spieler in Reichweite ist. Einstellbar mit `-Dvillagerplus.keptVillages=N` (Standard 1).

## 0.10.1

- Bauern ernten reife Pflanzen im ganzen Dorf und angeln bei Nahrungsmangel. Essen geht vor dem Kornspeicher über eine begrenzte Brücke in die Dorfkiste. Knappe Mittagsportionen gehen an die Hungrigen. Ein nicht startbares Gehege blockiert den Feldplan nicht mehr.
- Untätige Bewohner und freie Maurer arbeiten an Bruchstein, wenn Baupläne auf Stein warten.
- Einwohner ticken ohne Spieler in der Nähe (Chunk-Lease mit Radius 2).

Die Stände 0.10.1 bis 0.10.7 sind auf dem Dev-Server mit Prüfgruppen gelaufen, nicht als vollständiger Modpack-Lauf.
