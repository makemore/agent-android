package com.makemore.agentfrontend.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.makemore.agentfrontend.configuration.ChatWidgetConfig
import com.makemore.agentfrontend.models.FileAttachment
import com.makemore.agentfrontend.services.SharedPreferencesStorage
import com.makemore.agentfrontend.voice.VoiceController
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Message input view with text field and send button.
 * Mirrors the iOS InputView struct.
 *
 * Voice features (when [config].enableVoice is true):
 *   • Always-on mic — once the user taps the mic button it stays live
 *     across submits and through agent playback. Tap again to fully
 *     stop. The recognizer is recycled internally on every result/error
 *     so SpeechRecognizer's single-shot model still feels continuous.
 *   • Auto-send (hands-free) — a 3 s silence after the last partial
 *     auto-submits the text; after the agent finishes speaking the mic
 *     re-engages automatically. Toggle via the auto-renew icon; choice
 *     persists across launches.
 *   • Barge-in — while the agent is speaking the recognizer runs in
 *     *monitor* mode: partials are not written to the input field but
 *     diffed against [VoiceController.recentSpokenText]. A partial with
 *     two or more *novel* words triggers [VoiceController.stop],
 *     interrupting the agent. The platform speech recognizer doubles
 *     as the voice-activity detector, avoiding raw RMS thresholds.
 *   • Manual stop — the send button becomes a Stop button while the
 *     agent is speaking, so the user can always interrupt by tapping.
 */
