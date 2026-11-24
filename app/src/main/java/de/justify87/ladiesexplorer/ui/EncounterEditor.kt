package de.justify87.ladiesexplorer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.justify87.ladiesexplorer.core.Encounter
import de.justify87.ladiesexplorer.core.EncounterService
import de.justify87.ladiesexplorer.core.EncounterValues
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.ResolverStyle

private val encounterTime = DateTimeFormatter.ofPattern("dd.MM.uuuu HH:mm")
    .withResolverStyle(ResolverStyle.STRICT)
private val utcMillis = DateTimeFormatterBuilder().appendInstant(3).toFormatter()

internal data class EncounterServiceDraft(
    val id: Long,
    val name: String = "",
    val amount: String = "",
)

internal data class EncounterDraft(
    val whenText: String,
    val duration: String = "",
    val amount: String = "",
    val overall: String = "",
    val sympathy: String = "",
    val note: String = "",
    val listingId: Long? = null,
    val baseAmount: String = "",
    val services: List<EncounterServiceDraft> = emptyList(),
    val nextServiceId: Long = 0,
) {
    fun addService() = copy(
        services = services + EncounterServiceDraft(nextServiceId),
        nextServiceId = nextServiceId + 1,
    )

    fun changeService(id: Long, transform: (EncounterServiceDraft) -> EncounterServiceDraft) =
        copy(services = services.map { if (it.id == id) transform(it) else it })

    fun removeService(id: Long) = copy(services = services.filterNot { it.id == id })

    fun values(expected: Encounter?, zone: ZoneId): EncounterValues {
        val original = expected?.values
        val timestamp = if (original != null && whenText == displayTime(original.occurredAt, zone)) original.occurredAt
        else {
            val local = LocalDateTime.parse(whenText.trim(), encounterTime)
            val offset = zone.rules.getValidOffsets(local).singleOrNull()
                ?: throw IllegalArgumentException("Uhrzeit ist wegen Zeitumstellung nicht eindeutig.")
            utcMillis.format(local.toInstant(offset))
        }
        fun optionalNumber(text: String, range: LongRange): Long? {
            if (text.isBlank()) return null
            return text.trim().toLongOrNull()?.takeIf { it in range }
                ?: throw IllegalArgumentException("Dauer muss positiv, Bewertungen müssen 1–5 sein.")
        }
        val serviceValues = services.map {
            require(it.name.isNotBlank()) { "Leistungsbezeichnung darf nicht leer sein." }
            EncounterService(it.name.trim(), optionalCents(it.amount))
        }
        return EncounterValues(listingId, timestamp, optionalNumber(duration, 1L..Long.MAX_VALUE),
            optionalCents(amount), optionalNumber(overall, 1L..5L), optionalNumber(sympathy, 1L..5L),
            note.takeUnless { it.isBlank() }, optionalCents(baseAmount), serviceValues)
    }

    companion object {
        fun from(row: Encounter?, zone: ZoneId, now: Instant = Instant.now()): EncounterDraft {
            val v = row?.values
            return EncounterDraft(
                whenText = displayTime(v?.occurredAt ?: utcMillis.format(now), zone),
                duration = v?.durationMinutes?.toString().orEmpty(),
                amount = v?.totalPriceCents?.let(::euro).orEmpty(),
                overall = v?.overallRating?.toString().orEmpty(),
                sympathy = v?.sympathyRating?.toString().orEmpty(),
                note = v?.note.orEmpty(),
                listingId = v?.listingId,
                baseAmount = v?.basePriceCents?.let(::euro).orEmpty(),
                services = v?.services.orEmpty().mapIndexed { index, service ->
                    EncounterServiceDraft(index.toLong(), service.name, service.amountCents?.let(::euro).orEmpty())
                },
                nextServiceId = v?.services.orEmpty().size.toLong(),
            )
        }
    }
}

internal fun optionalCents(amount: String): Long? {
    if (amount.isBlank()) return null
    val normalized = amount.trim().replace(',', '.')
    require(Regex("[0-9]+(\\.[0-9]{1,2})?").matches(normalized)) {
        "Betrag muss in EUR mit höchstens zwei Nachkommastellen angegeben werden."
    }
    return normalized.toBigDecimal().movePointRight(2).longValueExact()
}

internal fun displayTime(value: String, zone: ZoneId): String =
    Instant.parse(value).atZone(zone).format(encounterTime)

internal fun euro(cents: Long): String =
    java.math.BigDecimal.valueOf(cents, 2).toPlainString().replace('.', ',')

internal typealias EncounterPanel = ProfileRecordPanel<List<Encounter>>
internal typealias EncounterEdit = ProfileRecordEdit<Encounter, EncounterDraft>

internal class EncounterEditor(
    current: (ProfileIdentityToken) -> Boolean,
    read: suspend (Long, Long) -> List<Encounter>,
    submit: (EncounterEdit, EncounterValues?) -> Unit,
    canBegin: () -> Boolean = { true },
) : ProfileRecordEditor<Encounter, EncounterValues, EncounterDraft, List<Encounter>>(
    current, read, { it }, { row, zone -> EncounterDraft.from(row, zone) },
    { draft, row, zone -> draft.values(row, zone) }, "Begegnungen",
    "Zeitpunkt bitte als TT.MM.JJJJ HH:mm eingeben.", canBegin, submit,
)

