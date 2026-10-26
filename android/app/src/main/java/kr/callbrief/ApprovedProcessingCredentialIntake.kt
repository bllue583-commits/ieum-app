package kr.callbrief

import android.content.Context
import android.os.Looper
import kr.callagent.drive.DriveRelay
import kr.callagent.drive.StrictJson
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/** Internal ports only. There is no production issuer, import UI, grant or network adapter. */
internal interface ApprovedProcessingCredentialSource {
    fun existing(scope: ApprovedProcessingIntakeScope): ApprovedProcessingCandidate?
}

internal class ApprovedProcessingCandidate(payload: ByteArray, evidence: ByteArray) {
    private val document = payload.clone()
    private val proof = evidence.clone()
    fun payload() = document.clone()
    fun evidence() = proof.clone()
    override fun toString() = "ApprovedProcessingCandidate[withheld]"
}

internal data class ApprovedProcessingIntakeScope(val native: DriveALocalScope, val policyEpoch: Long, val policySha: String) {
    override fun toString() = "ApprovedProcessingIntakeScope[withheld]"
}

/** A trusted adapter must independently authenticate an allowed issuer/origin and bind its
 * approval to this exact canonical payload and scope. Neither port trusts payload assertions.
 * Verification runs outside application locks. Revocation is a local monotonic latch. */
internal interface ApprovedProcessingApprovalPort {
    fun verify(candidate: ApprovedProcessingCandidate, scope: ApprovedProcessingIntakeScope, canonicalSha: String): ApprovedProcessingApproval?
}

internal class ApprovedProcessingApproval(
    val issuer: String, val origin: String, val approvalId: String,
    val scope: ApprovedProcessingIntakeScope, val canonicalSha: String,
    val validFromMs: Long, val expiresAtMs: Long,
    val usage: String, private val revoked: AtomicBoolean = AtomicBoolean(false)
) {
    fun revoke() { revoked.set(true) }
    fun current(now: Long) = !revoked.get() && validFromMs >= 0 && validFromMs <= now && now < expiresAtMs
    override fun toString() = "ApprovedProcessingApproval[withheld]"
}

internal object UnavailableApprovedProcessingSource : ApprovedProcessingCredentialSource {
    override fun existing(scope: ApprovedProcessingIntakeScope): ApprovedProcessingCandidate? = null
}
internal object UnavailableApprovedProcessingApproval : ApprovedProcessingApprovalPort {
    override fun verify(candidate: ApprovedProcessingCandidate, scope: ApprovedProcessingIntakeScope, canonicalSha: String): ApprovedProcessingApproval? = null
}

internal class ApprovedProcessingIntakeCancellation {
    private val value = AtomicBoolean(false)
    fun cancel() { value.set(true) }
    fun cancelled() = value.get()
}

