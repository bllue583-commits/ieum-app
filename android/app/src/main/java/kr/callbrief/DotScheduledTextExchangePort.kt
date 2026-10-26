package kr.callbrief

import android.content.ContentValues
import android.content.Context
import android.os.Looper
import kr.callagent.drive.*
import kr.co.callsummary.core.android.ProtectedDatabase
import java.time.Instant
import java.util.UUID

/** Current supported scheduled-dot text lane. No audio, task writes, scheduling tool call or new grant. */
internal data class DotTextExchangeConfiguration(val enabled:Boolean=false,val queueFolder:String="")
internal class DotScheduledTextExchangePort(private val context:Context,private val session:GoogleConnectionSession,
    private val configuration:()->DotTextExchangeConfiguration={DotTextExchangeConfiguration()},
    private val transport:DriveTransport=DriveRestTransport(session,GoogleDriveHttp(session)),
    private val preparedSyntheticInput:((String)->PreparedWhisperScheduledTextInput)?=null,private val clock:()->Instant=Instant::now) {
    data class Registration(val job:String,val folder:String,val input:String,val result:String,val descriptor:String,val inputSha:String,val descriptorSha:String,val state:String) {
        val schedulerRegistered=false;val dotInvoked=false;val audioTransferred=false
    }
    data class Outcome(val state:String,val registration:Registration?=null,val proposal:ScheduledTextProofCodec.Proposal?=null) {
        val nativeAudioRequestCompleted=false;val sttExecuted=false;val canonicalWriteAllowed=false;val kakaoSent=false
    }
    private fun enabled():Pair<DotTextExchangeConfiguration,DriveALocalScope>? {
        val c=configuration();if(!c.enabled)return null
        check(Looper.myLooper()!=Looper.getMainLooper()) { "DOT_TEXT_EXCHANGE_IO_THREAD_REQUIRED" }
        val s=DriveALocalScope.current(session) ?: error("DOT_TEXT_CURRENT_NATIVE_GRANT_REQUIRED")
        check(Regex("^[A-Za-z0-9_-]{1,256}$").matches(c.queueFolder)) { "DOT_TEXT_QUEUE_CONFIGURATION_REQUIRED" };return c to s
    }
    private fun checkMeta(id:String,parent:String?,mime:String,s:DriveALocalScope):DriveTransport.Metadata {
        session.requireEpoch(s.epoch);check(session.ownerBinding()==s.owner && session.state()==AuthorizationPort.State.ACTIVE) { "DOT_TEXT_ACCOUNT_CHANGED" }
        val m=transport.metadata(id);check(m.id==id && m.ownerBinding==s.owner && m.mimeType==mime && m.privateToOwner && m.isAppAuthorized && !m.trashed && (parent==null||m.parentId==parent)) { "DOT_TEXT_PRIVATE_APP_FILE_ACCESS_REQUIRED" };return m
    }
    private fun read(id:String,parent:String,s:DriveALocalScope):Pair<ByteArray,String> {
        val before=checkMeta(id,parent,DriveTransport.JSON_MIME,s);check(before.size in 1..65536) { "DOT_TEXT_FILE_SIZE" }
        val bytes=transport.download(id,65536).bytes;val after=checkMeta(id,parent,DriveTransport.JSON_MIME,s)
        check(before.version==after.version && before.size==after.size && after.size==bytes.size.toLong()) { "DOT_TEXT_FILE_CHANGED_DURING_READ" };return bytes to after.version
    }
    private fun createExact(spec:DriveTransport.FileSpec,bytes:ByteArray,s:DriveALocalScope) {
        try{session.commit(s.epoch,s.owner){transport.create(spec,bytes)}}catch(e:DriveTransport.DriveException){if(e.failure!=DriveTransport.Failure.CONFLICT)throw e}
        checkMeta(spec.id,spec.parentId,spec.mimeType,s)
        if(spec.mimeType!=DriveTransport.FOLDER_MIME)check(read(spec.id,spec.parentId,s).first.contentEquals(bytes)) { "DOT_TEXT_CREATE_CONTENT_CONFLICT" }
    }
    /** Fixed approved text or a strictly pinned approved synthetic local-STT request. */
    fun publishFixedSynthetic(proofRequestId:String):Outcome {
        val (c,s)=enabled() ?: return Outcome("DOT_TEXT_EXCHANGE_NOT_CONFIGURED")
        val prepared=preparedSyntheticInput?.invoke(proofRequestId)
        val preparedBytes=prepared?.forJob(proofRequestId)
        checkMeta(c.queueFolder,null,DriveTransport.FOLDER_MIME,s)
        return DotTextExchangeJournal(context,s).use { journal ->
            check(UUID.fromString(proofRequestId).toString()==proofRequestId) { "DOT_TEXT_CANONICAL_PROOF_KEY_REQUIRED" }
            val old=journal.load(proofRequestId)
            if(old!=null){validatePrepared(old,prepared);return@use Outcome(if(old.state in setOf("SENT","READ_ONLY_PROPOSAL_RECEIVED"))old.state else "DOT_TEXT_PUBLICATION_RECONCILE_REQUIRED",old.registration())}
            check(!journal.anyScopeContains(proofRequestId)) { "DOT_TEXT_SCOPE_CHANGED_RECONCILE_REQUIRED" }
            val ids=session.commit(s.epoch,s.owner){transport.generateIds(3)};check(ids.size==3&&ids.distinct().size==3&&ids.all{Regex("^[A-Za-z0-9_-]{1,256}$").matches(it)}) { "DOT_TEXT_RESERVED_IDS_REQUIRED" }
            val job=proofRequestId;val created=clock()
            val input=preparedBytes ?: StrictJson.bytes(mapOf("schema_version" to ScheduledTextProofCodec.SCHEMA,"job_id" to job,"synthetic" to true,"source_id" to "synthetic-conversation-a",
                "transcript" to listOf(mapOf("speaker" to "가람","text" to "제안서 초안은 내가 정리해서 공유할게. 가능하면 다음 주쯤 보내려고 해."),mapOf("speaker" to "나래","text" to "좋아. 검토 의견은 초안을 받은 뒤에 정리할게."),mapOf("speaker" to "가람","text" to "정확한 날짜는 아직 못 정했어."))))
            val pending=StrictJson.bytes(mapOf("schema_version" to "scheduled-dot-pending-v1","job_id" to job,"synthetic" to true,"source_file_id" to ids[0],"state" to "PENDING"))
            val descriptorFields=mapOf("schema_version" to "ieum.scheduled-dot-enrollment.v1","job_id" to job,"synthetic" to true,"folder_id" to c.queueFolder,"source_file_id" to ids[0],"result_file_id" to ids[1],"source_sha256" to DriveRelay.sha256(input),"pending_result_sha256" to DriveRelay.sha256(pending),"owner_binding" to s.binding()["ownerBinding"],"native_session_binding" to s.binding(),"native_binding" to "LOCAL_DRIVE_ABOUT_NOT_SERVER_ATTESTED","created_at_utc" to created.toString(),"single_writer_required" to true,"remote_atomic_cas_verified" to false,"canonical_write_allowed" to false,"stt_executed" to false).toMutableMap()
            if(prepared!=null){descriptorFields["source_kind"]="LOCAL_WHISPER_DERIVED_SYNTHETIC_TEXT";descriptorFields["local_stt_evidence_sha256"]=prepared.evidenceSha;descriptorFields["upstream_stt_execution_reported"]=prepared.upstreamExecutionReported;descriptorFields["upstream_stt_independently_verified"]=false}
            val descriptor=StrictJson.bytes(descriptorFields)
            val r=DotTextExchangeJournal.Record(job,c.queueFolder,c.queueFolder,ids[0],ids[1],ids[2],input,pending,descriptor,created,"RESERVED",null)
            journal.reserve(r) // Every remote mutation uses these durable IDs and exact bytes.
            publish(r,journal,s)
        }
    }
    private fun validatePrepared(r:DotTextExchangeJournal.Record,prepared:PreparedWhisperScheduledTextInput?) {
        if(prepared==null)return
        check(r.inputBytes.contentEquals(prepared.forJob(r.job))){"LOCAL_STT_EXISTING_SLOT_INPUT_MISMATCH"}
        val d=StrictJson.`object`(StrictJson.parse(r.descriptorBytes))
        check(d["source_kind"]=="LOCAL_WHISPER_DERIVED_SYNTHETIC_TEXT" && d["local_stt_evidence_sha256"]==prepared.evidenceSha && d["upstream_stt_execution_reported"]==prepared.upstreamExecutionReported && d["upstream_stt_independently_verified"]==false){"LOCAL_STT_EXISTING_SLOT_EVIDENCE_MISMATCH"}
    }
    /** Explicit bounded resumption of this original publication. No successor IDs or result overwrite. */
    fun resumePublication(job:String):Outcome {
        val (c,s)=enabled() ?: return Outcome("DOT_TEXT_EXCHANGE_NOT_CONFIGURED")
        return DotTextExchangeJournal(context,s).use { journal ->
            val r=journal.load(job) ?: return@use Outcome("DOT_TEXT_UNKNOWN_JOB");check(r.queue==c.queueFolder) { "DOT_TEXT_QUEUE_CHANGED" };validatePrepared(r,preparedSyntheticInput?.invoke(job))
            if(r.state in setOf("SENT","READ_ONLY_PROPOSAL_RECEIVED"))return@use Outcome(r.state,r.registration())
            checkMeta(c.queueFolder,null,DriveTransport.FOLDER_MIME,s);publish(r,journal,s)
        }
    }
    private fun publish(r:DotTextExchangeJournal.Record,journal:DotTextExchangeJournal,s:DriveALocalScope):Outcome {
        journal.state(r.job,"PUBLISHING")
        return try {
            createExact(DriveTransport.FileSpec(r.result,r.folder,"result-"+r.job+".json",DriveTransport.JSON_MIME,s.owner,DriveTransport.Role.RESULT),r.pending,s)
            createExact(DriveTransport.FileSpec(r.input,r.folder,"input-"+r.job+".json",DriveTransport.JSON_MIME,s.owner,DriveTransport.Role.REQUEST),r.inputBytes,s)
            createExact(DriveTransport.FileSpec(r.descriptor,r.folder,"enrollment-"+r.job+".json",DriveTransport.JSON_MIME,s.owner,DriveTransport.Role.REQUEST),r.descriptorBytes,s) // Commit descriptor last.
            session.commit(s.epoch,s.owner){journal.state(r.job,"SENT")};Outcome("SENT_AWAITING_SCHEDULED_DOT",r.copy(state="SENT").registration())
        }catch(_:Exception){journal.state(r.job,"PUBLICATION_UNCERTAIN");Outcome("PUBLICATION_UNCERTAIN",r.copy(state="PUBLICATION_UNCERTAIN").registration())}
    }
    fun poll(job:String):Outcome {
        val (c,s)=enabled() ?: return Outcome("DOT_TEXT_EXCHANGE_NOT_CONFIGURED")
        return DotTextExchangeJournal(context,s).use { journal ->
            val r=journal.load(job) ?: return@use Outcome("DOT_TEXT_UNKNOWN_JOB");check(r.queue==c.queueFolder) { "DOT_TEXT_QUEUE_CHANGED" };validatePrepared(r,preparedSyntheticInput?.invoke(job))
            if(r.state !in setOf("SENT","READ_ONLY_PROPOSAL_RECEIVED"))return@use Outcome("DOT_TEXT_PUBLICATION_RECONCILE_REQUIRED",r.registration())
            checkMeta(r.queue,null,DriveTransport.FOLDER_MIME,s);check(r.folder==r.queue) { "DOT_TEXT_QUEUE_BINDING_MISMATCH" }
            val source=read(r.input,r.folder,s);check(source.first.contentEquals(r.inputBytes)) { "DOT_TEXT_FROZEN_INPUT_CHANGED" }
            val result=read(r.result,r.folder,s)
            if(result.first.contentEquals(r.pending))return@use Outcome("DOT_TEXT_PENDING",r.registration())
            val sha=DriveRelay.sha256(result.first);check(r.resultSha==null||r.resultSha==sha) { "DOT_TEXT_IMMUTABLE_RESULT_CONFLICT" }
            val proposal=ScheduledTextProofCodec().validate(source.first,result.first,r.job,r.input,DriveRelay.sha256(r.inputBytes),sha,r.created,clock())
            check(checkMeta(r.input,r.folder,DriveTransport.JSON_MIME,s).version==source.second && checkMeta(r.result,r.folder,DriveTransport.JSON_MIME,s).version==result.second) { "DOT_TEXT_FILE_CHANGED_DURING_READ" }
            session.commit(s.epoch,s.owner){journal.accept(r.job,sha,result.first)}
            Outcome("READ_ONLY_SCHEDULED_TEXT_PROPOSAL",r.copy(state="READ_ONLY_PROPOSAL_RECEIVED",resultSha=sha).registration(),proposal)
        }
    }
}

