use rusqlite::{Connection, OptionalExtension, Row, params};

use crate::{CoreError, LadiesCore};

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct EncounterService {
    pub name: String,
    pub amount_cents: Option<i64>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct EncounterValues {
    pub listing_id: Option<i64>,
    pub occurred_at: String,
    pub duration_minutes: Option<i64>,
    pub total_price_cents: Option<i64>,
    pub overall_rating: Option<i64>,
    pub sympathy_rating: Option<i64>,
    pub note: Option<String>,
    pub base_price_cents: Option<i64>,
    pub services: Vec<EncounterService>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct Encounter {
    pub encounter_id: i64,
    pub person_id: i64,
    pub values: EncounterValues,
    pub created_at: String,
    pub updated_at: String,
}

const COLUMNS: &str = "encounter_id, person_id, listing_id, occurred_at, duration_minutes,
    total_price_cents, overall_rating, sympathy_rating, note, created_at, updated_at, base_price_cents";

fn from_row(row: &Row<'_>) -> rusqlite::Result<Encounter> {
    Ok(Encounter {
        encounter_id: row.get(0)?,
        person_id: row.get(1)?,
        values: EncounterValues {
            listing_id: row.get(2)?,
            occurred_at: row.get(3)?,
            duration_minutes: row.get(4)?,
            total_price_cents: row.get(5)?,
            overall_rating: row.get(6)?,
            sympathy_rating: row.get(7)?,
            note: row.get(8)?,
            base_price_cents: row.get(11)?,
            services: Vec::new(),
        },
        created_at: row.get(9)?,
        updated_at: row.get(10)?,
    })
}

fn require_owner(db: &Connection, listing_id: i64, person_id: i64) -> Result<(), CoreError> {
    let owner = db
        .query_row(
            "SELECT person_id FROM listings WHERE listing_id=?1",
            [listing_id],
            |row| row.get::<_, i64>(0),
        )
        .optional()?;
    if owner != Some(person_id) {
        return Err(CoreError::IdentityConflict(
            "Encounter owner changed; reopen profile".into(),
        ));
    }
    Ok(())
}

fn validate(db: &Connection, person_id: i64, values: &EncounterValues) -> Result<(), CoreError> {
    if let Some(listing) = values.listing_id {
        require_owner(db, listing, person_id)?;
    }
    // Canonical UTC milliseconds make textual chronology deterministic.
    let canonical: Option<String> = db.query_row(
        "SELECT strftime('%Y-%m-%dT%H:%M:%fZ', ?1, '+0 seconds')",
        [&values.occurred_at],
        |row| row.get(0),
    )?;
    if canonical.as_deref() != Some(values.occurred_at.as_str()) {
        return Err(CoreError::InvalidInput(
            "Encounter time must be a valid UTC timestamp with milliseconds".into(),
        ));
    }
    if values.duration_minutes.is_some_and(|v| v <= 0)
        || values.total_price_cents.is_some_and(|v| v < 0)
        || values.base_price_cents.is_some_and(|v| v < 0)
        || values
            .services
            .iter()
            .any(|v| v.name.trim().is_empty() || v.amount_cents.is_some_and(|cents| cents < 0))
        || [values.overall_rating, values.sympathy_rating]
            .into_iter()
            .flatten()
            .any(|v| !(1..=5).contains(&v))
    {
        return Err(CoreError::InvalidInput(
            "Invalid encounter duration, amount, service or rating".into(),
        ));
    }
    Ok(())
}

fn load_services(db: &Connection, row: &mut Encounter) -> Result<(), CoreError> {
    let mut stmt = db.prepare(
        "SELECT service_name, amount_cents FROM encounter_services
         WHERE encounter_id=?1 ORDER BY encounter_service_id",
    )?;
    row.values.services = stmt
        .query_map([row.encounter_id], |r| {
            Ok(EncounterService {
                name: r.get(0)?,
                amount_cents: r.get(1)?,
            })
        })?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    Ok(())
}

fn replace_services(
    db: &Connection,
    id: i64,
    services: &[EncounterService],
) -> Result<(), CoreError> {
    db.execute("DELETE FROM encounter_services WHERE encounter_id=?1", [id])?;
    let mut stmt = db.prepare(
        "INSERT INTO encounter_services (encounter_id,service_name,amount_cents)
         VALUES (?1,?2,?3)",
    )?;
    for service in services {
        stmt.execute(params![id, service.name, service.amount_cents])?;
    }
    Ok(())
}

fn read_one(db: &Connection, id: i64) -> Result<Option<Encounter>, CoreError> {
    let mut result = db
        .query_row(
            &format!("SELECT {COLUMNS} FROM encounters WHERE encounter_id=?1"),
            [id],
            from_row,
        )
        .optional()?;
    if let Some(row) = &mut result {
        load_services(db, row)?;
    }
    Ok(result)
}

fn require_current(db: &Connection, person_id: i64, expected: &Encounter) -> Result<(), CoreError> {
    if expected.person_id != person_id
        || read_one(db, expected.encounter_id)?.as_ref() != Some(expected)
    {
        return Err(CoreError::IdentityConflict(
            "Encounter has changed; reload before editing".into(),
        ));
    }
    if let Some(listing) = expected.values.listing_id {
        require_owner(db, listing, person_id)?;
    }
    Ok(())
}

pub(crate) fn list(
    core: &LadiesCore,
    listing_id: i64,
    person_id: i64,
) -> Result<Vec<Encounter>, CoreError> {
    let db = core.connection()?;
    require_owner(&db, listing_id, person_id)?;
    let mut stmt = db.prepare(&format!(
        "SELECT {COLUMNS} FROM encounters WHERE person_id=?1
         ORDER BY occurred_at DESC, encounter_id DESC"
    ))?;
    let mut rows = stmt
        .query_map([person_id], from_row)?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    for row in &mut rows {
        load_services(&db, row)?;
    }
    Ok(rows)
}

pub(crate) fn create(
    core: &LadiesCore,
    listing_id: i64,
    person_id: i64,
    values: EncounterValues,
) -> Result<Encounter, CoreError> {
    let mut db = core.connection()?;
    let tx = db.transaction()?;
    require_owner(&tx, listing_id, person_id)?;
    validate(&tx, person_id, &values)?;
    // Random nonnegative IDs avoid recycling a deleted row's editor identity.
    tx.execute(
        "INSERT INTO encounters
         (encounter_id,person_id,listing_id,occurred_at,duration_minutes,total_price_cents,
          overall_rating,sympathy_rating,note,base_price_cents)
         VALUES ((random() & 9223372036854775807),?1,?2,?3,?4,?5,?6,?7,?8,?9)",
        params![
            person_id,
            values.listing_id,
            values.occurred_at,
            values.duration_minutes,
            values.total_price_cents,
            values.overall_rating,
            values.sympathy_rating,
            values.note,
            values.base_price_cents
        ],
    )?;
    let id = tx.last_insert_rowid();
    replace_services(&tx, id, &values.services)?;
    let result = read_one(&tx, id)?
        .ok_or_else(|| CoreError::Storage("Created encounter missing".into()))?;
    tx.commit()?;
    Ok(result)
}

pub(crate) fn update(
    core: &LadiesCore,
    listing_id: i64,
    person_id: i64,
    expected: Encounter,
    values: EncounterValues,
) -> Result<Encounter, CoreError> {
    let mut db = core.connection()?;
    let tx = db.transaction()?;
    require_owner(&tx, listing_id, person_id)?;
    require_current(&tx, person_id, &expected)?;
    validate(&tx, person_id, &values)?;
    // Even identical edits within one clock tick invalidate an older editor.
    tx.execute(
        "UPDATE encounters SET listing_id=?1,occurred_at=?2,duration_minutes=?3,
         total_price_cents=?4,overall_rating=?5,sympathy_rating=?6,note=?7,base_price_cents=?8,
         updated_at=MAX(strftime('%Y-%m-%dT%H:%M:%fZ','now'),
                        strftime('%Y-%m-%dT%H:%M:%fZ',updated_at,'+0.001 seconds'))
         WHERE encounter_id=?9",
        params![
            values.listing_id,
            values.occurred_at,
            values.duration_minutes,
            values.total_price_cents,
            values.overall_rating,
            values.sympathy_rating,
            values.note,
            values.base_price_cents,
            expected.encounter_id
        ],
    )?;
    if expected.values.services != values.services {
        replace_services(&tx, expected.encounter_id, &values.services)?;
    }
    let result = read_one(&tx, expected.encounter_id)?
        .ok_or_else(|| CoreError::Storage("Updated encounter missing".into()))?;
    tx.commit()?;
    Ok(result)
}

pub(crate) fn delete(
    core: &LadiesCore,
    listing_id: i64,
    person_id: i64,
    expected: Encounter,
) -> Result<(), CoreError> {
    let mut db = core.connection()?;
    let tx = db.transaction()?;
    require_owner(&tx, listing_id, person_id)?;
    require_current(&tx, person_id, &expected)?;
    tx.execute(
        "DELETE FROM encounters WHERE encounter_id=?1",
        [expected.encounter_id],
    )?;
    tx.commit()?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn values() -> EncounterValues {
        EncounterValues {
            listing_id: None,
            occurred_at: "2026-11-24T12:00:00.000Z".into(),
            duration_minutes: None,
            total_price_cents: None,
            overall_rating: None,
            sympathy_rating: None,
            note: None,
            base_price_cents: None,
            services: Vec::new(),
        }
    }

    #[test]
    fn crud_across_listings_checks_owner_and_editor_snapshot() {
        let core = LadiesCore::new(":memory:".into()).unwrap();
        let person = core.create_person(None).unwrap();
        let other = core.create_person(None).unwrap();
        let listing = core
            .add_listing(person, None, "LADIES".into(), "1".into(), None)
            .unwrap();
        let second = core
            .add_listing(person, None, "LADIES".into(), "2".into(), None)
            .unwrap();
        let foreign = core
            .add_listing(other, None, "LADIES".into(), "3".into(), None)
            .unwrap();
        let original = create(&core, listing, person, values()).unwrap();
        let mut changed = values();
        changed.listing_id = Some(second);
        changed.total_price_cents = Some(0);
        changed.base_price_cents = Some(1000);
        changed.services = vec![
            EncounterService {
                name: "Fixture A".into(),
                amount_cents: None,
            },
            EncounterService {
                name: "Fixture B".into(),
                amount_cents: Some(0),
            },
        ];
        changed.overall_rating = Some(5);
        changed.duration_minutes = Some(15);
        let updated = update(&core, second, person, original.clone(), changed.clone()).unwrap();
        assert_eq!(updated.values, changed);
        assert_ne!(updated.updated_at, original.updated_at);
        assert!(update(&core, listing, person, original.clone(), values()).is_err());
        assert!(delete(&core, listing, person, original).is_err());
        assert!(list(&core, foreign, person).is_err());
        let mut invalid = values();
        invalid.listing_id = Some(foreign);
        assert!(update(&core, listing, person, updated.clone(), invalid).is_err());
        assert!(delete(&core, foreign, other, updated.clone()).is_err());
        assert_eq!(list(&core, listing, person).unwrap(), vec![updated.clone()]);
        let mut older = values();
        older.occurred_at = "2026-11-23T12:00:00.000Z".into();
        let older = create(&core, second, person, older).unwrap();
        assert_eq!(
            list(&core, listing, person).unwrap(),
            vec![updated.clone(), older.clone()]
        );
        assert!(older.values.total_price_cents.is_none());
        delete(&core, second, person, updated.clone()).unwrap();
        assert!(delete(&core, second, person, updated).is_err());
        assert_eq!(list(&core, listing, person).unwrap(), vec![older]);
        let db = core.connection().unwrap();
        let children: i64 = db
            .query_row("SELECT COUNT(*) FROM encounter_services", [], |r| r.get(0))
            .unwrap();
        assert_eq!(children, 0);
    }

    #[test]
    fn child_changes_are_atomic_and_part_of_editor_snapshot() {
        let core = LadiesCore::new(":memory:".into()).unwrap();
        let person = core.create_person(None).unwrap();
        let listing = core
            .add_listing(person, None, "LADIES".into(), "1".into(), None)
            .unwrap();
        let mut input = values();
        input.base_price_cents = Some(0);
        input.services = vec![EncounterService {
            name: "Fixture".into(),
            amount_cents: None,
        }];
        let original = create(&core, listing, person, input.clone()).unwrap();
        assert_eq!(original.values, input);
        assert!(original.values.total_price_cents.is_none());
        let mut invalid = input.clone();
        invalid.services[0].amount_cents = Some(-1);
        assert!(update(&core, listing, person, original.clone(), invalid).is_err());
        assert_eq!(list(&core, listing, person).unwrap(), vec![original.clone()]);
        {
            let db = core.connection().unwrap();
            db.execute_batch(
                "CREATE TEMP TRIGGER fail_service BEFORE INSERT ON encounter_services
                 WHEN NEW.service_name='Fail' BEGIN SELECT RAISE(ABORT,'fixture'); END;",
            )
            .unwrap();
        }
        let mut failing = input.clone();
        failing.base_price_cents = Some(1234);
        failing.services.push(EncounterService {
            name: "Fail".into(),
            amount_cents: None,
        });
        assert!(create(&core, listing, person, failing.clone()).is_err());
        assert!(update(&core, listing, person, original.clone(), failing).is_err());
        assert_eq!(list(&core, listing, person).unwrap(), vec![original.clone()]);
        {
            let db = core.connection().unwrap();
            db.execute_batch("DROP TRIGGER fail_service").unwrap();
            db.execute(
                "UPDATE encounter_services SET amount_cents=0 WHERE encounter_id=?1",
                [original.encounter_id],
            )
            .unwrap();
        }
        assert!(update(&core, listing, person, original.clone(), input.clone()).is_err());
        assert!(delete(&core, listing, person, original).is_err());
        let current = list(&core, listing, person).unwrap().remove(0);
        input.services.clear();
        let updated = update(&core, listing, person, current, input.clone()).unwrap();
        assert_eq!(updated.values, input);
        assert_eq!(list(&core, listing, person).unwrap(), vec![updated]);
    }

    #[test]
    fn invalid_fields_do_not_persist() {
        let core = LadiesCore::new(":memory:".into()).unwrap();
        let person = core.create_person(None).unwrap();
        let listing = core
            .add_listing(person, None, "LADIES".into(), "1".into(), None)
            .unwrap();
        for invalid in [
            EncounterValues {
                duration_minutes: Some(0),
                ..values()
            },
            EncounterValues {
                total_price_cents: Some(-1),
                ..values()
            },
            EncounterValues {
                base_price_cents: Some(-1),
                ..values()
            },
            EncounterValues {
                services: vec![EncounterService {
                    name: "  ".into(),
                    amount_cents: None,
                }],
                ..values()
            },
            EncounterValues {
                services: vec![EncounterService {
                    name: "Fixture".into(),
                    amount_cents: Some(-1),
                }],
                ..values()
            },
            EncounterValues {
                overall_rating: Some(6),
                ..values()
            },
            EncounterValues {
                sympathy_rating: Some(0),
                ..values()
            },
            EncounterValues {
                occurred_at: "2026-02-30T12:00:00.000Z".into(),
                ..values()
            },
        ] {
            assert!(create(&core, listing, person, invalid).is_err());
        }
        assert!(list(&core, listing, person).unwrap().is_empty());
    }
}
