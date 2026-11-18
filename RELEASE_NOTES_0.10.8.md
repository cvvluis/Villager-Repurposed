# Villagers Repurposed 0.10.8

Fabric · Minecraft 26.2 · Java 25

Diese Ausgabe fasst die Playtests 0.10.1 bis 0.10.8 zusammen. Gegenüber 0.10.0 ist vor allem das Einfrieren beim Betreten eines Dorfs behoben.

## Einfrieren beim Dorfbetreten

Im großen Modpack blieb das Bild stehen, sobald man ein Dorf betrat. Sound und Menü liefen weiter, der Server meldete „Can't keep up“. Ursache war die Relief-Abtastung der großen Dorftafel: sie hat auf dem Server-Thread fehlende Chunks synchron erzeugt.

0.10.8 liest Karte, Relief und die übrigen Geländeabtastungen nur noch aus Chunks, die schon vollständig geladen sind. Die Karte füllt sich schrittweise. Der Hänger-Wächter erkennt solche Wartezeiten in `getChunkBlocking` und zählt sie nicht mehr als harmlose Pause. Das Relief funktioniert mit Iris; der fehlende Shader `debug_quads` wird nicht mehr benutzt.

## Seit 0.10.0

- **Nahrung (0.10.1).** Dorfweite Ernte, Angeln bei Mangel, eine begrenzte Essensbrücke in die Dorfkiste, knappe Portionen zuerst für Hungrige. Untätige Bewohner und freie Maurer liefern Bruchstein, wenn Pläne warten.
- **Weniger Dauerlast (0.10.2 und 0.10.3).** Höchstens ein Dorf pro Dimension wird festgehalten, nur bereits geladene Chunks, keine neuen `/forceload`-Einträge. Alte Zwangsladungen werden freigegeben. Abschaltbar mit `-Dvillagerplus.keptVillages=0`.
- **Dorf-Toast (0.10.4).** Fehler schalten nur das Toast ab, die Chatnachricht bleibt. `-Dvillagerplus.toast=false` schaltet es ganz ab.
- **Vorlagenhäuser (0.10.5).** Dorfhaus, Rathaus und Schmiede als handgebaute Vorlagen. Neue hohe Häuser können das Dorfhaus verwenden. Rathaus und Schmiede liegen bereit, werden aber noch nicht als Meilenstein gebaut.
- **Hänger-Bericht (0.10.6 und 0.10.7).** Thread-Stapel in `logs/villagerplus-stall-server.txt` und `-client.txt`, höchstens drei Berichte je Start. Der Client meldet nur, solange eine Welt läuft, und seit 0.10.8 einmal pro Frame.

## Hinweis

`gradle build` läuft auf diesem Stand durch. 0.10.8 ist im großen Modpack noch nicht gespielt worden. Bitte dort prüfen: Dorf betreten ohne Standbild, Relief mit Iris, Tafelkarte füllt sich nach.
