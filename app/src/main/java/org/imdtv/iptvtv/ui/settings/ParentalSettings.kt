package org.imdtv.iptvtv.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import org.imdtv.iptvtv.R
import org.imdtv.iptvtv.data.model.Kind
import org.imdtv.iptvtv.ui.Field
import org.imdtv.iptvtv.ui.MenuFrame
import org.imdtv.iptvtv.ui.MenuItem

/** Playback › Parental controls: the PIN, Lock Settings, and which categories ask for it. */
@Composable
fun ParentalPage(vm: SettingsViewModel, first: FocusRequester) {
    val s by vm.settings.collectAsState()
    val cats by vm.lockableCategories.collectAsState()
    var editing by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    val saved = stringResource(R.string.set_b_pin_saved)
    val removed = stringResource(R.string.set_b_pin_removed)
    // Scrolls: a big playlist has hundreds of categories under the PIN rows.
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (s.parentalPin.isEmpty()) {
            SettingRow(stringResource(R.string.set_b_parental_pin_new), stringResource(R.string.set_b_parental_pin_expl), null,
                Modifier.focusRequester(first)) { editing = true }
        } else {
            SettingRow(stringResource(R.string.set_b_parental_pin_set), stringResource(R.string.set_b_parental_pin_expl),
                stringResource(R.string.set_b_pin_set_value), Modifier.focusRequester(first)) { editing = true }
            SettingRow(stringResource(R.string.set_b_parental_pin_remove),
                stringResource(if (confirmRemove) R.string.set_b_pin_remove_confirm else R.string.set_b_parental_pin_remove_expl), null) {
                if (confirmRemove) { vm.setParentalPin(""); confirmRemove = false; vm.notice(removed) } else confirmRemove = true
            }
            SwitchRow(stringResource(R.string.set_b_parental_lock_settings), stringResource(R.string.set_b_parental_lock_settings_expl), s.lockSettings) { on ->
                vm.update { it.copy(lockSettings = on) }
            }
            Text(stringResource(R.string.set_b_parental_categories), fontSize = 16.sp, color = Color.White,
                modifier = Modifier.padding(top = 12.dp))
            Text(stringResource(R.string.set_b_parental_categories_expl), fontSize = 12.sp, color = Color(0xFFB9C2D8),
                modifier = Modifier.padding(bottom = 6.dp))
            cats.forEach { cat ->
                SwitchRow(cat.name, stringResource(when (cat.kind) {
                    Kind.LIVE -> R.string.set_b_live_tv; Kind.MOVIE -> R.string.set_a_page_movie_categories; Kind.SERIES -> R.string.set_a_page_series_categories
                }), cat.id in s.lockedCategories) { on -> vm.setCategoryLocked(cat.id, on) }
            }
        }
    }
    if (editing) MenuFrame(stringResource(if (s.parentalPin.isEmpty()) R.string.set_b_parental_pin_new else R.string.set_b_parental_pin_set),
        onDismiss = { editing = false }) { f ->
        var pin by remember { mutableStateOf("") }
        var confirm by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        val digits = stringResource(R.string.set_b_pin_digits)
        val mismatch = stringResource(R.string.set_b_pin_mismatch)
        Field(stringResource(R.string.set_b_pin_new_hint), pin, KeyboardType.NumberPassword, secret = true, modifier = Modifier.focusRequester(f)) { v ->
            if (v.length <= 4 && v.all { it.isDigit() }) { pin = v; error = null }
        }
        Field(stringResource(R.string.set_b_pin_confirm_hint), confirm, KeyboardType.NumberPassword, secret = true) { v ->
            if (v.length <= 4 && v.all { it.isDigit() }) { confirm = v; error = null }
        }
        error?.let { Text(it, fontSize = 13.sp, color = Color(0xFFFF8A80), modifier = Modifier.padding(vertical = 4.dp)) }
        MenuItem(stringResource(R.string.vod_ok), Modifier) {
            when {
                pin.length != 4 -> error = digits
                pin != confirm -> error = mismatch
                else -> { vm.setParentalPin(pin); editing = false; vm.notice(saved) }
            }
        }
        MenuItem(stringResource(R.string.live_cancel), Modifier) { editing = false }
    }
}