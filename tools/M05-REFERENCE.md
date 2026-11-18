# Synthetische M05-Referenz (T0.2)

Keine realen Profile, Cookies, HARs oder Bilder. Der Generator verwendet ausschließlich das für den bereits bestehenden Import-Test verwendete native v1-Schema, künstliche Identitäten und selbst erzeugte geometrische PNGs. Kein Produktionscode und keine Laufzeitabhängigkeit. `example.invalid` ist absichtlich nicht erreichbar. Die Testdaten sind keine Kopie der Legacy-Datenbank und der Szenariokatalog ist kein simuliertes Testergebnis.

```sh
python3 tools/m05-reference.py /tmp/m05-reference --clock 2026-11-18T12:00:00+00:00
```

Ausgabe: acht Personen, neun Anzeigen, 18 Snapshots, sechs Präferenz/Favorit-Kombinationen, SQLite-v1-Datei, `m05-synthetic.zip` für die reguläre Android-Importfunktion und `scenarios.json` mit denselben Personen/Inseraten, stabilen IDs und Testfällen. Für die Live-Watchlist-Prüfung den Zeitpunkt weglassen (aktuelle UTC-Zeit). Statische Screenshots mit fixem Zeitpunkt; bei exakt 36 h auf die Fortschreibung der Gerätezeit achten. Ein Verzeichnis muss neu sein; keine bestehende DB wird überschrieben. Für Android nur einen frischen Debug-App-Datenbestand verwenden, nicht private Daten löschen oder ersetzen. Flugmodus für Cache-/Fehlerszenarien, keine Live-Suche auf synthetischen IDs.

Der bestehende CI-Job erzeugt das Archiv und ruft zusätzlich den gezielten Rust-Importtest auf:

```sh
(cd rust && M05_REFERENCE_BUNDLE=/tmp/m05-reference/m05-synthetic.zip cargo test --workspace --locked m05_reference_bundle_import -- --ignored)
```

Der Test ist bewusst opt-in (erfordert das erzeugte Archiv) und wird in CI explizit ausgeführt. Er prüft Import, Medienpfad, Präferenz, Notiz, Snapshot-Anzahl und Fremdschlüssel; noch keine Geräte-UI.

## Abnahmeverfahren

- Android: APK aus dem festgelegten Ausgangscommit bauen/installieren, ZIP über Einstellungen importieren, Offline-Feed, Suche, Details, gespeicherte Suchen und Karte öffnen. Screenshots bei 360/390/430/600 dp, normal/emphatisch skalierter Schrift und hell/dunkel; Route, Zustand, UTC-Zeit und APK-SHA notieren. Fehlende Daten und bestehende UI-Lücken sind ausdrücklich Teil der Baseline.
- Legacy: separaten Checkout von `9c514af6ce3659089807fefd4ed5040599975a5a` und isolierten Testserver/-DB verwenden. Die semantischen Szenarien/IDs aus `scenarios.json` über die **Legacy-eigene** Daten-/API-Fixture-Schnittstelle nachbilden; nicht die erzeugte native v1-SQLite unverändert als SQLAlchemy-Datenbank öffnen. Nach Bootstrap und Microtasks Screens vergleichen, insbesondere Requested Fixes. Kein Live-Netzwerk und kein persönlicher Bestand.
- Je P-ID: erwartete Sichtbarkeit, Reihenfolge, Zustand und Gesten anhand der Matrix vergleichen; Screenshot/Protokoll mit Fixture-ID, Viewport, Commit und Ergebnis ergänzen. 0/1/viele/partial/unverified und Stale/Cancel sind zusätzlich Mock-Transport-Szenarien, nicht allein durch den Import nachgewiesen.
- Der separate Offline-Browser-Adapter unten nutzt die echte Legacy-Template-/Bootstrap-Kette. Echte Browser-/Geräteabnahme nicht aus bloßem Vorhandensein des Tools ableiten.

Befehle und Zustände im Aufgabenregister dokumentieren. Generierte ZIP/DB/JSON und Screenshots nicht in Git einchecken.

## Offline-Browser-Referenz

Separater sauberer Checkout des privaten Legacy-Repositorys auf dem oben festgelegten Commit. Python ≥3.11, Playwright mit installiertem Chromium (nur lokales Testwerkzeug, keine App-Abhängigkeit).

```sh
python3 -m pip install playwright
python3 -m playwright install chromium
python3 tools/m05-legacy-browser.py /path/to/ladies-webapp /tmp/m05-reference /tmp/m05-browser
```

Optional `--chromium /usr/bin/chromium`. Der Adapter liefert die unveränderten lokalen JS/CSS/Template-Dateien und den originalen Filterkatalog, fängt HTTP-Anfragen im Browser ab und stellt ausschließlich synthetische JSON/PNG-Antworten bereit. Alle externen Requests werden blockiert. Unbekannte lokale API-Aufrufe und JS-Ausnahmen führen zu Fehlerstatus; Evidenz JSON enthält auch Teilergebnisse. Keine echte Website-/Messaging-Verbindung, keine schreibenden API-Funktionen. Vier Viewports, Suchscreen, Swipe, Grid, Profil/Services/Verlauf, Favoriten und gespeicherte Suchen/Ergebnisse. Kein MapLibre-Kachelrender, keine vollständige Mutations-/Gesture-/Dark-/Fontscale-Abnahme. Browser-Adapter muss erstmals mit Originalcheckout ausgeführt und ggf. anhand tatsächlicher Fehler vervollständigt werden. Private Legacy-Repo-Berechtigung nicht über Android-CI umgehen.
