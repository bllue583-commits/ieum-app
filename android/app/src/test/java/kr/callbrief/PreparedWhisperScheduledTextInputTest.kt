package kr.callbrief

import kr.callagent.drive.*
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import android.content.Context
import kotlinx.coroutines.*
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.SQLiteMode

/** Prior real engine output -> exact request -> existing scheduled-dot codec; no new inference. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class PreparedWhisperScheduledTextInputTest:ProtectedTextHostTestBase() {
    private fun bytes(name:String)=javaClass.getResourceAsStream("/local-stt/"+name)!!.readBytes()
    @Test fun producerArtifactAndExactQuotesUseCompatibleReadOnlyScheduledContract() {
        val request=bytes("scheduled-input.json");val evidence=bytes("stt-request-evidence.json")
        val input=PreparedWhisperScheduledTextInput(request,evidence,DriveRelay.sha256(evidence))
        assertEquals("IMPORTED_PREVIOUS_ACTUAL_EXECUTION_NOT_CURRENT",input.upstreamExecutionReported)
        assertFalse(input.executionIndependentlyVerified);assertFalse(input.speakerAssignmentVerified)
        val r=JSONObject(String(request,Charsets.UTF_8));val raw=r.getJSONArray("transcript").getJSONObject(0).getString("text");val quote=raw.substring(1,Math.min(raw.length,18))
        val result=JSONObject().put("schema_version","scheduler-proof-v1").put("job_id",input.jobId).put("synthetic",true).put("source_file_id","PUBLIC_TEST_INPUT_FILE")
            .put("produced_at_utc","2026-10-14T09:00:00Z").put("summary","TEST_ONLY_REQUEST_COMPATIBILITY")
            .put("tasks",org.json.JSONArray().put(JSONObject().put("title","TEST_ONLY_QUOTE").put("owner","미확인").put("status","needs_confirmation").put("due",JSONObject.NULL).put("timing_text",quote)
                .put("evidence",JSONObject().put("source_id","synthetic_call001").put("quote",quote))))
        val b=result.toString().toByteArray();val p=ScheduledTextProofCodec().validate(input.forJob(input.jobId),b,input.jobId,"PUBLIC_TEST_INPUT_FILE",DriveRelay.sha256(request),DriveRelay.sha256(b),Instant.parse("2026-10-14T08:59:00Z"),Instant.parse("2026-10-14T09:01:00Z"))
        assertEquals(1,p.tasks.size);assertFalse(p.canonicalWriteAllowed);assertFalse(p.schedulerTriggerIndependentlyVerified)
        val forged=JSONObject(String(evidence,Charsets.UTF_8)).put("speaker_assignment_verified",true).toString().toByteArray()
        try{PreparedWhisperScheduledTextInput(request,forged,DriveRelay.sha256(forged));fail("speaker assignment promoted")}catch(_:IllegalArgumentException){}
    }
    @Test fun preparedLocalSttArtifactPassesTheProductionPublisherHookWithoutCloudOrModelExecution()=runBlocking(Dispatchers.IO) {
        val request=bytes("scheduled-input.json");val evidence=bytes("stt-request-evidence.json");val prepared=PreparedWhisperScheduledTextInput(request,evidence,DriveRelay.sha256(evidence))
        val memory=object:GoogleConnectionSession.Persistence{var saved=JSONObject();override fun read()=JSONObject(saved.toString());override fun write(value:JSONObject){saved=JSONObject(value.toString())}}
        val session=GoogleConnectionSession(memory);val epoch=session.begin();session.activate(epoch,"PUBLIC_STT@example.invalid","PUBLIC_STT@example.invalid","PUBLIC_STT_OWNER","PUBLIC_MEMORY_TOKEN")
        val ctx:Context=RuntimeEnvironment.getApplication();GeneralLocalStore.catalog(AccountStorageContext(ctx,session.ownerBinding()))
        val drive=GoogleConnectionRegressionTest.FakeDrive();val queue="PUBLIC_EXISTING_STT_QUEUE";drive.specs[queue]=DriveTransport.FileSpec(queue,null,"public",DriveTransport.FOLDER_MIME,session.ownerBinding(),DriveTransport.Role.PRIVATE_FOLDER);drive.bytes[queue]=byteArrayOf();drive.versions[queue]=1
        val port=DotScheduledTextExchangePort(ctx,session,{DotTextExchangeConfiguration(true,queue)},drive,preparedSyntheticInput={prepared}){Instant.parse("2026-10-14T09:00:00Z")}
        val submitted=port.publishFixedSynthetic(prepared.jobId);assertEquals("SENT_AWAITING_SCHEDULED_DOT",submitted.state);val r=submitted.registration!!
        assertArrayEquals(request,drive.bytes[r.input]);val d=JSONObject(String(drive.bytes.getValue(r.descriptor),Charsets.UTF_8));assertEquals(prepared.evidenceSha,d.getString("local_stt_evidence_sha256"));assertEquals("IMPORTED_PREVIOUS_ACTUAL_EXECUTION_NOT_CURRENT",d.getString("upstream_stt_execution_reported"));assertFalse(d.getBoolean("upstream_stt_independently_verified"))
        assertFalse(r.dotInvoked);assertFalse(r.audioTransferred);assertEquals(3,drive.counter)
    }
}
