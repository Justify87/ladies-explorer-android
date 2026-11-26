# Arbeitsregister

Einzige operative Quelle für offene Aufgaben, Reihenfolge, Status und Abnahme. Erledigte oder verworfene Aufgaben stehen ausschließlich in Git und den zugehörigen PRs. Maßgebliche Verträge: [PRODUCT](PRODUCT.md), [ARCHITECTURE](ARCHITECTURE.md), [DESIGN](DESIGN.md). Arbeitsweise: [AGENTS](../AGENTS.md); Prüfungen: [README](../README.md#prüfung--ci).

## Nächster Entwicklungsschritt

M13.1 — Bestandsaufnahme und Kotlin-/Compose-Altlasten. L01 bleibt unabhängig vor weiterer öffentlicher Distribution zu klären.

## M13 — Dead Code & Refactoring Cleanup

Ziel: die aktuelle integrierte Codebasis repositoryweit auf entbehrliche, abgelöste und redundante Implementierungen prüfen und belegte Befunde ursächlich bereinigen. Kein neues Produktverhalten, keine pauschale Neustrukturierung und keine Wiedereröffnung historischer Aufgaben. Die folgenden Aufgaben bilden eine sequenzielle Bereinigung; jede hat einen eigenen Draft-PR und Abschluss gemäß [AGENTS](../AGENTS.md#arbeitszyklus). Neu belegte Restbefunde innerhalb des Meilensteins der sachlich zuständigen offenen Aufgabe zuordnen; konkrete unabhängige Fehler nur bei Bedarf als eigene Aufgabe erfassen. Keine parallelen Planungsdateien.

**Prüfmethode und Grenzen:**
- Je Aufgabe den tatsächlich integrierten Ausgangsstand, betroffene Verträge und offene PRs bestimmen. Inventar und Kandidaten nach Beleg, Aufrufern, Risiko und Entscheidung (entfernen, vereinfachen, erhalten, testexklusiv oder begründet offen) im PR festhalten. Compiler-/Lint-/Clippy-Meldungen und statische Referenzen als Hinweise, nicht als alleinigen Löschbeweis verwenden. Auch indirekte Aufrufer, Framework-/Manifest-/Ressourcen-/Reflection-/JNI-/FFI-Verträge, Serialisierung, Feature-Konfiguration, Skripte und Testtargets prüfen. Umfang und Grenzen der tatsächlich erfolgten Analyse offenlegen.
- Ursächliche Bereinigung vertikal über Compose, Kotlin, CoreStore, generierte UniFFI-Grenze, Rust und SQLite; horizontale Prüfung gemeinsam genutzter Zustände, Aufrufer und alternativer Einstiegspunkte. Abgelöste Pfade mitsamt exklusiven Helfern, Records, Fehlern, Tests, Ressourcen und Dokumentationsverweisen entfernen. Keine Änderung generierter Bindings von Hand, keine zweite Fachlogik, kein neuer dauerhafter Prüfmechanismus ohne belegten Nutzen.
- Gültiges Produkt-/Designverhalten, Architektur- und Schutzverträge, native Daten und interne Schemaentwicklung erhalten. Nicht allein wegen fehlender Android-Aufrufer oder geringer Nutzung entfernen. Notwendige dynamische Pfade, Migrationen, Parser-/Netzwerk-/Mediengrenzen, Cursor-/Revisions-/Owner-Verträge, Cancel-/Drain-/Epoch-Schutz und Diagnose bleiben erhalten. Keine Neuimplementierung historisch bereits gelöster Befunde ohne aktuellen Nachweis. Keine unbegründeten Formatierungswellen, Dependency-Updates, CI-Optimierungsprojekte oder Architekturumbauten.
- Relevante positive und negative Regressionen, Fehler/Retry, Back/Lifecycle, Race-/Cancel-/Stale-Result- und Integritätsfälle risikogerecht prüfen. Gezielte Prüfungen und genau den regulären risikogerechten technischen Review-Zyklus des Harness nutzen; Befunde und Folgeeffekte im Draft beheben. Keine weiteren routinemäßigen Review-Schleifen. Vor Ready den jeweiligen Registerabschluss bedingt vorbereiten. Nur das aktuelle erforderliche Ready-PR-Gate gemäß [README](../README.md#prüfung--ci) zählt; bei laufender CI ohne Polling unterbrechen, Fortsetzung anbieten, Fehler gesammelt im Draft korrigieren. Merge nur mit aufgabenbezogener Freigabe und anschließender Tree-/Registerverifikation. Kein Release oder Beginn einer weiteren Aufgabe ohne Auftrag.

### M13.1 — Bestandsaufnahme und Kotlin-/Compose-Altlasten

**Status:** READY.

Repositoryinventar anhand versionierter Produktions-, Test-, Build-, Tool- und Konfigurationsdateien erstellen; vorhandene Diagnose-/CI-Belege als Ausgangspunkt prüfen. Kandidaten für sämtliche M13-Aufgaben mit konkreten Codeankern und Begründungen erfassen und bei Bedarf die nachfolgenden Aufgabenumfänge im selben PR präzisieren. Kotlin-/Compose-Produktionspfade einschließlich Screens, Navigation, Komponenten, State/Controller/ViewModel, Medien/Prefetch, CoreStore-Fassade, Ressourcen und Imports auf tote Deklarationen, verwaiste Getter/Callbacks, veraltete Adapter, redundante Nullprüfungen und unerreichbare Zweige prüfen. Bestätigte ausschließlich Android-seitige Altlasten vollständig entfernen; schichtübergreifende Kandidaten M13.2 zuordnen.

**Akzeptanz/Prüfung:** Belegtes Inventar mit Entscheidungen und verbleibenden Prüfgrenzen im PR; keine bestätigten unbearbeiteten Kotlin-/Compose-Altlasten im eigenen Umfang; aktive Aufrufer, Zustands- und Interaktionsverträge erhalten. Gezielte JVM-/Android-Regressionen, Compiler-/Lint-Diagnosen und erforderliches Gate.

### M13.2 — Native API, UniFFI und Rust-Altlasten

**Status:** PLANNED. Abhängig von M13.1.

Native Exporte und Records gegen tatsächliche CoreStore-/Kotlin-Aufrufer, generierte Bindings, Android-/JNI-/Manifest-/R8-Konfiguration sowie Rust-interne und Testnutzer abgleichen. Obsolete FFI-Exporte, Wrapper, optional gewordene Altpfade, verwaiste Typen, Felder, Fehler, private Algorithmen und exklusive Helfer vertikal entfernen; benötigte Rust-interne APIs nicht wegen fehlender FFI-Aufrufer löschen. Rein testbezogene native Hilfen in Testmodule bzw. `cfg(test)` verschieben, auf minimale Fixtures begrenzen. Insbesondere Profil-/Medien-/Historien-/Identitäts-/Such-/Saved-People-/Diagnosepfade und interne Pagination, RequestControl, Queue-/Shutdown-/Recorder- und Revisionsverträge auf aktuelle Notwendigkeit prüfen. Keine Änderung des Datenformats, Schemas oder gültiger Migrationsverträge zur bloßen Bereinigung.

**Akzeptanz/Prüfung:** Inventar der überprüften nativen Symbole und Entscheidungen; kein bestätigter entbehrlicher Produktions-/Exportpfad im Umfang. Bindings regulär generiert, FFI-Aufrufer konsistent; Rust fmt/test/clippy, native synthetische Fixtures, Architektur-/Exportverträge, Android-Integration einschließlich APK-Vertrag und erforderliches Gate.

### M13.3 — Aktive Redundanz und semantisches Refactoring

**Status:** PLANNED. Abhängig von M13.2.

Verbleibende aktive Duplikate, unnötige Abstraktionen/Delegationen, redundante Daten- und Zustandsprojektionen, Übergangsimplementierungen und konkurrierende Verantwortlichkeiten in UI, Operations-/Lifecycle-Orchestrierung, CoreStore und Rust prüfen. Nur nachgewiesen gleichwertige Pfade vereinheitlichen, Verantwortlichkeiten am vorhandenen fachlichen Owner bündeln, vereinfachbare Pfade direkt ersetzen und abgelöste Implementierungen entfernen. Unterschiede zwischen Listing-/Person-Identität, CAS-/Owner-Prüfungen, Saved-/Discovery-/Standalone-Kontexten, Suchfenstern, Medienvorbereitung und Fehler-/Cancel-Semantik nicht als Duplikate behandeln, sofern fachlich verschieden.

**Akzeptanz/Prüfung:** Jede Konsolidierung durch konkrete Redundanz und Verhaltensäquivalenz begründet; keine zusätzlichen Parallelpfade oder spekulativen Abstraktionen. Betroffene horizontale Einstiegspunkte und vertikale Verträge einschließlich Integrität und Nebenläufigkeit mit angemessenen Regressionen und erforderlichem Gate geprüft; bei fehlendem Beleg Pfad erhalten und begründen.

### M13.4 — Testcode und Fixtures

**Status:** PLANNED. Abhängig von M13.3.

Betroffene und verbliebene Rust-, JVM-, Instrumentierungs- und Python-Tests samt Fixtures, Helfern und Testkonfiguration auf obsolete API-Wrapper, redundante Assertions, tote Fixtures, mehrfach abgesicherte identische Verträge, unproduktive Mock-/Roundtrip-Tests sowie unnötige Kopplung prüfen. Belegte Redundanzen konsolidieren, nützliche Regressionen auf aktive Produktionspfade übertragen; minimale testexklusive SQL-/Datenfixtures und deterministische Synchronisationshilfen beibehalten. Fehler-, Owner-/Cursor-/Zeitstempel-/Schema-/Revisions-/Cancel- und Integritätsregressionen nicht mit entfernten Produktionswrappers verlieren. Testschichten gemäß [AGENTS](../AGENTS.md#laufende-bereinigung) nutzen; keine neue Testinfrastruktur oder reale Quellenzugriffe.

**Akzeptanz/Prüfung:** Entfernte oder ersetzte Tests nachvollziehbar einem weiterhin gültigen Vertrag beziehungsweise einem belegten Wegfall zugeordnet; notwendige positive/negative Abdeckung erhalten, obsolete Testpfade entfernt, deterministische Ausführung und bestehende betroffene Tests sowie erforderliches Gate erfolgreich.

### M13.5 — Abhängigkeiten, Build, Tools und semantische Dokumentationspflege

**Status:** PLANNED. Abhängig von M13.4.

Manifeste, Gradle-/Cargo-Abhängigkeiten, Lockfiles, native AAR-/ABI-/Ressourcenreferenzen, Skripte, Tool-Aufrufer, Build-/Testkonfiguration und bedingte Pfade auf tatsächlich entbehrlich gewordene Einträge prüfen. Transitive und dynamische Nutzung bei konkretem Verdacht mit vorhandenen Dependency-/Build-Diagnosen klären. Nur belegte Altlasten entfernen; Schutzprüfungen, wirksame Konfiguration, Lizenz-/Provenance-Verträge und erforderliche Buildpfade erhalten. Kein pauschales Upgrade, kein CI-Laufzeitprojekt und keine Änderung der unabhängigen Lizenzklärung L01. Kommentare/KDoc/Rustdoc und betroffene Dokumentation semantisch auf Aktualität, Notwendigkeit, Widerspruch und Redundanz prüfen; abgelöste Verträge, Beispiele, Verweise und reine Historie entfernen, gültige Aussagen an ihrer maßgeblichen Stelle erhalten. Bestehende Bereinigungsregeln des Harness verwenden; nur konkret belegte dauerhafte Lücken dort korrigieren, keine Duplikate.

**Akzeptanz/Prüfung:** Für entfernte Abhängigkeiten und Konfigurationen Beleg der Nichtnotwendigkeit und betroffene Build-/Tool-/Testpfade geprüft. Dokumentation entspricht resultierendem Code und dokumentlokaler Semantik, keine parallelen Register. Vorhandene relevante Prüfungen und erforderliches Gate gemäß tatsächlichem Diff; keine Abschwächung von Schutzverträgen.

### M13.6 — Abschluss der repositoryweiten Bereinigung

**Status:** PLANNED. Abhängig von M13.5.

Offene Kandidaten aus M13.1 und den nachfolgenden PRs gegen den tatsächlich integrierten Stand abgleichen. Compiler-/Lint-/Clippy-, Architektur-, Export-, Build- und Testbefunde des zuletzt relevanten vollständigen CI-Laufs auf verbleibende bestätigte Altlasten, Folgefehler und verwaiste schichtübergreifende Referenzen prüfen. Nur noch konkrete Restbefunde im Meilenstein ursächlich beseitigen; begründet notwendige Pfade oder Stilhinweise nicht künstlich zum Dead Code erklären. Keine Wiederholung vollständiger früherer Audits oder zusätzliches unabhängiges Review ohne konkreten Anlass. Bei reinem Registerabschluss bereits integrierte Full-CI-Belege nachvollziehbar zuordnen und das erforderliche Dokumentationsgate nutzen; bei Code-/Build-relevanten Reständerungen vollständiges Gate.

**Akzeptanz/Prüfung:** Alle belegten M13-Kandidaten im PR nachvollziehbar erledigt, begründet erhalten, verworfen oder als tatsächlich unabhängige konkrete offene Aufgabe erfasst; keine unbegründeten Reste, kein behaupteter Vollständigkeitsnachweis jenseits der durchgeführten Prüfungen. Gültige Funktionen, Architektur-/Datenverträge und Schutztests erhalten. Nach erfolgreichem Gate und Merge den Meilenstein gemäß [Pflege](#pflege) vollständig aus dem Arbeitsregister entfernen; L01 unverändert erhalten.

## Unabhängige Aufgaben

### L01 — Lizenzklärung

**Status:** READY.

libmpv/FFmpeg-AAR-Lizenz, enthaltene Bibliotheken und ABIs, Corresponding Source und Weiterverteilungspflichten einschließlich bereits öffentlich verfügbarer Debug-Artefakte klären ([NOTICE](../NOTICE)). Keine Lizenzfreigabe oder Behauptung eines Rechtsverstoßes allein aus der Wrapper-Lizenz. Vor weiterer öffentlicher Distribution klären, unabhängig vom lokalen Projektabschluss.

## Pflege

**Merge-Regel:** Mit dem Merge eines PR sind sämtliche Geräteprüf- und Geräteabnahmeaufgaben seines integrierten Umfangs abgeschlossen. Keine separaten Gerätestatus, Nachabnahmen oder Verschiebungen in spätere Meilensteine. Noch nicht implementierte Funktionen und konkrete offene Fehler bleiben reguläre Aufgaben. Die technische Prüfung bleibt Bestandteil der Umsetzung; tatsächliche Geräteausführung nur bei entsprechendem Nachweis behaupten.

| Status | Bedeutung |
| --- | --- |
| `READY` | Ausführbar; aktive Bearbeitung und Prüfstand im zugehörigen PR |
| `BLOCKED` | Blocker und nächsten Schritt benennen |
| `PLANNED` | Abhängig/vorläufig, nicht freigegeben |

- Nur offene Aufgaben und ihre noch relevanten Abhängigkeiten führen. Jede eigenständig ausführbare Aufgabe besitzt eine stabile, nicht wiederverwendete ID, eigene Überschrift, einen maßgeblichen Status sowie risikogerechte Angaben zu Umfang, Akzeptanz und Prüfung.
- Neue Aufgaben ausschließlich hier erfassen. Aufgabenwahl nach [AGENTS](../AGENTS.md#arbeitszyklus) auf integriertem Stand; erforderliche Nutzerfreigaben bleiben verbindlich. Nächsten regulären Entwicklungsschritt ausdrücklich benennen oder bei fehlender freigegebener ausführbarer Aufgabe „Kein regulärer Entwicklungsschritt“ vermerken und den automatischen Arbeitsauftrag beenden. Unabhängige Aufgaben nur bei einschlägigem Auftrag oder tatsächlich relevanter Freigabebedingung beginnen; ihr Status READY allein genügt nicht. Vorübergehende Bearbeitungs- und CI-Stände im PR gemäß AGENTS festhalten; dauerhafte aufgabenbezogene Blocker mit nächstem Schritt im Register pflegen. Die integrierte Roadmap bleibt bis zum Merge maßgeblich.
- Bei vollständig umgesetztem Umfang den Abschluss im Draft vor dem finalen Gate bedingt vorbereiten; erfüllte Kriterien und vorhandene Prüf-/Reviewbelege im PR dokumentieren, ausstehende CI ausdrücklich als solche kennzeichnen. Erfolgreiche CI-Belege nach der Prüfung in PR-Metadaten ergänzen. Weiterhin gültige Erkenntnisse in den maßgeblichen Vertragsdokumenten sichern. Den zur Integration vorgesehenen Eintrag einschließlich erledigter Unteraufgaben im selben PR vollständig entfernen; erfüllte Abhängigkeiten sowie obsolete Verweise auf entfernte Einträge auch in Beschreibungen und verlinkenden Dokumenten entfernen, verbleibende Abhängigkeiten und den nächsten Entwicklungsschritt konsistent aktualisieren. Nachfolgende Aufgaben im vorbereiteten Integrationsstand nur dann auf READY setzen, wenn mit erfolgreichem Gate und Merge sämtliche Voraussetzungen erfüllt sind und die erforderliche Freigabe vorliegt; sonst PLANNED bzw. BLOCKED belassen und Grund benennen. Wenn alle Aufgaben eines Meilensteins einschließlich Gate abgeschlossen sind, auch seine Überschrift und Beschreibung entfernen. Erst der Merge macht die vorbereitete Entfernung und Statusfortschreibung wirksam.
- Nutzerseitig verworfene Aufgaben ebenfalls vollständig entfernen; die Entscheidung im PR belegen. Keine abgeschlossenen oder verworfenen Einträge, DONE-Tabellen, Chroniken, historischen Linksammlungen oder parallelen Aufgabenregister anlegen. Git und PRs bewahren die Historie. Abwesende IDs nicht als neue Aufgaben interpretieren oder ohne Auftrag wiedereröffnen; falls ein Abhängigkeitsnachweis nötig ist, den integrierten Stand und den betreffenden PR gezielt prüfen.
- Ein unabhängiger offener Blocker oder eine Freigabebedingung bleibt bis zur tatsächlichen Klärung erhalten. Keine Abschlussquote aus ungleich großen Aufgaben.
