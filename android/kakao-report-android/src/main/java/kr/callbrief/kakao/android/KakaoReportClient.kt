package kr.callbrief.kakao.android

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import kr.callbrief.kakao.core.*
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Host calls sendFinalized explicitly when approved. No Worker, alarm, service, or scheduled automation is installed. */
class KakaoReportClient private constructor(context: Context,val config: KakaoConfiguration) {
    private val store=PrivateKakaoStore(context,DeliveryCoordinator.sha256(context.noBackupFilesDir.absolutePath+"\n"+config.nativeAppKey))
    private val consent=LocalReportConsent(store)
    private val tokens=EncryptedKakaoTokenManager(store)
    private val sdk=KakaoSdkSessionProvider(context,config,tokens)
    private val ledger=SqliteDeliveryLedger(context)
    private val executor=Executors.newSingleThreadExecutor()
    private val main=Handler(Looper.getMainLooper())
    private val active=AtomicBoolean(false)
    private var confirmation:Pair<VerifiedSession,Long>?=null
    class PendingDelivery internal constructor(private val cancelled:AtomicBoolean) {
        /** Stops a future POST admission; bytes already posted cannot be recalled. */
        fun cancel(){cancelled.set(true)}
    }
    companion object {
        const val UNVERIFIED_LABEL="06시 목표 보고 · 실제 카카오 수신 미검증"
        private var singleton: KakaoReportClient?=null
        private var singletonScope=""
        @Synchronized fun get(context: Context): KakaoReportClient {
            val config=KakaoConfiguration.fromResources(context)
            val scope=context.applicationContext.noBackupFilesDir.absolutePath
            singleton?.let {
                check(it.config==config)
                if(singletonScope==scope)return it
                check(!it.config.canLogin) { "KAKAO_ACCOUNT_SWITCH_REAUTH_REQUIRED" }
            }
            singletonScope=scope
            return KakaoReportClient(context.applicationContext,config).also { singleton=it }
        }
    }
    fun localOptIn(): Boolean = try { consent.snapshot().let{it.enabled && it.policyVersion==DeliveryCoordinator.POLICY_VERSION} } catch(e: Exception) { false }
    fun disableReports() { synchronized(this){confirmation=null};consent.disable() }
    /** Stops the local sender. Does not revoke a Kakao grant or change account settings on the server. */
    fun disconnectLocally() { synchronized(this){confirmation=null};consent.disable();tokens.clear() }
    fun connect(activity: Activity,appUserKey: String,ownerStillCurrent:()->Boolean,callback: (VerifiedSession?,DeliveryResult?) -> Unit) {
        require(appUserKey.matches(Regex("[A-Za-z0-9_-]{8,128}")))
        if(!config.canLogin) { callback(null,DeliveryResult(DeliveryResult.Status.CONFIG_REQUIRED)); return }
        if(!active.compareAndSet(false,true)) { callback(null,DeliveryResult(DeliveryResult.Status.IN_FLIGHT)); return }
        try {
            check(ownerStillCurrent()) { "owner_changed" }
            synchronized(this){confirmation=null}
            val generation=consent.beginConnection()
            sdk.login(activity) { session,problem ->
                try {
                    if(!ownerStillCurrent() || consent.epoch()!=generation) {
                        callback(null,DeliveryResult(DeliveryResult.Status.ACCOUNT_MISMATCH))
                    } else {
                        if(session!=null){consent.connected(appUserKey,session,generation);synchronized(this){confirmation=session to generation}}
                        callback(session,problem)
                    }
                } catch(e: Exception) { callback(null,DeliveryResult(DeliveryResult.Status.STORAGE_FAILURE)) }
                finally { active.set(false) }
            }
        } catch(e: Exception) { active.set(false); callback(null,DeliveryResult(DeliveryResult.Status.STORAGE_FAILURE)) }
    }
    fun confirmOptIn(appUserKey:String,session:VerifiedSession,ownerStillCurrent:()->Boolean) {
        check(ownerStillCurrent()) { "owner_changed" }
        val verified=synchronized(this){confirmation}
        check(verified?.first===session) { "fresh_connection_confirmation_required" }
        consent.enable(appUserKey,session,verified!!.second)
        synchronized(this){confirmation=null}
    }
    /** Automatic caller: first local opt-in calendar day onward only. A caller
     * explicitly selecting a prior stored report uses sendFinalized, not this path. */
    fun sendAutomaticFinalized(report:DailyReport,ownerStillCurrent:()->Boolean,callback:(DeliveryResult)->Unit):PendingDelivery {
        try {
            if(!consent.automaticDateAllowed(report.appUserKey,report.date,report.zone)) {
                main.post{callback(DeliveryResult.priorReportHeld())};return PendingDelivery(AtomicBoolean(false))
            }
        }catch(_:Exception){main.post{callback(DeliveryResult(DeliveryResult.Status.STORAGE_FAILURE))};return PendingDelivery(AtomicBoolean(false))}
        return sendFinalized(report,{ownerStillCurrent() && consent.automaticDateAllowed(report.appUserKey,report.date,report.zone)},callback)
    }
    /** Legacy caller has no owner epoch/cancellation proof: remain held. */
    fun sendFinalized(report:DailyReport,callback:(DeliveryResult)->Unit) = sendFinalized(report,{false},callback)
    fun sendFinalized(report:DailyReport,ownerStillCurrent:()->Boolean,callback:(DeliveryResult)->Unit):PendingDelivery {
        val cancelled=AtomicBoolean(false);val pending=PendingDelivery(cancelled)
        val completed=AtomicBoolean(false)
        fun done(result:DeliveryResult){if(completed.compareAndSet(false,true)){active.set(false);main.post{callback(result)}}}
        fun current()=!cancelled.get() && runCatching{ownerStillCurrent()}.getOrDefault(false)
        if(!config.canLogin || !config.liveSendEnabled){main.post{callback(DeliveryResult(DeliveryResult.Status.DISABLED))};return pending}
        if(!report.finalized){main.post{callback(DeliveryResult(DeliveryResult.Status.NOT_FINALIZED))};return pending}
        if(!current()){main.post{callback(DeliveryResult(DeliveryResult.Status.ACCOUNT_MISMATCH))};return pending}
        val admitted=try{consent.snapshot()}catch(_:Exception){main.post{callback(DeliveryResult(DeliveryResult.Status.STORAGE_FAILURE))};return pending}
        if(!admitted.enabled || admitted.appUserKey!=report.appUserKey){main.post{callback(DeliveryResult(DeliveryResult.Status.LOCAL_CONSENT_REQUIRED))};return pending}
        if(!active.compareAndSet(false,true)){main.post{callback(DeliveryResult(DeliveryResult.Status.IN_FLIGHT))};return pending}
        try { sdk.ready { session,problem ->
            if(!current()){done(DeliveryResult(DeliveryResult.Status.ACCOUNT_MISMATCH));return@ready}
            if(problem!=null || session==null){done(problem ?: DeliveryResult(DeliveryResult.Status.AUTH_REQUIRED));return@ready}
            try { executor.execute {
                try {
                    if(!current() || consent.epoch()!=admitted.generation){done(DeliveryResult(DeliveryResult.Status.LOCAL_CONSENT_REQUIRED));return@execute}
                    val result=DeliveryCoordinator(ledger,MemoRestTransport.live()).send(DeliveryCoordinator.Mode.LIVE_APPROVED,
                        report,session,{consent.snapshot()},config.publicLandingUrl,{Instant.now()},{current() && consent.epoch()==admitted.generation})
                    done(result)
                }catch(_:Exception){done(DeliveryResult(DeliveryResult.Status.STORAGE_FAILURE))}
            }}catch(_:Exception){done(DeliveryResult(DeliveryResult.Status.STORAGE_FAILURE))}
        }}catch(_:Exception){done(DeliveryResult(DeliveryResult.Status.CONNECTION_CHECK_FAILED))}
        return pending
    }
}
