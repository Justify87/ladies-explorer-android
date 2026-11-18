#!/usr/bin/env python3
"""Capture the pinned original frontend using only local synthetic API responses."""
import argparse
import json
import mimetypes
import re
import sqlite3
import subprocess
import sys
from datetime import datetime
from pathlib import Path
from urllib.parse import urlsplit
from zipfile import ZipFile

LEGACY_SHA = "9c514af6ce3659089807fefd4ed5040599975a5a"
ORIGIN = "http://m05-fixture.local"


def load_fixture(folder):
    scenario = json.loads((folder / "scenarios.json").read_text(encoding="utf-8"))
    with ZipFile(folder / "m05-synthetic.zip") as archive:
        images = {name.removeprefix("assets/"): archive.read(name)
                  for name in archive.namelist() if name.startswith("assets/media/")}
    db_uri = (folder / "legacy.sqlite3").resolve().as_uri() + "?mode=ro"
    with sqlite3.connect(db_uri, uri=True) as db:
        db.row_factory = sqlite3.Row
        definitions = [dict(row) for row in db.execute("SELECT * FROM saved_searches ORDER BY saved_search_id")]
        active_ids = {row[0] for row in db.execute(
            "SELECT listing_id FROM search_results WHERE search_run_id=3")}
        note = db.execute("SELECT private_note FROM person_user_state WHERE person_id=1").fetchone()[0]
    db.close()
    rows = []
    for p in scenario["profiles"]:
        thumbnail = ORIGIN + "/fixture/" + p["image_asset"] if p["image_asset"] else ""
        media = [{"kind": "IMAGE", "local_url": thumbnail, "position": 0}] if thumbnail else []
        if p["media"] == "VIDEO":
            media.append({"kind": "VIDEO", "url": ORIGIN + "/fixture/missing-video.mp4", "position": 1})
        rows.append({**p, "thumbnail_url": thumbnail, "media": media,
                     "has_video": p["media"] == "VIDEO", "has_ai_content": p["ai_marker"],
                     "business_profile_known": True, "is_business_profile": False,
                     "last_seen_at": p["changed_at"], "updated_at": p["changed_at"],
                     "current_status": p["status"], "latest_advert_id": p["advert_id"],
                     "current_url": ORIGIN + "/fixture/unavailable-profile"})
    people = {}
    for row in rows:
        people.setdefault(row["person_id"], []).append(row)
    for row in rows:
        row["listing_count"] = row["known_listing_count"] = len(people[row["person_id"]])
        row["recent_change"] = {"created_at": row["changed_at"], "event_type": "FIELD_CHANGED"}
    active = [row for row in rows if row["listing_id"] in active_ids]
    saved = [{"id": d["saved_search_id"], "name": d["name"], "filters": json.loads(d["filters_json"]),
              "unread_new_results": d["saved_search_id"] == "fixture-supported",
              "last_delta": {"current_result_count": 2, "new_count": 1}} for d in definitions]
    return scenario, images, rows, people, active, saved, note


def snapshot(row):
    return {"name": row["name"], "city": row["city"], "description": "Synthetischer aktueller Text",
            "traits": {"Alter": [str(row["age"])], "Größe": ["170 cm"]},
            "services": {}, "opening_times": {}, "media": row["media"], "source_url": row["current_url"]}


def person_detail(rows, note):
    row = rows[0]
    return {"person": {"person_id": row["person_id"], "display_name": row["name"],
                       "listing_count": len(rows), "last_seen_at": row["changed_at"]},
            "user_state": {"preference": row["preference"], "favorite": row["favorite"],
                           "private_note": note if row["person_id"] == 1 else ""},
            "listings": rows, "latest_snapshot": snapshot(row),
            "timeline": [{"event_type": "FIELD_CHANGED", "field_key": "description",
                          "created_at": row["changed_at"], "from_snapshot_id": row["listing_id"] * 10,
                          "to_snapshot_id": row["listing_id"] * 10 + 1}],
            "analytics": {}, "identifiers": [], "encounters": [], "price_lists": []}