@Composable
fun InputView(
    config: ChatWidgetConfig,
    isLoading: Boolean,
    isAgentSpeaking: Boolean = false,
    voiceController: VoiceController? = null,
    onSend: (String, List<FileAttachment>) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Persisted user toggle (default on).
    val storage = remember { SharedPreferencesStorage(context, prefix = "voice") }
    val autoSendState = remember { mutableStateOf(storage.get("autoSend") != "false") }
    val autoSendEnabled = autoSendState.value

    // Compose-tracked state.
    val inputTextState = remember { mutableStateOf("") }
    val inputText = inputTextState.value
    val canSend = inputText.isNotBlank()
    val isRecordingState = remember { mutableStateOf(false) }
    val isRecording = isRecordingState.value
    var hasAudioPermission by remember { mutableStateOf(false) }
    var lastSendWasMic by remember { mutableStateOf(false) }
    val countdownState = remember { mutableFloatStateOf(0f) }
    val countdownProgress = countdownState.floatValue

    // Refs the listener captures (snapshots state for the recognizer
    // callback thread without forcing recomposition).
    val sessionRef = remember { intArrayOf(0) }
    val activeSessionRef = remember { intArrayOf(0) }
    val monitorModeRef = remember { booleanArrayOf(false) }
    val bargeInFiredRef = remember { booleanArrayOf(false) }
    val recentSpokenRef = remember { arrayOf("") }
    val silenceTimerRef = remember { arrayOfNulls<Job>(1) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasAudioPermission = granted }

    val speechRecognizer = remember { SpeechRecognizer.createSpeechRecognizer(context) }

    fun startListeningInternal() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        sessionRef[0]++
        activeSessionRef[0] = sessionRef[0]
        try { speechRecognizer.startListening(intent) } catch (_: Throwable) {}
    }

    fun cancelSilenceTimer() {
        silenceTimerRef[0]?.cancel()
        silenceTimerRef[0] = null
        countdownState.floatValue = 0f
    }

    fun stopRecordingFully() {
        sessionRef[0]++
        cancelSilenceTimer()
        try { speechRecognizer.cancel() } catch (_: Throwable) {}
        isRecordingState.value = false
        monitorModeRef[0] = false
        bargeInFiredRef[0] = false
    }

    fun doSend() {
        if (!canSend) return
        // Latch the input source so the hands-free loop knows this turn
        // originated from the mic.
        lastSendWasMic = isRecording
        cancelSilenceTimer()
        val textToSend = inputText
        inputTextState.value = ""
        if (isRecording) {
            // Continuous voice mode: keep the recognizer alive, just
            // recycle so the next utterance starts fresh.
            sessionRef[0]++
            try { speechRecognizer.cancel() } catch (_: Throwable) {}
            startListeningInternal()
        }
        onSend(textToSend, emptyList())
    }

    fun triggerBargeIn() {
        if (bargeInFiredRef[0]) return
        bargeInFiredRef[0] = true
        voiceController?.stop()
    }

    fun userStopAgent() {
        bargeInFiredRef[0] = true
        voiceController?.stop()
    }

    fun restartSilenceTimer() {
        silenceTimerRef[0]?.cancel()
        countdownState.floatValue = 1f
        val token = sessionRef[0]
        silenceTimerRef[0] = coroutineScope.launch {
            val tickMs = 100L
            val totalTicks = (silenceTimeoutSeconds * 1000 / tickMs).toInt()
            for (tick in 0 until totalTicks) {
                delay(tickMs)
                if (sessionRef[0] != token) return@launch
                countdownState.floatValue = 1f - (tick + 1).toFloat() / totalTicks
            }
            if (sessionRef[0] != token) return@launch
            if (canSend) doSend() else stopRecordingFully()
        }
    }

    DisposableEffect(Unit) {
        val listener = object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                if (activeSessionRef[0] != sessionRef[0]) return
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!monitorModeRef[0] && !matches.isNullOrEmpty()) {
                    val current = inputTextState.value
                    val newText = if (current.isBlank()) matches[0] else "$current ${matches[0]}"
                    val changed = newText != current
                    inputTextState.value = newText
                    if (autoSendState.value && changed && newText.isNotBlank()) {
                        restartSilenceTimer()
                    }
                }
                // Always-on: restart immediately if still recording so
                // the next utterance is captured without a re-tap.
                if (isRecordingState.value) startListeningInternal()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (activeSessionRef[0] != sessionRef[0]) return
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull().orEmpty()
                if (text.isEmpty()) return
                if (monitorModeRef[0]) {
                    if (bargeInFiredRef[0]) return
                    val novel = novelWordCount(text, recentSpokenRef[0])
                    if (novel >= bargeInNovelWordsRequired) triggerBargeIn()
                } else if (autoSendState.value) {
                    // Use partials only to keep the silence countdown
                    // warm — the final transcription lands via onResults.
                    restartSilenceTimer()
                }
            }

            override fun onError(error: Int) {
                if (activeSessionRef[0] != sessionRef[0]) return
                // Recycle on transient errors so the always-on mic
                // survives "no match", "speech timeout", etc.
                if (isRecordingState.value) startListeningInternal()
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
        speechRecognizer.setRecognitionListener(listener)
        onDispose {
            cancelSilenceTimer()
            try { speechRecognizer.destroy() } catch (_: Throwable) {}
        }
    }

    // Mirror the controller's rolling agent-text buffer into a thread-
    // shared ref so the recognizer callback can read it without
    // touching Compose snapshot state.
    val recentSpokenSnapshot = voiceController?.recentSpokenText?.value ?: ""
    SideEffect { recentSpokenRef[0] = recentSpokenSnapshot }

    // Hands-free transitions: when the agent's TTS playback flips,
    // swap recognizer modes. While speaking → monitor mode (partials
    // checked for novel words). After speaking → main recognition so
    // the next user utterance is transcribed into the input field.
    LaunchedEffect(isAgentSpeaking) {
        if (!isRecordingState.value) return@LaunchedEffect
        if (isAgentSpeaking) {
            monitorModeRef[0] = true
            bargeInFiredRef[0] = false
        } else {
            monitorModeRef[0] = false
        }
        sessionRef[0]++
        try { speechRecognizer.cancel() } catch (_: Throwable) {}
        startListeningInternal()
    }

    // Safety net when TTS is disabled or never produced audio: recycle
    // the recognizer on run completion so we don't get stuck on a
    // stale request.
    LaunchedEffect(isLoading) {
        if (!isLoading && isRecordingState.value && !isAgentSpeaking) {
            sessionRef[0]++
            try { speechRecognizer.cancel() } catch (_: Throwable) {}
            startListeningInternal()
        }
    }

    HorizontalDivider()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // File attachment button
        if (config.enableFiles) {
            IconButton(
                onClick = { /* TODO: file picker */ },
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    Icons.Default.AttachFile,
                    contentDescription = "Attach file",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Voice input button (with countdown ring when auto-send armed).
        if (config.enableVoice) {
            Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                if (autoSendEnabled && isRecording && countdownProgress > 0f) {
                    Canvas(modifier = Modifier.size(28.dp)) {
                        drawArc(
                            color = Color.Red,
                            startAngle = -90f,
                            sweepAngle = 360f * countdownProgress,
                            useCenter = false,
                            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                        )
                    }
                }
                IconButton(
                    onClick = {
                        if (isRecording) {
                            stopRecordingFully()
                        } else {
                            if (!hasAudioPermission) {
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                return@IconButton
                            }
                            isRecordingState.value = true
                            bargeInFiredRef[0] = false
                            monitorModeRef[0] = isAgentSpeaking
                            startListeningInternal()
                        }
                    },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        if (isRecording) Icons.Default.Mic else Icons.Default.MicNone,
                        contentDescription = if (isRecording) "Stop recording" else "Voice input",
                        tint = if (isRecording) Color.Red else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Auto-send (hands-free) toggle.
            IconButton(
                onClick = {
                    val next = !autoSendState.value
                    autoSendState.value = next
                    storage.set("autoSend", if (next) "true" else "false")
                    if (!next) {
                        cancelSilenceTimer()
                        lastSendWasMic = false
                    }
                },
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    Icons.Default.Autorenew,
                    contentDescription = if (autoSendEnabled)
                        "Disable hands-free auto-send"
                    else "Enable hands-free auto-send",
                    tint = if (autoSendEnabled) config.primaryColor
                           else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Text input
        OutlinedTextField(
            value = inputText,
            onValueChange = { inputTextState.value = it },
            placeholder = {
                Text(
                    config.placeholder,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(24.dp),
            maxLines = 5,
            singleLine = false,
            textStyle = MaterialTheme.typography.bodyMedium,
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = Color.Transparent,
                focusedBorderColor = Color.Transparent,
                unfocusedContainerColor = AgentColors.systemGray6,
                focusedContainerColor = AgentColors.systemGray6,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { doSend() }),
        )

        // Send / Cancel-run / Stop-agent button. Three states:
        //  • run in flight (isLoading)        → cancel the run
        //  • agent is speaking (TTS playback) → stop the agent (user
        //                                       barge-in)
        //  • otherwise                        → send the message
        when {
            isLoading -> {
                IconButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFA85D5D))
                ) {
                    Icon(
                        Icons.Default.Stop,
                        contentDescription = "Cancel run",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            isAgentSpeaking -> {
                IconButton(
                    onClick = { userStopAgent() },
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFA85D5D))
                ) {
                    Icon(
                        Icons.Default.Stop,
                        contentDescription = "Stop speaking",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            else -> {
                IconButton(
                    onClick = { doSend() },
                    enabled = canSend,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(if (canSend) config.primaryColor else Color.Gray)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = config.primaryColor.contrastingTextColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

private const val silenceTimeoutSeconds: Double = 3.0

/**
 * Number of *novel* words (not in the agent's recently-spoken text)
 * required in a single monitor partial to count as a barge-in. At
 * least two filters out single-word leak-backs that the recognizer
 * sometimes mis-segments and gives us as a 1-word transcription.
 */
private const val bargeInNovelWordsRequired: Int = 2

/**
 * Number of words in [transcript] not present in [agentText]. Both
 * inputs are lowercased and stripped of non-alphanumerics. Tokens of
 * 2 chars or fewer are excluded — short words ("a", "i", "is") are
 * extremely common in any English transcription and dominate spurious
 * matches.
 */
internal fun novelWordCount(transcript: String, agentText: String): Int {
    val agent = tokenize(agentText).toHashSet()
    var novel = 0
    for (token in tokenize(transcript)) {
        if (token.length > 2 && token !in agent) novel++
    }
    return novel
}

private fun tokenize(text: String): List<String> {
    val sb = StringBuilder(text.length)
    for (ch in text) {
        sb.append(if (ch.isLetterOrDigit() || ch.isWhitespace()) ch.lowercaseChar() else ' ')
    }
    return sb.toString().split(' ').filter { it.isNotEmpty() }
}
