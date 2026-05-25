package com.makemore.agentfrontend.configuration

import java.util.Calendar

/**
 * Controls the empty-state greeting shown when the conversation has no
 * messages yet (e.g. "Good afternoon, Chris"). Disabled-by-default in
 * the data model so direct consumers of `MessageListView` see no
 * behavioural change unless they opt in via
 * `ChatWidgetConfig.greeting.enabled = true`, but the bundled
 * `ChatWidgetView` enables it by default to match the library's new
 * warm-dark baseline.
 *
 * Mirrors the iOS `ChatGreetingConfig` struct field-for-field.
 */
data class ChatGreetingConfig(
    /** When `true`, the empty state renders the optional brand mark +
     *  serif greeting instead of the legacy speech-bubble icon. */
    val enabled: Boolean = false,
    /** Display name used in the greeting (e.g. "Chris" →
     *  "Good afternoon, Chris"). When `null` or empty the greeting
     *  drops the trailing comma + name and renders only the
     *  time-of-day phrase. Host apps typically wire this to the
     *  signed-in user's first name. */
    val userName: String? = null,
    /** Greeting copy keyed by time of day. The view picks the entry
     *  matching the device's current hour using [lineForHour].
     *  Override individual strings to localise without subclassing
     *  the view. */
    val morningTemplate: String = "Good morning",
    val afternoonTemplate: String = "Good afternoon",
    val eveningTemplate: String = "Good evening",
    val nightTemplate: String = "Good evening",
) {
    /** Returns the greeting line for an hour-of-day in 0..<24,
     *  appending ", \(userName)" when set. The split is the standard
     *  Western "morning until noon / afternoon until 5pm / evening
     *  until 10pm / late night" carve-up. */
    fun lineForHour(hour: Int): String {
        val phase = when (hour) {
            in 5..11 -> morningTemplate
            in 12..16 -> afternoonTemplate
            in 17..21 -> eveningTemplate
            else -> nightTemplate
        }
        val name = userName
        return if (!name.isNullOrEmpty()) "$phase, $name" else phase
    }

    /** Convenience: the greeting line for "now". Pulled into a
     *  separate method so tests can drive a deterministic hour by
     *  calling [lineForHour] directly. */
    fun currentLine(calendar: Calendar = Calendar.getInstance()): String {
        return lineForHour(calendar.get(Calendar.HOUR_OF_DAY))
    }
}
