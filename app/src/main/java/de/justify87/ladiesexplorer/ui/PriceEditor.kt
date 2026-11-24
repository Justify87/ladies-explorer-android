package de.justify87.ladiesexplorer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.justify87.ladiesexplorer.core.*
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

private val priceDate = DateTimeFormatter.ofPattern("dd.MM.uuuu")
    .withResolverStyle(ResolverStyle.STRICT)

private fun displayPriceDate(timestamp: String): String =
    LocalDate.parse(timestamp.substringBefore('T')).format(priceDate)

internal data class PriceItemDraft(
    val id: Long,
    val label: String = "",
    val duration: String = "",
    val amount: String = "",
)

internal data class PriceListDraft(
    val dateText: String,
    val listingId: Long? = null,
    val items: List<PriceItemDraft> = listOf(PriceItemDraft(0)),
    val nextItemId: Long = 1,
) {
    fun addItem() = copy(items = items + PriceItemDraft(nextItemId), nextItemId = nextItemId + 1)

    fun changeItem(id: Long, transform: (PriceItemDraft) -> PriceItemDraft) =
        copy(items = items.map { if (it.id == id) transform(it) else it })

    fun removeItem(id: Long) = copy(items = items.filterNot { it.id == id })

    fun values(expected: PriceList?): PriceListValues {
        val original = expected?.values?.observedAt
        val date = LocalDate.parse(dateText.trim(), priceDate)
        require(date.year in 1..9999) { "Datum liegt außerhalb des gültigen Bereichs." }
        val timestamp = if (original != null && dateText.trim() == displayPriceDate(original)) original
        else "${date}T00:00:00.000Z"
        require(items.isNotEmpty()) { "Mindestens eine Preisposition ist erforderlich." }
        val positions = items.map { item ->
            val duration = if (item.duration.isBlank()) null else
                item.duration.trim().toLongOrNull()?.takeIf { it > 0 }
                    ?: throw IllegalArgumentException("Dauer muss positiv sein.")
            val amount = requireNotNull(optionalCents(item.amount)) { "Jede Position benötigt einen EUR-Betrag." }
            PriceItem(item.label.trim().takeIf { it.isNotEmpty() }, duration, amount)
        }
        return PriceListValues(listingId, timestamp, positions)
    }

    companion object {
        fun from(row: PriceList?, zone: ZoneId, today: LocalDate = LocalDate.now(zone)): PriceListDraft {
            val values = row?.values ?: return PriceListDraft(today.format(priceDate))
            return PriceListDraft(
                dateText = displayPriceDate(values.observedAt),
                listingId = values.listingId,
                items = values.items.mapIndexed { index, item ->
                    PriceItemDraft(index.toLong(), item.label.orEmpty(), item.durationMinutes?.toString().orEmpty(),
                        euro(item.amountCents))
                },
                nextItemId = values.items.size.toLong(),
            )
        }
    }
}

internal typealias PricePanel = ProfileRecordPanel<PriceOverview>
internal typealias PriceEdit = ProfileRecordEdit<PriceList, PriceListDraft>

internal class PriceEditor(
    current: (ProfileIdentityToken) -> Boolean,
    read: suspend (Long, Long) -> PriceOverview,
    submit: (PriceEdit, PriceListValues?) -> Unit,
    canBegin: () -> Boolean = { true },
) : ProfileRecordEditor<PriceList, PriceListValues, PriceListDraft, PriceOverview>(
    current, read, { it.lists }, { row, zone -> PriceListDraft.from(row, zone) },
    { draft, row, _ -> draft.values(row) }, "Preislisten",
    "Datum bitte als TT.MM.JJJJ eingeben.", canBegin, submit,
)

@Composable
internal fun PriceEditorDialog(editor: PriceEditor) {
    ProfileRecordDialog(editor, "Neue Preisliste", "Preisliste korrigieren", "Preisliste löschen?",
        "Die gespeicherte Preisliste wird dauerhaft gelöscht.") { edit, enabled ->
        val d = edit.draft
        fun change(transform: (PriceListDraft) -> PriceListDraft) = editor.change(edit, transform)
        ProfileEditorField(d.dateText, "Datum (TT.MM.JJJJ)", enabled) { value -> change { it.copy(dateText = value) } }
        d.items.forEachIndexed { index, item ->
            key(item.id) {
                Text("Position ${index + 1}", style = MaterialTheme.typography.titleSmall)
                ProfileEditorField(item.label, "Bezeichnung (optional)", enabled) { value ->
                    change { it.changeItem(item.id) { line -> line.copy(label = value) } }
                }
                ProfileEditorField(item.duration, "Dauer in Minuten (optional)", enabled, KeyboardType.Number) { value ->
                    change { it.changeItem(item.id) { line -> line.copy(duration = value) } }
                }
                ProfileEditorField(item.amount, "Betrag in EUR", enabled, KeyboardType.Decimal) { value ->
                    change { it.changeItem(item.id) { line -> line.copy(amount = value) } }
                }
                TextButton(enabled = enabled, onClick = { change { it.removeItem(item.id) } }) {
                    Text("Position ${index + 1} entfernen")
                }
            }
        }
        TextButton(enabled = enabled, onClick = { change { it.addItem() } }) { Text("Preisposition hinzufügen") }
        ProfileListingAssociation(d.listingId, enabled) { checked ->
            change { it.copy(listingId = if (checked) edit.expected?.values?.listingId ?: edit.token.listingId else null) }
        }
    }
}

@Composable
internal fun PriceListRow(row: PriceList, latest: Boolean, enabled: Boolean, onEdit: () -> Unit, onDelete: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(if (latest) "Neueste erfasste Liste" else "Frühere Liste", style = MaterialTheme.typography.labelMedium)
        Text(displayPriceDate(row.values.observedAt), style = MaterialTheme.typography.titleMedium)
        row.values.items.forEach { item ->
            Text(listOfNotNull(item.label, item.durationMinutes?.let { "$it Min." }, "${euro(item.amountCents)} €").joinToString(" · "))
        }
        row.values.listingId?.let { Text("Anzeige #$it", style = MaterialTheme.typography.bodySmall) }
        Row {
            TextButton(enabled = enabled, onClick = onEdit) { Text("Korrigieren") }
            TextButton(enabled = enabled, onClick = onDelete) { Text("Löschen") }
        }
    }
}
