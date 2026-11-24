package de.justify87.ladiesexplorer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import java.time.ZoneId

internal data class ProfileRecordPanel<S>(
    val token: ProfileIdentityToken,
    val snapshot: S? = null,
    val error: String? = null,
)

internal data class ProfileRecordEdit<R, D>(
    val id: Long,
    val token: ProfileIdentityToken,
    val expected: R?,
    val draft: D,
    val zone: ZoneId,
    val deleting: Boolean = false,
    val writing: Boolean = false,
    val discardRequested: Boolean = false,
    val error: String? = null,
)

/** Presentation state only; Rust remains the authority for every write. */
internal open class ProfileRecordEditor<R, V, D, S>(
    private val current: (ProfileIdentityToken) -> Boolean,
    private val read: suspend (Long, Long) -> S,
    private val rows: (S) -> List<R>,
    private val newDraft: (R?, ZoneId) -> D,
    private val values: (D, R?, ZoneId) -> V,
    private val readLabel: String,
    private val dateError: String,
    private val canBegin: () -> Boolean,
    private val submit: (ProfileRecordEdit<R, D>, V?) -> Unit,
) {
    var panel by mutableStateOf<ProfileRecordPanel<S>?>(null, referentialEqualityPolicy())
        private set
    var edit by mutableStateOf<ProfileRecordEdit<R, D>?>(null, referentialEqualityPolicy())
        private set
    var readRevision by mutableLongStateOf(0L)
        private set
    private var nextId = 0L
    private var committedOwner: Long? = null

    suspend fun load(token: ProfileIdentityToken) {
        if (!current(token)) return
        val pending = ProfileRecordPanel<S>(token)
        panel = pending
        try {
            val snapshot = read(token.listingId, token.ownerId)
            if (panel === pending && current(token)) {
                panel = pending.copy(snapshot = snapshot)
                if (committedOwner == token.ownerId) committedOwner = null
            }
        } catch (cancel: CancellationException) {
            if (panel === pending) panel = null
            throw cancel
        } catch (_: Exception) {
            if (panel === pending && current(token)) {
                panel = pending.copy(error = if (committedOwner == token.ownerId)
                    "Änderung gespeichert; $readLabel konnten nicht neu geladen werden."
                    else "$readLabel konnten nicht geladen werden.")
            }
        }
    }

    fun detach(token: ProfileIdentityToken?) {
        if (panel?.token == token) panel = null
    }

    suspend fun retry(rendered: ProfileRecordPanel<S>) {
        if (panel === rendered && rendered.error != null) load(rendered.token)
    }

    fun begin(rendered: ProfileRecordPanel<S>, row: R? = null, deleting: Boolean = false) {
        if (edit != null || !canBegin() || panel !== rendered || rendered.snapshot == null || !current(rendered.token) ||
            (row != null && row !in rows(rendered.snapshot)) || (deleting && row == null)) return
        val zone = ZoneId.systemDefault()
        edit = ProfileRecordEdit<R, D>(++nextId, rendered.token, row, newDraft(row, zone), zone, deleting)
    }

    fun canEdit(rendered: ProfileRecordEdit<R, D>): Boolean =
        edit?.id == rendered.id && edit?.token == rendered.token && edit?.writing == false &&
            panel?.token == rendered.token && current(rendered.token)

    fun change(rendered: ProfileRecordEdit<R, D>, transform: (D) -> D) {
        val active = edit ?: return
        if (!canEdit(rendered) || active.deleting || active.discardRequested) return
        edit = active.copy(draft = transform(active.draft), error = null)
    }

    // A resumed profile must explicitly reauthorize a retained draft; never replace its CAS snapshot.
    fun matchesContext(token: ProfileIdentityToken?): Boolean {
        val original = edit?.token ?: return false
        return token != null && token::class == original::class &&
            token.listingId == original.listingId && token.ownerId == original.ownerId
    }

    fun canResume(rendered: ProfileRecordEdit<R, D>): Boolean {
        val token = panel?.token ?: return false
        return edit === rendered && !rendered.writing && !rendered.discardRequested && !current(rendered.token) &&
            token != rendered.token && matchesContext(token) &&
            current(token)
    }

    fun resume(rendered: ProfileRecordEdit<R, D>) {
        if (canResume(rendered)) edit = rendered.copy(token = checkNotNull(panel).token)
    }

    fun requestDismiss(rendered: ProfileRecordEdit<R, D>) {
        if (edit !== rendered || rendered.writing) return
        if (rendered.deleting) edit = null
        else edit = rendered.copy(discardRequested = true)
    }

    fun keep(rendered: ProfileRecordEdit<R, D>) {
        if (edit === rendered && rendered.discardRequested) edit = rendered.copy(discardRequested = false)
    }

    fun discard(rendered: ProfileRecordEdit<R, D>) {
        if (edit === rendered && rendered.discardRequested && !rendered.writing) edit = null
    }

    fun save(rendered: ProfileRecordEdit<R, D>) {
        if (edit !== rendered || !canEdit(rendered) || rendered.discardRequested) return
        val values = try {
            if (rendered.deleting) null else values(rendered.draft, rendered.expected, rendered.zone)
        } catch (_: java.time.DateTimeException) {
            edit = rendered.copy(error = dateError)
            return
        } catch (failure: Exception) {
            edit = rendered.copy(error = failure.message ?: "Ungültige Eingabe")
            return
        }
        val writing = rendered.copy(writing = true, error = null)
        edit = writing
        submit(writing, values)
    }

    fun committed(writing: ProfileRecordEdit<R, D>) {
        if (edit !== writing) return
        invalidateAfterCommit(writing.token.ownerId)
        edit = null
    }

    fun invalidateAfterCommit(ownerId: Long) {
        committedOwner = ownerId
        if (panel?.token?.ownerId == ownerId) panel = null
        readRevision++
    }

    fun failed(writing: ProfileRecordEdit<R, D>, message: String) {
        if (edit === writing) edit = writing.copy(writing = false, error = message)
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

internal fun euro(cents: Long): String =
    java.math.BigDecimal.valueOf(cents, 2).toPlainString().replace('.', ',')

@Composable
internal fun <R, V, D, S> ProfileRecordDialog(
    editor: ProfileRecordEditor<R, V, D, S>,
    newTitle: String,
    editTitle: String,
    deleteTitle: String,
    deleteMessage: String,
    fields: @Composable (ProfileRecordEdit<R, D>, Boolean) -> Unit,
) {
    val edit = editor.edit ?: return
    val enabled = editor.canEdit(edit)
    if (edit.discardRequested) {
        AlertDialog(onDismissRequest = { editor.keep(edit) }, title = { Text("Entwurf verwerfen?") },
            text = { Text("Ungespeicherte Änderungen gehen verloren.") },
            confirmButton = { TextButton(onClick = { editor.discard(edit) }) { Text("Verwerfen") } },
            dismissButton = { TextButton(onClick = { editor.keep(edit) }) { Text("Weiter bearbeiten") } })
        return
    }
    val scroll = rememberScrollState()
    val contextLost = !enabled && !edit.writing
    LaunchedEffect(edit.id, edit.error, contextLost) {
        if (edit.error != null || contextLost) scroll.scrollTo(0)
    }
    AlertDialog(
        onDismissRequest = { editor.requestDismiss(edit) },
        title = { Text(if (edit.deleting) deleteTitle else if (edit.expected == null) newTitle else editTitle) },
        text = {
            Column(Modifier.verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                edit.error?.let {
                    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small) {
                        Text(it, Modifier.fillMaxWidth().padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
                if (edit.writing) LinearProgressIndicator(Modifier.fillMaxWidth())
                else if (!enabled) {
                    Text("Der ursprüngliche Profilkontext ist nicht mehr aktiv. Der Entwurf bleibt erhalten.")
                    if (editor.canResume(edit)) TextButton(onClick = { editor.resume(edit) },
                        modifier = Modifier.heightIn(min = 48.dp)) { Text("Im aktuellen Profil fortsetzen") }
                }
                if (edit.deleting) Text(deleteMessage) else fields(edit, enabled)
            }
        },
        confirmButton = { Button(enabled = enabled, onClick = { editor.save(edit) }, modifier = Modifier.heightIn(min = 48.dp),
            colors = if (edit.deleting) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError) else ButtonDefaults.buttonColors()) {
            Text(if (edit.deleting) "Löschen" else "Speichern")
        } },
        dismissButton = { TextButton(enabled = !edit.writing, modifier = Modifier.heightIn(min = 48.dp), onClick = { editor.requestDismiss(edit) }) { Text("Abbrechen") } },
    )
}

@Composable
internal fun ProfileEditorField(
    value: String, label: String, enabled: Boolean,
    keyboard: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit,
) {
    OutlinedTextField(value, onChange, enabled = enabled, label = { Text(label) },
        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small)
}

@Composable
internal fun ProfileListingAssociation(
    listingId: Long?,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(listingId != null, onChange, enabled = enabled)
        Text(if (listingId == null) "Anzeige zuordnen" else "Anzeige #$listingId zugeordnet")
    }
}

@Composable
internal fun ProfileRecordCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
    }
}

@Composable
internal fun ProfileRecordActions(editLabel: String, enabled: Boolean, onEdit: () -> Unit, onDelete: () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(enabled = enabled, onClick = onEdit, modifier = Modifier.heightIn(min = 48.dp)) { Text(editLabel) }
        TextButton(enabled = enabled, onClick = onDelete, modifier = Modifier.heightIn(min = 48.dp),
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Löschen") }
    }
}

@Composable
internal fun ProfileEditorGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}
