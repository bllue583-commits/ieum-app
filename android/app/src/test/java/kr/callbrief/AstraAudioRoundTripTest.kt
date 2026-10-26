package kr.callbrief

import android.content.Context
import kr.callagent.drive.StrictJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import java.util.UUID

/** Real public WAV reads, real protected journal/file IO, RSA verification. Analysis is a fixture. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class AstraAudioRoundTripTest:ProtectedTextHostTestBase() {
    private val context:Context get()=RuntimeEnvironment.getApplication()
    private val scope="public-astra-"+UUID.randomUUID().toString()
    private val source=GeneralLocalStoreTest.Source()
    private var clock=1000L
    private var current=true
    private var approved=true
    private var online=true
    private var stopped=false
    internal val signing=KeyPairGenerator.getInstance("RSA").apply{initialize(2048)}.generateKeyPair()
    private fun input(index:Int=0,same:Boolean=false):AstraAudioInput {
        val name=if(index==0 || same)"public-ko.wav" else "public-en.wav"
        val bytes=File("../../worker/tests/audio/$name").readBytes();val id="public-$index"
        val document=SourceDocument(id,"${source.rootUri}/document/$id",name,bytes.size.toLong(),"audio/wav",100)
        source.documents[id]=document;source.bytes[id]=bytes
        val hash=AstraAudioContract.sha(bytes)
        val frozen=JSONObject().put("sourceId",id).put("sourceVersion",1).put("audioSha",hash).put("jobId","native-$index")
        return AstraAudioInput("automation-$index",id,1,hash,"a".repeat(64),JSONObject().put("frozenRequestJson",frozen.toString()).toString(),document)
    }
    private fun signed(payload:Map<String,Any?>):ByteArray {
        val signature=Signature.getInstance("SHA256withRSA").run{initSign(signing.private);update(StrictJson.bytes(payload));sign()}
        return StrictJson.bytes(mapOf("payload" to payload,"keyId" to "public-test-key","signature" to Base64.getEncoder().encodeToString(signature)))
    }
    private fun payload(manifest:ByteArray):MutableMap<String,Any?> {
        val files=AstraAudioContract.list(AstraAudioContract.parse(manifest),"files",4).map(AstraAudioContract::obj)
        val text="공개 합성 음성입니다. 자료를 확인해 주세요."
        return mutableMapOf("schema" to "ieum.astra-audio.result.v1","manifestSha256" to AstraAudioContract.sha(manifest),
            "model" to "gpt-6-astra","effort" to "xhigh","status" to "COMPLETE","revision" to 1L,"receipt" to "fixture-receipt",
            "execution" to mapOf("kind" to "TEST_FIXTURE","responseId" to "fixture-not-a-model-response","model" to "gpt-6-astra","effort" to "xhigh","audioSha256" to files.map{it["audioSha256"]}),
            "results" to files.map{f->f.filterKeys{it !in setOf("byteLength","mime")}.toMutableMap().apply{
                put("transcript",text);put("summary","자료 확인 요청");put("uncertainties",listOf("담당과 기한 미확인"))
                put("claims",listOf(mapOf("id" to "c1","kind" to "REQUEST","text" to "자료 확인","assignee" to null,"due" to null,
                    "requiredDocuments" to listOf("확인 대상 자료"),"uncertainty" to "원문에서 담당 미확인",
                    "evidence" to mapOf("start" to 0L,"end" to text.codePointCount(0,text.length).toLong(),"quote" to text))))
            }})
    }
    internal inner class FixtureTransport:AstraAudioTransport {
        var submits=0;var polls=0;var reconciles=0;var bytesRead=0;var unknown=false;var pending=false;var cancelAck=false
        var afterSubmit:()->Unit={};var transform:(MutableMap<String,Any?>)->Unit={};var responseBytes:ByteArray?=null
        override fun gate()=AutomationGate("READY","PUBLIC_FIXTURE_ONLY")
        override fun submit(key:String,manifest:ByteArray,files:List<AstraAudioUpload>):AutomationDispatch {
            submits++
            val entries=AstraAudioContract.list(AstraAudioContract.parse(manifest),"files",4).map(AstraAudioContract::obj)
            assertEquals(key,AstraAudioContract.sha(manifest));assertEquals(entries.size,files.size)
            entries.zip(files).forEach{(entry,file)->val bytes=file.copyBytes();assertEquals(entry["audioSha256"],AstraAudioContract.sha(bytes));assertEquals(entry["byteLength"],bytes.size.toLong());bytesRead+=bytes.size;bytes.fill(0)}
            responseBytes=signed(payload(manifest).also(transform));afterSubmit()
            if(unknown)throw IOException("synthetic delivery interrupted")
            return AutomationDispatch("ACCEPTED","fixture-receipt","PUBLIC_FIXTURE_ACCEPTED")
        }
        override fun reconcile(key:String,manifest:ByteArray):AutomationDispatch {reconciles++;return AutomationDispatch("ACCEPTED","fixture-receipt","PUBLIC_FIXTURE_RECONCILED")}
        override fun result(key:String,receipt:String):InputStream? {polls++;return if(pending)null else checkNotNull(responseBytes).inputStream()}
        override fun cancel(key:String,receipt:String)=cancelAck
    }
    private fun queue(t:AstraAudioTransport=UnavailableAstraAudioTransport,fixture:Boolean=true,publicHashes:Set<String> = source.bytes.values.map{AstraAudioContract.sha(it)}.toSet())=
        AstraAudioRoundTrip(context,scope,source,{current},publicHashes,{approved},t,mapOf("public-test-key" to signing.public),fixture,{clock},{online},{stopped})
    private fun file(id:String,ext:String=".json.enc")=File(context.noBackupFilesDir,"astra-audio-results-v1/${AstraAudioContract.sha(scope.toByteArray())}/$id$ext")
    private fun reject(code:String,block:()->Unit) {
        val failure=runCatching(block).exceptionOrNull();assertNotNull("Expected $code",failure);assertTrue(failure.toString(),failure!!.message.orEmpty().contains(code))
    }
    @Test fun unsupportedRoutePreparesMetadataWithoutReadingOrUploading()=runBlocking(Dispatchers.IO) {
        val i=input();queue().use{q->val id=q.prepare(listOf(i));assertEquals(id,q.prepare(listOf(i)));assertEquals("WAITING_PROVIDER",q.cycle(id));assertFalse(file(id).exists());assertEquals("UNCONFIGURED",q.gate().state)}
        assertEquals(0,source.opens)
    }
    @Test fun cancellingBeforeDispatchTwiceRemainsLocallyCancelled()=runBlocking(Dispatchers.IO) {
        val i=input();queue().use{q->val id=q.prepare(listOf(i));q.cancel(id);q.cancel(id);assertEquals("CANCELLED",q.state(id))};assertEquals(0,source.opens)
    }
    @Test fun bytesManifestSignedFileAndRestartAreRealButModelIsFixture()=runBlocking(Dispatchers.IO) {
        val inputs=listOf(input(),input(1));val t=FixtureTransport();var id=""
        queue(t).use{q->id=q.prepare(inputs);assertEquals("RESULT_READY",q.cycle(id));assertEquals(inputs.sumOf{it.document.size}.toInt(),t.bytesRead)
            assertEquals("TEST_FIXTURE",q.read(id).kind);assertFalse(file(id,".part").exists());assertTrue(file(id).exists());assertFalse(String(file(id).readBytes()).contains("transcript"))
            val observation=q.observation(inputs[0].job,inputs[0].handoff) as AutomationObservation.Result
            assertFalse(observation.assurance.executionVerified);assertFalse(observation.assurance.automaticUseAllowed);assertEquals("needs_confirmation",observation.result.tasks.getJSONObject(0).getString("status"))}
        queue(t).use{q->assertEquals("RESULT_READY",q.cycle(id));assertEquals(2,q.read(id).results.size)}
        assertEquals(1,t.submits);assertEquals(1,t.polls)
    }
    @Test fun manifestNeverExportsUriOrFilename()=runBlocking(Dispatchers.IO) {
        val i=input();queue().use{q->val text=String(q.journal.load(q.prepare(listOf(i))).manifest);assertFalse(text.contains(i.document.uri));assertFalse(text.contains(i.document.name));assertTrue(text.contains(i.audioSha))}
    }
    @Test fun offlineIsDurableAndResumesOriginalKeyOnce()=runBlocking(Dispatchers.IO) {
        val i=input();val t=FixtureTransport();online=false;var id=""
        queue(t).use{q->id=q.prepare(listOf(i));assertEquals("OFFLINE",q.cycle(id));assertEquals(0,t.submits)}
        online=true;clock+=40000
        queue(t).use{q->assertEquals("RESULT_READY",q.cycle(id))};assertEquals(1,t.submits)
    }
    @Test fun unknownSubmissionOnlyReconcilesAfterRestart()=runBlocking(Dispatchers.IO) {
        val i=input();val t=FixtureTransport();t.unknown=true;var id=""
        queue(t).use{q->id=q.prepare(listOf(i));assertEquals("UNKNOWN",q.cycle(id));assertEquals("UNKNOWN",q.cycle(id))}
        clock+=100000
        queue(t).use{q->assertEquals("RESULT_READY",q.cycle(id))};assertEquals(1,t.submits);assertEquals(1,t.reconciles)
    }
    @Test fun pendingResultBackoffDoesNotReupload()=runBlocking(Dispatchers.IO) {
        val i=input();val t=FixtureTransport();t.pending=true
        queue(t).use{q->val id=q.prepare(listOf(i));assertEquals("WAITING_RESULT",q.cycle(id));q.cycle(id);assertEquals(1,t.polls)
            clock+=100000;t.pending=false;assertEquals("RESULT_READY",q.cycle(id));assertEquals(1,t.submits)}
    }
    @Test fun noTransferApprovalMeansNoByteRead()=runBlocking(Dispatchers.IO) {
        val i=input();val t=FixtureTransport();approved=false
        queue(t).use{q->val id=q.prepare(listOf(i));reject("ASTRA_TRANSFER_APPROVAL_REQUIRED"){q.cycle(id)}}
        assertEquals(0,t.submits);assertEquals(0,source.opens)
    }
    @Test fun revokedReadGrantRejectsBeforeOpen()=runBlocking(Dispatchers.IO) {
        val i=input();val t=FixtureTransport()
        queue(t).use{q->val id=q.prepare(listOf(i));source.granted=false;assertNotNull(runCatching{q.cycle(id)}.exceptionOrNull())}
        assertEquals(0,t.submits);assertEquals(0,source.opens)
    }
    @Test fun privateHashCannotBeAdmitted()=runBlocking(Dispatchers.IO) {val i=input();queue(publicHashes=emptySet()).use{q->reject("ASTRA_PUBLIC_SYNTHETIC_REQUIRED"){q.prepare(listOf(i))}}}
    @Test fun changedBytesCannotPassManifestHash()=runBlocking(Dispatchers.IO) {
        val i=input();val t=FixtureTransport();queue(t).use{q->val id=q.prepare(listOf(i));source.bytes[i.document.documentId]!![100]=(source.bytes[i.document.documentId]!![100].toInt() xor 1).toByte()
            reject("ASTRA_AUDIO_HASH"){q.cycle(id)};assertFalse(file(id).exists())};assertEquals(0,t.submits)
    }
    @Test fun revisionChangePreventsReceivingOrReadingSavedCandidate()=runBlocking(Dispatchers.IO) {
        val i=input();val t=FixtureTransport();queue(t).use{q->val id=q.prepare(listOf(i));q.cycle(id);current=false;reject("ASTRA_SOURCE_OR_REVISION_CHANGED"){q.read(id)}}
    }
    @Test fun cancelBeforeDispatchIsTerminalAfterReopen()=runBlocking(Dispatchers.IO) {
        val i=input();val t=FixtureTransport();var id="";queue(t).use{q->id=q.prepare(listOf(i));q.cancel(id)}
        queue(t).use{q->assertEquals("CANCELLED",q.cycle(id));reject("ASTRA_CANCELLED"){q.read(id)}};assertEquals(0,t.submits)
    }
    @Test fun cancelInFlightRejectsLateResultAndKeepsUnknownRemoteState()=runBlocking(Dispatchers.IO) {
        val i=input();val t=FixtureTransport();queue(t).use{q->val id=q.prepare(listOf(i));t.afterSubmit={q.cancel(id)}
            reject("ASTRA_CANCELLED"){q.cycle(id)};assertEquals("CANCEL_REQUESTED",q.state(id));assertFalse(file(id).exists())
            assertEquals("CANCEL_REQUESTED",q.cycle(id));t.cancelAck=true;assertEquals("CANCELLED",q.cycle(id))}
    }
    @Test fun incompleteSignatureSchemaAndOtherBindingsNeverPublish()=runBlocking(Dispatchers.IO) {
        val i=input();val manifest=AstraAudioContract.manifest(scope,listOf(i));val valid=payload(manifest)
        for((field,value) in listOf("status" to "PARTIAL","model" to "gpt-other","effort" to "high","manifestSha256" to "b".repeat(64),"receipt" to "other","revision" to 2L)) {
            reject("ASTRA_RESULT_BINDING"){AstraAudioContract.verify(signed(valid.toMutableMap().apply{put(field,value)}),manifest,mapOf("public-test-key" to signing.public),true,"fixture-receipt")}
        }
        val t=FixtureTransport();t.transform={it["results"]=emptyList<Any>()}
        queue(t).use{q->val id=q.prepare(listOf(i));reject("ASTRA_PARTIAL_RESULT"){q.cycle(id)};assertFalse(file(id).exists());assertFalse(file(id,".part").exists())}
    }
    @Test fun corruptSignatureAndUnknownWriterAreRejected()=runBlocking(Dispatchers.IO) {
        val i=input();val manifest=AstraAudioContract.manifest(scope,listOf(i));val packet=AstraAudioContract.parse(signed(payload(manifest))).toMutableMap()
        packet["signature"]=Base64.getEncoder().encodeToString(ByteArray(256))
        reject("ASTRA_SIGNATURE"){AstraAudioContract.verify(StrictJson.bytes(packet),manifest,mapOf("public-test-key" to signing.public),true,"fixture-receipt")}
        reject("ASTRA_UNTRUSTED_WRITER"){AstraAudioContract.verify(signed(payload(manifest)),manifest,emptyMap(),true,"fixture-receipt")}
    }
    @Test fun fixtureIsRejectedByProductionVerifier()=runBlocking(Dispatchers.IO) {
        val i=input();val t=FixtureTransport();queue(t,fixture=false).use{q->val id=q.prepare(listOf(i));reject("ASTRA_EXECUTION_KIND"){q.cycle(id)};assertFalse(file(id).exists())}
    }
    @Test fun sameAudioDifferentSourcesRemainDistinct()=runBlocking(Dispatchers.IO) {
        val inputs=listOf(input(),input(1,true));assertEquals(inputs[0].audioSha,inputs[1].audioSha);val t=FixtureTransport()
        queue(t).use{q->val id=q.prepare(inputs);q.cycle(id);assertEquals(2,q.read(id).results.map{it["sourceId"]}.distinct().size)}
    }
    @Test fun wrongSourceOrEvidenceNeverReachesFile()=runBlocking(Dispatchers.IO) {
        val i=input();val t=FixtureTransport();t.transform={p->val rows=(p["results"] as List<*>).map{AstraAudioContract.obj(it).toMutableMap()};rows[0]["sourceId"]="another-source";p["results"]=rows}
        queue(t).use{q->val id=q.prepare(listOf(i));reject("ASTRA_SOURCE_BINDING"){q.cycle(id)};assertFalse(file(id).exists())}
    }
    @Test fun unicodeEvidenceUsesCodePointsAndInvalidQuoteIsRejected()=runBlocking(Dispatchers.IO) {
        val i=input();val manifest=AstraAudioContract.manifest(scope,listOf(i));val p=payload(manifest)
        val rows=(p["results"] as List<*>).map{AstraAudioContract.obj(it).toMutableMap()};rows[0]["transcript"]="다른 원문";p["results"]=rows
        reject("ASTRA_QUOTE"){AstraAudioContract.verify(signed(p),manifest,mapOf("public-test-key" to signing.public),true,"fixture-receipt")}
    }
    @Test fun malformedUtf8DuplicateKeysAndUnknownFieldsAreRejected()=runBlocking(Dispatchers.IO) {
        assertNotNull(runCatching{AstraAudioContract.parse(byteArrayOf(0xc3.toByte()))}.exceptionOrNull())
        assertNotNull(runCatching{AstraAudioContract.parse("{\"a\":1,\"a\":2}".toByteArray())}.exceptionOrNull())
        val i=input();val manifest=AstraAudioContract.manifest(scope,listOf(i));val p=payload(manifest);p["approved"]=true
        reject("ASTRA_SCHEMA"){AstraAudioContract.verify(signed(p),manifest,mapOf("public-test-key" to signing.public),true,"fixture-receipt")}
    }
    @Test fun truncatedDownloadIsNeverCompletedAndCanBeReceivedAgain()=runBlocking(Dispatchers.IO) {
        val i=input();val t=FixtureTransport();t.pending=true
        queue(t).use{q->val id=q.prepare(listOf(i));q.cycle(id);val good=t.responseBytes!!;t.responseBytes=good.copyOf(20);t.pending=false;clock+=100000
            assertNotNull(runCatching{q.cycle(id)}.exceptionOrNull());assertFalse(file(id).exists());assertFalse(file(id,".part").exists())
            t.responseBytes=good;assertEquals("RESULT_READY",q.cycle(id));assertEquals(1,t.submits)}
    }
    @Test fun corruptCompletedFileIsRejectedOnRestart()=runBlocking(Dispatchers.IO) {
        val i=input();val t=FixtureTransport();var id="";queue(t).use{q->id=q.prepare(listOf(i));q.cycle(id)}
        file(id).appendText(" ")
        queue(t).use{q->reject("ASTRA_FILE_AUTHENTICATION"){q.read(id)}};assertEquals(1,t.submits)
    }
    @Test fun renameBeforeJournalReadyRecoversWithoutNetwork()=runBlocking(Dispatchers.IO) {
        val i=input();val t=FixtureTransport();var id="";queue(t).use{q->id=q.prepare(listOf(i));q.cycle(id);assertTrue(q.journal.claim(id,clock));q.journal.state(id,"PUBLISHING");q.journal.release(id)}
        queue().use{q->assertEquals("RESULT_READY",q.cycle(id));assertEquals("TEST_FIXTURE",q.read(id).kind)};assertEquals(1,t.submits)
    }
    @Test fun oneNativeJobCannotBeEnrolledWithChangedManifest()=runBlocking(Dispatchers.IO) {
        val i=input();queue().use{q->q.prepare(listOf(i));reject("ASTRA_JOB_ALREADY_ENROLLED"){q.prepare(listOf(i.copy(revisionSha="b".repeat(64))))}}
    }
    @Test fun partialFileWithoutJournalDigestIsNotAResult()=runBlocking(Dispatchers.IO) {
        val i=input();queue().use{q->val id=q.prepare(listOf(i));file(id,".part").writeText("{}");reject("ASTRA_RESULT_NOT_COMMITTED"){q.read(id)}}
    }
    @Test fun boundNativeSourceAndHandoffAreRequired()=runBlocking(Dispatchers.IO) {
        val i=input();queue().use{q->reject("ASTRA_NATIVE_SOURCE_BINDING"){q.prepare(listOf(i.copy(sourceVersion=2)))}}
        val t=FixtureTransport();queue(t).use{q->val id=q.prepare(listOf(i));q.cycle(id);reject("ASTRA_HANDOFF_CHANGED"){q.observation(i.job,i.handoff+" ")}}
    }
    @Test fun revocationDuringByteReadStopsBeforeTransfer()=runBlocking(Dispatchers.IO) {
        val i=input();val t=FixtureTransport();source.onRead={source.granted=false}
        queue(t).use{q->val id=q.prepare(listOf(i));assertNotNull(runCatching{q.cycle(id)}.exceptionOrNull());assertFalse(file(id).exists())}
        assertEquals(0,t.submits)
    }
    @Test fun oversizedResultIsBoundedAndNeverPublished()=runBlocking(Dispatchers.IO) {
        val i=input();val t=FixtureTransport();t.pending=true
        queue(t).use{q->val id=q.prepare(listOf(i));q.cycle(id);t.pending=false;t.responseBytes=ByteArray(AstraAudioContract.MAX_RESULT+1){32};clock+=100000
            reject("ASTRA_RESULT_SIZE"){q.cycle(id)};assertFalse(file(id).exists());assertFalse(file(id,".part").exists())}
    }
    @Test fun symlinkResultCannotBeReadOrReplaced()=runBlocking(Dispatchers.IO) {
        val i=input();val t=FixtureTransport();queue(t).use{q->val id=q.prepare(listOf(i));q.cycle(id)
            val target=File(file(id).parentFile,"other.json");target.writeText("preserve");file(id).delete()
            java.nio.file.Files.createSymbolicLink(file(id).toPath(),target.toPath())
            reject("ASTRA_FILE_PATH"){q.read(id)};assertEquals("preserve",target.readText())}
    }
    @Test fun signedRuntimeClaimStillDoesNotProveExecutionOrUserReview()=runBlocking(Dispatchers.IO) {
        val i=input();val t=FixtureTransport();t.transform={p->p["execution"]=AstraAudioContract.obj(p["execution"]).toMutableMap().apply{put("kind","RUNTIME")}}
        queue(t,fixture=false).use{q->val id=q.prepare(listOf(i));q.cycle(id);val o=q.observation(i.job,i.handoff) as AutomationObservation.Result
            assertFalse(o.assurance.executionVerified);assertFalse(o.assurance.semanticReviewVerified);assertFalse(o.assurance.automaticUseAllowed)}
    }
    @Test fun publishingBeforeRenameRequiresMatchingRedownload()=runBlocking(Dispatchers.IO) {
        val i=input();val t=FixtureTransport();var id="";queue(t).use{q->id=q.prepare(listOf(i));q.cycle(id)
            assertTrue(q.journal.claim(id,clock));q.journal.state(id,"PUBLISHING");q.journal.release(id);file(id).delete()}
        queue(t).use{q->assertEquals("RESULT_READY",q.cycle(id));assertTrue(file(id).exists())};assertEquals(1,t.submits);assertEquals(2,t.polls)
    }

}
