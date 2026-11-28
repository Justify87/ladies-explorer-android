use rusqlite::{Connection, OptionalExtension, Row, params};

use crate::{CoreError, LadiesCore, identity::require_listing_owner};

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct PriceItem {
    pub label: Option<String>,
    pub duration_minutes: Option<i64>,
    pub amount_cents: i64,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct PriceListValues {
    pub listing_id: Option<i64>,
    pub observed_at: String,
    pub items: Vec<PriceItem>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct PriceList {
    pub price_list_id: i64,
    pub person_id: i64,
    pub values: PriceListValues,
    pub item_ids: Vec<i64>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct PaidEncounter {
    pub occurred_at: String,
    pub amount_cents: i64,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct PriceOverview {
    pub lists: Vec<PriceList>,
    pub last_paid: Option<PaidEncounter>,
}

const OWNER_CHANGED: &str = "Price list owner changed; reopen profile";

fn validate(db: &Connection, person_id: i64, values: &PriceListValues) -> Result<(), CoreError> {
    if let Some(listing) = values.listing_id {
        require_listing_owner(db, listing, person_id, OWNER_CHANGED)?;
    }
    let canonical: Option<String> = db.query_row(
        "SELECT strftime('%Y-%m-%dT%H:%M:%fZ', ?1, '+0 seconds')",
        [&values.observed_at],
        |row| row.get(0),
    )?;
    if canonical.as_deref() != Some(values.observed_at.as_str())
        || values.observed_at.starts_with("0000-")
        || values.items.is_empty()
        || values.items.iter().any(|item| {
            item.amount_cents < 0
                || item.duration_minutes.is_some_and(|v| v <= 0)
                || item.label.as_ref().is_some_and(|v| v.trim().is_empty())
        })
    {
        return Err(CoreError::InvalidInput(
            "Invalid price list date, amount, duration or label".into(),
        ));
    }
    Ok(())
}

fn from_row(row: &Row<'_>) -> rusqlite::Result<PriceList> {
    Ok(PriceList {
        price_list_id: row.get(0)?,
        person_id: row.get(1)?,
        values: PriceListValues {
            listing_id: row.get(2)?,
            observed_at: row.get(3)?,
            items: Vec::new(),
        },
        item_ids: Vec::new(),
    })
}

fn load_items(db: &Connection, list: &mut PriceList) -> Result<(), CoreError> {
    let mut stmt = db.prepare(
        "SELECT price_item_id,label,duration_minutes,amount_cents
         FROM price_items WHERE price_list_id=?1 ORDER BY price_item_id",
    )?;
    let rows = stmt.query_map([list.price_list_id], |row| {
        Ok((
            row.get::<_, i64>(0)?,
            PriceItem {
                label: row.get(1)?,
                duration_minutes: row.get(2)?,
                amount_cents: row.get(3)?,
            },
        ))
    })?;
    for row in rows {
        let (id, item) = row?;
        list.item_ids.push(id);
        list.values.items.push(item);
    }
    Ok(())
}

fn read_one(db: &Connection, id: i64) -> Result<Option<PriceList>, CoreError> {
    let mut result = db
        .query_row(
            "SELECT price_list_id,person_id,listing_id,observed_at FROM price_lists
             WHERE price_list_id=?1 AND source='USER_ENTERED' AND currency='EUR'",
            [id],
            from_row,
        )
        .optional()?;
    if let Some(list) = &mut result {
        load_items(db, list)?;
    }
    Ok(result)
}

fn require_current(db: &Connection, person_id: i64, expected: &PriceList) -> Result<(), CoreError> {
    if expected.person_id != person_id
        || read_one(db, expected.price_list_id)?.as_ref() != Some(expected)
    {
        return Err(CoreError::IdentityConflict(
            "Price list has changed; reload before editing".into(),
        ));
    }
    if let Some(listing) = expected.values.listing_id {
        require_listing_owner(db, listing, person_id, OWNER_CHANGED)?;
    }
    Ok(())
}

fn insert_items(db: &Connection, id: i64, items: &[PriceItem]) -> Result<(), CoreError> {
    let mut stmt = db.prepare(
        "INSERT INTO price_items (price_list_id,label,duration_minutes,amount_cents)
         VALUES (?1,?2,?3,?4)",
    )?;
    for item in items {
        stmt.execute(params![
            id,
            item.label,
            item.duration_minutes,
            item.amount_cents
        ])?;
    }
    Ok(())
}

pub(crate) fn overview(
    core: &LadiesCore,
    listing_id: i64,
    person_id: i64,
) -> Result<PriceOverview, CoreError> {
    let db = core.connection()?;
    require_listing_owner(&db, listing_id, person_id, OWNER_CHANGED)?;
    let mut stmt = db.prepare(
        "SELECT price_list_id,person_id,listing_id,observed_at FROM price_lists
         WHERE person_id=?1 AND source='USER_ENTERED' AND currency='EUR'
         ORDER BY substr(observed_at,1,10) DESC, created_at DESC, price_list_id DESC",
    )?;
    let mut lists = stmt
        .query_map([person_id], from_row)?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    for list in &mut lists {
        load_items(&db, list)?;
    }
    let last_paid = db
        .query_row(
            "SELECT occurred_at,total_price_cents FROM encounters
             WHERE person_id=?1 AND total_price_cents IS NOT NULL
             ORDER BY occurred_at DESC,encounter_id DESC LIMIT 1",
            [person_id],
            |row| {
                Ok(PaidEncounter {
                    occurred_at: row.get(0)?,
                    amount_cents: row.get(1)?,
                })
            },
        )
        .optional()?;
    Ok(PriceOverview { lists, last_paid })
}

pub(crate) fn create(
    core: &LadiesCore,
    listing_id: i64,
    person_id: i64,
    values: PriceListValues,
) -> Result<PriceList, CoreError> {
    let mut db = core.connection()?;
    let tx = db.transaction()?;
    require_listing_owner(&tx, listing_id, person_id, OWNER_CHANGED)?;
    validate(&tx, person_id, &values)?;
    // Preserve insertion order even when lists are recorded within the same clock tick.
    tx.execute(
        "INSERT INTO price_lists (price_list_id,person_id,listing_id,observed_at,created_at)
         VALUES ((random() & 9223372036854775807),?1,?2,?3,
                 MAX(strftime('%Y-%m-%dT%H:%M:%fZ','now'),
                     COALESCE((SELECT strftime('%Y-%m-%dT%H:%M:%fZ',MAX(created_at),'+0.001 seconds')
                               FROM price_lists), '')))",
        params![person_id, values.listing_id, values.observed_at],
    )?;
    let id = tx.last_insert_rowid();
    insert_items(&tx, id, &values.items)?;
    let result = read_one(&tx, id)?
        .ok_or_else(|| CoreError::Storage("Created price list missing".into()))?;
    tx.commit()?;
    Ok(result)
}

pub(crate) fn update(
    core: &LadiesCore,
    listing_id: i64,
    person_id: i64,
    expected: PriceList,
    values: PriceListValues,
) -> Result<PriceList, CoreError> {
    let mut db = core.connection()?;
    let tx = db.transaction()?;
    require_listing_owner(&tx, listing_id, person_id, OWNER_CHANGED)?;
    require_current(&tx, person_id, &expected)?;
    validate(&tx, person_id, &values)?;
    if expected.values != values {
        tx.execute(
            "UPDATE price_lists SET listing_id=?1,observed_at=?2 WHERE price_list_id=?3",
            params![
                values.listing_id,
                values.observed_at,
                expected.price_list_id
            ],
        )?;
        // Insert before deleting: the current item IDs are not recycled, even for date-only corrections.
        insert_items(&tx, expected.price_list_id, &values.items)?;
        for id in &expected.item_ids {
            tx.execute("DELETE FROM price_items WHERE price_item_id=?1", [id])?;
        }
    }
    let result = read_one(&tx, expected.price_list_id)?
        .ok_or_else(|| CoreError::Storage("Updated price list missing".into()))?;
    tx.commit()?;
    Ok(result)
}

pub(crate) fn delete(
    core: &LadiesCore,
    listing_id: i64,
    person_id: i64,
    expected: PriceList,
) -> Result<(), CoreError> {
    let mut db = core.connection()?;
    let tx = db.transaction()?;
    require_listing_owner(&tx, listing_id, person_id, OWNER_CHANGED)?;
    require_current(&tx, person_id, &expected)?;
    tx.execute(
        "DELETE FROM price_lists WHERE price_list_id=?1",
        [expected.price_list_id],
    )?;
    tx.commit()?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn values(date: &str, amount_cents: i64) -> PriceListValues {
        PriceListValues {
            listing_id: None,
            observed_at: format!("{date}T00:00:00.000Z"),
            items: vec![PriceItem {
                label: None,
                duration_minutes: Some(30),
                amount_cents,
            }],
        }
    }

    #[test]
    fn history_owner_cas_and_rollback() {
        let core = LadiesCore::new(":memory:".into()).unwrap();
        let person = core.create_person(None).unwrap();
        let other = core.create_person(None).unwrap();
        let listing = core
            .add_listing(person, None, "LADIES".into(), "1".into(), None)
            .unwrap();
        for (invalid_listing, invalid_owner) in [(listing, other), (i64::MAX, person)] {
            assert!(matches!(
                overview(&core, invalid_listing, invalid_owner),
                Err(CoreError::IdentityConflict(message)) if message == OWNER_CHANGED
            ));
        }
        let foreign = core
            .add_listing(other, None, "LADIES".into(), "2".into(), None)
            .unwrap();
        let older = create(&core, listing, person, values("2026-11-23", 0)).unwrap();
        let original = create(&core, listing, person, values("2026-11-24", 1234)).unwrap();
        assert_eq!(
            overview(&core, listing, person).unwrap().lists,
            vec![original.clone(), older.clone()]
        );
        assert!(overview(&core, foreign, person).is_err());
        let mut invalid = original.values.clone();
        invalid.listing_id = Some(foreign);
        assert!(create(&core, listing, person, invalid.clone()).is_err());
        assert!(update(&core, listing, person, original.clone(), invalid).is_err());
        {
            let db = core.connection().unwrap();
            db.execute_batch(
                "CREATE TEMP TRIGGER fail_price BEFORE INSERT ON price_items
                 WHEN NEW.amount_cents=77 BEGIN SELECT RAISE(ABORT,'fixture price rollback'); END;",
            )
            .unwrap();
        }
        let mut failing = values("2026-11-25", 5);
        failing.items.push(PriceItem {
            label: None,
            duration_minutes: None,
            amount_cents: 77,
        });
        assert!(matches!(
            create(&core, listing, person, failing.clone()),
            Err(CoreError::Storage(reason)) if reason.contains("fixture price rollback")
        ));
        assert_eq!(
            overview(&core, listing, person).unwrap().lists,
            vec![original.clone(), older.clone()]
        );
        assert!(matches!(
            update(&core, listing, person, original.clone(), failing),
            Err(CoreError::Storage(reason)) if reason.contains("fixture price rollback")
        ));
        assert_eq!(
            overview(&core, listing, person).unwrap().lists,
            vec![original.clone(), older.clone()]
        );
        let corrected = update(
            &core,
            listing,
            person,
            original.clone(),
            values("2026-11-24", 99),
        )
        .unwrap();
        let reverted = update(&core, listing, person, corrected, original.values.clone()).unwrap();
        assert_ne!(reverted.item_ids, original.item_ids);
        assert!(delete(&core, listing, person, original).is_err());
        delete(&core, listing, person, reverted).unwrap();
        assert_eq!(overview(&core, listing, person).unwrap().lists, vec![older]);
        let db = core.connection().unwrap();
        let count: i64 = db
            .query_row("SELECT COUNT(*) FROM price_items", [], |row| row.get(0))
            .unwrap();
        assert_eq!(count, 1);
    }

    #[test]
    fn latest_recorded_prices_and_last_known_payment_are_independent() {
        let core = LadiesCore::new(":memory:".into()).unwrap();
        let person = core.create_person(None).unwrap();
        let listing = core
            .add_listing(person, None, "LADIES".into(), "1".into(), None)
            .unwrap();
        let first = create(&core, listing, person, values("2026-11-24", 0)).unwrap();
        let second = create(&core, listing, person, values("2026-11-24", 1234)).unwrap();
        let latest = overview(&core, listing, person).unwrap();
        assert!(latest.last_paid.is_none());
        let ids = vec![second.price_list_id, first.price_list_id];
        assert_eq!(
            latest
                .lists
                .iter()
                .map(|row| row.price_list_id)
                .collect::<Vec<_>>(),
            ids
        );

        let encounter = |date: &str, total_price_cents| crate::EncounterValues {
            listing_id: None,
            occurred_at: format!("{date}T12:00:00.000Z"),
            duration_minutes: None,
            total_price_cents,
            overall_rating: None,
            sympathy_rating: None,
            note: None,
            base_price_cents: Some(444),
            services: Vec::new(),
        };
        core.create_encounter(listing, person, encounter("2026-11-22", Some(700)))
            .unwrap();
        let paid = core
            .create_encounter(listing, person, encounter("2026-11-23", Some(0)))
            .unwrap();
        core.create_encounter(listing, person, encounter("2026-11-24", None))
            .unwrap();
        assert_eq!(
            overview(&core, listing, person).unwrap().last_paid,
            Some(PaidEncounter {
                occurred_at: paid.values.occurred_at.clone(),
                amount_cents: 0,
            })
        );
        let corrected = core
            .update_encounter(listing, person, paid, encounter("2026-11-23", Some(250)))
            .unwrap();
        let result = overview(&core, listing, person).unwrap();
        assert_eq!(result.last_paid.unwrap().amount_cents, 250);
        assert_eq!(result.lists, latest.lists);
        core.delete_encounter(listing, person, corrected).unwrap();
        let result = overview(&core, listing, person).unwrap();
        assert_eq!(result.last_paid.unwrap().amount_cents, 700);
        assert_eq!(result.lists, latest.lists);
    }

    #[test]
    fn same_day_history_uses_creation_order_and_corrections_preserve_it() {
        let core = LadiesCore::new(":memory:".into()).unwrap();
        let person = core.create_person(None).unwrap();
        let listing = core
            .add_listing(person, None, "LADIES".into(), "1".into(), None)
            .unwrap();
        let first = create(&core, listing, person, values("2026-11-24", 100)).unwrap();
        let second = create(&core, listing, person, values("2026-11-24", 200)).unwrap();
        let corrected = core
            .update_price_list(listing, person, first, values("2026-11-24", 300))
            .unwrap();
        assert_eq!(
            overview(&core, listing, person).unwrap().lists,
            vec![second.clone(), corrected.clone()]
        );
        let older = core
            .update_price_list(listing, person, second, values("2026-11-23", 200))
            .unwrap();
        assert_eq!(
            overview(&core, listing, person).unwrap().lists,
            vec![corrected, older]
        );
    }

    #[test]
    fn same_day_history_does_not_use_random_id_as_creation_order() {
        let core = LadiesCore::new(":memory:".into()).unwrap();
        let person = core.create_person(None).unwrap();
        let listing = core
            .add_listing(person, None, "LADIES".into(), "1".into(), None)
            .unwrap();
        let first = create(&core, listing, person, values("2026-11-24", 100)).unwrap();
        let second = create(&core, listing, person, values("2026-11-24", 200)).unwrap();
        let (newer, older) = if first.price_list_id < second.price_list_id {
            (first, second)
        } else {
            (second, first)
        };
        {
            let db = core.connection().unwrap();
            db.execute(
                "UPDATE price_lists SET created_at=?1 WHERE price_list_id=?2",
                params!["2026-11-24T12:00:00.001Z", newer.price_list_id],
            )
            .unwrap();
            db.execute(
                "UPDATE price_lists SET created_at=?1 WHERE price_list_id=?2",
                params!["2026-11-24T12:00:00.000Z", older.price_list_id],
            )
            .unwrap();
        }
        assert_eq!(
            overview(&core, listing, person).unwrap().lists,
            vec![newer, older]
        );
    }

    #[test]
    fn invalid_prices_and_dates_are_rejected() {
        let core = LadiesCore::new(":memory:".into()).unwrap();
        let person = core.create_person(None).unwrap();
        let listing = core
            .add_listing(person, None, "LADIES".into(), "1".into(), None)
            .unwrap();
        for invalid in [
            values("2026-02-30", 0),
            values("0000-01-01", 0),
            values("2026-11-24", -1),
            PriceListValues {
                items: Vec::new(),
                ..values("2026-11-24", 0)
            },
            PriceListValues {
                items: vec![PriceItem {
                    label: None,
                    duration_minutes: Some(0),
                    amount_cents: 0,
                }],
                ..values("2026-11-24", 0)
            },
        ] {
            assert!(create(&core, listing, person, invalid).is_err());
        }
        assert!(overview(&core, listing, person).unwrap().lists.is_empty());
    }
}