internal class ApprovedProcessingSetupWriter(
    private val context: Context,
    private val session: GoogleConnectionSession,
    private val source: ApprovedProcessingCredentialSource = UnavailableApprovedProcessingSource,
    private val approvals: ApprovedProcessingApprovalPort = UnavailableApprovedProcessingApproval,
    private val settingsFactory: (Context) -> SecureSettings = { SecureSettings(it) },
    private val now: () -> Long = System::currentTimeMillis,
    private val cancellation: ApprovedProcessingIntakeCancellation = ApprovedProcessingIntakeCancellation()
) {
    enum class State {
        WAITING_PREVIOUS_APPROVED_WORKER_SETUP, WAITING_USER_REAUTH,
        HELD_APPROVAL_REVIEW, HELD_SOURCE_CHANGED, HELD_PROTECTED_STORE, LOCAL_WORK_STOPPED,
        PREVIOUS_APPROVED_WORKER_SETUP_STORED, PREVIOUS_APPROVED_WORKER_SETUP_ALREADY_STORED
    }
    class Outcome(val state: State) {
        override fun toString() = "ApprovedProcessingIntake[$state; connection not applied]"
    }
    private class Held(val state: State) : IllegalStateException(state.name)
    private fun stop() { if (cancellation.cancelled()) throw Held(State.LOCAL_WORK_STOPPED) }

    companion object {
        const val SETUP = "approvedAutomationProcessingSetup"
        const val MARKER = "approvedAutomationProcessingSetupIntakeRequired"
        const val USAGE = "PREVIOUSLY_APPROVED_SYNTHETIC_ONLY"
        private const val MAX_BYTES = 16_384
        private val policyKeys = setOf("personalPhoneDisabled", "personalAliasDisabled", "exclusions", "identities",
            "aliasMappings", "treeUri", "pendingRevocation", "nativeSyntheticApprovals", "automaticDiscoverySelectionRevision")
        fun policy(scope: DriveALocalScope, state: JSONObject): ApprovedProcessingIntakeScope {
            check(!state.optBoolean("pendingRevocation")) { "APPROVED_PROCESSING_POLICY_WITHDRAWN" }
            val values = StrictJson.`object`(StrictJson.parse(state.toString().toByteArray(Charsets.UTF_8)))
            val epoch = values["dailyReportPolicyEpoch"] ?: 0L
            check(epoch is Long && epoch >= 0) { "APPROVED_PROCESSING_POLICY_REVIEW" }
            return ApprovedProcessingIntakeScope(scope, epoch,
                DriveRelay.sha256(StrictJson.bytes(values.filterKeys { it in policyKeys })))
        }
        fun canonicalSetup(bytes: ByteArray, scope: DriveALocalScope): Pair<ByteArray, AutomationApiConfiguration> {
            check(bytes.isNotEmpty() && bytes.size <= MAX_BYTES) { "APPROVED_PROCESSING_SIZE_REVIEW" }
            val x = StrictJson.`object`(StrictJson.parse(bytes))
            check(x.keys == setOf("schema", "approvalState", "scope", "origin", "zone", "credential"))
            check(x["schema"] == "ieum.owner-approved-processing-setup.v1" && x["approvalState"] == USAGE)
            check(x["scope"] == scope.binding())
            check(listOf("origin", "zone", "credential").all { x[it] is String })
            val c = AutomationApiConfiguration(true, x["origin"] as String, scope.owner, scope.account, scope.epoch,
                x["zone"] as String, x["credential"] as String)
            c.validate(scope)
            return StrictJson.bytes(x) to c
        }
    }

    fun receive(): Outcome {
        // The disconnected production path has no storage, scheduler or external side effects.
        if (source === UnavailableApprovedProcessingSource) return Outcome(State.WAITING_PREVIOUS_APPROVED_WORKER_SETUP)
        try {
            stop()
            if (Looper.myLooper() == Looper.getMainLooper()) return Outcome(State.HELD_PROTECTED_STORE)
            val native = DriveALocalScope.current(session) ?: return Outcome(State.WAITING_USER_REAUTH)
            val account = AccountStorageContext(AutomationConnectionBindings.base(context), native.owner)
            val settings = settingsFactory(account)
            val frozen = try { session.commit(native.epoch, native.owner) { policy(native, settings.snapshot()) } }
                catch (_: Exception) { return Outcome(State.HELD_PROTECTED_STORE) }
            val candidate = source.existing(frozen) ?: return Outcome(State.WAITING_PREVIOUS_APPROVED_WORKER_SETUP)
            stop()
            if (!same(frozen, settings)) return Outcome(State.HELD_SOURCE_CHANGED)
            val raw = candidate.payload()
            if (candidate.evidence().size !in 1..MAX_BYTES) return Outcome(State.HELD_APPROVAL_REVIEW)
            val (canonical, configuration) = try { canonicalSetup(raw, native) }
                catch (_: Exception) { return Outcome(State.HELD_APPROVAL_REVIEW) }
            val sha = DriveRelay.sha256(canonical)
            val approval = approvals.verify(candidate, frozen, sha) ?: return Outcome(State.HELD_APPROVAL_REVIEW)
            if (approval.scope != frozen || approval.canonicalSha != sha || approval.origin != configuration.origin ||
                approval.usage != USAGE || !approval.issuer.matches(Regex("[A-Za-z0-9_.:-]{1,128}")) ||
                !approval.approvalId.matches(Regex("[A-Za-z0-9_.:-]{1,128}")) || !approval.current(now()))
                return Outcome(State.HELD_APPROVAL_REVIEW)
            val approvalSha = DriveRelay.sha256(StrictJson.bytes(mapOf("issuer" to approval.issuer,
                "id" to approval.approvalId, "origin" to approval.origin, "payload" to sha,
                "scope" to native.binding(), "policyEpoch" to frozen.policyEpoch, "policySha" to frozen.policySha,
                "validFrom" to approval.validFromMs, "expiresAt" to approval.expiresAtMs, "usage" to approval.usage)))
            return session.commit(native.epoch, native.owner) {
                stop()
                ApprovedProcessingIntakeJournal.openExisting(account, native).use { journal ->
                    journal.guarded(configuration) {
                        val marker = JSONObject().put("schema","ieum.local-approved-processing-intake.v1")
                            .put("scope",JSONObject(String(StrictJson.bytes(native.binding()),Charsets.UTF_8)))
                            .put("payloadSha",sha).put("approvalSha",approvalSha)
                            .put("policyEpoch",frozen.policyEpoch).put("policySha",frozen.policySha)
                            .put("validFrom",approval.validFromMs).put("expiresAt",approval.expiresAtMs)
                        val changed = settings.commitExistingProcessingSetup(JSONObject(String(canonical, Charsets.UTF_8)), marker,
                            validate = { state ->
                                stop()
                                if (DriveALocalScope.current(session) != native || policy(native, state) != frozen)
                                    throw Held(State.HELD_SOURCE_CHANGED)
                                if (!approval.current(now())) throw Held(State.HELD_APPROVAL_REVIEW)
                            },
                            prepare = { state, originalSealed ->
                                journal.prepare(state, originalSealed, frozen, approval, approvalSha, sha, marker)
                            },
                            committed = { journal.committed(sha, approvalSha) })
                        Outcome(if (changed) State.PREVIOUS_APPROVED_WORKER_SETUP_STORED else State.PREVIOUS_APPROVED_WORKER_SETUP_ALREADY_STORED)
                    }
                }
            }
        } catch (h: Held) { return Outcome(h.state) }
        catch (_: Exception) { return Outcome(State.HELD_PROTECTED_STORE) }
    }

    private fun same(frozen: ApprovedProcessingIntakeScope, settings: SecureSettings): Boolean = runCatching {
        session.commit(frozen.native.epoch, frozen.native.owner) {
            DriveALocalScope.current(session) == frozen.native && policy(frozen.native, settings.snapshot()) == frozen
        }
    }.getOrDefault(false)
}
