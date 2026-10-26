package kr.callbrief

import android.content.Context
import kr.callagent.drive.DriveRelay
import kr.callagent.drive.StrictJson
import kr.co.callsummary.core.android.AndroidSqlPort
import org.json.JSONObject
import java.util.UUID

/** Local execution authority, installed by application code, never by an inbound JSON/Intent.
 * The production adapter admits only the exact bundled public synthetic WAVs. */
internal interface UnclassifiedAudioTranscriber {
    val runtimeSha:String
    val modelSha:String
    val kind:LocalExecutionKind
    val publicAudio:Set<String>
    fun verify()
    fun transcribe(audio:ByteArray,stopped:()->Boolean):AndroidWhisperOutput
}
internal class AndroidUnclassifiedTranscriber(private val engine:AndroidWhisperEngine):UnclassifiedAudioTranscriber {
    override val runtimeSha get()=engine.runtimeSha
    override val modelSha get()=engine.modelSha
    override val kind=LocalExecutionKind.CURRENT_LOCAL_PROCESS
    override val publicAudio get()=engine.pin.getJSONObject("publicAudio").let{p->p.keys().asSequence().map{p.getString(it)}.toSet()}
    override fun verify()=engine.verify()
    override fun transcribe(audio:ByteArray,stopped:()->Boolean)=engine.transcribe(audio,stopped)
}

/** Additive app-local journal in the existing sealed database. No frozen wire/shared SQL change.
 * prepare -> analyse has no case/task/time requirement; confirmBinding is a separate user action.
 * Releasing a bound result still goes through LocalRecordingResultConsumer and Store.accept.
 * No private-audio admission, external dot execution, authority inferred from a hash or fake IDs. */
