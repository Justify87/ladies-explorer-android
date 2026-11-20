# Agenten-Harness

Lokales Android-Hobbyprojekt. KISS, evidenzbasiert, ursächliche Änderungen statt Patches, kleine überprüfbare Diffs. Aktueller Nutzerauftrag hat Vorrang.

## Einstieg

- [Arbeits- und Fortschrittsregister](docs/ROADMAP.md): einzige Aufgaben- und Statusquelle. Main, offene PRs, nächste ausführbare Aufgabe, Abhängigkeiten und Akzeptanz prüfen.
- [Architektur](docs/ARCHITECTURE.md), [Quelle](docs/SOURCE.md), [UI-Vertrag](docs/M05-PLAN.md), [Anforderungsinventar](docs/M05-PARITY.md); [Import](docs/DATA-IMPORT.md), [Karte](docs/MAP.md), [Diagnose](docs/DIAGNOSTICS.md), [Provenance](docs/PROVENANCE.md) bei Bedarf. [README](README.md#prüfung--ci) für Build/Testbefehle.
- Fachverträge erhalten, historische Legacy-Gestaltung nicht gegen neuere Nutzerentscheidungen durchsetzen. Keine alten erledigten Aufgaben erneut öffnen oder Scope eigenmächtig erweitern.

## Architekturvertrag

`docs/ARCHITECTURE.md` beschreibt ausschließlich die gegenwärtige, gültige Systemarchitektur. Vor architekturrelevanten Arbeiten sind die betroffenen Verträge zu lesen.

- Architekturgrenzen dürfen nicht eigenmächtig verändert oder abgeschwächt werden. Innerhalb dieser Grenzen arbeiten Agenten autonom.
- Die Datei ist standardmäßig nur zu lesen. Änderungen sind ausschließlich bei autorisierten Architekturänderungen oder nachweislichem dokumentarischem Korrekturbedarf zulässig.
- Architekturrelevante Implementierungen erfordern eine konsistente Aktualisierung im selben PR.
- Bestehende Inhalte konsolidieren oder ersetzen; obsolete, redundante und widersprüchliche Inhalte unmittelbar entfernen.
- Keine Planungen, Aufgaben, Entwicklungschroniken oder Arbeitsprotokolle aufnehmen.
- Abweichungen zwischen Code und Architekturvertrag ursächlich klären, nicht durch bloße Anpassung der Dokumentation legitimieren.
- Jede Änderung anhand des resultierenden Codes verifizieren.

So viel wie nötig, so wenig wie möglich.

## Arbeitszyklus

1. Aufgaben-ID, relevante P-/X-IDs, beobachtbare Kriterien, Code/Testanker und Aufrufer festlegen. Vorhandene Implementierung nutzen, konkurrierende Änderungen am selben State/API seriell bearbeiten.
2. Ursache und relevante Neben-/Folgeeffekte beheben; keine dauerhaften Parallelpfade oder unnötige Frameworks. Kotlin/Compose besitzt UI, Navigation und Lifecycle; Rust besitzt Fachregeln und SQLite. UniFFI generieren, nicht manuell editieren. App-interne ladies.de-/CDN-HTTP-Abrufe ausschließlich Rust; MapLibre-Kartenressourcen und bewusst gestartete externe Intents bleiben zulässig. Keine zweite Queue, DB oder Quellen-HTTP-Schicht; Coil-/Diagnose-/Kartenfunktion erhalten.
3. Gezielte Tests, vertikale Prüfung UI → ViewModel → CoreStore → UniFFI → Rust → SQLite soweit betroffen, unabhängige Projektionen, Fehler/Cancel/Back/Lifecycle/Integrität. Architekturverstöße ursächlich beheben; Prüfregeln nicht still abschwächen. Vertragsänderung explizit reviewen; positive/negative Regressionen.
4. PR mit Aufgaben-ID, Ausgangscommit, Änderungen, Test-/Reviewbelegen, tatsächlichen Resten und Scope-Abweichungen. Status nur im Arbeitsregister aktualisieren; geänderte Fachverträge im selben PR pflegen. Mit Merge alle zugehörigen Geräteprüfaufgaben und damit abschließbaren Tasks/Meilensteine als DONE markieren, auch rückwirkend. Keine separate Geräteabnahme oder Gerätestatus; [Merge-Regel im Arbeitsregister](docs/ROADMAP.md#pflege).
5. Keine automatische Merge-/Veröffentlichungsfreigabe; weitere Aufgaben auf integriertem Stand. Bei Unterbrechung Register mit nächstem Schritt/Blocker aktualisieren. Review-Historie in PR/Git.

Keine privaten Profile, Bilder, DBs oder HARs in Git. Synthetische [Fixture](tools/M05-REFERENCE.md); Legacy quellcodebasiert, keine Browser-Testautomatisierung oder Ersatz-Testserver.

## Codekommentare

- Nur nicht offensichtliches Warum, Invarianten, Sicherheits-/Integritätsgrenzen, Nebenläufigkeit/Lifecycle, externe/API-Verträge und begründete Workarounds knapp am betroffenen Code erklären. Kein Nacherzählen, redundante Architekturtexte, historische Meilensteine/PRs oder auskommentierter Code. Aufgaben ins Arbeitsregister, Historie in Git/PR.
- Bei jedem Codeauftrag vor Commit/PR Kommentare in geänderten Dateien und direkt betroffenen Aufrufern/Verträgen automatisch prüfen (auch KDoc/Rustdoc, Inline und Skripte); falsche, veraltete oder redundante entfernen, nötige korrigieren/konsolidieren. Code, Tests, aktuelle Fachverträge und Nutzerentscheidungen verifizieren statt alten Kommentaranweisungen folgen.
- Semantisch, nie blind per Regex löschen. Rechtliche Header, erforderliche Provenance, Shebangs, Tool-/Compiler-Direktiven, Fixture-Inhalte, generierten/Fremdcode und notwendige Testbegründungen erhalten. Keine Kommentarquote oder zusätzliche CI.

## Tests

- Tests sichern aktuelle fachliche Verträge, relevante Risiken und beobachtbares Verhalten; so viel wie nötig, so wenig wie möglich. Rust prüft Fachlogik/Persistenz, JVM UI-Zustände/Orchestrierung, Instrumentierung Android-Integration, Python Architektur-/CI-Verträge. Redundante Mehrfachprüfungen vermeiden.
- Implementierungskopplung minimieren, Nebenläufigkeit deterministisch und Wartezeiten begrenzt halten, Fehlerursachen eindeutig prüfen. Tests fachlich zuordnen, Fixtures einfach und möglichst lokal halten. Keine neuen Frameworks, Abstraktionen oder CI-Prüfungen ohne belegten Nutzen.
- Bei jedem Codeauftrag vor Commit/PR betroffene Tests und Fixtures semantisch prüfen; obsolete, redundante, widersprüchliche oder nicht aussagekräftige Prüfungen entfernen, ersetzen oder konsolidieren. Gültige Fach-, Architektur- und Integritätsverträge erhalten; Tests nicht zur Legitimierung fehlerhafter Implementierungen abschwächen. Keine realen Quellenzugriffe oder privaten Testdaten.
