package kr.callbrief

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kr.callbrief.context.*
import kr.callagent.drive.DriveRelay
import kr.co.callsummary.core.android.AndroidSqlPort
import org.json.JSONObject
import java.time.Instant

/** Production protected-SQL adapter for the candidate module. All source admission, relation
 * evidence, candidate CAS and explicit user-review writes share the existing sealed transaction. */
internal class IntegratedContextStore(private val context:Context,private val intake:UnclassifiedAudioStore,
    private val owner:String,private val current:()->Boolean,private val policy:()->JSONObject,
    private val now:()->Long=System::currentTimeMillis,private val fault:()->Unit={}):AutoCloseable {
    init {require(context is AccountStorageContext && context.ownerId==owner){"INTEGRATION_OWNER_CONTEXT_REQUIRED"}}
    private val workflow=RecordingWorkflowStore(context,now,policy,current)
    private val db=AndroidSqlPort(context,GeneralLocalStore.DATABASE,context.assets.open("call_core_schema.sql"))
    private fun hash(s:String)=DriveRelay.sha256(s.toByteArray(Charsets.UTF_8))
    init {db.requireProtectedPersistence();db.transaction {
        db.execute("CREATE TABLE IF NOT EXISTS integrated_context_state(owner TEXT PRIMARY KEY,revision INTEGER NOT NULL,payload TEXT NOT NULL)")
        db.execute("CREATE TABLE IF NOT EXISTS integrated_audio_sources(request_id TEXT PRIMARY KEY,recording_id TEXT NOT NULL,normalization_base INTEGER NOT NULL,event_json TEXT NOT NULL)")
        db.execute("CREATE TABLE IF NOT EXISTS integrated_relation_reviews(request_id TEXT PRIMARY KEY,receipt TEXT NOT NULL,snapshot_json TEXT NOT NULL,start_cp INTEGER NOT NULL,end_cp INTEGER NOT NULL,transcript_sha TEXT NOT NULL)")
        db.execute("CREATE TABLE IF NOT EXISTS integrated_applied_items(request_id TEXT NOT NULL,target TEXT NOT NULL,item_id TEXT NOT NULL,PRIMARY KEY(request_id,target))")
        db.execute("CREATE TABLE IF NOT EXISTS integrated_user_reviews(request_id TEXT PRIMARY KEY,recording_id TEXT NOT NULL,source_version INTEGER NOT NULL,audio_sha TEXT NOT NULL,native_request_id TEXT NOT NULL,job_id TEXT NOT NULL,result_revision INTEGER NOT NULL,result_digest TEXT NOT NULL,candidate_sha TEXT NOT NULL,summary_receipt TEXT NOT NULL,policy_sha TEXT NOT NULL,created_ms INTEGER NOT NULL)")
        for(t in listOf("integrated_relation_reviews","integrated_user_reviews","integrated_applied_items"))for(a in listOf("UPDATE","DELETE"))db.execute("CREATE TRIGGER IF NOT EXISTS ${t}_${a.lowercase()} BEFORE $a ON $t BEGIN SELECT RAISE(ABORT,'IMMUTABLE_HISTORY');END")
        for(t in listOf("integrated_relation_reviews","integrated_user_reviews"))db.execute("CREATE TRIGGER IF NOT EXISTS ${t}_no_replace BEFORE INSERT ON $t WHEN EXISTS(SELECT 1 FROM $t WHERE request_id=NEW.request_id) BEGIN SELECT RAISE(ABORT,'IMMUTABLE_HISTORY');END")
        db.execute("CREATE TRIGGER IF NOT EXISTS integrated_applied_no_replace BEFORE INSERT ON integrated_applied_items WHEN EXISTS(SELECT 1 FROM integrated_applied_items WHERE request_id=NEW.request_id AND target=NEW.target) BEGIN SELECT RAISE(ABORT,'IMMUTABLE_HISTORY');END")
        null
    }}
    private fun snapshot(id:String)=workflow.snapshot(id)
    private fun n(s:JSONObject,k:String)=s.optLong(k,0)
    private fun expect(id:String,expected:JSONObject):JSONObject {
        check(current()){"INTEGRATION_SCOPE_CHANGED"};val s=snapshot(id)
        for(k in listOf("source_version","user_revision","lock_revision","head_revision","visibility_revision","board_revision","link_revision","task_revision"))check(n(s,k)==n(expected,k)){"INTEGRATION_REVISION_CONFLICT"}
        check(s.getString("audio_sha256")==expected.getString("audio_sha256") && s.optInt("archived")==0){"INTEGRATION_SOURCE_CHANGED"};return s
    }
    private fun stored()=db.query("SELECT payload FROM integrated_context_state WHERE owner=?",owner).singleOrNull()?.let{ProtectedContextCodec.decode(it["payload"].toString())} ?: ContextState(owner)
    private fun repoView(state:ContextState):RepositoryView {
        check(current()){"INTEGRATION_SCOPE_CHANGED"}
        val rows=db.query("SELECT * FROM integrated_audio_sources ORDER BY request_id")
        val data=rows.filter{intake.available(it["request_id"].toString())}.associate{row->val id=row["request_id"].toString();val v=intake.view(id);check(v.getString("sourceId")==row["recording_id"]){"INTEGRATION_SOURCE_CHANGED"};id to (v to snapshot(v.getString("sourceId")))}
        val all=state.events.values.toList()+rows.map{ProtectedContextCodec.event(JSONObject(it["event_json"].toString()))}
        val fences=Fences(data.values.sumOf{n(it.second,"user_revision")},data.values.sumOf{n(it.second,"lock_revision")},data.values.sumOf{n(it.second,"task_revision")},data.values.maxOfOrNull{n(it.second,"board_revision")} ?: 0,
            data.values.sumOf{n(it.second,"link_revision")},data.values.sumOf{n(it.second,"visibility_revision")},data.values.sumOf{it.first.getJSONObject("receipt").getJSONObject("source").getLong("source_version")})
        val admitted=mutableSetOf<AdmittedSource>();val links=mutableListOf<ReviewedLink>();val items=mutableMapOf<ItemKey,CanonicalItem>()
        for(e in all.distinctBy{it.ref}) {
            val (v,s)=data[e.localBindingRef] ?: continue
            check(e.ref.key.source.owner==owner && e.ref.key.source.externalId==v.getString("sourceId") && e.ref.sha256==v.getString("transcriptSha")){"INTEGRATION_EVENT_BINDING_CHANGED"}
            admitted+=AdmittedSource(e.ref,e.localBindingRef,fences.leaseEpoch)
            val review=db.query("SELECT * FROM integrated_relation_reviews WHERE request_id=?",e.localBindingRef).singleOrNull() ?: continue
            val expected=JSONObject(review["snapshot_json"].toString())
            if(listOf("person_id","company_id","case_id","task_id","link_revision").any{s.opt(it)?.toString()!=expected.opt(it)?.toString()})continue
            check(review["transcript_sha"]==e.ref.sha256){"INTEGRATION_RELATION_EVIDENCE_CHANGED"}
            val evidence=EventAdapter.evidence(e.ref,e.body,(review["start_cp"] as Number).toInt(),(review["end_cp"] as Number).toInt())
            val scope=WorkScope(owner,s.getString("person_id"),s.getString("case_id"),s.optString("company_id").takeUnless{it=="null"||it.isBlank()})
            links+=ReviewedLink(e.ref,scope,fences.relation,evidence,evidence,review["receipt"].toString())
            for(row in db.query("SELECT a.target,i.* FROM integrated_applied_items a JOIN workflow_items i ON i.id=a.item_id WHERE a.request_id=?",e.localBindingRef)) {
                val manual=db.query("SELECT item_id FROM workflow_item_history WHERE item_id=?",row["id"]).isNotEmpty()
                items[ItemKey(scope,Kind.TASK,row["target"].toString())]=CanonicalItem((row["revision"] as Number).toLong(),mapOf(Field.TITLE to row["title"].toString(),Field.STATUS to row["status"].toString().uppercase()),manual,s.optInt("completed_lock")!=0||s.optInt("edited_lock")!=0)
            }
        }
        return RepositoryView(owner,fences,admitted,links.distinct(),items)
    }
    val port=object:ContextRepositoryPort {
        override fun <T> transaction(block:(ContextTransaction)->T):T=db.transaction {
            check(current()){"INTEGRATION_SCOPE_CHANGED"}
            val value=block(object:ContextTransaction {
                private var loaded:ContextSnapshot?=null
                override fun load():ContextSnapshot {val s=stored();return ContextSnapshot(s,repoView(s)).also{loaded=it}}
                override fun saveCandidates(expectedRevision:Long,state:ContextState) {
                    check(stored().revision==expectedRevision && state.owner==owner && repoView(state)==loaded?.view){"INTEGRATION_CANDIDATE_CAS"}
                    db.execute("INSERT INTO integrated_context_state VALUES(?,?,?) ON CONFLICT(owner) DO UPDATE SET revision=excluded.revision,payload=excluded.payload",owner,state.revision,ProtectedContextCodec.encode(state));fault()
                    check(repoView(state)==loaded?.view){"INTEGRATION_CANDIDATE_CAS"}
                }
            });check(current()){"INTEGRATION_SCOPE_CHANGED"};value
        }
    }
    fun normalize(request:String):JSONObject {
        val v=intake.view(request);val id=v.getString("sourceId");val s=snapshot(id)
        val linked=db.query("SELECT request_id FROM integrated_relation_reviews WHERE request_id=?",request).isNotEmpty()
        val body=v.getString("transcript");val tasks=v.getJSONObject("analysis").getJSONArray("tasks")
        val event=db.transaction {
        check(current() && intake.available(request)){"INTEGRATION_SOURCE_CHANGED"}
        val base=db.query("SELECT normalization_base FROM integrated_audio_sources WHERE request_id=?",request).singleOrNull()?.get("normalization_base") as? Number
        val normalizationBase=base?.toLong() ?: (db.query("SELECT coalesce(max(normalization_base),-1)+2 n FROM integrated_audio_sources WHERE recording_id=?",id).single()["n"] as Number).toLong()
        val event=EventAdapter.normalize(SourceId(owner,Channel.CALL_ANALYSIS,"android-local-stt",id),normalizationBase+if(linked)1 else 0,body,
            if(linked && !s.isNull("original_started_at_ms"))Instant.ofEpochMilli(s.getLong("original_started_at_ms")) else null,
            Instant.ofEpochMilli(v.getJSONObject("receipt").getLong("expiresMs")-1800000),"",request) {ref->
            (0 until tasks.length()).map{i->val t=tasks.getJSONObject(i);Claim("task-$i",Kind.TASK,"$request:task-$i",mapOf(Field.TITLE to t.getString("quote"),Field.STATUS to "NEEDS_CONFIRMATION"),EventAdapter.evidence(ref,body,t.getInt("start"),t.getInt("end")))}
        }
        db.execute("INSERT INTO integrated_audio_sources VALUES(?,?,?,?) ON CONFLICT(request_id) DO UPDATE SET event_json=excluded.event_json",request,id,normalizationBase,ProtectedContextCodec.event(event).toString());event
        }
        val result=ContextRepositoryAdapter(port).ingest(event);check(result.committed){"INTEGRATION_CANDIDATE_CONFLICT"};return candidate()
    }
    fun candidate():JSONObject {
        val adapter=ContextRepositoryAdapter(port);check(adapter.commit(adapter.prepareRefresh()).committed){"INTEGRATION_CANDIDATE_CONFLICT"}
        return port.transaction{tx->val s=tx.load();JSONObject(CandidateJson.encode(s.state,s.view.fences))}
    }
    fun confirmRelation(request:String,expected:JSONObject,start:Int,end:Int,confirmed:Boolean)=db.transaction {
        check(confirmed){"INTEGRATION_USER_RELATION_REVIEW_REQUIRED"};val v=intake.view(request);val s=expect(v.getString("sourceId"),expected)
        check(listOf("person_id","case_id","task_id","original_started_at_ms").all{!s.isNull(it)} && intake.status(request)=="BOUND"){"INTEGRATION_EXPLICIT_BINDING_REQUIRED"}
        val text=v.getString("transcript");check(start>=0&&end>start&&end<=text.codePointCount(0,text.length)){"INTEGRATION_QUOTE_REQUIRED"}
        val old=db.query("SELECT * FROM integrated_relation_reviews WHERE request_id=?",request).singleOrNull()
        if(old==null)db.execute("INSERT INTO integrated_relation_reviews VALUES(?,?,?,?,?,?)",request,hash("user-relation\n$request\n${s.getLong("link_revision")}\n$start\n$end"),s.toString(),start,end,v.getString("transcriptSha"))
        else check(old["transcript_sha"]==v.getString("transcriptSha") && old["start_cp"]==start.toLong() && old["end_cp"]==end.toLong()){"INTEGRATION_RELATION_REVIEW_IMMUTABLE"}
        null
    }
    fun approve(request:String,expected:JSONObject,candidateSha:String,summary:String,confirmed:Boolean):String=db.transaction {
        check(confirmed){"INTEGRATION_USER_CONTENT_REVIEW_REQUIRED"};val v=intake.view(request);val id=v.getString("sourceId");val s=expect(id,expected)
        val state=stored();val view=repoView(state);check(candidateDigest(JSONObject(CandidateJson.encode(state,view.fences)))==candidateSha){"INTEGRATION_CANDIDATE_REVIEW_CHANGED"}
        val old=db.query("SELECT * FROM integrated_user_reviews WHERE request_id=?",request).singleOrNull()
        if(old!=null){check(old["candidate_sha"]==candidateSha){"INTEGRATION_REVIEW_IMMUTABLE"};return@transaction old["summary_receipt"].toString()}
        check(s.optInt("completed_lock")==0 && s.optInt("edited_lock")==0 && s.optInt("task_manual_locks")==0 && s.optString("manual_status")!="done" && s.isNull("manual_text")){"INTEGRATION_MANUAL_STATE_PROTECTED"}
        check(state.projection.holds.none{it.source?.key?.source?.externalId==id || it.claim?.source?.key?.source?.externalId==id}){"INTEGRATION_CANDIDATE_HELD"}
        val job=db.query("SELECT * FROM automation_jobs WHERE recording_id=? AND source_version=? AND audio_sha=? AND result_revision>0 AND state IN ('APPLIED_SAFE_DERIVED','REVIEW_REQUIRED')",id,s.getLong("source_version"),s.getString("audio_sha256")).single()
        for((key,item) in state.projection.items.filterKeys{it.targetId.startsWith("$request:")}) {
            val title=checkNotNull(item.fields[Field.TITLE]?.value)
            check(item.fields[Field.STATUS]?.value=="NEEDS_CONFIRMATION"){"INTEGRATION_UNSUPPORTED_REVIEW_ACTION"}
            val existing=db.query("SELECT * FROM workflow_items WHERE recording_id=? AND title=?",id,title).singleOrNull()
            val itemId=if(existing==null)workflow.createItem(id,snapshot(id),"TODO",title,"","",quote=title,initialStatus="needs_confirmation")
                else {check(existing["status"]=="needs_confirmation" && db.query("SELECT item_id FROM workflow_item_history WHERE item_id=?",existing["id"]).isEmpty()){"INTEGRATION_MANUAL_ITEM_PROTECTED"};existing["id"].toString()}
            db.execute("INSERT INTO integrated_applied_items VALUES(?,?,?)",request,key.targetId,itemId)
        }
        val receipt=workflow.reviewOwnSummary(id,snapshot(id),summary)
        db.execute("INSERT INTO integrated_user_reviews VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",request,id,s.getLong("source_version"),s.getString("audio_sha256"),job["request_id"],job["id"],job["result_revision"],job["result_digest"],candidateSha,receipt,AndroidAutomationRuntime.policyHash(policy()),now())
        fault();check(current() && intake.available(request)){"INTEGRATION_SCOPE_OR_SOURCE_CHANGED"}
        val end=snapshot(id);check(listOf("source_version","user_revision","lock_revision","head_revision","visibility_revision","link_revision","task_revision").all{n(end,it)==n(s,it)} && end.getString("audio_sha256")==s.getString("audio_sha256")){"INTEGRATION_REVISION_CONFLICT"};receipt
    }
    /** One user selection, one protected commit across all selected sources. Board advances
     * caused by our own commands do not invalidate the next source in this same transaction. */
    fun approveSelection(selection:Map<String,Pair<JSONObject,String>>,candidateSha:String,confirmed:Boolean):List<String> =db.transaction {
        check(confirmed && selection.isNotEmpty()){"INTEGRATION_USER_CONTENT_REVIEW_REQUIRED"}
        val before=stored();val view=repoView(before)
        check(candidateDigest(JSONObject(CandidateJson.encode(before,view.fences)))==candidateSha){"INTEGRATION_CANDIDATE_REVIEW_CHANGED"}
        selection.forEach{(r,v)->expect(intake.view(r).getString("sourceId"),v.first)}
        selection.map{(r,v)->val fresh=candidate();approve(r,snapshot(intake.view(r).getString("sourceId")),candidateDigest(fresh),v.second,true)}
    }
    fun reviewCount()=(db.query("SELECT count(*) n FROM integrated_user_reviews").single()["n"] as Number).toInt()
    override fun close(){db.close();workflow.close()}
    companion object {fun candidateDigest(candidate:JSONObject)=DriveRelay.sha256(kr.callagent.drive.StrictJson.bytes(kr.callagent.drive.StrictJson.parse(candidate.toString().toByteArray())))}
}

