package com.makemore.agentfrontend.networking

/**
 * API errors.
 * Mirrors the iOS APIError enum.
 */
sealed class APIError(message: String, cause: Throwable? = null) : Exception(message, cause)

object InvalidResponse : APIError("Invalid response from server")
object Unauthorized : APIError("Unauthorized - please check your credentials")
object NotFound : APIError("Resource not found")
data class HttpError(val statusCode: Int) : APIError("HTTP error: $statusCode")
data class ServerError(override val message: String) : APIError(message)
object SessionCreationFailed : APIError("Failed to create session")
object CancelFailed : APIError("Failed to cancel run")
data class DecodingError(val underlying: Throwable) : APIError("Failed to decode response: ${underlying.message}", underlying)
data class NetworkError(val underlying: Throwable) : APIError("Network error: ${underlying.message}", underlying)
data class InsecureTransport(val host: String) : APIError(
    "Refusing to send over an insecure (non-HTTPS) connection to $host. " +
        "Use an https:// backend URL, or set allowInsecureHTTP for local development."
)

