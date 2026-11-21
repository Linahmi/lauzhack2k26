package ch.ripple.app.assistant

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.ripple.app.components.PrimaryButton
import ch.ripple.core.InputState
import ch.ripple.core.SampleData
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class VoiceStage { Idle, Listening, Processing, Review, Error }

@Composable
fun AssistantScreen(
    speechInput: SpeechInput,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
    speechOutput: SpeechOutput = SilentOutput,
    twin: FinancialTwinController,
    onBack: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    var language by rememberSaveable { mutableStateOf("en") }
    var muted by rememberSaveable { mutableStateOf(true) }
    var goalAccepted by rememberSaveable { mutableStateOf(false) }
    var showInsight by rememberSaveable { mutableStateOf(false) }
    var structuredEntry by rememberSaveable { mutableStateOf(false) }
    var amountText by rememberSaveable { mutableStateOf("") }
    var lastSpoken by rememberSaveable { mutableStateOf<String?>(null) }
    var showOptions by remember { mutableStateOf(false) }
    var pendingDemo by remember { mutableStateOf(false) }
    var unsupportedGoal by remember { mutableStateOf(false) }
    var stage by remember { mutableStateOf(if (text.isBlank()) VoiceStage.Idle else VoiceStage.Review) }
    var error by remember { mutableStateOf<String?>(null) }
    var inputError by remember { mutableStateOf<String?>(null) }
    var partial by remember { mutableStateOf("") }
    var captureId by remember { mutableIntStateOf(0) }
    var elapsedSeconds by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val french = language == "fr"
    val briefing = twin.briefing
    val inputState = speechInput.state
    val outputState = speechOutput.state
    val captureBusy = stage == VoiceStage.Listening || stage == VoiceStage.Processing
    val busy = captureBusy || twin.busy
    fun copy(en: String, fr: String) = if (french) fr else en
    val response = when {
        !goalAccepted -> copy("What decision would you like to explore?", "Quelle décision souhaitez-vous explorer ?")
        briefing == null -> copy("Preparing the fictional moving-home example.", "Préparation de l'exemple fictif de déménagement.")
        showInsight -> BriefingPresenter.insight(briefing, french)
        else -> BriefingPresenter.question(briefing, french)
            ?: copy("What should the refundable deposit amount be? You can also say: I'm not sure.", "Quel est le montant de la garantie de loyer ? Vous pouvez aussi répondre : je ne sais pas.")
    }
    val qualifications = if (goalAccepted && briefing != null)
        listOfNotNull(BriefingPresenter.bounds(briefing, french)) + BriefingPresenter.qualifications(briefing, french) else emptyList()
    val spokenResponse = (listOf(response) + qualifications).joinToString(" ")

    fun stopCapture() {
        captureId++
        speechInput.cancel()
        partial = ""
        stage = if (text.isBlank()) VoiceStage.Idle else VoiceStage.Review
    }
    fun editWords(value: String) {
        text = value
        twin.discardPending()
        pendingDemo = false
        unsupportedGoal = false
        inputError = null
        error = null
        stage = if (value.isBlank()) VoiceStage.Idle else VoiceStage.Review
    }
    fun startCapture() {
        if (twin.busy) return
        speechOutput.stop()
        stopCapture()
        speechInput.setLanguage(language)
        twin.discardPending()
        pendingDemo = false
        unsupportedGoal = false
        elapsedSeconds = 0
        error = null
        if (!speechInput.state.canRecord) {
            error = copy("Voice needs setup for this language. You can type instead.", "La reconnaissance vocale doit être configurée. Vous pouvez écrire.")
            stage = VoiceStage.Error
            return
        }
        val token = captureId
        speechInput.start { event ->
            if (token == captureId) when (event) {
                SpeechEvent.Preparing, SpeechEvent.Processing -> stage = VoiceStage.Processing
                SpeechEvent.Listening -> stage = VoiceStage.Listening
                is SpeechEvent.Partial -> partial = event.text
                is SpeechEvent.Result -> { partial = ""; editWords(event.text) }
                is SpeechEvent.Failure -> { partial = ""; error = event.message; stage = VoiceStage.Error }
            }
        }
    }
    fun playResponse() {
        stopCapture()
        muted = false
        lastSpoken = "$language:$spokenResponse"
        speechOutput.speak(spokenResponse, language)
    }
    fun propose(value: InputState) {
        speechOutput.stop()
        stopCapture()
        focusManager.clearFocus()
        keyboard?.hide()
        inputError = null
        scope.launch {
            if (!twin.proposeDeposit(value)) inputError = copy("Please wait for the example to load, then review again.", "Attendez le chargement de l'exemple, puis vérifiez à nouveau.")
            scroll.scrollTo(0)
        }
    }
    fun reviewWords() {
        speechOutput.stop()
        focusManager.clearFocus()
        keyboard?.hide()
        if (!goalAccepted) {
            pendingDemo = MovingDemoIntent.supports(text)
            unsupportedGoal = !pendingDemo
            scope.launch { scroll.scrollTo(0) }
        } else {
            val value = DepositInput.parse(text, language)
            if (value == null) {
                structuredEntry = true
                amountText = ""
                inputError = copy("I haven't inferred an amount. Please enter just the deposit amount, or choose ‘I'm not sure’.", "Aucun montant n'a été déduit. Saisissez uniquement la garantie de loyer, ou choisissez « Je ne sais pas ».")
            } else propose(value)
        }
    }

    LaunchedEffect(stage) {
        if (stage == VoiceStage.Listening) {
            val began = kotlin.time.TimeSource.Monotonic.markNow()
            while (true) { delay(250); elapsedSeconds = began.elapsedNow().inWholeSeconds.toInt() }
        }
    }
    LaunchedEffect(language) { speechInput.setLanguage(language) }
    LaunchedEffect(spokenResponse, muted, outputState.languages, twin.busy) {
        val key = "$language:$spokenResponse"
        if (!muted && !captureBusy && !twin.busy && language in outputState.languages && lastSpoken != key) {
            lastSpoken = key
            stopCapture()
            speechOutput.speak(spokenResponse, language)
        }
    }
    DisposableEffect(speechInput, speechOutput) {
        onDispose { captureId++; speechInput.cancel(); speechOutput.stop(); twin.discardPending() }
    }

    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val minHeight = maxHeight
        val showCaptureControls = !pendingDemo && !unsupportedGoal && twin.pending == null && !showInsight
        val controlsHeight = if (showCaptureControls) 132.dp else 0.dp
        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(bottom = controlsHeight)
            .verticalScroll(scroll).heightIn(min = (minHeight - controlsHeight).coerceAtLeast(0.dp))
            .padding(horizontal = 28.dp, vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton({ stopCapture(); speechOutput.stop(); twin.discardPending(); onBack() }, Modifier.size(48.dp)) {
                        Text("‹", fontSize = 30.sp, modifier = Modifier.semantics { contentDescription = "Back" })
                    }
                    TextButton({ showOptions = true }, Modifier.size(48.dp)) {
                        Text("···", fontSize = 26.sp, modifier = Modifier.semantics { contentDescription = "Voice and language options" })
                    }
                }
                Spacer(Modifier.height(28.dp))
                Text(copy("Tell me about your goals", "Parlez-moi de vos projets"), style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(20.dp))
            }
            when {
                pendingDemo || unsupportedGoal -> {
                    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        Text(copy("Your reviewed words", "Vos mots vérifiés"), style = MaterialTheme.typography.titleMedium)
                        Text(text, style = MaterialTheme.typography.bodyLarge)
                        if (unsupportedGoal) Text(copy("I haven't interpreted this request. I can guide you through one fictional moving-home example.", "Cette demande n'a pas été interprétée. Je peux vous guider dans un exemple fictif de déménagement."))
                        else Text(copy("Interpreted intent: explore the fictional moving-home demo.", "Intention proposée : explorer l'exemple fictif de déménagement."))
                        val profile = SampleData.alexSession.profile
                        Text(copy("This uses ${profile.name}'s fictional ${profile.horizonMonths}-month scenario and ${profile.openingCash.format()} starting cash. Your words do not set any financial inputs. This is not a retirement model.",
                            "Cet exemple fictif de ${profile.name} dure ${profile.horizonMonths} mois, avec ${profile.openingCash.format()} au départ. Vos mots ne définissent aucun montant. Ce n'est pas un modèle de retraite."), style = MaterialTheme.typography.bodySmall)
                        if (unsupportedGoal) PrimaryButton(copy("Use fictional moving demo", "Choisir l'exemple fictif"), { unsupportedGoal = false; pendingDemo = true }, Modifier.fillMaxWidth())
                        else PrimaryButton(copy("Confirm demo", "Confirmer l'exemple"), {
                            goalAccepted = true; pendingDemo = false; text = ""; inputError = null
                            showInsight = briefing?.followUp == null
                            stage = VoiceStage.Idle; elapsedSeconds = 0
                            scope.launch { scroll.scrollTo(0) }
                        }, Modifier.fillMaxWidth(), enabled = !twin.busy && briefing != null)
                        OutlinedButton({ pendingDemo = false; unsupportedGoal = false }, Modifier.heightIn(min = 56.dp)) { Text(copy("Edit words", "Modifier mes mots")) }
                    }
                }
                twin.pending != null -> {
                    val proposal = twin.pending!!
                    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        Text(copy("Confirm this financial input", "Confirmez cette donnée"), style = MaterialTheme.typography.headlineMedium)
                        Text(DepositInput.label(proposal.value, french), style = MaterialTheme.typography.bodyLarge)
                        Text(copy("Only this deposit answer is being confirmed. No age or other amounts have been inferred from your words.", "Seule cette réponse sur la garantie de loyer sera confirmée. Aucun âge ni autre montant n'a été déduit."), style = MaterialTheme.typography.bodySmall)
                        PrimaryButton(copy("Confirm amount", "Confirmer le montant"), {
                            scope.launch {
                                if (twin.confirmDeposit()) {
                                    showInsight = true; structuredEntry = false; text = ""; amountText = ""
                                    stage = VoiceStage.Idle; scroll.scrollTo(0)
                                }
                            }
                        }, Modifier.fillMaxWidth(), enabled = !twin.busy)
                        OutlinedButton({ twin.discardPending() }, Modifier.heightIn(min = 56.dp), enabled = !twin.busy) { Text(copy("Edit answer", "Modifier la réponse")) }
                    }
                }
                goalAccepted && showInsight && briefing != null -> {
                    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        Text(copy("What the model found", "Ce que montre le modèle"), style = MaterialTheme.typography.headlineMedium)
                        Text(response, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        Text(qualifications.joinToString(" "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton({ playResponse() }, Modifier.heightIn(min = 56.dp), enabled = !busy && language in outputState.languages) { Text(copy("Read insight aloud", "Lire le résultat")) }
                        PrimaryButton(copy("Change deposit answer", "Modifier la garantie de loyer"), {
                            showInsight = false; structuredEntry = false; text = ""; amountText = ""
                            speechOutput.stop(); stage = VoiceStage.Idle
                        }, Modifier.fillMaxWidth())
                    }
                }
                else -> {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (goalAccepted) {
                            Text(response, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                            TextButton({ playResponse() }, Modifier.heightIn(min = 48.dp), enabled = !busy && language in outputState.languages) { Text(copy("Read question aloud", "Lire la question")) }
                        }
                        MicrophoneControl(stage == VoiceStage.Listening, stage == VoiceStage.Processing, speechInput.available && !twin.busy,
                            reduceMotion, speaking = outputState.speaking, onClick = {
                                if (stage == VoiceStage.Listening) { stage = VoiceStage.Processing; speechInput.stop() } else startCapture()
                            })
                        Spacer(Modifier.height(16.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("●", color = if (stage == VoiceStage.Listening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, fontSize = 12.sp)
                            Text("${elapsedSeconds / 60}:${(elapsedSeconds % 60).toString().padStart(2, '0')}", style = MaterialTheme.typography.bodyLarge)
                        }
                        val status = when {
                            outputState.speaking -> copy("Speaking", "Lecture à voix haute")
                            twin.busy -> copy("Processing the example…", "Calcul de l'exemple…")
                            stage == VoiceStage.Idle -> copy("Ready when you are", "À votre rythme")
                            stage == VoiceStage.Listening -> copy("Listening", "À l'écoute")
                            stage == VoiceStage.Processing -> copy("Processing…", "Traitement…")
                            stage == VoiceStage.Review -> copy("Review your words", "Vérifiez vos mots")
                            else -> copy("Voice needs a little help", "La voix a besoin d'aide")
                        }
                        Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        if (stage == VoiceStage.Error) {
                            Text(if (french) "Vos mots n'ont pas été reconnus. Vous pouvez écrire." else error ?: "We couldn't capture your words. You can type instead.", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                TextButton({ if (inputState.canRecord) startCapture() else showOptions = true }, Modifier.heightIn(min = 56.dp)) { Text(if (inputState.canRecord) copy("Retry", "Réessayer") else copy("Set up voice", "Configurer la voix")) }
                                TextButton({ stopCapture(); error = null; focus.requestFocus(); keyboard?.show() }, Modifier.heightIn(min = 56.dp)) { Text(copy("Type instead", "Écrire plutôt")) }
                            }
                        }
                        Spacer(Modifier.height(28.dp))
                        Text(if (text.isBlank()) copy("VOICE OR TEXT", "PARLER OU ÉCRIRE") else copy("YOUR WORDS · TAP TO EDIT", "VOS MOTS · MODIFIER"), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        BasicTextField(text, { editWords(it) }, Modifier.fillMaxWidth().heightIn(min = 100.dp).focusRequester(focus)
                            .semantics { contentDescription = "Your words. Type or edit your plans" }, enabled = !busy, minLines = 3, maxLines = 5,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurface),
                            decorationBox = { field -> Box(Modifier.fillMaxWidth()) {
                                if (text.isBlank()) Text(copy("Share a plan, at your own pace.\nTap here to type instead.", "Parlez d'un projet, à votre rythme.\nTouchez ici pour écrire."),
                                    modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                field()
                            } })
                        if (partial.isNotBlank()) Text(copy("Hearing: $partial", "En cours : $partial"), style = MaterialTheme.typography.bodySmall)
                        if (structuredEntry) {
                            inputError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                            OutlinedTextField(amountText, { amountText = it; twin.discardPending() }, Modifier.fillMaxWidth(),
                                label = { Text(copy("Deposit amount only (CHF)", "Garantie de loyer uniquement (CHF)")) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, enabled = !busy)
                            PrimaryButton(copy("Review amount", "Vérifier le montant"), {
                                val value = DepositInput.parse(amountText, language)
                                if (value != null) propose(value) else inputError = copy("Enter one non-negative CHF amount, with no other information.", "Saisissez un seul montant CHF positif ou nul, sans autre information.")
                            }, Modifier.fillMaxWidth().padding(top = 12.dp), enabled = !busy && amountText.isNotBlank())
                        }
                        if (goalAccepted) {
                            TextButton({ propose(InputState.Unknown) }, Modifier.heightIn(min = 56.dp), enabled = !busy) { Text(copy("I'm not sure", "Je ne sais pas")) }
                            Text(qualifications.joinToString(" "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (!showCaptureControls) Spacer(Modifier.height(28.dp))
            twin.error?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.error)
                if (briefing == null) TextButton({ scope.launch { twin.initialize() } }) { Text("Retry calculation") }
            }
        }
        if (showCaptureControls) {
                Row(Modifier.align(Alignment.BottomCenter).widthIn(max = 560.dp).fillMaxWidth()
                    .height(controlsHeight).padding(horizontal = 28.dp, vertical = 18.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    AssistantAction("Cancel", "×", false, true) { stopCapture(); speechOutput.stop(); error = null; twin.discardPending() }
                    AssistantAction(if (stage == VoiceStage.Listening) "Stop" else "Record", if (stage == VoiceStage.Listening) "■" else "●", true,
                        speechInput.available && !twin.busy && stage != VoiceStage.Processing) {
                        if (stage == VoiceStage.Listening) { stage = VoiceStage.Processing; speechInput.stop() } else startCapture()
                    }
                    AssistantAction("Confirm", "✓", false, !busy && text.any(Char::isLetterOrDigit)) { reviewWords() }
                }
        }
    }
    if (showOptions) SpeechOptionsDialog(speechInput, speechOutput, language, muted, busy,
        onLanguage = { stopCapture(); speechOutput.stop(); language = it; twin.discardPending(); speechInput.setLanguage(it) },
        onMute = { muted = !muted; if (muted) speechOutput.stop() else lastSpoken = null },
        onReplay = { playResponse() }, onDismiss = { showOptions = false })
}

@Composable
private fun AssistantAction(label: String, symbol: String, primary: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(onClick, Modifier.size(72.dp).semantics { contentDescription = label }, enabled = enabled,
            shape = androidx.compose.foundation.shape.CircleShape, contentPadding = PaddingValues(0.dp),
            colors = if (primary) ButtonDefaults.buttonColors() else ButtonDefaults.buttonColors(
                containerColor = androidx.compose.ui.graphics.Color(0xFFF1EAE4), contentColor = MaterialTheme.colorScheme.onSurface)) {
            Text(symbol, fontSize = 30.sp)
        }
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}
