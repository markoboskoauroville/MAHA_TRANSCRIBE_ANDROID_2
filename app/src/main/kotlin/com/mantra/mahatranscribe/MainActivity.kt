package com.mantra.mahatranscribe

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.mantra.mahatranscribe.audio.Decode
import com.mantra.mahatranscribe.audio.Recorder
import com.mantra.mahatranscribe.audio.RecordingService
import com.mantra.mahatranscribe.audio.Wav
import com.mantra.mahatranscribe.logic.Edits
import com.mantra.mahatranscribe.logic.Keys
import com.mantra.mahatranscribe.logic.Level
import com.mantra.mahatranscribe.logic.Mode
import com.mantra.mahatranscribe.logic.Ring
import com.mantra.mahatranscribe.logic.Role
import com.mantra.mahatranscribe.logic.Roles
import com.mantra.mahatranscribe.logic.Spoken
import com.mantra.mahatranscribe.logic.Take
import com.mantra.mahatranscribe.net.Transcribe
import com.mantra.mahatranscribe.ui.GearScreen
import com.mantra.mahatranscribe.ui.GearState
import com.mantra.mahatranscribe.ui.MaTheme
import com.mantra.mahatranscribe.ui.Tab
import com.mantra.mahatranscribe.ui.TalkScreen
import com.mantra.mahatranscribe.ui.TalkState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * THE ONE SCREEN THIS VERSION HAS, AND EVERYTHING BEHIND IT.
 *
 * ### Why the state lives here rather than in a ViewModel
 *
 * Because the thing a ViewModel buys — surviving a rotation — is not the problem this app has.
 * The problem is PROCESS DEATH, which a ViewModel does not survive either. So the answer is the
 * same answer at every level: **write to disk on change, restore on start**. [Store] does that,
 * and once it does, a ViewModel would be a second place for the same truth to live.
 *
 * Everything a person can lose is written the moment it changes: the text, the tab, the language,
 * the mode, both scales. Nothing waits for `onPause`, because process death does not call it.
 */
class MainActivity : ComponentActivity() {

    private lateinit var store: Store
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var recorder: Recorder? = null
    private var player: MediaPlayer? = null
    private lateinit var edits: Edits

    // ---- what the screen reads -----------------------------------------------------------
    private var tab by mutableStateOf(Tab.T)
    private var text by mutableStateOf("")
    private var spoken by mutableStateOf(Spoken.HR)
    private var mode by mutableStateOf(Mode.SINGLE)
    private var recState by mutableStateOf(Recorder.State.IDLE)
    private var clock by mutableStateOf("0:00")
    private var status by mutableStateOf("")
    private var fault by mutableStateOf(false)
    private var busy by mutableStateOf(false)
    private var audioFile by mutableStateOf<File?>(null)
    private var playing by mutableStateOf(false)
    private var playPos by mutableStateOf("")
    private var textScale by mutableStateOf(1.0f)
    private var uiScale by mutableStateOf(1.0f)
    private var undoTick by mutableStateOf(0)

