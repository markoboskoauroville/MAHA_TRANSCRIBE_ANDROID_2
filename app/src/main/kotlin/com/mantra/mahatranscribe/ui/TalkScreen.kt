package com.mantra.mahatranscribe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import com.mantra.mahatranscribe.audio.Recorder

/**
 * T — TALK, AND IT IS WHERE YOU LAND.
 *
 * This screen is the model for the other five. Everything about how a player looks, how a choice
 * is made, where the actions for a box live and what a failure looks like is decided here and
 * inherited there.
 *
 * ### The order down the screen, and why it is this order
 *
 * The tab row, then the two choices, then the transport, then the meter, then the status line,
 * then the actions, then the box. Reading down, that is: where am I, what am I about to do, do
 * it, is it hearing me, what is happening, what can I do to the result, the result.
 *
 * The transport sits ABOVE the box rather than below it because a thumb reaches the middle of a
 * phone more easily than the bottom of a long page, and because the box grows and the transport
 * must not move when it does. design-language.md 1: reading does not rearrange the screen.
 *
 * ### Everything is present from the first frame
 *
 * The player, the meter, the actions and the box are all drawn before anything has been recorded.
 * They are dim and unpressable, which costs no layout at all, and the alternative — controls
 * arriving as they become relevant — is three layouts and two jumps for one task.
 */
@Composable
fun TalkScreen(state: TalkState) {
    val s = LocalScale.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink.Night)
            .padding(horizontal = s.dp(Gap.EDGE)),
        verticalArrangement = Arrangement.spacedBy(s.dp(Gap.UNIT)),
    ) {
        MaTabs(current = Tab.T, available = state.availableTabs, onPick = state::onTab)

        // ---- THE TWO CHOICES, BOTH BEFORE RECORDING --------------------------------------
        //
        // Language first because naming it is more accurate than AUTO and the brief says so.
        // Mode second because it answers "what happens when I press stop", which is the next
        // question in the order somebody thinks about them.
        //
        // Both are LOCKED while recording. Changing the language mid-take would describe the
        // audio wrongly, and changing the mode mid-take would change where the result lands
        // after the decision to record it was already made.
        MaChooser(
            options = state.spokenOptions,
            chosen = state.spoken,
            enabled = !state.isRecording,
            onPick = state::onSpoken,
        )
        MaChooser(
            options = state.modeOptions,
            chosen = state.mode,
            enabled = !state.isRecording,
            onPick = state::onMode,
        )

        // ---- THE TRANSPORT ---------------------------------------------------------------
        //
        // Big targets, thumb reachable, usable without looking. Record is red while it runs,
        // which is the one place red is not a fault: recorder.md and design-language.md 3 both
        // reserve red for faults AND for recording, because recording is the one state where
        // the whole point is that it is impossible to miss.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(s.dp(Gap.UNIT)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MaRound(
                icon = Icons.Filled.Mic,
                label = "record",
                enabled = state.canRecord,
                lit = state.isRecording,
                tint = Ink.Red,
                size = 64f,
                onClick = state::onRecord,
            )
            MaRound(
                icon = Icons.Filled.Pause,
                label = "pause",
                enabled = state.canPause,
                lit = state.isPaused,
                size = 64f,
                onClick = state::onPause,
            )
            MaRound(
                icon = Icons.Filled.Stop,
                label = "stop",
                enabled = state.canStop,
                size = 64f,
                onClick = state::onStop,
            )
            Box(Modifier.weight(1f))
            // OPEN, AND IT SAYS VIDEO.
            //
            // The web version accepted video for a year and nobody knew, because the button said
            // only "open". The glyph is a folder and the word beneath it names both, so the
            // capability is legible without a manual. That is the whole fix and it is a label.
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                MaRound(
                    icon = Icons.Filled.FolderOpen,
                    label = "open an audio or video file",
                    enabled = !state.isRecording && !state.isBusy,
                    size = 56f,
                    onClick = state::onOpen,
                )
                Text(
                    "AUDIO / VIDEO",
                    color = if (!state.isRecording && !state.isBusy) Ink.Sand else Ink.Dead,
                    fontSize = s.sp(10f),
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        // The timer and the meter, side by side. The meter is the answer to "is it hearing me"
        // and the timer is the answer to "how long have I been going", and neither is useful
        // without the other.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(s.dp(Gap.UNIT)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                state.clock,
                color = if (state.isRecording) Ink.Red else Ink.Dead,
                fontSize = s.sp(22f),
                fontWeight = FontWeight.Bold,
            )
            Box(Modifier.weight(1f)) {
                MaMeter(level = state.level, active = state.isRecording)
            }
        }

        MaPlayer(
            ready = state.hasAudio,
            playing = state.isPlaying,
            position = state.playPosition,
            onPlayPause = state::onPlayPause,
            onStop = state::onPlayStop,
        )

        // Never empty while anything is happening, and never a spinner with no words.
        MaStatus(state.status, state.statusIsFault)

        MaBoxActions(
            hasText = state.text.isNotEmpty(),
            canUndo = state.canUndo,
            canRedo = state.canRedo,
            onCopy = state::onCopy,
            onClear = state::onClear,
            onUndo = state::onUndo,
            onRedo = state::onRedo,
            onNote = state::onNote,
        )

        // ---- THE BOX ---------------------------------------------------------------------
        //
        // It scrolls inside a fixed frame rather than growing the page, so nothing below it
        // moves as words arrive. A box that grows with its content changes the layout under a
        // thumb that is already moving toward a key.
        BasicTextField(
            value = state.text,
            onValueChange = state::onTextEdited,
            textStyle = TextStyle(color = Ink.Sand, fontSize = s.sp(17f)),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(Ink.Amber),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .heightIn(min = s.dp(120f))
                .background(Ink.Surface, RoundedCornerShape(s.dp(12f)))
                .padding(s.dp(Gap.UNIT))
                .verticalScroll(rememberScrollState()),
        )

        Box(Modifier.padding(bottom = s.dp(Gap.UNIT)))
    }
}

/**
 * Everything the screen needs, and nothing about how it is produced.
 *
 * The screen is deliberately given an interface rather than the activity: it is what lets the
 * layout be reasoned about, and eventually tested, without a microphone, a network or a key.
 */
interface TalkState {
    val availableTabs: Set<Tab>
    val spokenOptions: List<String>
    val spoken: String
    val modeOptions: List<String>
    val mode: String
    val recorderState: Recorder.State
    val isRecording: Boolean
    val isPaused: Boolean
    val isBusy: Boolean
    val canRecord: Boolean
    val canPause: Boolean
    val canStop: Boolean
    val clock: String
    val level: () -> Double
    val hasAudio: Boolean
    val isPlaying: Boolean
    val playPosition: String
    val status: String
    val statusIsFault: Boolean
    val text: String
    val canUndo: Boolean
    val canRedo: Boolean

    fun onTab(tab: Tab)
    fun onSpoken(value: String)
    fun onMode(value: String)
    fun onRecord()
    fun onPause()
    fun onStop()
    fun onOpen()
    fun onPlayPause()
    fun onPlayStop()
    fun onCopy()
    fun onClear()
    fun onUndo()
    fun onRedo()
    fun onNote()
    fun onTextEdited(value: String)
}
