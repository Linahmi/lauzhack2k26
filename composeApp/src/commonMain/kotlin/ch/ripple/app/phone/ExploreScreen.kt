package ch.ripple.app.phone

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.ripple.app.assistant.LivePhase
import ch.ripple.app.keyMoment
import ch.ripple.core.Correction
import ch.ripple.core.InputState
import ch.ripple.core.briefing
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/* ============================== What the screen shows ============================== */

/** What a finished exchange shows: an engine result (by decision/version), or Routes' own words. */
sealed interface AnswerContent {
    data class Result(val decisionId: String, val nodeId: String, val readout: Readout, val note: String?) : AnswerContent
    data class Reply(val text: String) : AnswerContent
}

sealed interface ExploreVoiceUiState {
    data object Idle : ExploreVoiceUiState
    /** [level] is Live's microphone level (0 with Android's recognizer, which reports none). */
    data class Listening(val level: Float, val partialTranscript: String, val connecting: Boolean = false) : ExploreVoiceUiState
    /** Only while Live prepares its spoken reply: the engine itself answers instantly. */
    data class Thinking(val question: String) : ExploreVoiceUiState
    data class Answer(val question: String?, val content: AnswerContent, val isSpeaking: Boolean) : ExploreVoiceUiState
    data class Error(val message: String) : ExploreVoiceUiState
    data object MicPermissionDenied : ExploreVoiceUiState
}

/** Maps the real session and voice state; nothing here is invented. */
fun exploreState(session: RoutesSession, voice: VoiceState): ExploreVoiceUiState {
    val live = voice.live.state
    val phase = voice.live.state.phase
    val lastQuestion = session.turns.lastOrNull { it is Turn.User }?.let { (it as Turn.User).text }
    return when {
        live.permissionDenied -> ExploreVoiceUiState.MicPermissionDenied
        voice.problem != null -> ExploreVoiceUiState.Error(voice.problem!!)
        voice.checking || phase == LivePhase.Connecting -> ExploreVoiceUiState.Listening(0f, "", connecting = true)
        voice.listening -> ExploreVoiceUiState.Listening(0f, voice.heard)
        live.connected && phase == LivePhase.Listening -> ExploreVoiceUiState.Listening(live.inputLevel, live.heard)
        live.connected && phase == LivePhase.Thinking -> ExploreVoiceUiState.Thinking(live.heard.ifBlank { lastQuestion.orEmpty() })
        else -> answerOf(session)?.let { a ->
            // A result is about a decision: show that decision's own question, not the last short answer typed.
            val question = (a as? AnswerContent.Result)?.let { r -> session.decisions.firstOrNull { it.id == r.decisionId }?.spec?.intention } ?: lastQuestion
            ExploreVoiceUiState.Answer(question, a, live.playbackActive)
        } ?: ExploreVoiceUiState.Idle
    }
}

/** The newest exchange: what Routes said after the person's last turn. A result wins over plain replies. */
private fun answerOf(session: RoutesSession): AnswerContent? {
    val turns = session.turns
    // Only something the person asked gets an answer; a greeting before that stays part of the idle screen.
    val lastAsked = turns.indexOfLast { it is Turn.User }
    if (lastAsked < 0) return null
    val after = turns.drop(lastAsked + 1)
    if (after.isEmpty()) return null
    val replies = after.filterIsInstance<Turn.Routes>().joinToString(" ") { it.text }.ifBlank { null }
    val finding = after.filterIsInstance<Turn.Finding>().lastOrNull()
    val d = finding?.let { f -> session.decisions.firstOrNull { it.id == f.decisionId } }
    if (finding == null || d == null) return replies?.let(AnswerContent::Reply)
    val node = d.graph.node(finding.nodeId)
    val readout = readoutOf(d, keyMoment(d.graph.briefing(node.id)), node.session)
    // A saved change or a what-if keeps its own one-line explanation of what moved.
    val changed = finding.text.startsWith("Saved") || finding.text.startsWith("What-if")
    return AnswerContent.Result(d.id, node.id, if (changed) readout.copy(explanation = finding.text) else readout, replies)
}

/* ============================== The screen ============================== */

