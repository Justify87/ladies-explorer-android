# M05 – Paritätsmatrix

Anforderungsinventar aus der Quellcodeanalyse (keine Laufzeitabnahme). P-IDs bleiben stabil. Ausgangs-Ist ist ein historischer Befund, nicht fortlaufender Erledigungsstatus: dafür ausschließlich [M05-TASKS](M05-TASKS.md) verwenden. Bei M05.0 jede Zeile mit konkreten Zeilen/Verhaltensnachweis ergänzen; neue belegte in-scope Anforderungen mit eigener P-ID und zugehöriger Aufgabe aufnehmen. Abweichungen von Legacy gelten nur begründet nach Plan/aktuellen Fachverträgen.

Pfadschlüssel: Legacy JS unter [ladies/static/explorer/js](https://github.com/Justify87/ladies-webapp/tree/9c514af6ce3659089807fefd4ed5040599975a5a/ladies/static/explorer/js), CSS unter [ladies/static/explorer/css](https://github.com/Justify87/ladies-webapp/tree/9c514af6ce3659089807fefd4ed5040599975a5a/ladies/static/explorer/css), Python unter [ladies](https://github.com/Justify87/ladies-webapp/tree/9c514af6ce3659089807fefd4ed5040599975a5a/ladies); native UI unter [ui](https://github.com/Justify87/ladies-explorer-android/tree/d0290b9fe652a03ac79e256b805219aae839cfa9/app/src/main/java/de/justify87/ladiesexplorer/ui), Rust unter [src](https://github.com/Justify87/ladies-explorer-android/tree/d0290b9fe652a03ac79e256b805219aae839cfa9/rust/ladies-core/src). Legacy-Startpunkt [bootstrap.js](https://github.com/Justify87/ladies-webapp/tree/9c514af6ce3659089807fefd4ed5040599975a5a/ladies/static/explorer/js/bootstrap.js), tatsächliche Nachbearbeitungen in [requested_fixes.js](https://github.com/Justify87/ladies-webapp/tree/9c514af6ce3659089807fefd4ed5040599975a5a/ladies/static/explorer/js/requested_fixes.js), [regressions.js](https://github.com/Justify87/ladies-webapp/tree/9c514af6ce3659089807fefd4ed5040599975a5a/ladies/static/explorer/js/regressions.js), [ux_patch.js](https://github.com/Justify87/ladies-webapp/tree/9c514af6ce3659089807fefd4ed5040599975a5a/ladies/static/explorer/js/ux_patch.js).

| ID | Bereich | Legacy-Beleg (Datei/Funktion) | Native Ausgangslage | Ziel / beobachtbare Parität | Aufgabe |
| --- | --- | --- | --- | --- | --- |
| P01 | Design | tokens.css; layout/navigation.css | LadiesTheme.kt: dynamische Farbe/Fallback blau | Legacy-Palette, Shapes, Typografie, Kontrast, Dark Mode | T1.1 |
| P02 | Shell | chrome.js; shell.css; index.html | LadiesApp.kt: generischer Header/5 Root-Tabs | Kontextheader, native Insets, 4 Hauptbereiche | T1.2 |
| P03 | Navigation | app/router.js; app/scroll_state.js | LadiesApp.kt: selected/profileOpen | Back, Scroll/Tab/Medienzustand, Profil ohne Bottom Bar | T1.3 |
| P04 | Karte | ui/screens/discover.js; discover-map.css | MapScreen.kt separat | Bestehende Karte im Ansichtswechsel; Datenbezug kenntlich | T1.4 |
| P05 | Suchzustand | effects/core.js; app/selectors.js; search_runtime.js | MainViewModel.kt startSearch + discovery.rs global | Aktiver Suchlauf getrennt von Filterentwurf, keine Vermischung | T2.1 |
| P06 | Grundfilter | ui/screens/search.js; components/filters.css | SearchScreen.kt Grundfilter/Katalog vorhanden | Ort, Radius, Alter, Größe, Treffpunkt, Kategorie mit korrekten Werten | T2.2 |
| P07 | Filterlayout | ui/screens/search.js; ux_patch.js | SearchScreen.kt generische Gruppen | Gruppierung, Einklappen, Merken, globaler Filtertext, Footer/Reset | T2.2 |
| P08 | Häufige Filter | data/filter_usage.js; ui/screens/search.js | MainViewModel.kt keine Nutzungserfassung | Top 5 aus tatsächlicher Nutzung, stabil/dedupliziert | T2.3 |
| P09 | Verfügbarkeit | search_intelligence.js; chrome.js | Katalog ohne vergleichbare Schnellwahl | Alle/Jetzt/Heute kanonisch, nur Schnellwahl im Header führt Suche aus | T2.4 |
| P10 | Preview | effects/core.js: schedulePreview; search_preview.py | MainViewModel.kt keine Preview-API | Debounce/Abbruch, ehrlicher Count bzw. unbekannt, keine DB-Suchpersistenz | T2.5 |
| P11 | Radius/Fehler | ui/screens/search.js; regressions.js | SOURCE.md strenger Radius-/Teilresultatvertrag | Keine Radiusaufweitung; verständliche Preview-/Suchfehler, partial | T2.5 |
| P12 | Lokale Filter | app/selectors.js; requested_fixes.js | saved_search.rs unterstützt Teilmenge; UI fehlt | Bilder, Videos, Like/Dislike; gemeinsame Projektion | T2.6 |
| P13 | Bild/Business | app/selectors.js; effects/core.js; business_profiles.py | SOURCE.md: begrenzte Marker, Klassifikationslücken | ALL/AI/NATURAL; STUDIO/FACEAPP/Business nur mit Evidenz, sonst explizite Grenze | T2.6 |
| P14 | Suchabschluss | effects/core.js: performSearch | MainViewModel.kt: verbleibt auf Suche | Erfolgreiche Suche öffnet Entdecken, Fehler/Abbruch preserves Zustand | T2.7 |
| P15 | Medienhero | ui/profile.js; features/discover.css; features/profile.css | DiscoveryScreen.kt und ProfileDetailScreen.kt getrennt | Große responsive Galerie, Story-Balken/Zähler/Tippzonen | T3.1 |
| P16 | Medienfallback | ui/profile.js; effects/core.js | Coil 3, Archiv/Remote, externe Videos vorhanden | Reihenfolge, lokale Bilder, Platzhalter, externer Video-Intent, Fehler | T3.2 |
| P17 | Identität | ui/profile.js: renderIdentity | DiscoveryCard begrenzt; Detail rudimentär | Name/Alter/Ort, Status, neu/bekannt/Video nur belegt | T3.3 |
| P18 | Aktionen | ui/profile.js; requested_fixes.js; overlays.js | ProfileDetailScreen.kt Einzelbuttons | Like/Favorit unabhängig, Kontakt, WhatsApp extern, Originalprofil/Mehr | T3.4 |
| P19 | Gemeinsamer Profilaufbau | ui/profile.js: renderSwipe/renderStandalone | native zwei getrennte Layouts | Ein komponierbarer Inhaltsaufbau, keine doppelte Logik | T3.5 |
| P20 | Swipe-Queue | app/selectors.js; requested_fixes.js; app/commands.js | discovery.rs neutraler globaler Feed | Aktive Ergebnismenge, Replay-/Hide-Likes, stabile IDs, eindeutige Reihenfolge | T4.1 |
| P21 | Swipe-Gesten | regressions.js; features/discover.css | DiscoveryScreen.kt horizontale Entscheidung | Horizontale Entscheidung/vertikales Detail-Scrollen/Tap ohne Konflikt | T4.2 |
| P22 | Undo/Reset/Später | effects/core.js; app/commands.js | MainViewModel.kt nur skipCard/choosePreference | Undo persistiert vorherigen Zustand, Reset ohne Reset der DB, Später sessionlokal | T4.3 |
| P23 | Prefetch | effects/core.js: startSwipePrefetch/warmProfileMedia | Kein gleichwertiger belegter Deck-Prefetch | Begrenzt, lifecycle-/epoch-gebunden, keine unnötige Archivierung | T4.4 |
| P24 | Grid | ui/screens/discover.js; components/listing-card.css | Kein natives Grid | Zwei Spalten, 0,77 Aspect, Cover, Name/Alter, Gradienten | T5.1 |
| P25 | Grid-Aktion | requested_fixes.js; app/selectors.js | Keine Grid-Likes | Direkte Like-Aktion ohne Profilöffnung, Fehler-/Filterverhalten | T5.2 |
| P26 | View-Switch | ui/screens/discover.js; app/reducer.js | Separater Map-Tab, kein Grid | Swipe/Grid/Karte wechseln ohne erneute Suche, Position erhalten | T5.3 |
| P27 | Profil-Tabs | ui/profile.js; requested_fixes.js; regressions.js | ProfileDetailScreen.kt flache Liste | Info/Dienste/Analyse/Notizen/Verlauf, passende Icons/State | T6.1 |
| P28 | Info | ui/profile.js; requested_fixes.js; regressions.js | Detailfelder vorhanden, Layout fehlt | Info-Raster, Merkmale, Zeiten, einklappbare Beschreibung | T6.2 |
| P29 | Dienste/Filterbelege | ui/profile.js; requested_fixes.js | JSON-Listen, keine Ja/Nein/Unbekannt-Zuordnung | Dienste-Chips, ausgewählte Filterbelege im Info-Tab | T6.3 |
| P30 | Analyse | ui/profile.js: renderAnalysis | Identitätskandidaten vorhanden, Konsistenz nicht gleichwertig | Vorhandene Belege, keine erdachten Scores/AI; sichere Bestätigung bewahren | T6.4 |
| P31 | Personennotiz | ui/profile.js; requested_fixes.js; models.py | Schema/Import; lib.rs kein Notiz-CRUD | Personennotiz lesen/bearbeiten/speichern; getrennt von Treffen | T6.5 |
| P32 | Historie | ui/profile.js: renderHistory; effects/core.js | ChangeRecord-Liste, kein Vergleich | Timeline, alle Anzeigen, Original-Link, Snapshot-Vergleich | T6.6 |
| P33 | Gespeichert-Daten | ui/screens/favorites.js; app/selectors.js | discovery.rs favorite-only bzw. neutral | FAVORITE/LIKE/DISLIKE, Personenkonsistenz, korrekte Anzahl/Sortierung | T7.1 |
| P34 | Gespeichert-Layout | favorites-redesign.css; ui/screens/favorites.js | FavoritesScreen.kt einfache Liste | Tabs, Thumbnail, Name/Alter/Ort, Herz, Menü, Datum/Name | T7.2 |
| P35 | Watchlist | ui/screens/favorites.js: CHANGE_WINDOW_MS | Keine Neu/Geändert-Sektion | 36 h, drei initial, expandierbar, keine doppelten Einträge | T7.3 |
| P36 | Status | ui/screens/favorites.js; effects/core.js | Keine gleichwertige Statusaktion | Einzel-/Batchprüfung, wahrheitsgemäßer Status, Zeit, Progress, Teilfehler | T7.4 |
| P37 | Gespeicherte Suche | ui/screens/saved_searches.js; saved_searches.js | SearchScreen.kt im Filterformular | Eigene Liste, Header-Indikator, Delta, CRUD/Run/Acknowledge erhalten | T8.1 |
| P38 | Gemeinsamer Editor | ui/screens/saved_searches.js; ui/screens/search.js | SearchScreen.kt eigener Formularzustand | Gemeinsamer Editor, kein Überschreiben importierter unsupported Regeln | T8.2 |
| P39 | Gespeichertes Grid | ui/screens/saved_searches.js | native savedResultCards als Liste | Gemeinsame Grid-Karte, Pagination/Offline, sichtbare Deltas | T8.3 |
| P40 | Zustände/UX | app/scroll_state.js; regressions.js; ux_patch.js | Teilweise Lifecycle-/Fehlerbehandlung | Konsistente Loading/Empty/Error, Wiederherstellung, Accessibility | T8.4 |
| P41 | Gesamtcheck | bootstrap.js; tools/check_* (Legacy) | CI vorhanden, keine visuelle Abnahme | Referenzvergleich, Regression, APK, echte Geräteabnahme | T9.1 |

## Bewusste Abgrenzung (keine M05-Implementierung)

| ID | Legacy-Funktion | Umgang |
| --- | --- | --- |
| X01 | Chat/Messaging, automatische Profilanfragen, Unread-Chat-Badge | Ausgeschlossen; bestehender externer WhatsApp-Aufruf bleibt (P18). |
| X02 | Treffen, Bewertungen, Service-Details, private Treffen-Notiz (requested_fixes.js) | M06; Personennotiz P31 separat. |
| X03 | Preislisten, Preisrevision/Preishistorie, „Letzter Preis“ | M06 für manuelle Listen; automatische Extraktion ausgeschlossen. |
| X04 | VisualAnalysis/Live-Bildanalyse, KI/OCR | Ausgeschlossen; nur vorhandene Quellmarker P13. |
| X05 | Freier manueller Merge/Split | Keine neue Identitätsregel in M05; vorhandene evidenzbasierte Bestätigung erhalten (P30). |
| X06 | Globale Statistik, Verlauf außerhalb Profil, eigenständiges Bild-/Videoarchiv | Separat gegen Hauptplan abgleichen, nicht M05. |
| X07 | Kartenlogik/Geocoding-Erweiterung | M06; bestehende Karte anbinden P04/P26. |
| X08 | Backup/Export/Release, allgemeine Performanceoptimierung | M07; M05 nur funktionsbezogene Tests/Regression. |
| X09 | Eingebettetes Video/Autoplay | Bestehender externer Video-Intent als native Abweichung P16; kein neuer Player in M05. |
| X10 | Automatische Radiusaufweitung | Bewusst abweichender aktueller SOURCE-Vertrag P11; keine Rücknahme. |
| X11 | Browser-Smartphone-Simulation, synthetische Statusleiste | Native Systemleisten P02. |

Offene Evidenz: STUDIO/FACEAPP/Business (P13) nur über tatsächlich vorhandene Daten, keine erfundene Parität; M05.0 und T2.6 verifizieren. Karte mit abweichender Datenmenge P04/P26 klar kennzeichnen; keine verdeckte M06-Erweiterung. Konkrete Varianten und Gerätebelege im Aufgabenregister/PR ergänzen.
