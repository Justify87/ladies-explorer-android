# Anforderungsinventar – UI und fachliche Parität

Stabile P-/X-IDs als Regressionsreferenz, kein Aufgabenregister. Status: [Arbeitsregister](ROADMAP.md); detaillierter [UI-Vertrag](M05-PLAN.md). Legacy-Referenz [9c514af6](https://github.com/Justify87/ladies-webapp/tree/9c514af6ce3659089807fefd4ed5040599975a5a). Vollständige historische Quell-/Testanker siehe [ursprüngliche Matrix](https://github.com/Justify87/ladies-explorer-android/blob/c6114a6f51b2c2ce99e986efd939ab9115708b70/docs/M05-PARITY.md). Keine alte Abnahme wieder öffnen; neuere native Darstellung ersetzt widersprechende Legacy-Gestaltung.

| ID | Bereich | Legacy-Referenz | Gültiger Fach-/UX-Vertrag |
| --- | --- | --- | --- |
| P01 | Design | tokens.css; layout/navigation.css | Legacy-Palette, Shapes, Typografie, Kontrast, Dark Mode |
| P02 | Shell | chrome.js; shell.css; index.html | Kontextheader, native Insets, 4 Hauptbereiche |
| P03 | Navigation | app/router.js; app/scroll_state.js | Back, Scroll-/Medien-/Sektionszustand, Standalone ohne Bottom Bar |
| P04 | Karte | ui/screens/discover.js; discover-map.css | Bestehende Karte im Ansichtswechsel; Datenbezug kenntlich |
| P05 | Suchzustand | effects/core.js; app/selectors.js; search_runtime.js | Aktiver Suchlauf getrennt von Filterentwurf, keine Vermischung |
| P06 | Grundfilter | ui/screens/search.js; components/filters.css | Ort, Radius, Alter, Größe, Treffpunkt, Kategorie mit korrekten Werten |
| P07 | Filterlayout | ui/screens/search.js; ux_patch.js | Gruppierung, Einklappen, Merken, globaler Filtertext, Footer/Reset |
| P08 | Häufige Filter | data/filter_usage.js; ui/screens/search.js | Top 5 aus tatsächlicher Nutzung, stabil/dedupliziert |
| P09 | Verfügbarkeit | search_intelligence.js; chrome.js | Alle/Jetzt/Heute kanonisch, nur Schnellwahl im Header führt Suche aus |
| P10 | Preview | effects/core.js: schedulePreview; search_preview.py | Debounce/Abbruch, ehrlicher Count bzw. unbekannt, keine DB-Suchpersistenz |
| P11 | Radius/Fehler | ui/screens/search.js; regressions.js | Keine Radiusaufweitung; verständliche Preview-/Suchfehler, partial |
| P12 | Lokale Filter | app/selectors.js; requested_fixes.js | Bilder, Videos, Like/Dislike; gemeinsame Projektion |
| P13 | Bild/Business | app/selectors.js; effects/core.js; business_profiles.py | ALL/AI/NATURAL; STUDIO/FACEAPP/Business nur mit Evidenz, sonst explizite Grenze |
| P14 | Suchabschluss | effects/core.js: performSearch | Erfolgreiche Suche öffnet Entdecken, Fehler/Abbruch preserves Zustand |
| P15 | Medienhero | ui/profile.js; features/discover.css; features/profile.css | Große responsive Galerie ohne obere Chrome; seitliche Tap-Zonen, Video Play/Pause |
| P16 | Medienfallback | ui/profile.js; effects/core.js | Reihenfolge, lokale Medien vor Remote, Fehler/Platzhalter; eingebettetes stummes Video über lokale Datei |
| P17 | Identität | ui/profile.js: renderIdentity | Name/Alter/Ort, Status, neu/bekannt/Video nur belegt |
| P18 | Aktionen | ui/profile.js; requested_fixes.js; overlays.js | Like/Favorit unabhängig, Kontakt, WhatsApp extern, Originalprofil/Mehr |
| P19 | Gemeinsamer Profilaufbau | ui/profile.js: renderSwipe/renderStandalone | Ein komponierbarer Inhaltsaufbau, keine doppelte Logik |
| P20 | Swipe-Queue | app/selectors.js; requested_fixes.js; app/commands.js | Aktive Ergebnismenge, Replay-/Hide-Likes, stabile IDs, eindeutige Reihenfolge |
| P21 | Swipe-Gesten | regressions.js; features/discover.css | Horizontale Entscheidung/vertikales Detail-Scrollen/Tap ohne Konflikt |
| P22 | Undo/Reset/Später | effects/core.js; app/commands.js | Undo persistiert vorherigen Zustand, Reset ohne Reset der DB, Später sessionlokal |
| P23 | Prefetch | effects/core.js: startSwipePrefetch/warmProfileMedia | Begrenzt, lifecycle-/epoch-gebunden, keine unnötige Archivierung |
| P24 | Grid | ui/screens/discover.js; components/listing-card.css | Zwei Spalten, 0,77 Aspect, Cover, Name/Alter, Gradienten |
| P25 | Grid-Aktion | requested_fixes.js; app/selectors.js | Direkte Like-Aktion ohne Profilöffnung, Fehler-/Filterverhalten |
| P26 | View-Switch | ui/screens/discover.js; app/reducer.js | Swipe/Grid/Karte wechseln ohne erneute Suche, Position erhalten |
| P27 | Profil-Tabs | ui/profile.js; requested_fixes.js; regressions.js | Info/Dienste/Analyse/Notizen/Verlauf im gemeinsamen vertikalen Profilfluss, keine Pflicht-Tabs |
| P28 | Info | ui/profile.js; requested_fixes.js; regressions.js | Info-Raster, Merkmale, Zeiten, einklappbare Beschreibung |
| P29 | Dienste/Filterbelege | ui/profile.js; requested_fixes.js | Dienste und ausgewählte Filterbelege Ja/Nein/Unbekannt im gemeinsamen Profilfluss |
| P30 | Analyse | ui/profile.js: renderAnalysis | Vorhandene Belege, keine erdachten Scores/AI; sichere Bestätigung bewahren |
| P31 | Personennotiz | ui/profile.js; requested_fixes.js; models.py | Personennotiz lesen/bearbeiten/speichern; getrennt von Treffen |
| P32 | Historie | ui/profile.js: renderHistory; effects/core.js | Timeline, alle Anzeigen, Original-Link, Snapshot-Vergleich |
| P33 | Gespeichert-Daten | ui/screens/favorites.js; app/selectors.js | FAVORITE/LIKE/DISLIKE, Personenkonsistenz, korrekte Anzahl/Sortierung |
| P34 | Gespeichert-Layout | favorites-redesign.css; ui/screens/favorites.js | Tabs, Thumbnail, Name/Alter/Ort, Herz, Menü, Datum/Name |
| P35 | Watchlist | ui/screens/favorites.js: CHANGE_WINDOW_MS | 36 h, drei initial, expandierbar, keine doppelten Einträge |
| P36 | Status | ui/screens/favorites.js; effects/core.js | Einzel-/Batchprüfung, wahrheitsgemäßer Status, Zeit, Progress, Teilfehler |
| P37 | Gespeicherte Suche | ui/screens/saved_searches.js; saved_searches.js | Eigene Liste, Header-Indikator, Delta, CRUD/Run/Acknowledge erhalten |
| P38 | Gemeinsamer Editor | ui/screens/saved_searches.js; ui/screens/search.js | Gemeinsamer Editor, kein Überschreiben importierter unsupported Regeln |
| P39 | Gespeichertes Grid | ui/screens/saved_searches.js | Gemeinsame Grid-Karte, Pagination/Offline, sichtbare Deltas |
| P40 | Zustände/UX | app/scroll_state.js; regressions.js; ux_patch.js | Konsistente Loading/Empty/Error, Wiederherstellung, Accessibility |
| P41 | Gesamtcheck | bootstrap.js; tools/check_* (Legacy) | Referenzvergleich und gezielte Regression; technische Prüfungen und Gerätebelege getrennt |
| P42 | Quellen-HTTP | — | Alle app-internen ladies.de-/CDN-Anfragen inkl. Videobytes ausschließlich Rust. |
| P43 | Medien | — | Coil-Cache, Offline, Prefetch und HAR über Rust; reine Anzeige ohne Archiv-/DB-Mutation. |
| P44 | Architekturgrenze | — | Automatisierte Architekturverträge; MapLibre und explizite externe Intents ausgenommen. |

## Profilvertrag

| ID | Gültige Anforderung | Bezug |
| --- | --- | --- |
| P45 | Ein gemeinsamer vertikaler Profilfluss; Swipe ohne Doppelroute, Standalone aus Grid/Karte/Suche/Gespeichert mit bestehendem Back und ohne Deckaktion; Merge in beiden Kontexten, verknüpfte Anzeigen extern | P19,P21,P27–P32 |
| P46 | Swipe-first A, beim Scrollen B mit kollabiertem Header und kompakter Identität, keine doppelte Identität; kompakte Filter/Modus/Status, relevante Warnung/Cancel sichtbar, Root-Ziele erhalten | P01–P05,P09,P20,P26 |
| P47 | Foto 65–72 % H normal, Ziel 70 %, Beginn ≤14 % H, Seiten 0–8 dp; keine Storysegmente/Zähler/Pfeile, seitliche native Tap-Zonen, Original/Fit im Menü; eingebettetes stummes Video, Autoplay/Loop, mittig Play/Pause, Archiv zuerst, adaptive Ausnahmen | P15–P17 |
| P48 | Tap/Galerie, horizontal/Entscheidung, vertikal/Scroll; native Event-Weitergabe und Kindziele, eindeutiges Commit/Cancel, keine Multi-Touch/stale Entscheidung; Fingerfolgen, Rückfedern bei Cancel, stabiler Viewport und bestätigter Exit | P21,P22 |
| P49 | Eine Decision Rail in A/B, Undo letzte gültige Medien-/Scrollposition, Skip/Reset/Replay, Favorit abgrenzen, erwarteter Owner/Run und eine Write-Route | P18,P20–P23,P25 |
| P50 | Scanbare kondensierte Fakten/Verfügbarkeit/Beschreibung/Dienste/Kontakt, progressive Offenlegung von Analyse/Notiz/Verlauf; vollständige bestehende Felder, keine falschen Werte; bedarfsgerechte wiederverwendbare Reads | P16,P18,P27–P32 |
| P51 | Gemeinsame Kontext-/Operations-/Projektionsverträge; native Drain, Owner/CAS, unabhängige Reprojektion, current+2 Prefetch, Rust-only HTTP inkl. Video mit Host/Kinds/Size/Timeout/Cancel/HAR; kein zusätzlicher Archiv-/DB-Write, Decoder nur lokale Dateien ohne externe Referenzen; keine zweite Queue/DB/HTTP. | P03,P23,P37–P44,X01–X11 |

## Scope-Grenzen

| ID | Bereich | Gültiger Umgang |
| --- | --- | --- |
| X01 | Chat/Messaging, automatische Profilanfragen, Unread-Chat-Badge | Ausgeschlossen; bestehender externer WhatsApp-Aufruf bleibt (P18). |
| X02 | Treffen, Bewertungen, Service-Details, private Treffen-Notiz (requested_fixes.js) | M06; Personennotiz P31 separat. |
| X03 | Preislisten, Preisrevision/Preishistorie, „Letzter Preis“ | M06 für manuelle Listen; automatische Extraktion ausgeschlossen. |
| X04 | VisualAnalysis/Live-Bildanalyse, KI/OCR | Ausgeschlossen; nur vorhandene Quellmarker P13. |
| X05 | Freier manueller Merge/Split | Keine neue Identitätsregel in M05; vorhandene evidenzbasierte Bestätigung erhalten (P30). |
| X06 | Globale Statistik, Verlauf außerhalb Profil, eigenständiges Bild-/Videoarchiv | Separat gegen Hauptplan abgleichen, nicht M05. |
| X07 | Kartenlogik/Geocoding-Erweiterung | M06; bestehende Karte anbinden P04/P26. |
| X08 | Backup/Export/Release, allgemeine Performanceoptimierung | M07; M05 nur funktionsbezogene Tests/Regression. |
| X09 | Eingebettetes Video | Ursprünglicher Ausschluss aufgehoben; ausdrücklich Teil der Galerie P16/P47, kein Messenger. |
| X10 | Automatische Radiusaufweitung | Bewusst abweichender aktueller SOURCE-Vertrag P11; keine Rücknahme. |
| X11 | Browser-Smartphone-Simulation, synthetische Statusleiste | Native Systemleisten P02. |

STUDIO/FACEAPP/Business ohne belastbare Daten bleiben sichtbar nicht unterstützt; keine neue Klassifikation oder automatische Radiusaufweitung. Historische Abweichung akzeptiert. Gespeicherte Suchen: ausschließlich explizites Gelesen-Ack, keine automatische Markierung beim Öffnen. Karte: tatsächlichen DB-weiten Datenbezug kennzeichnen. [Fachverträge](ARCHITECTURE.md).
