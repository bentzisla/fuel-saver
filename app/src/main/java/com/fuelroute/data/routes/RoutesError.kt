package com.fuelroute.data.routes

import com.fuelroute.domain.model.Route
import java.io.IOException
import kotlinx.serialization.SerializationException
import retrofit2.HttpException

/** Thrown by [RoutesMapper] when the API response cannot be parsed. Never silently defaulted. */
class RoutesParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * User-facing error taxonomy for route computation. The UI maps each case to a Hebrew
 * message + suggested action; raw exception messages never reach the UI.
 */
sealed class RoutesError : Exception() {

    /** No connectivity / DNS / socket failure. */
    object NoNetwork : RoutesError()

    /** HTTP 429 — the Routes SKU quota was exceeded. */
    object Quota : RoutesError()

    /** HTTP 403 — the API key is not allowed for this package/cert or API. */
    object Forbidden : RoutesError()

    /** The API returned no route. */
    object NoRoute : RoutesError()

    /** Only one route was returned, so there is nothing to compare. */
    object SingleRouteOnly : RoutesError()

    /** HTTP 4xx that is not otherwise classified. [detail] is technical, for logs only. */
    data class Invalid(val detail: String) : RoutesError()

    /** The response was malformed / could not be parsed. */
    data class Parse(val error: Throwable) : RoutesError()

    /** Anything else. */
    data class Unknown(val error: Throwable) : RoutesError()

    companion object {

        fun from(t: Throwable): RoutesError = when (t) {
            is RoutesError -> t
            is RoutesParseException -> Parse(t)
            is HttpException -> when (t.code()) {
                429 -> Quota
                403 -> Forbidden
                400 -> Invalid("HTTP 400")
                404 -> NoRoute
                in 400..499 -> Invalid("HTTP ${t.code()}")
                in 500..599 -> Unknown(t)
                else -> Unknown(t)
            }
            is IOException -> NoNetwork
            is SerializationException -> Parse(t)
            else -> Unknown(t)
        }

        /** Maps a returned route list to a notice: none, one, or [null] when there is a real choice. */
        fun fromRoutes(routes: List<Route>): RoutesError? = when (routes.size) {
            0 -> NoRoute
            1 -> SingleRouteOnly
            else -> null
        }
    }
}
