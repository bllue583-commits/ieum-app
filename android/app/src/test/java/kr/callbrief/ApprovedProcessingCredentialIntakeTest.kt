package kr.callbrief

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import kotlinx.coroutines.*
import kr.callagent.drive.*
import kr.co.callsummary.core.android.ProtectedDatabase
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadows.ShadowLog
import java.util.concurrent.*
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/** Public synthetic credentials and proof only. Real host SQLite/JCE/protected journal;
 * authority, source and preference failure boundaries are controlled. No external service. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ApprovedProcessingCredentialIntakeTest : ProtectedTextHostTestBase() {
    private val owner="PUBLIC_CP32_OWNER"
    private val account="cp32@example.invalid"
    private val credential="PUBLIC_SYNTHETIC_CP32_CREDENTIAL_CANARY_ONLY"
    private val origin="https://cp32-approved.example.invalid"
    private val proof="PUBLIC_CP32_SEPARATE_TRUSTED_CHANNEL"
    private var now=System.currentTimeMillis()
    private var authNow=now // Independent native-auth clock; approval expiry does not simulate token expiry.
    private val worker=Executors.newSingleThreadExecutor()
    private fun <T> io(f:()->T):T = try { worker.submit<T>{f()}.get(20,TimeUnit.SECONDS) }
        catch(e:ExecutionException){throw e.cause!!}
    private class Memory:GoogleConnectionSession.Persistence {
        var saved=JSONObject()
        override fun read()=JSONObject(saved.toString())
        override fun write(value:JSONObject){saved=JSONObject(value.toString())}
    }
    private class Keys:SecureSettings.KeyAccess {
        var value:SecretKey?=SecretKeySpec(ByteArray(32){0x32},"AES")
        var creates=0
        override fun existing()=value
        override fun createForFirstEmptyStore():SecretKey { creates++;error("PUBLIC_CP32_UNEXPECTED_KEY_CREATION") }
    }
    private class GatePreferences(private val original:SharedPreferences):SharedPreferences by original {
        var writes=0;var falseAfterDurable=false;var throwAfterDurable=false;var interruptAfterDurable=false
        var before:(()->Unit)?=null;var after:(()->Unit)?=null
        override fun edit():SharedPreferences.Editor {
            val editor=original.edit()
            return object:SharedPreferences.Editor by editor {
                override fun putString(key:String,value:String?):SharedPreferences.Editor {editor.putString(key,value);return this}
                override fun commit():Boolean {
                    writes++;before?.invoke()
                    val result=editor.commit();after?.invoke()
                    if(throwAfterDurable)throw java.io.IOException("PUBLIC_CP32_CREDENTIAL_CANARY_EXCEPTION")
                    if(interruptAfterDurable){Thread.currentThread().interrupt();return false}
                    return result && !falseAfterDurable
                }
            }
        }
    }
    private class GateContext(base:Context):ContextWrapper(base) {
        val gates=mutableMapOf<String,GatePreferences>()
        override fun getApplicationContext():Context=this
        override fun getSharedPreferences(name:String,mode:Int):SharedPreferences =
            if(name.endsWith("_private_settings"))gates.getOrPut(name){GatePreferences(super.getSharedPreferences(name,mode))}
            else super.getSharedPreferences(name,mode)
    }
    private lateinit var context:GateContext
    private lateinit var scoped:AccountStorageContext
    private lateinit var session:GoogleConnectionSession
    private lateinit var memory:Memory
    private lateinit var keys:Keys
    private lateinit var source:Source
    private lateinit var authority:Authority
    private fun settings(c:Context=scoped)=SecureSettings(c,keys)
    private fun scope()=checkNotNull(DriveALocalScope.current(session))
    private fun gate()=context.gates.values.single()
    private fun sealed()=scoped.getSharedPreferences("private_settings",0).getString("state",null)
    private fun payload(s:DriveALocalScope=scope())=JSONObject().put("schema","ieum.owner-approved-processing-setup.v1")
        .put("approvalState",ApprovedProcessingSetupWriter.USAGE).put("scope",JSONObject(String(StrictJson.bytes(s.binding()))))
        .put("origin",origin).put("zone","Etc/UTC").put("credential",credential)
    private inner class Source:ApprovedProcessingCredentialSource {
        var calls=0;var onLoad:(()->Unit)?=null;var body:((DriveALocalScope)->ByteArray)?=null
        var evidence=proof.toByteArray();var absent=false
        override fun existing(scope:ApprovedProcessingIntakeScope):ApprovedProcessingCandidate? {
            calls++;onLoad?.invoke();if(absent)return null
            return ApprovedProcessingCandidate(body?.invoke(scope.native) ?: payload(scope.native).toString().toByteArray(),evidence)
        }
    }
    private inner class Authority:ApprovedProcessingApprovalPort {
        var calls=0;var allowed=true;var onVerify:((ApprovedProcessingApproval)->Unit)?=null
        var latest:ApprovedProcessingApproval?=null
        override fun verify(candidate:ApprovedProcessingCandidate,scope:ApprovedProcessingIntakeScope,canonicalSha:String):ApprovedProcessingApproval? {
            calls++
            if(!allowed || !candidate.evidence().contentEquals(proof.toByteArray()))return null
            val grant=ApprovedProcessingApproval("PUBLIC_CP32_FIXTURE_ISSUER",origin,"PUBLIC_CP32_APPROVAL_1",scope,
                canonicalSha,now-1000,now+3_600_000,ApprovedProcessingSetupWriter.USAGE)
            latest=grant;onVerify?.invoke(grant);return grant
        }
    }
    private fun writer(cancellation:ApprovedProcessingIntakeCancellation=ApprovedProcessingIntakeCancellation(),
        approval:ApprovedProcessingApprovalPort=authority)=ApprovedProcessingSetupWriter(context,session,source,approval,::settings,{now},cancellation)
    private fun read()=ExistingApprovedProcessingSetup.read(context,session,::settings)
    private fun heldReader(){try{read();fail("reader admitted incomplete setup")}catch(_:IllegalStateException){}}
    private fun journalState():String?=ProtectedDatabase.open(scoped,GeneralLocalStore.DATABASE,null).use { p -> ProtectedDatabase.read(p.database()) {
        if(!p.database().rawQuery("SELECT name FROM sqlite_master WHERE name='approved_processing_intakes'",null).use{it.moveToFirst()})null
        else p.database().rawQuery("SELECT state FROM approved_processing_intakes WHERE scope=?",arrayOf(scope().key)).use{if(it.moveToFirst())it.getString(0) else null}
    }}
    private fun dbHash()=DriveRelay.sha256(ProtectedDatabase.file(scoped,GeneralLocalStore.DATABASE).readBytes())
    private fun normalizedState()=settings().snapshot().also{it.remove(ApprovedProcessingSetupWriter.SETUP);it.remove(ApprovedProcessingSetupWriter.MARKER)}.toString()
    private fun accepted() { assertEquals(ApprovedProcessingSetupWriter.State.PREVIOUS_APPROVED_WORKER_SETUP_STORED,writer().receive().state) }
    @Before fun setup()=io {
        ShadowTextAtomicFile.failNext=false;ShadowTextAtomicFile.failedStage=null;ShadowLog.clear()
        context=GateContext(RuntimeEnvironment.getApplication());keys=Keys();memory=Memory()
        session=GoogleConnectionSession(memory){authNow};val e=session.begin();session.activate(e,account,account,owner,"PUBLIC_CP32_RAM_ONLY_GOOGLE_TOKEN")
        scoped=AccountStorageContext(context,owner)
        scoped.getSharedPreferences("private_settings",0).edit().clear().commit()
        GeneralLocalStore.catalog(scoped)
        settings().update{it.put("treeUri","content://public-cp32-fixture/tree");it.put("excludedRule","PUBLIC_CP32_EXCLUDED");it.put("manualState","DONE")}
        gate().writes=0;source=Source();authority=Authority()
    }
    @After fun stop() {worker.shutdown();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS));ShadowTextAtomicFile.failNext=false;GoogleConnectionSession.resetForHost()}

    @Test fun C01DefaultUnavailableIsNoOpAndNeverCallsStorageFactory()=io {
        val before=dbHash();val old=sealed();var factories=0
        val r=ApprovedProcessingSetupWriter(context,session,settingsFactory={factories++;error("not available")}).receive()
        assertEquals(ApprovedProcessingSetupWriter.State.WAITING_PREVIOUS_APPROVED_WORKER_SETUP,r.state)
        assertEquals(0,factories);assertEquals(old,sealed());assertEquals(before,dbHash());assertEquals(0,gate().writes)
    }
    @Test fun C02TrustedSyntheticIntakeReadsWithExistingReaderAndSimulatedSessionRestart()=io {
        val untouched=normalizedState();val native=scope();val creates=ShadowTextKeys.CREATES.get()
        accepted();assertEquals("COMMITTED",journalState());assertTrue(read()?.bearerCredential==credential)
        session=GoogleConnectionSession(memory){authNow};assertNull(session.accessToken())
        session.activate(native.epoch,account,account,owner,"PUBLIC_CP32_RESTARTED_RAM_TOKEN")
        assertEquals(native,scope());assertTrue(read()?.bearerCredential==credential)
        assertEquals(untouched,normalizedState());assertEquals(creates,ShadowTextKeys.CREATES.get());assertEquals(0,keys.creates)
        assertNull(AutomationConnectionBindings.processing(context,session))
    }
    @Test fun C03OwnerChangeDuringSupplyDiscardsOldResult()=io {
        val old=sealed();source.onLoad={val e=session.begin();session.activate(e,"other@example.invalid","other@example.invalid","PUBLIC_OTHER_OWNER","PUBLIC_OTHER_RAM_TOKEN")}
        assertEquals(ApprovedProcessingSetupWriter.State.HELD_SOURCE_CHANGED,writer().receive().state)
        assertEquals(old,sealed());assertEquals(0,authority.calls);assertEquals(0,gate().writes)
    }
    @Test fun C04PolicyABARestorationCannotReviveOldIntake()=io {
        val before=settings().snapshot().getLong("dailyReportPolicyEpoch")
        source.onLoad={settings().update{it.put("pendingRevocation",true)};settings().update{it.put("pendingRevocation",false)}}
        assertEquals(ApprovedProcessingSetupWriter.State.HELD_SOURCE_CHANGED,writer().receive().state)
        assertEquals(before+2,settings().snapshot().getLong("dailyReportPolicyEpoch"));assertEquals(0,authority.calls);assertFalse(settings().snapshot().has(ApprovedProcessingSetupWriter.SETUP))
    }
    @Test fun C05UnknownAndMalformedOrOversizePayloadsNeverReachTrustOrStorage()=io {
        val old=sealed();val before=dbHash()
        val variants=listOf(payload().put("unknown",true).toString(),payload().put("origin","http://cp32.example.invalid").toString(),
            payload().put("zone","PUBLIC_NOT_A_TIMEZONE").toString(),payload().put("approvalState","APPROVED").toString(),
            payload().put("scope",JSONObject()).toString(),payload().put("credential","short").toString(),"{bad}"," ".repeat(16_385))
        for(v in variants){source.body={v.toByteArray()};assertEquals(ApprovedProcessingSetupWriter.State.HELD_APPROVAL_REVIEW,writer().receive().state)}
        assertEquals(0,authority.calls);assertEquals(old,sealed());assertEquals(before,dbHash());assertEquals(0,gate().writes)
    }
    @Test fun C06SameApprovalRetryIsByteIdempotentAndChangedCredentialIsHeld()=io {
        accepted();val old=sealed();val before=dbHash();val writes=gate().writes
        assertEquals(ApprovedProcessingSetupWriter.State.PREVIOUS_APPROVED_WORKER_SETUP_ALREADY_STORED,writer().receive().state)
        assertEquals(old,sealed());assertEquals(before,dbHash());assertEquals(writes,gate().writes)
        source.body={payload(it).put("credential",credential+"_CHANGED").toString().toByteArray()}
        assertEquals(ApprovedProcessingSetupWriter.State.HELD_PROTECTED_STORE,writer().receive().state);assertEquals(old,sealed())
    }
    @Test fun C07MissingSettingsStateWithExistingKeyNeverInitializes()=io {
        scoped.getSharedPreferences("private_settings",0).edit().clear().commit();val before=dbHash();val creates=keys.creates
        assertEquals(ApprovedProcessingSetupWriter.State.HELD_PROTECTED_STORE,writer().receive().state)
        assertNull(sealed());assertEquals(before,dbHash());assertEquals(creates,keys.creates)
    }
    @Test fun C08MissingSettingsStateAndKeyNeverInitializes()=io {
        scoped.getSharedPreferences("private_settings",0).edit().clear().commit();keys.value=null;val before=dbHash()
        assertEquals(ApprovedProcessingSetupWriter.State.HELD_PROTECTED_STORE,writer().receive().state)
        assertNull(sealed());assertEquals(0,keys.creates);assertEquals(before,dbHash())
    }
    @Test fun C09LostAndWrongSettingsKeyPreserveOriginalCiphertext()=io {
        val old=sealed();val original=keys.value;keys.value=null
        assertEquals(ApprovedProcessingSetupWriter.State.HELD_PROTECTED_STORE,writer().receive().state);assertEquals(old,sealed())
        keys.value=SecretKeySpec(ByteArray(32){0x33},"AES")
        assertEquals(ApprovedProcessingSetupWriter.State.HELD_PROTECTED_STORE,writer().receive().state);assertEquals(old,sealed());assertEquals(0,keys.creates);keys.value=original
    }
    @Test fun C10NoProtectedDatabaseCannotCreateOne()=io {
        val path=ProtectedDatabase.file(scoped,GeneralLocalStore.DATABASE);path.delete();val before=sealed();val creates=ShadowTextKeys.CREATES.get()
        assertEquals(ApprovedProcessingSetupWriter.State.HELD_PROTECTED_STORE,writer().receive().state)
        assertFalse(path.exists());assertEquals(before,sealed());assertEquals(creates,ShadowTextKeys.CREATES.get());assertEquals(0,gate().writes)
    }
    @Test fun C11FalseAfterDurablePreferenceCommitIsQuarantinedInReader()=io {
        val original=sealed();gate().falseAfterDurable=true
        assertEquals(ApprovedProcessingSetupWriter.State.HELD_PROTECTED_STORE,writer().receive().state)
        assertEquals("PREPARED",journalState());assertNotEquals(original,sealed());heldReader()
        assertEquals(1,gate().writes);assertEquals(0,keys.creates)
    }
    @Test fun C12ThrowAfterDurablePreferenceCommitAlsoStaysPrepared()=io {
        gate().throwAfterDurable=true
        val r=writer().receive();assertEquals(ApprovedProcessingSetupWriter.State.HELD_PROTECTED_STORE,r.state)
        assertEquals("PREPARED",journalState());heldReader();assertFalse(r.toString().contains("CANARY"))
    }
    @Test fun C13PreparedCommitFailureDoesNotWritePreferences()=io {
        val old=sealed();ShadowTextAtomicFile.failNext=true
        assertEquals(ApprovedProcessingSetupWriter.State.HELD_PROTECTED_STORE,writer().receive().state)
        assertEquals(old,sealed());assertEquals(0,gate().writes);assertNull(read())
    }
    @Test fun C14FinalJournalFailureAfterSettingsDurableNeverAuthorizesReader()=io {
        gate().after={ShadowTextAtomicFile.failNext=true}
        assertEquals(ApprovedProcessingSetupWriter.State.HELD_PROTECTED_STORE,writer().receive().state)
        assertEquals("PREPARED",journalState());heldReader();assertEquals(1,gate().writes)
    }
    @Test fun C15RawApprovalGoogleActiveAndCapabilityClaimsCannotReplaceSeparateAuthority()=io {
        assertEquals(kr.callagent.drive.AuthorizationPort.State.ACTIVE,session.state())
        assertEquals(ApprovedProcessingSetupWriter.State.HELD_APPROVAL_REVIEW,writer(approval=UnavailableApprovedProcessingApproval).receive().state)
        source.evidence="{\"approval\":\"APPROVED\",\"capabilities\":true}".toByteArray()
        assertEquals(ApprovedProcessingSetupWriter.State.HELD_APPROVAL_REVIEW,writer().receive().state);assertEquals(0,gate().writes)
    }
    @Test fun C16UntrustedOriginOrRevokedApprovalCannotBeStored()=io {
        authority.allowed=false;assertEquals(ApprovedProcessingSetupWriter.State.HELD_APPROVAL_REVIEW,writer().receive().state)
        authority.allowed=true;authority.onVerify={it.revoke()}
        assertEquals(ApprovedProcessingSetupWriter.State.HELD_APPROVAL_REVIEW,writer().receive().state);assertEquals(0,gate().writes)
    }
    @Test fun C17CancelDuringSupplyAndAfterPrepareNeverCompletes()=io {
        val cancel=ApprovedProcessingIntakeCancellation();source.onLoad={cancel.cancel()}
        assertEquals(ApprovedProcessingSetupWriter.State.LOCAL_WORK_STOPPED,writer(cancel).receive().state);assertEquals(0,gate().writes)
        source.onLoad=null;val later=ApprovedProcessingIntakeCancellation();gate().before={later.cancel()}
        assertEquals(ApprovedProcessingSetupWriter.State.LOCAL_WORK_STOPPED,writer(later).receive().state)
        assertEquals("PREPARED",journalState());heldReader()
    }
    @Test fun C18MarkerRemovalAndMissingJournalCannotFallBackToLegacy()=io {
        accepted();settings().update{it.remove(ApprovedProcessingSetupWriter.MARKER)};heldReader()
        settings().update{it.put(ApprovedProcessingSetupWriter.MARKER,JSONObject().put("unknown",true))};heldReader()
        ProtectedDatabase.open(scoped,GeneralLocalStore.DATABASE,null).use{p->p.transaction{p.database().execSQL("DROP TABLE approved_processing_intakes")}}
        heldReader()
    }
    @Test fun C19DurableWithdrawBeforeBindingBlocksReaderAndReintake()=io {
        accepted();assertNull(AutomationConnectionBindings.processing(context,session));AutomationConnectionBindings.withdraw(context,session)
        assertEquals("REVOKED",journalState());heldReader()
        val old=sealed();assertEquals(ApprovedProcessingSetupWriter.State.HELD_PROTECTED_STORE,writer().receive().state);assertEquals(old,sealed())
    }
    @Test fun C20ExistingRevokedBindingCannotLookLikeFirstIntake()=io {
        val c=AutomationApiConfiguration(true,origin,owner,account,scope().epoch,"Etc/UTC",credential)
        AutomationConnectionBindings.bindApprovedProcessing(context,session,c,::settings,{now});AutomationConnectionBindings.withdraw(context,session)
        assertNull(AutomationConnectionBindings.processing(context,session));val old=sealed()
        assertEquals(ApprovedProcessingSetupWriter.State.HELD_PROTECTED_STORE,writer().receive().state);assertEquals(old,sealed());assertEquals(0,gate().writes)
    }
    @Test fun C21SuppliedExceptionAndAllPublicResultsWithholdCanary()=io {
        source.onLoad={throw java.io.IOException(credential)}
        val r=writer().receive();assertFalse(r.toString().contains(credential));assertFalse(ShadowLog.getLogs().joinToString().contains(credential))
        val c=ApprovedProcessingCandidate(credential.toByteArray(),proof.toByteArray());assertFalse(c.toString().contains(credential))
        source.onLoad=null;accepted();assertFalse(checkNotNull(authority.latest).toString().contains(credential))
        assertFalse(String(ProtectedDatabase.file(scoped,GeneralLocalStore.DATABASE).readBytes(),Charsets.ISO_8859_1).contains(credential))
    }
    @Test fun C22NestedProtectedTransactionCannotDelayPreparedDurability()=io {
        val old=sealed()
        ProtectedDatabase.open(scoped,GeneralLocalStore.DATABASE,null).use{p->p.transaction{
            assertEquals(ApprovedProcessingSetupWriter.State.HELD_PROTECTED_STORE,writer().receive().state)
        }}
        assertEquals(old,sealed());assertEquals(0,gate().writes)
    }
    @Test fun C23TerminalResponseLossRetryKeepsStoredBytesAndNoBinding()=io {
        writer().receive() // Caller loses this response after the durable terminal record.
        val old=sealed();val before=dbHash();val writes=gate().writes
        assertEquals(ApprovedProcessingSetupWriter.State.PREVIOUS_APPROVED_WORKER_SETUP_ALREADY_STORED,writer().receive().state)
        assertEquals(old,sealed());assertEquals(before,dbHash());assertEquals(writes,gate().writes);assertNull(AutomationConnectionBindings.processing(context,session))
    }
    @Test fun C24IntakeKeepsManualDoneHistoryAndExclusionsWithoutAnotherAudioRead()=io {
        val audioKeys=SyntheticPilotIntegrationTest.HostKeys();val audio=GeneralLocalStoreTest.Source().apply{put()}
        var recording=""
        GeneralLocalStoreTest.host(scoped,audioKeys).use{store->store.initializeEmpty();store.importSelected(audio)
            val r=store.snapshot().getJSONObject(0);recording=r.getString("file_id");store.edit(recording,r,"PUBLIC_CP32_MANUAL_NOTE","done")}
        RecordingWorkflowStore(scoped).use{it.reviewOwnSummary(recording,it.snapshot(recording),"PUBLIC_CP32_REVIEWED_SUMMARY")}
        val before=RecordingWorkflowStore(scoped).use{it.snapshot(recording).toString()};val opens=audio.opens;val policy=normalizedState()
        accepted()
        assertEquals(before,RecordingWorkflowStore(scoped).use{it.snapshot(recording).toString()});assertEquals(opens,audio.opens);assertEquals(policy,normalizedState())
        assertFalse(settings().snapshot().optBoolean("cloudEnabled"));assertNull(AutomationConnectionBindings.processing(context,session))
    }
    @Test fun C25PolicyChangeDuringTrustVerificationIsRecheckedInsideCommit()=io {
        authority.onVerify={settings().update{it.put("pendingRevocation",true)};settings().update{it.put("pendingRevocation",false)}}
        val before=dbHash()
        assertEquals(ApprovedProcessingSetupWriter.State.HELD_SOURCE_CHANGED,writer().receive().state)
        assertEquals(before,dbHash());assertNull(journalState());assertFalse(settings().snapshot().has(ApprovedProcessingSetupWriter.SETUP))
    }
    @Test fun C26ExpiredApprovalAndExpiryAfterSettingsCommitNeverComplete()=io {
        authority.onVerify={now=it.expiresAtMs}
        assertEquals(ApprovedProcessingSetupWriter.State.HELD_APPROVAL_REVIEW,writer().receive().state);assertEquals(0,gate().writes)
        authority.onVerify=null;gate().after={now+=3_600_001}
        assertEquals(ApprovedProcessingSetupWriter.State.HELD_APPROVAL_REVIEW,writer().receive().state)
        assertEquals("PREPARED",journalState());heldReader()
    }
    @Test fun C27WithdrawalBeforeFirstIntakeIsDurableAndCannotBeResurrected()=io {
        AutomationConnectionBindings.withdraw(context,session);val old=sealed();val before=dbHash()
        assertEquals(ApprovedProcessingSetupWriter.State.HELD_PROTECTED_STORE,writer().receive().state)
        assertEquals(old,sealed());assertEquals(before,dbHash());assertEquals(0,gate().writes)
    }
    @Test fun C28ReadonlyBindingStatesDistinguishAbsentExactChangedRevokedAndUnavailable()=io {
        val c=AutomationApiConfiguration(true,origin,owner,account,scope().epoch,"Etc/UTC",credential)
        fun status(v:AutomationApiConfiguration=c)=ApprovedProcessingIntakeJournal.inspectBinding(scoped,scope(),v)
        assertEquals(ApprovedProcessingIntakeJournal.BindingState.ABSENT,status())
        AutomationConnectionBindings.bindApprovedProcessing(context,session,c,::settings,{now})
        assertEquals(ApprovedProcessingIntakeJournal.BindingState.EXACT,status())
        assertEquals(ApprovedProcessingIntakeJournal.BindingState.CHANGED,status(c.copy(bearerCredential=credential+"_OTHER")))
        AutomationConnectionBindings.withdraw(context,session);assertEquals(ApprovedProcessingIntakeJournal.BindingState.REVOKED,status())
        ProtectedDatabase.file(scoped,GeneralLocalStore.DATABASE).delete()
        val creates=ShadowTextKeys.CREATES.get()
        assertEquals(ApprovedProcessingIntakeJournal.BindingState.STORE_UNAVAILABLE,status());assertEquals(creates,ShadowTextKeys.CREATES.get())
    }
    @Test fun C29ProtectedDbKeyLossAndCorruptBindingAreHeldWithoutInitialKeys()=io {
        val c=AutomationApiConfiguration(true,origin,owner,account,scope().epoch,"Etc/UTC",credential)
        AutomationConnectionBindings.bindApprovedProcessing(context,session,c,::settings,{now})
        ProtectedDatabase.open(scoped,GeneralLocalStore.DATABASE,null).use{p->p.transaction{
            p.database().execSQL("DROP TRIGGER automation_connection_binding_immutable")
            p.database().execSQL("UPDATE automation_connection_bindings SET digest='PUBLIC_CORRUPT_DIGEST'")
        }}
        assertEquals(ApprovedProcessingIntakeJournal.BindingState.CORRUPT,ApprovedProcessingIntakeJournal.inspectBinding(scoped,scope(),c))
        assertEquals(ApprovedProcessingSetupWriter.State.HELD_PROTECTED_STORE,writer().receive().state)
        val before=dbHash();val old=sealed();val creates=ShadowTextKeys.CREATES.get();ShadowTextKeys.KEYS.clear()
        assertEquals(ApprovedProcessingSetupWriter.State.HELD_PROTECTED_STORE,writer().receive().state)
        assertEquals(creates,ShadowTextKeys.CREATES.get());assertEquals(before,dbHash());assertEquals(old,sealed());assertEquals(0,gate().writes)
    }
    @Test fun C30TwoConcurrentIntakesFinishWithOneCommitAndOneIdempotentResult()=io {
        val barrier=CyclicBarrier(2);source.onLoad={barrier.await(5,TimeUnit.SECONDS)}
        val pool=Executors.newFixedThreadPool(2)
        try {
            val first=pool.submit<ApprovedProcessingSetupWriter.Outcome>{writer().receive()}
            val second=pool.submit<ApprovedProcessingSetupWriter.Outcome>{writer().receive()}
            val states=setOf(first.get(10,TimeUnit.SECONDS).state,second.get(10,TimeUnit.SECONDS).state)
            assertEquals(setOf(ApprovedProcessingSetupWriter.State.PREVIOUS_APPROVED_WORKER_SETUP_STORED,
                ApprovedProcessingSetupWriter.State.PREVIOUS_APPROVED_WORKER_SETUP_ALREADY_STORED),states)
            assertEquals(1,gate().writes);assertEquals("COMMITTED",journalState())
        }finally{pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS))}
    }
    @Test fun C31WithdrawDuringCommitWaitsThenBlocksNewReaderAfterDurableReturn()=io {
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val requested=CountDownLatch(1);val done=CountDownLatch(1)
        gate().before={entered.countDown();check(release.await(5,TimeUnit.SECONDS))}
        val pool=Executors.newFixedThreadPool(2)
        try {
            val intake=pool.submit<ApprovedProcessingSetupWriter.Outcome>{writer().receive()}
            assertTrue(entered.await(5,TimeUnit.SECONDS))
            val withdrawal=pool.submit {requested.countDown();AutomationConnectionBindings.withdraw(context,session);done.countDown()}
            assertTrue(requested.await(5,TimeUnit.SECONDS));assertFalse(done.await(100,TimeUnit.MILLISECONDS))
            release.countDown();assertEquals(ApprovedProcessingSetupWriter.State.PREVIOUS_APPROVED_WORKER_SETUP_STORED,intake.get(10,TimeUnit.SECONDS).state)
            withdrawal.get(10,TimeUnit.SECONDS);assertEquals(0L,done.count);assertEquals("REVOKED",journalState());heldReader()
        }finally{release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS))}
    }
    @Test fun C32PreparedRecordIsOnDiskBeforePreferencesAndContainsOriginalCiphertext()=io {
        val old=sealed();var observed=false
        gate().before={
            val file=ProtectedDatabase.file(scoped,GeneralLocalStore.DATABASE);val identity=file.canonicalPath
            val key=checkNotNull(ShadowTextKeys.KEYS["callsummary.text.v1."+DriveRelay.sha256(identity.toByteArray())])
            val bytes=file.readBytes();val cipher=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE,key,javax.crypto.spec.GCMParameterSpec(128,bytes.copyOfRange(1,13)))
            cipher.updateAAD(("IEUM_SQL_SNAPSHOT_V1\n"+identity).toByteArray())
            val disk=JSONObject(String(cipher.doFinal(bytes.copyOfRange(13,bytes.size))))
            val tables=disk.getJSONArray("tables")
            val table=(0 until tables.length()).map{tables.getJSONObject(it)}.single{it.getString("name")=="approved_processing_intakes"}
            val columns=table.getJSONArray("columns");val names=(0 until columns.length()).map{columns.getString(it)}
            val row=table.getJSONArray("rows").getJSONArray(0)
            assertEquals("PREPARED",row.getJSONObject(names.indexOf("state")).getString("s"))
            assertTrue(old==row.getJSONObject(names.indexOf("original_sealed")).getString("s"));observed=true
        }
        accepted();assertTrue(observed);assertEquals("COMMITTED",journalState())
    }
    @Test fun C33ReaderChecksExpiryAndPolicyAfterCommittedStorage()=io {
        accepted()
        try{ExistingApprovedProcessingSetup.read(context,session,::settings){now+3_600_001};fail("expired intake admitted")}catch(_:IllegalStateException){}
        settings().update{it.put("pendingRevocation",true)};settings().update{it.put("pendingRevocation",false)};heldReader()
    }
    @Test fun C34WithdrawUnavailableOrNestedTransactionCannotReportDurableSuccess()=io {
        ProtectedDatabase.open(scoped,GeneralLocalStore.DATABASE,null).use{p->p.transaction{
            try{AutomationConnectionBindings.withdraw(context,session);fail("nested withdrawal acknowledged")}catch(_:IllegalStateException){}
        }}
        ProtectedDatabase.file(scoped,GeneralLocalStore.DATABASE).delete()
        try{AutomationConnectionBindings.withdraw(context,session);fail("unavailable withdrawal acknowledged")}catch(_:IllegalStateException){}
    }
    private var fakeCapabilities=0
    private fun applyAfterCapability(change:()->Unit={}):String {
        return AutomationApprovedSetup(context,session,GoogleConnectionRegressionTest.FakeDrive(),existingProcessing={
            ExistingApprovedProcessingSetup.read(context,session,::settings,{now})
        },capability={c->object:AutomationProcessingHttp {
            override fun call(method:String,path:String,body:ByteArray?):AutomationHttpReply {
                fakeCapabilities++;assertEquals("GET",method);assertEquals("/v1/automation/capabilities",path);change()
                return AutomationHttpReply(200,StrictJson.bytes(mapOf("schema" to "ieum.automation.capabilities.v1",
                    "ownerId" to c.owner,"driveAccount" to c.account,"accountEpoch" to c.epoch,"reportZone" to c.reportZone,
                    "resultKind" to AutomationV2Codec.KIND,"resultSchemaVersion" to 2L,"dotInvoked" to false,
                    "driveResultWritten" to false,"canonicalWriteAllowed" to false)))
            }
        }},approvedSettings=::settings,approvedNow={now}).applyExistingProcessing()
    }
    private fun blockedApply(change:()->Unit) {
        var blocked=false
        try{applyAfterCapability(change)}catch(_:IllegalStateException){blocked=true}
        assertTrue("changed approval must block first binding",blocked)
        assertEquals(1,fakeCapabilities);assertNull(AutomationConnectionBindings.processing(context,session,::settings,{now}))
    }
    @Test fun C35WithdrawalDuringCapabilityCannotCreateFirstBinding()=io {
        accepted();blockedApply{AutomationConnectionBindings.withdraw(context,session)}
        assertEquals("REVOKED",journalState());heldReader()
    }
    @Test fun C36PolicyABADuringCapabilityCannotCreateFirstBinding()=io {
        accepted();blockedApply{settings().update{it.put("pendingRevocation",true)};settings().update{it.put("pendingRevocation",false)}}
        assertEquals("COMMITTED",journalState());heldReader()
    }
    @Test fun C37ApprovalExpiryDuringCapabilityCannotCreateFirstBinding()=io {
        accepted();blockedApply{now+=3_600_001};assertEquals("COMMITTED",journalState())
    }
    @Test fun C38UnchangedApprovedIntakeStillAllowsExplicitSeparateBinding()=io {
        accepted();assertNull(AutomationConnectionBindings.processing(context,session,::settings,{now}))
        assertEquals("PREVIOUS_APPROVED_WORKER_BOUND",applyAfterCapability());assertEquals(1,fakeCapabilities)
        assertTrue(AutomationConnectionBindings.processing(context,session,::settings,{now})?.bearerCredential==credential)
    }
    @Test fun C39ExistingBindingCannotBypassNewIntakePolicyFence()=io {
        accepted();applyAfterCapability();settings().update{it.put("pendingRevocation",true)};settings().update{it.put("pendingRevocation",false)}
        try{AutomationConnectionBindings.processing(context,session,::settings,{now});fail("binding bypassed current policy")}catch(_:IllegalStateException){}
    }
    @Test fun C40ExistingBindingCannotBypassNewIntakeExpiry()=io {
        accepted();applyAfterCapability();now+=3_600_001
        try{AutomationConnectionBindings.processing(context,session,::settings,{now});fail("binding bypassed approval expiry")}catch(_:IllegalStateException){}
    }
}