def capture(legacy, fixture, out, executable):
    from playwright.sync_api import sync_playwright
    sha = subprocess.check_output(["git", "-C", str(legacy), "rev-parse", "HEAD"], text=True).strip()
    if sha != LEGACY_SHA:
        raise ValueError(f"Expected pinned checkout {LEGACY_SHA}, got {sha}")
    if subprocess.check_output(["git", "-C", str(legacy), "status", "--porcelain", "--untracked-files=no"], text=True).strip():
        raise ValueError("Legacy tracked files must be clean")
    sys.path.insert(0, str(legacy))
    from ladies.config import frontend_config
    config = frontend_config(default_city="Leipzig", default_radius=20)
    app = legacy / "ladies"
    static = (app / "static").resolve()
    template = (app / "templates/index.html").read_text(encoding="utf-8")
    needle = '{{ config["FRONTEND_CONFIG"]|tojson }}'
    if template.count(needle) != 1:
        raise ValueError("Unexpected legacy template")
    html = template.replace(needle, json.dumps(config, ensure_ascii=True).replace("<", "\\u003c"))
    scenario, images, rows, people, active, saved, note = load_fixture(fixture)
    by_advert = {row["advert_id"]: row for row in rows}
    out.mkdir(parents=True, exist_ok=False)
    evidence = []
    unexpected = set()
    blocked_external = set()
    errors = []

    def api(path, method, search_rows):
        if method != "GET" and (method, path) not in {
            ("POST", "/api/search-preview"), ("POST", "/api/search"),
            ("POST", "/api/search-transient"), ("POST", "/api/client-log"),
            ("POST", "/api/saved-searches/acknowledge"),
        }:
            raise KeyError(path)
        if path == "/api/config":
            return config
        if path == "/api/settings":
            return {"settings": {"replay_likes": True, "reduced_motion": True,
                                 "show_description_changes": True, "visual_check_enabled": False,
                                 "default_filters": {"local": {"photosOnly": False, "hideDislikes": False,
                                                               "hideBusinessProfiles": False}}}}
        if path == "/api/dashboard":
            return {"counts": {"persons": len(people), "changes": len(rows)}}
        if path == "/api/messaging/status":
            return {"state": "NOT_CONFIGURED"}
        if path in ("/api/messaging/inbox", "/api/messaging/review", "/api/messaging/threads"):
            return {"items": [], "threads": []}
        if path in ("/api/storage", "/api/pricing/settings", "/api/pricing/runtime/status", "/api/client-log"):
            return {}
        if path == "/api/search-preview":
            return {"count": len(search_rows), "radius_km": 20, "effective_radius_km": 20,
                    "radius_auto_expanded": False}
        if path in ("/api/search", "/api/search-transient"):
            return {"results": search_rows, "meta": {"requested_radius_km": 20, "effective_radius_km": 20}}
        if path == "/api/saved-searches":
            return {"items": saved}
        if path == "/api/saved-searches/acknowledge":
            return {"items": saved}
        if path == "/api/saved-searches/fixture-supported/results":
            return {"items": active[:2]}
        if path.startswith("/api/lists/"):
            tab = path.rsplit("/", 1)[-1]
            unique = [v[0] for v in people.values()]
            return {"items": [p for p in unique if p["favorite"]]} if tab == "FAVORITE" else {
                "items": [p for p in unique if p["preference"] == tab]}
        if path.startswith("/api/person/") and method == "GET":
            pid = int(path.rsplit("/", 1)[-1])
            return person_detail(people[pid], note)
        if path.startswith("/api/profile/") or path.startswith("/api/listing/"):
            row = by_advert[path.rsplit("/", 1)[-1]]
            return {"identity": {"person_id": row["person_id"], "preference": row["preference"],
                                 "favorite": row["favorite"], "known_listing_count": row["listing_count"]},
                    "listing": row, "profile": snapshot(row)}
        if path.startswith("/api/profile-classification/"):
            return {"is_business_profile": False, "business_profile_known": True}
        if re.fullmatch(r"/api/messaging/person/\d+/request", path) and method == "GET":
            return {"can_send": False, "reason": "synthetic fixture"}
        if re.fullmatch(r"/api/pricing/person/\d+/(current|history)", path):
            return {"items": [], "current": None}
        raise KeyError(path)

    with sync_playwright() as pw:
        browser = pw.chromium.launch(executable_path=executable, headless=True)
        try:
            for width in scenario["viewports_dp"]:
                context = browser.new_context(viewport={"width": width, "height": 850},
                                              device_scale_factor=1, locale="de-DE",
                                              timezone_id="Europe/Berlin", service_workers="block")
                search_rows = active[:]

                def route_request(route):
                    request = route.request
                    parsed = urlsplit(request.url)
                    path = parsed.path
                    if f"{parsed.scheme}://{parsed.netloc}" != ORIGIN:
                        blocked_external.add(request.url)
                        route.abort()
                    elif path == "/":
                        route.fulfill(content_type="text/html; charset=utf-8", body=html)
                    elif path.startswith("/static/"):
                        target = (static / path.removeprefix("/static/")).resolve()
                        if not target.is_relative_to(static) or not target.is_file():
                            route.fulfill(status=404, body="")
                        else:
                            route.fulfill(content_type=mimetypes.guess_type(target.name)[0] or "application/octet-stream",
                                          body=target.read_bytes())
                    elif path.startswith("/fixture/"):
                        data = images.get(path.removeprefix("/fixture/"))
                        route.fulfill(status=200 if data else 404, content_type="image/png" if data else "text/plain",
                                      body=data or b"")
                    elif path == "/api/messaging/events":
                        route.fulfill(status=204, body="")
                    elif path.startswith("/api/"):
                        try:
                            payload = api(path, request.method, search_rows)
                            route.fulfill(json=payload)
                        except (KeyError, ValueError, IndexError):
                            unexpected.add(f"{request.method} {path}")
                            route.fulfill(status=501, json={"error": "Not implemented in fixture"})
                    else:
                        unexpected.add(f"{request.method} {path}")
                        route.fulfill(status=404, body="")

                context.route("**/*", route_request)
                page = context.new_page()
                page.clock.set_fixed_time(datetime.fromisoformat(scenario["clock"]))
                page.on("pageerror", lambda error: errors.append(str(error)))

                def shot(name, selector):
                    page.locator(selector).first.wait_for(state="visible")
                    page.evaluate("() => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)))")
                    path = out / f"{width}-{name}.png"
                    page.screenshot(path=str(path), animations="disabled")
                    evidence.append({"viewport": width, "screen": name, "file": path.name})

                page.goto(ORIGIN + "/")
                page.wait_for_function("""() => {
                    const s=window.__LADIES_EXPLORER_V13__?.store.getState();
                    return s?.search.preview.status==='ready' && s.search.results.length>0
                }""")
                shot("search", ".search-screen")
                page.locator('[data-action="root-route"][data-route="discover"]').click()
                shot("swipe", ".swipe-profile .profile-media")
                page.locator('[data-action="discover-view"][data-mode="grid"]').click()
                shot("grid", ".grid-card")
                assert page.locator(".grid-card").count() == len(active)
                page.locator('.grid-card[data-person-id="1"]').first.click()
                shot("profile", ".standalone-profile .profile-details")
                page.locator('[data-action="profile-tab"][data-tab="offer"]').click()
                shot("profile-services", ".profile-panel")
                page.locator('[data-action="profile-tab"][data-tab="history"]').click()
                shot("profile-history", ".profile-panel")
                page.locator('[data-action="back"]').first.click()
                page.locator('[data-action="root-route"][data-route="favorites"]').click()
                shot("favorites", ".saved-person-row")
                assert page.locator(".saved-recent-section .saved-person-row").count() == 4
                page.locator('[data-action="root-route"][data-route="search"]').first.click()
                page.locator('[data-saved-search-action="open"]').click()
                shot("saved-searches", ".saved-search-entry")
                page.locator('[data-saved-search-action="results"][data-saved-id="fixture-supported"]').click()
                shot("saved-results", ".saved-search-result-card")
                assert page.locator(".saved-search-result-card").count() == 2
                context.close()
        finally:
            browser.close()
            (out / "evidence.json").write_text(json.dumps({
                "legacy_commit": sha, "clock": scenario["clock"], "captures": evidence,
                "unexpected_local_requests": sorted(unexpected),
                "blocked_external_requests": sorted(blocked_external), "page_errors": errors,
                "not_exercised": ["live source", "map tiles", "mutations", "Android", "dark/font-scale"]
            }, indent=2) + "\n", encoding="utf-8")
    if unexpected or errors:
        raise RuntimeError("Unexpected API requests or browser errors; see evidence.json")
    print(f"Captured {len(evidence)} synthetic legacy screens")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("legacy", type=Path, help="clean checkout of the pinned original repository")
    parser.add_argument("fixture", type=Path, help="output directory from m05-reference.py")
    parser.add_argument("output", type=Path, help="new screenshot directory")
    parser.add_argument("--chromium", help="optional local Chromium executable")
    args = parser.parse_args()
    capture(args.legacy.resolve(), args.fixture.resolve(), args.output.resolve(), args.chromium)
