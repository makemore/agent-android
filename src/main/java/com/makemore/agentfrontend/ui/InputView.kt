package com.makemore.agentfrontend.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowDown
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.makemore.agentfrontend.configuration.ChatAppearance
import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.makemore.agentfrontend.configuration.ChatWidgetConfig
import com.makemore.agentfrontend.models.FileAttachment
import com.makemore.agentfrontend.services.SharedPreferencesStorage
import com.makemore.agentfrontend.viewmodels.ChatViewModel
import com.makemore.agentfrontend.voice.VoiceController
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

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
    viewModel: ChatViewModel? = null,
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
    // Files staged by the user via the AddToChatSheet (camera) before
    // hitting send. They flow through with the next turn and reset.
    val attachedFiles = remember { mutableStateListOf<FileAttachment>() }
    val canSend = inputText.isNotBlank() || attachedFiles.isNotEmpty()
    val isRecordingState = remember { mutableStateOf(false) }
    val isRecording = isRecordingState.value
    var hasAudioPermission by remember { mutableStateOf(false) }
    var lastSendWasMic by remember { mutableStateOf(false) }
    val countdownState = remember { mutableFloatStateOf(0f) }
    val countdownProgress = countdownState.floatValue
    // Drives the AddToChatSheet presentation. Tapping `+` (anthropic)
    // or the paperclip (classic) flips this on; the sheet itself
    // dismisses by clearing the flag.
    var showAddToChat by remember { mutableStateOf(false) }

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

    // Camera capture — TakePicturePreview returns a thumbnail Bitmap
    // which we re-encode as JPEG and stage as a FileAttachment so it
    // travels through the same upload pipeline as picker-sourced files.
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap: Bitmap? ->
        val attachment = bitmap?.let { makeAttachment(it) }
        if (attachment != null) attachedFiles.add(attachment)
    }

    // System document picker. OpenMultipleDocuments is permission-free
    // (the picker UI runs in a separate process and returns content://
    // URIs the host can read). We immediately materialise each URI to
    // bytes so the staged FileAttachment is self-contained and travels
    // with the next createRun multipart request.
    val documentPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        uris.forEach { uri ->
            readDocumentAttachment(context, uri)?.let { attachedFiles.add(it) }
        }
    }

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
        val filesToSend = attachedFiles.toList()
        inputTextState.value = ""
        attachedFiles.clear()
        if (isRecording) {
            // Continuous voice mode: keep the recognizer alive, just
            // recycle so the next utterance starts fresh.
            sessionRef[0]++
            try { speechRecognizer.cancel() } catch (_: Throwable) {}
            startListeningInternal()
        }
        onSend(textToSend, filesToSend)
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

    // Right-action button is shared across both composer styles. Three
    // states: cancel (run in flight), stop (TTS playing), send.
    val rightActionButton: @Composable () -> Unit = {
        RightActionButton(
            isLoading = isLoading,
            isAgentSpeaking = isAgentSpeaking,
            canSend = canSend,
            accent = config.appearance.accent,
            textOnAccent = config.appearance.textOnAccent,
            onCancel = onCancel,
            onStopAgent = { userStopAgent() },
            onSend = { doSend() },
        )
    }

    Column {
        if (attachedFiles.isNotEmpty()) {
            AttachedFilesRow(
                config = config,
                files = attachedFiles,
                onRemove = { f -> attachedFiles.removeAll { it.id == f.id } },
            )
        }
        when (config.appearance.composerStyle) {
            ChatAppearance.ComposerStyle.ANTHROPIC -> {
                AnthropicComposer(
                    config = config,
                    inputText = inputText,
                    onInputChange = { inputTextState.value = it },
                    onAddToChat = { showAddToChat = true },
                    onSend = { doSend() },
                    voiceEnabled = config.enableVoice,
                    isRecording = isRecording,
                    autoSendEnabled = autoSendEnabled,
                    countdownProgress = countdownProgress,
                    onToggleAutoSend = {
                        val next = !autoSendState.value
                        autoSendState.value = next
                        storage.set("autoSend", if (next) "true" else "false")
                        if (!next) {
                            cancelSilenceTimer()
                            lastSendWasMic = false
                        }
                    },
                    onToggleRecording = {
                        if (isRecording) {
                            stopRecordingFully()
                        } else {
                            if (!hasAudioPermission) {
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            } else {
                                isRecordingState.value = true
                                bargeInFiredRef[0] = false
                                monitorModeRef[0] = isAgentSpeaking
                                startListeningInternal()
                            }
                        }
                    },
                    rightActionButton = rightActionButton,
                )
            }
            ChatAppearance.ComposerStyle.CLASSIC -> {
                ClassicComposer(
                    config = config,
                    inputText = inputText,
                    onInputChange = { inputTextState.value = it },
                    onSend = { doSend() },
                    onAddToChat = { showAddToChat = true },
                    voiceEnabled = config.enableVoice,
                    isRecording = isRecording,
                    autoSendEnabled = autoSendEnabled,
                    countdownProgress = countdownProgress,
                    onToggleAutoSend = {
                        val next = !autoSendState.value
                        autoSendState.value = next
                        storage.set("autoSend", if (next) "true" else "false")
                        if (!next) {
                            cancelSilenceTimer()
                            lastSendWasMic = false
                        }
                    },
                    onToggleRecording = {
                        if (isRecording) {
                            stopRecordingFully()
                        } else {
                            if (!hasAudioPermission) {
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            } else {
                                isRecordingState.value = true
                                bargeInFiredRef[0] = false
                                monitorModeRef[0] = isAgentSpeaking
                                startListeningInternal()
                            }
                        }
                    },
                    rightActionButton = rightActionButton,
                )
            }
        }
    }

    if (showAddToChat) {
        AddToChatSheet(
            config = config,
            onAddFiles = {
                showAddToChat = false
                // "*/*" lets any MIME through; the picker UI still
                // honours per-app type filters callers might set later.
                documentPickerLauncher.launch(arrayOf("*/*"))
            },
            onDismiss = { showAddToChat = false },
            viewModel = viewModel,
            onCaptureImage = { cameraLauncher.launch(null) },
        )
    }
}