internal class DotTextExchangeJournal(context:Context,private val scope:DriveALocalScope):AutoCloseable {
    data class Record(val job:String,val queue:String,val folder:String,val input:String,val result:String,val descriptor:String,val inputBytes:ByteArray,val pending:ByteArray,val descriptorBytes:ByteArray,val created:Instant,val state:String,val resultSha:String?) {
        fun registration()=DotScheduledTextExchangePort.Registration(job,folder,input,result,descriptor,DriveRelay.sha256(inputBytes),DriveRelay.sha256(descriptorBytes),state)
    }
    private val protectedStore=ProtectedDatabase.open(AccountStorageContext(context,scope.owner),GeneralLocalStore.DATABASE,null)
    private val db=protectedStore.database()
    init {ProtectedDatabase.transaction(db){
        db.execSQL("CREATE TABLE IF NOT EXISTS scheduled_dot_text_jobs(scope TEXT NOT NULL,job TEXT NOT NULL,queue TEXT NOT NULL,folder TEXT NOT NULL,input_id TEXT NOT NULL,result_id TEXT NOT NULL,descriptor_id TEXT NOT NULL,input BLOB NOT NULL,pending BLOB NOT NULL,descriptor BLOB NOT NULL,created TEXT NOT NULL,state TEXT NOT NULL,PRIMARY KEY(scope,job),UNIQUE(scope,result_id))")
        db.execSQL("CREATE TABLE IF NOT EXISTS scheduled_dot_text_receipts(scope TEXT NOT NULL,job TEXT NOT NULL,digest TEXT NOT NULL,payload BLOB NOT NULL,PRIMARY KEY(scope,job))")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS scheduled_dot_text_job_immutable BEFORE UPDATE OF scope,job,queue,folder,input_id,result_id,descriptor_id,input,pending,descriptor,created ON scheduled_dot_text_jobs BEGIN SELECT RAISE(ABORT,'DOT_TEXT_JOB_IMMUTABLE'); END")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS scheduled_dot_text_job_no_delete BEFORE DELETE ON scheduled_dot_text_jobs BEGIN SELECT RAISE(ABORT,'DOT_TEXT_JOB_IMMUTABLE'); END")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS scheduled_dot_text_receipt_immutable BEFORE UPDATE ON scheduled_dot_text_receipts BEGIN SELECT RAISE(ABORT,'DOT_TEXT_RECEIPT_IMMUTABLE'); END")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS scheduled_dot_text_receipt_no_delete BEFORE DELETE ON scheduled_dot_text_receipts BEGIN SELECT RAISE(ABORT,'DOT_TEXT_RECEIPT_IMMUTABLE'); END")
    }}
    fun anyScopeContains(job:String)=db.rawQuery("SELECT 1 FROM scheduled_dot_text_jobs WHERE job=? LIMIT 1",arrayOf(job)).use{it.moveToFirst()}
    fun reserve(r:Record)=ProtectedDatabase.transaction(db){val v=ContentValues().apply{put("scope",scope.key);put("job",r.job);put("queue",r.queue);put("folder",r.folder);put("input_id",r.input);put("result_id",r.result);put("descriptor_id",r.descriptor);put("input",r.inputBytes);put("pending",r.pending);put("descriptor",r.descriptorBytes);put("created",r.created.toString());put("state",r.state)};db.insertOrThrow("scheduled_dot_text_jobs",null,v);Unit}
    fun load(job:String):Record?=db.rawQuery("SELECT queue,folder,input_id,result_id,descriptor_id,input,pending,descriptor,created,state FROM scheduled_dot_text_jobs WHERE scope=? AND job=?",arrayOf(scope.key,job)).use{c->
        if(!c.moveToFirst())return@use null
        val digest=db.rawQuery("SELECT digest,payload FROM scheduled_dot_text_receipts WHERE scope=? AND job=?",arrayOf(scope.key,job)).use{q->if(!q.moveToFirst())null else q.getString(0).also{check(it==DriveRelay.sha256(q.getBlob(1))) { "DOT_TEXT_STORE_REVIEW" }}}
        val r=Record(job,c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getBlob(5),c.getBlob(6),c.getBlob(7),Instant.parse(c.getString(8)),c.getString(9),digest)
        val d=StrictJson.`object`(StrictJson.parse(r.descriptorBytes));check(d["job_id"]==job&&d["folder_id"]==r.folder&&d["source_file_id"]==r.input&&d["result_file_id"]==r.result&&d["source_sha256"]==DriveRelay.sha256(r.inputBytes)&&d["pending_result_sha256"]==DriveRelay.sha256(r.pending)&&d["native_session_binding"]==scope.binding()&&d["created_at_utc"]==r.created.toString()) { "DOT_TEXT_STORE_REVIEW" };r
    }
    fun state(job:String,value:String)=ProtectedDatabase.transaction(db){check(load(job)!=null);db.execSQL("UPDATE scheduled_dot_text_jobs SET state=? WHERE scope=? AND job=?",arrayOf(value,scope.key,job))}
    fun accept(job:String,digest:String,bytes:ByteArray)=ProtectedDatabase.transaction(db){val r=checkNotNull(load(job));check(r.resultSha==null||r.resultSha==digest) { "DOT_TEXT_IMMUTABLE_RESULT_CONFLICT" };if(r.resultSha==null)db.insertOrThrow("scheduled_dot_text_receipts",null,ContentValues().apply{put("scope",scope.key);put("job",job);put("digest",digest);put("payload",bytes)});state(job,"READ_ONLY_PROPOSAL_RECEIVED")}
    override fun close()=protectedStore.close()
}
