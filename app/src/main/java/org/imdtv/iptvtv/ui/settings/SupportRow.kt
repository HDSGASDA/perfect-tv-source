package org.imdtv.iptvtv.ui.settings

import androidx.compose.ui.res.stringResource
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.imdtv.iptvtv.data.support.LogCollector
import org.imdtv.iptvtv.data.support.LogServer
import org.imdtv.iptvtv.data.support.lanAddress
import org.imdtv.iptvtv.data.support.scrubLog
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import org.imdtv.iptvtv.IptvApp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import org.imdtv.iptvtv.BuildConfig
import org.imdtv.iptvtv.R
import org.imdtv.iptvtv.ui.MenuFrame
import org.imdtv.iptvtv.ui.MenuItem

const val CONTACT_EMAIL = "admin@perfectiptv.co.uk"

/** Settings › About: version, licence, contact, and support diagnostics. */
@Composable
fun AboutRows(first: Modifier) {
    VersionRow(first)
    SettingRow(stringResource(R.string.set_b_licence), stringResource(R.string.set_b_licence_expl), "GPL-3.0") {}
    SettingRow(stringResource(R.string.set_b_contact), stringResource(R.string.set_b_contact_expl), CONTACT_EMAIL) {}
    SendLogsRow()
}

/** Version: OK opens the update prompt when there is one, else checks GitHub now. */
@Composable
private fun VersionRow(first: Modifier) {
    val updater = (LocalContext.current.applicationContext as IptvApp).container.updater
    val update by updater.available.collectAsState()
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val u = update
    val msgCheckFailed = stringResource(R.string.set_b_check_failed)
    val msgLatest = stringResource(R.string.set_b_latest)
    SettingRow(stringResource(R.string.set_b_version), result ?: if (u != null) stringResource(R.string.set_b_version_available, u.version) else stringResource(R.string.set_b_version_check),
        BuildConfig.VERSION_NAME, first, busy = checking) {
        if (u != null) updater.prompt.value = true
        else if (!checking) scope.launch {
            checking = true
            val ok = updater.check(manual = true)
            checking = false
            result = if (!ok) msgCheckFailed else if (updater.available.value == null) msgLatest else null
        }
    }
}

private const val LOG_PAGE_MS = 10 * 60 * 1000L

/** Send logs: OK builds the scrubbed log, serves it on the local network for 10 minutes and shows a QR code for it. Nothing runs otherwise. */
@Composable
private fun SendLogsRow() {
    var open by remember { mutableStateOf(false) }
    SettingRow(stringResource(R.string.set_b_send_logs), stringResource(R.string.set_b_send_logs_expl), null) { open = true }
    if (open) SendLogsPage { open = false }
}

/** The Send logs page; [extraSecrets] are scrubbed too (e.g. a login typed into an add that failed, so never saved). */
@Composable
fun SendLogsPage(extraSecrets: List<String> = emptyList(), onClose: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as IptvApp
    var server by remember { mutableStateOf<LogServer?>(null) }
    var qr by remember { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val s = withContext(Dispatchers.IO) {
            runCatching {
                val addr = lanAddress() ?: return@runCatching null
                val c = app.container
                val providers = c.db.providerDao().all()
                val (prev, cur) = LogCollector.read(ctx)
                val header = "Perfect TV ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}\n" +
                    "Theme: ${c.settings.settings.first().theme}\nPlaylists: ${providers.size} ${providers.map { it.type }}\n\n"
                // Stalker passwords hold "serial\ndeviceId": each line is a secret.
                val secrets = (providers.flatMap { listOfNotNull(it.username, it.password) } + extraSecrets).flatMap { it.lines() }
                LogServer(addr, (header + scrubLog(prev + cur, secrets)).toByteArray()).also {
                    server = it // set at once so closing the page mid-build still stops it
                    val m = QRCodeWriter().encode(it.url, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0))
                    qr = Bitmap.createBitmap(IntArray(m.width * m.height) { i -> if (m.get(i % m.width, i / m.width)) 0xFF000000.toInt() else -1 }, m.width, m.height, Bitmap.Config.ARGB_8888).asImageBitmap()
                }
            }.getOrNull()
        }
        server = s
        failed = s == null
        delay(LOG_PAGE_MS)
        onClose()
    }
    DisposableEffect(Unit) { onDispose { server?.stop() } }
    MenuFrame(stringResource(R.string.set_b_send_logs), onDismiss = onClose) { first ->
        val img = qr
        if (img != null) Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
            Image(img, contentDescription = stringResource(R.string.set_b_qr_log), filterQuality = FilterQuality.None,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color.White).padding(12.dp).size(200.dp))
        }
        Text(when {
            img != null -> stringResource(R.string.set_b_log_ready, server?.url ?: "", CONTACT_EMAIL)
            failed -> stringResource(R.string.set_b_log_no_net)
            else -> stringResource(R.string.set_b_log_preparing)
        }, fontSize = 15.sp, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
        MenuItem(stringResource(R.string.set_b_close), Modifier.focusRequester(first)) { onClose() }
    }
}