/** Re-encode a camera-preview [Bitmap] as JPEG @ 0.9 quality and wrap
 *  it as a [FileAttachment] so it flows through the same upload
 *  pipeline as files picked from the document browser. */
private fun makeAttachment(bitmap: Bitmap): FileAttachment? {
    val stream = ByteArrayOutputStream()
    val ok = bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
    if (!ok) return null
    val bytes = stream.toByteArray()
    val name = "camera-${System.currentTimeMillis() / 1000}.jpg"
    return FileAttachment(name = name, size = bytes.size, type = "image/jpeg", data = bytes)
}

/** Resolve a SAF [Uri] returned by the system document picker to a
 *  self-contained [FileAttachment]. Display name + size are looked up
 *  via [OpenableColumns]; mime type falls back to the resolver's type
 *  when the cursor row is sparse. Bytes are read eagerly because the
 *  Uri's read permission only lives for the activity result lifetime,
 *  and the attachment may be deferred behind several UI ticks before
 *  it's actually uploaded. */
private fun readDocumentAttachment(context: Context, uri: Uri): FileAttachment? {
    val resolver: ContentResolver = context.contentResolver
    val mime = resolver.getType(uri) ?: "application/octet-stream"
    var displayName: String? = null
    var declaredSize: Int? = null
    runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0 && !cursor.isNull(nameIdx)) displayName = cursor.getString(nameIdx)
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) declaredSize = cursor.getLong(sizeIdx).toInt()
                }
            }
    }
    val bytes = runCatching {
        resolver.openInputStream(uri)?.use { it.readBytes() }
    }.getOrNull() ?: return null
    val name = displayName ?: uri.lastPathSegment?.substringAfterLast('/') ?: "attachment"
    return FileAttachment(
        name = name,
        size = declaredSize ?: bytes.size,
        type = mime,
        data = bytes,
    )
}

/** Horizontally scrolling chip row previewing files the user has
 *  staged for the next turn. Each chip carries a remove affordance. */
