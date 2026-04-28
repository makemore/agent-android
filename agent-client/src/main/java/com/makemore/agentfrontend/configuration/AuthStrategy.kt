package com.makemore.agentfrontend.configuration

/**
 * Authentication strategy for API requests.
 * Mirrors the iOS AuthStrategy enum.
 */
enum class AuthStrategy {
    /** Token authentication (Django REST Framework style). Sends: Authorization: Token {token} */
    TOKEN,

    /** JWT/Bearer authentication. Sends: Authorization: Bearer {token} */
    JWT,

    /** Session-based authentication (cookies). Relies on session cookies, no auth header */
    SESSION,

    /** Anonymous session tokens. Fetches token from endpoint, sends: X-Anonymous-Token: {token} */
    ANONYMOUS,

    /** No authentication */
    NONE;

    /** Default header name for this strategy */
    val defaultHeader: String
        get() = when (this) {
            TOKEN, JWT -> "Authorization"
            ANONYMOUS -> "X-Anonymous-Token"
            SESSION, NONE -> ""
        }

    /** Default token prefix for this strategy */
    val defaultPrefix: String
        get() = when (this) {
            TOKEN -> "Token"
            JWT -> "Bearer"
            ANONYMOUS, SESSION, NONE -> ""
        }
}

/**
 * API case style for request/response transformation.
 */
enum class APICaseStyle {
    /** Backend uses camelCase */
    CAMEL,

    /** Backend uses snake_case */
    SNAKE,

    /** Accept both in responses, send snake_case in requests */
    AUTO
}