@Composable
internal fun EncounterEditorDialog(editor: EncounterEditor) {
    val edit = editor.edit ?: return
    val enabled = editor.canEdit(edit)
    if (edit.discardRequested) {
        AlertDialog(onDismissRequest = { editor.keep(edit) }, title = { Text("Entwurf verwerfen?") },
            text = { Text("Ungespeicherte Änderungen gehen verloren.") },
            confirmButton = { TextButton(onClick = { editor.discard(edit) }) { Text("Verwerfen") } },
            dismissButton = { TextButton(onClick = { editor.keep(edit) }) { Text("Weiter bearbeiten") } })
        return
    }
    AlertDialog(
        onDismissRequest = { editor.requestDismiss(edit) },
        title = { Text(if (edit.deleting) "Begegnung löschen?" else if (edit.expected == null) "Neue Begegnung" else "Begegnung bearbeiten") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (edit.deleting) Text("Die gespeicherte Begegnung wird dauerhaft gelöscht.")
                else {
                    val d = edit.draft
                    fun change(transform: (EncounterDraft) -> EncounterDraft) = editor.change(edit, transform)
                    ProfileEditorField(d.whenText, "Zeitpunkt (TT.MM.JJJJ HH:mm)", enabled) { value -> change { it.copy(whenText = value) } }
                    Text("Zeitzone: ${edit.zone.id}", style = MaterialTheme.typography.bodySmall)
                    ProfileEditorField(d.duration, "Dauer in Minuten (optional)", enabled, KeyboardType.Number) { value -> change { it.copy(duration = value) } }
                    ProfileEditorField(d.amount, "Bezahlt in EUR (optional)", enabled, KeyboardType.Decimal) { value -> change { it.copy(amount = value) } }
                    ProfileEditorField(d.baseAmount, "Grundpreis in EUR (optional)", enabled, KeyboardType.Decimal) { value -> change { it.copy(baseAmount = value) } }
                    Text("Persönliche Leistungsdetails", style = MaterialTheme.typography.titleSmall)
                    d.services.forEachIndexed { index, service ->
                        key(service.id) {
                            ProfileEditorField(service.name, "Leistung ${index + 1}", enabled) { value ->
                                change { it.changeService(service.id) { line -> line.copy(name = value) } }
                            }
                            ProfileEditorField(service.amount, "Betrag ${index + 1} in EUR (optional)", enabled, KeyboardType.Decimal) { value ->
                                change { it.changeService(service.id) { line -> line.copy(amount = value) } }
                            }
                            TextButton(enabled = enabled, onClick = { change { it.removeService(service.id) } }) {
                                Text("Leistung ${index + 1} entfernen")
                            }
                        }
                    }
                    TextButton(enabled = enabled, onClick = { change { it.addService() } }) { Text("Leistung hinzufügen") }
                    ProfileEditorField(d.overall, "Gesamtbewertung 1–5 (optional)", enabled, KeyboardType.Number) { value -> change { it.copy(overall = value) } }
                    ProfileEditorField(d.sympathy, "Sympathie 1–5 (optional)", enabled, KeyboardType.Number) { value -> change { it.copy(sympathy = value) } }
                    OutlinedTextField(d.note, { value -> change { it.copy(note = value) } },
                        enabled = enabled, label = { Text("Private Begegnungsnotiz") },
                        modifier = Modifier.fillMaxWidth(), minLines = 2)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(d.listingId != null, { checked ->
                            change { it.copy(listingId = if (checked) edit.expected?.values?.listingId ?: edit.token.listingId else null) }
                        }, enabled = enabled)
                        Text(if (d.listingId == null) "Anzeige zuordnen" else "Anzeige #${d.listingId} zugeordnet")
                    }
                }
                edit.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (edit.writing) CircularProgressIndicator(Modifier.size(24.dp))
                else if (!enabled) {
                    Text("Der ursprüngliche Profilkontext ist nicht mehr aktiv. Der Entwurf bleibt erhalten.")
                    if (editor.canResume(edit)) TextButton(onClick = { editor.resume(edit) }) { Text("Im aktuellen Profil fortsetzen") }
                }
            }
        },
        confirmButton = { TextButton(enabled = enabled, onClick = { editor.save(edit) }) { Text(if (edit.deleting) "Löschen" else "Speichern") } },
        dismissButton = { TextButton(enabled = !edit.writing, onClick = { editor.requestDismiss(edit) }) { Text("Abbrechen") } },
    )
}

@Composable
internal fun ProfileEditorField(
    value: String, label: String, enabled: Boolean,
    keyboard: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit,
) {
    OutlinedTextField(value, onChange, enabled = enabled, label = { Text(label) },
        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth())
}

@Composable
internal fun EncounterRow(row: Encounter, enabled: Boolean, onEdit: () -> Unit, onDelete: () -> Unit) {
    val v = row.values
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(displayTime(v.occurredAt, ZoneId.systemDefault()), style = MaterialTheme.typography.titleMedium)
        Text("Dauer: ${v.durationMinutes?.let { "$it Min." } ?: "Unbekannt"} · Bezahlt: ${v.totalPriceCents?.let { "${euro(it)} €" } ?: "Unbekannt"}")
        Text("Gesamt: ${v.overallRating?.let { "$it/5" } ?: "Unbekannt"} · Sympathie: ${v.sympathyRating?.let { "$it/5" } ?: "Unbekannt"}")
        v.basePriceCents?.let { Text("Grundpreis: ${euro(it)} €") }
        if (v.services.isNotEmpty()) {
            Text("Persönliche Leistungsdetails", style = MaterialTheme.typography.titleSmall)
            v.services.forEach { service ->
                Text("${service.name} · ${service.amountCents?.let { "${euro(it)} €" } ?: "Unbekannt"}")
            }
        }
        v.listingId?.let { Text("Anzeige #$it", style = MaterialTheme.typography.bodySmall) }
        v.note?.let { Text(it) }
        Row {
            TextButton(enabled = enabled, onClick = onEdit) { Text("Bearbeiten") }
            TextButton(enabled = enabled, onClick = onDelete) { Text("Löschen") }
        }
    }
}
