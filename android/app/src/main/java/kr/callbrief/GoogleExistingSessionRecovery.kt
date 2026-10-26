package kr.callbrief

import android.accounts.Account
import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.Scope
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.tasks.Tasks
import kr.callagent.drive.AuthorizationPort
import java.util.concurrent.TimeUnit

/** A result can request user action but never opens a chooser/consent/resolution or creates a grant. */
internal class ExistingAuthorizationResult(val resolutionRequired:Boolean,val opaqueToken:String?=null) {
    override fun toString()="ExistingAuthorizationResult[credential omitted]"
}
internal interface ExistingGoogleAuthorization {
    fun existingOnly(account:String):ExistingAuthorizationResult
    fun verifyOwner(epoch:Long,account:String,token:String):GoogleDriveHttp.Owner
}
internal class GoogleAuthorizationEnvironmentMissing:IllegalStateException("GOOGLE_PLAY_SERVICES_REQUIRED")
internal class NativeExistingGoogleAuthorization(private val context:Context,private val session:GoogleConnectionSession):ExistingGoogleAuthorization {
    override fun existingOnly(account:String):ExistingAuthorizationResult {
        if(GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)!=ConnectionResult.SUCCESS)throw GoogleAuthorizationEnvironmentMissing()
        val request=AuthorizationRequest.builder().setAccount(Account(account,"com.google"))
            .setRequestedScopes(listOf(Scope(AuthorizationPort.DRIVE_FILE_SCOPE))).setOptOutIncludingGrantedScopes(true).build()
        val result=try{Tasks.await(Identity.getAuthorizationClient(context).authorize(request),25,TimeUnit.SECONDS)}catch(e:java.util.concurrent.ExecutionException){
            val status=(e.cause as? ApiException)?.statusCode
            if(status==CommonStatusCodes.SIGN_IN_REQUIRED || status==CommonStatusCodes.RESOLUTION_REQUIRED)return ExistingAuthorizationResult(true)
            throw e
        }
        if(result.hasResolution())return ExistingAuthorizationResult(true) // Never launch PendingIntent from Worker.
        if(!result.grantedScopes.contains(AuthorizationPort.DRIVE_FILE_SCOPE) || result.accessToken.isNullOrBlank())return ExistingAuthorizationResult(true)
        return ExistingAuthorizationResult(false,result.accessToken)
    }
    override fun verifyOwner(epoch:Long,account:String,token:String)=GoogleDriveHttp(session).verifySelectedOwner(epoch,account,token)
}
internal data class ExistingSessionRecoveryResult(val state:String,val reason:String="") {
    val ready:Boolean get()=state=="READY"
    val retryable:Boolean get()=state=="RETRY_EXISTING_AUTH"
}
/** Serializes quiet refreshes; retains owner/epoch/session ID and never renews source leases. */
internal class GoogleExistingSessionRecovery(private val session:GoogleConnectionSession,private val authorization:ExistingGoogleAuthorization) {
    fun recover(owner:String,epoch:Long,scopeKey:String?=null,stopped:()->Boolean={false}):ExistingSessionRecoveryResult=synchronized(lock) {
        fun matches()=session.ownerBinding()==owner && session.epoch()==epoch
        fun <T> fenced(action:()->T):T=synchronized(session) {
            session.requireEpoch(epoch);check(session.ownerBinding()==owner) { "ACCOUNT_SESSION_CHANGED" };action()
        }
        fun result(state:String,reason:String=""):ExistingSessionRecoveryResult=synchronized(session) {
            if(!matches())return@synchronized ExistingSessionRecoveryResult("STALE_NATIVE_SCOPE")
            if(state!="STALE_NATIVE_SCOPE" && state!="LOCAL_WORK_STOPPED")session.recordRecoveryState(epoch,owner,state)
            ExistingSessionRecoveryResult(state,reason)
        }
        if(stopped())return@synchronized ExistingSessionRecoveryResult("LOCAL_WORK_STOPPED")
        if(session.ownerBinding()!=owner || session.epoch()!=epoch)return@synchronized ExistingSessionRecoveryResult("STALE_NATIVE_SCOPE")
        val saved=session.previouslyVerifiedScope() ?: return@synchronized ExistingSessionRecoveryResult("WAITING_USER_REAUTH","NO_PREVIOUS_VERIFIED_ACCOUNT")
        if(saved.owner!=owner || saved.epoch!=epoch || scopeKey!=null && saved.key!=scopeKey)return@synchronized ExistingSessionRecoveryResult("STALE_NATIVE_SCOPE")
        val current=session.view()
        if(current.phase==GoogleConnectionSession.Phase.ACTIVE)return@synchronized result("READY")
        if(current.phase==GoogleConnectionSession.Phase.CONSENT_REQUIRED)return@synchronized result("WAITING_USER_REAUTH","SDK_RESOLUTION_REQUIRED")
        if(current.phase !in setOf(GoogleConnectionSession.Phase.REAUTH_REQUIRED,GoogleConnectionSession.Phase.FAILED))return@synchronized result("WAITING_USER_REAUTH","INTERACTIVE_ACCOUNT_SELECTION_NOT_CONFIRMED")
        var candidate:ExistingAuthorizationResult?=null
        try {
            fenced {session.begin(true)};candidate=authorization.existingOnly(saved.account)
            fenced {}
            if(stopped()) {fenced {session.invalidate(AuthorizationPort.State.TOKEN_EXPIRED)};return@synchronized ExistingSessionRecoveryResult("LOCAL_WORK_STOPPED")}
            if(candidate.resolutionRequired) {return@synchronized fenced {session.awaitingConsent(epoch);result("WAITING_USER_REAUTH","SDK_RESOLUTION_REQUIRED")}}
            val token=checkNotNull(candidate.opaqueToken){"EXISTING_DRIVE_GRANT_UNAVAILABLE"}
            val observed=authorization.verifyOwner(epoch,saved.account,token)
            fenced {
                if(observed.permissionId!=owner || !observed.account.equals(saved.account,ignoreCase=true)) {session.failed(epoch);result("WAITING_USER_REAUTH","EXISTING_OWNER_RECHECK_MISMATCH")}
                else if(stopped()) {session.invalidate(AuthorizationPort.State.TOKEN_EXPIRED);ExistingSessionRecoveryResult("LOCAL_WORK_STOPPED")}
                else {
                    session.activate(epoch,saved.account,observed.account,observed.permissionId,token)
                    check(DriveALocalScope.current(session)?.key==saved.key) { "NATIVE_SCOPE_RECHECK_CHANGED" }
                    result("READY")
                }
            }
        }catch(_:GoogleAuthorizationEnvironmentMissing){synchronized(session){
            if(!matches())ExistingSessionRecoveryResult("STALE_NATIVE_SCOPE")
            else {session.invalidate(AuthorizationPort.State.TOKEN_EXPIRED);result("WAITING_GOOGLE_ENVIRONMENT","GOOGLE_PLAY_SERVICES_REQUIRED")}
        }}
        catch(_:Exception){
            synchronized(session){
                if(!matches())ExistingSessionRecoveryResult("STALE_NATIVE_SCOPE")
                else {session.invalidate(AuthorizationPort.State.TOKEN_EXPIRED);result("RETRY_EXISTING_AUTH","EXISTING_AUTH_RECHECK_NOT_CONFIRMED")}
            }
        }finally{candidate=null}
    }
    companion object {private val lock=Any()}
}
internal object ExistingGoogleSessionRecoveryRuntime {
    @Volatile private var hostFactory:((Context,GoogleConnectionSession)->ExistingGoogleAuthorization)?=null
    internal fun installForHost(factory:((Context,GoogleConnectionSession)->ExistingGoogleAuthorization)?){check(BuildConfig.DEBUG);hostFactory=factory}
    fun recover(context:Context,session:GoogleConnectionSession,owner:String,epoch:Long,scopeKey:String?=null,stopped:()->Boolean={false})=
        GoogleExistingSessionRecovery(session,hostFactory?.invoke(context,session) ?: NativeExistingGoogleAuthorization(context,session)).recover(owner,epoch,scopeKey,stopped)
    fun messageKo(state:String)=when(state){
        "WAITING_USER_REAUTH"->"기존 Google 접근을 다시 확인해 주세요. 연결 확인 전에는 결과를 받지 않아요."
        "WAITING_GOOGLE_ENVIRONMENT"->"Google Play 서비스가 있는 기존 기기에서 접근을 확인해 주세요. 결과 수신은 대기 중이에요."
        "RETRY_EXISTING_AUTH"->"기존 접근을 재확인하지 못했어요. 저장된 요청을 유지하며 확인을 기다려요."
        "STALE_NATIVE_SCOPE"->"계정이나 연결 범위가 바뀌었어요. 이전 결과 작업을 중단했어요."
        else->""
    }
}
