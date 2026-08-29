package com.mantra.mahatranscribe.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mantra.mahatranscribe.logic.Level
import kotlinx.coroutines.delay

/**
 * THE PARTS EVERY SCREEN IS BUILT FROM.
 *
 * design-language.md 2: tabs are views of one application, not separate applications sharing a
 * window. If the Talk screen and the Read screen both have a player, **it is the same player** —
 * same component, same controls, same order, same proportions. Not a similar one built twice.
 * Two implementations of one thing are two places to drift apart, and the drift always shows up
 * as the same control behaving differently depending on where you found it.
 *
 * So this file is written for T and is where R, TR and VR will get theirs. Nothing in it knows
 * anything about transcription.
 */

/**
 * A round key with a letter or a glyph in it.
 *
 * [enabled] false means present and greyed, NEVER absent. That is the rule everything else
 * follows from: nothing appears, nothing disappears, things become active or inactive. Opacity
 * does not touch layout, so the key occupies exactly the same space either way.
 *
 * [lit] is the state channel. Amber means this is the one in force.
 */
@Composable
fun MaRound(
    label: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    lit: Boolean = false,
    tint: Color? = null,
    size: Float = 44f,
    onClick: () -> Unit,
) {
    val s = LocalScale.current
    val ink = when {
        !enabled -> Ink.Dead
        lit -> Ink.Night
        else -> tint ?: Ink.Sand
    }
    val fill = when {
        !enabled -> Ink.Slate
        lit -> tint ?: Ink.Amber
        else -> Ink.Surface
    }
    Box(
        modifier = Modifier
            // 44dp is WCAG 2.2's AAA target size. The AA floor of 24 is not enough for a shaking
            // hand, and this app is for a shaking hand.
            .size(s.dp(size))
            .background(fill, CircleShape)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = label, tint = ink, modifier = Modifier.size(s.dp(size * 0.5f)))
        } else if (label != null) {
            Text(label, color = ink, fontSize = s.sp(15f), fontWeight = FontWeight.Bold)
        }
    }
}

/** The six screens. The row never moves and never changes contents. */
enum class Tab(val letter: String) { T("T"), R("R"), TR("TR"), VR("VR"), GEAR("⚙"), H("H") }

/**
 * The tab row.
 *
 * From the brief: *"Six, reachable from a single row of round buttons at the top, always visible,
 * one letter each. The row never moves and never changes contents."*
 *
 * [available] is the set that currently does something. In version 1 that is T alone, and the
 * other five are drawn, greyed and unpressable rather than missing. A row that grows as features
 * arrive makes the eye hunt for its contents on every build.
 */
@Composable
fun MaTabs(current: Tab, available: Set<Tab>, onPick: (Tab) -> Unit) {
    val s = LocalScale.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = s.dp(Gap.EDGE), vertical = s.dp(Gap.UNIT)),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (t in Tab.entries) {
            MaRound(
                label = t.letter,
                enabled = t in available,
                lit = t == current,
                size = 42f,
            ) { onPick(t) }
        }
    }
}

/**
 * THE LEVEL METER, AND IT MUST NEVER LIE.
 *
 * recorder.md 3. A meter with automatic gain and no floor amplifies the noise of a DISCONNECTED
 * microphone into a convincing dance, and that is the one lie this display must never tell. The
 * reading it is given is true dBFS with a floor and no gain of any kind, so a flat trace means
 * the microphone is genuinely not receiving.
 *
 * Two things lifted from TTT mini's meter rather than reinvented, because the version before it
 * was called amateurish and was:
 *
 *  - **A dB DOMAIN.** A linear fraction of amplitude spends most of its travel in the top sixth
 *    of the range, so ordinary speech barely moves the bar and a shout pins it.
 *  - **A FIXED CLOCK.** The bar is sampled on its own 50 ms tick and animated over 70 ms, so its
 *    speed is a decision rather than an accident of whatever cadence the audio buffers arrive on.
 */
@Composable
fun MaMeter(level: () -> Double, active: Boolean) {
    val s = LocalScale.current
    var shown by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(active) {
        // A bounded wait with a stated interval, not a spin. It ends when `active` changes,
        // which recomposes this effect.
        while (active) {
            val db = level()
            // -50 dB to 0 dB across the bar. Below -50 is room noise and reads as nothing, which
            // is what makes the dead-microphone case visibly flat rather than merely small.
            val f = ((db + 50.0) / 50.0).coerceIn(0.0, 1.0).toFloat()
            shown = shown * 0.4f + f * 0.6f
            delay(50)
        }
        shown = 0f
    }

    Canvas(modifier = Modifier.fillMaxWidth().height(s.dp(10f))) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(Ink.Slate, Offset.Zero, size, r)
        val w = size.width * shown
        if (w > 0.5f) {
            drawRoundRect(
                if (active) Ink.Red else Ink.Dead,
                Offset.Zero, Size(w, size.height), r,
            )
        }
    }
}

/**
 * EVERY ACTION FOR A TEXT BOX, IN THE SAME CORNER.
 *
 * From the brief: upper right, above the box, the same size and the same font as its neighbours.
 * Copy, clear, undo, redo, add to notes, in that order, everywhere in the app, for ever.
 *
 * **Undo is visible whenever there is text and greyed when there is nothing behind it.** Somebody
 * looks for undo BEFORE they need it, to know it is there. A safety net you cannot see is not a
 * safety net. That is why [canUndo] controls the ink and never the presence.
 */
