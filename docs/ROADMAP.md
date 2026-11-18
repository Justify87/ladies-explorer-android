# Roadmap und Abnahmestand

Stand des Ist-Abgleichs: 18.11.2026, [main 7d768e5b](https://github.com/Justify87/ladies-explorer-android/commit/7d768e5bac9ddf0239de904e21f120e82c8db67a). Diese Roadmap rekonstruiert den ursprünglichen Android-Migrationsplan M00–M07 aus dem Projektverlauf vom 17.11.2026 und den gemergten Meilensteinen. Sie ist der Soll-/Fortschrittsindex, nicht eine zweite technische Spezifikation. Die späteren Diagnose-Meilensteine M0–M4 sind ein eigener Arbeitsstrang, keine Umnummerierung des Hauptplans.

## Verbindliches Ziel

Eine eigenständige, lokal genutzte Android-App auf einem Gerät, mit Kotlin/Compose und eigenem Rust/SQLite-Core über UniFFI. Suche, Extraktion und Persistenz im App-Prozess; externe Quellen nur für die jeweils benötigten Netzwerkfunktionen. Selektive Übernahme der technischen Artefact-Basis, kein Artefact-Fork und keine fachfremden Dokument-/OCR-/PDF-Funktionen. Relevante fachliche Daten und Regeln der alten Web-App erhalten, nicht den Flask/Python/Messaging-Stack. Kein integrierter WhatsApp-/Messenger-Client, keine Synchronisierung oder automatische KI-/OCR-Preisextraktion; WhatsApp nur extern öffnen. KISS für ein Hobbyprojekt.

## Hauptplan M00–M07

| Meilenstein | Soll | Ist / Rest |
| --- | --- | --- |
| M00 | Bestandsaufnahme: Web-App, Daten und selektive Artefact-Referenz | Grundlage dokumentiert; [PROVENANCE](PROVENANCE.md), [ARCHITECTURE](ARCHITECTURE.md). |
| M01 | Android-/Compose-Grundgerüst, Rust, Cargo-NDK, UniFFI, lokaler Speicher | In main; technische Grundlage und Schema vorhanden. |
| M02 | SQLite-Domänenmodell und Legacy-Datenimport inkl. Medien/Historie | In main; [DATA-IMPORT](DATA-IMPORT.md). Reale vollständige Migration mit privatem Bestand nicht durch CI belegt. |
| M03 | Rust-Suche, Filter, Pagination, Profilparser | In main, einschließlich späterer Fallback-/Radius- und Profilrouten-Korrekturen ([PR #8](https://github.com/Justify87/ladies-explorer-android/pull/8)); [SOURCE](SOURCE.md). Live-Kompatibilität und Vollständigkeit separat prüfen. |
| M04 | Profile, Identitätskandidaten, dHash, Änderungshistorie, Archiv | In main; [ARCHITECTURE](ARCHITECTURE.md). Reale Medien-/Identitätsfälle noch gesondert abnehmen. |
| M05 | Swipe-Deck, Suche/Grid, Profildetails, Favoriten, Notizen | Native Feeds, Suchliste und Profile in [PR #6](https://github.com/Justify87/ladies-explorer-android/pull/6) gemergt. Notizen sind im Datenmodell/Import vorhanden, eine vollständige native Notizbearbeitung und eine separate Grid-/Wechselansicht sind nicht als umgesetzt nachgewiesen; ggf. Sollumfang bestätigen. |
| M06 | Karte, gespeicherte Suchen, Begegnungen, Einstellungen, externer WhatsApp-Aufruf | Gespeicherte Suchen schon in M05, Einstellungen/WhatsApp in main; MapLibre in [PR #10](https://github.com/Justify87/ladies-explorer-android/pull/10) gemergt ([MAP](MAP.md)). Begegnungen/Besuche und manuelle Preislisten sind im Schema/Import vorhanden, keine vollständige native Begegnungs-/Preislistenverwaltung nachgewiesen. Keine automatische Preisextraktion vorgesehen. |
| M07 | Gesamtintegration, Performance, Stabilität, Tests, Import/Export und Release | Offen als Gesamtmeilenstein. CI- und Diagnose-Infrastruktur bereits vorhanden; Parität der vereinbarten Funktionen, reale Geräteabnahme, Import-/Export-Scope und Release-Reife noch prüfen. |

Die Bezeichnungen der historischen M01/M02-Implementierung überlappen die technische Grundlage und das Datenmodell; die Tabelle bildet die ursprünglichen Ziele ab und erfindet keine weitere Migration. „In main“ bedeutet Code gemergt, nicht automatisch physisch getestet oder vollständig abgenommen.

## Nachträgliche Arbeitsstränge

- Diagnose M0–M4, Implementierungsplan vom 18.11.2026: einheitliche Tracing-/Kermit-Logs, echte Laufzeitmessungen, optionaler begrenzter HTTP-Mitschnitt, TXT/HAR-Export, Regression und Geräteabnahme. [PR #7](https://github.com/Justify87/ladies-explorer-android/pull/7) ist gemergt. [DIAGNOSTICS](DIAGNOSTICS.md) beschreibt den Ist-Vertrag. Die planmäßig geforderte reale Android-Abnahme und der manuell bestätigte Firefox-HAR-Import sind nicht durch den Merge allein belegt; vor endgültiger Abnahme auch Aufzeichnung an/aus vergleichen.
- CI-Konsolidierung [PR #9](https://github.com/Justify87/ladies-explorer-android/pull/9): gelockte Rust-Tests, clippy, JVM-Tests, Android-Lint, Export-Skript-/Roundtrip-Test, arm64-APK. Zentrale Befehle und Toolchain in [README](../README.md).
- Fachliche Korrekturen an Navigation/Profilrouten, Filterkanonisierung und Pagination gehören zum heutigen Quellenvertrag und sind in [SOURCE](SOURCE.md) beschrieben.

## Offene Entscheidungen und Abnahme

Vor M07 die noch nicht abgedeckten Notiz- und Begegnungsfunktionen sowie Grid-/Export-/Release-Anforderungen gegen den vereinbarten Soll-Umfang abgleichen. Nicht vorhandene Funktionen nicht durch bloße Schematabellen als erledigt markieren. Keine neue Funktion allein aufgrund dieser Dokumentationskonsolidierung umsetzen; bestätigte Scope-Änderungen hier ausdrücklich vermerken.

Geräteabnahme: realer Import (mit lokalem Testbestand), vollständige Suche mit Radius und Pagination, gespeicherte Such-Deltas, Profil-/Medienabruf und Offline-Fallback, Swipe/Favoriten/Identitätsbestätigung, Karte inkl. Lifecycle/Geocoding, Diagnose inkl. Firefox-Import, App-Neustart und Performance. Bereichsspezifische Prüfschritte stehen in den Fachdateien. Automatisierte CI ist davon getrennt; keine unbelegte Geräte- oder Releasefreigabe.

## Pflege

Der vereinbarte Plan definiert Soll/Scope; Code und Tests im jeweiligen main definieren den nachweisbaren Ist-Stand. Reale Tests belegen tatsächliches Verhalten auf dem Gerät bzw. an der Quelle. Neuere bestätigte Entscheidungen dürfen den Plan ändern, nicht bloße Implementierungsabweichungen. Bei einem PR, der den Vertrag ändert, die zuständige Fachdatei und ggf. den Roadmap-Status im selben PR aktualisieren. Keine neuen historischen Meilensteinberichte als parallele Spezifikation; alte Berichte sind in Git verfügbar.
