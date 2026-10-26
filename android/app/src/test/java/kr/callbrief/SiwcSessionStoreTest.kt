package kr.callbrief

import kr.callagent.drive.StrictJson
import kr.co.callsummary.core.android.ProtectedDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.IOException

/** Fake OAuth server and test RSA key; actual protected DB, session CAS and rotation behavior. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SiwcSessionStoreTest:ProtectedTextHostTestBase() {
    private val app get()=RuntimeEnvironment.getApplication() as android.content.Context
    private val f=SiwcAuthFixture();private var clock=f.now
    private inner class Port:SiwcAuthTransport {
        var calls=0;var revokes=0;var metadataCalls=0;var fail:Exception?=null;var revokeOk=true;var last=emptyMap<String,String>();var onRevoke:()->Unit={};var onJwks:()->Unit={}
        var reply:(Map<String,String>)->ByteArray={error("FIXTURE_NOT_PREPARED")}
        override fun metadata()=f.metadata.also{metadataCalls++}
        override fun jwks(metadata:SiwcOAuthProtocol.Metadata)=f.jwks.also{onJwks()}
        override fun token(form:Map<String,String>):ByteArray {calls++;last=form;fail?.let{throw it};return reply(form)}
        override fun revoke(metadata:SiwcOAuthProtocol.Metadata,clientId:String,refreshToken:String):Boolean {revokes++;onRevoke();return revokeOk}
    }
    private val port=Port()
    private fun store()=SiwcSessionStore(app,port,{clock})
    private fun begin(s:SiwcSessionStore,client:String?="fixture-client",selected:String?=null)=s.begin(SiwcOAuthConfig("PUBLIC_LOCAL_FIXTURE",client,client==null),"http://127.0.0.1:1455/auth/callback",selected,true)
    private fun finish(s:SiwcSessionStore,p:SiwcOAuthProtocol.Pending,client:String=p.config.issuedClientId ?: "fixture-new-client",subject:String="fixture-subject",
        scopes:Set<String> = p.requestedScopes):SiwcSessionStore.Registration {
        port.reply={f.tokens(p,clock,client,subject,scopes)};return s.finish(p,p.callback(f.callback(p,client),clock),f.metadata)
    }
    private fun fails(block:()->Unit){assertNotNull(runCatching(block).exceptionOrNull())}
    @Test fun configMissingDoesNotPrepareOrContactOAuth()=runBlocking(Dispatchers.IO) {store().use{s->fails{s.begin(null,"http://127.0.0.1:1455/auth/callback",null,true)};assertTrue(s.activeKey().isEmpty());assertEquals(0,port.calls)}}
    @Test fun hostIdentityAndEncryptedTokensSurviveRestartAndGrantObjectIsStable()=runBlocking(Dispatchers.IO) {
        var host="";var key="";var scope=""
        store().use{s->host=s.hostId();key=finish(s,begin(s)).key;scope=s.runtimeScope("owner")
            assertSame(s.inferenceGrant("owner",setOf("a")),s.inferenceGrant("owner",setOf("a")))
            assertFalse(ProtectedDatabase.file(app,"siwc-auth-v1.db").readText().contains("PUBLIC_ACCESS_NOT_A_REAL"))}
        store().use{s->assertEquals(host,s.hostId());assertEquals(key,s.activeKey());assertEquals(scope,s.runtimeScope("owner"));assertNotNull(s.inferenceGrant("owner",emptySet()))};assertEquals(1,port.calls)
    }
    @Test fun identityWithoutPlanScopeNeverEnablesInference()=runBlocking(Dispatchers.IO) {store().use{s->finish(s,begin(s),scopes=SiwcOAuthProtocol.IDENTITY);assertTrue(s.activeKey().isNotEmpty());assertNull(s.inferenceGrant("owner",emptySet()))}}
    @Test fun wrongReturningSubjectLeavesExistingAccountAndEpochUntouched()=runBlocking(Dispatchers.IO) {store().use{s->val key=finish(s,begin(s)).key;val epoch=s.epoch()
        fails{finish(s,begin(s,selected=key),subject="other-subject")};assertEquals(key,s.activeKey());assertEquals(epoch,s.epoch());assertNotNull(s.inferenceGrant("owner",emptySet()));assertTrue(s.incompleteRegistrationIds().isEmpty())}}
    @Test fun equalEmailDifferentRegistrationsRemainSeparateAndSwitchFencesRuntimeScope()=runBlocking(Dispatchers.IO) {store().use{s->val a=finish(s,begin(s)).key;val old=s.runtimeScope("owner")
        val b=finish(s,begin(s,"fixture-other-client")).key;assertNotEquals(a,b);assertEquals(2,s.registrations().size);assertEquals(1,s.registrations().map{it.email}.toSet().size)
        assertNotEquals(old,s.runtimeScope("owner"));assertEquals(b,s.activeKey())}}
    @Test fun cancelledOrSupersededLoginCannotReplaceActiveAccount()=runBlocking(Dispatchers.IO) {store().use{s->val key=finish(s,begin(s)).key;val p=begin(s,"fixture-other-client");s.cancel(p)
        fails{finish(s,p)};assertEquals(key,s.activeKey());val a=begin(s);begin(s);fails{finish(s,a)};assertEquals(1,port.calls)}}
    @Test fun failedCodeRetainsIssuedDraftWithoutActivatingOrCreatingAnotherClient()=runBlocking(Dispatchers.IO) {store().use{s->val p=begin(s,null);port.fail=SiwcOAuthProtocol.Failure("SIWC_OAUTH_INVALID_GRANT")
        fails{finish(s,p)};assertEquals(listOf("fixture-new-client"),s.incompleteRegistrationIds());assertTrue(s.activeKey().isEmpty());assertTrue(s.registrations().isEmpty())}}
    @Test fun refreshRotatesTogetherAndReusesIssuedClientWithoutScopeParameter()=runBlocking(Dispatchers.IO) {store().use{s->val p=begin(s);val key=finish(s,p).key;val old=checkNotNull(s.inferenceGrant("owner",emptySet()));clock+=3500000
        port.reply={f.tokens(p,clock,change={it["access_token"]="PUBLIC_ROTATED_ACCESS_CREDENTIAL_12345678";it["refresh_token"]="PUBLIC_ROTATED_REFRESH_CREDENTIAL_12345678"})}
        assertEquals("ACTIVE",s.refresh(key));assertEquals("refresh_token",port.last["grant_type"]);assertEquals("fixture-client",port.last["client_id"]);assertFalse(port.last.containsKey("scope"))
        val next=checkNotNull(s.inferenceGrant("owner",emptySet()));assertNotSame(old,next);assertNotEquals(old.accessToken,next.accessToken);assertEquals(2,port.calls)
        assertEquals("ACTIVE",s.refresh(key));assertEquals(2,port.calls)}}
    @Test fun terminalRefreshClearsTokensButKeepsRegistrationAndHost()=runBlocking(Dispatchers.IO) {store().use{s->val key=finish(s,begin(s)).key;val host=s.hostId();clock+=3500000
        port.fail=SiwcOAuthProtocol.Failure("SIWC_OAUTH_REFRESH_TOKEN_REUSED");assertEquals("SIGNED_OUT",s.refresh(key));assertEquals(host,s.hostId());assertEquals(key,s.registrations().single().key);assertNull(s.inferenceGrant("owner",emptySet()))}}
    @Test fun temporaryErrorPreservesCredentialAndAppliesBackoff()=runBlocking(Dispatchers.IO) {store().use{s->val key=finish(s,begin(s)).key;clock+=3500000;port.fail=SiwcOAuthProtocol.Failure("SIWC_OAUTH_TEMPORARY")
        assertEquals("ACTIVE",s.refresh(key));assertNotNull(s.inferenceGrant("owner",emptySet()));assertEquals("ACTIVE",s.refresh(key));assertEquals(2,port.calls)}}
    @Test fun uncertainRefreshNeverReusesOldRotatingTokenAfterRestart()=runBlocking(Dispatchers.IO) {var key="";store().use{s->key=finish(s,begin(s)).key;clock+=3500000;port.fail=IOException("fixture response lost");assertEquals("REFRESH_UNKNOWN",s.refresh(key))}
        port.fail=null;store().use{s->assertEquals("REFRESH_UNKNOWN",s.refresh(key));assertNull(s.inferenceGrant("owner",emptySet()))};assertEquals(2,port.calls)}
    @Test fun persistedInterruptedRefreshBecomesUnknown()=runBlocking(Dispatchers.IO) {var key="";store().use{s->key=finish(s,begin(s)).key}
        ProtectedDatabase.open(app,"siwc-auth-v1.db",null).use{d->d.transaction{d.database().execSQL("UPDATE siwc_auth_accounts SET state='REFRESHING',process='dead-process' WHERE id=?",arrayOf(key));null}}
        store().use{s->assertEquals("REFRESH_UNKNOWN",s.registrations().single().state);assertNull(s.inferenceGrant("owner",emptySet()))}}
    @Test fun logoutRetainsIdentityAndReportsUnconfirmedRemoteRevocation()=runBlocking(Dispatchers.IO) {store().use{s->val key=finish(s,begin(s)).key;port.revokeOk=false
        assertEquals("SIWC_REMOTE_REVOCATION_UNKNOWN",s.signOut(key));assertNull(s.inferenceGrant("owner",emptySet()));assertTrue(s.activeKey().isEmpty());assertEquals(key,s.registrations().single().key);assertEquals(1,port.revokes)}}
    @Test fun lateRefreshAfterSignOutCannotRestoreCredentials()=runBlocking(Dispatchers.IO) {store().use{s->val p=begin(s);val key=finish(s,p).key;clock+=3500000
        port.reply={s.signOut(key);f.tokens(p,clock)};assertEquals("SIGNED_OUT",s.refresh(key));assertNull(s.inferenceGrant("owner",emptySet()))}}
    @Test fun lateLogoutCannotEraseASeparatelyVerifiedNewSignIn()=runBlocking(Dispatchers.IO) {store().use{s->val key=finish(s,begin(s)).key
        port.onRevoke={finish(s,begin(s,selected=key))};s.signOut(key);assertEquals(key,s.activeKey());assertNotNull(s.inferenceGrant("owner",emptySet()))}}
    @Test fun simultaneousRefreshAttemptUsesSingleDurableClaim()=runBlocking(Dispatchers.IO) {store().use{s->val p=begin(s);val key=finish(s,p).key;clock+=3500000
        port.reply={assertEquals("REFRESHING",s.refresh(key));f.tokens(p,clock)};assertEquals("ACTIVE",s.refresh(key));assertEquals(2,port.calls)}}
    @Test fun unknownRefreshScheduleEncodingIsPreservedAndHeldWithoutGuessingUnits()=runBlocking(Dispatchers.IO) {store().use{s->val p=begin(s);port.reply={f.tokens(p,change={it["earliest_refresh_at"]="UNVERIFIED_FIXTURE_ENCODING"})}
        val key=s.finish(p,p.callback(f.callback(p),clock),f.metadata).key;clock+=3500000
        assertEquals("ACTIVE",s.refresh(key));assertEquals("SIWC_REFRESH_SCHEDULE_UNVERIFIED",s.registrations().single().status);assertEquals(1,port.calls)}}
    @Test fun badNewSignatureNeverReplacesExistingCredentials()=runBlocking(Dispatchers.IO) {store().use{s->val key=finish(s,begin(s)).key;val p=begin(s,selected=key)
        port.reply={f.tokens(p,change={it["id_token"]=SiwcAuthFixture().jwt(p)})};fails{s.finish(p,p.callback(f.callback(p),clock),f.metadata)};assertEquals(key,s.activeKey());assertNotNull(s.inferenceGrant("owner",emptySet()))}}
    @Test fun fetchingSigningKeysDoesNotExtendAccessTokenLifetime()=runBlocking(Dispatchers.IO) {store().use{s->port.onJwks={clock+=120000};finish(s,begin(s))
        assertEquals(f.now+3600000,checkNotNull(s.inferenceGrant("owner",emptySet())).expiresMs)}}
    @Test fun refreshCredentialWithoutGrantedOfflineScopeCannotBeStored()=runBlocking(Dispatchers.IO) {store().use{s->val p=begin(s)
        port.reply={f.tokens(p,scopes=SiwcOAuthProtocol.IDENTITY,change={it["refresh_token"]="PUBLIC_UNGRANTED_REFRESH"})}
        fails{s.finish(p,p.callback(f.callback(p),clock),f.metadata)};assertTrue(s.activeKey().isEmpty());assertTrue(s.registrations().isEmpty())}}
}