@Composable
fun MaBoxActions(
    hasText: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    onCopy: () -> Unit,
    onClear: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onNote: () -> Unit,
) {
    val s = LocalScale.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MaRound(icon = Icons.Filled.ContentCopy, label = "copy", enabled = hasText, size = 38f, onClick = onCopy)
        Box(Modifier.size(s.dp(Gap.UNIT)))
        MaRound(icon = Icons.Filled.Undo, label = "undo", enabled = canUndo, size = 38f, onClick = onUndo)
        Box(Modifier.size(s.dp(Gap.UNIT)))
        MaRound(icon = Icons.Filled.Redo, label = "redo", enabled = canRedo, size = 38f, onClick = onRedo)
        Box(Modifier.size(s.dp(Gap.UNIT)))
        MaRound(icon = Icons.Filled.NoteAdd, label = "add to notes", enabled = hasText, size = 38f, onClick = onNote)
        Box(Modifier.size(s.dp(Gap.UNIT)))
        MaRound(icon = Icons.Filled.DeleteOutline, label = "clear", enabled = hasText, size = 38f, onClick = onClear)
    }
}

/**
 * One choice in force out of several.
 *
 * design-language.md 6: radio for one, tick-box for many. If exactly one option is in force, the
 * mark must visibly leave the last one when another is chosen. These are pills rather than radio
 * circles because a pill is a target and a circle is a decoration, but the semantics are a
 * radio's: exactly one is lit.
 *
 * design-language.md 10, the rule this component is most likely to break: **nothing goes off the
 * screen.** Sizing to content and spacing evenly, never a fraction of the line, because a weight
 * is a promise about width that a word cannot keep.
 */
@Composable
fun MaChooser(options: List<String>, chosen: String, enabled: Boolean = true, onPick: (String) -> Unit) {
    val s = LocalScale.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(s.dp(Gap.UNIT)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (o in options) {
            val lit = o == chosen
            Box(
                modifier = Modifier
                    .background(
                        if (!enabled) Ink.Slate else if (lit) Ink.Amber else Ink.Surface,
                        RoundedCornerShape(s.dp(20f)),
                    )
                    .then(if (enabled) Modifier.clickable { onPick(o) } else Modifier)
                    .padding(horizontal = s.dp(14f), vertical = s.dp(10f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    o,
                    color = if (!enabled) Ink.Dead else if (lit) Ink.Night else Ink.Sand,
                    fontSize = s.sp(14f),
                    fontWeight = if (lit) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

/**
 * THE PLAYER, AND THERE IS ONLY ONE OF IT IN THE WHOLE APP.
 *
 * It is here, in the shared file, on the day the T screen was written, because T is the screen
 * every other one is measured against. When R needs a player it takes this one.
 *
 * design-language.md 5: **a control says what the next press will DO, not what is currently
 * happening.** So the key shows a triangle when pressing it would start and two bars when
 * pressing it would pause. A control that describes its own state leaves the person working out
 * what pressing it would achieve, which is one thought too many mid-sentence.
 *
 * design-language.md 1: the player is ALWAYS PRESENT, greyed when there is nothing to play. In
 * Baba's own words about the reader tab: *"You cannot play anything with it until you paste the
 * text, but GIVE ME THE PLAYER."*
 */
@Composable
fun MaPlayer(
    ready: Boolean,
    playing: Boolean,
    position: String,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
) {
    val s = LocalScale.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Ink.Surface, RoundedCornerShape(s.dp(12f)))
            .padding(s.dp(Gap.UNIT)),
        horizontalArrangement = Arrangement.spacedBy(s.dp(Gap.UNIT)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MaRound(
            icon = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            label = if (playing) "pause" else "play",
            enabled = ready,
            size = 40f,
            onClick = onPlayPause,
        )
        MaRound(icon = Icons.Filled.Stop, label = "stop", enabled = ready && playing, size = 40f, onClick = onStop)
        Text(
            position,
            color = if (ready) Ink.Sand else Ink.Dead,
            fontSize = s.sp(15f),
            modifier = Modifier.padding(start = s.dp(Gap.UNIT)),
        )
    }
}

/**
 * A FAILURE MUST BE A SENTENCE ON SCREEN, NEVER SILENCE.
 *
 * From the brief, and it is named as the worst bug the app ever had: *"a button that did nothing:
 * no error, no spinner, nothing. People conclude the app is broken, not that something was
 * unavailable."*
 *
 * So this line is always present, and it holds a sentence whenever anything is happening or has
 * gone wrong. It is never empty while work is in flight. Red is reserved for a real fault; work
 * in progress is amber, because a screen opened forty times a day must not feel like an incident.
 */
@Composable
fun MaStatus(text: String, fault: Boolean) {
    val s = LocalScale.current
    Text(
        text = text,
        color = if (fault) Ink.Red else Ink.AmberDim,
        fontSize = s.sp(14f),
        textAlign = TextAlign.Start,
        modifier = Modifier.fillMaxWidth().padding(vertical = s.dp(4f)),
    )
}
