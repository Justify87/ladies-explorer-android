# Arbeitsregister

Einzige operative Quelle für offene Aufgaben, Reihenfolge, Status und Abnahme. Erledigte oder verworfene Aufgaben stehen ausschließlich in Git und den zugehörigen PRs. Maßgebliche Verträge: [PRODUCT](PRODUCT.md), [ARCHITECTURE](ARCHITECTURE.md), [DESIGN](DESIGN.md). Arbeitsweise: [AGENTS](../AGENTS.md); Prüfungen: [README](../README.md#prüfung--ci).

## Nächster Entwicklungsschritt

SEC-D2D-01 — Android-Gerätetransfer privater App-Daten ausschließen. L01 bleibt unabhängig vor weiterer öffentlicher Distribution zu klären.

## Reguläre Aufgaben

### SEC-D2D-01 — Android-Gerätetransfer privater App-Daten ausschließen

**Status:** READY.

Konkrete Schutzlücke: `app/src/main/AndroidManifest.xml` setzt nur `android:allowBackup="false"`; `tools/check_architecture.py` (ARC-08) prüft in Bezug auf Backup nur dieses Attribut. `CoreStore.kt` legt `ladies.sqlite` unter `context.filesDir` und die Einstellungen in den SharedPreferences `diagnostics` und `explorer` ab. Auch zugehörige private Medien, Diagnose- und sonstige App-Daten sind einzubeziehen. Bei Android 12+ kann es laut [Android-Plattformvertrag](https://developer.android.com/about/versions/12/behavior-changes-12#backup-restore) je nach Hersteller trotz deaktiviertem Backup Gerät-zu-Gerät-Übertragungen geben. Dies lässt eine vom [Produktvertrag](PRODUCT.md#produktziel-und-grenzen) ausgeschlossene Nutzerdatenmigration zu; ein tatsächlich erfolgter Datentransfer ist nicht belegt.

**Akzeptanz/Prüfung:** Auf unterstützten Android-Versionen (Min-API 29, Target-SDK 37) private App-Daten aus den anwendbaren Backup- und Transferpfaden ausschließen: Legacy-Regeln für API 29–30 sowie getrennte Cloud-Backup- und Gerätetransferregeln ab API 31 gemäß [Auto Backup](https://developer.android.com/identity/data/autobackup); die Anwendbarkeit der Cross-Platform-Transferregeln ab API 36.1 prüfen und erforderliche Ausschlüsse berücksichtigen. Tatsächliche Speicherorte und Regelbereiche abgleichen, `allowBackup="false"` und bestehende Schutzregeln erhalten. Quell- und gebaute APK-Konfiguration einschließlich referenzierter Regeln mit vorhandenen Architekturprüfmitteln und positiven/negativen Regressionen absichern; Hersteller-/Gerätegrenzen offenlegen, tatsächliche Geräteübertragung nur bei entsprechendem Nachweis behaupten. Keine neue Import-/Exportfunktion, keine Änderung der Lizenzaufgabe.

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
