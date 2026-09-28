package com.fuelroute.ui.messages

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/** A short message meant for the shared Snackbar: a plain string, or a string resource with format args. */
sealed interface UiText {
    data class Plain(val text: String) : UiText
    data class Resource(@StringRes val resId: Int, val args: List<Any> = emptyList()) : UiText
}

/** Resolves [UiText] to a display string; needs a [Context] for the [UiText.Resource] case. */
fun UiText.resolve(context: Context): String = when (this) {
    is UiText.Plain -> text
    is UiText.Resource -> context.getString(resId, *args.toTypedArray())
}

/**
 * App-wide replacement for scattering `Toast.makeText(...).show()` across screens. Any screen
 * posts a message here; the single `SnackbarHost` hosted at the nav-host `Scaffold`
 * ([com.fuelroute.nav.FuelRouteNavHost]) shows it, so feedback looks the same everywhere instead
 * of Toasts in some screens and a snackbar-style pattern in others (History).
 */
@Singleton
class UserMessages @Inject constructor() {
    private val _messages = MutableSharedFlow<UiText>(extraBufferCapacity = 8)
    val messages: SharedFlow<UiText> = _messages.asSharedFlow()

    fun show(text: String) {
        _messages.tryEmit(UiText.Plain(text))
    }

    fun show(@StringRes resId: Int, vararg args: Any) {
        _messages.tryEmit(UiText.Resource(resId, args.toList()))
    }
}

/** Hilt access to the app-wide message channel from composables that have no ViewModel for it. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface MessagesEntryPoint {
    fun userMessages(): UserMessages
}

@Composable
fun rememberUserMessages(): UserMessages {
    val context = LocalContext.current
    return remember(context) {
        EntryPointAccessors.fromApplication(context.applicationContext, MessagesEntryPoint::class.java)
            .userMessages()
    }
}
