# Agenten-Harness

Dieses Repository ist ein lokales Android-Hobbyprojekt. Arbeitsweise: KISS, evidenzbasiert, ursächliche Änderungen statt Patches, kleine überprüfbare Änderungen. Kein automatischer Merge, keine Veröffentlichung ohne Auftrag.

## Einstieg und Autorität
1. Aktuellen main/PR-Stand und offene Änderungen prüfen; auf aktuellem Integrationsstand arbeiten.
2. [Roadmap](docs/ROADMAP.md) lesen. Aktiver Implementierungsstrang: **M05 – Frontend-Parität und M05.10 Unified Profile Experience**. M06/M07 nicht eigenmächtig beginnen.
3. [M05-Plan](docs/M05-PLAN.md) enthält Soll, Grenzen, Architektur und Gates; [M05-Paritätsmatrix](docs/M05-PARITY.md) enthält nachweisbare Anforderungen; [M05-Aufgaben](docs/M05-TASKS.md) ist das einzige operative Aufgabenregister. Fachdateien beschreiben den nachweisbaren Ist-Vertrag. Bei Konflikt zwischen altem Legacy-Verhalten und aktuellem Quellen-/Persistenzvertrag gilt letzterer; Abweichung transparent dokumentieren. Bestätigte Nutzerentscheidungen haben Vorrang.
4. Zu Beginn die erste ausführbare offene Aufgabe und ihre Abhängigkeiten wählen; betroffene Quellen und Tests lesen. Keine Erledigung aus bloßem Merge ableiten.

## Arbeitszyklus
- Aufgaben-ID, Matrix-IDs, konkrete Akzeptanzkriterien und betroffene Dateien vor Änderung festlegen. Pro Meilenstein eigener Branch/PR empfohlen, keine konkurrierenden Änderungen am gleichen State/API; unabhängige Leseanalysen können parallel erfolgen.
- Status in M05-TASKS aktualisieren: TODO → IN_PROGRESS → REVIEW → DONE; BLOCKED mit Grund und nächstem Schritt. DONE nur mit belegtem Ergebnis; fehlende echte Geräteprüfung als DEVICE_PENDING festhalten, nicht als bestanden. Bei Integrationskonflikt neu prüfen.
- Kotlin/Compose besitzt UI, Navigation und Lifecycle; Rust besitzt fachliche Regeln und SQLite. UniFFI-Bindings generieren, nicht manuell editieren. Alle app-internen HTTP(S)-Abrufe zu ladies.de einschließlich APIs, Subdomains und Medien-CDNs ausschließlich im Rust-Core; Kotlin, Compose, Coil und andere SDKs dürfen diese Quelle nie direkt abrufen. MapLibre darf eigene Kartenressourcen laden; bewusst gestartete externe Intents sind keine App-Abrufe. Kein zweiter Quellen-HTTP-Stack, kein WebView-Port, kein unnötiges Modul/Framework. Vorhandene Coil-/Diagnose-/Kartenfunktion erhalten.
- Nach jeder Aufgabe gezielte Tests; nach jedem Teilmeilenstein Diff-Review gegen Matrix, Nebenwirkungen, Abhängigkeiten, Datenintegrität und Scope. Fehler ursächlich beheben, irrelevante Umbauten unterlassen. Regressionsfälle als Tests sichern; redundante ersetzte Implementierung entfernen.
- Testbefehle aus README/CI verwenden. Bei fehlenden Laufzeitwerkzeugen CI oder expliziten Blocker nennen. Kein privater Datenbestand, keine realen Personenbilder oder HARs in Git; synthetische Fixtures.
- PR enthält Aufgaben-/Matrix-IDs, Ausgangsstand, Änderungen, Tests/CI mit Ergebnis bzw. nicht ausgeführte Tests, Review-Befunde, Rest-/Geräteabnahme, Scope-Abweichungen. Task-Status und tatsächlich geänderte Fachverträge im selben PR pflegen.
- Erst nach bestandenen Gate-Kriterien und erforderlicher Nutzerfreigabe integrieren; Folgemeilenstein auf integriertem Stand beginnen. Keine Aufgabe ohne Beleg schließen.

## Prüfkommandos
Siehe [README](README.md#prüfung--ci) und [CI](.github/workflows/android.yml). Für M05 zusätzlich synthetische native UI- und State-Regressionen sowie manuelle visuelle Geräteprüfung gemäß M05-Plan. Legacy-Referenz quellcodebasiert; keine Browser-Testwerkzeuge, Browser-Automatisierung oder Ersatz-Testserver (Nutzerentscheidung). Harness ist eine agentenlesbare Prozessvorgabe, keine eigenständig laufende Automation.


## M05.10 – verbindliche Start- und Regressionsregeln

- G9-T belegt nur den integrierten technischen Bestand; G9-Geräte-/Abweichungsblocker nicht als DONE umdeuten. T10.0/G10.0 vor produktiver Änderung: G9 erfüllt oder ausdrückliche dokumentierte Nutzerfreigabe zur Verschiebung ausschließlich externer Abnahmeblocker; alle technischen Altbefunde separat korrigiert und integriert. Planungs-PR ist keine Implementierungsfreigabe. Kein konkurrierender State-/API-PR. Aktuellen main prüfen, nicht blind auf alte SHA schreiben.
- M05-PLAN Abschnitt M05.10 ist verbindliches UX-Soll (Bildziel, ein Profilfluss, Gestenabgrenzung); bestehende fachliche P- und X-Verträge bleiben. T10.0–T10.9 und P45–P51 nutzen, nach jedem Gate vertikal von Einstieg/Compose über ViewModel/CoreStore bis zur betroffenen Rust-/SQLite-Operation prüfen, auch seitliche Projektionen und Back/Lifecycle/Fehlerpfade. Vorhandene Owner/Draining/Ack-CAS/Notiz-/Identity-/Media-Verträge nicht durch UI-Refactor umgehen. Kein zweites Profil, kein zweiter Swipe-State oder HTTP-Stack. Ersetzten Code löschen, nicht mit Sonderfällen überkleben. Neue Architekturentscheidungen mit Evidenz und Scope begründen.
- Kein privater Screenshot oder Personenbild als Fixture. Layoutmessung an synthetischen Profilen mit dokumentierter H-Basis, Gerätegröße, Schrift und Inset; reale Geräteabnahme nur mit Beleg. CI grün bedeutet nicht Gesten-/Live-Abnahme. G9/G10 vollständig abschließen, bevor M05 als erledigt gilt.
