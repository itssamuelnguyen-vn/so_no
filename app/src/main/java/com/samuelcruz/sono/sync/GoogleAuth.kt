package com.samuelcruz.sono.sync

import android.accounts.Account
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Google sign-in for Drive through Google Play services (the phone's own Google accounts, no password typed in
 * the app). Only the `drive.appdata` permission is asked: the app can read and write its own hidden folder and
 * nothing else in the user's Drive.
 *
 * Needs an "Android" OAuth client in Google Cloud for this package name + the SHA-1 of the signing key
 * (README, "Google Drive sync"); without it the sign-in returns an error.
 */
object GoogleAuth {
    private val scopes = listOf(Scope("https://www.googleapis.com/auth/drive.appdata"))

    sealed interface Result {
        data class Token(val value: String) : Result
        /** The user must pick an account / agree on a Google screen: start this with an activity. */
        data class NeedsUi(val intent: PendingIntent) : Result
    }

    private fun request(email: String?) = AuthorizationRequest.builder()
        .setRequestedScopes(scopes)
        .apply { if (!email.isNullOrBlank()) setAccount(Account(email, "com.google")) }
        .build()

    suspend fun authorize(context: Context, email: String?): Result {
        val r = Identity.getAuthorizationClient(context).authorize(request(email)).await()
        val pending = r.pendingIntent
        return if (r.hasResolution() && pending != null) Result.NeedsUi(pending)
        else Result.Token(r.accessToken ?: error("No access token"))
    }

    /** Token without any screen, for background sync; null if the user has to sign in again. */
    suspend fun silentToken(context: Context, email: String?): String? =
        (authorize(context, email) as? Result.Token)?.value

    /** Reads the result of the Google screen started for [Result.NeedsUi]. */
    fun tokenFromIntent(context: Context, data: Intent?): String =
        Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data).accessToken
            ?: error("No access token")

    /** Forget a token Drive rejected (401), so the next [authorize] fetches a fresh one. */
    suspend fun clearToken(context: Context, token: String) {
        runCatching {
            Identity.getAuthorizationClient(context).clearToken(ClearTokenRequest.builder().setToken(token).build()).await()
        }
    }

    /** Disconnect: withdraw the app's Drive permission for [email], so connecting again shows the account picker. */
    suspend fun revoke(context: Context, email: String) {
        if (email.isBlank()) return
        runCatching {
            Identity.getAuthorizationClient(context).revokeAccess(
                RevokeAccessRequest.builder().setAccount(Account(email, "com.google")).setScopes(scopes).build(),
            ).await()
        }
    }
}

/** Waits for a Play services Task without blocking (like `await` on a promise). */
suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
