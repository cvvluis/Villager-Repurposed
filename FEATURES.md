# Villagers: Repurposed 0.10.0 – komplette Funktionsliste

Fabric-Mod für Minecraft 26.2 (Server und Client; der Info-Knopf braucht die Mod auch beim Spieler). Stand: nach `AI-HANDOFF.md` ist
vermerkt, was im Testlauf beobachtet wurde und was nur im Code steht.

## 1. Villager im Alltag
- **Eigene Chatmeldungen**: Villages: Repurposed nutzt ein gold-grünes `◆ DORF ›`-Kennzeichen und eine eigene animierte Toast-Karte im HUD (Einblenden, kurzes Verweilen, Ausblenden). Die formatierte Meldung bleibt im Systemchatverlauf; gewöhnlicher Spieler- und Vanilla-Chat bleibt unverändert. Visuell im Dev-Client geprüft.
- Deutsche **Namen** (eindeutig; Namensschilder und Namen anderer Mods bleiben erhalten)
- **Festes Zuhause**: das Bett, das die Vanilla-KI vergibt, wird gespeichert; **Schild neben der Haustür** (Name, Beruf, Stufe; bei Mitbewohnern „Haus von …“)
- **Private Kiste** pro Villager im Haus; Öffnen/Zerstören durch Spieler kostet Ansehen und wird per Klatsch weitererzählt
- **Fester Tagesplan**: Morgen (Frühstück, abliefern, Plausch) → Arbeit → Pause (Gespräche) → Arbeit → Mittag am Dorfplatz → Arbeit → Pause → Arbeit → Feierabend (Abendessen, Besitz einlagern) → Nacht (Schlaf)
- **Essen und Hunger**, **Gespräche** mit Klatsch-Austausch, Lohn aus der Dorfkasse
- **Befinden** (Sättigung, Energie, Soziales, Gesundheit, Stimmung) mit echter Wirkung: Arbeitstempo, Preise (Rabatt/Aufschlag), Handelsverweigerung bei Elend
- **Gedächtnis**: Ruf/Klatsch verfällt 4× langsamer; merkt sich Haus, Tür, Schild, Kisten, Arbeitsplätze, erledigte Arbeiten, Geschenke je Spieler
- **Geschenke** (Schleichen + Rechtsklick mit Smaragd, Diamant, Brot, Goldkarotte, Kuchen) verbessern dauerhaft den Ruf; leere Hand zeigt Kurzinfo
- **Fertigkeiten** mit 6 Stufen (Lehrling … Legende): Schwellen 150/500/1200/2600/5000 Aufgaben, je Stufe +12 % Tempo und Zusatzerträge; Aufstiegsmeldung mit Partikeln
- **Heilung im Schlaf**
- **Kinder**: wachsen 3× langsamer auf, gehen tagsüber in die Schule und sammeln dort Erfahrung für später
- **Berufsnachbesetzung**: fällt der letzte Maurer, Pfeilmacher oder Bauer aus, übernimmt ein Arbeitsloser oder ein Überzähliger

## 2. Berufe und ihre Arbeit
- **Bauer**: erntet und pflanzt neu, Extra-Ernte nach Fertigkeit, backt Brot, legt neue Felder an
- **Schäfer**: schert Schafe, baut Betten
- **Metzger**: schlachtet Überschuss, sammelt Drops, gart Fleisch/Fisch
- **Fischer**: angelt am Wasser
- **Rüstungs-/Waffen-/Werkzeugschmied**: repariert Eisengolems; wartet beschädigte Depotwerkzeuge am Amboss der registrierten Werkzeugkammer mit einem laut `ItemStack`-Reparaturdaten kompatiblen Material; schmilzt Erz und arbeitet in der Mine (Metall). Ersatzwerkzeuge und Mod-Ausrüstung sind noch nicht vollständig abgedeckt.
- **Kleriker**: heilt Villager, Golems und Spieler
- **Maurer**: baut Häuser, Brunnen, Rathaus, Mauer, Gehege, Schule, Lager; stellt Fackeln auf; arbeitet in der Mine (Stein)
- **Pfeilmacher**: Pfeile, Stöcke, **fällt echte Bäume** und pflanzt Setzlinge nach
- **Gerber** (Bücher), **Kartograph** (Papier), **Bibliothekar** (Bücherregale)
- **Träger** (Arbeitslose/Dorftrottel): sammeln herumliegende Items zur Dorfkiste
- **Werkstatt**: Bretter, Treppen, Stufen, Mauern, Zäune, Türen, Glasscheiben, Steinziegel, Stein, Glas aus Rohstoffen (auch für Mod-Blöcke nach Vanilla-Benennung)
- **Mine**: abseits des Dorfes, Eingang mit Schild und Laterne, Treppe nach unten; **echter Abbau** von Stein, Erde, Kies, Erzen samt Erzadern; Stollen mit wechselnder Breite/Höhe, **Seitenstollen, Holzstützen mit Deckenbalken, Fackeln**; Schutz vor Wasser, Lava, Spawnern, Höhlen darunter und herabfallenden Blöcken
- **Ausgewogene Vorräte**: Holzfäller und Bergleute beschaffen, was relativ zu Essen/Holz/Stein/Metall knapp ist (keine festen Limits)

