package com.selenite.scanner.link.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.selenite.scanner.link.MirorLinkAvailabilityUi
import com.selenite.scanner.link.MirorLinkContentOffer
import com.selenite.scanner.link.MirorLinkContentPhase
import com.selenite.scanner.link.MirorLinkPeerChoice
import com.selenite.scanner.link.MirorLinkPhase
import com.selenite.scanner.link.MirorLinkProtocol
import com.selenite.scanner.link.MirorLinkUiState
import com.selenite.scanner.ui.ShareDialogFrame
import com.selenite.scanner.ui.mirorShareOutlinedTextFieldColors

/**
 * The Miror Link overlay.
 *
 * Deliberately extracted from `CollectionScreen` rather than added to it: Link owns a
 * radio session with its own lifecycle, and folding transport callbacks into the
 * collection screen is what the plan set out to avoid.
 */
@Composable
internal fun MirorLinkDialog(
    state: MirorLinkUiState,
    storedNickname: String,
    onNicknameChosen: (String) -> Unit,
    onChoosePeer: (String) -> Unit,
    onAcceptContent: () -> Unit,
    onDeclineContent: () -> Unit,
    onRequestPermissions: () -> Unit,
    onUseSigilInstead: () -> Unit,
    onDismiss: () -> Unit,
) {
    ShareDialogFrame(
        title = "Miror Link",
        icon = { Icon(Icons.Filled.Cable, null, tint = Color.White.copy(alpha = 0.9f)) },
        onDismiss = onDismiss,
    ) {
        when {
            // Kept ahead of everything else as a safety net: if a chooser somehow appears
            // without a stored name, the user is asked before being made to pick between
            // anonymous entries.
            state.showChooser && storedNickname.isBlank() ->
                NicknameSetup(onNicknameChosen, becauseOfChooser = true)

            state.availability == MirorLinkAvailabilityUi.Unsupported ->
                UnavailableBody(
                    message = "This phone can't use Miror Link. You can still share a " +
                        "Sigil Code, which works everywhere.",
                    onUseSigilInstead = onUseSigilInstead,
                    onDismiss = onDismiss,
                )

            state.availability == MirorLinkAvailabilityUi.NeedsPermissions ->
                PermissionBody(onRequestPermissions, onUseSigilInstead)

            state.availability == MirorLinkAvailabilityUi.RadiosOff ->
                UnavailableBody(
                    message = "Turn on Bluetooth to link with a phone nearby, or share a " +
                        "Sigil Code instead.",
                    onUseSigilInstead = onUseSigilInstead,
                    onDismiss = onDismiss,
                )

            // Asked once, on the first Link, and only after the permission prompt has
            // been answered -- so the first thing a user sees is still the explanation of
            // why Miror wants those permissions, not a text field.
            //
            // Previously this was deferred until a chooser was actually needed. Asking up
            // front costs one prompt, once, and buys a real name in every later session:
            // the chooser is how someone picks their friend out of a crowded room, and a
            // chooser full of anonymous differentiators is exactly where a phone
            // impersonating a friend would be hardest to spot.
            storedNickname.isBlank() -> NicknameSetup(onNicknameChosen)

            state.phase == MirorLinkPhase.Failed ->
                FailureBody(
                    message = state.failure ?: "Miror Link couldn't finish.",
                    updateRequired = state.updateRequired,
                    onUseSigilInstead = onUseSigilInstead,
                    onDismiss = onDismiss,
                )

            state.showChooser -> PeerChooser(state.candidates, onChoosePeer)

            state.contentPhase == MirorLinkContentPhase.Offered && state.contentOffer != null ->
                ContentOfferBody(state.contentOffer!!, onAcceptContent, onDeclineContent)

            state.contentPhase == MirorLinkContentPhase.Receiving ||
                state.contentPhase == MirorLinkContentPhase.Applying ->
                ContentProgressBody(state)

            else -> ProgressBody(
                state = state,
                onRetry = onRequestPermissions,
                onUseSigilInstead = onUseSigilInstead,
                onDismiss = onDismiss,
            )
        }
    }
}

@Composable
private fun NicknameSetup(
    onNicknameChosen: (String) -> Unit,
    becauseOfChooser: Boolean = false,
) {
    var draft by remember { mutableStateOf("") }
    val sanitized = MirorLinkProtocol.sanitizeNickname(draft)
    val valid = sanitized.isNotBlank() && MirorLinkProtocol.isNicknameWithinBounds(sanitized)

    Text(
        if (becauseOfChooser) {
            "There's more than one phone nearby. Pick a name so your friend can tell " +
                "which one is yours. Only people you link with, while Link is open, can see it."
        } else {
            "Pick a name the other phone will see. Only people you link with, while Link " +
                "is open, can see it."
        },
        style = MaterialTheme.typography.bodyMedium,
        color = Color.White.copy(alpha = 0.75f),
    )
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = draft,
        onValueChange = { draft = it },
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("Your name") },
        colors = mirorShareOutlinedTextFieldColors(),
        shape = RoundedCornerShape(22.dp),
        singleLine = true,
        isError = draft.isNotBlank() && !valid,
    )
    Spacer(Modifier.height(12.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Button(
            onClick = { onNicknameChosen(sanitized) },
            enabled = valid,
            shape = RoundedCornerShape(50),
        ) {
            Text("Continue")
        }
    }
}

