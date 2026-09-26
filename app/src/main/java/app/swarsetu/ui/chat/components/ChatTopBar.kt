package app.swarsetu.ui.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.swarsetu.ui.components.Avatar
import app.swarsetu.ui.components.GroupAvatar
import app.swarsetu.ui.components.RoomAvatar
import app.swarsetu.ui.i18n.UiTextKey
import app.swarsetu.ui.i18n.uiText
import app.swarsetu.ui.theme.BrandTertiaryDark
import app.swarsetu.ui.theme.BrandTertiaryLight

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatTopBar(
    title: String,
    statusSubtitle: String,
    isOnline: Boolean,
    isSecure: Boolean,
    isRoom: Boolean,
    isGroup: Boolean,
    avatarHash: String?,
    nodeId: String?,
    onBack: () -> Unit,
    onAvatarClick: () -> Unit,
    onClearChat: () -> Unit,
    onViewDetails: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f
    val onlineColor = if (isDark) BrandTertiaryDark else BrandTertiaryLight

    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        shadowElevation = 1.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        TopAppBar(
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .clickable { onAvatarClick() }
                        .padding(vertical = 4.dp),
                ) {
                    // Avatar with status indicator dot
                    Box(modifier = Modifier.size(40.dp)) {
                        when {
                            isRoom -> RoomAvatar(size = 40.dp)
                            isGroup -> GroupAvatar(photoHash = avatarHash, size = 40.dp)
                            else -> Avatar(avatarHash = avatarHash, name = title, size = 40.dp)
                        }

                        if (!isRoom) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(if (isOnline) onlineColor else Color.Gray.copy(alpha = 0.5f))
                                    .align(Alignment.BottomEnd),
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 16.sp,
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface,
                        )

                        Text(
                            text = statusSubtitle,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 12.sp,
                                color = if (isOnline) onlineColor else MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = uiText(UiTextKey.CD_BACK),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            },
            actions = {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = uiText(UiTextKey.CD_MORE_OPTIONS),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }

                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(uiText(UiTextKey.CHAT_VIEW_PROFILE)) },
                        onClick = {
                            menuExpanded = false
                            onViewDetails()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(uiText(UiTextKey.ACTION_CLEAR)) },
                        onClick = {
                            menuExpanded = false
                            onClearChat()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(uiText(UiTextKey.DIAGNOSTICS_TITLE)) },
                        onClick = {
                            menuExpanded = false
                            onOpenDiagnostics()
                        },
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
            ),
        )
    }
}
