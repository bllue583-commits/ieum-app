package kr.callbrief

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.PublicKey

/** A byte-bearing transport seam, NOT an invented Android/OpenAI endpoint. No live adapter is installed. */
internal interface AstraAudioTransport {
    fun gate():AutomationGate
    /** Must be an idempotent operation keyed by the exact manifest SHA. */
    fun submit(key:String,manifest:ByteArray,files:List<AstraAudioUpload>):AutomationDispatch
    /** No bytes or new execution: look up the original idempotency key after an uncertain outcome. */
    fun reconcile(key:String,manifest:ByteArray):AutomationDispatch
    fun result(key:String,receipt:String):InputStream?
    /** True only for a confirmed remote cancellation; false leaves CANCEL_REQUESTED durable. */
    fun cancel(key:String,receipt:String):Boolean
}
internal class AstraAudioUpload(val sourceId:String,val sha256:String,private val bytes:ByteArray) {
    fun copyBytes()=bytes.clone()
    internal fun erase()=bytes.fill(0)
    override fun toString()="AstraAudioUpload[withheld]"
}
internal object UnavailableAstraAudioTransport:AstraAudioTransport {
    override fun gate()=AutomationGate("UNCONFIGURED","ASTRA_ANDROID_RUNTIME_ROUTE_UNAVAILABLE")
    override fun submit(key:String,manifest:ByteArray,files:List<AstraAudioUpload>):AutomationDispatch=error("ASTRA_ANDROID_RUNTIME_ROUTE_UNAVAILABLE")
    override fun reconcile(key:String,manifest:ByteArray)=AutomationDispatch("UNKNOWN",reason="ASTRA_ANDROID_RUNTIME_ROUTE_UNAVAILABLE")
    override fun result(key:String,receipt:String):InputStream?=null
    override fun cancel(key:String,receipt:String)=false
}

/** Resumable public/synthetic-only implementation. A filename/URI never counts as uploaded audio.
 * The caller supplies current permission/revision checks and an explicit per-manifest transfer grant.
 * Neither a signed model claim nor TEST_FIXTURE can authorize transfer or canonical writes. */
