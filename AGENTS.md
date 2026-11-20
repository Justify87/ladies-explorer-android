# Agenten-Harness

Lokales Android-Hobbyprojekt. KISS, evidenzbasiert, ursächliche Änderungen statt Patches, kleine überprüfbare Diffs. Aktueller Nutzerauftrag hat Vorrang.

## Einstieg

- [Arbeits- und Fortschrittsregister](docs/ROADMAP.md): einzige Aufgaben- und Statusquelle. Main, offene PRs, nächste ausführbare Aufgabe, Abhängigkeiten und Akzeptanz prüfen.
- [Architektur](docs/ARCHITECTURE.md), [Quelle](docs/SOURCE.md), [UI-Vertrag](docs/M05-PLAN.md), [Anforderungsinventar](docs/M05-PARITY.md); [Import](docs/DATA-IMPORT.md), [Karte](docs/MAP.md), [Diagnose](docs/DIAGNOSTICS.md), [Provenance](docs/PROVENANCE.md) bei Bedarf. [README](README.md#prüfung--ci) für Build/Testbefehle.
- Fachverträge erhalten, historische Legacy-Gestaltung nicht gegen neuere Nutzerentscheidungen durchsetzen. Keine alten erledigten Aufgaben erneut öffnen oder Scope eigenmächtig erweitern.

## Arbeitszyklus

1. Aufgaben-ID, relevante P-/X-IDs, beobachtbare Kriterien, Code/Testanker und Aufrufer festlegen. Vorhandene Implementierung nutzen, konkurrierende Änderungen am selben State/API seriell bearbeiten.
2. Ursache und relevante Neben-/Folgeeffekte beheben; keine dauerhaften Parallelpfade oder unnötige Frameworks. Kotlin/Compose besitzt UI, Navigation und Lifecycle; Rust besitzt Fachregeln und SQLite. UniFFI generieren, nicht manuell editieren. App-interne ladies.de-/CDN-HTTP-Abrufe ausschließlich Rust; MapLibre-Kartenressourcen und bewusst gestartete externe Intents bleiben zulässig. Keine zweite Queue, DB oder Quellen-HTTP-Schicht; Coil-/Diagnose-/Kartenfunktion erhalten.
3. Gezielte Tests, vertikale Prüfung UI → ViewModel → CoreStore → UniFFI → Rust → SQLite soweit betroffen, unabhängige Projektionen, Fehler/Cancel/Back/Lifecycle/Integrität. Architekturverstöße ursächlich beheben; Prüfregeln nicht still abschwächen. Vertragsänderung explizit reviewen; positive/negative Regressionen.
4. PR mit Aufgaben-ID, Ausgangscommit, Änderungen, Test-/Reviewbelegen, tatsächlichen Resten und Scope-Abweichungen. Status nur im Arbeitsregister aktualisieren; geänderte Fachverträge im selben PR pflegen. Mit Merge alle zugehörigen Geräteprüfaufgaben und damit abschließbaren Tasks/Meilensteine als DONE markieren, auch rückwirkend. Keine separate Geräteabnahme oder Gerätestatus; [Merge-Regel im Arbeitsregister](docs/ROADMAP.md#pflege).
5. Keine automatische Merge-/Veröffentlichungsfreigabe; weitere Aufgaben auf integriertem Stand. Bei Unterbrechung Register mit nächstem Schritt/Blocker aktualisieren. Review-Historie in PR/Git.

Keine privaten Profile, Bilder, DBs oder HARs in Git. Synthetische [Fixture](tools/M05-REFERENCE.md); Legacy quellcodebasiert, keine Browser-Testautomatisierung oder Ersatz-Testserver.
