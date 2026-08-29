package com.mantra.mahatranscribe.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * HOW EVERY SCREEN IN THIS APP LOOKS, IN ONE FILE.
 *
 * design-language.md, and none of it is preference. Each rule below cost at least a day and
 * several were undone by a later build and had to be re-argued.
 *
 * ### Colour carries state. Shape carries identity.
 *
 * Colour is the state channel and it is the ONLY one. Amber means the active thing, the lit
 * thing, the one in force. Grey means present but not available. Red is a fault, and a full
 * container is not a fault: a recording not yet transcribed is blue, never red, because red on a
 * screen opened forty times a day makes every visit feel like an incident.
 *
 * **A border must not mean on.** Tried in TTT mini on 19.8.2026 and reversed the same day: a ring
 * around three keys in a row where nothing else has one is not emphasis, it is difference, and
 * difference is what the eye lands on first.
 *
 * ### The two scales, and why they are separate
 *
 * This is the accessibility screen and it is the first thing a new person is pointed at. Text
 * size and interface size are different questions: somebody may want large words in a layout that
 * still fits, or big targets for a shaking hand at ordinary text size. One slider for both would
 * force a compromise on whichever they did not mean.
 *
 * **Everything textual goes through [MaScale.text] and everything pressable through
 * [MaScale.ui].** A hardcoded `14.sp` anywhere is a size that ignores the person's own setting,
 * which is the first thing somebody with low vision will already have turned up.
 */
object Ink {
    /** Background. Warm near-black, the same one as every other app in this account. */
    val Night = Color(0xFF0B0D10)
    val Surface = Color(0xFF141A21)

    /** Inactive surfaces. Present, visible, and clearly not available. */
    val Slate = Color(0xFF23303D)

    /** The accent, everywhere, deliberately identical across the account. */
    val Amber = Color(0xFFF59E0B)
    val AmberDim = Color(0xFFE8A64B)

    /** Text and icon ink on dark. */
    val Sand = Color(0xFFF2DDB4)

    /** Recording, and real faults only. */
    val Red = Color(0xFFEF4444)

    /** Work outstanding: a recording captured and not yet transcribed. NOT a fault, so not red. */
    val Blue = Color(0xFF60A5FA)

    /**
     * Greyed ink: a control that is present and cannot be used.
     *
     * design-language.md 1: nothing appears and nothing disappears. Opacity and pointer-events
     * are the tools because they DO NOT TOUCH LAYOUT, so an element occupies exactly the same
     * space idle as it does active.
     */
    val Dead = Color(0xFF5B6672)
}

/** The live scale factors, provided once at the root and read by every component. */
class MaScale(val text: Float, val ui: Float) {
    /** A text size in sp, already scaled. */
    fun sp(base: Float): TextUnit = (base * text).sp

    /** A layout dimension in dp, already scaled. */
    fun dp(base: Float): Dp = (base * ui).dp

    companion object {
        /** Both defaults are 1, and both have a named default the gear screen can return to. */
        const val DEFAULT_TEXT = 1.0f
        const val DEFAULT_UI = 1.0f

        /**
         * The range. 250% at the top because WCAG 1.4.4 asks for 200% and 200 is a floor, not a
         * target. The bottom stops at 80% rather than going lower: a control should not offer a
         * setting that defeats it, and this app's whole purpose is being readable.
         */
        const val MIN = 0.8f
        const val MAX = 2.5f
    }
}

val LocalScale = compositionLocalOf { MaScale(MaScale.DEFAULT_TEXT, MaScale.DEFAULT_UI) }

@Composable
fun MaTheme(textScale: Float, uiScale: Float, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalScale provides MaScale(textScale, uiScale), content = content)
}

/**
 * The one gap used between everything.
 *
 * design-language.md 10: the gaps between rows are the same gap and the gaps between items in a
 * row are the same gap. The eye reads uneven spacing as an error even when it cannot name what is
 * wrong, and it is right — nothing in a considered layout is 6dp from one neighbour and 11dp from
 * the next by intention.
 */
object Gap {
    const val UNIT = 8f
    const val EDGE = 12f
}
