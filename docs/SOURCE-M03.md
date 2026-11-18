# M03 – Native Suche und Profil-Extraktion

## Quelle und Funktion

Referenz: Justify87/ladies-webapp bei Commit
9c514af6ce3659089807fefd4ed5040599975a5a, besonders:
ladies/source.py, ladies/parser.py, ladies/search_catalog.py,
ladies/core/services/search.py, ladies/core/repositories/search.py und
ladies/core/repositories/persons.py.

Die Suche läuft vollständig in Rust, im Android-Prozess, ohne Python,
Flask, lokalen HTTP-Server oder Cloud-Backend. Native Kotlin/Compose
greift ausschließlich über UniFFI darauf zu.

Die Rust-Module sind nach Verantwortung gegliedert:
- source/filter.rs: Filtervalidierung und Pfadbildung aus der originalen
  Filterliste, die in filter_catalog.json eingecheckt ist
- source/network.rs: HTTPS, Ortsauflösung, Pagination und Radiusgrenzen
- source/parse.rs: HTML-Suchkarten, Profilfelder und geordnete Medienreferenzen
- source/storage.rs: atomare SQLite-Speicherung und Identitätsinvarianten

## Rust-Suche

Die typisierte SearchRequest-Struktur nimmt Ort, Radius, Rubrik,
Alters- und Größenbereich, ausgewählte Filter als JSON und ein
explizites Seitenlimit entgegen. Radiuswerte sind wie im alten Dienst
auf 5/10/20/30/50/70/100 km beschränkt. Die Rubriken, Gruppen,
Wert-Aliase und Quell-Slugs folgen der bisherigen Web-App.

Das ursprüngliche HTML von Seite 1 liefert die Pagination über die
SearchConfig-Markierungen. Fehlen diese, fragt Rust wie die Web-App
die zweite API-Seite ab. Weitere Seiten werden über die Quell-API
angefordert. Die maximal zu ladenden Seiten sind pro Anfrage
begrenzt (1–50); ein abgebrochener Lauf ist im Ergebnis ausdrücklich
als partial markiert. Die Suche dedupliziert über die originale
externe Inserat-ID. Ein leeres Suchergebnis wird nur akzeptiert, wenn
die Quelle ausdrücklich null Inserate meldet (oder HTTP 404 liefert).
Nicht erkennbare HTML-Seiten werden als Parserfehler behandelt, nicht
als gültige Null-Treffer-Suche. Ein anhand des dokumentierten
`page-section`-/`affiliate-card`-Platzhalters und der
`load-adverts-next-distance`-Schaltfläche eindeutig erkannter
Radiusübergang auf einer API-Folgeseite beendet die Pagination
ohne Parserfehler. Bis dahin erkannte Anzeigen bleiben erhalten.
Der Übergang ist ein reguläres Ende der gewählten Umkreissuche: Sind
Radius und die vollständige Inseratzahl der Quelle bestätigt, ist der
Lauf nicht `partial`. Fehlt die Gesamtzahl oder weicht sie von den
eindeutigen Profilen ab, wird er weiterhin als `partial` gespeichert.
Die Diagnose protokolliert pro API-Seite erkannte Karten sowie nach
Deduplizierung die Abweichung zur Quellzahl. Bei einem Quellzahlkonflikt
werden keine fehlenden Inserate erfunden.

Wichtig: Wenn die Quelle einen größeren als den angeforderten Radius
meldet, akzeptiert die neue App keine Daten aus dem erweiterten
Radius. Auf Seite 1 ist dies ein Fehler ohne SQLite-Schreibzugriff,
auf Folgeseiten ein explizit unvollständiges Suchergebnis.
Die alte Web-App konnte dagegen automatisch auf den größeren Radius
wechseln. Das Verhalten weicht hier absichtlich zugunsten eines
klaren Radiusvertrags ab.

Fehlt die Radiusangabe der Quelle, wird die Suche ausdrücklich als
unvollständig und der Radius als nicht bestätigt ausgewiesen. Der
Datenbankwert `distance_searched` bleibt dann NULL, statt den
angeforderten Radius fälschlich als beobachteten zu speichern.

