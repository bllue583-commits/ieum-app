package kr.callbrief

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.*
import kr.callagent.drive.StrictJson
import kr.co.callsummary.core.android.ProtectedDatabase
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
import org.robolectric.annotation.SQLiteMode
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="ko-rKR-w360dp-h800dp-xhdpi",shadows=[ShadowTextKeys::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
internal class SyntheticTextGenerationTest:ProtectedTextHostTestBase() {
    private lateinit var context:Context
    private var now=Instant.parse("2026-10-13T16:00:00Z").toEpochMilli()
    private val a="00000000-0000-4000-8000-000000000001";private val b="00000000-0000-4000-8000-000000000002"
    @Before fun setup(){context=RuntimeEnvironment.getApplication()}
    private fun <T> io(action:suspend ()->T)=runBlocking(Dispatchers.IO){withTimeout(20_000){action()}}
    private inner class FaultChannel(val initial:SyntheticTextConnectionScope=SyntheticTextLocalFixtureChannel.LOCAL_SCOPE):SyntheticTextMcpChannel {
        private val delegate=SyntheticTextLocalFixtureChannel(context,{now},initial)
        @Volatile var live:SyntheticTextConnectionScope?=initial
        override val origin=SyntheticTextContract.LOCAL_ORIGIN
        override fun binding()=live
        val calls=CopyOnWriteArrayList<Pair<String,Map<String,Any?>>>()
        var before:suspend (String)->Unit={};var after:suspend (String)->Unit={};var mutate:(String,JSONObject)->Unit={_,_->}
        override suspend fun exchange(request:ByteArray):ByteArray {
            val rpc=SyntheticTextContract.obj(StrictJson.parse(request));val params=SyntheticTextContract.obj(rpc["params"]);val tool=params["name"] as String;calls.add(tool to SyntheticTextContract.obj(params["arguments"]))
            before(tool);val response=delegate.exchange(request);after(tool);val json=JSONObject(String(response));mutate(tool,json);return json.toString().toByteArray()
        }
        fun count(tool:String)=calls.count { it.first==tool }
    }
    private fun port(channel:FaultChannel)=McpSyntheticTextGenerationPort(channel)
    private fun coordinator(channel:FaultChannel)=SyntheticTextForegroundCoordinator(context,port(channel),{now})
    private fun store(channel:FaultChannel,action:(SyntheticTextProofStore)->Unit)=SyntheticTextProofStore(context,channel.initial,channel::binding).use(action)
    private fun data(json:JSONObject)=json.getJSONObject("result").getJSONObject("structuredContent")
    private fun evidence(id:String,vararg values:Pair<String,Any?>) { val json=JSONObject().put("id",id).put("pass",true).put("scope","API35_HOST_PROTECTED_SQLITE_JCE_LOCAL_MCP_DOUBLE_ONLY").put("realNetwork",0).put("actualNativeAuthentication",false).put("canonicalApplication",0);values.forEach { json.put(it.first,it.second ?: JSONObject.NULL) };File("build/reports/sg-v1").apply { mkdirs() }.resolve("$id.json").writeText(json.toString(2)) }
    private suspend fun ready(channel:FaultChannel)=coordinator(channel).startExplicit().also { assertEquals("RESULT_RECEIVED",it.state);assertFalse(it.nativeConnected);assertFalse(it.canonicalWriteAllowed);assertNotNull(it.view?.result) }
    private fun rows(name:String):Long=TextStoreTestSupport.open(context,SyntheticTextLocalFixtureChannel.DATABASE).use { db->db.rawQuery("SELECT count(*) FROM $name",null).use { it.moveToFirst();it.getLong(0) } }
    private fun reject(action:()->Unit) { try{action();fail("unsafe input accepted")}catch(_:IllegalArgumentException){} }
    @Test fun S01PublishedV4GoldenFingerprintAndExactCharacters() {
        val result=SyntheticGenerationFingerprint.validate(SyntheticTextContract.draft());assertEquals(SyntheticTextContract.GOLDEN,SyntheticGenerationFingerprint.hashValidated(result))
        val vectors=mutableListOf<Map<String,Any?>>(SyntheticTextContract.draft())
        vectors+=SyntheticTextContract.draft()+mapOf("summary" to " \ufeff유니코드 😀\u2028 줄\n\"따옴표\" \\ 끝 ")
        val dir=File("build/reports/sg-v1").apply { mkdirs() };dir.resolve("kotlin-golden-vectors.json").writeText(JSONObject().put("vectors",org.json.JSONArray(vectors.map { input->JSONObject().put("input",JSONObject(input)).put("canonical",StrictJson.stringify(SyntheticTextContract.validateInput(input))).put("sha256",SyntheticTextContract.fingerprint(SyntheticTextContract.validateInput(input))) })).toString(2))
        evidence("S01","fingerprint" to SyntheticTextContract.GOLDEN)
    }
    @Test fun S02ResultIdUsesThePersistedLowercaseSuffixOnly() {
        assertEquals("$a:$b",GenerationResultId.make(a,b));reject { GenerationResultId.make(a.uppercase()+"A",b) };reject { GenerationResultId.requireFor(a,"$b:$a") };reject { GenerationResultId.requireFor(a,b) };evidence("S02")
    }
    @Test fun S03DoneDowngradeDoesNotNormalizeStoredText() {
        val input=SyntheticTextContract.draft().toMutableMap();val t=SyntheticTextContract.obj((input["tasks"] as List<*>).single()).toMutableMap();t["title"]=" ＡＢＣ ";t["status"]="done";input["tasks"]=listOf(t)
        val result=SyntheticTextContract.validateInput(input);val checked=SyntheticTextContract.obj((result["tasks"] as List<*>).single());assertEquals(" ＡＢＣ ",checked["title"]);assertEquals("needs_confirmation",checked["status"]);assertEquals(false,result["applied"])
        reject { SyntheticTextContract.validatedReadback(input+mapOf("applied" to true)) };evidence("S03")
    }
    @Test fun S04DuplicateNfkcComparisonRejectsWithoutRewritingTitle() {
        val input=SyntheticTextContract.draft().toMutableMap();val t=SyntheticTextContract.obj((input["tasks"] as List<*>).single()).toMutableMap();t["title"]="ＡＢＣ";t["owner"]="USER";input["tasks"]=listOf(t,t+mapOf("title" to "a b c","owner" to "user"))
        reject { SyntheticTextContract.validateInput(input) };evidence("S04")
    }
    @Test fun S05StructuredMcpOnlyNoTextFallbackOrWrongRpcId() {
        val textOnly=JSONObject().put("jsonrpc","2.0").put("id",1).put("result",JSONObject().put("resultType","complete").put("isError",false).put("content",org.json.JSONArray().put(JSONObject().put("type","text").put("text","{\"status\":\"result_ready\"}")))).toString().toByteArray()
        reject { SyntheticTextMcpResponseMapper.read(textOnly,1) };reject { SyntheticTextMcpResponseMapper.read(textOnly,2) };evidence("S05")
    }
    @Test fun S32SignedMcpErrorsKeepIntegerTokenAndUtf8Validation() {
        val error="{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32003,\"data\":{\"reason\":\"missing_identity\"}}}"
        try{SyntheticTextMcpResponseMapper.read(error.toByteArray(),1);fail("signed MCP error not held")}catch(e:SyntheticTextFailure){assertEquals("missing_identity",e.reason)}
        for(token in listOf("1.0","1e0","9007199254740992"))reject { SyntheticTextMcpResponseMapper.read(error.replace("\"id\":1,","\"id\":$token,").toByteArray(),1) }
        reject { SyntheticTextMcpResponseMapper.read(error.replace("\"id\":1,","\"id\":1,\"id\":1,").toByteArray(),1) }
        reject { SyntheticTextMcpResponseMapper.read(byteArrayOf(0xc3.toByte(),0x28),1) };evidence("S32")
    }
    @Test fun S06DurableForegroundRequiresVerifiedReadback()=io {
        val c=FaultChannel();val result=ready(c);assertEquals(1,c.count("start_demo_generation"));assertEquals(1,c.count("save_demo_result"));assertEquals(2,c.count("get_demo_result"));assertEquals(1L,result.view!!.start!!.revision)
        store(c) { assertTrue(it.receiptCount()>=4) };evidence("S06","proof" to result.view!!.proof,"generation" to result.view.start!!.generation,"hash" to result.view.expectedHash)
    }
    @Test fun S07StartCommitResponseLossAndReopenKeepProofAndSuffix()=io {
        val c=FaultChannel();var fail=true;c.after={if(it=="start_demo_generation"&&fail){fail=false;throw IOException("PUBLIC_LOST_RESPONSE")}}
        val first=coordinator(c).startExplicit();assertEquals("UNKNOWN",first.state);val v=first.view!!;assertNull(v.start);assertEquals(1L,rows("fixture_generations"))
        val next=FaultChannel();val done=coordinator(next).resumeExplicit(v.proof);assertEquals("RESULT_RECEIVED",done.state);assertEquals(v.proof,done.view!!.proof);assertEquals(v.suffix,done.view.suffix);assertEquals(1L,rows("fixture_generations"));evidence("S07","same_proof" to true,"same_suffix" to true)
    }
    @Test fun S08SaveCommitResponseLossIsRecoveredByReadWithoutResave()=io {
        val c=FaultChannel();var fail=true;c.after={if(it=="save_demo_result"&&fail){fail=false;throw IOException("PUBLIC_SAVE_RESPONSE_LOST")}}
        val first=coordinator(c).startExplicit();assertEquals("UNKNOWN",first.state);val v=first.view!!;assertNotNull(v.expectedId);assertNull(v.result)
        val done=coordinator(c).resumeExplicit(v.proof);assertEquals("RESULT_RECEIVED",done.state);assertEquals(v.expectedId,done.view!!.expectedId);assertEquals(v.expectedHash,done.view.expectedHash);assertEquals(1,c.count("save_demo_result"));evidence("S08","resave" to 0)
    }
    @Test fun S09SaveAckAloneCannotMarkReceived()=io {
        val c=FaultChannel();c.after={if(it=="get_demo_result"&&c.count(it)==2)throw IOException("PUBLIC_READBACK_LOSS")}
        val first=coordinator(c).startExplicit();assertEquals("UNKNOWN",first.state);assertNull(first.view!!.result);c.after={};val next=coordinator(c).readOnly(first.view.proof);assertEquals("RESULT_RECEIVED",next.state);assertEquals(1,c.count("save_demo_result"));evidence("S09")
    }
    @Test fun S10DoubleTapOneCoordinatorAdmitsOneProof()=io { coroutineScope {
        val c=FaultChannel();val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();c.before={if(it=="start_demo_generation"){entered.complete(Unit);release.await()}}
        val run=coordinator(c);val one=async { run.startExplicit() };entered.await();val duplicate=run.startExplicit();assertEquals("BUSY",duplicate.state);release.complete(Unit);assertEquals("RESULT_RECEIVED",one.await().state);assertEquals(1,c.count("start_demo_generation"));evidence("S10")
    } }
    @Test fun S11TwoCoordinatorsShareDurableClaim()=io { coroutineScope {
        val c=FaultChannel();val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();c.before={if(it=="start_demo_generation"){entered.complete(Unit);release.await()}}
        val one=async { coordinator(c).startExplicit() };entered.await();assertEquals("BUSY",coordinator(c).startExplicit().state);release.complete(Unit);assertEquals("RESULT_RECEIVED",one.await().state);assertEquals(1,c.count("start_demo_generation"));evidence("S11")
    } }
    @Test fun S12LateOwnerOrEpochResponseCannotEnterCurrentStore()=io {
        val c=FaultChannel();c.after={if(it=="start_demo_generation")c.live=c.initial.copy(owner="OTHER_PUBLIC_OWNER",epoch=2)}
        val held=coordinator(c).startExplicit();assertEquals("SCOPE_HELD",held.state);assertNull(held.view);assertEquals("IDLE",coordinator(c).load().state);assertEquals(0,c.count("save_demo_result"));evidence("S12")
    }
    @Test fun S13LateGenerationAndWrongPrefixNeverBecomeReceived()=io {
        for(mode in listOf("generation","result_id")) {
            val scope=SyntheticTextLocalFixtureChannel.LOCAL_SCOPE.copy(session="PUBLIC_$mode");val c=FaultChannel(scope)
            c.mutate={tool,json->if(tool=="get_demo_result"&&data(json).optString("status")=="result_ready") { if(mode=="generation")data(json).put("generation_id",a) else data(json).put("result_id",data(json).getString("generation_id")+":"+b) }}
            val held=coordinator(c).startExplicit();assertTrue(held.state in listOf("RESPONSE_REVIEW","CONFLICT"));assertNull(held.view?.result)
        };evidence("S13")
    }
    @Test fun S14FingerprintTamperIsIntegrityHold()=io {
        val c=FaultChannel();c.mutate={tool,json->if(tool=="get_demo_result"&&data(json).optString("status")=="result_ready")data(json).put("result_hash","0".repeat(64))}
        val held=coordinator(c).startExplicit();assertEquals("STORE_REVIEW",held.state);assertNull(held.view!!.result);evidence("S14")
    }
    @Test fun S15AppliedTrueOrInvalidFixtureQuoteIsWithheld()=io {
        for(mode in listOf("applied","quote")) {
            val c=FaultChannel(SyntheticTextLocalFixtureChannel.LOCAL_SCOPE.copy(session="PUBLIC_$mode"));c.mutate={tool,json->if(tool=="get_demo_result"&&data(json).optString("status")=="result_ready") {
                val result=data(json).getJSONObject("result");if(mode=="applied")result.put("applied",true) else result.getJSONArray("tasks").getJSONObject(0).getJSONObject("evidence").put("quote","not present in fixed text")
            }};val held=coordinator(c).startExplicit();assertEquals("RESPONSE_REVIEW",held.state);assertNull(held.view?.result)
        };evidence("S15")
    }
    @Test fun S16ExpiryBoundaryKeepsResultAndNeverStartsOrExtends()=io {
        val c=FaultChannel();val first=ready(c);val v=first.view!!;now=v.start!!.expiresAt;val calls=c.calls.size
        val held=coordinator(c).resumeExplicit(v.proof);assertEquals("EXPIRED",held.state);assertEquals(v.result,held.view!!.result);assertEquals(calls,c.calls.size);assertEquals(1L,rows("fixture_generations"));evidence("S16","retained_hash" to held.view.expectedHash)
    }
    @Test fun S17ExpiryBetweenSaveAckAndReadbackDoesNotComplete()=io {
        val c=FaultChannel();c.after={if(it=="save_demo_result")now+=SyntheticTextContract.TTL};val held=coordinator(c).startExplicit();assertEquals("EXPIRED",held.state);assertNull(held.view!!.result);assertEquals(1L,rows("fixture_generations"));evidence("S17")
    }
    @Test fun S18McpAuthExpiredUnknownToolAndStorageErrorsStayHeld()=io {
        val cases=mapOf("missing_identity" to "AUTH_HELD","access_revoked" to "AUTH_HELD","job_expired" to "EXPIRED","unknown_tool" to "NATIVE_RETURN_UNSUPPORTED","storage_unavailable" to "UNKNOWN","result_conflict" to "CONFLICT")
        for((reason,state)in cases) { val c=FaultChannel(SyntheticTextLocalFixtureChannel.LOCAL_SCOPE.copy(session="PUBLIC_$reason"));c.mutate={_,json->json.put("result",JSONObject().put("resultType","complete").put("isError",true).put("structuredContent",JSONObject().put("error",JSONObject().put("reason",reason).put("code",-32003))))};val held=coordinator(c).startExplicit();assertEquals(reason,state,held.state);assertNull(held.view?.result) };evidence("S18")
    }
    @Test fun S19WrongProofOrLifetimeInStartIsNotAdmitted()=io {
        for(mode in listOf("proof","lifetime")) { val c=FaultChannel(SyntheticTextLocalFixtureChannel.LOCAL_SCOPE.copy(session="PUBLIC_$mode"));c.mutate={tool,json->if(tool=="start_demo_generation") { if(mode=="proof")data(json).put("proof_request_id",b) else data(json).put("expires_at",data(json).getLong("expires_at")+1) }};val held=coordinator(c).startExplicit();assertEquals("RESPONSE_REVIEW",held.state);assertNull(held.view!!.start);assertEquals(0,c.count("save_demo_result")) };evidence("S19")
    }
    @Test fun S20NativeGateNeverCreatesProofOrAuthenticates()=io {
        val run=SyntheticTextForegroundCoordinator(context,UnavailableNativeSyntheticTextPort());assertEquals("NATIVE_RETURN_UNSUPPORTED",run.startExplicit().state);assertFalse(ProtectedDatabase.file(context,SyntheticTextProofStore.DATABASE).exists());assertFalse(NativeAnalysisAvailability.canTransfer());evidence("S20","RPC_calls" to 0)
    }
    @Test fun S21ToolArgumentsCannotSupplyOwnerOrLegacyRouting()=io {
        val c=FaultChannel();val p=port(c);try { p.call("start_demo_generation",mapOf("sample_id" to SyntheticTextContract.SAMPLE,"proof_request_id" to a,"owner" to "forged"));fail("owner argument accepted") }catch(_:IllegalArgumentException){}
        try { p.call("get_demo_result",mapOf("job_id" to SyntheticTextContract.JOB));fail("legacy routing accepted") }catch(_:IllegalArgumentException){};assertEquals(0,c.calls.size);evidence("S21")
    }
    @Test fun S22ExplicitNewStartAloneCreatesAnotherGeneration()=io {
        val c=FaultChannel();val one=ready(c);val read=coordinator(c).readOnly(one.view!!.proof);assertEquals("RESULT_RECEIVED",read.state);assertEquals(one.view.proof,read.view!!.proof);assertEquals(1L,rows("fixture_generations"));val two=ready(c);assertNotEquals(one.view.proof,two.view!!.proof);assertNotEquals(one.view.start!!.generation,two.view.start!!.generation);assertEquals(2L,rows("fixture_generations"));evidence("S22")
    }
    @Test fun S23ForegroundCancellationRetainsKeysAndDoesNotSave()=io { coroutineScope {
        val c=FaultChannel();val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();c.before={if(it=="save_demo_result"){entered.complete(Unit);release.await()}}
        val one=async { coordinator(c).startExplicit() };entered.await();one.cancelAndJoin();var v:SyntheticTextProofStore.View?=null;store(c){v=it.current()};assertEquals("UNKNOWN",v!!.state);assertNotNull(v!!.expectedId)
        c.before={};val done=coordinator(c).resumeExplicit(v!!.proof);assertEquals("RESULT_RECEIVED",done.state);assertEquals(v!!.expectedId,done.view!!.expectedId);evidence("S23")
    } }
    @Test fun S24ImmutableReceiptsAndEncryptedFiles()=io {
        val c=FaultChannel();val done=ready(c);TextStoreTestSupport.open(context,SyntheticTextProofStore.DATABASE).use { db->try{db.execSQL("UPDATE sg_receipts SET kind='forged'");fail("receipt changed")}catch(_:RuntimeException){} }
        context.noBackupFilesDir.walkTopDown().filter{it.isFile}.forEach { assertFalse(it.path,String(it.readBytes()).contains("가상 샘플 배송을 13일로 조정")) };assertEquals(SyntheticTextContract.GOLDEN,done.view!!.expectedHash);evidence("S24")
    }
    @Test fun S25MissingExistingStorageKeyCannotResetOrReprovision()=io {
        val c=FaultChannel();ready(c);val creates=ShadowTextKeys.CREATES.get();ShadowTextKeys.KEYS.clear();try{coordinator(c).load();fail("missing existing key accepted")}catch(_:RuntimeException){};assertEquals(creates,ShadowTextKeys.CREATES.get());evidence("S25")
    }
    @Test fun S26LegacyRecordingAndManualDoneBytesAreUnchanged()=io {
        val keys=SyntheticPilotIntegrationTest.HostKeys();val source=GeneralLocalStoreTest.Source().apply { put() };GeneralLocalStoreTest.host(context,keys).use { s->s.initializeEmpty();s.importSelected(source);val row=s.snapshot().getJSONObject(0);s.edit(row.getString("file_id"),row,"PUBLIC_SG_KEEP_MANUAL_DONE","done") }
        val file=ProtectedDatabase.file(context,GeneralLocalStore.DATABASE);val before=file.readBytes();val opens=source.opens;ready(FaultChannel());assertArrayEquals(before,file.readBytes());assertEquals(opens,source.opens);assertFalse(NativeAnalysisAvailability.canTransfer());assertEquals(SyntheticTextContract.TTL,86_400_000L);evidence("S26","original_raw_reads" to 0,"legacy_bytes_unchanged" to true)
    }
    private fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{views(v.getChildAt(it))}else emptyList()
    private fun field(a:Activity,key:String)=SyntheticTextConnectionActivity::class.java.getDeclaredField(key).apply {isAccessible=true}.get(a)
    private fun settle(a:Activity) { repeat(400) { shadowOf(Looper.getMainLooper()).idle();if(field(a,"busy")==false)return;Thread.sleep(10) };fail("SG_UI_NOT_SETTLED") }
    private fun render(v:View,name:String) { v.measure(View.MeasureSpec.makeMeasureSpec(720,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1600,View.MeasureSpec.EXACTLY));v.layout(0,0,720,1600);val b=Bitmap.createBitmap(720,1600,Bitmap.Config.ARGB_8888);v.draw(Canvas(b));val out=File("build/reports/sg-v1/screens").apply {mkdirs()};FileOutputStream(File(out,"$name.png")).use{b.compress(Bitmap.CompressFormat.PNG,100,it)} }
    @Test fun S27DefaultUiAndForgedReturnExtrasRemainNativeDisconnected() {
        val intent=Intent(context,SyntheticTextConnectionActivity::class.java).putExtra("owner","forged").putExtra("generation_id",a).putExtra("connected",true)
        val c=Robolectric.buildActivity(SyntheticTextConnectionActivity::class.java,intent).setup();settle(c.get());val root=field(c.get(),"root") as ViewGroup;assertTrue(views(root).filterIsInstance<TextView>().any {it.text.contains("앱의 결과 연결 미확인")});assertFalse(root.findViewWithTag<Button>("sg_native_start").isEnabled);render(root,"S27-native-blocked")
        c.pause().stop().restart().start().resume();settle(c.get());assertFalse((field(c.get(),"data") as SyntheticTextForegroundCoordinator.Result).nativeConnected);c.pause().stop().destroy();evidence("S27")
    }
    @Test fun S28BrowserIntentDoesNotSupplyCredentialsOrMarkConnected() {
        val c=Robolectric.buildActivity(SyntheticTextConnectionActivity::class.java).setup();settle(c.get());val root=field(c.get(),"root") as ViewGroup;root.findViewWithTag<Button>("sg_browser_handoff").performClick();val launched=shadowOf(c.get()).nextStartedActivity;assertEquals(Intent.ACTION_VIEW,launched.action);assertEquals(SyntheticTextContract.SITE,launched.data.toString());assertNull(launched.extras);assertFalse((field(c.get(),"data") as SyntheticTextForegroundCoordinator.Result).nativeConnected);c.pause().stop().destroy();evidence("S28","browser_execution" to "SHADOW_INTENT_ONLY")
    }
    @Test fun S29LocalUiShowsReadOnlyFixedDraftAndReopensSameProof() {
        val intent=Intent(context,SyntheticTextConnectionActivity::class.java).putExtra("local_fixture",true);val c=Robolectric.buildActivity(SyntheticTextConnectionActivity::class.java,intent).setup();settle(c.get());var root=field(c.get(),"root") as ViewGroup;root.findViewWithTag<Button>("sg_start").performClick();settle(c.get());val first=field(c.get(),"data") as SyntheticTextForegroundCoordinator.Result;assertEquals("RESULT_RECEIVED",first.state);assertFalse(first.nativeConnected);root=field(c.get(),"root") as ViewGroup;assertTrue(views(root).filterIsInstance<TextView>().any {it.text.contains("미적용 합성 제안 수신")});assertEquals(0,views(root).filterIsInstance<EditText>().size);render(root,"S29-local-unapplied")
        c.pause().stop().destroy();val next=Robolectric.buildActivity(SyntheticTextConnectionActivity::class.java,intent).setup();settle(next.get());val second=field(next.get(),"data") as SyntheticTextForegroundCoordinator.Result;assertEquals(first.view!!.proof,second.view!!.proof);assertEquals(first.view.expectedId,second.view.expectedId);assertEquals(first.view.result,second.view.result);next.pause().stop().destroy();evidence("S29","native_connected" to false)
    }
    @Test fun S30ReopenedPayloadMustStillMatchItsImmutableReadback()=io {
        val c=FaultChannel();ready(c);TextStoreTestSupport.open(context,SyntheticTextProofStore.DATABASE).use { db ->
            val body=db.rawQuery("SELECT body FROM sg_proofs",null).use { it.moveToFirst();JSONObject(it.getString(0)) }
            val result=JSONObject(body.getString("result")).put("summary","PUBLIC_TAMPERED_LOCAL_BODY");body.put("result",result.toString())
            db.execSQL("UPDATE sg_proofs SET body=?",arrayOf(body.toString()))
        };val held=coordinator(c).load();assertEquals("STORE_REVIEW",held.state);assertNull(held.view);evidence("S30")
    }
    @Test fun S31ReopenedMissingReadbackReceiptCannotBeResultReceived()=io {
        val c=FaultChannel();ready(c);TextStoreTestSupport.open(context,SyntheticTextProofStore.DATABASE).use { db ->
            db.execSQL("DROP TRIGGER sg_receipt_no_delete");db.execSQL("DELETE FROM sg_receipts WHERE kind='RESULT_READBACK'")
        };val held=coordinator(c).load();assertEquals("STORE_REVIEW",held.state);assertNull(held.view);evidence("S31")
    }

}