@Composable
fun ExploreScreen(session: RoutesSession, nav: PhoneNav, voice: VoiceState, platform: PhonePlatform) {
    val state = exploreState(session, voice)
    val still = platform.animationsOff()
    var typing by remember { mutableStateOf(false) }
    // Errors and a denied microphone open the text field: typing always works.
    LaunchedEffect(state is ExploreVoiceUiState.Error || state is ExploreVoiceUiState.MicPermissionDenied) {
        if (state is ExploreVoiceUiState.Error || state is ExploreVoiceUiState.MicPermissionDenied) typing = true
    }
    val haptic = LocalHapticFeedback.current
    val listening = state is ExploreVoiceUiState.Listening && !state.connecting
    LaunchedEffect(listening) { if (listening) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }

    Column(Modifier.fillMaxSize()) {
        Header(session)
        session.loadProblem?.let { p -> Box(Modifier.padding(horizontal = 20.dp)) { Notice("$p You can start a new session; the old file is kept aside, not deleted.", "Start a new session") { session.startFresh() } } }
        session.saveProblem?.let { p -> Box(Modifier.padding(horizontal = 20.dp)) { Notice(p) } }

        // The words, top-left; the mic in the middle of the space left, or just above the nav once there's an answer.
        val answer = state is ExploreVoiceUiState.Answer
        Column(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.then(if (answer) Modifier.weight(1f) else Modifier).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
                AnimatedContent(
                    targetState = state,
                    contentKey = { it::class },
                    transitionSpec = { if (still) EnterTransition.None togetherWith ExitTransition.None else fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
                ) { s -> Words(s, session, nav, voice) }
                session.pending?.let { p -> Spacer(Modifier.height(16.dp)); PendingCard(p, session) }
            }
            if (answer) Mic(state, voice, still)
            else Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Mic(state, voice, still) }
        }

        if (typing || state is ExploreVoiceUiState.Error || state is ExploreVoiceUiState.MicPermissionDenied)
            TypeField(if (session.interview != null) "Your answer" else "Ask “What if…”", autoFocus = typing) { session.handle(it, Via.TEXT); voice.problem = null }
        else Text("Type instead", style = Ty.Small.copy(color = Tk.Ink, textDecoration = TextDecoration.Underline, textAlign = TextAlign.Center),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { typing = true }.wrapContentHeight())
        Text("Voice is sent to Google to reply. You can always type.", style = Ty.Small.copy(fontSize = 11.sp, textAlign = TextAlign.Center),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp))
    }
}

@Composable
private fun Header(session: RoutesSession) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        RoutesLockup()
        Spacer(Modifier.weight(1f))
        session.profile?.let { Text(if (it.fictional) "${it.name} · fictional example" else it.name, style = Ty.Small) }
    }
}

/** An example Routes can actually answer (the engine recognises "move out"; it ignores month names). */
private const val EXAMPLE = "What if I move out?"

