# Anforderungen und fachliche Parität

Stabile P-/X-IDs als Regressionsreferenz, kein Aufgabenregister. Status: [Arbeitsregister](ROADMAP.md); detaillierter [UI-/Designvertrag](DESIGN.md). Legacy-Referenz [9c514af6](https://github.com/Justify87/ladies-webapp/tree/9c514af6ce3659089807fefd4ed5040599975a5a). Vollständige historische Quell-/Testanker siehe [ursprüngliche Matrix](https://github.com/Justify87/ladies-explorer-android/blob/c6114a6f51b2c2ce99e986efd939ab9115708b70/docs/M05-PARITY.md). Keine alte Abnahme wieder öffnen; neuere native Darstellung ersetzt widersprechende Legacy-Gestaltung.

| ID | Bereich | Legacy-Referenz | Anforderung / maßgeblicher Vertrag |
| --- | --- | --- | --- |
| P01 | Design | tokens.css; layout/navigation.css | [Visuelle Identität](DESIGN.md#visuelle-identität) |
| P02 | Shell | chrome.js; shell.css; index.html | [Navigation](DESIGN.md#navigation-und-bildschirmmuster) |
| P03 | Navigation | app/router.js; app/scroll_state.js | [Navigation und Präsentation](DESIGN.md#profile-und-medien) |
| P04 | Karte | ui/screens/discover.js; discover-map.css | Bestehende Karte im Ansichtswechsel; Datenbezug kenntlich |
| P05 | Suchzustand | effects/core.js; app/selectors.js; search_runtime.js | Aktiver Suchlauf getrennt von Filterentwurf, keine Vermischung |
| P06 | Grundfilter | ui/screens/search.js; components/filters.css | Ort, Radius, Alter, Größe, Treffpunkt, Kategorie mit korrekten Werten |
| P07 | Filterlayout | ui/screens/search.js; ux_patch.js | [Suchoberfläche](DESIGN.md#navigation-und-bildschirmmuster) |
| P08 | Häufige Filter | data/filter_usage.js; ui/screens/search.js | Top 5 aus tatsächlicher Nutzung, stabil/dedupliziert |
| P09 | Verfügbarkeit | search_intelligence.js; chrome.js | Alle/Jetzt/Heute kanonisch, nur Schnellwahl im Header führt Suche aus |
| P10 | Preview | effects/core.js: schedulePreview; search_preview.py | Debounce/Abbruch, ehrlicher Count bzw. unbekannt, keine DB-Suchpersistenz |
| P11 | Radius/Fehler | ui/screens/search.js; regressions.js | Keine Radiusaufweitung; verständliche Preview-/Suchfehler, partial |
| P12 | Lokale Filter | app/selectors.js; requested_fixes.js | Bilder, Videos, Like/Dislike; gemeinsame Projektion |
| P13 | Bild/Business | app/selectors.js; effects/core.js; business_profiles.py | ALL/AI/NATURAL; STUDIO/FACEAPP/Business nur mit Evidenz, sonst explizite Grenze |
| P14 | Suchabschluss | effects/core.js: performSearch | Erfolgreiche Suche öffnet Entdecken, Fehler/Abbruch preserves Zustand |
| P15 | Medienhero | ui/profile.js; features/discover.css; features/profile.css | [Medienhero](DESIGN.md#profile-und-medien) |
| P16 | Medienfallback | ui/profile.js; effects/core.js | Medienreihenfolge, lokal vor Remote, Fehler/Platzhalter; reine Anzeige ohne Archiv-/DB-Mutation. [Design](DESIGN.md#profile-und-medien), [Medienarchitektur](ARCHITECTURE.md#persistentes-archiv-und-anzeige). |
| P17 | Identität | ui/profile.js: renderIdentity | Name/Alter/Ort, Status, neu/bekannt/Video nur belegt |
| P18 | Aktionen | ui/profile.js; requested_fixes.js; overlays.js | Like/Favorit unabhängig, Kontakt, WhatsApp extern, Originalprofil/Mehr |
| P19 | Gemeinsamer Profilaufbau | ui/profile.js: renderSwipe/renderStandalone | [Gemeinsamer Profilfluss](DESIGN.md#profile-und-medien) |
| P20 | Swipe-Queue | app/selectors.js; requested_fixes.js; app/commands.js | Aktive Ergebnismenge, Replay-/Hide-Likes, stabile IDs, eindeutige Reihenfolge |
| P21 | Swipe-Gesten | regressions.js; features/discover.css | [Gesten](DESIGN.md#gesten-und-präsentation) |
| P22 | Undo/Reset/Später | effects/core.js; app/commands.js | Undo persistiert vorherigen Zustand, Reset ohne Reset der DB, Später sessionlokal |
| P23 | Prefetch | effects/core.js: startSwipePrefetch/warmProfileMedia | [Swipe-Read-Window und Prefetch](ARCHITECTURE.md#swipe-read-window-und-prefetch) |
| P24 | Grid | ui/screens/discover.js; components/listing-card.css | [Grid-Design](DESIGN.md#navigation-und-bildschirmmuster) |
| P25 | Grid-Aktion | requested_fixes.js; app/selectors.js | Direkte Like-Aktion ohne Profilöffnung, Fehler-/Filterverhalten |
| P26 | View-Switch | ui/screens/discover.js; app/reducer.js | Swipe/Grid/Karte wechseln ohne erneute Suche, Position erhalten |
| P27 | Profilabschnitte | ui/profile.js; requested_fixes.js; regressions.js | [Profilabschnitte](DESIGN.md#profile-und-medien) |
| P28 | Info | ui/profile.js; requested_fixes.js; regressions.js | [Informationshierarchie](DESIGN.md#profile-und-medien) |
| P29 | Dienste/Filterbelege | ui/profile.js; requested_fixes.js | Dienste und ausgewählte Filterbelege Ja/Nein/Unbekannt im gemeinsamen Profilfluss |
| P30 | Analyse | ui/profile.js: renderAnalysis | Vorhandene Belege, keine erdachten Scores/AI; sichere Bestätigung bewahren |
| P31 | Personennotiz | ui/profile.js; requested_fixes.js; models.py | Personennotiz lesen/bearbeiten/speichern; getrennt von Treffen |
| P32 | Historie | ui/profile.js: renderHistory; effects/core.js | Timeline, alle Anzeigen, Original-Link, Snapshot-Vergleich |
| P33 | Gespeichert-Daten | ui/screens/favorites.js; app/selectors.js | FAVORITE/LIKE/DISLIKE, Personenkonsistenz, korrekte Anzahl/Sortierung |
| P34 | Gespeichert-Layout | favorites-redesign.css; ui/screens/favorites.js | [Gespeichert-Design](DESIGN.md#navigation-und-bildschirmmuster) |
| P35 | Watchlist | ui/screens/favorites.js: CHANGE_WINDOW_MS | 36 h, drei initial, expandierbar, keine doppelten Einträge |
| P36 | Status | ui/screens/favorites.js; effects/core.js | Einzel-/Batchprüfung, wahrheitsgemäßer Status, Zeit, Progress, Teilfehler |
| P37 | Gespeicherte Suche | ui/screens/saved_searches.js; saved_searches.js | Eigene Liste, Header-Indikator, Delta, CRUD/Run/Acknowledge erhalten |
| P38 | Gemeinsamer Editor | ui/screens/saved_searches.js; ui/screens/search.js | Gemeinsamer Editor, kein Überschreiben importierter unsupported Regeln |
| P39 | Gespeichertes Grid | ui/screens/saved_searches.js | Gemeinsame Grid-Karte, Pagination/Offline, sichtbare Deltas |
| P40 | Zustände/UX | app/scroll_state.js; regressions.js; ux_patch.js | [Zustände und Accessibility](DESIGN.md) |
| P41 | Gesamtcheck | bootstrap.js; tools/check_* (Legacy) | Referenzvergleich und gezielte Regression; technische Prüfungen und Abschluss nach Merge-Regel |
| P42 | Quellen-HTTP | — | [Netzwerkgrenze](ARCHITECTURE.md#netzwerkgrenze) |
| P43 | Medien | — | [Medienarchitektur](ARCHITECTURE.md#persistentes-archiv-und-anzeige); reine Anzeige ohne Archiv-/DB-Mutation (P16). |
| P44 | Architekturgrenze | — | [Architekturverträge](ARCHITECTURE.md#automatisierte-architekturverträge) |

## Profilvertrag

| ID | Gültige Anforderung | Bezug |
| --- | --- | --- |
| P45 | [Profilfluss](DESIGN.md#profile-und-medien); explizite Identitätsbestätigung in beiden Kontexten, verknüpfte Anzeigen extern. | P19,P21,P27–P32 |
| P46 | [Swipe-first-Zustände](DESIGN.md#profile-und-medien) | P01–P05,P09,P20,P26 |
| P47 | [Hero-Budget und Galerie](DESIGN.md#profile-und-medien); Medienfallback P16. | P15–P17 |
| P48 | [Gesten](DESIGN.md#gesten-und-präsentation) | P21,P22 |
| P49 | [Decision Rail und Präsentation](DESIGN.md#profile-und-medien); Undo/Reset/Skip P22, Favorit P18; [Operationsmodell](ARCHITECTURE.md#operations--und-konsistenzmodell). | P18,P20–P23,P25 |
| P50 | [Informationshierarchie](DESIGN.md#profile-und-medien); vollständige belegte Felder, keine erfundenen Werte. | P16,P18,P27–P32 |
| P51 | [Operations- und Konsistenzmodell](ARCHITECTURE.md#operations--und-konsistenzmodell), [Medien](ARCHITECTURE.md#persistentes-archiv-und-anzeige), [Prefetch](ARCHITECTURE.md#swipe-read-window-und-prefetch). | P03,P23,P37–P44,X01–X11 |

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
| X09 | Eingebettetes Video | Zulässig und Bestandteil der Galerie (P16/P47); [Medienarchitektur](ARCHITECTURE.md#persistentes-archiv-und-anzeige), kein Messenger. |
| X10 | Automatische Radiusaufweitung | Bewusst abweichender aktueller SOURCE-Vertrag P11; keine Rücknahme. |
| X11 | Browser-Smartphone-Simulation, synthetische Statusleiste | Native Systemleisten P02. |

STUDIO/FACEAPP/Business ohne belastbare Daten bleiben sichtbar nicht unterstützt; keine neue Klassifikation oder automatische Radiusaufweitung. Historische Abweichung akzeptiert. Gespeicherte Suchen: ausschließlich explizites Gelesen-Ack, keine automatische Markierung beim Öffnen. Karte: tatsächlichen DB-weiten Datenbezug kennzeichnen. [Fachverträge](ARCHITECTURE.md).

## Fachliche Präzisierungen

- Aktive Suche und Filterentwurf getrennt; lokaler neutraler Startbestand kenntlich, Quellzahl nicht lokale Trefferzahl. Lokale Bild-/Video-/Präferenzfilter, ALL/AI/NATURAL nur auf Evidenz; keine URL-Heuristik für Business. Nicht ausführbare importierte Definitionen nicht still ändern. Such-/Vorschau-/Statusvertrag: [SOURCE](SOURCE.md).
- Entscheidung erst nach bestätigtem Ergebnis; Skip bleibt sessionlokal, Reset löscht keine persistierten Präferenzen; Standalone/Neutral/Favorit sind keine Deck-Visits. Favorit unabhängig; Grid-Like ohne Profilöffnung, temporäres Ausblenden auf aktiven Lauf begrenzt, bei Writefehler Rücknahme. Undo nur gültiger letzter Schritt; ältere Präsentationsreferenz darf kontrolliert fehlen. Kein doppelter fachlicher Write nach erfolgreicher Speicherung und anschließendem Lesefehler.
- Identitätsbestätigung zeigt Konsequenzen, nur mit aktueller Evidenz; ungespeicherte Notizen betroffener Personen blockieren Merge. Aktuelles Listing möglichst erhalten, andernfalls gültigen Kontext mit Hinweis öffnen. Merge ist kein Undo-Step. Notizkonflikte ausdrücklich lösen, keine Überschreibung fremder Felder oder stilles Umdeuten von Entwürfen. Kontakt und Vergleich nur auf ausdrückliche Aktion, keine automatischen Anrufe/Nachrichten.
- Statuschecks: nur belegter Status, Ausfall/Timeout/Prüfseite sind Fehler/Unbekannt; Fortschritt und Teilfehler, Abbruch, keine Statusverfälschung. Karte zeigt den tatsächlich DB-weiten Datenumfang ([MAP](MAP.md)). SavedSearch-Ack nur ausdrücklich; Öffnen, Reload, Pagination, Rückkehr oder fachfremde Mutation quittieren nichts.
- Für offene M06-/M07-Funktionen gilt ausschließlich der bestätigte Umfang im [Arbeitsregister](ROADMAP.md); keine UI aus bloß vorhandenen Datenbankfeldern ableiten.

## Regressionsreferenz

Bei betroffenen Änderungen gezielte vertikale Prüfung: 0/1/200+ Cards, 0/1/12/80 Medien, vollständige/partielle/unbestätigte Radiusläufe, mehrere Inserate je Person, neue/geänderte/aktive/inaktive/unbekannte Profile, Präferenz-/Favorit-Kombinationen, Offline/Timeout/fehlende Medien, importierte Notizen und Definitionen. Swipe/Tap/Scroll/Undo/Skip/Reset/Replay, Einstiege/Rückkehr, Rotation/Back/Moduswechsel, schneller Runwechsel/Cancel/Late Write, Identitätsmerge mit Dirty Notes/Kandidatenwechsel/Archive Cancel/Readfehler und dedupliziertem Deck, Notizkonflikte, unabhängige Reprojektionen, explizites SavedSearch-Ack und veraltete Revision, Quelle/Radius/Pagination/Filter/HTTP/HAR. Keine Quellenanfragen allein durch Darstellung, keine unbelegte Beschleunigungsbehauptung. UI-Prüfung siehe [DESIGN](DESIGN.md#prüfung-und-gestaltungsspielraum), technische Prüfungen [README](../README.md#prüfung--ci). Umfang risikobasiert; keine neue obligatorische Gerätestufe.
