package kr.callbrief

import android.content.Context
import android.content.ContextWrapper
import kr.callagent.drive.AuthorizationPort
import kr.callagent.drive.DriveRestTransport
import org.json.JSONObject
import java.security.MessageDigest

/** Only account metadata and a monotonic fence are persisted. Access tokens stay in memory. */
internal class GoogleConnectionSession(private val persistence: Persistence, private val now: () -> Long = System::currentTimeMillis) : DriveRestTransport.TokenPort {
    interface Persistence { fun read(): JSONObject; fun write(value: JSONObject) }
    enum class Phase { SIGNED_OUT, ACCOUNT_SELECTED, CONSENT_REQUIRED, CHECKING_ACCOUNT, ACTIVE, REAUTH_REQUIRED, FAILED }
    data class View(val phase: Phase, val accountLabel: String, val ownerId: String, val epoch: Long) {
        override fun toString() = "GoogleConnection($phase, epoch=$epoch; account omitted)"
    }
    private var saved = persistence.read()
    private var generation = saved.optLong("epoch", 0)
    private var phase = if (saved.optString("ownerId").isBlank()) Phase.SIGNED_OUT else Phase.REAUTH_REQUIRED
    private var token: String? = null
    private var deadline = 0L
    private val cancellations = linkedSetOf<() -> Unit>()
    @Synchronized fun view(): View {
        if (phase == Phase.ACTIVE && now() >= deadline) { token = null; phase = Phase.REAUTH_REQUIRED }
        return View(phase, saved.optString("accountLabel"), saved.optString("ownerId"), generation)
    }
    @Synchronized fun begin(refreshSameOwner: Boolean = false): Long {
        if (refreshSameOwner && saved.optString("ownerId").isNotBlank()) {
            token = null; phase = Phase.ACCOUNT_SELECTED
            cancellations.toList().forEach { runCatching { it() } }; cancellations.clear()
            return generation
        }
        val next = generation + 1
        val update = JSONObject(saved.toString()).put("epoch", next)
        persistence.write(update) // Do not acknowledge the fence before durable save succeeds.
        saved = update; generation = next; token = null; phase = Phase.ACCOUNT_SELECTED
        cancellations.toList().forEach { runCatching { it() } }; cancellations.clear()
        return next
    }
    @Synchronized fun requireEpoch(epoch: Long) { check(generation == epoch) { "ACCOUNT_SESSION_CHANGED" } }
    @Synchronized fun awaitingConsent(epoch: Long) { requireEpoch(epoch); phase = Phase.CONSENT_REQUIRED }
    @Synchronized fun checking(epoch: Long) { requireEpoch(epoch); phase = Phase.CHECKING_ACCOUNT }
    @Synchronized fun activate(epoch: Long, selectedAccount: String, observedAccount: String, ownerPermissionId: String, accessToken: String) {
        requireEpoch(epoch)
        require(selectedAccount.equals(observedAccount, ignoreCase = true)) { "GOOGLE_ACCOUNT_MISMATCH" }
        require(ownerPermissionId.matches(Regex("[A-Za-z0-9_-]{1,256}"))) { "DRIVE_OWNER_UNCONFIRMED" }
        require(accessToken.isNotBlank() && !accessToken.contains('\r') && !accessToken.contains('\n')) { "TOKEN_UNAVAILABLE" }
        val update = JSONObject(saved.toString()).put("epoch", generation).put("accountLabel", observedAccount).put("ownerId", ownerPermissionId)
        if(saved.optString("sessionId").isBlank() || saved.optString("ownerId")!=ownerPermissionId || saved.optLong("authenticatedEpoch",-1)!=generation)
            update.put("sessionId","native_"+java.util.UUID.randomUUID().toString().replace("-",""))
        update.put("authenticatedEpoch",generation).put("ownerEvidence","GOOGLE_AUTHORIZATIONCLIENT_DRIVE_ABOUT").put("backgroundAuthState","READY")
        persistence.write(update)
        saved = update; token = accessToken; phase = Phase.ACTIVE
        deadline = now() + 15 * 60_000 // Conservative local lease, not an asserted Google token expiry.
    }
    @Synchronized fun failed(epoch: Long) { if (generation == epoch) { token = null; phase = Phase.FAILED } }
    @Synchronized fun localSignOut() {
        val next = generation + 1
        val update = JSONObject().put("epoch", next)
        persistence.write(update)
        saved = update; generation = next; token = null; phase = Phase.SIGNED_OUT
        cancellations.toList().forEach { runCatching { it() } }; cancellations.clear()
    }
    @Synchronized fun registerCancellation(epoch: Long, cancel: () -> Unit, pendingAccountCheck: Boolean = false) {
        requireEpoch(epoch); check(view().phase == Phase.ACTIVE || pendingAccountCheck && phase == Phase.CHECKING_ACCOUNT) { "GOOGLE_REAUTH_REQUIRED" }; cancellations.add(cancel)
    }
    @Synchronized fun unregisterCancellation(cancel: () -> Unit) { cancellations.remove(cancel) }
    @Synchronized override fun state() = when (view().phase) {
        Phase.ACTIVE -> AuthorizationPort.State.ACTIVE
        Phase.REAUTH_REQUIRED -> AuthorizationPort.State.TOKEN_EXPIRED
        Phase.SIGNED_OUT -> AuthorizationPort.State.REVOKED
        else -> AuthorizationPort.State.CONSENT_REQUIRED
    }
    @Synchronized override fun ownerBinding() = saved.optString("ownerId")
    @Synchronized override fun driveAccount() = saved.optString("accountLabel")
    @Synchronized override fun driveOwnerPermissionId() = saved.optString("ownerId")
    @Synchronized override fun epoch() = generation
    /** Read-only local fence. A cached owner during account selection is not a confirmed current owner.
     * A persisted previously authenticated owner may read local reports after restart without a Drive token. */
    @Synchronized fun dailyReportScope():View {
        val current=view()
        val local=current.ownerId.isBlank() && current.phase==Phase.SIGNED_OUT
        val confirmed=current.ownerId.isNotBlank() && current.phase in setOf(Phase.ACTIVE,Phase.REAUTH_REQUIRED) &&
            saved.optLong("authenticatedEpoch",-1)==generation && saved.optString("sessionId").isNotBlank() &&
            saved.optString("ownerEvidence")=="GOOGLE_AUTHORIZATIONCLIENT_DRIVE_ABOUT"
        check(local || confirmed) { "DAILY_REPORT_CURRENT_OWNER_UNCONFIRMED" }
        return current
    }
    @Synchronized fun nativeSessionBinding(): JSONObject {
        check(state()==AuthorizationPort.State.ACTIVE && saved.optLong("authenticatedEpoch",-1)==generation && saved.optString("sessionId").isNotBlank()) { "VERIFIED_GOOGLE_SESSION_REQUIRED" }
        return JSONObject().put("sessionId",saved.getString("sessionId")).put("epoch",generation)
            .put("ownerBinding",JSONObject().put("ownerId",ownerBinding()).put("driveAccount",driveAccount()))
    }
    /** Cached identity can address LOCAL approval/state only; it does not authorize network access. */
    @Synchronized fun previouslyVerifiedScope():DriveALocalScope? {
        val v=view()
        if(v.ownerId.isBlank() || v.accountLabel.isBlank() || saved.optLong("authenticatedEpoch",-1)!=generation || saved.optString("sessionId").isBlank() || saved.optString("ownerEvidence")!="GOOGLE_AUTHORIZATIONCLIENT_DRIVE_ABOUT")return null
        if(v.phase==Phase.SIGNED_OUT)return null
        return DriveALocalScope(v.ownerId,v.accountLabel,v.ownerId,saved.getString("sessionId"),generation)
    }
    @Synchronized fun recordRecoveryState(epoch:Long,owner:String,state:String) {
        requireEpoch(epoch);check(saved.optString("ownerId")==owner && previouslyVerifiedScope()!=null) { "RECOVERY_STATUS_SCOPE_CHANGED" }
        check(state in setOf("READY","WAITING_USER_REAUTH","WAITING_GOOGLE_ENVIRONMENT","RETRY_EXISTING_AUTH"))
        val next=JSONObject(saved.toString()).put("backgroundAuthState",state);persistence.write(next);saved=next
    }
    @Synchronized fun recoveryState():String=saved.optString("backgroundAuthState")
    @Synchronized override fun accessToken(): String? = if (state() == AuthorizationPort.State.ACTIVE) token else null
    @Synchronized override fun invalidate(reason: AuthorizationPort.State) {
        if (reason == AuthorizationPort.State.TOKEN_EXPIRED) {
            token = null; phase = Phase.REAUTH_REQUIRED; cancellations.toList().forEach { runCatching { it() } }; cancellations.clear()
        } else { begin(); phase = Phase.SIGNED_OUT }
    }
    /** Result admission/SQLite commit must occur while this same epoch and owner remain active. */
    @Synchronized fun <T> commit(epoch: Long, owner: String, block: () -> T): T {
        requireEpoch(epoch); check(state() == AuthorizationPort.State.ACTIVE && ownerBinding() == owner) { "ACCOUNT_RESULT_BLOCKED" }
        return block()
    }
    companion object {
        @Volatile private var instance: GoogleConnectionSession? = null
        @Synchronized fun get(context: Context): GoogleConnectionSession {
            instance?.let { return it }
            val scoped = object : ContextWrapper(context.applicationContext) {
                override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("eum_connections_v1_" + name, mode)
            }
            val settings = SecureSettings(scoped)
            return GoogleConnectionSession(object : Persistence {
                override fun read() = settings.snapshot()
                override fun write(value: JSONObject) = settings.update { current -> current.keys().asSequence().toList().forEach { current.remove(it) }; value.keys().forEach { current.put(it, value.get(it)) } }
            }).also { instance = it }
        }
        internal fun resetForHost() { instance = null }
        internal fun installForHost(session: GoogleConnectionSession) { check(BuildConfig.DEBUG); instance = session }
        fun scopeKey(owner: String) = MessageDigest.getInstance("SHA-256").digest(("google-drive-owner-v1\n" + owner).toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
