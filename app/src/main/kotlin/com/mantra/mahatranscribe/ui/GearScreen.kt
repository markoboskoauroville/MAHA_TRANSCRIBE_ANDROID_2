package com.mantra.mahatranscribe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight

/**
 * THE GEAR — HOW IT LOOKS, AND IT IS THE ACCESSIBILITY SCREEN.
 *
 * From the brief: *"This is not decoration. It is the accessibility screen, and it should be the
 * first thing a new person is pointed at."*
 *
 * ### Why this screen exists in version 1 at all
 *
 * The plan was T alone. This screen is here anyway for one honest reason: without keys the app
 * cannot transcribe, and keys have to arrive from somewhere. Shipping T with no way to give it a
 * key would be shipping a screen that cannot do the thing it is for.
 *
 * So it holds exactly two things, and the rest of the brief's gear screen — typeface, colour
 * scheme, the live sample — is deliberately absent and named as absent in the delivery record.
 *
 * ### The sample is the setting
 *
 * A size control with no example of the size is a control you have to leave in order to judge,
 * which is design-language.md 11: a dial is judged against what it is doing, and it must apply
 * to the thing it affects WHILE that thing is happening. The sample line below sits inside the
 * same screen as the sliders and changes as they move, so a change can be seen before it is kept.
 */
@Composable
fun GearScreen(state: GearState) {
    val s = LocalScale.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink.Night)
            .padding(horizontal = s.dp(Gap.EDGE)),
        verticalArrangement = Arrangement.spacedBy(s.dp(Gap.UNIT)),
    ) {
        MaTabs(current = Tab.GEAR, available = state.availableTabs, onPick = state::onTab)

        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(s.dp(Gap.UNIT)),
        ) {
            Heading("TEXT SIZE")
            StepRow(
                value = state.textScale,
                onLess = { state.onTextScale(state.textScale - 0.1f) },
                onMore = { state.onTextScale(state.textScale + 0.1f) },
                onDefault = { state.onTextScale(MaScale.DEFAULT_TEXT) },
            )

            Heading("INTERFACE SIZE")
            StepRow(
                value = state.uiScale,
                onLess = { state.onUiScale(state.uiScale - 0.1f) },
                onMore = { state.onUiScale(state.uiScale + 0.1f) },
                onDefault = { state.onUiScale(MaScale.DEFAULT_UI) },
            )

            // The live sample. Two lines, because one line of five words does not show what
            // happens to a wrapped paragraph at 250%, and a paragraph is what somebody will
            // actually be reading.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Ink.Surface, RoundedCornerShape(s.dp(12f)))
                    .padding(s.dp(Gap.UNIT)),
            ) {
                Text(
                    "Ovo je primjer teksta. This is a sample of the text at the size you have " +
                        "chosen, wrapped over more than one line so the spacing can be seen too.",
                    color = Ink.Sand,
                    fontSize = s.sp(17f),
                )
            }

            Heading("KEYS")
            Text(
                state.keySummary,
                color = Ink.Sand,
                fontSize = s.sp(14f),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(s.dp(Gap.UNIT)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // A FILE PICKER, NEVER A TEXT BOX.
                //
                // Keys arrive as a file of notes with account names, dates and CANCELLED lines
                // in it, and they are parsed by shape. Asking somebody to paste a key into a box
                // means asking them to select exactly the right characters on a phone, which is
                // the one task this app's whole audience is least able to do.
                MaChooser(
                    options = listOf("IMPORT A KEY FILE"),
                    chosen = "",
                    onPick = { state.onImportKeys() },
                )
            }
            MaStatus(state.status, state.statusIsFault)

            Text(
                "Version ${state.version}",
                color = Ink.Dead,
                fontSize = s.sp(12f),
                modifier = Modifier.fillMaxWidth().padding(top = s.dp(Gap.UNIT)),
            )
        }
    }
}

@Composable
private fun Heading(text: String) {
    val s = LocalScale.current
    Text(text, color = Ink.AmberDim, fontSize = s.sp(12f), fontWeight = FontWeight.Bold)
}

/**
 * A value with less, more and default.
 *
 * design-language.md 10: two controls doing the same job must LOOK the same. `default` is a pill
 * exactly like the other two rather than an underlined link, because a difference in appearance
 * is read as a difference in kind and the person then wonders what the other one does.
 */
@Composable
private fun StepRow(value: Float, onLess: () -> Unit, onMore: () -> Unit, onDefault: () -> Unit) {
    val s = LocalScale.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(s.dp(Gap.UNIT)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MaRound(label = "−", size = 44f, onClick = onLess)
        Text(
            "${(value * 100).toInt()}%",
            color = Ink.Sand,
            fontSize = s.sp(16f),
            fontWeight = FontWeight.Bold,
        )
        MaRound(label = "+", size = 44f, onClick = onMore)
        Box(Modifier.weight(1f))
        MaChooser(options = listOf("default"), chosen = "", onPick = { onDefault() })
    }
}

interface GearState {
    val availableTabs: Set<Tab>
    val textScale: Float
    val uiScale: Float
    val keySummary: String
    val status: String
    val statusIsFault: Boolean
    val version: String

    fun onTab(tab: Tab)
    fun onTextScale(value: Float)
    fun onUiScale(value: Float)
    fun onImportKeys()
}
