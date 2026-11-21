package org.imdtv.iptvtv.data.management

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import org.imdtv.iptvtv.R
import org.imdtv.iptvtv.data.update.ScrollText
import org.imdtv.iptvtv.data.update.Updater
import org.imdtv.iptvtv.ui.MenuFrame
import org.imdtv.iptvtv.ui.MenuItem

@Composable
fun AnnouncementPrompt(repository: AnnouncementRepository, updater: Updater) {
    val messages by repository.pending.collectAsState()
    val updating by updater.prompt.collectAsState()
    val notes by updater.whatsNew.collectAsState()
    val message = messages.firstOrNull() ?: return
    // Avoid two modal windows fighting for D-pad focus.
    if (updating || notes != null) return
    val scope = rememberCoroutineScope()
    val dismiss = { scope.launch { repository.dismiss(message.id) }; Unit }
    MenuFrame(
        stringResource(R.string.managed_message_sender),
        onDismiss = dismiss,
        width = 560.dp,
    ) { first ->
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                message.title,
                fontSize = 24.sp,
                lineHeight = 29.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.fillMaxWidth(),
            )
            Box(
                Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp))
                    .background(Color(0xFF59C7F2)),
            )
            ScrollText(message.body, Modifier.focusRequester(first))
            MenuItem(stringResource(R.string.managed_message_dismiss), Modifier.padding(top = 2.dp)) { dismiss() }
        }
    }
}
