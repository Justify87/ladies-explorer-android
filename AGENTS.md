# Agenten-Harness

Lokales Android-Hobbyprojekt. KISS/YAGNI, evidenzbasiert, ursächliche Änderungen, kleine überprüfbare Diffs. Nur gegenwärtigen Bedarf implementieren; vorhandene Lösungen bevorzugt nutzen und vereinfachen. Aktueller Nutzerauftrag hat Vorrang.

## Einstieg und maßgebliche Quellen

Die betroffenen Verträge gezielt nach der Aufgabe lesen; keine pauschale Volllektüre.

| Dokument | Maßgebliche Zuständigkeit | Wann lesen |
| --- | --- | --- |
| [ROADMAP](docs/ROADMAP.md) | Einziges Register für offene Aufgaben, Status, Abhängigkeiten und Abnahme | Vor Aufgabenwahl: `main`, offene PRs, nächsten regulären Entwicklungsschritt, Status und Akzeptanz nach [Pflege](docs/ROADMAP.md#pflege) prüfen; erledigte Aufgaben nicht wiedereröffnen. Vor Registeränderungen die Pflegekonventionen lesen. |
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

1. Je Auftrag eine freigegebene Aufgabe bearbeiten. Auf integriertem Stand `main`, offene PRs und [ROADMAP](docs/ROADMAP.md#pflege) prüfen; laufende Arbeit derselben Aufgabe fortsetzen, konkurrierende Änderungen am selben State/API seriell bearbeiten. Ohne regulären freigegebenen ausführbaren Schritt beenden; unabhängige Aufgaben nicht als Ersatz wählen. Betroffene IDs, beobachtbare Kriterien, Code-/Testanker und Aufrufer bestimmen.
2. PR als Draft anlegen und vor jeder Implementierungs- oder Korrekturphase in Draft versetzen. Zusammengehörige Änderungen möglichst atomar committen. Ursache und relevante Folgeeffekte beheben; keine spekulativen Abstraktionen, Abhängigkeiten oder dauerhaften Parallelpfade. Betroffene Schichtengrenzen und Fachverträge erhalten.
3. Im Draft gezielte Tests, vertikale und horizontale Prüfung der betroffenen Schichten und ein risikogerechtes eigenständiges technisches Review durchführen. Fehler, Cancel, Back, Lifecycle und Integrität nach Risiko prüfen; Vertragsänderungen explizit reviewen, positive/negative Regressionen sichern. Korrektheit, Notwendigkeit und Vereinfachungspotenzial prüfen. Relevante Befunde autonom beheben und betroffene Bereiche nachprüfen; keine separaten Nutzerfreigaben oder routinemäßigen weiteren unabhängigen Reviews.
4. Vor Prüfbereitschaft alle Code-, Test- und Vertragsänderungen sowie den bedingt vorbereiteten Roadmap-Abschluss im selben PR abschließen. PR dokumentiert Aufgabe, Ausgangscommit, Änderungen, tatsächlich vorhandene Prüf-/Reviewbelege, Reste und Scope-Abweichungen. [Roadmap-Pflege](docs/ROADMAP.md#pflege) einschließlich Geräte-Merge-Regel beachten. Keine ausstehenden Prüfungen als erfolgreich bezeichnen.
5. Erst dann PR auf Ready for review setzen; erforderliches Gate gemäß [README](README.md#prüfung--ci) auf dem endgültigen Integrationsstand auslösen. Bei laufender oder gerade ausgelöster CI ohne Polling unterbrechen: PR, HEAD, bekannte Merge-Basis, CI-Link bzw. noch nicht verfügbaren Run, Stand und nächsten Schritt nennen; im Chat einen Fortsetzungsbutton (alternativ kopierbaren Auftrag) anbieten. Vorübergehende Ausführungsstände und Belege in PR/Chat, nicht durch zusätzliche Roadmap-Commits pflegen.
6. Bei Wiederaufnahme aktuellen PR-Zustand, tatsächlichen HEAD, `main`, Merge-Kandidat und gegebenenfalls zugehörigen PR-CI-Versuch gemäß README neu ermitteln; nie allein auf Chatstand, grünes Symbol oder alte SHA vertrauen. Bereits integrierte Arbeit nur verifizieren; keine doppelte Aktion. Unfertigen Draft weiterbearbeiten; fertig geprüften Draft freigeben. Bei laufender erforderlicher Prüfung wieder unterbrechen. Bei Code-/Test-/Vertragsfehlern vor Änderungen Draft setzen, Ursachen und relevante Folgefehler gesammelt beheben, betroffene Bereiche gezielt nachprüfen, erneut freigeben und unterbrechen. Bei nachweislichem Infrastrukturfehler ohne Änderungen betroffene Jobs desselben gültigen Versuchs erneut starten. Fehlende/abgebrochene/überholte Prüfung ursächlich klären; bei unverändertem fertig vorbereitetem Stand ohne gültigen Run nötigenfalls Draft → Ready erneut auslösen. Bei geänderter Basis oder Merge-Konflikt im Draft integrieren, Aufgaben- und Roadmap-Abschluss neu abgleichen und erneut prüfen. Keine endlosen Wiederholungen ohne neue Erkenntnis; nicht autonom lösbaren Blocker benennen.
7. Nach gültigem erfolgreichem Gate Prüfbelege in PR-Metadaten ergänzen, nicht durch zusätzliche Commits. Nur mit ausdrücklicher aufgabenbezogener Merge-Freigabe mergen; diese kann bereits im Arbeitsauftrag erteilt sein und gilt für notwendige Korrekturen im selben Umfang. Keine erneute Freigabe regulärer technischer Schritte. Vor Merge aktuelle Basis, HEAD und Gate nochmals prüfen, erwarteten HEAD beim Merge binden; danach geprüften und integrierten Git-Tree sowie Roadmap-Zustand abgleichen. Ohne Merge-Freigabe bereitstellen. Keine automatische Veröffentlichung oder nächste Aufgabe ohne entsprechenden Auftrag. Nutzerintervention nur bei fehlender Freigabe, notwendiger fachlicher/Scope-Entscheidung oder nicht autonom lösbarem Blocker.

Keine privaten Profile, Bilder, DBs oder HARs in Git. Synthetische Fixtures; keine Browser-Testautomatisierung oder Ersatz-Testserver.

## Laufende Bereinigung

Bei jeder Änderung betroffene Implementierungen, Aufrufer, Schnittstellen, Kommentare, Tests und Dokumentation vor Commit/PR semantisch auf Notwendigkeit, Aktualität, Redundanz und Konsistenz prüfen. Abgelöste Pfade einschließlich FFI und Tests entfernen; Umfang begrenzen, größere unabhängige Befunde bei Bedarf im Arbeitsregister erfassen.

- Code: notwendige Testhilfen testexklusiv halten. Dynamische Aufrufer, Framework-/FFI-Verträge, Serialisierung, interne Schemaentwicklung und gültige Fachverträge berücksichtigen; nicht allein aufgrund fehlender Textreferenzen löschen.
- Kommentare (auch KDoc/Rustdoc, Inline, Skripte): nur nicht offensichtliches Warum, Invarianten, Sicherheit/Integrität, Nebenläufigkeit/Lifecycle, externe Verträge und begründete Workarounds knapp erklären. Keine Historie, Nacherzählung oder auskommentierten Code. Rechtliche Header, Provenance, Shebangs, Direktiven, Fixtures, Fremd-/generierten Code und notwendige Testbegründungen erhalten; niemals blind per Regex löschen.
- Tests: beobachtbares Verhalten und aktuelle Verträge sichern. Rust Fachlogik/Persistenz, JVM UI-State/Orchestrierung, Instrumentierung Android-Integration, Python Architektur/CI. Redundante Prüfungen konsolidieren, Kopplung minimieren, deterministische Nebenläufigkeit, begrenzte Wartezeiten, eindeutige Fehlerursachen, einfache lokale Fixtures. Gültige Verträge nicht zur Legitimierung fehlerhafter Implementierungen abschwächen; keine realen Quellenzugriffe oder privaten Testdaten.
- Dokumentation: Jede Aussage an einer maßgeblichen Stelle pflegen; andere Dateien verlinken statt Verträge zu duplizieren. Veraltete, widersprüchliche und redundante Aussagen im betroffenen PR bereinigen. Dokumentlokale Pflegekonventionen beachten und beim semantischen Review die inhaltliche Vollständigkeit sowie Einhaltung der jeweiligen Informationsstruktur prüfen.
- Schreibweise: pro Abschnitt ein Thema, kurze atomare Regeln mit Subjekt, Geltungsbedingung und Konsequenz; Fehler/Cancel/Ausnahmen explizit, wo relevant. Verbindliche Regeln, Richtwerte und refaktorierbaren Ist-Stand unterscheidbar halten. Präzise Abschnittslinks und Codebezeichner in `Backticks`; Tabellen nur für echte Zuordnungen/Entscheidungen, nummerierte Listen für Abläufe. Eine H1 je Datei, weitere Überschriftenebenen nach semantischer Hierarchie; keine starren Templates oder Kürzung zulasten gültiger Verträge.
- Neue Dokumente nur bei eigenständigem dauerhaft belegtem Informationsbedarf, nicht pro Feature. Agentenregeln nur bei dauerhaftem projektspezifischem Bedarf ergänzen, möglichst vorhandene ersetzen. Nutzerentscheidungen und verbindliche Grenzen nicht eigenmächtig abschwächen.

## Prüfungen und CI

Vorhandene Compiler-, Lint-, Testwerkzeuge und CI-Prüfungen bevorzugt nutzen. Zusätzliche dauerhafte Prüfungen, Jobs, Frameworks oder Werkzeuge nur bei konkretem Risiko/belegtem Nutzen unter Berücksichtigung von Laufzeit, Redundanz und Wartungsaufwand. Aufwendige Spezialdiagnosen bedarfsgesteuert. Schutzverträge nicht zur Beschleunigung abschwächen.
