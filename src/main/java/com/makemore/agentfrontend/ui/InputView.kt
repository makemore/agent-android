package com.makemore.agentfrontend.ui

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

/**
 * Message input view with text field and send button.
 * Mirrors the iOS InputView struct.
 */
@Composable
fun InputView(
    config: ChatWidgetConfig,
    isLoading: Boolean,
    onSend: (String, List<FileAttachment>) -> Unit,
    onCancel: () -> Unit
) {
    var inputText by remember { mutableStateOf("") }
    val canSend = inputText.isNotBlank()
    var isRecording by remember { mutableStateOf(false) }
    // Monotonic token captured by the speech recognition listener so a
    // late onResults() delivered after cancel()/send cannot repopulate the
    // field. SpeechRecognizer.cancel() is documented to suppress further
    // notifications, but in practice a result already queued on the main
    // looper still arrives. The guard makes the suppression unconditional.
    val sessionRef = remember { intArrayOf(0) }
    val activeSessionRef = remember { intArrayOf(0) }
    var hasAudioPermission by remember { mutableStateOf(false) }
    val context = LocalContext.current
    
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasAudioPermission = granted }
    
    val speechRecognizer = remember { SpeechRecognizer.createSpeechRecognizer(context) }
    
    DisposableEffect(Unit) {
        val listener = object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                if (activeSessionRef[0] != sessionRef[0]) {
                    isRecording = false
                    return
                }
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    inputText = if (inputText.isBlank()) matches[0] else "$inputText ${matches[0]}"
                }
                isRecording = false
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    // Show partial result as preview
                }
            }
            override fun onError(error: Int) { isRecording = false }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
        speechRecognizer.setRecognitionListener(listener)
        onDispose { speechRecognizer.destroy() }
    }

    fun doSend() {
        if (canSend) {
            if (isRecording) {
                sessionRef[0]++
                speechRecognizer.cancel()
                isRecording = false
            }
            onSend(inputText, emptyList())
            inputText = ""
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

        // Voice input button
        if (config.enableVoice) {
            IconButton(
                onClick = {
                    if (isRecording) {
                        // Don't bump session here: stopListening() asks the engine
                        // to deliver any pending result via onResults, which is the
                        // user's intent when they tap the mic button. Send/destroy
                        // bump the session because the user no longer wants the result.
                        speechRecognizer.stopListening()
                        isRecording = false
                    } else {
                        if (!hasAudioPermission) {
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            return@IconButton
                        }
                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                        }
                        sessionRef[0]++
                        activeSessionRef[0] = sessionRef[0]
                        speechRecognizer.startListening(intent)
                        isRecording = true
                    }
                },
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    if (isRecording) Icons.Default.MicOff else Icons.Default.Mic,
                    contentDescription = if (isRecording) "Stop recording" else "Voice input",
                    tint = if (isRecording) Color.Red else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Text input
        OutlinedTextField(
            value = inputText,
            onValueChange = { inputText = it },
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

        // Send/Cancel button
        if (isLoading) {
            IconButton(
                onClick = onCancel,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.Red)
            ) {
                Icon(
                    Icons.Default.Stop,
                    contentDescription = "Cancel",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        } else {
            IconButton(
                onClick = ::doSend,
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
