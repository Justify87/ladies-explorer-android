# Arbeitsregister

Einzige operative Quelle für offene Aufgaben, Reihenfolge, Status und Abnahme. Erledigte oder verworfene Aufgaben stehen ausschließlich in Git und den zugehörigen PRs. Maßgebliche Verträge: [PRODUCT](PRODUCT.md), [ARCHITECTURE](ARCHITECTURE.md), [DESIGN](DESIGN.md). Arbeitsweise: [AGENTS](../AGENTS.md); Prüfungen: [README](../README.md#prüfung--ci).

## Nächster Entwicklungsschritt

M12.1. L01 ist unabhängig vor weiterer öffentlicher Distribution zu klären.

## M12 — Unified Profile Experience: Compact Information Architecture

Gemeinsame Profilansicht unterhalb der Galerie in Swipe und Standalone gemäß [DESIGN](DESIGN.md#informationshierarchie--compact-profile) vollständig neu gestalten. Das freigegebene Soll ersetzt den bisherigen Profil-Akkordeonaufbau. Fachlicher Umfang, Datenhoheit, Integrität, Medienvorbereitung und bestehende lokale Bearbeitungsfunktionen bleiben erhalten. Umsetzung seriell auf integriertem Stand; Designvertrag ist maßgeblich, diese Einträge definieren Umfang und Abnahme.

### M12.1 — Gemeinsamer kompakter Profilfluss und Informationskopf

**Status:** READY.

**Umfang:** Bestehende Profilprojektion, `ProfileShell`, `ProfileFlow`, `ProfilePanels` und tatsächliche Aufrufer gegen das neue Soll prüfen. Gemeinsame flache Abschnittsstruktur, kompakte Identität/Kontakte, zentrale Merkmale und eindeutige Status-/Fehlerdarstellung einführen; semantische Gruppierung ausschließlich vorhandener Daten. Den alten strukturellen Rahmen ersetzen, keine zweite Profilimplementierung. Galerie- und fachliche Operationsverträge unverändert lassen.

**Akzeptanz:** Beide Profilkontexte zeigen dieselben gültigen Daten ohne doppelte Identität, ungültige Kontaktziele, Quellenstatus als Live-Verfügbarkeit oder sichtbare Layoutsprünge. Alle bisherigen Felder und Aktionen bleiben während der Integration erreichbar. Keine neuen Quellenrequests oder Medieninitialisierung durch bloßes Lesen.

**Prüfung:** Gezielte JVM-/native UI-Regressionen für Listing-/Owner-/Snapshot-Bezug, fehlende Werte, Kontakt-/Statussemantik, Fehler/Retry und gemeinsamen Profilfluss; tatsächliche Aufrufer und stabilen Präsentationszustand reviewen. Betroffene technische Ist-Beschreibung in ARCHITECTURE nach Umsetzung konsistent halten.

### M12.2 — Strukturierte Profildaten und verlustfreie Verdichtung

**Status:** PLANNED. Abhängig von M12.1.

**Umfang:** Dienste, übrige Merkmale, Zeitangaben und vorhandenen Preiskontext gemäß Designvertrag in direkt sichtbare, kompakte Sachinformationen überführen; tatsächliche Quellgruppen unverändert erhalten. Suchübereinstimmung verständlich verdichten, vollständige Gruppenbelege und lokale Kriterien zugänglich lassen. Keine neue Quelle, Preisextraktion, Taxonomie oder fachliche Filterlogik.

**Akzeptanz:** Keine standardmäßig geschlossenen Akkordeons für reguläre strukturierte Profildaten; vollständige Werte einschließlich langer und zusätzlicher Kategorien, belegter Negativwerte und unbekannter Evidenz. Chronologische Zeiten und verlustfreie Verdichtung identischer Intervalle; abweichende/mehrteilige Zeiten, Feiertage und Freitext bleiben erreichbar. Keine geschätzten Werte, künstlichen Scores oder doppelten Merkmale; manuelle Preislisten und bezahlte Beträge mit eindeutiger Herkunft getrennt darstellen.

**Prüfung:** Synthetische vollständige und unvollständige Profile, variable Kategorien, lange Werte, unbekannte Felder, Suchgruppen/Alternativen, identische und unterschiedliche Zeitangaben einschließlich Mitternacht sowie Herkunft und Trennung der lokalen Preisangaben. JVM-Regressionen für Präsentationsableitungen, native Bounds/Umbruch in beiden Themes und bei Schriftvergrößerung; fachliche Datenvollständigkeit gegen den vorhandenen Projektionsumfang prüfen.

### M12.3 — Lesezustand, sekundäre Details und lokale Funktionen

**Status:** PLANNED. Abhängig von M12.2.

**Umfang:** Kontextabhängiger kompakter Identitätskopf, Root-Navigation und einzelne Decision Rail; Galerie-Rückkehr und Vollbildzugang erhalten. Originalbeschreibung, vollständige Nachweise, Historie, verknüpfte Anzeigen und persönliche Funktionen gemäß Designvertrag neu einordnen. Profilaktionen und Deckverwaltung semantisch trennen. Bestehende Editoren, Read-/Commit-/Konflikt- und Merge-Verträge weiterverwenden. Scroll-Anker nur bei nachgewiesenem Bedarf, keine zweite Profilroute oder zweite Navigation für Inhaltsabschnitte.

**Akzeptanz:** Lesemodus schafft zusätzlichen nutzbaren Raum, alle Aktionen bleiben zugänglich; Back, Rotation, Font Scale, IME, kurze Fenster, Fehler und Owner-Wechsel erhalten gültige Scroll-/Galeriepositionen und Zustände. Keine Entscheidungsgeste in Details, keine neuen Quellenabrufe, keine vorgezogenen privaten Reads, keine Entwurfsverluste oder parallelen Editoren. Root-Ziele durch eindeutige Rückkehr zum Hero erreichbar; Standalone ohne Deck-Rail. Originaltext, Verlauf, Filterbelege, lokale Datensätze und alle bisherigen Verwaltungsaktionen vollständig erreichbar.

**Prüfung:** JVM- und Instrumentierungsregressionen für Hero-/Leseübergang, Rail-/Root-Bounds, Back/Undo, Lifecycle, Galerie-/Medienzustand, lokale Reads, Entwürfe, CAS, Konflikt/Retry und explizite Identitätsoperationen; Aufruferreview einschließlich Grid/Karte/Gespeichert. Betroffene veraltete Profil-UI und Tests entfernen, Suchfilter-Akkordeons erhalten.

### M12.4 / G12 — Integriertes Compact-Profile-Abschlussreview

**Status:** PLANNED. Abhängig von M12.1–M12.3.

**Umfang:** Gesamten integrierten M12-Umfang gegen DESIGN, PRODUCT, ARCHITECTURE und AGENTS prüfen. Vollständige Feld-/Funktionszuordnung beider Profilkontexte, semantische Dokumentationskonsistenz, abgelöste Pfade, native Layoutmessungen und Regressionen reviewen. Konkrete Befunde ursächlich beheben; keine unabhängigen Erweiterungen.

**Akzeptanz:** Einheitlicher flacher Profilfluss, unmittelbar lesbare strukturierte Angaben und vollständiger Zugriff auf sekundäre Inhalte ohne Datenverlust. Native Vergleichsbelege für Informationsdichte/Interaktionsaufwand und gemessene Bounds, keine starre Einsparquote. Dark/Light, 360 × 740, 390 × 844, 430 × 932 und 600 dp, Font Scale 1/1,3/2, kurze Fenster, Rotation, IME, TalkBack-/Fokussemantik und mindestens 48-dp-Touchziele risikogerecht prüfen. Galerie-/Prefetch-Stabilität, Run-/Owner-Admission, Entscheidungen, Undo, lokale Editoren und explizite Quellenaktionen unverändert.

**Prüfung:** Vollständiges CI-Gate gemäß README, relevante Rust-/JVM-/Architekturtests, Lint sowie ARM64-Debug- und Instrumentierungs-APK. Tatsächlich ausgeführte Prüfungen, gebaute Artefakte und nicht ausgeführte Geräteprüfungen getrennt belegen; Geräteaufgaben gemäß Merge-Regel. Abschluss und Belege nur bei entsprechendem tatsächlichem Ergebnis gemäß [Pflege](#pflege).

## Unabhängige Aufgaben

### L01 — Lizenzklärung

**Status:** READY.

libmpv/FFmpeg-AAR-Lizenz, enthaltene Bibliotheken und ABIs, Corresponding Source und Weiterverteilungspflichten einschließlich bereits öffentlich verfügbarer Debug-Artefakte klären ([NOTICE](../NOTICE)). Keine Lizenzfreigabe oder Behauptung eines Rechtsverstoßes allein aus der Wrapper-Lizenz. Vor weiterer öffentlicher Distribution klären, unabhängig vom lokalen Projektabschluss.

## Pflege

**Merge-Regel:** Mit dem Merge eines PR sind sämtliche Geräteprüf- und Geräteabnahmeaufgaben seines integrierten Umfangs abgeschlossen. Keine separaten Gerätestatus, Nachabnahmen oder Verschiebungen in spätere Meilensteine. Noch nicht implementierte Funktionen und konkrete offene Fehler bleiben reguläre Aufgaben. Die technische Prüfung bleibt Bestandteil der Umsetzung; tatsächliche Geräteausführung nur bei entsprechendem Nachweis behaupten.

| Status | Bedeutung |
| --- | --- |
| `READY` | Ausführbar |
| `DOING` | Tatsächlich in Arbeit; Branch/PR nennen |
| `REVIEW` | Zur Prüfung |
| `BLOCKED` | Blocker und nächsten Schritt benennen |
| `PLANNED` | Abhängig/vorläufig, nicht freigegeben |

- Nur offene Aufgaben und ihre noch relevanten Abhängigkeiten führen. Jede eigenständig ausführbare Aufgabe besitzt eine stabile, nicht wiederverwendete ID, eigene Überschrift, einen maßgeblichen Status sowie risikogerechte Angaben zu Umfang, Akzeptanz und Prüfung.
- Neue Aufgaben ausschließlich hier erfassen. Aufgabenwahl nach [AGENTS](../AGENTS.md#arbeitszyklus) auf integriertem Stand; erforderliche Nutzerfreigaben bleiben verbindlich. Nächsten Entwicklungsschritt und Abhängigkeiten konsistent halten. Bei Unterbrechung Stand, nächsten Schritt oder Blocker am Eintrag festhalten.
- Bei freigegebenem Abschluss die erfüllten Kriterien und konkreten Prüf-/Reviewbelege im PR dokumentieren. Weiterhin gültige Erkenntnisse in den maßgeblichen Vertragsdokumenten sichern. Den abgeschlossenen Eintrag einschließlich erledigter Unteraufgaben im selben zur Integration bestimmten PR vollständig entfernen; die verbleibenden Abhängigkeiten und Status entsprechend anpassen. Wenn alle Aufgaben eines Meilensteins einschließlich Gate abgeschlossen sind, auch seine Überschrift und Beschreibung entfernen. Erst der Merge macht die vorbereitete Entfernung wirksam.
- Nutzerseitig verworfene Aufgaben ebenfalls vollständig entfernen; die Entscheidung im PR belegen. Keine abgeschlossenen oder verworfenen Einträge, DONE-Tabellen, Chroniken, historischen Linksammlungen oder parallelen Aufgabenregister anlegen. Git und PRs bewahren die Historie. Abwesende IDs nicht als neue Aufgaben interpretieren oder ohne Auftrag wiedereröffnen; falls ein Abhängigkeitsnachweis nötig ist, den integrierten Stand und den betreffenden PR gezielt prüfen.
- Ein unabhängiger offener Blocker oder eine Freigabebedingung bleibt bis zur tatsächlichen Klärung erhalten. Keine Abschlussquote aus ungleich großen Aufgaben.