@Composable
private fun Words(s: ExploreVoiceUiState, session: RoutesSession, nav: PhoneNav, voice: VoiceState) {
    Column(Modifier.fillMaxWidth().padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (s) {
            ExploreVoiceUiState.Idle -> {
                val greeting = session.turns.takeIf { t -> t.none { it is Turn.User } }?.filterIsInstance<Turn.Routes>()?.lastOrNull()?.text
                Text(greeting ?: "Ask a “What if…” question about a decision.", style = Ty.Body.copy(color = Tk.Ink2))
                session.activeDecision?.let { d ->
                    LinkText("Continue “${d.title}”") { session.open(d.id); nav.openDecision = d.id; nav.space = Space.ROUTES }
                }
                if (session.profile == null) LinkText("Try with an example person (Alex, fictional)") { session.startExample() }
                Spacer(Modifier.height(8.dp))
                Text("Try: “$EXAMPLE”", style = Ty.Small.copy(fontSize = 12.sp),
                    modifier = Modifier.heightIn(min = 48.dp).clickable(role = Role.Button) { session.handle(EXAMPLE, Via.TEXT) }.wrapContentHeight())
            }
            is ExploreVoiceUiState.Listening -> Text(
                if (s.connecting) "" else s.partialTranscript,
                // Nothing is final until the person stops: the words stay in the secondary grey (AA on both themes).
                style = Ty.Body.copy(fontSize = 20.sp, lineHeight = 28.sp, color = Tk.Ink2),
            )
            is ExploreVoiceUiState.Thinking -> Text(s.question, style = Ty.Body.copy(fontSize = 15.sp, color = Tk.Ink2))
            is ExploreVoiceUiState.Answer -> {
                s.question?.let { Text(it, style = Ty.Small.copy(fontSize = 12.sp)) }
                when (val c = s.content) {
                    is AnswerContent.Reply -> Text(c.text, style = Ty.Finding)
                    is AnswerContent.Result -> {
                        var why by remember(c.nodeId) { mutableStateOf(false) }
                        Text(c.readout.status, style = Ty.Small.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium, color = if (c.readout.problem) Tk.Red else Tk.Ink2))
                        Text(c.readout.headline, style = Ty.Finding)
                        Text(c.readout.explanation, style = Ty.Body.copy(fontSize = 14.sp, color = Tk.Ink2))
                        c.note?.let { Text(it, style = Ty.Small) }
                        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            LinkText("Month by month →") { session.open(c.decisionId); nav.openDecision = c.decisionId; nav.space = Space.ROUTES }
                            LinkText(if (why) "Hide why" else "Why?") { why = !why }
                        }
                        if (why) session.decisions.firstOrNull { it.id == c.decisionId }?.let { d -> Text(explainText(d), style = Ty.Body.copy(fontSize = 14.sp)) }
                    }
                }
            }
            is ExploreVoiceUiState.Error -> {
                Text(s.message, style = Ty.Body)
                LinkText("Try again") { voice.problem = null; voice.toggle() }
                // The recognizer's own ways out, kept from the previous composer.
                if (voice.speech.state.canDownload) LinkText("Download the speech model (recognition then stays on this phone)") {
                    voice.speech.downloadModel(consented = true); voice.problem = voice.speech.state.summary
                }
                if (voice.canUseSystem) LinkText("Use Android's speech service (it may send audio to its provider)") { voice.useSystem() }
            }
            ExploreVoiceUiState.MicPermissionDenied -> {
                Text("Routes can't use the microphone. You can type instead.", style = Ty.Body)
                LinkText("Allow microphone") { voice.live.openMicrophoneSettings() }
            }
        }
    }
}

@Composable
private fun Mic(s: ExploreVoiceUiState, voice: VoiceState, still: Boolean) {
    val answer = s is ExploreVoiceUiState.Answer
    val size by animateDpAsState(if (answer) 52.dp else 88.dp, if (still) snap() else tween(200))
    val speaking = (s as? ExploreVoiceUiState.Answer)?.isSpeaking == true
    val description = when {
        s is ExploreVoiceUiState.Listening -> "Stop listening"
        s is ExploreVoiceUiState.Thinking -> "Stop"
        speaking -> "Interrupt"
        else -> "Start talking"
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (s is ExploreVoiceUiState.Listening) Wave(s.level, still) else if (!answer) Spacer(Modifier.height(24.dp))
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier.size(size).semantics { contentDescription = description }
                .clickable(role = Role.Button) { if (speaking) voice.live.stopAudio() else voice.toggle() },
            contentAlignment = Alignment.Center,
        ) {
            if (s is ExploreVoiceUiState.Thinking) ThinkingRing(still)
            else Box(Modifier.fillMaxSize().background(Tk.Red, CircleShape), contentAlignment = Alignment.Center) {
                if (s is ExploreVoiceUiState.Listening) Box(Modifier.size(if (answer) 14.dp else 22.dp).background(Tk.OnRed, Tk.R))
                else MicGlyph(if (answer) 20.dp else 30.dp)
            }
        }
        Spacer(Modifier.height(8.dp))
        val caption = when (s) {
            ExploreVoiceUiState.Idle, is ExploreVoiceUiState.Error, ExploreVoiceUiState.MicPermissionDenied -> "Tap to talk"
            is ExploreVoiceUiState.Listening -> if (s.connecting) "Connecting…" else "Listening · tap to stop"
            is ExploreVoiceUiState.Thinking -> "Calculating your months…"
            is ExploreVoiceUiState.Answer -> if (speaking) "Speaking · tap to interrupt" else ""
        }
        // One polite announcement per state change.
        val announce = when (s) {
            is ExploreVoiceUiState.Listening -> if (s.connecting) "Connecting" else "Listening"
            is ExploreVoiceUiState.Thinking -> "Calculating"
            is ExploreVoiceUiState.Answer -> "Answer ready"
            else -> ""
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite; contentDescription = announce.ifBlank { caption } }) {
            if (s is ExploreVoiceUiState.Listening && !s.connecting) { Box(Modifier.size(6.dp).background(Tk.Red, CircleShape)); Spacer(Modifier.width(6.dp)) }
            Text(caption, style = Ty.Small.copy(fontSize = if (answer) 11.sp else 13.sp))
        }
    }
}

