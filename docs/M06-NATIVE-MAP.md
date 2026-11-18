# M06 – Native Karte mit MapLibre

## Umfang
- Fünfter Tab **Karte** mit nativer MapLibre-Android-Karte in Compose, GeoJSON-Clustern, Profilauswahl und Öffnen der bestehenden Profilansicht.
- **Alle Profile / nur Favoriten**, ein aktuelles Inserat pro Person. Rust liefert die Marker per UniFFI; SQLite bleibt ausschließlich Rust-verwaltet.
- Auflösung ausschließlich ungefährer Standorte: deutsche **PLZ-Zentren**, sonst **Stadtzentren**. Keine Haus-, Straßen- oder GPS-Position eines Profils; es werden keine exakten `hidden_location`-Adressen an Geocoder übertragen.
- `app_settings` hält den lokalen Koordinatencache unter `map:postal:DE:...` bzw. `map:city:DE:...`. Deshalb **keine Schema-Migration**. Cache bleibt nach Neustarts offline abrufbar.
- Kein automatisiertes Geocoding beim App-Start. Der Nutzer tippt auf **Standorte auflösen**. Ein Lauf ist auf 15 verschiedene Schlüssel begrenzt; ein weiterer Lauf ist erst nach Abschluss des aktuellen Auftrags möglich. Der Cancel-Button beendet die Verarbeitung nach der laufenden HTTP-Anfrage. Weitere Orte werden beim nächsten ausdrücklichen Durchlauf ergänzt; vorübergehend fehlgeschlagene Schlüssel rotieren hinter noch nicht versuchte Schlüssel.

## Externe Infrastruktur

- Kartendarstellung: **MapLibre Android SDK 13.5.4**, eingebettet in Compose per `AndroidView` (kein WebView und kein eigener Server).
- Kartenstil/Kacheln: `https://tiles.openfreemap.org/styles/liberty`. Die Basiskarte benötigt Netzwerk, solange benötigte Kacheln nicht gecacht sind.
- PLZ: `https://api.zippopotam.us/DE/<plz>`. Fehlende Postleitzahlen erhalten bei einer späteren Benutzeraktion einen Stadt-Fallback.
- Stadt: `https://nominatim.openstreetmap.org/search`; nur Deutschland, maximal ein Ergebnis, mindestens 1,1 Sekunden Abstand zwischen Aufrufen auch über Vorgänge hinweg.
- HTTP-Timeout: neun Sekunden pro Standort. Netzwerkfehler werden nicht als dauerhafte Fehlstellen gespeichert. Nur bestätigte Nichttreffer haben eine sieben Tage gültige Negativ-Cachemarkierung. Unerwartete JSON-Formate bzw. ungültige Koordinaten sind Providerfehler.
- Der Server sieht ausschließlich PLZ beziehungsweise Stadt, nicht persönliche Notizen, Telefonnummern oder Adressen.
- Der Kartenstil unterliegt den Lizenz-/Attributionsbedingungen des jeweiligen Kachelservers. Der MapLibre-Renderer wird mit seiner eigenen Lizenz eingebunden.

## Datenfluss

1. Rust ermittelt aus `listings`, `listing_snapshots`, `search_results` und `person_user_state` die aktuelle Karte pro Person. Leere oder nicht auflösbare Orte bleiben bewusst **ohne Marker**.
2. Rust liest vorhandene gültige Cachekoordinaten. `mapOverview(favoritesOnly)` ist vollständig offline und löst niemals Netzwerkanfragen aus.
3. Ein ausdrücklicher Nutzerauftrag `resolveMapLocations(favoritesOnly, 15, control)` ergänzt den Cache und liefert den aktualisierten Kartenbestand zurück.
4. Compose aktualisiert die GeoJSON-Quelle; MapLibre clustert die Positionen. Tippen auf ein einzelnes Profil zeigt die lokale Profilauswahl. Profile am gleichen PLZ-/Stadtzentrum werden gemeinsam angeboten.
5. Der vorhandene `loadProfile(listingId, personId)`-Datenfluss zeigt wie in M05 zunächst aktuelle Quelldaten und fällt bei Netzproblemen auf die archivierte Version zurück.

## Einschränkungen und Abnahme

- Stadtzentren sind Näherungen: Marker sind **nicht** als Treffpunkt, Navigation oder exakte Adresse geeignet.
- Ein Standort kann mehrfach vorkommen (verschiedene Profile im selben Postleitzahlgebiet). Bei Auswahl eines individuellen Markers zeigt die UI die Profile derselben Position gemeinsam.
- Ohne passenden Geocoding-Eintrag werden keine Ersatzkoordinaten erfunden.
- Nicht alle Städte und Schreibweisen lassen sich über öffentliche Geocoder zuverlässig auflösen. Den Fehlzähler beachten, später erneut versuchen.
- Native Karte benötigt keinen eigenen App-Prozess; MapView-Lifecycle ist an den Compose-Screen gebunden.
- Automatische Hintergrundstandortauflösung, manuelle Kartenmarker, Navigation/Routing, Treffpunkte und lokale Offline-Kachelpakete sind bewusst nicht Bestandteil von M06.

**Prüfung:** Rust-Regressionsfälle zu Identitäts-Deduplizierung, Favoriten, Offline-Cache, deutscher PLZ und Stadt-Fallback. CI: Rust format/test/clippy sowie ARM64 Android/UniFFI-Build, JVM-Tests, Android Lint. Reale Karte/Kacheln und Geocoder müssen zusätzlich auf einem Android-Gerät verifiziert werden.

## Review-Korrekturen

- Kartenkamera und Profilauswahl werden über Compose SaveableStateHolder bei Tab- und Profilwechseln erhalten. Der Lifecycle-Observer synchronisiert die MapView genau einmal; Ladefehler haben eine native Wiederholmöglichkeit.
- Rust hält eine zentrale Geocoding-Auftragslease und die Nominatim-Pause über alle Läufe. Nach Abschluss oder Abbruch liest die UI den aktuellen DB-Stand erneut. Bereits gecachte Stadtzentren bleiben während der PLZ-Auflösung sichtbar.
- Der bestehende HTTP-Recorder erfasst Geocoder-Anfragen im bewusst aktivierten HAR-Modus. MapLibre-Kachelrequests werden nicht über den Rust-/Coil-Recorder erfasst.
- Referenzen: [MapLibre Quickstart](https://maplibre.org/maplibre-native/android/examples/getting-started/), [CameraPositionActivity](https://maplibre.org/maplibre-native/android/examples/camera/cameraposition/), [GeoJSON-Updates](https://maplibre.org/maplibre-native/android/examples/geojson-guide/), [Android Lifecycle](https://developer.android.com/reference/androidx/lifecycle/Lifecycle), [Nominatim Policy](https://operations.osmfoundation.org/policies/nominatim/).