## 3. Dorf und Bauwerke
- **Dorferkennung über die Glocke** (Vanilla und Welt-Mod-Dörfer, getestet mit Overhauled Villages)
- **Dorfmitte** mit **Dorfkiste** (Doppelkiste), vier Laternenpfählen; vorhandene Doppelkiste wird übernommen; hügeliges Gelände wird eingeebnet
- **Häuser nach Bedarf** (nur bei Bevölkerungswachstum oder Obdachlosen): kopieren eines der 6 nächsten Häuser Block für Block (Form, Dach, Materialien), zufällig **gespiegelt und gedreht**; Standardhaus im Dorfstil als Rückfall
- **Dorfstil-Erkennung** (Boden, Wand, Dach, Treppen, Tür, Zaun)
- **Brunnen**, **Felder** (7×7 → 9×9 → 11×11, bis 6), **Tiergehege** (9×9 mit Tor), **Rathaus**, **Schule**, **Lagerschuppen**
- **Stadtmauer** (nur bei passender Dorfgröße): 5 Blöcke hoch mit Zinnen, Laternen, Ecktürmen und vier Toren mit Torbogen
- **Reichtums-Aufbauten** nach Dorfreichtum (Laternenpfähle → Marktstand → Denkmal → Springbrunnen → Festplatz)
- **Wege** zwischen Haus und Glocke
- **Lager**: bei voller Dorfkiste wird gebaut, **Erweiterung durch Anbauten**; Maurer holen Material aus dem Lager; nichts wird weggeworfen
- **Material-Kette**: Wunschliste → Werkstatt/Holzfäller/Mine → Händler; nicht beschaffbares Material wird über die Karawane gekauft, notfalls **Ersatzmaterial**
- **Dauerhaft geladen**: Dorf (7×7 Chunks) und aktive Mine bleiben per Force-Load aktiv, damit alles auch ohne Spieler weiterläuft

## 4. Wirtschaft, Handel, Besucher
- **Handelskarawane** (wandernder Händler) mit „imaginärem Dorf“: kauft Überschuss, verkauft Wunschliste, Saatgut, Setzlinge, Fackeln, liefert Tiere fürs Gehege; zahlt höchstens 6 Smaragde je Besuch
- **Smaragde bleiben knapp**: Löhne aus der Dorfkasse, Gäste bringen nur selten einen
- **Gäste aus fremden Dörfern** (einzeln oder Reisegruppe, Tracht anderer Biome): Gastgeschenke, handelbar, siedeln sich manchmal an
- **Tierhaltung**: Füttern, Zucht, Bestandspflege (Metzger/Schäfer)
- **Dorfreichtum** als Punktwert (Stufen 0–5)
- **Expeditionen**: 1–3 mutige Villager erkunden Ruinen, Höhlen oder Festungen; Gesundheit und Fertigkeit entscheiden, ob sie verletzt oder gar nicht zurückkehren; **Beute** kommt in die Dorfkiste/ins Lager

## 5. Dorftafel (Schild neben der Dorfkiste)
- **Übersicht**: farbige **Karte** (4×4-Block-Felder) mit Bebauung, Feldern, Wasser, Wegen, Wald, Glocke/Kiste/Tafel/Schule/Lager/Gehege/Mine und den Dorfbewohnern, dazu **Balkendiagramme** (Einwohner, Stimmung, Reichtum, Essen/Holz/Stein/Metall)
- **Details**: alle Zahlen (Einwohner, Berufe, Bauwerke, Reichtum, Dorfkiste, Lager, Tiere, Bau, Mine, Wunschliste, nächste Karawane)
- **Expeditionen**: Stand, Gesundheit der Mitglieder, Berichte, Statistik
- Reiter anklickbar; Schleichen + Rechtsklick öffnet direkt die Expeditionen

## 6. Handelsmenü
- Knopf **„Info“** zeigt links den Steckbrief (Name, Beruf/Stufe, Tätigkeit, Stimmung, Sättigung, Energie, Soziales, Gesundheit, Haus, Besitz, Ruf, Geschenke, Arbeiten, Gespräche, Preiswirkung), live alle 1 s
- **Stimmungspreise** (Rabatt/Aufschlag), Handelsverweigerung bei Erschöpfung

