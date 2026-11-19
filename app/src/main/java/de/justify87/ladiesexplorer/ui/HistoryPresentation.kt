package de.justify87.ladiesexplorer.ui

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun historyFieldLabel(key: String?): String = when (key) {
    "name" -> "Name"
    "city" -> "Ort"
    "postal_code" -> "Postleitzahl"
    "hidden_location" -> "Standortangabe"
    "category" -> "Kategorie"
    "description" -> "Beschreibung"
    "published_at" -> "Veröffentlichung"
    "external_profile_id", "canonical_id" -> "Profilkennung"
    "phone_display", "normalized_phone" -> "Telefon"
    "traits_json" -> "Merkmale"
    "services_json" -> "Dienste"
    "opening_times_json" -> "Öffnungszeiten"
    "media" -> "Medien"
    "status", "current_status" -> "Status"
    null -> "Profil"
    else -> "Weitere Angabe"
}

internal fun historyEventLabel(type: String, field: String?): String = when (type.uppercase(Locale.ROOT)) {
    "PROFILE_CREATED", "LISTING_FIRST_SNAPSHOT" -> "Anzeige entdeckt"
    "NEW_LISTING_FOR_PERSON" -> "Person wiedererkannt"
    "MEDIA_CHANGED" -> "Medien geändert"
    "STATUS_CHANGED" -> "Status geändert"
    "FIELD_CHANGED" -> "${historyFieldLabel(field)} geändert"
    else -> if (field == null) "Profilereignis" else "${historyFieldLabel(field)} geändert"
}

internal fun historyDate(raw: String): String {
    val stamp = runCatching { Instant.parse(raw) }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(raw).toInstant() }.getOrNull()
    return stamp?.atZone(ZoneId.systemDefault())
        ?.format(DateTimeFormatter.ofPattern("dd.MM.yyyy · HH:mm", Locale.GERMAN))
        ?: raw.replace('T', ' ')
}
