# Agenten-Harness

Lokales Android-Hobbyprojekt. KISS/YAGNI, evidenzbasiert, ursächliche Änderungen, kleine überprüfbare Diffs. Nur gegenwärtigen Bedarf implementieren; vorhandene Lösungen bevorzugt nutzen und vereinfachen. Aktueller Nutzerauftrag hat Vorrang.

## Einstieg und maßgebliche Quellen

- [Arbeitsregister](docs/ROADMAP.md): einzige Aufgaben-/Statusquelle. Main, offene PRs, Abhängigkeiten und Akzeptanz prüfen; keine erledigten Aufgaben erneut öffnen oder Scope eigenmächtig erweitern.
- [Architektur](docs/ARCHITECTURE.md): technische Grenzen und Ist-System. [Design](docs/DESIGN.md): UI/UX. [Produkt](docs/PRODUCT.md): Features, fachliches Soll und Grenzen. Vor Änderungen die jeweils betroffenen Produkt-, Architektur- und Designverträge lesen; weitere Informationen nur bei Bedarf.
- [README](README.md#prüfung--ci) für Build/Test, Diagnose und synthetische Fixtures; [NOTICE](NOTICE) und [LICENSE](LICENSE) für Herkunft/Lizenzen.

## Architektur- und Designvertrag

- ARCHITECTURE.md ist die einzige maßgebliche Quelle technischer Architekturvorgaben. Verbindliche Grenzen oder Schutzprüfungen nur ausdrücklich autorisiert ändern; Verstöße ursächlich beheben, nicht durch Dokumentationsänderungen legitimieren.
- Innerhalb der Grenzen autonome Implementierungsentscheidungen und Refactorings. Architekturkonforme Änderungen der Ist-Beschreibung und belegte Dokumentationskorrekturen sind zulässig; im selben PR gegen resultierenden Code und Prüfungen verifizieren. Keine Planung, Aufgabenstände oder Chronik in ARCHITECTURE.md.
- PRODUCT.md und DESIGN.md beschreiben das gültige fachliche und gestalterische Soll. Verbindliche Entscheidungen erhalten; Richtwerte und Details autonom anpassen.

## Arbeitszyklus

1. Betroffene Aufgabe (IDs soweit relevant), beobachtbare Kriterien, Code/Testanker und Aufrufer bestimmen. Konkurrierende Änderungen am selben State/API seriell bearbeiten.
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
- Dokumentation: Informationen an maßgeblicher Stelle pflegen, andernorts referenzieren. Obsolete Inhalte entfernen, keine parallelen Spezifikationen. Neue Dateien nur bei eigenständigem dauerhaft belegtem Informationsbedarf, nicht pro Feature. Agentenregeln nur bei belegtem dauerhaftem projektspezifischem Bedarf ergänzen; bevorzugt konsolidieren oder ersetzen. Nutzerentscheidungen und verbindliche Grenzen nicht eigenmächtig abschwächen.

## Prüfungen und CI

Vorhandene Compiler-, Lint-, Testwerkzeuge und CI-Prüfungen bevorzugt nutzen. Zusätzliche dauerhafte Prüfungen, Jobs, Frameworks oder Werkzeuge nur bei konkretem Risiko/belegtem Nutzen unter Berücksichtigung von Laufzeit, Redundanz und Wartungsaufwand. Aufwendige Spezialdiagnosen bedarfsgesteuert. Schutzverträge nicht zur Beschleunigung abschwächen.