@Composable
private fun AttachedFilesRow(
    config: ChatWidgetConfig,
    files: List<FileAttachment>,
    onRemove: (FileAttachment) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        files.forEach { file ->
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(config.appearance.surface)
                    .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.InsertDriveFile,
                    contentDescription = null,
                    tint = config.appearance.textSecondary,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(modifier = Modifier.size(6.dp))
                Text(
                    text = file.name,
                    color = config.appearance.textPrimary,
                    fontSize = 12.sp,
                    maxLines = 1,
                )
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .clickable { onRemove(file) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = "Remove",
                        tint = config.appearance.textSecondary,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ClassicComposer(
    config: ChatWidgetConfig,
    inputText: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onAddToChat: () -> Unit,
    voiceEnabled: Boolean,
    isRecording: Boolean,
    autoSendEnabled: Boolean,
    countdownProgress: Float,
    onToggleAutoSend: () -> Unit,
    onToggleRecording: () -> Unit,
    rightActionButton: @Composable () -> Unit,
) {
    HorizontalDivider()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // File attachment button — routes through the AddToChatSheet
        // so classic hosts get the same attach/connectors picker as
        // anthropic. Hosts without `enableFiles` keep the slimmer row.
        if (config.enableFiles) {
            IconButton(
                onClick = onAddToChat,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    Icons.Default.AttachFile,
                    contentDescription = "Add to chat",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Voice input button (with countdown ring when auto-send armed).
        if (voiceEnabled) {
            MicButton(
                isRecording = isRecording,
                autoSendEnabled = autoSendEnabled,
                countdownProgress = countdownProgress,
                onClick = onToggleRecording,
            )
            AutoSendToggle(
                autoSendEnabled = autoSendEnabled,
                accent = config.primaryColor,
                onClick = onToggleAutoSend,
            )
        }

        // Text input
        OutlinedTextField(
            value = inputText,
            onValueChange = onInputChange,
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
            keyboardActions = KeyboardActions(onSend = { onSend() }),
        )

        rightActionButton()
    }
}

/**
 * Warm-dark composer: a rounded card containing the text field on top
 * and a horizontal control row underneath. Mirrors the iOS
 * `anthropicComposer` layout: `+` attach button, optional model pill,
 * voice controls aligned right, and the shared right-action button.
 */
@Composable
private fun AnthropicComposer(
    config: ChatWidgetConfig,
    inputText: String,
    onInputChange: (String) -> Unit,
    onAddToChat: () -> Unit,
    onSend: () -> Unit,
    voiceEnabled: Boolean,
    isRecording: Boolean,
    autoSendEnabled: Boolean,
    countdownProgress: Float,
    onToggleAutoSend: () -> Unit,
    onToggleRecording: () -> Unit,
    rightActionButton: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(config.appearance.background)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(config.appearance.composerCornerRadius))
                .background(config.appearance.surface),
        ) {
            BasicTextField(
                value = inputText,
                onValueChange = onInputChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = config.appearance.textPrimary,
                ),
                cursorBrush = SolidColor(config.appearance.accent),
                maxLines = 6,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                decorationBox = { inner ->
                    if (inputText.isEmpty()) {
                        Text(
                            config.placeholder,
                            color = config.appearance.textSecondary,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    inner()
                },
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (config.enableFiles) {
                    CircularIconButton(
                        icon = Icons.Outlined.Add,
                        contentDescription = "Add to chat",
                        tint = config.appearance.textSecondary,
                        onClick = onAddToChat,
                    )
                }
                config.appearance.modelPillLabel?.takeIf { it.isNotEmpty() }?.let { label ->
                    ModelPill(
                        label = label,
                        appearance = config.appearance,
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                if (voiceEnabled) {
                    AutoSendToggle(
                        autoSendEnabled = autoSendEnabled,
                        accent = config.appearance.accent,
                        onClick = onToggleAutoSend,
                    )
                    MicButton(
                        isRecording = isRecording,
                        autoSendEnabled = autoSendEnabled,
                        countdownProgress = countdownProgress,
                        onClick = onToggleRecording,
                    )
                }
                rightActionButton()
            }
        }
    }
}

/** Send / Cancel-run / Stop-agent button shared by both composer
 *  styles. Sizing and corner radius are constant across styles so the
 *  muscle memory of "tap bottom-right" is preserved. */
@Composable
private fun RightActionButton(
    isLoading: Boolean,
    isAgentSpeaking: Boolean,
    canSend: Boolean,
    accent: Color,
    textOnAccent: Color,
    onCancel: () -> Unit,
    onStopAgent: () -> Unit,
    onSend: () -> Unit,
) {
    when {
        isLoading -> StopButton(label = "Cancel run", onClick = onCancel)
        isAgentSpeaking -> StopButton(label = "Stop speaking", onClick = onStopAgent)
        else -> {
            IconButton(
                onClick = onSend,
                enabled = canSend,
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(if (canSend) accent else Color.Gray),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = if (canSend) textOnAccent else Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun StopButton(label: String, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Color(0xFFA85D5D)),
    ) {
        Icon(
            Icons.Default.Stop,
            contentDescription = label,
            tint = Color.White,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** Mic button with an optional countdown ring drawn around it when
 *  the hands-free silence timer is armed. */
@Composable
private fun MicButton(
    isRecording: Boolean,
    autoSendEnabled: Boolean,
    countdownProgress: Float,
    onClick: () -> Unit,
) {
    Box(modifier = Modifier.size(36.dp), contentAlignment = Alignment.Center) {
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
        IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
            Icon(
                if (isRecording) Icons.Default.Mic else Icons.Default.MicNone,
                contentDescription = if (isRecording) "Stop recording" else "Voice input",
                tint = if (isRecording) Color.Red else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AutoSendToggle(
    autoSendEnabled: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
        Icon(
            Icons.Default.Autorenew,
            contentDescription = if (autoSendEnabled)
                "Disable hands-free auto-send"
            else "Enable hands-free auto-send",
            tint = if (autoSendEnabled) accent else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Compact circular icon button used for the `+` (attach) affordance
 *  on the Anthropic composer's bottom row. */
@Composable
private fun CircularIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
        Icon(icon, contentDescription = contentDescription, tint = tint)
    }
}

/** Pill button used for the model name. Visual only — the action is a
 *  no-op until a host wires it to a model picker. Capsule with text
 *  and a downward chevron. */
@Composable
private fun ModelPill(label: String, appearance: ChatAppearance) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(appearance.surfaceElevated)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = label,
            color = appearance.textPrimary,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
        )
        Icon(
            Icons.Outlined.KeyboardArrowDown,
            contentDescription = null,
            tint = appearance.textPrimary,
            modifier = Modifier.size(14.dp),
        )
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
