# Villagers Repurposed 0.11.0

Fabric · Minecraft 26.2 · Java 25

## Sperrzonen auf der Dorftafel

Auf der Karte gibt es jetzt fünf Zonenarten: Feld, Wohnen, Arbeit, Weide und **Sperrzone**. In einer Sperrzone arbeitet das Dorf nicht — kein Neubau, kein Fällen, kein Abbau, keine Erweiterung der Hauptwege zwischen Platz und Glocke. Andere Zonen steuern weiterhin, *wo* geplant wird; die Sperrzone schützt ausdrücklich markierte Felder.

## Bauplatz-Diagnose

Findet das Dorf keinen geeigneten Hausplatz, merkt es sich eine Kurzstatistik (Hang, Zone, blockierte Parzelle, …). Diese Zeile erscheint auf der Dorftafel und im Statusbefehl neben den übrigen Dorfdaten.

## Dorfglocke und Datenhaltung

Eine Dorfglocke mit Dorfdaten bricht beim normalen Abbau nur nach Bestätigung. Mit Schleichen wird sie mit **allen** persistenten Dorf-Attachments (Netz, Markt, Journal, Baupläne, Straßen, Quoten, Zonen, …) als Item aufgehoben und in derselben Dimension wieder platziert — mit Abstand zu anderen Dörfern. Weltkoordinaten in diesen Daten werden zum neuen Glockenort verschoben; Nachbar-Glocken im Handelsnetz, Dorftafel-Blöcke und der Journal-Verlauf bleiben unverändert (Hinweis im Chat). Kreative Duplikate der Umzugs-Glocke enthalten keine zweite Datensatz-Kopie. Explosionen zerstören verwaltete Glocken nicht. Beim Serverende werden interne Caches zuverlässig geleert.

## Technik

Rezepte werden beim Serverstart indexiert. Berufs-Tags und E04-Rollen nutzen stabile Tag-Lookups. Die Relief-Karte auf der großen Dorftafel rendert nur neu, wenn sich die zugrunde liegenden Pixel ändern.

## Release-JAR

Die JAR bleibt deutlich kleiner als der volle Dev-Stand, schließt aber alles aus, was der Produktionscode direkt lädt (`G02SupplyTrace`, `AutoTest`, `G01VillageSpacingChecks`).

## Hinweis

`gradle build` ist auf diesem Stand durchgelaufen. Sperrzone, Bauplatzzeile und Glocken-Umzug im Spiel bitte einmal gegen echte Dorfdaten prüfen.