internal class AstraAudioRoundTrip(
    context:Context,private val scope:String,private val source:SelectedRecordingSource,
    private val current:(AstraAudioInput)->Boolean,private val publicAudio:Set<String>,
    private val transferApproved:(String)->Boolean={false},
    private val transport:AstraAudioTransport=UnavailableAstraAudioTransport,
    private val keys:Map<String,PublicKey> = emptyMap(),private val allowFixture:Boolean=false,
    private val now:()->Long=System::currentTimeMillis,private val online:()->Boolean={true},
    private val stopped:()->Boolean={false}
):AutoCloseable {
    internal val journal=AstraAudioJournal(context,scope)
    private val sealed=AstraAudioSealedFiles(scope)
    private val directory=File(context.noBackupFilesDir.canonicalFile,"astra-audio-results-v1/"+AstraAudioContract.sha(scope.toByteArray()))
    init { require(scope.isNotBlank());check(directory.canonicalPath==directory.absolutePath) { "ASTRA_FILE_PATH" };check(directory.mkdirs() || directory.isDirectory);check(!Files.isSymbolicLink(directory.toPath())) }
    fun prepare(inputs:List<AstraAudioInput>):String {
        inputs.forEach{permitted(it)} // metadata only, no audio reads or transfer
        return journal.prepare(inputs)
    }
    fun state(id:String)=journal.load(id).state
    fun find(job:String)=journal.find(job)
    fun gate()=transport.gate()
    private fun path(id:String,suffix:String):File {
        AstraAudioContract.hash(id);val file=File(directory,id+suffix)
        check(file.canonicalFile.parentFile==directory.canonicalFile && !Files.isSymbolicLink(file.toPath())) { "ASTRA_FILE_PATH" };return file
    }
    private fun permitted(input:AstraAudioInput) {
        check(!stopped()) { "ASTRA_STOPPED" }
        check(current(input)) { "ASTRA_SOURCE_OR_REVISION_CHANGED" }
        check(source.explicitlySyntheticScope && input.audioSha in publicAudio) { "ASTRA_PUBLIC_SYNTHETIC_REQUIRED" }
        source.checkSelected();source.checkReadable(input.document)
        check(!input.document.partial && !input.document.virtual && !source.excluded(input.document) && source.stat(input.document)==input.document) { "ASTRA_SOURCE_UNAVAILABLE" }
        val inventory=source.inventory()
        check(inventory.complete && inventory.documents.count{it.documentId==input.document.documentId}==1 && inventory.documents.single{it.documentId==input.document.documentId}==input.document) { "ASTRA_INCOMPLETE_SOURCE_LIST" }
    }
    private fun permitted(r:AstraAudioJournal.Record) {
        check(journal.load(r.id).state !in setOf("CANCELLED","CANCEL_REQUESTED")) { "ASTRA_CANCELLED" }
        r.inputs.forEach(::permitted)
    }
    private fun upload(r:AstraAudioJournal.Record):List<AstraAudioUpload> {
        val files=mutableListOf<AstraAudioUpload>()
        try {
            for(i in r.inputs) {
                permitted(r);check(transferApproved(r.id)) { "ASTRA_TRANSFER_APPROVAL_REQUIRED" }
                val out=ByteArrayOutputStream()
                source.open(i.document).use{stream->val buffer=ByteArray(8192);while(true) {
                    permitted(r);check(transferApproved(r.id)) { "ASTRA_TRANSFER_APPROVAL_REQUIRED" }
                    val n=stream.read(buffer);if(n<0)break
                    check(out.size().toLong()+n<=i.document.size && out.size()+n<=AstraAudioContract.MAX_AUDIO) { "ASTRA_AUDIO_SIZE" }
                    out.write(buffer,0,n)
                };buffer.fill(0)}
                val bytes=out.toByteArray()
                if(bytes.size.toLong()!=i.document.size || AstraAudioContract.sha(bytes)!=i.audioSha) {bytes.fill(0);error("ASTRA_AUDIO_HASH")}
                files+=AstraAudioUpload(i.sourceId,i.audioSha,bytes)
            }
            permitted(r);check(transferApproved(r.id)) { "ASTRA_TRANSFER_APPROVAL_REQUIRED" };return files
        } catch(e:Exception) {files.forEach{it.erase()};throw e}
    }
    private fun retryAt(r:AstraAudioJournal.Record)=now()+minOf(900000L,30000L*(1L shl minOf(r.attempts,5)))
    fun cycle(id:String):String {
        if(!journal.claim(id,now()))return state(id)
        try {
            var r=journal.load(id)
            if(r.state=="CANCEL_REQUESTED") {
                if(online() && transport.gate().state=="READY" && transport.cancel(id,r.receipt))journal.cancelAcknowledged(id)
                return state(id)
            }
            permitted(r)
            if(r.digest.isNotBlank() && path(id,".json.enc").exists()) {
                read(id);journal.state(id,"RESULT_READY");return state(id)
            }
            val gate=transport.gate()
            if(gate.state!="READY") {
                if(r.state in setOf("PREPARED","WAITING_PROVIDER","OFFLINE"))journal.state(id,"WAITING_PROVIDER",next=retryAt(r))
                return state(id)
            }
            if(!online()) {
                if(r.state in setOf("PREPARED","WAITING_PROVIDER","OFFLINE"))journal.state(id,"OFFLINE",next=retryAt(r))
                return state(id)
            }
            check(transferApproved(id)) { "ASTRA_TRANSFER_APPROVAL_REQUIRED" }
            if(r.receipt.isBlank()) {
                val dispatch=if(r.state in setOf("UNKNOWN","DISPATCHING")) {
                    try { transport.reconcile(id,r.manifest.clone()) } catch(_:Exception) { journal.state(id,"UNKNOWN",next=retryAt(r),attempt=true);return state(id) }
                } else {
                    val files=upload(r)
                    try {
                        journal.state(id,"DISPATCHING",attempt=true) // durable before external side effects
                        permitted(r);check(transferApproved(id)) { "ASTRA_TRANSFER_APPROVAL_REQUIRED" }
                        try { transport.submit(id,r.manifest.clone(),files) } catch(_:Exception) { journal.state(id,"UNKNOWN",next=retryAt(r));return state(id) }
                    } finally { files.forEach{it.erase()} }
                }
                permitted(r)
                when(dispatch.state) {
                    "ACCEPTED" -> {require(dispatch.receipt.length in 1..256) { "ASTRA_RECEIPT" };journal.state(id,"WAITING_RESULT",receipt=dispatch.receipt)}
                    "FAILED" -> {journal.state(id,"REJECTED");return state(id)}
                    else -> {journal.state(id,"UNKNOWN",next=retryAt(r),attempt=true);return state(id)}
                }
            }
            r=journal.load(id)
            val stream=try {transport.result(id,r.receipt)} catch(_:Exception) {journal.state(id,"WAITING_RESULT",next=retryAt(r),attempt=true);return state(id)}
            if(stream==null) {journal.state(id,"WAITING_RESULT",next=retryAt(r),attempt=true);return state(id)}
            stream.use{receive(r,it)}
            return state(id)
        } finally {journal.release(id)}
    }
    private fun receive(r:AstraAudioJournal.Record,stream:InputStream) {
        val part=path(r.id,".part");val complete=path(r.id,".json.enc")
        try {
            val out=ByteArrayOutputStream();val buffer=ByteArray(8192)
            while(true) {
                permitted(r);val n=stream.read(buffer);if(n<0)break
                check(out.size()+n<=AstraAudioContract.MAX_RESULT) { "ASTRA_RESULT_SIZE" };out.write(buffer,0,n)
            }
            val bytes=out.toByteArray()
            AstraAudioContract.verify(bytes,r.manifest,keys,allowFixture,r.receipt)
            val encrypted=sealed.seal(r.id,bytes,journal.ids().all{journal.load(it).digest.isBlank()} && directory.listFiles().orEmpty().isEmpty())
            FileOutputStream(part).use{it.write(encrypted);it.fd.sync()}
            check(sealed.open(r.id,readBounded(part)).contentEquals(bytes)) { "ASTRA_FILE_AUTHENTICATION" }
            permitted(r);val digest=AstraAudioContract.sha(bytes)
            journal.state(r.id,"PUBLISHING",digest=digest) // binds exact bytes before rename; restart repairs this window
            if(complete.exists())check(AstraAudioContract.sha(sealed.open(r.id,readBounded(complete)))==digest) { "ASTRA_RESULT_CONFLICT" }
            Files.move(part.toPath(),complete.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
            java.nio.channels.FileChannel.open(directory.toPath(),java.nio.file.StandardOpenOption.READ).use{it.force(true)}
            // Completed files are never inferred from a directory listing; journal + signature are mandatory.
            permitted(r);read(r.id);journal.state(r.id,"RESULT_READY")
        } finally {part.delete()}
    }
    private fun readBounded(file:File):ByteArray = FileInputStream(file).use { input ->
        val out=ByteArrayOutputStream();val buffer=ByteArray(8192)
        while(true) { val n=input.read(buffer);if(n<0)break
            check(out.size()+n<=AstraAudioContract.MAX_RESULT+29) { "ASTRA_RESULT_SIZE" };out.write(buffer,0,n)
        }
        out.toByteArray()
    }
    fun read(id:String):AstraAudioContract.Verified {
        val r=journal.load(id);permitted(r);check(r.digest.isNotBlank()) { "ASTRA_RESULT_NOT_COMMITTED" }
        val file=path(id,".json.enc");check(file.length() in 30..AstraAudioContract.MAX_RESULT.toLong()+29) { "ASTRA_RESULT_SIZE" }
        val bytes=sealed.open(id,readBounded(file))
        check(AstraAudioContract.sha(bytes)==r.digest) { "ASTRA_RESULT_INTEGRITY" }
        return AstraAudioContract.verify(bytes,r.manifest,keys,allowFixture,r.receipt).also{permitted(r)}
    }
    fun observation(job:String,handoff:String):AutomationObservation {
        val id=find(job) ?: return AutomationObservation.Waiting("ASTRA_JOB_NOT_PREPARED")
        val r=journal.load(id)
        if(r.state !in setOf("RESULT_READY","PUBLISHING"))return AutomationObservation.Waiting("ASTRA_RESULT_PENDING")
        return read(id).observation(job,handoff)
    }
    fun cancel(id:String) { journal.cancel(id);path(id,".part").delete() }
    override fun close()=journal.close()
}
