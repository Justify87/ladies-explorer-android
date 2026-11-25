#!/usr/bin/env python3
"""Generate an isolated, synthetic native Android v3 database and M05 scenarios.

No network, private data, production DB access or third-party packages.
"""
import argparse
import hashlib
import json
import sqlite3
import struct
import zlib
from datetime import datetime, timedelta, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCHEMA = ROOT / "rust/ladies-core/src/schema.rs"


def png(rgb):
    def chunk(kind, data):
        return (struct.pack(">I", len(data)) + kind + data
                + struct.pack(">I", zlib.crc32(kind + data)))
    width, height = 48, 64
    pixels = b"".join(b"\0" + bytes(rgb if y < 40 else (28, 42, 65)) * width
                      for y in range(height))
    return (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(pixels)) + chunk(b"IEND", b""))


def generate(out, now):
    out.mkdir(parents=True, exist_ok=False)
    def stamp(hours):
        return (now - timedelta(hours=hours)).isoformat(timespec="seconds").replace("+00:00", "Z")
    source = SCHEMA.read_text(encoding="utf-8")
    schema_v1 = source.split('pub(crate) const VERSION_1: &str = r#"', 1)[1].split('"#;', 1)[0]
    schema_v2 = source.split('const VERSION_2: &str = r#"', 1)[1].split('"#;', 1)[0]
    schema_v3 = source.split('const VERSION_3: &str = r#"', 1)[1].split('"#;', 1)[0]
    db_path = out / "ladies.sqlite"
    assets = {}
    profiles = []
    people = [
        (1, "NONE", True, "ACTIVE", 1, "IMAGE"),
        (2, "LIKE", True, "ACTIVE", 12, "VIDEO"),
        (3, "DISLIKE", True, "INACTIVE", 35, "NONE"),
        (4, "LIKE", True, "UNKNOWN", 36, "IMAGE"),
        (5, "NONE", True, "ACTIVE", 3, "IMAGE"),
        (6, "DISLIKE", False, "UNKNOWN", 72, "NONE"),
        (7, "NONE", False, "ACTIVE", 96, "IMAGE"),
        (8, "LIKE", False, "UNKNOWN", 96, "NONE"),
    ]
    with sqlite3.connect(db_path) as db:
        db.executescript(schema_v1)
        db.executescript(schema_v2)
        db.executescript(schema_v3)
        db.execute(f"PRAGMA application_id={int('4C445850', 16)}")
        db.execute("PRAGMA user_version=3")
        db.execute("PRAGMA foreign_keys=ON")
        for pid, pref, fav, status, hours, media in people:
            name = f"Testprofil {pid:02}"
            change = stamp(hours)
            db.execute("INSERT INTO persons(person_id,display_name,first_seen_at,last_seen_at) VALUES(?,?,?,?)",
                       (pid, name, stamp(96), change))
            db.execute("UPDATE person_user_state SET preference=?,favorite=?,private_note=?,updated_at=? WHERE person_id=?",
                       (pref, int(fav), "Synthetische Personennotiz" if pid == 1 else None, change, pid))
            db.execute("INSERT INTO source_profiles(source_profile_id,person_id,external_profile_id) VALUES(?,?,?)",
                       (pid, pid, f"fixture-person-{pid}"))
            for lid in ([101, 108] if pid == 1 else [109] if pid == 8 else [100 + pid]):
                advert = f"fixture-{lid}"
                url = f"https://example.invalid/{advert}"
                db.execute("INSERT INTO listings(listing_id,person_id,source_profile_id,external_listing_id,current_url,current_status,first_seen_at,last_seen_at) VALUES(?,?,?,?,?,?,?,?)",
                           (lid, pid, pid, advert, url, status, stamp(96), change))
                old = lid * 10
                sid = old + 1
                traits = json.dumps({"Alter": [str(24 + pid)], "Größe": ["170 cm"]}, ensure_ascii=False)
                for snap, desc, captured in [(old, "Synthetischer früherer Text", stamp(96)), (sid, "Synthetischer aktueller Text", change)]:
                    db.execute("INSERT INTO listing_snapshots(snapshot_id,listing_id,fingerprint,name,city,description,traits_json,services_json,opening_times_json,captured_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                               (snap, lid, f"fixture-{snap}", name, "Leipzig", desc, traits, "{}", "{}", captured))
                db.execute("UPDATE listings SET current_snapshot_id=? WHERE listing_id=?", (sid, lid))
                db.execute("INSERT INTO change_events(person_id,listing_id,from_snapshot_id,to_snapshot_id,event_type,field_key,old_json,new_json,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
                           (pid, lid, old, sid, "FIELD_CHANGED", "description", json.dumps("Synthetischer früherer Text"), json.dumps("Synthetischer aktueller Text"), change))
                thumbnail = None
                if media != "NONE":
                    data = png((140 + pid * 10, 80 + pid * 12, 145))
                    digest = hashlib.sha256(data).hexdigest()
                    rel = f"media/images/{digest}.png"
                    assets[rel] = data
                    db.execute("INSERT OR IGNORE INTO media_assets(sha256,kind,mime_type,byte_size,width,height,local_path) VALUES(?,'IMAGE','image/png',?,48,64,?)",
                               (digest, len(data), rel))
                    asset = db.execute("SELECT media_asset_id FROM media_assets WHERE sha256=?", (digest,)).fetchone()[0]
                    thumbnail = url + "/image.png"
                    db.execute("INSERT INTO snapshot_media(snapshot_id,media_asset_id,position,kind,remote_url) VALUES(?,?,0,'IMAGE',?)", (sid, asset, thumbnail))
                    if media == "VIDEO":
                        db.execute("INSERT INTO snapshot_media(snapshot_id,position,kind,remote_url) VALUES(?,1,'VIDEO',?)", (sid, url + "/video.mp4"))
                profiles.append(dict(person_id=pid, listing_id=lid, advert_id=advert, name=name,
                                     age=24 + pid, city="Leipzig", status=status, preference=pref,
                                     favorite=fav, changed_at=change, media=media,
                                     image_asset=rel if thumbnail else None, ai_marker=pid == 5))
        filters = dict(city="Leipzig", radius=20, category="", ageMin=18, ageMax=60,
                       heightMin=140, heightMax=200, selected={})
        for run, cards in [(1, []), (2, profiles[:1]), (3, profiles[:-2])]:
            db.execute("INSERT INTO search_runs(search_run_id,city,radius_km,advert_count,distance_searched,filters_json) VALUES(?,?,?,?,?,?)",
                       (run, "Leipzig", 20, len(cards), 20, json.dumps(filters)))
            for pos, card in enumerate(cards):
                db.execute("INSERT INTO search_results(search_run_id,listing_id,position,name,city,has_video,has_ai_content) VALUES(?,?,?,?,?,?,?)",
                           (run, card["listing_id"], pos, card["name"], card["city"], int(card["media"] == "VIDEO"), int(card["ai_marker"])))
        for sid, local in [("fixture-supported", {"hideLikes": True}), ("fixture-unsupported", {"imageMode": "STUDIO"})]:
            definition = {**filters, "local": local}
            db.execute("INSERT INTO saved_searches(saved_search_id,name,filters_json) VALUES(?,?,?)", (sid, sid, json.dumps(definition)))
        for pos, card in enumerate(profiles[:2]):
            db.execute("INSERT INTO saved_search_results(saved_search_id,result_key,snapshot_json,position,present,visible) VALUES(?,?,?,?,1,1)",
                       ("fixture-supported", card["advert_id"], json.dumps(card), pos))
        assert db.execute("PRAGMA integrity_check").fetchone() == ("ok",)
        assert not db.execute("PRAGMA foreign_key_check").fetchall()
        assert db.execute("SELECT COUNT(*) FROM persons").fetchone() == (8,)
        assert db.execute("SELECT COUNT(*) FROM listings").fetchone() == (9,)
        assert db.execute("SELECT COUNT(*) FROM search_results WHERE search_run_id=3").fetchone() == (7,)
        assert db.execute("SELECT COUNT(*) FROM listing_snapshots").fetchone() == (18,)
        assert db.execute("SELECT COUNT(*) FROM (SELECT DISTINCT preference,favorite FROM person_user_state)").fetchone() == (6,)
    db.close()
    for rel, data in sorted(assets.items()):
        target = out / "ladies_data" / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
    # Fixture inputs and expectations only; not a replay server.
    scenarios = {
        "clock": stamp(0), "viewports_dp": [360, 390, 430, 600],
        "font_scales": [1.0, 1.3], "themes": ["light", "dark"],
        "profiles": profiles,
        "cases": {
            "P05-P14": ["zero", "one", "many", "duplicate person", "draft vs active run",
                         "partial radius boundary", "unverified radius", "timeout", "stale response", "cancel"],
            "P15-P23": ["no media", "local image", "image plus unavailable video", "offline",
                         "two listings", "undo error", "skip/reset"],
            "P24-P32": ["grid like", "back/scroll", "note edit/restart", "snapshot comparison", "unknown fields"],
            "P33-P39": ["all preference/favorite combinations", "36h boundary", "four recent favorites",
                         "status failure", "supported and unsupported saved definitions", "delta baseline"],
            "P01-P04/P40-P41": ["Insets", "rotation", "large text", "TalkBack", "map scope", "diagnostic access"]
        }
    }
    (out / "scenarios.json").write_text(json.dumps(scenarios, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Generated {db_path} and scenarios.json: 8 persons, 9 listings, 18 snapshots, 3 search runs")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path, help="new directory; never overwrites an existing DB")
    parser.add_argument("--clock", type=datetime.fromisoformat,
                        default=datetime.now(timezone.utc), help="ISO-8601 reference clock with timezone")
    args = parser.parse_args()
    if args.clock.tzinfo is None:
        parser.error("--clock must include a timezone")
    generate(args.output, args.clock.astimezone(timezone.utc))
