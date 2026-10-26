package kr.callbrief

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Activity
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.AccountPicker
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.api.Scope
import kr.callagent.drive.AuthorizationPort

/** Android OAuth registration is identified by package/certificate, not passed as a Web client ID. */
internal interface GoogleConnectActions { fun chooseAccount(); fun refreshExistingNoPrompt(); fun requestDriveConsent(); fun close() }
internal class GoogleAuthorizationSdk(private val activity: ComponentActivity, private val session: GoogleConnectionSession, private val consumer: Consumer) : GoogleConnectActions {
    interface Consumer {
        fun selected(account: String)
        fun authorized(epoch: Long, account: String, token: String, refreshingOwner: String?)
        fun status(code: String)
    }
    private var epoch = -1L
    private var selectedAccount = ""
    private var refreshingOwner: String? = null
    private var interactive = false
    private var closed = false
    private fun alive() = !closed && !activity.isFinishing && !activity.isDestroyed &&
        activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
    private val authorization = Identity.getAuthorizationClient(activity)
    private val picker: ActivityResultLauncher<Intent> = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (!alive()) return@registerForActivityResult
        if (result.resultCode != Activity.RESULT_OK) { session.failed(epoch); consumer.status("ACCOUNT_SELECTION_CANCELLED"); return@registerForActivityResult }
        val name = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
        val type = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_TYPE)
        if (name.isNullOrBlank() || type != "com.google") { session.failed(epoch); consumer.status("GOOGLE_ACCOUNT_UNAVAILABLE"); return@registerForActivityResult }
        try { session.requireEpoch(epoch); selectedAccount = name; consumer.selected(name) }
        catch (_: Exception) { consumer.status("ACCOUNT_SESSION_CHANGED") }
    }
    private val consent = activity.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (!alive()) return@registerForActivityResult
        if (result.resultCode != Activity.RESULT_OK) { session.failed(epoch); consumer.status("DRIVE_CONSENT_CANCELLED"); return@registerForActivityResult }
        try { session.requireEpoch(epoch); accept(authorization.getAuthorizationResultFromIntent(result.data)) }
        catch (_: Exception) { session.failed(epoch); consumer.status("DRIVE_REAUTH_REQUIRED") }
    }
    override fun chooseAccount() {
        if (BuildConfig.GOOGLE_ANDROID_CLIENT_ID.isBlank()) { consumer.status("GOOGLE_CONFIG_MISSING"); return }
        if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(activity)!=ConnectionResult.SUCCESS) { consumer.status("GOOGLE_PLAY_SERVICES_REQUIRED");return }
        epoch = session.begin(); selectedAccount = ""; refreshingOwner = null
        picker.launch(AccountPicker.newChooseAccountIntent(AccountPicker.AccountChooserOptions.Builder().setAllowableAccountsTypes(listOf("com.google")).build()))
    }
    /** Restore existing permission without displaying another login/consent screen automatically. */
    override fun refreshExistingNoPrompt() {
        if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(activity)!=ConnectionResult.SUCCESS) { consumer.status("GOOGLE_PLAY_SERVICES_REQUIRED");return }
        val v = session.view(); if (v.ownerId.isBlank() || v.accountLabel.isBlank()) return
        epoch = session.begin(true); selectedAccount = v.accountLabel; refreshingOwner = v.ownerId; authorize(false)
    }
    override fun requestDriveConsent() {
        if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(activity)!=ConnectionResult.SUCCESS) { consumer.status("GOOGLE_PLAY_SERVICES_REQUIRED");return }
        if (selectedAccount.isBlank()) {
            val v = session.view(); if (v.accountLabel.isBlank()) { consumer.status("CHOOSE_GOOGLE_ACCOUNT_FIRST"); return }
            selectedAccount = v.accountLabel; refreshingOwner = v.ownerId; epoch = session.begin(true)
        }
        authorize(true)
    }
    private fun authorize(allowConsentWindow: Boolean) {
        interactive = allowConsentWindow
        session.requireEpoch(epoch)
        val request = AuthorizationRequest.builder().setAccount(Account(selectedAccount, "com.google"))
            .setRequestedScopes(listOf(Scope(AuthorizationPort.DRIVE_FILE_SCOPE))).setOptOutIncludingGrantedScopes(true).build()
        authorization.authorize(request).addOnSuccessListener { result ->
            if (!alive()) return@addOnSuccessListener
            try {
                session.requireEpoch(epoch)
                if (result.hasResolution()) {
                    session.awaitingConsent(epoch); consumer.status("DRIVE_CONSENT_REQUIRED")
                    if (interactive && !activity.isFinishing && !activity.isDestroyed) consent.launch(IntentSenderRequest.Builder(result.pendingIntent!!.intentSender).build())
                } else accept(result)
            } catch (_: Exception) { session.failed(epoch); consumer.status("DRIVE_REAUTH_REQUIRED") }
        }.addOnFailureListener { if(alive()) { session.failed(epoch); consumer.status("GOOGLE_SDK_CONNECTION_FAILED") } }
    }
    private fun accept(result: AuthorizationResult) {
        session.requireEpoch(epoch)
        check(result.grantedScopes.contains(AuthorizationPort.DRIVE_FILE_SCOPE))
        val token = result.accessToken; check(!token.isNullOrBlank())
        consumer.authorized(epoch, selectedAccount, token, refreshingOwner)
    }
    override fun close() { closed = true; picker.unregister(); consent.unregister() }
}
