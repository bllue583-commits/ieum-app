package kr.callbrief

import android.content.Context
import kr.co.callsummary.core.android.ProtectedDatabase
import kr.callagent.drive.StrictJson

/** One explicitly approved local trial, one attempted POST per stage across process restarts.
 * HTTP failure, rejected output and UNKNOWN all consume the slot; no automatic paid fallback. */
internal class SiwcTrialBudget(context:Context,private val approval:String="PERSONAL_PUBLIC_20261026"):AutoCloseable {
    private val sealed=ProtectedDatabase.open(context,"siwc-personal-trial-v1.db",SCHEMA)
    private val db=sealed.database()
    fun bind(scope:String,manifest:String,snapshot:String)=sealed.transaction {
        val expected=AstraAudioContract.sha(StrictJson.bytes(listOf(scope,manifest,snapshot)))
        val prior=ProtectedDatabase.read(db){db.rawQuery("SELECT digest FROM trial_binding WHERE approval=?",arrayOf(approval)).use{if(it.moveToFirst())it.getString(0) else null}}
        check(prior==null || prior==expected){"SIWC_TRIAL_BINDING_CHANGED"}
        db.execSQL("INSERT OR IGNORE INTO trial_binding VALUES(?,?)",arrayOf(approval,expected));null
    }
    fun claim(request:SiwcAstraProtocol.Invocation)=sealed.transaction {
        check(ProtectedDatabase.read(db){db.rawQuery("SELECT digest FROM trial_binding WHERE approval=?",arrayOf(approval)).use{it.moveToFirst()}}){"SIWC_TRIAL_BINDING_REQUIRED"}
        val exists=ProtectedDatabase.read(db){db.rawQuery("SELECT request_key FROM trial_slots WHERE approval=? AND stage=?",arrayOf(approval,request.stage.name)).use{it.moveToFirst()}}
        if(exists)throw SiwcAstraProtocol.Failure("SIWC_TRIAL_STAGE_ALREADY_ATTEMPTED")
        db.execSQL("INSERT INTO trial_slots VALUES(?,?,?,'ATTEMPTING','','')",arrayOf(approval,request.stage.name,request.key()));null
    }
    fun finish(request:SiwcAstraProtocol.Invocation,state:String,completion:SiwcAstraProtocol.Completion?=null)=sealed.transaction {
        check(state in setOf("COMPLETION_OBSERVED","FAILED_OR_UNKNOWN"))
        db.execSQL("UPDATE trial_slots SET state=?,response_id=?,stream_sha=? WHERE approval=? AND stage=? AND request_key=?",arrayOf(state,completion?.responseId.orEmpty(),completion?.rawSha256.orEmpty(),approval,request.stage.name,request.key()));null
    }
    fun event(code:String) {require(code.matches(Regex("[A-Z0-9_]{1,100}")));sealed.transaction {db.execSQL("INSERT INTO trial_events(approval,code) VALUES(?,?)",arrayOf(approval,code));null}}
    fun snapshot():Map<String,Any?> =ProtectedDatabase.read(db){
        val slots=db.rawQuery("SELECT stage,request_key,state,response_id,stream_sha FROM trial_slots WHERE approval=? ORDER BY stage",arrayOf(approval)).use{c->buildList<Map<String,Any?>>{while(c.moveToNext())add(mapOf("stage" to c.getString(0),"key" to c.getString(1),"state" to c.getString(2),"responseId" to c.getString(3),"streamSha256" to c.getString(4)))}}
        val events=db.rawQuery("SELECT code FROM trial_events WHERE approval=? ORDER BY id",arrayOf(approval)).use{c->buildList{while(c.moveToNext())add(c.getString(0))}}
        mapOf("approval" to approval,"slots" to slots,"httpEvents" to events)
    }
    fun port(delegate:SiwcInferencePort)=object:SiwcInferencePort {
        override fun models(scope:String)=delegate.models(scope)
        override fun invoke(request:SiwcAstraProtocol.Invocation,stopped:()->Boolean):SiwcAstraProtocol.Completion {
            claim(request)
            return try{delegate.invoke(request,stopped).also{finish(request,"COMPLETION_OBSERVED",it)}}catch(e:Exception){finish(request,"FAILED_OR_UNKNOWN");throw e}
        }
    }
    override fun close()=sealed.close()
    companion object {private const val SCHEMA="""CREATE TABLE trial_binding(approval TEXT PRIMARY KEY,digest TEXT NOT NULL)
-- statement-break
CREATE TABLE trial_slots(approval TEXT NOT NULL,stage TEXT NOT NULL,request_key TEXT NOT NULL,state TEXT NOT NULL,response_id TEXT NOT NULL,stream_sha TEXT NOT NULL,PRIMARY KEY(approval,stage))
-- statement-break
CREATE TABLE trial_events(id INTEGER PRIMARY KEY AUTOINCREMENT,approval TEXT NOT NULL,code TEXT NOT NULL)"""}
}
