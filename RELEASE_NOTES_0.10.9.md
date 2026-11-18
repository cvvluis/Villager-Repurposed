# Villagers Repurposed 0.10.9

Fabric · Minecraft 26.2 · Java 25

Ausgabe mit Fokus auf Leistung: weniger Villager-Geometrie beim Rendern und weniger Serverarbeit beim Bauplan-Spitzhacken-Check.

## Rollen-Outfit: ein Modell, eine Textur

Rollen-Dorfbewohner wurden bisher zweimal gezeichnet (Basismodell plus Rollen-Layer). Mit Shadern wie Iris verdoppelte das auch den Schattenpass. Jetzt wird die Rolle in **eine** dynamische Gesamt-Textur gelegt (Vanille-Basis plus Rollen-Overlay, beim Ressourcen-Reload). Biome-Kleidung entfällt bei Rollen absichtlich — das Outfit sieht überall gleich aus.

## Bauplan: Spitzhacken-Prüfung gedrosselt

Wenn ein Plan Bruchstein braucht, aber noch keine Spitzhacke da ist, hat der Server jede Tick alle Kisten und Rezepte durchsucht — auch ohne Änderung am Inventar. Die Ergebnisprüfung wird für ein paar Sekunden gecacht und pro Aufruf nur noch einmal über die Lager gescannt.

## Hinweis

`gradle build` ist auf diesem Stand durchgelaufen. Im Spiel nicht getestet: Rolle sichtbar wie zuvor (ohne Biom-Textur), Spitzhacke noch fristgerecht bei gelieferten Materialien.