/** A local native result is reviewed only through an exact, explicit USER_REVIEW_SUMMARY
 * receipt. Candidate flags or the automatic result decision never create this receipt. */
internal object IntegratedReviewReceipt {
    fun matches(db:SQLiteDatabase,id:String,summaryReceipt:String,request:String?,policy:JSONObject):Boolean {
        if(request==null || !db.rawQuery("SELECT 1 FROM sqlite_master WHERE name='integrated_user_reviews'",null).use{it.moveToFirst()})return false
        return db.rawQuery("""SELECT 1 FROM integrated_user_reviews u JOIN workflow_user_receipts w ON w.event_id=u.summary_receipt AND w.operation='USER_REVIEW_SUMMARY' AND w.recording_id=u.recording_id
            JOIN automation_jobs j ON j.id=u.job_id AND j.request_id=u.native_request_id AND j.recording_id=u.recording_id AND j.source_version=u.source_version AND j.audio_sha=u.audio_sha AND j.result_revision=u.result_revision AND j.result_digest=u.result_digest
            JOIN automation_results a ON a.job_id=j.id AND a.revision=u.result_revision AND a.digest=u.result_digest
            WHERE u.recording_id=? AND u.summary_receipt=? AND u.native_request_id=? AND u.policy_sha=? AND j.state IN ('APPLIED_SAFE_DERIVED','REVIEW_REQUIRED')""".trimIndent(),arrayOf(id,summaryReceipt,request,AndroidAutomationRuntime.policyHash(policy))).use{it.moveToFirst()}
    }
}