@Composable
private fun ProgressBody(
    state: MirorLinkUiState,
    onRetry: () -> Unit,
    onUseSigilInstead: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Concrete states rather than a single indeterminate spinner: there should be no
    // unexplained blank wait.
    val label = when (state.phase) {
        MirorLinkPhase.Idle, MirorLinkPhase.PermissionSetup -> "Getting ready"
        MirorLinkPhase.Discovering -> "Looking nearby"
        MirorLinkPhase.SelectingPeer -> "Found more than one phone"
        MirorLinkPhase.Connecting -> state.peerNickname?.let { "Found $it" } ?: "Connecting"
        MirorLinkPhase.ExchangingHello -> "Linking"
        MirorLinkPhase.ExchangingSnapshots -> "Swapping collections"
        MirorLinkPhase.Viewing -> "Collection received"
        // Reached when the peer linked successfully but had nothing to share.
        MirorLinkPhase.Complete -> state.peerNickname
            ?.let { "$it hasn't added any cards yet" }
            ?: "Nothing to show yet"
        MirorLinkPhase.Cancelled -> "Link ended"
        MirorLinkPhase.TimedOut -> "No one nearby"
        MirorLinkPhase.Failed -> "Link failed"
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        if (state.isActive && state.phase != MirorLinkPhase.Viewing) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
        }
        Column {
            Text(label, style = MaterialTheme.typography.titleSmall, color = Color.White)
            Text(
                text = when (state.phase) {
                    MirorLinkPhase.Complete -> "Your collection was shared with them."
                    MirorLinkPhase.Cancelled -> "Open Miror Link again to retry."
                    // The radios are already down at this point, so this is an
                    // invitation to retry rather than an error to recover from.
                    MirorLinkPhase.TimedOut ->
                        "Miror stopped looking. Make sure they've tapped Link too, " +
                            "then try again."
                    else -> "Hold the phones together."
                },
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.6f),
            )
        }
    }

    if (state.phase == MirorLinkPhase.ExchangingSnapshots) {
        Spacer(Modifier.height(12.dp))
        LinearProgressIndicator(
            progress = { (state.sendProgress + state.receiveProgress) / 2f },
            modifier = Modifier.fillMaxWidth(),
        )
    }

    Spacer(Modifier.height(16.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        if (state.phase == MirorLinkPhase.TimedOut) {
            OutlinedButton(
                onClick = onUseSigilInstead,
                shape = RoundedCornerShape(50),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = Color.White,
                    containerColor = Color.White.copy(alpha = 0.06f),
                ),
            ) {
                Text("Use a Sigil")
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = onRetry, shape = RoundedCornerShape(50)) {
                Text("Try again")
            }
        } else {
            OutlinedButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(50),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = Color.White,
                    containerColor = Color.White.copy(alpha = 0.06f),
                ),
            ) {
                Text(if (state.remote != null) "End Link" else "Cancel")
            }
        }
    }
}

