package org.imdtv.iptvtv.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import org.imdtv.iptvtv.R
import org.imdtv.iptvtv.data.security.PinGate
import org.imdtv.iptvtv.ui.Field
import org.imdtv.iptvtv.ui.MenuFrame
import org.imdtv.iptvtv.ui.MenuItem

/** The parental PIN dialog, over any screen: a locked category or Settings asked for it. */
@Composable
fun PinPrompt(gate: PinGate) {
    val request by gate.prompt.collectAsState()
    val wrong by gate.wrong.collectAsState()
    val r = request ?: return
    var pin by remember(r) { mutableStateOf("") }
    var forgot by remember(r) { mutableStateOf(false) }
    // Four digits entered: check at once (the OK button is for a keyboard-less retry).
    LaunchedEffect(pin) { if (pin.length == 4) gate.submit(pin) }
    MenuFrame(
        r.label?.let { stringResource(R.string.set_b_pin_enter_for, it) } ?: stringResource(R.string.set_b_pin_enter),
        onDismiss = gate::cancel,
    ) { first ->
        if (forgot) {
            // No self-service reset: the admin unlocks with the override PIN, then changes or removes it in Settings.
            Text(stringResource(R.string.set_b_pin_forgot_expl), fontSize = 14.sp, color = Color.White, modifier = Modifier.padding(vertical = 6.dp))
            MenuItem(stringResource(R.string.live_cancel), Modifier.focusRequester(first), onClick = gate::cancel)
            return@MenuFrame
        }
        Field("PIN", pin, KeyboardType.NumberPassword, secret = true, modifier = Modifier.focusRequester(first)) { v ->
            if (v.length <= 4 && v.all { it.isDigit() }) pin = v
        }
        if (wrong) Text(stringResource(R.string.set_b_pin_wrong), fontSize = 13.sp, color = Color(0xFFFF8A80), modifier = Modifier.padding(vertical = 4.dp))
        MenuItem(stringResource(android.R.string.ok), Modifier) { if (!gate.submit(pin)) pin = "" }
        MenuItem(stringResource(R.string.set_b_pin_forgot), Modifier) { forgot = true }
        MenuItem(stringResource(R.string.live_cancel), Modifier, onClick = gate::cancel)
    }
}