/** The only animation while listening: one thin red line whose height follows the microphone level. */
@Composable
private fun Wave(level: Float, still: Boolean) {
    val amp by animateFloatAsState(level.coerceIn(0f, 1f), if (still) snap() else spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow))
    val red = Tk.Red
    Canvas(Modifier.width(160.dp).height(24.dp)) {
        val mid = size.height / 2
        val path = Path()
        val steps = 64
        for (i in 0..steps) {
            val x = size.width * i / steps
            val y = mid + sin(i / steps.toFloat() * 4 * PI).toFloat() * (mid - 2f) * amp
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, red, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
private fun ThinkingRing(still: Boolean) {
    val angle = if (still) 0f else {
        val t = rememberInfiniteTransition()
        t.animateFloat(0f, 360f, infiniteRepeatable(tween(1600, easing = LinearEasing))).value
    }
    val red = Tk.Red
    Canvas(Modifier.fillMaxSize()) {
        val r = size.minDimension / 2 - 1.5.dp.toPx()
        drawCircle(red, r, style = Stroke(1.5.dp.toPx()))
        val a = angle / 180f * PI.toFloat()
        drawCircle(red, 3.dp.toPx(), Offset(center.x + r * cos(a), center.y + r * sin(a)))
    }
}

@Composable
private fun MicGlyph(size: androidx.compose.ui.unit.Dp) {
    val c = Tk.OnRed
    Canvas(Modifier.size(size)) {
        val u = this.size.width / 24f
        val st = Stroke(2f * u, cap = StrokeCap.Round)
        drawRoundRect(c, Offset(9 * u, 3 * u), androidx.compose.ui.geometry.Size(6 * u, 11 * u), androidx.compose.ui.geometry.CornerRadius(3 * u), style = st)
        drawArc(c, 0f, 180f, false, Offset(5 * u, 4 * u), androidx.compose.ui.geometry.Size(14 * u, 14 * u), style = st)
        drawLine(c, Offset(12 * u, 18 * u), Offset(12 * u, 21 * u), 2f * u)
    }
}

@Composable
private fun TypeField(hint: String, autoFocus: Boolean, onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(autoFocus) { if (autoFocus) runCatching { focus.requestFocus() } }
    fun send() { if (text.isNotBlank()) { onSend(text.trim()); text = "" } }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f).heightIn(min = 48.dp).border(1.dp, Tk.Line, Tk.R).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
            if (text.isEmpty()) Text(hint, style = Ty.Body.copy(color = Tk.Ink2))
            BasicTextField(text, { text = it }, textStyle = Ty.Body, singleLine = true, cursorBrush = SolidColor(Tk.Ink),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = "Type a question" })
        }
        if (text.isNotBlank()) Secondary("Send", Modifier.width(72.dp)) { send() }
    }
}

@Composable
fun PendingCard(p: Pending, session: RoutesSession) {
    val canTry = (p.correction as? Correction.AnswerInput)?.value is InputState.Exact
    Column(Modifier.fillMaxWidth().border(1.5.dp, Tk.Ink, Tk.Card).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Heard: ${p.summary}", style = Ty.Strong, modifier = Modifier.weight(1f))
            Text("not saved", style = Ty.Small)
        }
        Text("If this is real: ${changeText(p.before, p.after)}", style = Ty.Body)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (canTry) Secondary("Just trying", Modifier.weight(1f)) { session.tryPending() }
            Primary("That's real, save", Modifier.weight(1f)) { session.confirmPending() }
        }
        LinkText("Cancel") { session.dismissPending() }
    }
}