internal class UnclassifiedAudioStore(private val context:Context,private val source:SelectedRecordingSource,
    private val transcriber:UnclassifiedAudioTranscriber,private val current:()->Boolean,
    private val policy:()->String,private val authority:String,private val now:()->Long=System::currentTimeMillis):AutoCloseable {
    private val workflow=RecordingWorkflowStore(context)
    private val db=AndroidSqlPort(context,GeneralLocalStore.DATABASE,context.assets.open("call_core_schema.sql"))
    private val process=PROCESS_TOKEN
    init {
        require(authority.isNotBlank() && authority.length<=2048);db.requireProtectedPersistence()
        db.transaction {
            db.execute("CREATE TABLE IF NOT EXISTS unclassified_audio_requests(id TEXT PRIMARY KEY REFERENCES workflow_analysis_requests(id),receipt_json TEXT NOT NULL,state TEXT NOT NULL,process_token TEXT NOT NULL,reason TEXT NOT NULL)")
            db.execute("CREATE TABLE IF NOT EXISTS unclassified_audio_results(id TEXT PRIMARY KEY REFERENCES unclassified_audio_requests(id),raw_output BLOB NOT NULL,raw_sha TEXT NOT NULL,runtime_sha TEXT NOT NULL,model_sha TEXT NOT NULL,execution_kind TEXT NOT NULL)")
            db.execute("CREATE TABLE IF NOT EXISTS unclassified_audio_bindings(id TEXT PRIMARY KEY REFERENCES unclassified_audio_results(id),snapshot_json TEXT NOT NULL,confirmed_ms INTEGER NOT NULL)")
            db.execute("CREATE TABLE IF NOT EXISTS unclassified_audio_events(id TEXT PRIMARY KEY,request_id TEXT NOT NULL,kind TEXT NOT NULL,at_ms INTEGER NOT NULL)")
            db.execute("CREATE TRIGGER IF NOT EXISTS unclassified_receipt_immutable BEFORE UPDATE OF receipt_json ON unclassified_audio_requests BEGIN SELECT RAISE(ABORT,'IMMUTABLE_RECEIPT');END")
            for(t in listOf("unclassified_audio_results","unclassified_audio_bindings","unclassified_audio_events")) {
                for(a in listOf("UPDATE","DELETE"))db.execute("CREATE TRIGGER IF NOT EXISTS ${t}_${a.lowercase()} BEFORE $a ON $t BEGIN SELECT RAISE(ABORT,'IMMUTABLE_HISTORY');END")
                db.execute("CREATE TRIGGER IF NOT EXISTS ${t}_no_replace BEFORE INSERT ON $t WHEN EXISTS(SELECT 1 FROM $t WHERE id=NEW.id) BEGIN SELECT RAISE(ABORT,'IMMUTABLE_HISTORY');END")
            }
            // A process death is an uncertain execution, never permission to run again.
            db.execute("UPDATE unclassified_audio_requests SET state='UNKNOWN',reason='INTERRUPTED_LOCAL_ANALYSIS' WHERE state='RUNNING' AND process_token!=?",process)
            null
        }
    }
    private fun sha(bytes:ByteArray)=DriveRelay.sha256(bytes)
    private fun canonical(x:JSONObject)=StrictJson.stringify(StrictJson.parse(x.toString().toByteArray(Charsets.UTF_8)))
    private fun request(id:String)=db.query("SELECT * FROM unclassified_audio_requests WHERE id=?",id).single()
    private fun receipt(id:String)=JSONObject(request(id)["receipt_json"].toString())
    private fun result(id:String)=db.query("SELECT * FROM unclassified_audio_results WHERE id=?",id).singleOrNull()
    fun status(id:String)=request(id)["state"].toString()
    private fun event(id:String,kind:String) {db.execute("INSERT INTO unclassified_audio_events VALUES(?,?,?,?)",UUID.randomUUID().toString(),id,kind,now())}
    private fun state(id:String,state:String,reason:String=state) {
        val old=request(id)
        if(old["state"]!=state || old["reason"]!=reason)event(id,reason)
        db.execute("UPDATE unclassified_audio_requests SET state=?,reason=? WHERE id=?",state,reason,id)
    }
    private fun document(r:JSONObject)=r.getJSONObject("document").let{d->SourceDocument(d.getString("id"),d.getString("uri"),d.getString("name"),d.getLong("size"),d.getString("mime"),d.getLong("modified"),d.getBoolean("partial"),d.getBoolean("virtual"))}
    private fun sourceRow(id:String)=db.query("""SELECT r.*,v.audio_sha256,l.root_uri,l.actual_document_id,l.actual_uri,
        a.archived,a.revision AS visibility_revision,g.active AS grant_active,g.selected_by_user,
        coalesce(auth.epoch,0) AS authority_epoch,
        q.plaintext_bytes AS audio_bytes
        FROM recordings r JOIN source_versions v ON v.recording_id=r.recording_id AND v.source_version=r.source_version
        JOIN local_files l ON l.recording_id=r.recording_id JOIN local_visibility a ON a.recording_id=r.recording_id
        JOIN folder_grants g ON g.grant_id=r.grant_id
        JOIN vault2_refs q ON q.recording_id=r.recording_id AND q.source_version=r.source_version AND q.plaintext_sha256=v.audio_sha256
        LEFT JOIN workflow_analysis_authority auth ON auth.recording_id=r.recording_id
        WHERE r.recording_id=? AND r.owner_key=?""".trimIndent(),id,GeneralLocalStore.OWNER).single()
    private fun permitted(r:JSONObject,checkRevisions:Boolean) {
        check(current() && authority==r.getString("authority") && policy()==r.getString("policy") && now()<r.getLong("expiresMs")){"UNCLASSIFIED_AUTHORITY_OR_LEASE_CHANGED"}
        val prepared=workflow.request(r.getString("requestId"));val binding=r.getJSONObject("preparedBinding")
        check(prepared.getString("recording_id")==r.getString("sourceId") && canonical(JSONObject(prepared.getString("binding_json")))==canonical(binding) &&
            binding.getString("audioSha")==r.getString("audioSha") && binding.getLong("sourceVersion")==r.getJSONObject("source").getLong("source_version")){"UNCLASSIFIED_PREPARED_RECEIPT_CHANGED"}
        check(request(r.getString("requestId"))["state"]!="CANCELLED" && prepared.getString("state")!="CANCELLED"){"UNCLASSIFIED_CANCELLED"}
        val row=sourceRow(r.getString("sourceId"));val frozen=r.getJSONObject("source")
        for(k in listOf("recording_id","source_version","audio_sha256","root_uri","actual_document_id","actual_uri","authority_epoch"))
            check(row[k].toString()==frozen.get(k).toString()){"UNCLASSIFIED_SOURCE_CHANGED"}
        check(row["presence"]=="PRESENT" && row["archived"]==0L && row["grant_active"]==1L && row["selected_by_user"]==1L){"UNCLASSIFIED_SOURCE_UNAVAILABLE"}
        check(source.explicitlySyntheticScope && source.rootUri==row["root_uri"] && r.getString("audioSha") in transcriber.publicAudio){"UNCLASSIFIED_PUBLIC_SYNTHETIC_REQUIRED"}
        val d=document(r);source.checkSelected();source.checkReadable(d)
        check(!d.partial && !d.virtual && !source.excluded(d) && source.stat(d)==d){"UNCLASSIFIED_DOCUMENT_HELD"}
        val inventory=source.inventory()
        check(inventory.complete && inventory.documents.count{it.documentId==d.documentId}==1 && inventory.documents.single{it.documentId==d.documentId}==d){"UNCLASSIFIED_INCOMPLETE_OR_AMBIGUOUS_SOURCE"}
        if(checkRevisions)check(revisionsCurrent(r)){"UNCLASSIFIED_REVISION_CONFLICT"}
    }
    private fun revisionsCurrent(r:JSONObject):Boolean {
        val s=workflow.snapshot(r.getString("sourceId"));val b=r.getJSONObject("preparedBinding")
        return listOf("sourceVersion" to "source_version","userRevision" to "user_revision","lockRevision" to "lock_revision","headRevision" to "head_revision","visibilityRevision" to "visibility_revision","boardRevision" to "board_revision")
            .all{(saved,key)->b.getLong(saved)==s.getLong(key)} && b.getString("audioSha")==s.getString("audio_sha256")
    }
    fun prepare(recording:String,expected:JSONObject,retryOf:String?=null):String=db.transaction {
        check(current()){"UNCLASSIFIED_AUTHORITY_CHANGED"}
        if(retryOf!=null) {
            val parent=receipt(retryOf)
            check(parent.getString("sourceId")==recording && result(retryOf)==null && request(retryOf)["state"] in setOf("HELD","UNKNOWN","CANCELLED")){"UNCLASSIFIED_RETRY_NOT_ALLOWED"}
        }
        val id=workflow.prepareAnalysis(recording,expected,retryOf=retryOf)
        if(db.query("SELECT id FROM unclassified_audio_requests WHERE id=?",id).isNotEmpty())return@transaction id
        val row=sourceRow(recording)
        val inventory=source.inventory();check(inventory.complete){"UNCLASSIFIED_INCOMPLETE_OR_AMBIGUOUS_SOURCE"}
        val d=inventory.documents.single{it.documentId==row["actual_document_id"] && it.uri==row["actual_uri"]}
        val r=JSONObject().put("schema","ieum.unclassified-audio-receipt.v1").put("requestId",id).put("sourceId",recording)
            .put("source",JSONObject(row)).put("audioSha",row["audio_sha256"]).put("policy",policy()).put("authority",authority).put("expiresMs",now()+1800000)
            .put("preparedBinding",JSONObject(workflow.request(id).getString("binding_json")))
            .put("document",JSONObject().put("id",d.documentId).put("uri",d.uri).put("name",d.name).put("size",d.size).put("mime",d.mime).put("modified",d.modified).put("partial",d.partial).put("virtual",d.virtual))
        db.execute("INSERT INTO unclassified_audio_requests VALUES(?,?,'PREPARED',?,'LOCAL_ANALYSIS_PREPARED')",id,canonical(r),process)
        permitted(r,true);check(d.size==row["audio_bytes"] && d.size in 44..10485760){"UNCLASSIFIED_AUDIO_SIZE"}
        event(id,"LOCAL_ANALYSIS_PREPARED");id
    }
    fun view(id:String):JSONObject=db.transaction {
        val r=receipt(id);permitted(r,false);val saved=result(id);val output=saved?.let(::checkedOutput)
        JSONObject().put("requestId",id).put("sourceId",r.getString("sourceId")).put("audioSha",r.getString("audioSha"))
            .put("state",request(id)["state"]).put("reason",request(id)["reason"]).put("receipt",r)
            .put("transcript",output?.text ?: JSONObject.NULL).put("transcriptSha",output?.let{sha(it.text.toByteArray(Charsets.UTF_8))} ?: JSONObject.NULL)
            .put("segments",output?.segments ?: JSONObject.NULL)
            .put("observation",saved?.let{JSONObject().put("executionKind",it["execution_kind"]).put("runtimeSha",it["runtime_sha"]).put("modelSha",it["model_sha"]).put("rawOutputSha",it["raw_sha"])} ?: JSONObject.NULL)
            .put("analysis",output?.let{AndroidWhisperRunner.extract(it.text,JSONObject().put("proposedSummary",""))} ?: JSONObject.NULL)
    }
    private fun checkedOutput(row:Map<String,Any?>):AndroidWhisperOutput {
        val bytes=(row["raw_output"] as ByteArray).clone()
        check(sha(bytes)==row["raw_sha"] && row["runtime_sha"]==transcriber.runtimeSha && row["model_sha"]==transcriber.modelSha){"UNCLASSIFIED_OUTPUT_BINDING"}
        return AndroidWhisperEngine.decodeOutput(bytes)
    }
    fun analyse(id:String,stopped:()->Boolean={false}):String {
        val r=db.transaction {
            val row=request(id)
            if(row["state"]!="PREPARED")return@transaction null
            val r=receipt(id);permitted(r,true);check(!stopped()){"UNCLASSIFIED_CANCELLED"}
            state(id,"RUNNING");db.execute("UPDATE unclassified_audio_requests SET process_token=? WHERE id=?",process,id);r
        } ?: return status(id)
        var audio:ByteArray?=null
        try {
            val d=document(r);val bytes=java.io.ByteArrayOutputStream()
            source.open(d).use{input->val buffer=ByteArray(8192);while(true){
                permitted(r,true);check(!stopped()){"UNCLASSIFIED_CANCELLED"}
                val n=input.read(buffer);if(n<0)break;check(bytes.size().toLong()+n<=d.size){"UNCLASSIFIED_AUDIO_SIZE"};bytes.write(buffer,0,n)
            }}
            audio=bytes.toByteArray();check(audio.size.toLong()==d.size && sha(audio)==r.getString("audioSha")){"UNCLASSIFIED_AUDIO_HASH"}
            AndroidWhisperEngine.decodePcm(audio).fill(0f)
            transcriber.verify();val output=transcriber.transcribe(audio){stopped() || !current() || request(id)["state"]=="CANCELLED"}
            val decoded=AndroidWhisperEngine.decodeOutput(output.raw)
            check(decoded.text==output.text && canonical(JSONObject().put("segments",decoded.segments))==canonical(JSONObject().put("segments",output.segments))){"UNCLASSIFIED_OUTPUT_BINDING"}
            db.transaction {
                permitted(r,false);check(!stopped() && request(id)["state"]=="RUNNING"){"UNCLASSIFIED_CANCELLED"};transcriber.verify()
                db.execute("INSERT INTO unclassified_audio_results VALUES(?,?,?,?,?,?)",id,output.raw,sha(output.raw),transcriber.runtimeSha,transcriber.modelSha,transcriber.kind.name)
                val unchanged=revisionsCurrent(r)
                state(id,if(unchanged)"RESULT_READY" else "REVIEW_REQUIRED",if(unchanged)"UNCLASSIFIED_ANALYSIS_AWAITING_USER_BINDING" else "UNCLASSIFIED_REVISION_CONFLICT")
                request(id)["state"].toString()
            }.let{return it}
        } catch(e:Exception) {
            db.transaction {if(request(id)["state"]!="CANCELLED")state(id,if(stopped())"CANCELLED" else "HELD",e.message?.takeIf{it.matches(Regex("[A-Z0-9_]{1,100}"))} ?: "UNCLASSIFIED_ANALYSIS_FAILED");null}
            return request(id)["state"].toString()
        } finally {audio?.fill(0)}
    }
    /** Caller must first use existing explicit confirmWork/confirmTime commands. This command
     * acknowledges their exact new snapshot, never infers people/work/time from STT or a phone. */
    fun confirmBinding(id:String,expected:JSONObject,confirmed:Boolean):String=db.transaction {
        check(confirmed){"UNCLASSIFIED_USER_BINDING_REQUIRED"}
        val r=receipt(id);permitted(r,false)
        check(request(id)["state"] in setOf("RESULT_READY","BOUND")){"UNCLASSIFIED_RESULT_NOT_READY"};checkedOutput(checkNotNull(result(id)))
        val snapshot=workflow.snapshot(r.getString("sourceId"))
        check(listOf("person_id","case_id","task_id","original_started_at_ms").all{!snapshot.isNull(it)}){"UNCLASSIFIED_EXPLICIT_CONTEXT_REQUIRED"}
        for(k in listOf("source_version","user_revision","lock_revision","head_revision","visibility_revision","board_revision","task_revision","link_revision"))
            check(snapshot.getLong(k)==expected.getLong(k)){"UNCLASSIFIED_BINDING_REVISION_CONFLICT"}
        val old=db.query("SELECT snapshot_json FROM unclassified_audio_bindings WHERE id=?",id).singleOrNull()
        if(old!=null)check(old["snapshot_json"]==canonical(snapshot)){"UNCLASSIFIED_BINDING_IMMUTABLE"}
        else {db.execute("INSERT INTO unclassified_audio_bindings VALUES(?,?,?)",id,canonical(snapshot),now());event(id,"USER_CONFIRMED_AUDIO_BINDING")}
        state(id,"BOUND");id
    }
    fun cancel(id:String)=db.transaction {
        workflow.transitionRequest(id,"CANCELLED",reason="UNCLASSIFIED_USER_CANCELLED");state(id,"CANCELLED");null
    }
    /** Metadata only; a cancelled, missing or ambiguous receipt cannot start native publication. */
    fun canRelease(snapshot:JSONObject):Boolean=db.transaction {
        val matching=db.query("SELECT id FROM unclassified_audio_requests WHERE state='BOUND'").filter{
            val r=receipt(it["id"].toString());r.getString("sourceId")==snapshot.getString("recording_id") && r.getString("audioSha")==snapshot.getString("audio_sha256") && r.getJSONObject("source").getLong("source_version")==snapshot.getLong("source_version")
        }
        if(matching.size!=1)return@transaction false
        // Do not catch a failed nested transaction: it must roll the admission back.
        permitted(receipt(matching.single()["id"].toString()),false);true
    }
    /** Source identities remain separate even when their content hashes are equal. */
    fun boundRunner()=object:LocalRecordingRunner {
        private fun selected(handoff:String):String {
            val wire=JSONObject(JSONObject(handoff).getString("frozenRequestJson"))
            val rows=db.query("SELECT r.id FROM unclassified_audio_requests r JOIN unclassified_audio_bindings b ON b.id=r.id WHERE r.state='BOUND'")
                .filter{val r=receipt(it["id"].toString());r.getString("sourceId")==wire.getString("sourceId") && r.getString("audioSha")==wire.getString("audioSha") && r.getJSONObject("source").getLong("source_version")==wire.getLong("sourceVersion")}
            check(rows.size==1){"UNCLASSIFIED_EXACT_BOUND_RESULT_REQUIRED"}
            val id=rows.single()["id"].toString();permitted(receipt(id),false)
            val b=JSONObject(db.query("SELECT snapshot_json FROM unclassified_audio_bindings WHERE id=?",id).single()["snapshot_json"].toString())
            // The board can advance when another source is linked/applied. The native request
            // and Store.accept freeze that board/business head; this receipt binds this source.
            for((local,remote) in listOf("user_revision" to "userRevision","lock_revision" to "lockRevision","task_revision" to "taskRevision","visibility_revision" to "archiveRevision"))
                check(b.getLong(local)==wire.getLong(remote)){"UNCLASSIFIED_BOUND_REQUEST_CHANGED"}
            check(b.getLong("head_revision")==JSONObject(wire.getString("contextJson")).getLong("nativeHeadRevision")){"UNCLASSIFIED_BOUND_REQUEST_CHANGED"}
            return id
        }
        private fun receiptFor(id:String,handoff:String,business:JSONObject)=sha(("ieum-unclassified-bound-v1\n"+id+"\n"+handoff+"\n"+canonical(business)).toByteArray())
        override fun submit(key:String,handoff:String,business:JSONObject):AutomationDispatch {
            val id=selected(handoff);return AutomationDispatch("ACCEPTED",receiptFor(id,handoff,business),"UNCLASSIFIED_BOUND_LOCAL_RESULT")
        }
        override fun reconcile(key:String,handoff:String,business:JSONObject)=submit(key,handoff,business)
        override fun receive(key:String,receipt:String,handoff:String,business:JSONObject):LocalRecordingRun {
            val id=selected(handoff);check(receipt==receiptFor(id,handoff,business)){"UNCLASSIFIED_RECEIPT_CHANGED"}
            transcriber.verify();val row=checkNotNull(result(id));val output=checkedOutput(row)
            val kind=LocalExecutionKind.valueOf(row["execution_kind"].toString()).let{if(it==LocalExecutionKind.CURRENT_LOCAL_PROCESS)LocalExecutionKind.TRUSTED_LOCAL_REPLAY else it}
            return LocalRecordingPacket.create(output,handoff,business,transcriber.runtimeSha,transcriber.modelSha,kind)
        }
    }
    override fun close(){db.close();workflow.close()}
    companion object {private val PROCESS_TOKEN=UUID.randomUUID().toString()}
}

