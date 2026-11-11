# Villagers: Repurposed (Fabric, Minecraft 26.2)
> **A living village, block by block.** Villagers: Repurposed gives Minecraft villages homes, daily routines, work, trade, and room to grow.

[Project website](https://cvvluis.github.io/Villager-Repurposed/) · [GitHub repository](https://github.com/cvvluis/Villager-Repurposed) · [Luis's portfolio](https://cvvluis.github.io/)

<p align="center"><img src="docs/assets/villagers-repurposed-logo.png" alt="Villagers: Repurposed" width="420" /></p>

**Fabric · Minecraft 26.2 · Java 25**

Villagers: Repurposed turns a village into a small, persistent community. Residents keep homes and personal belongings, follow a daily schedule, take on jobs, trade with neighbours, and build useful places as the village grows. The project is under active development; compatibility and feature status are documented below and in the project docs.

### Explore

- [What the mod does](#neu-in-090-handelsnetz-dorfschrein-und-marktleben)
- [Project website](https://cvvluis.github.io/Villager-Repurposed/)
- [More projects by Luis](https://cvvluis.github.io/)

Dorfbewohner haben Namen, feste Häuser mit Schild, einen festen Tagesplan und echte Aufgaben. Das Dorf bekommt eine Dorfmitte
mit Doppelkiste und wächst selbstständig. Server- und Client-Mod (der Info-Knopf braucht die Mod auch beim Spieler; Einzelspieler funktioniert), benötigt Fabric Loader 0.20.1+,
Fabric API und Java 25.

## Neu in 0.9.0: Handelsnetz, Dorfschrein und Marktleben

- **Große Plaza mit Dorfschrein:** achteckiger Platz in der Dorfpalette, überdachter Schrein, Sitzplätze, Beleuchtung, Dorfkiste und Dorftafel. Die Suche schützt bestehende Häuser und berücksichtigt offene Pflasterflächen. In dichten Dörfern kann die neue Mitte an einer freien Erweiterungsfläche liegen. Häuser werden nicht umgesetzt. Neue Bauten halten fünf freie Blöcke Abstand zur äußeren Plaza bzw. zu den Ladenhütten einschließlich Vordächern.
- **Dorfjournal:** neuer Reiter mit Handel, Befinden und Bauen, Bewohnerfilter und Rangfolge nach Handelsvolumen. Relevante Zustandswechsel und Aktivitäten bleiben über Neustarts erhalten. Getrennte Verlaufsgrenzen (96 Befinden-, je 128 Handels- und Bauereignisse) verhindern, dass Routineberichte wichtige Bau- oder Handelsmeldungen verdrängen.
- **Ladeninhaber:** bis zu drei private Läden ab jeweils acht Einwohnern. Der erste Laden bevorzugt einen Erwachsenen, weitere können Kindern als künftige Laufbahn zugeteilt werden. Nach dem Erwachsenwerden bauen sie kleine Hütten in der Dorfpalette mit Dach, Holzrahmen, Fenster und gestreifter Markise. Material wird wirklich aus den Dorfvorräten entnommen. Essenzielle Arbeiter werden nicht abgezogen.
- **Echte Einkäufe:** Ladeninhaber kaufen Ware mit privaten Smaragden bei der Dorfgemeinschaft; Einwohner kaufen während gemeinsamer Öffnungszeiten in der Mittagspause. Waren, Geld und private Kisten werden tatsächlich verändert; es entstehen keine Smaragde durch diese Geschäfte. Ladenbesitzer erhalten ebenfalls reguläre Pausen. Bei Tod ist eine Nachfolge möglich; fremde Blöcke auf Baustellen werden nicht überschrieben.
- **Nachbarn:** drei fiktive Handelspartner mit Spezialitäten und Beziehung sowie bis zu zwölf echte Dorfverbindungen. Dorfkarte im Menü mitnehmen und an einer anderen Dorftafel einlösen (gleiche Dimension, höchstens 3000 Blöcke, beide Dörfer geladen). Warentausch, Handelsroute, Gäste und Grüße mit gegenseitigen Abklingzeiten; Tausch prüft Platz und gleichen Warenwert vor der Änderung.
- **Karawanen:** benannter Händler mit zwei angeleinten, bepackten Eseln, Start bevorzugt 70–100 Blöcke von der Glocke entfernt, Anreise zur Kiste und normale Spielerangebote. Erhandelte Waren werden sichtbar stapelweise aus den Eseltruhen in Dorfkiste/Lager entladen; volle Lager lassen den Frachtesel warten. Handel/Geschenke beeinflussen Beziehungen, aktive Routen die Häufigkeit. Bei fehlenden geladenen Ankunftsplätzen gibt es einen nahen Rückfall bzw. einen späteren Versuch.
- **Eigene Namensanzeigen:** kompakte zweizeilige, farbige Anzeigen mit Name, Beruf und Sternen; nur in der Nähe und mit normaler Sichtverdeckung.
- **Geordnete Wege:** drei Blöcke breite, gerade Verbindungen zwischen Dorfmitte/Glocke und aktiven Handelsrouten. Nur geladene, geeignete Bodenzellen werden umgewandelt; Häuser, Felder und Wasser bleiben geschützt. Es gibt keinen automatischen Tunnel- oder Brückenbau.
- **Bevölkerungswachstum:** neue Wohnhäuser brauchen wachsende Bevölkerung und Wohnbedarf. Eine anfängliche Beobachtungszeit verhindert, dass das erstmalige Laden der Dorfbewohner sofort als Zuwachs zählt. Große Dörfer werden bis 128 Einwohner berücksichtigt.

### Testhinweise

Die CTOV-Testwelt verwendet CTOV 4.1.0, Lithostitched 1.8.0 und Cloth Config. Das frische große CTOV-Wüstendorf liegt bei 7328 / −416, seine Mod-Dorfglocke bei 7333 / 78 / −423 und die neue Plaza bei 7397 / 66 / −450. Die alte Gegend um −338 / −303 enthält historische Testreste.

Entwicklungsbefehle (nur Loom-Entwicklungsumgebung): `/vpkaravane`, `/vptest090 run`, `/vptest090 verify`, `/vptest090 board`. Die Prüfreihe erzeugt abgegrenzte Prüfbauten bei 12000 / 201 / 12000; diese sind keine natürlich gewachsenen Dörfer. Sie prüft Logik und Inventar-Erhaltung. Der zusätzliche Echtlauf im frischen CTOV-Dorf prüft selbstständige Abläufe. Details und tatsächlich beobachtete Ergebnisse: `TEST-REPORT-0.9.0.md`.

## Tagesplan (alle erwachsenen Villager, Zeit seit Sonnenaufgang)
| Zeit (Ticks) | Phase |
|---|---|
| 0–1000 | **Morgen:** Frühstück zu Hause (aus der eigenen Kiste), Waren zur Dorfkiste, Plausch am Dorfplatz |
| 1000–2800 | **Arbeit** (Hauptaufgabe) |
| 2800–3300 | **Pause:** Plausch mit Nachbarn (Gesprächspartner suchen, zusammenstehen, reden, Klatsch austauschen) |
| 3300–5000 | **Arbeit** (Hauptaufgabe) |
| 5000–6500 | **Mittag** am Dorfplatz: abliefern, gemeinsam essen, Vorrat auffüllen, Plausch |
| 6500–8300 | **Arbeit** (Nebenaufgabe) |
| 8300–8800 | **Pause:** Plausch |
| 8800–10500 | **Arbeit** (Nebenaufgabe) |
| 10500–12000 | **Feierabend** zu Hause: Abendessen, persönlichen Besitz in die eigene Kiste |
| ab 12000 | Nacht: Schlaf im eigenen Haus (Vanilla) |
Bei Alarm (Raid, Panik, Kampf) pausiert der Plan. Wer lange nichts gegessen hat, arbeitet nur noch halb so schnell.
## Berufe
| Beruf | Hauptaufgabe | Nebenaufgabe |
|---|---|---|
| Bauer | erntet & pflanzt neu | backt Brot (3 Weizen), **legt neue Felder an** (Saatgut in der Dorfkiste) |
| Schäfer | schert Schafe | baut Betten (3 Wolle + 3 Bretter) |
| Metzger | schlachtet Überschuss (>8/Art), sammelt Drops | gart Fleisch/Fisch/Kartoffeln |
| Fischer | angelt am Wasser | angelt |
| Rüstungs-/Waffen-/Werkzeugschmied | repariert Eisengolems (Barren: +25 HP, ohne: langsam +5) | schmilzt Roheisen/Nuggets zu Barren |
| Kleriker | heilt Villager, Golems, Spieler | heilt |
| Maurer | **baut Häuser & Brunnen**, stellt Fackeln auf | dito |
| Pfeilmacher | Pfeile (Feuerstein+Stock+Feder), Stöcke | dito |
| Gerber | Bücher (Leder + 3 Papier) | dito |
| Kartograph | Papier (3 Zuckerrohr) | dito |
| Bibliothekar | Bücherregale (6 Bretter + 3 Bücher) | dito |
| Arbeitslose / Dorftrottel | **Träger**: sammeln Items auf, die >5 Min. herumliegen, und bringen sie zur Kiste | dito |

## Privateigentum
Jeder Villager bekommt in seinem Haus eine **eigene Kiste** (nahe am Bett, drinnen, nicht vor der Tür). Sie gehört ihm:
abends legt er dort persönlichen Besitz ab (alles außer Arbeitswaren und etwas Proviant), morgens/abends isst er daraus.
Für je 80 erledigte Aufgaben bekommt er einen **Smaragd Lohn** – aus der Dorfkasse (Dorfkiste), nicht aus dem Nichts; ist keiner da, entfällt er. Spieler *können* die Kiste öffnen, verlieren dann aber
Ansehen beim Besitzer (öffnen: klein, zerstören: groß), und die Nachbarn erfahren es per Klatsch.

## Namen, Häuser, Schilder
- Jeder Villager bekommt einen deutschen Namen (außer er wurde per Namensschild benannt).
- Das Bett, das er sich vanilla-typisch sucht, wird sein festes Zuhause (im Gedächtnis gespeichert).
- Neben der Haustür hängt ein **Schild** (Holzart der Tür): Name, Beruf und Stufe. Teilen sich mehrere ein Haus, steht dort „Haus von …“.
- Fehlen Häuser (ein Villager ohne Bett oder weniger als 2 freie Betten), baut der Maurer ein neues.

## Dorfmitte (kompatibel mit Weltgenerator-Mods)
Es wird nichts in die Weltgenerierung eingehängt. Stattdessen erkennen die Villager ihr Dorf über die **Glocke** (Meeting Point) –
das funktioniert für Vanilla-Dörfer und für Dörfer jeder Mod, die normale Glocken/Betten benutzt. Gibt es noch keine Dorfmitte,
legen die Villager im Umkreis von 3–9 Blöcken der Glocke eine an: 5×5-Platz im Bodenmaterial des Dorfes, **Doppelkiste (die Dorfkiste)** und
vier Laternenpfähle. Liegt schon eine Doppelkiste im Umkreis von 12 Blöcken, wird sie übernommen.

## Häuser wie in der Umgebung – nur bei Bevölkerungswachstum
**Wann:** Ein neues Haus wird nur gebaut, wenn die Bevölkerung wächst: wenn jemand kein Bett hat, oder alle Betten belegt sind *und*
es mehr Einwohner gibt als beim letzten Hausbau. Vorher passiert nichts (Brunnen/Felder siehe unten).
**Wie:** Der Maurer wählt zufällig ein bestehendes Haus des Dorfes (um ein Bett herum) und **kopiert es Block für Block**: gleiche Form, Dach,
Fenster, Materialien – auch bei Mod-Dörfern. Das Haus wird so gedreht, dass die Tür zur Dorfmitte zeigt. Blöcke mit Blockentity (Truhen,
Schilder, Arbeitsblöcke) werden nicht kopiert; die eigene Kiste und das Schild setzen die Villager nach dem Einzug selbst.
Findet sich kein abgegrenztes Haus, baut der Maurer ein Standardhaus im erkannten Dorfstil (Boden, Wände, Dach, Treppen, Tür).
**Material:** Die Dorfkiste muss die benötigten Blöcke enthalten (exakt die des Vorbildhauses; Betten und Holztüren beliebig). Fehlt etwas, bekommen
Spieler in der Nähe eine Meldung mit den größten Lücken. `Config.MATERIAL_COST_PERCENT` senkt den Anteil, der wirklich bezahlt werden muss (0 = gratis).
Danach legt der Maurer einen Pfad zur Glocke. Weitere Bauwerke: Brunnen (8 Wandblöcke), Felder (7×7 mit Wasser, Saatgut ≥16 in der Kiste, bis zu 3).
Der Bauauftrag liegt an der Glocke und überlebt Neustarts.
## Handelsmenü: Info-Knopf und Befinden
Im Handelsmenü sitzt über dem Fenster ein Knopf **„Info“**. Er blendet links neben dem Menü den **Steckbrief** des Villagers ein (live, alle 1 s aktualisiert):
Name, Beruf und Stufe, aktuelle Tätigkeit, **Stimmung, Sättigung, Energie, Soziales, Gesundheit** (mit Balken), Haus (mit eigener Kiste), Besitz (Smaragde),
Ruf bei dir, Geschenke, erledigte Arbeiten, Gespräche und ob die Preise gerade Rabatt/Aufschlag haben.
Dafür muss die Mod **auch auf dem Client** installiert sein (der Server liefert die Daten; ohne Client-Mod gibt es nur den Knopf nicht).

Das Befinden ist keine Deko, sondern echte Logik:
- **Sättigung** fällt seit der letzten Mahlzeit (30 000 Ticks bis 0). Unter 25 % isst der Villager zwischendurch einen Happen aus dem Inventar.
- **Energie** fällt seit dem letzten Aufwachen (20 000 Ticks bis 0), Schlaf füllt sie.
- **Soziales** fällt seit dem letzten Gespräch (15 000 Ticks bis 0), Plauschen in den Pausen füllt es.
- **Stimmung** = 30 % Sättigung, 25 % Energie, 20 % Soziales, 15 % Haus, 10 % Gesundheit, dazu bis +10 für Besitz (Smaragde).
- Wirkung: Stimmung < 50 % → er arbeitet nur noch 3/4, < 35 % → 1/2, < 20 % → 1/4 der Zeit.
- **Preise:** ab 80 % Stimmung kleiner Rabatt (5 %), unter 25 % Aufschlag (10 %).
- **Handel:** unter 10 % Stimmung verweigert der Villager den Handel („zu erschöpft und hungrig“).
- Schleichen + Rechtsklick mit leerer Hand zeigt weiterhin eine Kurzfassung im Chat-Overlay.
## Rohstoffe: Mine, Holzfällen, Werkstatt
Baumaterial kommt nicht aus dem Nichts. Fehlt der Dorfkiste etwas für einen Bau, trägt das Dorf es auf eine **Wunschliste** ein, und die Dorfbewohner besorgen es:
- **Mine:** Das Dorf legt abseits der Siedlung eine Mine an (Eingang mit Laterne und Schild „Dorfmine“, Treppe nach unten, dann ein Stollen). Maurer (Stein) und Schmiede (Eisen/Kohle) bauen die Blöcke **wirklich ab**: Stein, Erde, Kies, Erze samt angrenzenden Erzadern. Wasser, Lava, Spawner oder herabfallende Blöcke beenden den Stollen, danach wird eine neue Mine angelegt.
- **Holzfällen:** Der Pfeilmacher fällt echte Bäume (von oben nach unten) und pflanzt einen Setzling nach, wenn er einen hat.
- **Werkstatt:** Aus Rohstoffen stellen Maurer/Pfeilmacher nach Item-Namen her: Bretter aus Stämmen, Treppen/Halbstufen/Mauern/Zäune/Tore/Türen aus dem Grundblock, Glasscheiben aus Glas, Steinziegel aus Stein, Stein aus Bruchstein bzw. Glas aus Sand (mit Kohle). Das klappt auch für Mod-Blöcke, die der Vanilla-Benennung folgen.

## Stadtentwicklung
| Einwohner | Bauwerk |
|---|---|
| ab 3 | Brunnen |
| ab 6 (und ≥ 1 Feld) | **Tiergehege** (9×9 Zaun mit Tor) |
| ab 12, alle haben ein Haus | **Rathaus** (9×7, hohes Satteldach, im Stil des Dorfes) |
| ab 20, Rathaus steht, Dorf **nicht zu klein und nicht zu verstreut** (Häuser-Radius 14–32) | **Stadtmauer** rund um das Dorf: 5 Blöcke hoch mit Zinnen und Laternen, **Ecktürme (9 Blöcke)**, vier **Tore mit Torbogen** zwischen Tortürmen in der Mitte jeder Seite |
| laufend, nach Dorfreichtum | **Reichtums-Aufbauten**: Laternenpfähle → Marktstand → Denkmal → Springbrunnen → Festplatz |
Häuser entstehen nur bei Bevölkerungswachstum (siehe oben). Alles wird Block für Block gebaut, mit Material aus der Dorfkiste.

## Felder wachsen mit dem Dorf
Der Bauer legt bis zu 6 Felder an (ca. 1 pro 4 Einwohner). Das 1.–2. Feld ist 7×7, das 3.–4. 9×9, ab dem 5. 11×11 (größere Felder bekommen vier Wasserstellen).

## Dorfreichtum
Punktwert aus Einwohnern, Häusern, Feldern, Brunnen, Rathaus, Mauer, Gehege und den Schätzen der Dorfkiste (Smaragde, Diamanten, Gold, Eisen). Bei 60 / 110 / 170 / 250 / 350 Punkten stellen die Maurer jeweils einen repräsentativen Aufbau nahe der Dorfmitte auf (siehe Tabelle). Der Wert steht im Info-Panel und in /villagerplus status.

## Gäste aus fremden Dörfern
Etwa alle 1–1,5 Tage (ab 4 Einwohnern) kommt ein **einzelner Gast** oder – in 30 % der Fälle – eine **Reisegruppe (2–4 Personen)** in der Tracht anderer Biome. Sie gehen zur Dorfkiste, bringen Gastgeschenke (Smaragde + Waren passend zum Beruf), bleiben einen halben Tag (man kann mit ihnen handeln) und ziehen weiter. Gibt es ein freies Bett, lässt sich ein Gast mit 35 % Chance dauerhaft nieder. Gäste arbeiten nicht und zählen nicht zur Bevölkerung.

## Handelskarawane (wandernder Händler)
Alle ~1,5 Tage (ab 5 Einwohnern) kommt ein wandernder Händler zur Dorfkiste und tauscht mit einem **„imaginären“ Dorf** Ressourcen:
er kauft Überschuss (Erde, Kies, Bruchstein über 288, Erze, Nahrung, Wolle …) gegen Smaragde und verkauft Fehlendes von der Wunschliste, Saatgut, Setzlinge fürs Holzfällen, Fackeln – und liefert die ersten Tiere für das Gehege. Danach zieht er weiter.

## Tierhaltung
Im Gehege füttern Dorfbewohner Kühe/Schafe (Weizen), Schweine (Karotten) und Hühner (Samen) aus der Dorfkiste, die Tiere vermehren sich. Der Metzger hält den Bestand klein (>8 je Art), der Schäfer schert die Schafe.

## Animationen
Alle Tätigkeiten sind sichtbar: Der Dorfbewohner hält das passende Werkzeug/Gegenstand in der Hand (Hacke, Schere, Spitzhacke, Axt, Angel, Schwert, Goldapfel, Nahrung beim Essen, Baumaterial beim Bauen …), schwingt den Arm, es gibt Block-Bruch-, Angel-, Herz-, Essens- und Gesprächspartikel samt Geräuschen. Eigene Körperposen (Sitzen, Hämmern) gibt es nicht – das würde eigene Client-Modelle erfordern.

## Befehle
- `/villagerplus status` listet Dorfbewohner (Tätigkeit, Stimmung, Haus) und das Dorf (Kiste, Bauten, Auftrag, Mine, Wunschliste) in der Nähe.
- `/villagerplus tafel [uebersicht|details|expeditionen]` zeigt die Dorftafel (siehe unten) auch per Befehl.

## Neu in 0.7.0
**Dorftafel** (Schild neben der Dorfkiste, Rechtsklick): Reiter **Übersicht** (farbige Draufsicht-Karte des Dorfes mit Gebäuden, Feldern, Wasser, Wegen, Wald, Glocke/Kiste/Tafel/Schule/Lager/Gehege/Mine und den Dorfbewohnern, dazu Balkendiagramme für Einwohner, Stimmung, Reichtum und die Vorräte Essen/Holz/Stein/Metall), **Details** (alle Zahlen) und **Expeditionen**. Die Reiter sind anklickbar; Schleichen + Rechtsklick öffnet direkt die Expeditionen.
**Expeditionen:** Alle ~1,5 Tage (ab 6 Einwohnern) brechen 1–3 Villager (Beruf nötig, mind. 14 ♥) zu Ruinen, Höhlen oder verlassenen Festungen auf und sind einen halben Tag fort. Danach zählen Gesundheit und Fertigkeitsstufe: Sie kommen verletzt zurück – oder gar nicht (Stufe 3 = Festung, Hinterhalte). Heimkehrer bringen **Beute** mit (Dorfkiste, sonst Lager). Heilung im Schlaf. Stand und Berichte stehen im Reiter „Expeditionen“.
**Fertigkeiten** (langsam): Stufen bei 150 / 500 / 1200 / 2600 / 5000 erledigten Aufgaben, je Stufe +12 % Tempo und Zusatzerträge.
**Schule:** Jedes Dorf baut ab 5 Einwohnern früh eine kleine Schule (aus eigenem Material). Kinder wachsen **3× langsamer** auf und gehen tagsüber zur Schule; ihr Unterricht zählt als Erfahrung für später.
**Lager:** Ist die Dorfkiste voll, baut das Dorf einen **Lagerschuppen** (7 Truhen) und bringt Überschuss dorthin; sind alle voll, wird er durch Anbauten **erweitert**. Fehlt der Dorfkiste etwas aus der Wunschliste, holen die Maurer es aus dem Lager. Es gibt keine Mengenlimits und nichts wird weggeworfen.
**Ausgewogene Vorräte:** Holzfäller und Bergleute beschaffen, was im Vergleich zu Essen/Holz/Stein/Metall knapp ist (statt fester Zielmengen).
**Smaragde bleiben knapp:** Löhne kommen aus der Dorfkasse, Gäste bringen nur selten einen, der Händler zahlt höchstens 6 pro Besuch.
**Stollen statt Tunnel:** Mine mit wechselnder Breite/Höhe, Seitenstollen, Holzstützen mit Deckenbalken und Fackeln; vor jedem Betreten wird der Boden geprüft (kein Absturz in Höhlen).
**Händler als Quelle:** Material, das das Dorf nicht herstellen kann (z. B. bunte Terrakotta), kauft die Karawane. Klappt das nicht, weicht der Hausplan auf beschaffbares Ersatzmaterial aus.
**Mehr Abwechslung:** Neue Häuser kopieren eines der 6 nächsten Häuser (zufällig) und werden zufällig gespiegelt.
**Dauerhaft geladen:** Dörfer (7×7 Chunks um die Glocke) und ihre aktive Mine werden per Force-Load geladen gehalten (`Config.KEEP_VILLAGES_LOADED`), damit alles auch ohne Spieler weiterläuft.

**Leistung:** Zeitbudget je Tick (Config.TICK_BUDGET_NANOS), automatisch seltenere Taktung bei Serverlast, Zwischenspeicher für Dorfsuche/Vorräte/Lager, spaltenweise Baumsuche. /villagerplus perf zeigt die Messwerte (zuletzt Ø 21–36 µs je Tick bei 33–155 Villagern).
**Berufsnachbesetzung:** Fällt der letzte Maurer/Pfeilmacher/Bauer aus, übernimmt ein Arbeitsloser oder Überzähliger (ab 8 Einwohnern).
Den aktuellen Stand und die Funktionsübersicht zeigt die [Projektseite](https://cvvluis.github.io/Villager-Repurposed/).

## Kompatibilität mit anderen Mods
- Unterklassen von `Villager` und eigene Namen aus anderen Mods werden mitverwaltet.
- Fremde Berufe: per Datapack-Tag `villagerplus:job/<farmer|shepherd|butcher|smith|cleric|mason|fisherman|fletcher|leatherworker|cartographer|librarian>` (Registry `villager_profession`) einer Tätigkeit zuordnen; ohne Tag erkennt die Mod Schlüsselwörter im Berufsnamen („farm“, „smith“ …), sonst arbeitet der Villager als Träger.
- Entity-Tag `villagerplus:ignored`: Villager-Typen, die nicht verwaltet werden sollen.
- Holz/Wolle anderer Mods gilt über die Vanilla-Block-Tags als beschaffbar. Die Mixins nutzen `@WrapOperation` und sind optional (`require = 0`).
- Getestet mit Overhauled Villages (CTOV) 4.1.0 (+ Lithostitched, Cloth Config).

## Kompatibilität mit Dörfern aus Weltgenerator-Mods (z. B. Overhauled Villages)
Häuser werden über das **Struktur-Teil** (Jigsaw-Piece) des nächstgelegenen Hauses abgegrenzt und 1:1 kopiert (Form, Dach, Materialien), egal wie groß oder verschachtelt. Hügeliges Gelände wird eingeebnet. Beute-Kisten der Dörfer werden nie als Dorfkiste übernommen.
## Gedächtnis
- Vanilla-Ruf/Klatsch verfällt 4× langsamer (`Config.GOSSIP_DECAY_DIVISOR`).
- Jeder Villager merkt sich dauerhaft Name, Haus, Tür, Schild, Kisten, Arbeitsplätze, erledigte Arbeiten und Geschenke je Spieler.
- **Schleichen + Rechtsklick mit Smaragd/Diamant/Brot/Goldkarotte/Kuchen** = Geschenk (verbessert dauerhaft den Ruf → bessere Preise).
- **Schleichen + Rechtsklick mit leerer Hand** = Name, Beruf, aktuelle Tätigkeit, Haus, Geschenke, Arbeiten.

## Bauen
`gradlew.bat build` mit JDK 25 → `build/libs/villagers-repurposed-0.7.2.jar` in den `mods`-Ordner. Werte stehen in `Config.java`.
