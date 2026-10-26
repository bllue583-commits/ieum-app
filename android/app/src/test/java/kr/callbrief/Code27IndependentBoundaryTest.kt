package kr.callbrief

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import kr.callagent.drive.StrictJson
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="ko-rKR-w360dp-h800dp-xhdpi",shadows=[ShadowTextKeys::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
internal class Code27IndependentBoundaryTest:ProtectedTextHostTestBase() {
    companion object {
        lateinit var uiChannel:SyntheticTextMcpChannel
        var uiNow=Instant.parse("2026-10-14T00:00:00Z").toEpochMilli()
    }
    class UiHost:SyntheticTextConnectionActivity() {
        override fun createPort()=McpSyntheticTextGenerationPort(uiChannel)
        override fun createCoordinator(port:SyntheticTextGenerationPort)=SyntheticTextForegroundCoordinator(this,port,{uiNow})
    }
    private lateinit var context:Context
    private var now=Instant.parse("2026-10-14T00:00:00Z").toEpochMilli()
    @Before fun setup(){context=RuntimeEnvironment.getApplication();uiNow=now}
    private fun <T> io(action:suspend ()->T)=runBlocking(Dispatchers.IO){withTimeout(20_000){action()}}
    private inner class Channel(session:String="INDEPENDENT") : SyntheticTextMcpChannel {
        val initial=SyntheticTextLocalFixtureChannel.LOCAL_SCOPE.copy(session=session)
        @Volatile var live:SyntheticTextConnectionScope?=initial
        private val delegate=SyntheticTextLocalFixtureChannel(context,{now},initial)
        override val origin=SyntheticTextContract.LOCAL_ORIGIN
        override fun binding()=live
        val calls=CopyOnWriteArrayList<Pair<String,Map<String,Any?>>>()
        var before:suspend(String)->Unit={}
        var after:suspend(String)->Unit={}
        override suspend fun exchange(request:ByteArray):ByteArray {
            val raw=SyntheticTextContract.obj(StrictJson.parse(request))
            val params=SyntheticTextContract.obj(raw["params"])
            val tool=params["name"] as String
            calls.add(tool to SyntheticTextContract.obj(params["arguments"]))
            before(tool);val result=delegate.exchange(request);after(tool);return result
        }
        fun count(tool:String)=calls.count{it.first==tool}
    }
    private fun coordinator(c:Channel)=SyntheticTextForegroundCoordinator(context,McpSyntheticTextGenerationPort(c),{now})
    private suspend fun ready(c:Channel)=coordinator(c).startExplicit().also {
        assertEquals("RESULT_RECEIVED",it.state);assertNotNull(it.view?.result)
        assertFalse(it.nativeConnected);assertFalse(it.canonicalWriteAllowed)
    }
    private fun proofBody(proof:String):JSONObject=TextStoreTestSupport.open(context,SyntheticTextProofStore.DATABASE).use { db->
        db.rawQuery("SELECT body FROM sg_proofs WHERE proof_id=?",arrayOf(proof)).use { it.moveToFirst();JSONObject(it.getString(0)) }
    }
    private fun mutateProof(proof:String,action:(JSONObject)->Unit) {
        TextStoreTestSupport.open(context,SyntheticTextProofStore.DATABASE).use { db->
            val b=proofBody(proof);action(b);db.execSQL("UPDATE sg_proofs SET body=? WHERE proof_id=?",arrayOf(b.toString(),proof))
        }
    }
    private fun evidence(name:String,values:Map<String,Any?>) {
        val v=JSONObject().put("id",name).put("scope","INDEPENDENT_API35_ROBOLECTRIC_NATIVE_SQLITE_JCE_SYNTHETIC_ONLY")
            .put("macUnlock",false).put("actualNativeSite",false).put("observation",JSONObject(values))
        File("build/reports/code27-independent").apply{mkdirs()}.resolve("$name.json").writeText(v.toString(2))
    }
    private suspend fun held(c:Channel):Boolean=try {
        val v=coordinator(c).load();evidence("last-load",mapOf("state" to v.state,"resultPresent" to (v.view?.result!=null)))
        v.state!="RESULT_RECEIVED"
    }catch(_:RuntimeException){true}

    @Test fun P01ForgedReceivedStateWithoutReadbackMustNotDisplayReceiptSuccess()=io {
        val c=Channel("STATE_FORGERY")
        val v=SyntheticTextProofStore(context,c.initial,c::binding).use { it.explicitStart() }
        mutateProof(v.proof){it.put("state","RESULT_RECEIVED")}
        val out=coordinator(c).load()
        evidence("P01",mapOf("state" to out.state,"startPresent" to (out.view?.start!=null),
            "resultPresent" to (out.view?.result!=null),"nativeConnected" to out.nativeConnected))
        assertNotEquals("A stored label must not manufacture result receipt success","RESULT_RECEIVED",out.state)
    }
    @Test fun P02CachedStartTimeMustStillValidateImmutableStartReceipt()=io {
        val accepted=mutableListOf<String>()
        for(mode in listOf("INVALID_ISO","VALID_ISO_WRONG_TTL")){
            val c=Channel("TIME_$mode");val done=ready(c);val proof=done.view!!.proof
            mutateProof(proof){b->val start=b.getJSONObject("start")
                start.put("created",if(mode=="INVALID_ISO")"not-an-instant" else Instant.ofEpochMilli(now+1).toString())
            }
            if(!held(c))accepted.add(mode)
        }
        evidence("P02",mapOf("invalidCachedTimesAccepted" to accepted))
        assertEquals("Cached result must keep validated created/expires binding",emptyList<String>(),accepted)
    }
    @Test fun P03CachedForeignGenerationCannotReuseIdenticalDraftHash()=io {
        val c=Channel("GENERATION_SWAP");val a=ready(c);val b=ready(c)
        assertEquals(a.view!!.expectedHash,b.view!!.expectedHash)
        mutateProof(b.view.proof){body->
            body.getJSONObject("start").put("generation",a.view.start!!.generation)
            body.put("expected_id",GenerationResultId.make(a.view.start!!.generation,b.view.suffix))
        }
        assertTrue(held(c));evidence("P03",mapOf("sameHashForeignGenerationHeld" to true))
    }
    @Test fun P04OwnerSessionEpochTamperAndCurrentOwnerSwitchStayIsolated()=io {
        val c=Channel("OWNER_EPOCH");val a=ready(c)
        mutateProof(a.view!!.proof){it.put("epoch",2)}
        assertTrue(held(c))
        c.live=c.initial.copy(owner="INDEPENDENT_OTHER_OWNER",session="OTHER_SESSION",epoch=2)
        assertEquals("IDLE",coordinator(c).load().state);assertEquals(1,c.count("start_demo_generation"))
        evidence("P04",mapOf("oldEpochHeld" to true,"otherOwnerEmpty" to true))
    }
    @Test fun P05FourCompetingCoordinatorsCannotAdmitDuringOneDurableClaim()=io { coroutineScope {
        val c=Channel("MULTI_CLICK");val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        c.before={if(it=="start_demo_generation"){entered.complete(Unit);release.await()}}
        val first=async { coordinator(c).startExplicit() };entered.await()
        val replies=(0 until 4).map { coordinator(c).startExplicit().state }
        assertTrue(replies.all{it=="BUSY"});release.complete(Unit);assertEquals("RESULT_RECEIVED",first.await().state)
        assertEquals(1,c.count("start_demo_generation"));evidence("P05",mapOf("competingCalls" to 4,"admissions" to 1))
    } }
    @Test fun P06ConsecutiveLostStartAndSaveResponsesReuseAllKeys()=io {
        val c=Channel("TWO_LOSSES");var loseStart=true;var loseSave=true
        c.after={if(it=="start_demo_generation"&&loseStart){loseStart=false;throw IOException("PUBLIC_SYNTHETIC_START_LOSS")}
            if(it=="save_demo_result"&&loseSave){loseSave=false;throw IOException("PUBLIC_SYNTHETIC_SAVE_LOSS")}}
        val first=coordinator(c).startExplicit();assertEquals("UNKNOWN",first.state);val p=first.view!!
        val second=coordinator(c).resumeExplicit(p.proof);assertEquals("UNKNOWN",second.state)
        val last=coordinator(c).readOnly(p.proof);assertEquals("RESULT_RECEIVED",last.state)
        assertEquals(p.proof,last.view!!.proof);assertEquals(p.suffix,last.view.suffix)
        assertEquals(second.view!!.expectedId,last.view.expectedId);assertEquals(1,c.count("save_demo_result"))
        assertTrue(c.calls.filter{it.first=="start_demo_generation"}.all{it.second["proof_request_id"]==p.proof})
        evidence("P06",mapOf("proofSuffixResultIdPreserved" to true,"saveCount" to 1))
    }
    @Test fun P07RepeatedReadbackKeepsImmutableReceiptsAndDoesNotResave()=io {
        val c=Channel("DUP_RECEIPTS");val done=ready(c);val proof=done.view!!.proof
        val before=SyntheticTextProofStore(context,c.initial,c::binding).use { it.receiptCount() }
        repeat(3){assertEquals("RESULT_RECEIVED",coordinator(c).readOnly(proof).state)}
        val after=SyntheticTextProofStore(context,c.initial,c::binding).use { it.receiptCount() }
        assertEquals(before,after);assertEquals(1,c.count("save_demo_result"))
        TextStoreTestSupport.open(context,SyntheticTextProofStore.DATABASE).use { db->
            try{db.execSQL("UPDATE sg_receipts SET digest='forged'");fail("immutable receipt update accepted")}catch(_:RuntimeException){}
            try{db.execSQL("DELETE FROM sg_receipts");fail("immutable receipt delete accepted")}catch(_:RuntimeException){}
        }
        assertEquals("RESULT_RECEIVED",coordinator(c).load().state);evidence("P07",mapOf("receiptCountBefore" to before,"receiptCountAfter" to after))
    }
    private fun field(a:Activity,key:String)=SyntheticTextConnectionActivity::class.java.getDeclaredField(key).apply{isAccessible=true}.get(a)
    private fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{views(v.getChildAt(it))}else emptyList()
    private fun settle(a:Activity){repeat(400){shadowOf(Looper.getMainLooper()).idle();if(field(a,"busy")==false)return;Thread.sleep(10)};fail("INDEPENDENT_UI_NOT_SETTLED")}
    private fun ui(c:Channel):org.robolectric.android.controller.ActivityController<UiHost> {
        uiChannel=c;uiNow=now
        return Robolectric.buildActivity(UiHost::class.java,Intent(context,UiHost::class.java).putExtra("local_fixture",true)).setup().also{settle(it.get())}
    }
    @Test fun P08ExpiredUiHidesStoredProposalAndKeepsNativeDisconnected() {
        val c=Channel("EXPIRED_UI");val fixture=ui(c);var root=field(fixture.get(),"root") as ViewGroup
        root.findViewWithTag<Button>("sg_start").performClick();settle(fixture.get())
        val first=field(fixture.get(),"data") as SyntheticTextForegroundCoordinator.Result
        assertEquals("RESULT_RECEIVED",first.state);now=first.view!!.start!!.expiresAt;uiNow=now
        fixture.pause().stop().restart().start().resume();settle(fixture.get())
        val after=field(fixture.get(),"data") as SyntheticTextForegroundCoordinator.Result;root=field(fixture.get(),"root") as ViewGroup
        assertEquals("EXPIRED",after.state);assertNull(root.findViewWithTag<TextView>("sg_summary"));assertFalse(after.nativeConnected)
        fixture.pause().stop().destroy();evidence("P08",mapOf("expiredSummaryVisible" to false,"nativeConnected" to false))
    }
    @Test fun P09StoppedActivityCannotPublishLateReturnedReadback() {
        val c=Channel("STOPPED_UI");val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        c.before={if(it=="get_demo_result"&&c.count(it)==2){entered.complete(Unit);release.await()}}
        val fixture=ui(c);val root=field(fixture.get(),"root") as ViewGroup;root.findViewWithTag<Button>("sg_start").performClick()
        runBlocking { withTimeout(5_000){entered.await()} };fixture.pause().stop();release.complete(Unit)
        repeat(40){shadowOf(Looper.getMainLooper()).idle();Thread.sleep(5)}
        val after=field(fixture.get(),"data") as SyntheticTextForegroundCoordinator.Result
        assertNotEquals("RESULT_RECEIVED",after.state);assertFalse(after.nativeConnected);fixture.destroy()
        val saved=io{coordinator(c).load()};assertEquals("UNKNOWN",saved.state);assertNull(saved.view!!.result)
        val recovered=io{coordinator(c).resumeExplicit(saved.view.proof)};assertEquals("RESULT_RECEIVED",recovered.state);assertEquals(1,c.count("save_demo_result"))
        evidence("P09",mapOf("lateResultNotPublished" to true,"sameProofRecoverable" to true))
    }
    @Test fun P10ForgedBrowserReturnAndValidLookingPayloadCannotConnectNative() {
        val intent=Intent(context,SyntheticTextConnectionActivity::class.java)
            .putExtra("connected",true).putExtra("owner","PUBLIC_FAKE_OWNER").putExtra("session","PUBLIC_FAKE_SESSION")
            .putExtra("epoch",1L).putExtra("proof_request_id","00000000-0000-4000-8000-000000000001")
            .putExtra("result_json",StrictJson.stringify(SyntheticTextContract.validateInput(SyntheticTextContract.draft())))
        val fixture=Robolectric.buildActivity(SyntheticTextConnectionActivity::class.java,intent).setup();settle(fixture.get())
        val data=field(fixture.get(),"data") as SyntheticTextForegroundCoordinator.Result
        assertEquals("NATIVE_RETURN_UNSUPPORTED",data.state);assertNull(data.view);assertFalse(data.nativeConnected)
        val root=field(fixture.get(),"root") as ViewGroup;assertFalse(root.findViewWithTag<Button>("sg_native_start").isEnabled)
        root.findViewWithTag<Button>("sg_browser_handoff").performClick()
        val browser=shadowOf(fixture.get()).nextStartedActivity;assertEquals(Intent.ACTION_VIEW,browser.action);assertNull(browser.extras)
        fixture.pause().stop().destroy();evidence("P10",mapOf("forgedReturnAccepted" to false,"browserIntentOnly" to true))
    }
}
