# Agenten-Harness

Lokales Android-Hobbyprojekt. KISS/YAGNI, evidenzbasiert, ursächliche Änderungen, kleine überprüfbare Diffs. Nur gegenwärtigen Bedarf implementieren; vorhandene Lösungen bevorzugt nutzen und vereinfachen. Aktueller Nutzerauftrag hat Vorrang.

## Einstieg und maßgebliche Quellen

Die betroffenen Verträge gezielt nach der Aufgabe lesen; keine pauschale Volllektüre.

| Dokument | Maßgebliche Zuständigkeit | Wann lesen |
| --- | --- | --- |
| [ROADMAP](docs/ROADMAP.md) | Einziges Register für Aufgaben, Status, Abhängigkeiten und Abnahme | Vor Aufgabenwahl: `main`, offene PRs, Status und Akzeptanz prüfen; erledigte Aufgaben nicht wiedereröffnen |
| [PRODUCT](docs/PRODUCT.md) | Verbindliches fachliches Soll und Produktgrenzen | Bei geändertem Fachverhalten |
| [ARCHITECTURE](docs/ARCHITECTURE.md) | Aktuelle technische Architektur, Invarianten und Grenzen | Bei Code-, Schnittstellen- oder Persistenzänderungen |
| [DESIGN](docs/DESIGN.md) | UI-/UX-Verträge und flexible Richtwerte | Bei sichtbaren oder interaktiven Änderungen |
| [README](README.md#prüfung--ci) | Build, Tests, CI, Diagnose und Fixtures | Für passende Prüfungen und Betrieb |
| [NOTICE](NOTICE) / [LICENSE](LICENSE) | Herkunft und Lizenzen | Bei Lizenz- oder Distributionsfragen |

Vor Änderungen die jeweils betroffenen Produkt-, Architektur- und Designverträge lesen; weitere Informationen nur bei Bedarf.

## Architektur- und Designvertrag

- ARCHITECTURE.md ist die einzige maßgebliche Quelle technischer Architekturvorgaben. Verbindliche Grenzen oder Schutzprüfungen nur ausdrücklich autorisiert ändern; Verstöße ursächlich beheben, nicht durch Dokumentationsänderungen legitimieren.
- Innerhalb der Grenzen autonome Implementierungsentscheidungen und Refactorings. Architekturkonforme Änderungen der Ist-Beschreibung und belegte Dokumentationskorrekturen sind zulässig; im selben PR gegen resultierenden Code und Prüfungen verifizieren. Keine Planung, Aufgabenstände oder Chronik in ARCHITECTURE.md.
- PRODUCT.md und DESIGN.md beschreiben das gültige fachliche und gestalterische Soll. Verbindliche Entscheidungen erhalten; Richtwerte und Details autonom anpassen.

## Arbeitszyklus

1. Je Arbeitskontext eine Aufgabe. Betroffene Aufgabe (IDs soweit relevant), beobachtbare Kriterien, Code/Testanker und Aufrufer bestimmen. Konkurrierende Änderungen am selben State/API seriell bearbeiten.
2. Ursache und relevante Folgeeffekte beheben; keine spekulativen Abstraktionen, Abhängigkeiten oder dauerhaften Parallelpfade. Betroffene Schichtengrenzen und Fachverträge erhalten.
3. Gezielte Tests und vertikale Prüfung der betroffenen Schichten; Fehler, Cancel, Back, Lifecycle und Integrität nach Risiko. Vertragsänderungen explizit reviewen, positive/negative Regressionen. Vor Abschluss Korrektheit, Notwendigkeit und Vereinfachungspotenzial prüfen.
4. PR mit Aufgabenbezug, Ausgangscommit, Änderungen, konkreten Test-/Reviewbelegen, Resten und Scope-Abweichungen. Status ausschließlich im Arbeitsregister und geänderte Verträge im selben PR pflegen. Geräteprüfaufgaben nach der [Merge-Regel](docs/ROADMAP.md#pflege) abschließen; keine separate Geräteabnahme.
5. Keine automatische Merge-/Veröffentlichungsfreigabe. Weitere Aufgaben auf integriertem Stand; bei Unterbrechung nächsten Schritt/Blocker im Register festhalten. Historie in Git/PR.

Keine privaten Profile, Bilder, DBs oder HARs in Git. Synthetische Fixtures; keine Browser-Testautomatisierung oder Ersatz-Testserver.

## Laufende Bereinigung

Bei jeder Änderung betroffene Implementierungen, Aufrufer, Schnittstellen, Kommentare, Tests und Dokumentation vor Commit/PR semantisch auf Notwendigkeit, Aktualität, Redundanz und Konsistenz prüfen. Abgelöste Pfade einschließlich FFI und Tests entfernen; Umfang begrenzen, größere unabhängige Befunde bei Bedarf im Arbeitsregister erfassen.

- Code: notwendige Testhilfen testexklusiv halten. Dynamische Aufrufer, Framework-/FFI-Verträge, Serialisierung, interne Schemaentwicklung und gültige Fachverträge berücksichtigen; nicht allein aufgrund fehlender Textreferenzen löschen.
- Kommentare (auch KDoc/Rustdoc, Inline, Skripte): nur nicht offensichtliches Warum, Invarianten, Sicherheit/Integrität, Nebenläufigkeit/Lifecycle, externe Verträge und begründete Workarounds knapp erklären. Keine Historie, Nacherzählung oder auskommentierten Code. Rechtliche Header, Provenance, Shebangs, Direktiven, Fixtures, Fremd-/generierten Code und notwendige Testbegründungen erhalten; niemals blind per Regex löschen.
- Tests: beobachtbares Verhalten und aktuelle Verträge sichern. Rust Fachlogik/Persistenz, JVM UI-State/Orchestrierung, Instrumentierung Android-Integration, Python Architektur/CI. Redundante Prüfungen konsolidieren, Kopplung minimieren, deterministische Nebenläufigkeit, begrenzte Wartezeiten, eindeutige Fehlerursachen, einfache lokale Fixtures. Gültige Verträge nicht zur Legitimierung fehlerhafter Implementierungen abschwächen; keine realen Quellenzugriffe oder privaten Testdaten.
- Dokumentation: Jede Aussage an einer maßgeblichen Stelle pflegen; andere Dateien verlinken statt Verträge zu duplizieren. Veraltete, widersprüchliche und redundante Aussagen im betroffenen PR bereinigen.
- Schreibweise: pro Abschnitt ein Thema, kurze atomare Regeln mit Subjekt, Geltungsbedingung und Konsequenz; Fehler/Cancel/Ausnahmen explizit, wo relevant. Verbindliche Regeln, Richtwerte und refaktorierbaren Ist-Stand unterscheidbar halten. Präzise Abschnittslinks und Codebezeichner in `Backticks`; Tabellen nur für echte Zuordnungen/Entscheidungen, nummerierte Listen für Abläufe. Eine H1 je Datei, H2/H3 nach Themen; keine starren Templates oder Kürzung zulasten gültiger Verträge.
- Neue Dokumente nur bei eigenständigem dauerhaft belegtem Informationsbedarf, nicht pro Feature. Agentenregeln nur bei dauerhaftem projektspezifischem Bedarf ergänzen, möglichst vorhandene ersetzen. Nutzerentscheidungen und verbindliche Grenzen nicht eigenmächtig abschwächen.

## Prüfungen und CI

Vorhandene Compiler-, Lint-, Testwerkzeuge und CI-Prüfungen bevorzugt nutzen. Zusätzliche dauerhafte Prüfungen, Jobs, Frameworks oder Werkzeuge nur bei konkretem Risiko/belegtem Nutzen unter Berücksichtigung von Laufzeit, Redundanz und Wartungsaufwand. Aufwendige Spezialdiagnosen bedarfsgesteuert. Schutzverträge nicht zur Beschleunigung abschwächen.
