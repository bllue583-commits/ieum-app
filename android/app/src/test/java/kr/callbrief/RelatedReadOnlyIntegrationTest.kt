package kr.callbrief

import android.Manifest
import android.app.Application
import android.content.*
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import kr.callbrief.core.PrivacyRules
import kr.callbrief.navermail.*
import kr.callbrief.navermail.android.NaverCredentialVault
import kr.co.callsummary.core.android.ProtectedDatabase
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadows.ShadowContentResolver
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Normal read ports over an in-memory SMS provider and mailbox fixtures, not device data. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RelatedReadOnlyIntegrationTest:ProtectedTextHostTestBase() {
    private lateinit var context:Context;private lateinit var keys:SyntheticPilotIntegrationTest.HostKeys
    private var id="";private val phone="010"+"0".repeat(8);private val worker=Executors.newSingleThreadExecutor()
    private fun <T> io(action:()->T):T=try{worker.submit<T>{action()}.get(30,TimeUnit.SECONDS)}catch(e:java.util.concurrent.ExecutionException){throw e.cause!!}
    private lateinit var provider:SmsProvider
    private fun store()=RelatedReadOnlyStore(context)
    private fun snap()=RecordingWorkflowStore(context).use{it.snapshot(id)}
    private fun scope()=store().use{it.smsScope(id)!!}
    private fun configure()=store().use{it.configureSms(id,snap(),1000,4000,true)}
    private fun read()=store().use{s->val selected=s.smsScope(id)!!;val b=AndroidSmsReadOnlySource(context).read(selected,{s.current(selected)},CancellationSignal());s.storeSms(selected,b,{s.current(selected)})}
    private fun failure(code:String,action:()->Unit){try{action();fail("Expected $code")}catch(e:Exception){assertTrue(e.toString(),e.message.orEmpty().contains(code))}}
    class SmsProvider:ContentProvider() {
        data class Row(val id:Long=1,val phone:String="010"+"0".repeat(8),val date:Long=2000,val type:Int=1,var text:String="PUBLIC_SMS_BODY_A")
        var rows=mutableListOf(Row());var metadataQueries=0;var bodyQueries=0;var mutations=0;var lastSelection="";var nullResult=false;var afterMetadata:(()->Unit)?=null;var afterBody:(()->Unit)?=null
        override fun onCreate()=true
        override fun query(uri:Uri,p:Array<out String>?,s:String?,a:Array<out String>?,sort:String?):Cursor? {
            lastSelection=s.orEmpty();val body=p?.contains("body")==true
            if(body)bodyQueries++ else metadataQueries++
            if(nullResult)return null
            val columns=p!!.map{it}.toTypedArray();val cursor=MatrixCursor(columns)
            val chosen=if(body)rows.filter{it.id.toString()==a!!.last()} else rows
            for(row in chosen)cursor.addRow(columns.map{when(it){"_id"->row.id;"address"->row.phone;"date"->row.date;"type"->row.type;"body"->row.text;else->error("Unexpected projection")}})
            if(body)afterBody?.invoke() else afterMetadata?.invoke()
            return cursor
        }
        override fun getType(u:Uri)="vnd.android.cursor.dir/sms"
        override fun insert(u:Uri,v:ContentValues?):Uri? {mutations++;error("Read only")}
        override fun delete(u:Uri,s:String?,a:Array<out String>?)=throw AssertionError("Read only")
        override fun update(u:Uri,v:ContentValues?,s:String?,a:Array<out String>?)=throw AssertionError("Read only")
    }
    @Before fun setup() {
        context=RuntimeEnvironment.getApplication();keys=SyntheticPilotIntegrationTest.HostKeys();provider=SmsProvider();provider.attachInfo(context,android.content.pm.ProviderInfo().apply{authority="sms"});ShadowContentResolver.registerProviderInternal("sms",provider)
        // This is a synthetic framework shadow, not an Android grant on the connected device.
        shadowOf(context as Application).grantPermissions(Manifest.permission.READ_SMS)
        io {GeneralLocalStoreTest.host(context,keys).use{s->s.initializeEmpty();s.importSelected(GeneralLocalStoreTest.Source().apply{put()});val r=s.snapshot().getJSONObject(0);id=r.getString("file_id");s.edit(id,r,"PUBLIC_RELATED_DONE","done")};RecordingWorkflowStore(context).use{s->s.confirmWork(id,s.snapshot(id),"합성 담당자","합성 회사","합성 통화 업무",phone)};configure()}
    }
    @After fun stop(){worker.shutdown();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS));assertEquals(0,provider.mutations)}
    @Test fun S01PermissionMissingHasNoProviderQuery():Unit=io {shadowOf(context as Application).denyPermissions(Manifest.permission.READ_SMS);failure("SMS_EXISTING_PERMISSION_REQUIRED"){read()};assertEquals(0,provider.metadataQueries);assertEquals(0,provider.bodyQueries)}
    @Test fun S02ExactScopeReadStoresCandidateWithoutTaskOrAnalysisWrites():Unit=io {assertEquals(1,read());assertEquals(1,provider.bodyQueries);assertTrue(provider.lastSelection.contains("_id=?"));assertEquals(0,snap().getJSONArray("related").length());assertEquals(0,snap().getJSONArray("items").length());assertEquals("done",snap().getString("manual_status"));assertEquals("PUBLIC_RELATED_DONE",snap().getString("manual_text"))}
    @Test fun S03SameBytesRepeatedReadHasOneVersion():Unit=io {assertEquals(1,read());assertEquals(0,read());store().use{s->assertEquals(1,s.smsCandidates(id).length());assertEquals(1L,s.smsCandidates(id).getJSONObject(0).getLong("version"))}}
    @Test fun S04AtoBtoAKeepsThreeContentVersions():Unit=io {read();provider.rows.single().text="PUBLIC_SMS_BODY_B";read();provider.rows.single().text="PUBLIC_SMS_BODY_A";read();store().use{s->assertEquals(3L,s.smsCandidates(id).getJSONObject(0).getLong("version"))};TextStoreTestSupport.open(context,GeneralLocalStore.DATABASE).use{db->db.rawQuery("SELECT COUNT(*) FROM related_sms_candidates",null).use{c->c.moveToFirst();assertEquals(3,c.getInt(0))}}}
    @Test fun S05ExcludedBeforeReadQueriesNothing():Unit=io {val rules=JSONObject().put("exclusions",org.json.JSONArray().put(JSONObject().put("type","phone").put("hash",PrivacyRules.hash(phone))));RelatedReadOnlyStore(context,policy={rules}).use{s->val selected=s.smsScope(id)!!;assertFalse(s.current(selected));failure("RELATED_READ_CANCELLED"){AndroidSmsReadOnlySource(context).read(selected,{s.current(selected)},CancellationSignal())}};assertEquals(0,provider.metadataQueries)}
    @Test fun S06ProviderOtherNumberAndOutgoingFailedTypeHaveNoBodyRead():Unit=io {provider.rows=mutableListOf(SmsProvider.Row(phone="010"+"0".repeat(7)+"1"),SmsProvider.Row(id=2,type=5));assertEquals(0,read());assertEquals(0,provider.bodyQueries)}
    @Test fun S07FutureAndBeforeRangeHaveNoBodyRead():Unit=io {provider.rows=mutableListOf(SmsProvider.Row(date=999),SmsProvider.Row(id=2,date=4000));assertEquals(0,read());assertEquals(0,provider.bodyQueries)}
    @Test fun S08CancelAfterMetadataBeforeBodyQueriesNothing():Unit=io {val signal=CancellationSignal();provider.afterMetadata={signal.cancel()};store().use{s->val selected=s.smsScope(id)!!;failure("RELATED_READ_CANCELLED"){AndroidSmsReadOnlySource(context).read(selected,{s.current(selected)},signal)}};assertEquals(0,provider.bodyQueries)}
    @Test fun S09ScopeChangeAfterMetadataRejectsBeforeBodyRead():Unit=io {provider.afterMetadata={store().use{it.disableSms(id)}};failure("RELATED_READ_CANCELLED"){read()};assertEquals(0,provider.bodyQueries)}
    @Test fun S10PermissionRevokedAfterMetadataRejectsBeforeBodyRead():Unit=io {provider.afterMetadata={shadowOf(context as Application).denyPermissions(Manifest.permission.READ_SMS)};failure("SMS_EXISTING_PERMISSION_REQUIRED"){read()};assertEquals(0,provider.bodyQueries)}
    @Test fun S11CancelledBodyReturnDoesNotStoreAnyCandidate():Unit=io {provider.afterBody={store().use{it.disableSms(id)}};failure("RELATED_READ_CANCELLED"){read()};store().use{assertEquals(0,it.smsCandidates(id).length())}}
    @Test fun S12NullProviderIsErrorRatherThanSuccessfulEmpty():Unit=io {provider.nullResult=true;failure("SMS_PROVIDER_UNAVAILABLE"){read()};store().use{assertEquals(0,it.smsCandidates(id).length())}}
    @Test fun S13ExplicitConfirmationCreatesOnlyLocalEvidence():Unit=io {read();store().use{s->s.confirmSms(id,s.smsCandidates(id).getJSONObject(0).getString("digest"),snap())};val s=snap();assertEquals(1,s.getJSONArray("related").length());assertEquals(0,s.getJSONArray("items").length());assertEquals("done",s.getString("manual_status"))}
    @Test fun S14UserEditBetweenReadAndConfirmationIsProtected():Unit=io {read();val before=snap();GeneralLocalStoreTest.host(context,keys).use{s->s.edit(id,s.snapshot().getJSONObject(0),"PUBLIC_LATER_DONE","done")};store().use{s->failure("USER_REVISION_CONFLICT"){s.confirmSms(id,s.smsCandidates(id).getJSONObject(0).getString("digest"),before)}};assertEquals(0,snap().getJSONArray("related").length());assertEquals("PUBLIC_LATER_DONE",snap().getString("manual_text"))}
    @Test fun S15ScopeReconfigurationDoesNotInventContentVersion():Unit=io {read();configure();read();store().use{s->val c=s.smsCandidates(id).getJSONObject(0);assertEquals(1L,c.getLong("version"));s.confirmSms(id,c.getString("digest"),snap())};assertEquals(1,snap().getJSONArray("related").length())}
    @Test fun S16CandidateBodyCanaryIsSealedOnDisk():Unit=io {read();assertFalse(String(ProtectedDatabase.file(context,GeneralLocalStore.DATABASE).readBytes(),Charsets.ISO_8859_1).contains("PUBLIC_SMS_BODY_A"))}
    @Test fun S17BoundedFiftyBodiesAndExtraRowMarksIncomplete():Unit=io {provider.rows=(1L..60L).map{SmsProvider.Row(id=it)}.toMutableList();store().use{s->val selected=scope();val batch=AndroidSmsReadOnlySource(context).read(selected,{s.current(selected)},CancellationSignal());assertEquals(50,batch.messages.size);assertTrue(batch.incomplete)};assertEquals(50,provider.bodyQueries)}
    @Test fun S18NoBodyForNonnumericSenderAndNoUnboundedMetadataLoop():Unit=io {provider.rows=(1L..200L).map{SmsProvider.Row(id=it,phone="SYNTHETIC_BRAND")}.toMutableList();assertEquals(0,read());assertEquals(0,provider.bodyQueries)}
    @Test fun S19InternationalFormattingUsesFullNormalizedPhone():Unit=io {provider.rows=mutableListOf(SmsProvider.Row(phone="+82 10-0000-0000"));assertEquals(1,read());assertEquals(1,provider.bodyQueries)}
    @Test fun S20MissingIdentityIsHeldBeforeMetadataQuery():Unit=io {RecordingWorkflowStore(context).use{s->s.confirmWork(id,s.snapshot(id),"","","다른 합성 업무",phone)};failure("RELATED_READ_CANCELLED"){read()};assertEquals(0,provider.metadataQueries)}
    @Test fun S21StaleAudioVersionCannotAuthorizeNewSmsScope():Unit=io {val expected=snap();GeneralLocalStoreTest.host(context,keys).use{it.importSelected(GeneralLocalStoreTest.Source().apply{put(alternate=true)})};assertNotEquals(expected.getLong("source_version"),snap().getLong("source_version"));store().use{s->failure("RELATED_SCOPE_CHANGED"){s.configureSms(id,expected,1000,4000,true)}};assertEquals(0,provider.metadataQueries)}
    @Test fun S22NoEligibleBodyDoesNotHideIncompleteScan():Unit=io {provider.rows=mutableListOf(SmsProvider.Row(phone="PUBLIC_BRAND"));assertEquals(0,read());store().use{s->val last=s.lastSmsScan(id)!!;assertEquals(0,last.getInt("message_count"));assertEquals(1,last.getInt("incomplete"));assertEquals(1,last.getInt("skipped"))}}
    @Test fun S23RepeatedScanReceiptDoesNotDuplicateContent():Unit=io {read();read();store().use{s->assertEquals(0,s.lastSmsScan(id)!!.getInt("added_count"));assertEquals(1,s.smsCandidates(id).length())};TextStoreTestSupport.open(context,GeneralLocalStore.DATABASE).use{db->db.rawQuery("SELECT COUNT(*) FROM related_sms_scans",null).use{c->c.moveToFirst();assertEquals(2,c.getInt(0))}}}
    private fun savedMail():NaverCredentialVault.SavedAccount {
        val account=AccountBinding(GeneralLocalStore.OWNER,"fixture-mail@naver.com")
        val scope=MailScope(account,"unbound","unbound",setOf("fixture@example.invalid"),setOf("계약"),LocalDate.parse("2026-10-01"),LocalDate.parse("2026-10-12"),relationshipConfirmedByUser=true)
        return NaverCredentialVault.SavedAccount(account,account.key,scope,ConnectionState.VERIFY_REQUIRED)
    }
    private class Mailbox:ReadOnlyMailbox {
        override val origin=Origin.SYNTHETIC_FIXTURE;var opens=0;var bodies=0;var body="PUBLIC_MAIL_BODY";var afterHeader:(()->Unit)?=null
        override fun open(account:AccountBinding,secret:SecretChars):ReadOnlyMailbox.Session {opens++;return object:ReadOnlyMailbox.Session {
            override fun examineInbox()=100L
            override fun search(scope:MailScope)=SearchResult(listOf(1),false)
            override fun header(ref:MailRef)=HeaderEnvelope(ref,Instant.parse("2026-10-10T00:00:00Z"),null,setOf("fixture@example.invalid"),setOf(account.address),"합성 계약 자료",PrivacyRules.hash("PUBLIC_HEADER"),"text/plain",origin=origin).also{afterHeader?.invoke()}
            override fun selectedPlainBody(header:HeaderEnvelope)=PlainBody(header.ref,body,false,origin).also{bodies++}
            override fun close(){}
        }}
    }
    @Test fun M01ExistingSavedScopeMapsExactCaseAndKeepsBounds():Unit=io {val saved=savedMail();store().use{s->val b=s.configureMail(id,snap(),saved.scope!!,false);assertEquals(snap().getString("case_id"),b.selected.caseId);assertEquals(saved.scope!!.correspondentAddresses,b.selected.correspondentAddresses);assertEquals(saved.scope!!.since,b.selected.since);assertEquals(saved.scope!!.fingerprint,b.savedScopeSha)}}
    @Test fun M02DeniedDefaultPolicyDoesNotDecryptCredentialsOrOpenMailbox():Unit=io {val saved=savedMail();store().use{it.configureMail(id,snap(),saved.scope!!,false)};val mailbox=Mailbox();var reads=0;RelatedReadOnlyCoordinator(context,{true},mailPolicy=AccountActionPolicy.denied(),savedAccounts={listOf(saved)},credentials={reads++;SecretChars(CharArray(16){'a'})},mailbox=mailbox).use{failure("APPROVAL_REQUIRED"){it.scanMail(id)}};assertEquals(0,reads);assertEquals(0,mailbox.opens)}
    @Test fun M03ExistingScopeHeadersUseNativeProtectedInboxOnly():Unit=io {val saved=savedMail();store().use{it.configureMail(id,snap(),saved.scope!!,false)};val mailbox=Mailbox();RelatedReadOnlyCoordinator(context,{true},mailPolicy=AccountActionPolicy.approvedByHost(),savedAccounts={listOf(saved)},credentials={SecretChars(CharArray(16){'a'})},mailbox=mailbox).use{val b=it.scanMail(id);assertEquals(1,b.candidates.size);assertTrue(b.selectedBodies.isEmpty())};assertEquals(0,mailbox.bodies);assertEquals(1,snap().getJSONArray("naver_candidates").length());assertEquals(0,snap().getJSONArray("related").length());assertEquals("done",snap().getString("manual_status"))}
    @Test fun M04OnlyConsentedMatchingPlainBodyIsRead():Unit=io {val saved=savedMail();store().use{it.configureMail(id,snap(),saved.scope!!,true)};val mailbox=Mailbox();RelatedReadOnlyCoordinator(context,{true},mailPolicy=AccountActionPolicy.approvedByHost(),savedAccounts={listOf(saved)},credentials={SecretChars(CharArray(16){'a'})},mailbox=mailbox).use{assertEquals(1,it.scanMail(id).selectedBodies.size)};assertEquals(1,mailbox.bodies);assertEquals("done",snap().getString("manual_status"));assertEquals(0,snap().getJSONArray("items").length())}
    @Test fun M05SavedScopeChangedIsHeldBeforeCredentialRead():Unit=io {val saved=savedMail();store().use{it.configureMail(id,snap(),saved.scope!!,true)};var secretReads=0;RelatedReadOnlyCoordinator(context,{true},mailPolicy=AccountActionPolicy.approvedByHost(),savedAccounts={listOf(saved.copy(scope=saved.scope!!.copy(revision=2)))},credentials={secretReads++;SecretChars(CharArray(16){'a'})},mailbox=Mailbox()).use{failure("MAIL_SAVED_SCOPE_CHANGED"){it.scanMail(id)}};assertEquals(0,secretReads)}
    @Test fun M06OwnerChangedIsHeldBeforeMailboxOpen():Unit=io {var read=0;RelatedReadOnlyCoordinator(context,{false},mailPolicy=AccountActionPolicy.approvedByHost(),credentials={read++;SecretChars(CharArray(16){'a'})},mailbox=Mailbox()).use{failure("RELATED_READ_CANCELLED"){it.scanMail(id)}};assertEquals(0,read)}
    @Test fun M07MailCandidateConfirmationKeepsNewBodyAsNewEvidence():Unit=io {val saved=savedMail();store().use{it.configureMail(id,snap(),saved.scope!!,true)};val mailbox=Mailbox();RelatedReadOnlyCoordinator(context,{true},mailPolicy=AccountActionPolicy.approvedByHost(),savedAccounts={listOf(saved)},credentials={SecretChars(CharArray(16){'a'})},mailbox=mailbox).use{it.scanMail(id)};RecordingWorkflowStore(context).use{s->val c=s.snapshot(id).getJSONArray("naver_candidates");val digest=(0 until c.length()).map{c.getJSONObject(it)}.single{!it.isNull("raw_text")}.getString("digest");s.confirmNaverCandidate(id,s.snapshot(id),digest)};assertEquals(1,snap().getJSONArray("related").length());assertEquals("done",snap().getString("manual_status"));assertEquals(0,snap().getJSONArray("items").length())}
    @Test fun M08DisconnectStateIsHeldBeforeCredentialRead():Unit=io {val saved=savedMail();store().use{it.configureMail(id,snap(),saved.scope!!,true)};var reads=0;RelatedReadOnlyCoordinator(context,{true},mailPolicy=AccountActionPolicy.approvedByHost(),savedAccounts={listOf(saved.copy(state=ConnectionState.DISCONNECTED))},credentials={reads++;SecretChars(CharArray(16){'a'})},mailbox=Mailbox()).use{failure("MAIL_SAVED_SCOPE_CHANGED"){it.scanMail(id)}};assertEquals(0,reads)}
    private fun refreshAndConfirm(saved:NaverCredentialVault.SavedAccount,mailbox:Mailbox) {
        RelatedReadOnlyCoordinator(context,{true},mailPolicy=AccountActionPolicy.approvedByHost(),savedAccounts={listOf(saved)},credentials={SecretChars(CharArray(16){'a'})},mailbox=mailbox).use{it.scanMail(id)}
        RecordingWorkflowStore(context).use{s->val c=s.snapshot(id).getJSONArray("naver_candidates");val digest=(0 until c.length()).map{c.getJSONObject(it)}.single{!it.isNull("raw_text")}.getString("digest");s.confirmNaverCandidate(id,s.snapshot(id),digest)}
    }
    @Test fun M09SameUidChangedMailBodyPreservesBothEvidenceVersions():Unit=io {val saved=savedMail();store().use{it.configureMail(id,snap(),saved.scope!!,true)};val mailbox=Mailbox();refreshAndConfirm(saved,mailbox);mailbox.body="PUBLIC_MAIL_BODY_CHANGED";refreshAndConfirm(saved,mailbox);val related=snap().getJSONArray("related");assertEquals(2,related.length());assertEquals(setOf("PUBLIC_MAIL_BODY","PUBLIC_MAIL_BODY_CHANGED"),(0 until related.length()).map{related.getJSONObject(it).getString("raw_text")}.toSet());assertEquals("done",snap().getString("manual_status"))}
    @Test fun M10LegacySameUidAndSameBytesReuseExistingSource():Unit=io {val saved=savedMail();RecordingWorkflowStore(context).use{s->s.addRelated(id,s.snapshot(id),"MAIL","PUBLIC_MAIL_BODY","","2026-10-10T00:00:00Z","合成 legacy","naver-v1:${saved.key}:INBOX:100:1")};store().use{it.configureMail(id,snap(),saved.scope!!,true)};refreshAndConfirm(saved,Mailbox());assertEquals(1,snap().getJSONArray("related").length())}
    @Test fun M11StaleAudioVersionCannotAuthorizeNewMailBinding():Unit=io {val saved=savedMail();val expected=snap();GeneralLocalStoreTest.host(context,keys).use{it.importSelected(GeneralLocalStoreTest.Source().apply{put(alternate=true)})};store().use{s->failure("RELATED_SCOPE_CHANGED"){s.configureMail(id,expected,saved.scope!!,true)}}}
}
