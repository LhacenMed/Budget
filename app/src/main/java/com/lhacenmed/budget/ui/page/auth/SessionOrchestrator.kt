package com.lhacenmed.budget.ui.page.auth

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.lhacenmed.budget.util.PreferenceUtil
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

enum class AuthGate { Loading, App, Auth }

@Singleton
class SessionOrchestrator @Inject constructor(
    private val supabase: SupabaseClient,
    @ApplicationContext context: Context
) {
    private val scope               = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)

    /**
     * Single source of truth for top-level auth navigation.
     * Eagerly shared so the gate is ready before the first collector.
     */
    val authGate: StateFlow<AuthGate> = combine(
        supabase.auth.sessionStatus,
        PreferenceUtil.authSkipped
    ) { status, skipped ->
        when {
            status is SessionStatus.Authenticated    -> AuthGate.App
            skipped                                  -> AuthGate.App
            status is SessionStatus.Initializing     -> AuthGate.Loading
            status is SessionStatus.NotAuthenticated -> AuthGate.Auth
            status is SessionStatus.RefreshFailure   -> resolveRefreshFailure()
            else                                     -> AuthGate.Auth
        }
    }.stateIn(
        scope        = scope,
        started      = SharingStarted.Eagerly,
        initialValue = AuthGate.Loading
    )

    init {
        watchConnectivityForSessionRecovery()
    }

    fun skipAuth() { PreferenceUtil.setAuthSkipped(true) }

    fun resetSkip() { PreferenceUtil.setAuthSkipped(false) }

    /**
     * Core fix:
     * - Online  + RefreshFailure → refresh token is truly dead → force re-auth.
     * - Offline + RefreshFailure → network caused the failure → stay in app with local data.
     */
    private fun resolveRefreshFailure(): AuthGate =
        if (isNetworkAvailable()) AuthGate.Auth else AuthGate.App

    private fun isNetworkAvailable(): Boolean {
        val caps = connectivityManager
            .getNetworkCapabilities(connectivityManager.activeNetwork ?: return false)
            ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * When connectivity is restored after an offline [SessionStatus.RefreshFailure],
     * proactively refresh the session so the user seamlessly transitions back to
     * [AuthGate.App] without touching any UI.
     *
     * Flow on reconnect:
     * - Refresh succeeds → SDK emits [SessionStatus.Authenticated] → [AuthGate.App] ✓
     * - Refresh fails (token truly dead) → SDK emits [SessionStatus.RefreshFailure]
     *   → [resolveRefreshFailure] now sees network is up → [AuthGate.Auth] ✓
     */
    private fun watchConnectivityForSessionRecovery() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager.registerNetworkCallback(
            request,
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    scope.launch {
                        runCatching {
                            supabase.auth.awaitInitialization()
                            if (supabase.auth.currentSessionOrNull() != null) {
                                supabase.auth.refreshCurrentSession()
                            }
                        }
                        // All outcomes are handled declaratively by the sessionStatus Flow above.
                    }
                }
            }
        )
    }
}