@Composable
private fun PeerChooser(candidates: List<MirorLinkPeerChoice>, onChoosePeer: (String) -> Unit) {
    Text(
        "More than one phone is nearby. Which one?",
        style = MaterialTheme.typography.titleSmall,
        color = Color.White,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        "Miror couldn't tell which phone is closest, so it's asking instead of guessing.",
        style = MaterialTheme.typography.bodySmall,
        color = Color.White.copy(alpha = 0.6f),
    )
    Spacer(Modifier.height(12.dp))
    candidates.forEach { candidate ->
        OutlinedButton(
            onClick = { onChoosePeer(candidate.key) },
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            shape = RoundedCornerShape(22.dp),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = Color.White,
                containerColor = Color.White.copy(alpha = 0.06f),
            ),
        ) {
            Text(
                text = candidate.differentiator
                    ?.let { "${candidate.nickname}  ·  $it" }
                    ?: candidate.nickname,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ContentOfferBody(
    offer: MirorLinkContentOffer,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    // The size is genuinely unknown for a donor serving content bundled in its APK, so
    // the copy says so rather than guessing or blocking on measuring it.
    val mebibyte = 1024L * 1024L
    val size = offer.totalSizeBytes
        // Round up: the consent prompt must never describe a transfer as smaller than
        // the bytes the user is agreeing to receive.
        ?.let { ", ${((it + mebibyte - 1) / mebibyte).coerceAtLeast(1)} MB" }
        ?: ", size unavailable"
    Text(
        "${offer.peerNickname}'s Miror has newer card data (v${offer.offeredVersion}$size). " +
            "Receive this update directly from their phone?",
        style = MaterialTheme.typography.bodyMedium,
        color = Color.White,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        "Your collection will not be replaced. You have v${offer.localVersion}.",
        style = MaterialTheme.typography.bodySmall,
        color = Color.White.copy(alpha = 0.6f),
    )
    Spacer(Modifier.height(16.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        OutlinedButton(
            onClick = onDecline,
            shape = RoundedCornerShape(50),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = Color.White,
                containerColor = Color.White.copy(alpha = 0.06f),
            ),
        ) {
            Text("Not now")
        }
        Spacer(Modifier.width(8.dp))
        Button(onClick = onAccept, shape = RoundedCornerShape(50)) {
            Text("Receive update")
        }
    }
}

@Composable
private fun ContentProgressBody(state: MirorLinkUiState) {
    val label = if (state.contentPhase == MirorLinkContentPhase.Applying) {
        "Installing card data"
    } else {
        "Receiving card data"
    }
    Text(label, style = MaterialTheme.typography.titleSmall, color = Color.White)
    Spacer(Modifier.height(12.dp))
    if (state.contentPhase == MirorLinkContentPhase.Applying) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    } else {
        LinearProgressIndicator(
            progress = { state.contentProgress },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Spacer(Modifier.height(8.dp))
    Text(
        "Keep both phones on this screen.",
        style = MaterialTheme.typography.bodySmall,
        color = Color.White.copy(alpha = 0.6f),
    )
}

@Composable
private fun PermissionBody(onRequestPermissions: () -> Unit, onUseSigilInstead: () -> Unit) {
    Text(
        "Miror Link needs permission to see phones right next to yours. It's used only " +
            "while this screen is open.",
        style = MaterialTheme.typography.bodyMedium,
        color = Color.White.copy(alpha = 0.8f),
    )
    Spacer(Modifier.height(16.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        OutlinedButton(
            onClick = onUseSigilInstead,
            shape = RoundedCornerShape(50),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = Color.White,
                containerColor = Color.White.copy(alpha = 0.06f),
            ),
        ) {
            Text("Use a Sigil")
        }
        Spacer(Modifier.width(8.dp))
        Button(onClick = onRequestPermissions, shape = RoundedCornerShape(50)) {
            Text("Continue")
        }
    }
}

@Composable
private fun UnavailableBody(
    message: String,
    onUseSigilInstead: () -> Unit,
    onDismiss: () -> Unit,
) {
    Text(message, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.8f))
    Spacer(Modifier.height(16.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        OutlinedButton(
            onClick = onDismiss,
            shape = RoundedCornerShape(50),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = Color.White,
                containerColor = Color.White.copy(alpha = 0.06f),
            ),
        ) {
            Text("Close")
        }
        Spacer(Modifier.width(8.dp))
        Button(onClick = onUseSigilInstead, shape = RoundedCornerShape(50)) {
            Text("Share a Sigil")
        }
    }
}

@Composable
private fun FailureBody(
    message: String,
    updateRequired: Boolean,
    onUseSigilInstead: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = if (updateRequired) "Update Miror to link with this version." else message,
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    Spacer(Modifier.height(16.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        OutlinedButton(
            onClick = onDismiss,
            shape = RoundedCornerShape(50),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = Color.White,
                containerColor = Color.White.copy(alpha = 0.06f),
            ),
        ) {
            Text("Close")
        }
        Spacer(Modifier.width(8.dp))
        Button(onClick = onUseSigilInstead, shape = RoundedCornerShape(50)) {
            Text("Share a Sigil")
        }
    }
}

/** Compact Link-or-Sigil choice shown from multi-select. */
@Composable
internal fun ShareMethodChooserDialog(
    selectedCount: Int,
    linkAvailable: Boolean,
    onDismiss: () -> Unit,
    onMirorLink: () -> Unit,
    onSigil: () -> Unit,
) {
    ShareDialogFrame(
        title = "Share $selectedCount ${if (selectedCount == 1) "card" else "cards"}",
        icon = { Icon(Icons.Filled.Cable, null, tint = Color.White.copy(alpha = 0.9f)) },
        onDismiss = onDismiss,
    ) {
        Text(
            "Both ways share exactly the cards you picked.",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = onMirorLink,
                enabled = linkAvailable,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(50),
            ) {
                Text("Miror Link", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            OutlinedButton(
                onClick = onSigil,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(50),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = Color.White,
                    containerColor = Color.White.copy(alpha = 0.06f),
                ),
            ) {
                Text("Sigil Code", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
