# Roadmap und Abnahmestand

Stand des Ist-Abgleichs: 18.11.2026, [main 7d768e5b](https://github.com/Justify87/ladies-explorer-android/commit/7d768e5bac9ddf0239de904e21f120e82c8db67a). Diese Roadmap rekonstruiert den ursprünglichen Android-Migrationsplan M00–M07 aus dem Projektverlauf vom 17.11.2026 und den gemergten Meilensteinen. Sie ist der Soll-/Fortschrittsindex, nicht eine zweite technische Spezifikation. Die späteren Diagnose-Meilensteine M0–M4 sind ein eigener Arbeitsstrang, keine Umnummerierung des Hauptplans.

## Verbindliches Ziel

Eine eigenständige, lokal genutzte Android-App auf einem Gerät, mit Kotlin/Compose und eigenem Rust/SQLite-Core über UniFFI. Suche, Extraktion und Persistenz im App-Prozess; externe Quellen nur für die jeweils benötigten Netzwerkfunktionen. Selektive Übernahme der technischen Artefact-Basis, kein Artefact-Fork und keine fachfremden Dokument-/OCR-/PDF-Funktionen. Relevante fachliche Daten und Regeln der alten Web-App erhalten, nicht den Flask/Python/Messaging-Stack. Kein integrierter WhatsApp-/Messenger-Client, keine Synchronisierung oder automatische KI-/OCR-Preisextraktion; WhatsApp nur extern öffnen. KISS für ein Hobbyprojekt.

## Hauptplan M00–M07: Soll und tatsächliche PR-Zuordnung

Die linke Spalte verwendet die ursprüngliche Planung vom 17.11.2026; die PR-Spalte verwendet die späteren tatsächlichen Implementierungsbezeichnungen. Diese Nummern sind insbesondere für M00–M02 nicht austauschbar.

| Ursprüngliches Soll | Tatsächliche Umsetzung in main | Rest / Abnahme |
| --- | --- | --- |
| M00 – Bestandsaufnahme: Web-App, Daten, selektive Artefact-Referenz | Grundlage des späteren Bootstraps; [PROVENANCE](PROVENANCE.md), [ARCHITECTURE](ARCHITECTURE.md). Kein eigenständiger Bestandsaufnahme-PR nachgewiesen. | Bei offenen Anforderungen auf den ursprünglichen Sollumfang zurückgreifen. |
| M01 – Android + Rust: Compose, Cargo-NDK, UniFFI | Bootstrap (später M00), [PR #1](https://github.com/Justify87/ladies-explorer-android/pull/1). | Technische Basis vorhanden. |
| M02 – Datenbank: SQLite-Domänenmodell, Migration/Import | Später M01 [PR #2](https://github.com/Justify87/ladies-explorer-android/pull/2) für das Domänenmodell; M02 [PR #3](https://github.com/Justify87/ladies-explorer-android/pull/3) für den Legacy-Import. Schema v2 im weiteren Verlauf. | Reale Migration mit privatem Bestand nicht durch CI belegt; [DATA-IMPORT](DATA-IMPORT.md). |
| M03 – Suche: HTTP, Filter, Pagination, Parser | M03 [PR #4](https://github.com/Justify87/ladies-explorer-android/pull/4), spätere Profilrouten-Korrektur [PR #8](https://github.com/Justify87/ladies-explorer-android/pull/8); [SOURCE](SOURCE.md). | Live-Kompatibilität und Vollständigkeit prüfen. |
| M04 – Profile und Identitäten: dHash, Historie, Medien | M04 [PR #5](https://github.com/Justify87/ladies-explorer-android/pull/5); [ARCHITECTURE](ARCHITECTURE.md). | Reale Medien-/Identitätsfälle abnehmen. |
| M05 – Frontend: Swipe-Deck, Suche, Grid, Details, Favoriten, Notizen | M05 [PR #6](https://github.com/Justify87/ladies-explorer-android/pull/6): native Feeds, Suchliste, Profile, Favoriten, bereits gespeicherte Suchen. | M05 erneut geöffnet: visuelle und funktionale Parität nach [M05-PLAN](M05-PLAN.md), [Matrix](M05-PARITY.md) und [Aufgabenregister](M05-TASKS.md); PR #6 bleibt technische Basis, nicht Endabnahme. |
| M06 – Weitere Funktionen: Karte, gespeicherte Suchen, Begegnungen, Einstellungen, externer WhatsApp-Aufruf | Gespeicherte Suchen bereits in #6; Einstellungen/WhatsApp in main; MapLibre M06 [PR #10](https://github.com/Justify87/ladies-explorer-android/pull/10), [MAP](MAP.md). | Begegnungen/Besuche und Bewertungen (u. a. Gesamt- und Sympathiebewertung) im Schema/Import vorhanden, keine vollständige native Bearbeitung nachgewiesen. Manuelle Preislisten ebenfalls im Schema/Import; deren UI-Sollumfang noch bestätigen. Keine automatische Preisextraktion. |
| M07 – Gesamtintegration: Performance, Stabilität, Tests, Import/Export, Release-Workflow | CI- und Diagnose-Infrastruktur vorhanden; kein abgeschlossener M07-PR nachgewiesen. | Funktionsparität zum vereinbarten Soll, Geräteabnahme, Import-/Export-Umfang und Release-Reife prüfen. |

„In main“ bedeutet gemergter Code, nicht automatisch physisch getestet oder vollständig abgenommen. Die ursprünglichen Anforderungen sind aus dem Projektverlauf rekonstruiert, nicht aus einem separat vollständig verifizierten Originaldokument. Die PRs belegen die tatsächlich umgesetzte Reihenfolge; sie ersetzen den Sollumfang nicht.

## Aktiver Arbeitsstrang: M05-Erweiterung

Der Nutzer hat am 18.11.2026 die vollständige M05-Frontend-Adaption auf Grundlage der ursprünglichen Web-App priorisiert. [M05-PLAN](M05-PLAN.md) definiert Soll/Scope und Gates, [M05-PARITY](M05-PARITY.md) den belegten Ausgangsabgleich, [M05-TASKS](M05-TASKS.md) den einzigen operativen Status. [AGENTS](../AGENTS.md) regelt agentenbasierte Umsetzung. Reihenfolge M05.0–M05.9, danach M05.10 Unified Profile Experience (Nutzerentscheidung 19.11.2026); nächsten ausführbaren Task und Gates aus dem Aufgabenregister ableiten, keine neue M06-/M07-Implementierung. Die schon integrierten M06-Bestandteile Karte und gespeicherte Suchen nur in die M05-UI einbinden; bestehende Fachverträge erhalten. Planungsstand ist keine Implementierung oder Geräteabnahme.

## Nachträgliche Arbeitsstränge

- Diagnose M0–M4, Implementierungsplan vom 18.11.2026: einheitliche Tracing-/Kermit-Logs, echte Laufzeitmessungen, optionaler begrenzter HTTP-Mitschnitt, TXT/HAR-Export, Regression und Geräteabnahme. [PR #7](https://github.com/Justify87/ladies-explorer-android/pull/7) ist gemergt. [DIAGNOSTICS](DIAGNOSTICS.md) beschreibt den Ist-Vertrag. Die planmäßig geforderte reale Android-Abnahme und der manuell bestätigte Firefox-HAR-Import sind nicht durch den Merge allein belegt; vor endgültiger Abnahme auch Aufzeichnung an/aus vergleichen.
- CI-Konsolidierung [PR #9](https://github.com/Justify87/ladies-explorer-android/pull/9): gelockte Rust-Tests, clippy, JVM-Tests, Android-Lint, Export-Skript-/Roundtrip-Test, arm64-APK. Zentrale Befehle und Toolchain in [README](../README.md).
- Fachliche Korrekturen an Navigation/Profilrouten, Filterkanonisierung und Pagination gehören zum heutigen Quellenvertrag und sind in [SOURCE](SOURCE.md) beschrieben.

## Offene Entscheidungen und Abnahme

M05 zuerst gemäß aktivem Aufgabenregister abschließen (u. a. Personennotiz und Grid). Danach M06-Restumfang (Begegnungen, Bewertungen, manuelle Preislisten) und M07-Export-/Release-Anforderungen abgleichen. Nicht vorhandene Funktionen nicht durch bloße Schematabellen als erledigt markieren. Keine neue Funktion allein aufgrund dieser Dokumentationskonsolidierung umsetzen; bestätigte Scope-Änderungen hier ausdrücklich vermerken.

Geräteabnahme: realer Import (mit lokalem Testbestand), vollständige Suche mit Radius und Pagination, gespeicherte Such-Deltas, Profil-/Medienabruf und Offline-Fallback, Swipe/Favoriten/Identitätsbestätigung, Karte inkl. Lifecycle/Geocoding, Diagnose inkl. Firefox-Import, App-Neustart und Performance. Bereichsspezifische Prüfschritte stehen in den Fachdateien. Automatisierte CI ist davon getrennt; keine unbelegte Geräte- oder Releasefreigabe.

## Pflege

Der vereinbarte Plan definiert Soll/Scope; Code und Tests im jeweiligen main definieren den nachweisbaren Ist-Stand. Reale Tests belegen tatsächliches Verhalten auf dem Gerät bzw. an der Quelle. Neuere bestätigte Entscheidungen dürfen den Plan ändern, nicht bloße Implementierungsabweichungen. Bei einem PR, der den Vertrag ändert, die zuständige Fachdatei und ggf. den Roadmap-Status im selben PR aktualisieren. Keine neuen historischen Meilensteinberichte als parallele Spezifikation; alte Berichte sind in Git verfügbar.

### Verbindlicher M05-Zwischenschritt M05.4a

Zwischen M05.4 und M05.5: vollständiger Quellen-HTTP-Transport über den Rust-Core (inklusive Bild-/Medien-CDNs), Coil ohne eigenen HTTP-Client, Architekturcheck und konsolidierte Diagnose. MapLibre-Netzwerk und explizite externe Intents bleiben unverändert. Operativer Status ausschließlich in M05-TASKS; G4a ist Voraussetzung für G5.


### M05 – Übergabe an Unified Profile Experience (19.11.2026)

PR #23 ist integriert (`f054781d`), technische Baseline G9-T belegt (finale CI #393, APK, kein Dateidiff zum Merge). G9 bleibt bis wirklicher Geräteabnahme und offenen Abweichungsentscheidungen blockiert (T9.2/T9.3). M05.10 ist bestätigtes UX-Soll: größerer Fotobereich (Ziel 70 % nutzbarer Portrait-Höhe), Tap-Galerie, Horizontal-Swipe, vertikal durchgehendes Profil ohne Doppelroute, kompakte Bedienelemente. Detailvertrag [M05-PLAN](M05-PLAN.md), P45–P51 in [M05-PARITY](M05-PARITY.md), Tasks und Gates nur in [M05-TASKS](M05-TASKS.md). Planung ist keine Implementierung. G10.0 erfordert G9 oder ausdrücklich dokumentierte Freigabe ausschließlich externer Abnahmeblocker; technische Altfehler separat schließen. G9/G10 vor endgültiger M05-Freigabe, keine neue M06/M07-Arbeit. Legacy bleibt fachliche Referenz, neues natives UX-Soll hat bei der Präsentation Vorrang.