## 7. Animationen und Effekte
- Passender Gegenstand in der Hand (Hacke, Schere, Spitzhacke, Axt, Angel, Schwert, Buch, Nahrung, Baumaterial …), Armschwung
- Partikel und Geräusche: Block-Bruch, Angeln, Herzen, Essen, Gespräche, Aufstieg
- (Eigene Körperposen wie Sitzen/Hämmern gibt es nicht – das bräuchte Client-Modelle)

## 8. Befehle
- `/villagerplus tafel [uebersicht|details|expeditionen]` – Dorftafel
- `/villagerplus status` – Dorfbewohner und Dorf in der Nähe
- `/villagerplus perf` – Zeit, die der Mod je Tick braucht

## 9. Kompatibilität
- Funktioniert mit Dörfern aus Weltgenerator-Mods (getestet: Overhauled Villages 4.1.0 + Lithostitched + Cloth Config)
- **Unterklassen von Villager** anderer Mods werden mitverwaltet; Namen anderer Mods bleiben
- **Fremde Berufe**: Datapack-Tags `villagerplus:job/<farmer|shepherd|butcher|smith|cleric|mason|fisherman|fletcher|leatherworker|cartographer|librarian>` oder automatische Erkennung über Schlüsselwörter im Berufsnamen; sonst Träger
- Entity-Tag `villagerplus:ignored` nimmt Villager-Typen aus
- Holz/Wolle anderer Mods über Vanilla-Block-Tags als beschaffbar erkannt
- Mixins mit `@WrapOperation` und `require = 0` (kein Absturz, wenn eine andere Mod dieselbe Stelle ändert)

## 10. Leistung
- Zeitbudget je Tick (2 ms) mit Fortsetzung im nächsten Tick; Taktung wird bei hoher Serverlast automatisch 2×/4× seltener
- Zwischenspeicher für Dorfsuche, Smaragdzählung, Vorrats-Gleichgewicht und Lagertruhen
- Baumsuche liest je Spalte nur die Oberfläche statt ~59 000 Blöcke; Material-Prüfung, Schild-Abfrage und Mine-Wegberechnung gedrosselt bzw. ohne Objekterzeugung
- Messung: Ø 21–36 µs je Tick bei 33–155 Villagern (siehe `/villagerplus perf`)

## 11. Einstellungen (`Config.java`)
Taktung, Tagesphasen, Hunger, Gespräche, Lohn, Baukosten, Felder, Gehege, Rathaus/Mauer-Größen, Karawanenintervall, Expeditionen, Wachstumsfaktor der Kinder, Schule/Lager-Einwohnerzahl, Force-Load, Zeitbudget.

## 12. Neu in 0.7.2: Gebäude-Generator und Instandhaltung
- **Architekt**: Häuser, Schule und Lager werden pro Dorf aus dessen echten Baustoffen (Wand, Fachwerk, Sockel, Dach, Fenster, Zaun) frisch entworfen – nie als Kopie. Variiert: Größe, Wandhöhe 3–5, Dachrichtung, Überstand, Fachwerk, Fenster (symmetrisch), Tür, Vordach mit Laternen, Schornstein mit Rauch, Seitenanbau, Hofschmuck (Blumenbeet, Fass + Heu, Sitzbank, Holzstapel), Fensterläden, Blumentöpfe, Weg, Wegelaterne, Teppich + Tisch innen, Fundament am Hang.
- **Abstand**: Neue Gebäude werden nicht mehr in bestehende hineingebaut (2 Blöcke Mindestabstand).
- **Instandhaltung**: Maurer bessern fehlende Blöcke an selbst gebauten Gebäuden und an der Stadtmauer aus; zerstörte Gebäude (> 50 % fehlen) werden nicht ungefragt neu gebaut.
- **Minen räumen auf**: Eingangsmarker (Pfosten, Laterne, Schild) beendeter Minen verschwinden; neue Mine ≥ 24 Blöcke von alten.

## 0.9.0-Erweiterungen
Handelsnetz und Dorfkarten, Nachbarn-Menü, Anreise mit zwei Lamas und Spielerhandel, Beziehungen und Grußstimmung, Dorfschrein/Plaza, fünf Blöcke Bebauungsabstand, Marktkarrieren und Privatläden mit echten Einkäufen, Nachfolge, Dorfjournal mit Kategorien/Bewohnerfilter/Handelsrangfolge, eigene zweizeilige Namensanzeigen, gerade Routenwege, größere Dorfzählung und verlässlicher Verwaltungstakt. Einzelheiten und Grenzen stehen im README und im Testbericht.