/** One exact result format for the existing consumer, independent of when business was bound. */
internal object LocalRecordingPacket {
    fun create(output:AndroidWhisperOutput,handoff:String,business:JSONObject,runtimeSha:String,modelSha:String,kind:LocalExecutionKind):LocalRecordingRun {
        fun sha(s:String)=DriveRelay.sha256(s.toByteArray(Charsets.UTF_8))
        fun canonical(x:JSONObject)=StrictJson.stringify(StrictJson.parse(x.toString().toByteArray(Charsets.UTF_8)))
        val frozen=JSONObject(handoff).getString("frozenRequestJson");val r=JSONObject(frozen);val binding=JSONObject()
        listOf("jobId","ownerBinding","sourceId","sourceVersion","audioSha","baseRevision","userRevision","lockRevision","taskRevision","archiveRevision","leaseEpoch","resultFileId","requestDigest").forEach{binding.put(it,r.get(it))}
        val businessSha=sha(canonical(business));val rawSha=DriveRelay.sha256(output.raw)
        val current=kind in setOf(LocalExecutionKind.CURRENT_LOCAL_PROCESS,LocalExecutionKind.TRUSTED_LOCAL_REPLAY)
        val result=JSONObject().put("schema","ieum.local-recording-producer-result.v1").put("kind","LOCAL_RECORDING_PROPOSAL").put("requestSha",sha(frozen)).put("businessContextSha",businessSha).put("binding",binding).put("revision",1)
            .put("stt",JSONObject().put("provider",if(current)"WHISPER_CPP_LOCAL" else kind.name).put("runtimeSha",runtimeSha).put("modelSha",modelSha).put("text",output.text).put("textSha",sha(output.text)).put("segments",output.segments))
            .put("analysis",AndroidWhisperRunner.extract(output.text,business)).put("execution",JSONObject().put("kind",if(current)"CURRENT_LOCAL_PROCESS" else kind.name).put("rawOutputSha",rawSha).put("exitCode",0))
            .put("canonicalWriteAllowed",false).put("applied",false).put("dotInvoked",false)
        result.put("resultDigest",sha("ieum-local-recording-producer-v1\n"+canonical(result)))
        return LocalRecordingRun(canonical(result).toByteArray(Charsets.UTF_8),LocalExecutionObservation(kind,r.getString("audioSha"),sha(frozen),businessSha,runtimeSha,modelSha,output.raw,0))
    }
}
