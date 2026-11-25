# Arbeitsregister

Einzige operative Quelle für offene Aufgaben, Reihenfolge, Status und Abnahme. Erledigte oder verworfene Aufgaben stehen ausschließlich in Git und den zugehörigen PRs. Maßgebliche Verträge: [PRODUCT](PRODUCT.md), [ARCHITECTURE](ARCHITECTURE.md), [DESIGN](DESIGN.md). Arbeitsweise: [AGENTS](../AGENTS.md); Prüfungen: [README](../README.md#prüfung--ci).

## Nächster Entwicklungsschritt

M12.2. L01 ist unabhängig vor weiterer öffentlicher Distribution zu klären.

## M12 — Unified Profile Experience: Compact Information Architecture

Gemeinsame Profilansicht unterhalb der Galerie in Swipe und Standalone gemäß [DESIGN](DESIGN.md#informationshierarchie--compact-profile) vollständig neu gestalten. Das freigegebene Soll ersetzt den bisherigen Profil-Akkordeonaufbau. Fachlicher Umfang, Datenhoheit, Integrität, Medienvorbereitung und bestehende lokale Bearbeitungsfunktionen bleiben erhalten. Umsetzung seriell auf integriertem Stand. DESIGN beschreibt das gemeinsame Ziel; je Teilaufgabe gelten ihr abgegrenzter Umfang und die zugehörigen Abnahmekriterien. Bis zur vollständigen Umsetzung bleiben noch nicht umgestellte Inhalte und Interaktionen in ihren funktionierenden bestehenden Pfaden erreichbar. Keine temporären Parallelpfade oder vorgezogenen Umbauten; vollständige Designkonformität erst nach den Implementierungsaufgaben, integriert zu prüfen im Abschlussreview.

### M12.2 — Strukturierte Profildaten und verlustfreie Verdichtung

**Status:** REVIEW. PR #125, Branch `feat/m12-2-structured-profile`. Unabhängiges Review dokumentiert; R01–R07 korrigiert. Vollständiges CI-Gate für den Korrekturstand und Merge-Freigabe ausstehend.

**Umfang:** Zentrale und weitere Merkmale, Dienste, Zeitangaben und Suchübereinstimmung gemäß DESIGN im gemeinsamen Rahmen darstellen und verlustfrei verdichten; tatsächliche Quellgruppen und vollständige Filterbelege erhalten. Den vorhandenen Preiskontext in die vorgesehene Reihenfolge einordnen und vorhandene Preisansichten weiterverwenden. Keine Änderung der Navigations-/Editorzustände, keine neue Quelle, Preisextraktion, Taxonomie oder fachliche Filterlogik.

**Akzeptanz:** Keine standardmäßig geschlossenen Akkordeons für reguläre strukturierte Profildaten; vollständige Werte einschließlich langer und zusätzlicher Kategorien, belegter Negativwerte und unbekannter Evidenz. Chronologische Zeiten und verlustfreie Verdichtung identischer Intervalle; abweichende/mehrteilige Zeiten, Feiertage und Freitext bleiben erreichbar. Keine geschätzten Werte, künstlichen Scores oder doppelten Merkmale; manuelle Preislisten und bezahlte Beträge mit eindeutiger Herkunft getrennt darstellen.

**Prüfung:** Synthetische vollständige und unvollständige Profile, variable Kategorien, lange Werte, unbekannte Felder, Suchgruppen/Alternativen, identische und unterschiedliche Zeitangaben einschließlich Mitternacht sowie Herkunft und Trennung der lokalen Preisangaben. JVM-Regressionen für Präsentationsableitungen, native Bounds/Umbruch in beiden Themes und bei Schriftvergrößerung; fachliche Datenvollständigkeit gegen den vorhandenen Projektionsumfang prüfen.

### M12.3 — Lesezustand, sekundäre Details und lokale Funktionen

**Status:** PLANNED. Abhängig von M12.2.

**Umfang:** Vorhandenen Identitätskopf um den Lesezustand, kontextabhängige Root-Navigation und kompakte einzelne Decision Rail ergänzen; Galerie-Rückkehr und Vollbildzugang erhalten. Zugänge zu Originalbeschreibung, vollständigen Nachweisen, Historie, verknüpften Anzeigen und privaten Funktionen einschließlich Preisbereich gemäß DESIGN neu einordnen; vorhandene strukturierte Datenkomponenten weiterverwenden. Profilaktionen und Deckverwaltung semantisch trennen. Bestehende Editoren, Read-/Commit-/Konflikt- und Merge-Verträge weiterverwenden. Scroll-Anker nur bei nachgewiesenem Bedarf, keine zweite Profilroute oder zweite Navigation für Inhaltsabschnitte.

**Akzeptanz:** Lesemodus schafft zusätzlichen nutzbaren Raum ohne Rückkopplung durch geänderte Viewport-/Hero-Geometrie; stabile Scrollanker, Medienidentität und Aktionsziele bei beiden Übergangsrichtungen und Restore direkt im Lesemodus gemäß DESIGN. Back, Rotation, Font Scale, IME, kurze Fenster, Fehler und Owner-Wechsel beachten. Keine Entscheidungsgeste in Details, keine zusätzlichen Profil-Quellabrufe oder unnötige Medienvorbereitung, keine vorgezogenen privaten Reads, Entwurfsverluste oder parallelen Editoren. Root-Ziele durch eindeutige Rückkehr zum Hero erreichbar; Standalone ohne Deck-Rail, aber mit eigenen Präferenz-/Neutral- und Favoritenaktionen. Originaltext, Verlauf, Filterbelege, lokale Datensätze und alle bisherigen Verwaltungsaktionen vollständig erreichbar.

**Prüfung:** JVM- und Instrumentierungsregressionen für beide Hero-/Leseübergänge und Restore im Lesemodus einschließlich Rückkopplungsfreiheit, stabiler Row-Keys/Offsets, Rail-/Root-/Hero-Bounds und einmaliger Insets, Back/Undo, Lifecycle, Galerie-/Medienzustand einschließlich zulässiger Video-Player-/Surface-Wiederherstellung, lokale Reads, Entwürfe, CAS, Konflikt/Retry und explizite Identitätsoperationen; Aufruferreview einschließlich Grid/Karte/Gespeichert. Betroffene veraltete Profil-UI und Tests entfernen, Suchfilter-Akkordeons erhalten.

### M12.4 / G12 — Integriertes Compact-Profile-Abschlussreview

**Status:** PLANNED. Abhängig von M12.3.

**Umfang:** Gesamten integrierten M12-Umfang gegen DESIGN, PRODUCT, ARCHITECTURE und AGENTS prüfen. Vollständige Feld-/Aktionszuordnung beider Profilkontexte gegen den tatsächlichen Projektions- und Aufruferumfang (einschließlich Anzeigenanzahl, Analyse-/Identitätsevidenz, Standalone-Präferenz/Neutral/Favorit und Swipe-Deckaktionen), semantische Dokumentationskonsistenz, abgelöste Pfade, native Layoutmessungen und Regressionen reviewen. Konkrete Befunde ursächlich beheben; keine unabhängigen Erweiterungen.

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
- Neue Aufgaben ausschließlich hier erfassen. Aufgabenwahl nach [AGENTS](../AGENTS.md#arbeitszyklus) auf integriertem Stand; erforderliche Nutzerfreigaben bleiben verbindlich. Nächsten regulären Entwicklungsschritt ausdrücklich benennen oder bei fehlender freigegebener ausführbarer Aufgabe „Kein regulärer Entwicklungsschritt“ vermerken und den automatischen Arbeitsauftrag beenden. Unabhängige Aufgaben nur bei einschlägigem Auftrag oder tatsächlich relevanter Freigabebedingung beginnen; ihr Status READY allein genügt nicht. Bei Unterbrechung Stand, nächsten Schritt oder Blocker am Eintrag festhalten.
- Bei freigegebenem Abschluss die erfüllten Kriterien und konkreten Prüf-/Reviewbelege im PR dokumentieren. Weiterhin gültige Erkenntnisse in den maßgeblichen Vertragsdokumenten sichern. Den abgeschlossenen Eintrag einschließlich erledigter Unteraufgaben im selben zur Integration bestimmten PR vollständig entfernen; erfüllte Abhängigkeiten sowie obsolete Verweise auf entfernte Einträge auch in Beschreibungen und verlinkenden Dokumenten entfernen, verbleibende Abhängigkeiten und den nächsten Entwicklungsschritt konsistent aktualisieren. Nachfolgende Aufgaben nur bei erfüllten Voraussetzungen und vorhandener Freigabe auf READY setzen; sonst PLANNED bzw. BLOCKED belassen und Grund benennen. Wenn alle Aufgaben eines Meilensteins einschließlich Gate abgeschlossen sind, auch seine Überschrift und Beschreibung entfernen. Erst der Merge macht die vorbereitete Entfernung und Statusfortschreibung wirksam.
- Nutzerseitig verworfene Aufgaben ebenfalls vollständig entfernen; die Entscheidung im PR belegen. Keine abgeschlossenen oder verworfenen Einträge, DONE-Tabellen, Chroniken, historischen Linksammlungen oder parallelen Aufgabenregister anlegen. Git und PRs bewahren die Historie. Abwesende IDs nicht als neue Aufgaben interpretieren oder ohne Auftrag wiedereröffnen; falls ein Abhängigkeitsnachweis nötig ist, den integrierten Stand und den betreffenden PR gezielt prüfen.
- Ein unabhängiger offener Blocker oder eine Freigabebedingung bleibt bis zur tatsächlichen Klärung erhalten. Keine Abschlussquote aus ungleich großen Aufgaben.
