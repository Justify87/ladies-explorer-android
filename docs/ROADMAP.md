# Arbeitsregister

Einzige operative Quelle für offene Aufgaben, Reihenfolge, Status und Abnahme. Erledigte oder verworfene Aufgaben stehen ausschließlich in Git und den zugehörigen PRs. Maßgebliche Verträge: [PRODUCT](PRODUCT.md), [ARCHITECTURE](ARCHITECTURE.md), [DESIGN](DESIGN.md). Arbeitsweise: [AGENTS](../AGENTS.md); Prüfungen: [README](../README.md#prüfung--ci).

## Nächster Entwicklungsschritt

M13.2 — Native API, UniFFI und Rust-Altlasten. L01 bleibt unabhängig vor weiterer öffentlicher Distribution zu klären.

## M13 — Dead Code & Refactoring Cleanup

Ziel: die integrierte Codebasis repositoryweit auf entbehrliche, abgelöste und redundante Implementierungen prüfen und belegte Befunde ursächlich bereinigen. Kein neues Produktverhalten, keine pauschale Neustrukturierung, keine Wiedereröffnung historischer Aufgaben ohne aktuellen Befund. Die sechs Aufgaben sind sequenziell; Durchführung und Abschluss nach [AGENTS](../AGENTS.md#arbeitszyklus), [laufender Bereinigung](../AGENTS.md#laufende-bereinigung), [README](../README.md#prüfung--ci) und [Pflege](#pflege).

**M13-spezifische Prüfmethode und Übergabe:**
- Jede versionierte Datei im Inventar einer sachlich zuständigen Aufgabe zuordnen und im Verlauf des Meilensteins ihrer Funktion entsprechend semantisch prüfen. Eigenen Quellcode dateiweise tiefgehend untersuchen, einschließlich Rust-interner Pfade ohne FFI-Bezug. Generierte Dateien, Fremdcode, Binärdaten und Assets hinsichtlich Herkunft, Einbindung, Nutzung und Schutzbedarf prüfen, nicht blind umschreiben. Nicht prüfbare Bereiche und Gründe im PR offenlegen.
- Statische Referenzen und Compiler-/Lint-/Clippy-Diagnosen sind Hinweise, keine alleinigen Löschbeweise. Auch indirekte Aufrufer, Framework-, Manifest-, Ressourcen-, Reflection-, JNI-/FFI- und Serialisierungsverträge, Feature-Konfiguration, Skripte und Testtargets berücksichtigen. Vertikal über die betroffenen Schichten und horizontal über gemeinsame Zustände und alternative Einstiegspunkte prüfen. Abgelöste Pfade mit exklusiven Helfern, Records, Fehlern, Tests, Ressourcen und Verweisen vollständig entfernen. Gültige Produkt-, Design-, Daten-, Architektur- und Schutzverträge erhalten; insbesondere interne Schemaentwicklung, Parser-/Netzwerk-/Mediengrenzen, Cursor-/Revisions-/Owner-Verträge, Cancel-/Drain-/Epoch-Schutz und notwendige Diagnose.
- Je Aufgabe Inventarfortschritt, konkrete Kandidaten mit Codeankern, Beleg, Aufrufern, Risiko und Entscheidung im PR festhalten. Zu Beginn die integrierten Vorgänger-PRs des Meilensteins gezielt auswerten und übernommene offene Befunde am aktuellen Stand verifizieren. Vor Abschluss jeden noch offenen Befund mit zuständiger verbleibender Aufgaben-ID, Codeanker und Grund im PR übergeben; Umfang bzw. dauerhafte Blocker der zuständigen offenen Aufgabe bei Bedarf im selben Roadmap-Diff präzisieren. Spätere Aufgaben übernehmen diese Befunde ausdrücklich und dokumentieren ihre Entscheidung. Bestätigte Befunde im eigenen Umfang ursächlich beheben; nicht einfach zur Abschlussaufgabe verschieben. Bei bereits entfernten Aufgaben keine IDs wiedereröffnen; neu belegte Folgefehler einer sachlich zuständigen noch offenen Aufgabe zuordnen, gegebenenfalls M13.6. Unabhängige konkrete Fehler nach [Pflege](#pflege) erfassen. Keine zusätzlichen Register oder historischen Einträge in der Roadmap.

### M13.2 — Native API, UniFFI und Rust-Altlasten

**Status:** READY.

Sämtliche eigenen Rust-Produktionsdateien einschließlich interner Module, privater Algorithmen und Rust-interner APIs unabhängig von FFI-Nutzung semantisch prüfen. Native Exporte und Records gegen tatsächliche CoreStore-/Kotlin-Aufrufer, regulär generierte Bindings, Android-/JNI-/Manifest-/R8-Konfiguration sowie Rust-interne und Testnutzer abgleichen. Obsolete FFI-Exporte, Wrapper, optional gewordene Altpfade, verwaiste Typen, Felder, Fehler und exklusive Helfer vertikal entfernen; benötigte Rust-interne APIs nicht wegen fehlender FFI-Aufrufer löschen. Rein testbezogene native Hilfen in Testmodule bzw. `cfg(test)` verschieben und auf minimale Fixtures begrenzen. Insbesondere Profil-/Medien-/Historien-/Identitäts-/Such-/Saved-People-/Diagnosepfade sowie interne Pagination, RequestControl, Queue-/Shutdown-/Recorder- und Revisionsverträge auf aktuelle Notwendigkeit prüfen. Keine Änderung des Datenformats, Schemas oder gültiger Migrationsverträge zur bloßen Bereinigung.

**Akzeptanz/Prüfung:** Dateibezogener Rust-Prüfnachweis und Inventar der überprüften nativen Symbole mit Entscheidungen; kein bestätigter entbehrlicher Produktions-/Exportpfad im Umfang. Bindings regulär generiert, FFI-Aufrufer konsistent; Rust fmt/test/clippy, native synthetische Fixtures, Architektur-/Exportverträge, Android-Integration einschließlich APK-Vertrag und erforderliches Gate.

### M13.3 — Aktive Redundanz und semantisches Refactoring

**Status:** PLANNED. Abhängig von M13.2.

Verbleibende aktive Duplikate, unnötige Abstraktionen/Delegationen, redundante Daten- und Zustandsprojektionen, Übergangsimplementierungen und konkurrierende Verantwortlichkeiten in UI, Operations-/Lifecycle-Orchestrierung, CoreStore und Rust prüfen. Nur nachgewiesen gleichwertige Pfade vereinheitlichen, Verantwortlichkeiten am vorhandenen fachlichen Owner bündeln, vereinfachbare Pfade direkt ersetzen und abgelöste Implementierungen entfernen. Unterschiede zwischen Listing-/Person-Identität, CAS-/Owner-Prüfungen, Saved-/Discovery-/Standalone-Kontexten, Suchfenstern, Medienvorbereitung und Fehler-/Cancel-Semantik nicht als Duplikate behandeln, sofern fachlich verschieden.

**Akzeptanz/Prüfung:** Jede Konsolidierung durch konkrete Redundanz und Verhaltensäquivalenz begründet; keine zusätzlichen Parallelpfade oder spekulativen Abstraktionen. Betroffene horizontale Einstiegspunkte und vertikale Verträge einschließlich Integrität und Nebenläufigkeit mit angemessenen Regressionen und erforderlichem Gate geprüft; bei fehlendem Beleg Pfad erhalten und begründen.

### M13.4 — Testcode und Fixtures

**Status:** PLANNED. Abhängig von M13.3.

Sämtliche eigenen Rust-, JVM-, Instrumentierungs- und Python-Testdateien samt Fixtures, Helfern und Testkonfiguration semantisch prüfen: obsolete API-Wrapper, redundante oder wirkungslose Assertions, tote Fixtures, mehrfach abgesicherte identische Verträge, unproduktive Mock-/Roundtrip-Tests und unnötige Kopplung. Test-Discovery, Filter, Ignore-/Skip-Bedingungen und tatsächlich ausgeführte Tests anhand vorhandener Berichte prüfen; bewusst separat ausgeführte Tests und nicht automatisch ausgeführte Instrumentierung unterscheiden. Sicherstellen, dass behauptete Fehlerfälle tatsächlich ausgelöst und beobachtet werden, positive und negative Kontrollfälle den realen Produktionsvertrag prüfen und keine unbeabsichtigten No-ops, Selbst-Joins oder unbeschränkten Wartepfade bestehen. Belegte Redundanzen konsolidieren, nützliche Regressionen auf aktive Produktionspfade übertragen; minimale testexklusive SQL-/Datenfixtures und deterministische Synchronisationshilfen beibehalten. Fehler-, Owner-/Cursor-/Zeitstempel-/Schema-/Revisions-/Cancel- und Integritätsregressionen nicht mit entfernten Produktionswrappers verlieren. Testschichten nach [AGENTS](../AGENTS.md#laufende-bereinigung); keine künstlichen Coverage-Ziele, neue Testframeworks oder realen Quellenzugriffe.

**Akzeptanz/Prüfung:** Entfernte oder ersetzte Tests nachvollziehbar einem weiterhin gültigen Vertrag beziehungsweise dessen belegtem Wegfall zugeordnet; notwendige positive/negative Abdeckung, aussagekräftige Assertions und deterministische Ausführung erhalten. Betroffene Test-Discovery und Ausführung anhand verfügbarer Nachweise geprüft, unbeabsichtigte Auslassungen behoben; gezielte Prüfungen und erforderliches Gate. Instrumentierungs-Build nicht als Geräteausführung ausgeben.

### M13.5 — Abhängigkeiten, Build, Tools und semantische Dokumentationspflege

**Status:** PLANNED. Abhängig von M13.4.

Sämtliche zugeordneten Manifeste, Gradle-/Cargo-Abhängigkeiten, Cargo-Features, Gradle-Varianten, Lockfiles, native AAR-/ABI-/Ressourcenreferenzen, Skripte, Tool-Aufrufer, CI-/Build-/Testkonfiguration sowie bedingte Kompilierungs- und Ausführungspfade semantisch auf Notwendigkeit prüfen. Transitive und dynamische Nutzung bei konkretem Verdacht mit vorhandenen Dependency-/Build-Diagnosen klären. Nur belegte Altlasten entfernen; Schutzprüfungen, wirksame Konfiguration, Lizenz-/Provenance-Verträge und erforderliche Buildpfade erhalten. Kein pauschales Upgrade, kein CI-Laufzeitprojekt und keine Änderung der unabhängigen Lizenzklärung L01.

Repositoryweit sämtliche eigenen Dokumentationsdateien und verbliebenen Kommentare/KDoc/Rustdoc semantisch auf Aktualität, Notwendigkeit, Widerspruch, Redundanz und korrekte Verweise prüfen, auch unabhängig von bisherigen Codeänderungen. Abgelöste Verträge, Beispiele, Verweise und reine Historie entfernen; gültige Aussagen an ihrer maßgeblichen Stelle, notwendige Begründungen, rechtliche Header und bewusst erhaltene Referenzmaterialien gemäß ihrer Funktion schützen. Bereinigungsregeln des [Harness](../AGENTS.md#laufende-bereinigung) verwenden; nur konkret belegte dauerhafte Lücken dort korrigieren, keine Duplikate.

**Akzeptanz/Prüfung:** Für entfernte Abhängigkeiten und Konfigurationen Beleg der Nichtnotwendigkeit einschließlich relevanter bedingter Nutzung; betroffene Build-/Tool-/Testpfade geprüft. Dokumentationsumfang nachvollziehbar abgeglichen; gültige Vertragsinhalte, Referenzen und dokumentlokale Semantik erhalten, keine parallelen Register oder Abschwächung von Schutzverträgen. Vorhandene relevante Prüfungen und erforderliches Gate gemäß tatsächlichem Diff.

### M13.6 — Abschluss der repositoryweiten Bereinigung

**Status:** PLANNED. Abhängig von M13.5.

Inventarfortschritt und übergebene Kandidaten aus allen integrierten M13-PRs gegen den tatsächlich integrierten Stand abgleichen. Verfügbare Compiler-/Lint-/Clippy-, Architektur-, Export-, Build- und Testdiagnosen der relevanten Prüfungen dem geprüften Code zuordnen und auf bestätigte Altlasten, Folgefehler und verwaiste schichtübergreifende Referenzen auswerten; ein grünes Gate allein ersetzt diese Auswertung nicht. Fehlende oder abgelaufene Berichte sowie daraus folgende Prüfgrenzen offenlegen; bei konkretem ungeklärtem Risiko notwendige Nachweise mit bestehenden gezielten Prüfmitteln nachholen. Nur konkrete verbleibende M13-Befunde ursächlich beseitigen; notwendige Pfade oder bloße Stilhinweise nicht künstlich zum Dead Code erklären. Kein erneuter vollständiger Audit ohne konkreten Anlass. Bei reinem Registerabschluss ausreichende integrierte Full-CI-Belege zuordnen und das erforderliche Dokumentationsgate nutzen; kein zusätzlicher Full-CI-Lauf allein zum Abschluss. Bei Code-/Build-relevanten Änderungen gilt das vollständige Gate.

**Akzeptanz/Prüfung:** Dateiinventar und tatsächliche Prüfabdeckung aller Aufgaben abgeglichen; jeder belegte Kandidat nachvollziehbar erledigt, begründet erhalten, als unbegründet verworfen oder als tatsächlich unabhängige konkrete offene Aufgabe erfasst. Keine unbegründeten Reste und kein behaupteter Vollständigkeitsnachweis jenseits der erfolgten Prüfungen. Gültige Funktionen, Architektur-/Datenverträge und Schutztests erhalten. M13-Abschluss im Draft nach [Pflege](#pflege) vorbereiten; mit erfolgreichem Gate und Merge entfällt der gesamte Meilenstein, L01 bleibt erhalten.

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