    private val takeFile: File get() = File(filesDir, "take.wav")

    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) beginRecording()
        else say("The microphone is not allowed. Open Android settings and give this app permission.", true)
    }

    private val openMedia = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importFrom(uri)
    }

    private val openKeys = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importKeys(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)

        // RESTORE, AND IT IS NOT AN EDIT. A restore that pushed onto the undo history would make
        // the first undo after reopening the app take somebody to a state they never typed.
        text = store.text
        edits = Edits(text)
        spoken = Spoken.of(store.spoken)
        mode = runCatching { Mode.valueOf(store.mode) }.getOrDefault(Mode.SINGLE)
        textScale = store.textScale
        uiScale = store.uiScale
        tab = runCatching { Tab.valueOf(store.tab) }.getOrDefault(Tab.T)

        // A TAKE LEFT OPEN BY A KILLED PROCESS. Its header still claims zero samples, so nothing
        // can play it. Repairing it turns a crash from a lost take into a lost second, which is
        // the entire promise of writing to disk every second and it has to be collected here.
        if (takeFile.exists() && takeFile.length() > Wav.HEADER_BYTES) {
            if (Wav.repair(takeFile)) {
                audioFile = takeFile
                say("A recording from last time was recovered. It is in the player.", false)
            }
        }

        setContent {
            MaTheme(textScale = textScale, uiScale = uiScale) {
                when (tab) {
                    Tab.GEAR -> GearScreen(gear)
                    else -> TalkScreen(talk)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { player?.release() }
        player = null
    }

    private fun say(message: String, isFault: Boolean) { status = message; fault = isFault }

    // ---- RECORDING -------------------------------------------------------------------------

    private fun beginRecording() {
        val r = Recorder(takeFile)
        if (!r.start()) { say(r.fault ?: "The recording did not start.", true); return }
        recorder = r
        recState = Recorder.State.RECORDING
        audioFile = null
        RecordingService.start(this)
        say("Recording. Speak.", false)

        // The clock and the state, on their own tick. A bounded wait, ended by the state leaving
        // the recording states, never a spin.
        scope.launch {
            while (r.state == Recorder.State.RECORDING || r.state == Recorder.State.PAUSED) {
                recState = r.state
                val s = r.seconds.toInt()
                clock = "%d:%02d".format(s / 60, s % 60)
                delay(200)
            }
            recState = r.state
        }
    }

    private fun endRecording() {
        val r = recorder ?: return
        val file = r.stop()
        recorder = null
        recState = Recorder.State.STOPPED
        RecordingService.stop(this)
        if (file == null) { say("Nothing was recorded.", true); return }
        audioFile = file
        transcribe(file)
    }

    // ---- TRANSCRIBING ----------------------------------------------------------------------

    private fun transcribe(file: File) {
        val provider = Roles.resolve(Role.TRANSCRIPTION, store.route(Role.TRANSCRIPTION.name))
        val keys = store.keysFor(provider)
        if (keys.isEmpty()) {
            // NEVER SILENCE. The audio is safe on disk and the sentence says both halves: what is
            // missing, and that nothing was lost.
            say("There are no ${provider.name} keys yet. The recording is saved. Open ⚙ and import a key file.", true)
            return
        }

        busy = true
        say("Levelling the sound", false)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                // LEVEL BEFORE SENDING. A quiet recording is misheard in a particular way: the
                // transcriber DROPS short words rather than guessing, so the transcript reads
                // fluently and is missing things, with nothing in the result to notice.
                val levelled = File(cacheDir, "levelled.wav")
                runCatching {
                    val samples = Decode.decodeToMono16k(file)
                    Level.applyInPlace(samples)
                    Wav.write(levelled, samples)
                }
                val toSend = if (levelled.exists() && levelled.length() > Wav.HEADER_BYTES) levelled else file
                Transcribe.run(
                    provider, Ring(keys), toSend, spoken,
                    onProgress = { msg -> scope.launch { say(msg, false) } },
                )
            }
            busy = false
            when (result) {
                is Transcribe.Outcome.Text -> {
                    if (result.text.isBlank()) {
                        say("Nothing was heard in that recording. It is still in the player.", true)
                    } else {
                        applyText(Take.deliver(text, result.text, mode))
                        say("Done.", false)
                    }
                }
                is Transcribe.Outcome.Failed -> {
                    // The audio survives every failure. Saying so is the difference between a
                    // setback and a loss.
                    say("${result.message} The recording is kept, so it can be tried again.", true)
                }
            }
        }
    }

    private fun importFrom(uri: Uri) {
        busy = true
        say("Reading the file", false)
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val copy = File(cacheDir, "opened.bin")
                    contentResolver.openInputStream(uri)?.use { input ->
                        copy.outputStream().use { input.copyTo(it) }
                    } ?: error("the file could not be opened")
                    // The same path for audio and for video: MediaExtractor takes the audio track
                    // out of an mp4 exactly as it does out of an m4a.
                    val samples = Decode.decodeToMono16k(copy)
                    val wav = File(filesDir, "take.wav")
                    Wav.write(wav, samples)
                    wav
                }
            }
            busy = false
            outcome.onSuccess { wav ->
                audioFile = wav
                transcribe(wav)
            }.onFailure {
                say("That file has no sound in it that this app can read.", true)
            }
        }
    }

    // ---- KEYS ------------------------------------------------------------------------------

    private fun importKeys(uri: Uri) {
        scope.launch {
            val report = withContext(Dispatchers.IO) {
                runCatching {
                    val blob = contentResolver.openInputStream(uri)
                        ?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                    val counts = mutableListOf<String>()
                    for (p in listOf(
                        Keys.Provider.GROQ, Keys.Provider.ASSEMBLYAI,
                        Keys.Provider.SPEECHIFY, Keys.Provider.ANTHROPIC,
                    )) {
                        val n = store.addKeys(p, Keys.parse(blob, p))
                        if (n > 0) counts.add("$n ${p.name.lowercase()}")
                    }
                    val pairs = store.addHumePairs(Keys.parseHume(blob))
                    if (pairs > 0) counts.add("$pairs hume pairs")
                    counts
                }.getOrDefault(emptyList())
            }
            // ADDS, NEVER REPLACES, and the count is the report. A zero here means the file held
            // a format nobody has taught this app yet, which is a different problem from an empty
            // file and deserves a different sentence.
            say(
                if (report.isEmpty()) "No keys were recognised in that file. Nothing was changed."
                else "Added " + report.joinToString(", ") + ".",
                report.isEmpty(),
            )
        }
    }

    private fun keySummary(): String {
        val parts = mutableListOf<String>()
        for (p in listOf(Keys.Provider.ASSEMBLYAI, Keys.Provider.GROQ, Keys.Provider.SPEECHIFY)) {
            parts.add("${p.name.lowercase()} ${store.keysFor(p).size}")
        }
        parts.add("hume ${store.humePairs().size}")
        return parts.joinToString("   ")
    }

    // ---- PLAYING ---------------------------------------------------------------------------

    private fun togglePlay() {
        val f = audioFile ?: return
        val p = player
        // STATE AND REALITY ARE TWO FACTS AND THEY WILL DISAGREE. design-language.md 14: the
        // object is the truth and the state is a claim about the object. Checking the player
        // itself here rather than trusting `playing` is what stops the failure where the control
        // flips between play and pause for ever with no sound behind it.
        if (p != null && p.isPlaying) { p.pause(); playing = false; return }
        if (p != null) { p.start(); playing = true; watchPlayer(); return }
        val fresh = MediaPlayer().apply {
            setDataSource(f.absolutePath)
            setOnCompletionListener { playing = false; playPos = "" }
            prepare()
            start()
        }
        player = fresh
        playing = true
        watchPlayer()
    }

    private fun watchPlayer() {
        scope.launch {
            while (player?.isPlaying == true) {
                val p = player ?: break
                val at = p.currentPosition / 1000
                val of = (p.duration / 1000).coerceAtLeast(0)
                playPos = "%d:%02d / %d:%02d".format(at / 60, at % 60, of / 60, of % 60)
                delay(250)
            }
            playing = false
        }
    }

    private fun stopPlay() {
        runCatching { player?.stop(); player?.release() }
        player = null; playing = false; playPos = ""
    }

    // ---- TEXT ------------------------------------------------------------------------------

    private fun applyText(next: String) {
        edits.push(next)
        text = edits.present
        store.text = text
        undoTick++
    }

    // ---- THE TWO SCREENS' STATE ------------------------------------------------------------

    /** Version 1 has T and the gear. The other four are drawn, greyed, and unpressable. */
    private val available = setOf(Tab.T, Tab.GEAR)

    private val talk = object : TalkState {
        override val availableTabs get() = available
        override val spokenOptions = Spoken.ORDER.map { it.label }
        override val spoken get() = this@MainActivity.spoken.label
        override val modeOptions = listOf("SINGLE", "MULTI")
        override val mode get() = this@MainActivity.mode.name
        override val recorderState get() = recState
        override val isRecording get() = recState == Recorder.State.RECORDING
        override val isPaused get() = recState == Recorder.State.PAUSED
        override val isBusy get() = busy
        override val canRecord get() = !busy && recState != Recorder.State.RECORDING
        override val canPause get() = recState == Recorder.State.RECORDING || recState == Recorder.State.PAUSED
        override val canStop get() = recState == Recorder.State.RECORDING || recState == Recorder.State.PAUSED
        override val clock get() = this@MainActivity.clock
        override val level: () -> Double get() = { recorder?.level ?: Level.FLOOR_DB }
        override val hasAudio get() = audioFile != null
        override val isPlaying get() = playing
        override val playPosition get() = playPos.ifEmpty { if (audioFile != null) "ready" else "" }
        override val status get() = this@MainActivity.status
        override val statusIsFault get() = fault
        override val text get() = this@MainActivity.text
        override val canUndo get() = undoTick >= 0 && edits.canUndo
        override val canRedo get() = undoTick >= 0 && edits.canRedo

        override fun onTab(tab: Tab) { this@MainActivity.tab = tab; store.tab = tab.name }
        override fun onSpoken(value: String) {
            Spoken.ORDER.firstOrNull { it.label == value }?.let {
                this@MainActivity.spoken = it; store.spoken = it.name
            }
        }
        override fun onMode(value: String) {
            runCatching { Mode.valueOf(value) }.getOrNull()?.let {
                this@MainActivity.mode = it; store.mode = it.name
            }
        }
        override fun onRecord() {
            if (recState == Recorder.State.PAUSED) { recorder?.resume(); recState = Recorder.State.RECORDING; return }
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
            ) beginRecording() else askMic.launch(Manifest.permission.RECORD_AUDIO)
        }
        override fun onPause() {
            when (recState) {
                Recorder.State.RECORDING -> { recorder?.pause(); recState = Recorder.State.PAUSED; say("Paused.", false) }
                Recorder.State.PAUSED -> { recorder?.resume(); recState = Recorder.State.RECORDING; say("Recording.", false) }
                else -> Unit
            }
        }
        override fun onStop() = endRecording()
        override fun onOpen() { openMedia.launch(arrayOf("audio/*", "video/*")) }
        override fun onPlayPause() = togglePlay()
        override fun onPlayStop() = stopPlay()
        override fun onCopy() {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("maha", text))
            say("Copied.", false)
        }
        override fun onClear() { applyText("") ; say("Cleared. Undo will bring it back.", false) }
        override fun onUndo() { text = edits.undo(); store.text = text; undoTick++ }
        override fun onRedo() { text = edits.redo(); store.text = text; undoTick++ }
        override fun onNote() { say("Notes arrive in the next version.", false) }
        override fun onTextEdited(value: String) { applyText(value) }
    }

    private val gear = object : GearState {
        override val availableTabs get() = available
        override val textScale get() = this@MainActivity.textScale
        override val uiScale get() = this@MainActivity.uiScale
        override val keySummary get() = keySummary()
        override val status get() = this@MainActivity.status
        override val statusIsFault get() = fault
        override val version = BuildConfig.VERSION_NAME

        override fun onTab(tab: Tab) { this@MainActivity.tab = tab; store.tab = tab.name }
        override fun onTextScale(value: Float) {
            val v = value.coerceIn(com.mantra.mahatranscribe.ui.MaScale.MIN, com.mantra.mahatranscribe.ui.MaScale.MAX)
            textScale = v; store.textScale = v
        }
        override fun onUiScale(value: Float) {
            val v = value.coerceIn(com.mantra.mahatranscribe.ui.MaScale.MIN, com.mantra.mahatranscribe.ui.MaScale.MAX)
            uiScale = v; store.uiScale = v
        }
        override fun onImportKeys() { openKeys.launch(arrayOf("text/*", "*/*")) }
    }
}