Der HTTP-Client hat 25 Sekunden Timeout pro Anfrage, ein 8-MiB-Limit
pro Antwort, 350 ms Abstand zwischen Seiten, keine HTTP-Redirects,
eine feste Ziel-Domain und eine echte URL-Validierung von Profilen.
HTTP-/Parserfehler werden über typisierte UniFFI-Fehler an Kotlin
weitergegeben, ohne unvollständige Daten als Erfolg zu tarnen.

## Profil-Extraktion

Über load_source_profile(listing_id) wird nur ein bereits lokal
bekanntes Inserat geladen. Der Servername wird niemals aus einem
freien Benutzer-URL übernommen. Anzeigen-IDs im Quell-HTML werden
mit der erwarteten Inserat-ID abgeglichen.

Der HTML-Parser liest sichtbaren Namen, Ort, Beschreibung,
Postleitzahl, verstecktes Ortsattribut, Profil-ID, Canonical-ID,
Telefon, Merkmale, Dienstleistungen, Öffnungszeiten sowie die
Galerie-Referenzen. Relative und protokollrelative Links, CSS-Bildquellen
und der reine HTML-Text werden vor der URL-Klassifikation verarbeitet.
Hauptbild und Galerie behalten ihre gemischte Bild-/Video-Reihenfolge;
bei gleicher Foto-ID wird die A- statt der F-Variante bevorzugt.
18+- und Sterne-Markierungen werden in der Suchhistorie persistiert. Die Kontakte werden nur als
Profilinformationen gelesen – kein WhatsApp-Client.

Profil-Snapshots werden in der vorhandenen SQLite-Datenbank
anhand eines Inhalts-Fingerprints dedupliziert. Vorhandene
Personenzuordnungen und LIKE/DISLIKE/Favoriten bleiben erhalten.
Eine bereits einer anderen Person zugeordnete Quellprofil-ID wird
**nicht** automatisch zusammengeführt. Der neue Snapshot bleibt
vollständig erhalten; der Konflikt wird dedupliziert als
`identity_evidence` mit der Kandidatenperson dokumentiert. Dies
vermeidet Datenverlust ohne verfrühten Identitätsabgleich.
Jeder erfolgreiche Profilabruf wird außerdem als `PROFILE`-Beobachtung
vermerkt, auch wenn sich der Inhalts-Fingerprint nicht verändert.
Die aktive Ansicht wird auf den aktuellen Snapshot gesetzt.
Datenbank-Suche und Profilaktualisierung sind jeweils transaktional.

Die Bilder/Videos werden in M03 **nur als Remote-Referenzen**
gespeichert. Download, medienbasierte Identitätsprüfung, ChangeEvent-
Differenzierung und Archivierungsrichtlinien gehören zu M04.

## Android und offene Punkte

Unter Suche ist vorübergehend eine kleine, native Testansicht für
Ort, 10/20/50 km, Suche und Profil-Detailabruf verfügbar. Das ist
kein vorgezogenes Redesign; Swipe-Deck, vollständige Filtersteuerung,
gespeicherte Suchansicht und Material-3-UX folgen in M05.

Automatische KI-/OCR-Preiserkennung, internes Messaging und
WhatsApp-Synchronisierung bleiben ausgeschlossen.

## Prüfungen

Rust-Unit-Tests verwenden vollständig lokale HTML- und API-Mocks:
Filter/Aliase, Pagination, sichere Null-Treffer-Erkennung,
Radiusbestätigung/Unsicherheit, Kartenmarker, CSS- und relative
Galerie-URLs, Identitätsevidenz bei Konflikten, Beobachtungen,
SQLite-Idempotenz und bestehende Nutzerentscheidungen.

Die vollständige CI kompiliert Rust, generiert UniFFI-Kotlin-
Bindings und baut die Android-ARM64-Debug-APK.

Eine erfolgreiche Offline-CI beweist nicht die aktuelle
HTML-Kompatibilität der Website. Vor einer produktiven Abnahme
muss die Android-App deshalb mit realen Suchseiten/Profilen
getestet werden. Bei DOM-Änderungen soll der Parser eine sichtbare
Fehlermeldung ausgeben, statt fiktive leere Treffer zu erzeugen.
