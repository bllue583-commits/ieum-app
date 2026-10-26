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
import java.io.File
import java.util.UUID

/** The HTTP completions are fixtures. Protected journals, encrypted files and restart are real. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SiwcResultFilesTest:ProtectedTextHostTestBase() {
    private val app get()=RuntimeEnvironment.getApplication() as android.content.Context
    private val scope="public-siwc-files-"+UUID.randomUUID()
    private val manifest=StrictJson.bytes(mapOf("schema" to "public-test.source-manifest.v1","sourceId" to "public-ko","audioSha256" to "a".repeat(64),"sourceRevision" to 1L))
    private val analysis=SiwcAstraProtocol.Invocation(scope,SiwcAstraProtocol.Stage.ANALYSIS,"gpt-6-astra","xhigh",AstraAudioContract.sha(manifest),mapOf("transcript" to "공개 합성 자료"),"Return a checked candidate.")
    private var current=true
    private var calls=0
    private val port=object:SiwcInferencePort {
        override fun models(scope:String)=listOf("gpt-6-astra")
        override fun invoke(request:SiwcAstraProtocol.Invocation,stopped:()->Boolean):SiwcAstraProtocol.Completion {
            calls++;return SiwcAstraProtocol.completed(SiwcAstraProtocolTest().event().byteInputStream(),request,stopped)
        }
    }
    private fun jobs()=SiwcInferenceJobs(app,scope,port){current}
    private fun files(j:SiwcInferenceJobs)=SiwcResultFiles(app,scope,j){current}
    private fun validate(raw:String) {check(AstraAudioContract.parse(raw.toByteArray())["summary"]=="공개 후보")}
    private fun completed(j:SiwcInferenceJobs):SiwcAstraProtocol.Invocation {
        assertEquals("COMPLETE",j.run(analysis,::validate).state)
        return j.context(analysis,mapOf("revision" to 1L),"gpt-6-astra","xhigh","Return context candidate.").also {assertEquals("COMPLETE",j.run(it,::validate).state)}
    }
    private fun file(id:String,suffix:String=".json.enc")=File(app.noBackupFilesDir,"siwc-results-v1/${AstraAudioContract.sha(scope.toByteArray())}/$id$suffix")
    private fun fails(block:()->Unit) {assertNotNull(runCatching(block).exceptionOrNull())}
    @Test fun twoStagesPersistAsEncryptedBundleAndReplayDoesNotInvoke()=runBlocking(Dispatchers.IO) {
        lateinit var context:SiwcAstraProtocol.Invocation;var id=""
        jobs().use{j->context=completed(j);files(j).use{f->id=f.publish(manifest,analysis,context,::validate,::validate)
            assertFalse(file(id).readText().contains("공개 후보"));assertFalse(file(id,".part").exists())}}
        jobs().use{j->files(j).use{f->assertEquals(id,f.publish(manifest,analysis,context,::validate,::validate))
            val read=f.read(manifest,analysis,context,::validate,::validate);assertEquals(false,AstraAudioContract.obj(read["receipt"])["userApproved"])}}
        assertEquals(2,calls)
    }
    @Test fun noCommittedContextMeansNoFile()=runBlocking(Dispatchers.IO) {
        jobs().use{j->j.run(analysis,::validate);val context=j.context(analysis,mapOf("revision" to 1L),"gpt-6-astra","xhigh","policy")
            files(j).use{f->fails{f.publish(manifest,analysis,context,::validate,::validate)};assertFalse(file(f.key(analysis,context)).exists())}}
    }
    @Test fun corruptedCiphertextIsHeldAndNeverOverwritten()=runBlocking(Dispatchers.IO) {
        jobs().use{j->val c=completed(j);files(j).use{f->val id=f.publish(manifest,analysis,c,::validate,::validate)
            val damaged=file(id).readBytes().also{it[it.lastIndex]=(it.last().toInt() xor 1).toByte()};file(id).writeBytes(damaged)
            fails{f.read(manifest,analysis,c,::validate,::validate)};fails{f.publish(manifest,analysis,c,::validate,::validate)};assertArrayEquals(damaged,file(id).readBytes())}}
    }
    @Test fun orphanCompleteFileRejoinsOnlyMatchingCommittedStages()=runBlocking(Dispatchers.IO) {
        jobs().use{j->val c=completed(j);var id="";files(j).use{f->id=f.publish(manifest,analysis,c,::validate,::validate)}
            ProtectedDatabase.open(app,"siwc-result-files-v1.db",null).use{p->p.transaction{p.database().execSQL("DELETE FROM siwc_files WHERE scope=?",arrayOf(scope));null}}
            files(j).use{f->fails{f.read(manifest,analysis,c,::validate,::validate)};assertEquals(id,f.publish(manifest,analysis,c,::validate,::validate))}}
        assertEquals(2,calls)
    }
    @Test fun partialFileDoesNotAuthorizeReading()=runBlocking(Dispatchers.IO) {
        jobs().use{j->val c=completed(j);files(j).use{f->val id=f.key(analysis,c);file(id,".part").writeBytes(byteArrayOf(1,2,3))
            fails{f.read(manifest,analysis,c,::validate,::validate)};assertFalse(file(id).exists())}}
    }
    @Test fun changedSourceRevisionAndCancellationFenceRead()=runBlocking(Dispatchers.IO) {
        jobs().use{j->val c=completed(j);files(j).use{f->f.publish(manifest,analysis,c,::validate,::validate)
            fails{f.read(StrictJson.bytes(mapOf("sourceRevision" to 2L)),analysis,c,::validate,::validate)}
            current=false;fails{f.read(manifest,analysis,c,::validate,::validate)};current=true
            j.cancel(c.key());fails{f.read(manifest,analysis,c,::validate,::validate)}}}
    }
    @Test fun validationIsReappliedAtEveryRead()=runBlocking(Dispatchers.IO) {
        jobs().use{j->val c=completed(j);files(j).use{f->f.publish(manifest,analysis,c,::validate,::validate)
            fails{f.read(manifest,analysis,c,{error("SCHEMA_CHANGED")},::validate)}}}
    }
    @Test fun contextOfDifferentAnalysisCannotPublish()=runBlocking(Dispatchers.IO) {
        jobs().use{j->val c=completed(j)
            val other=SiwcAstraProtocol.Invocation(scope,SiwcAstraProtocol.Stage.ANALYSIS,"gpt-6-astra","xhigh",AstraAudioContract.sha(manifest),mapOf("transcript" to "다른 공개 자료"),"policy")
            j.run(other,::validate);files(j).use{f->fails{f.publish(manifest,other,c,::validate,::validate)}}}
    }
}
