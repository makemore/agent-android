package com.makemore.agentfrontend.networking

/** No response body, URL, token, or transcript is included in transport errors. */
sealed class SSEFailure(message: String, val retryable: Boolean) : Exception(message) {
    data class Authentication(val status: Int) : SSEFailure("Stream authentication rejected ($status)", false)
    data class Http(val status: Int) : SSEFailure("Stream HTTP error ($status)", status == 408 || status == 429 || status >= 500)
    data object ContentType : SSEFailure("Expected an event stream", false)
    data object Malformed : SSEFailure("Invalid or oversized event stream", false)
    data object UnexpectedEof : SSEFailure("Stream ended before run completion", true)
    data object Network : SSEFailure("Stream connection interrupted", true)
    data object ConnectionTimeout : SSEFailure("Stream connection timed out", true)
    data object IdleTimeout : SSEFailure("Stream idle timeout", true)
    data object OverallTimeout : SSEFailure("Stream overall timeout", true)
}