package kr.callbrief

import android.content.Context
import kr.callagent.drive.StrictJson
import kr.co.callsummary.core.android.ProtectedDatabase
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** SIWC response -> device file, without a remote writer or model-controlled execution assertion.
 * A single immutable encrypted bundle holds manifest, both outputs and native response observations.
 * Validators remain mandatory: existing six-field analysis/context boundaries are not bypassed.
 * No canonical/user-review write occurs here. Read it through the core's current revision gate. */
internal class SiwcResultFiles(context:Context,private val scope:String,private val jobs:SiwcInferenceJobs,
    private val current:()->Boolean):AutoCloseable {
    private val store=ProtectedDatabase.open(context,"siwc-result-files-v1.db",SCHEMA)
    private val db=store.database()
    private val sealed=AstraAudioSealedFiles("siwc-result-files\n"+scope)
    private val directory=File(context.noBackupFilesDir.canonicalFile,"siwc-results-v1/"+AstraAudioContract.sha(scope.toByteArray(Charsets.UTF_8)))
    init {check(scope.isNotBlank() && directory.canonicalPath==directory.absolutePath) { "SIWC_FILE_PATH" };check(directory.mkdirs() || directory.isDirectory)}
    private fun path(id:String,suffix:String):File {
        AstraAudioContract.hash(id);val file=File(directory,id+suffix)
        check(directory.canonicalPath==directory.absolutePath && !Files.isSymbolicLink(directory.toPath()) && file.canonicalFile.parentFile==directory && !Files.isSymbolicLink(file.toPath())) { "SIWC_FILE_PATH" };return file
    }
    fun key(analysis:SiwcAstraProtocol.Invocation,context:SiwcAstraProtocol.Invocation)=
        AstraAudioContract.sha(StrictJson.bytes(listOf(scope,analysis.sourceManifestSha,analysis.key(),context.key())))
    private fun expected(manifest:ByteArray,analysis:SiwcAstraProtocol.Invocation,context:SiwcAstraProtocol.Invocation,
        validateAnalysis:(String)->Unit,validateContext:(String)->Unit):ByteArray {
        check(current() && analysis.scope==scope && context.scope==scope && AstraAudioContract.sha(manifest)==analysis.sourceManifestSha) { "SIWC_SOURCE_OR_REVISION_CHANGED" }
        val a=jobs.completed(analysis,validateAnalysis);val c=jobs.completed(context,validateContext);context.requireContextOf(analysis,a.output)
        fun stage(r:SiwcAstraProtocol.Invocation,o:SiwcAstraProtocol.Completion):Map<String,Any?> = mapOf(
            "jobId" to r.key(),"requestSha256" to AstraAudioContract.sha(r.body()),"requestedModel" to r.model,"requestedEffort" to r.effort,
            "responseId" to o.responseId,"actualModel" to o.actualModel,"effectiveEffort" to o.effectiveEffort,"responseStreamSha256" to o.rawSha256,
            "outputSha256" to AstraAudioContract.sha(o.output.toByteArray(Charsets.UTF_8)))
        val bytes=StrictJson.bytes(mapOf("schema" to "ieum.siwc-result-file.v1","scope" to scope,"manifest" to AstraAudioContract.parse(manifest),
            "manifestSha256" to analysis.sourceManifestSha,"analysis" to AstraAudioContract.parse(a.output.toByteArray(Charsets.UTF_8)),
            "context" to AstraAudioContract.parse(c.output.toByteArray(Charsets.UTF_8)),
            "receipt" to mapOf("analysis" to stage(analysis,a),"context" to stage(context,c),"userApproved" to false)))
        check(bytes.size<=AstraAudioContract.MAX_RESULT) { "SIWC_RESULT_SIZE" };check(current()) { "SIWC_SOURCE_OR_REVISION_CHANGED" };return bytes
    }
    private fun pointer(id:String):String?=ProtectedDatabase.read(db) {
        db.rawQuery("SELECT sha FROM siwc_files WHERE scope=? AND id=? AND state='COMPLETE'",arrayOf(scope,id)).use {if(it.moveToFirst())it.getString(0) else null}
    }
    private fun bytes(file:File):ByteArray=FileInputStream(file).use {input->
        check(file.length() in 30..AstraAudioContract.MAX_RESULT.toLong()+29) { "SIWC_RESULT_SIZE" }
        val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
        while(true) {val n=input.read(buffer);if(n<0)break;check(out.size()+n<=AstraAudioContract.MAX_RESULT+29) { "SIWC_RESULT_SIZE" };out.write(buffer,0,n)}
        out.toByteArray()
    }
    /** Re-publish after a crash compares the existing artifact with both durable stage receipts.
     * A directory listing/partial file alone never becomes COMPLETE; inference is never re-run. */
    fun publish(manifest:ByteArray,analysis:SiwcAstraProtocol.Invocation,context:SiwcAstraProtocol.Invocation,
        validateAnalysis:(String)->Unit,validateContext:(String)->Unit):String=synchronized(LOCK) {
        val plain=expected(manifest,analysis,context,validateAnalysis,validateContext);val id=key(analysis,context)
        val digest=AstraAudioContract.sha(plain);val part=path(id,".part");val complete=path(id,".json.enc")
        check(pointer(id)==null || pointer(id)==digest) { "SIWC_RESULT_CONFLICT" }
        try {
            if(complete.exists())check(sealed.open(id,bytes(complete)).contentEquals(plain)) { "SIWC_RESULT_CONFLICT" }
            else {
                check(pointer(id)==null) { "SIWC_COMMITTED_FILE_MISSING" }
                val empty=ProtectedDatabase.read(db){db.rawQuery("SELECT COUNT(*) FROM siwc_files WHERE scope=?",arrayOf(scope)).use{it.moveToFirst();it.getLong(0)==0L}} && directory.listFiles().orEmpty().isEmpty()
                val encoded=sealed.seal(id,plain,empty)
                FileOutputStream(part).use{it.write(encoded);it.fd.sync()}
                check(sealed.open(id,bytes(part)).contentEquals(plain)) { "SIWC_RESULT_INTEGRITY" }
                check(expected(manifest,analysis,context,validateAnalysis,validateContext).contentEquals(plain)) { "SIWC_RESULT_CHANGED" }
                Files.move(part.toPath(),complete.toPath(),StandardCopyOption.ATOMIC_MOVE)
                FileChannel.open(directory.toPath(),StandardOpenOption.READ).use{it.force(true)}
            }
            check(sealed.open(id,bytes(complete)).contentEquals(expected(manifest,analysis,context,validateAnalysis,validateContext))) { "SIWC_RESULT_INTEGRITY" }
            store.transaction {
                check(current()) { "SIWC_SOURCE_OR_REVISION_CHANGED" }
                db.execSQL("INSERT OR IGNORE INTO siwc_files VALUES(?,?,?,'COMPLETE')",arrayOf(scope,id,digest));null
            }
            check(pointer(id)==digest) { "SIWC_RESULT_CONFLICT" };id
        } finally {plain.fill(0);part.delete()}
    }
    fun read(manifest:ByteArray,analysis:SiwcAstraProtocol.Invocation,context:SiwcAstraProtocol.Invocation,
        validateAnalysis:(String)->Unit,validateContext:(String)->Unit):Map<String,Any?> = synchronized(LOCK) {
        val id=key(analysis,context);val digest=pointer(id) ?: error("SIWC_RESULT_NOT_COMMITTED")
        val plain=sealed.open(id,bytes(path(id,".json.enc")))
        check(AstraAudioContract.sha(plain)==digest && plain.contentEquals(expected(manifest,analysis,context,validateAnalysis,validateContext))) { "SIWC_RESULT_INTEGRITY" }
        AstraAudioContract.parse(plain).also{check(current()) { "SIWC_SOURCE_OR_REVISION_CHANGED" }}
    }
    override fun close()=store.close()
    companion object {
        private val LOCK=Any()
        private const val SCHEMA="CREATE TABLE siwc_files(scope TEXT NOT NULL,id TEXT NOT NULL,sha TEXT NOT NULL,state TEXT NOT NULL,PRIMARY KEY(scope,id))"
    }
}
