package kr.callbrief

import android.content.Context
import android.os.Looper
import kr.callagent.drive.StrictJson
import kr.callagent.drive.DriveRelay
import kr.co.callsummary.core.android.ProtectedDatabase
import java.util.UUID

/** Bootstrap for an already approved connection. No UI grant, credential provisioning,
 * remote activation or model call. Secrets stay in the existing AEAD protected owner DB.
 * Only the approved setup owner may call bind; the application never calls it on startup. */
internal object AutomationConnectionBindings {
    fun base(context:Context):Context=if(context is AccountStorageContext)context.baseContext else context
    private fun scope(session:GoogleConnectionSession)=checkNotNull(DriveALocalScope.current(session)){"AUTOMATION_CURRENT_NATIVE_GRANT_REQUIRED"}
    private fun <T> database(context:Context,session:GoogleConnectionSession,allowRetainedScope:Boolean=false,action:(android.database.sqlite.SQLiteDatabase,DriveALocalScope)->T):T? {
        check(Looper.myLooper()!=Looper.getMainLooper()){"LOCAL_IO_THREAD_REQUIRED"}
        val current=DriveALocalScope.current(session) ?: if(allowRetainedScope)session.previouslyVerifiedScope() else null
        if(current==null)return null
        val account=AccountStorageContext(base(context),current.owner)
        if(!ProtectedDatabase.file(account,GeneralLocalStore.DATABASE).exists())return null
        return ProtectedDatabase.open(account,GeneralLocalStore.DATABASE,null).use{action(it.database(),current)}
    }
    private fun read(context:Context,session:GoogleConnectionSession,kind:String,allowRetainedScope:Boolean=false):Map<String,Any?>?=database(context,session,allowRetainedScope){db,s->
        if(!db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='automation_connection_bindings'",null).use{it.moveToFirst()})return@database null
        db.rawQuery("SELECT payload,digest,revoked FROM automation_connection_bindings WHERE scope=? AND kind=?",arrayOf(s.key,kind)).use{c->
            if(!c.moveToFirst() || c.getInt(2)!=0)return@use null
            val bytes=c.getBlob(0);check(bytes.size<=16384 && DriveRelay.sha256(bytes)==c.getString(1)){"AUTOMATION_CONNECTION_BINDING_REVIEW"}
            StrictJson.`object`(StrictJson.parse(bytes))
        }
    }
    private fun bind(context:Context,session:GoogleConnectionSession,kind:String,payload:ByteArray,
        admission:((android.database.sqlite.SQLiteDatabase,DriveALocalScope)->Unit)?=null) {
        check(payload.size<=16384){"AUTOMATION_CONNECTION_SIZE"}
        check(database(context,session){db,s->ProtectedDatabase.transaction(db){
            admission?.invoke(db,s)
            db.execSQL("CREATE TABLE IF NOT EXISTS automation_connection_bindings(scope TEXT NOT NULL,kind TEXT NOT NULL,payload BLOB NOT NULL,digest TEXT NOT NULL,revoked INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(scope,kind))")
            db.execSQL("CREATE TRIGGER IF NOT EXISTS automation_connection_binding_immutable BEFORE UPDATE OF scope,kind,payload,digest ON automation_connection_bindings BEGIN SELECT RAISE(ABORT,'AUTOMATION_CONNECTION_IMMUTABLE'); END")
            db.execSQL("CREATE TRIGGER IF NOT EXISTS automation_connection_no_delete BEFORE DELETE ON automation_connection_bindings BEGIN SELECT RAISE(ABORT,'AUTOMATION_CONNECTION_IMMUTABLE'); END")
            val old=db.rawQuery("SELECT payload,revoked FROM automation_connection_bindings WHERE scope=? AND kind=?",arrayOf(s.key,kind)).use{c->if(c.moveToFirst())c.getBlob(0) to c.getInt(1) else null}
            if(old!=null)check(old.first.contentEquals(payload) && old.second==0){"AUTOMATION_CONNECTION_CHANGED_REVIEW_REQUIRED"}
            else db.execSQL("INSERT INTO automation_connection_bindings(scope,kind,payload,digest) VALUES(?,?,?,?)",arrayOf(s.key,kind,payload,DriveRelay.sha256(payload)))
            true
        }}==true){"WAITING_PROTECTED_STORE"}
    }
    fun bindApprovedProcessing(context:Context,session:GoogleConnectionSession,configuration:AutomationApiConfiguration,
        settings:(Context)->SecureSettings={SecureSettings(it)},now:()->Long=System::currentTimeMillis) {
        configuration.validate(scope(session))
        bind(context,session,"PROCESSING_V1",StrictJson.bytes(mapOf("enabled" to configuration.enabled,"origin" to configuration.origin,"owner" to configuration.owner,"account" to configuration.account,"epoch" to configuration.epoch,"zone" to configuration.reportZone,"credential" to configuration.bearerCredential))){_,s->
            ApprovedProcessingIntakeJournal.checkBindingAdmission(context,s,configuration,settings,now())
        }
    }
    fun processing(context:Context,session:GoogleConnectionSession,settings:(Context)->SecureSettings={SecureSettings(it)},now:()->Long=System::currentTimeMillis):AutomationApiConfiguration? {
        val x=read(context,session,"PROCESSING_V1") ?: return null
        check(x.keys==setOf("enabled","origin","owner","account","epoch","zone","credential") && x["enabled"]==true && x["epoch"] is Long){"AUTOMATION_CONNECTION_BINDING_REVIEW"}
        val s=scope(session)
        return AutomationApiConfiguration(true,x["origin"] as String,x["owner"] as String,x["account"] as String,x["epoch"] as Long,x["zone"] as String,x["credential"] as String).also{
            it.validate(s);ApprovedProcessingIntakeJournal.checkBindingAdmission(context,s,it,settings,now())
        }
    }
    internal fun validateProof(p:ScheduledDotProofPlan) {
        check(p.enabled && UUID.fromString(p.proofRequestId).toString()==p.proofRequestId && p.queueFolder.matches(Regex("[A-Za-z0-9_-]{1,256}"))){"SCHEDULED_DOT_PLAN_REQUIRED"}
        if(p.inputMode==ScheduledDotInputMode.PREPARED_LOCAL_STT_SYNTHETIC)check(p.requestSha.matches(Regex("[0-9a-f]{64}")) && p.evidenceSha.matches(Regex("[0-9a-f]{64}"))){"LOCAL_STT_PRODUCER_PINS_REQUIRED"}
        else check(p.requestSha.isBlank() && p.evidenceSha.isBlank()){"SCHEDULED_DOT_FIXED_TEXT_PINS_REJECTED"}
    }
    fun bindApprovedScheduled(context:Context,session:GoogleConnectionSession,p:ScheduledDotProofPlan) {
        scope(session);validateProof(p)
        bind(context,session,"SCHEDULED_SYNTHETIC_TEXT_V1",StrictJson.bytes(mapOf("job" to p.proofRequestId,"queue" to p.queueFolder,"mode" to p.inputMode.name,"requestSha" to p.requestSha,"evidenceSha" to p.evidenceSha)))
    }
    fun scheduled(context:Context,session:GoogleConnectionSession,allowRetainedScope:Boolean=false):ScheduledDotProofPlan? {
        val x=read(context,session,"SCHEDULED_SYNTHETIC_TEXT_V1",allowRetainedScope) ?: return null
        check(x.keys==setOf("job","queue","mode","requestSha","evidenceSha")){"AUTOMATION_CONNECTION_BINDING_REVIEW"}
        return ScheduledDotProofPlan(true,x["job"] as String,x["queue"] as String,ScheduledDotInputMode.valueOf(x["mode"] as String),x["requestSha"] as String,x["evidenceSha"] as String).also{validateProof(it)}
    }
    /** Revocation is durable; rebinding cannot silently re-enable the same native scope. */
    fun withdraw(context:Context,session:GoogleConnectionSession) {
        check(database(context,session){db,s->
            ProtectedDatabase.requireSealedPersistence(db)
            check(!db.inTransaction()){"AUTOMATION_WITHDRAW_TOP_LEVEL_REQUIRED"}
            ProtectedDatabase.transaction(db){
                val bindings=db.rawQuery("SELECT name FROM sqlite_master WHERE name='automation_connection_bindings'",null).use{it.moveToFirst()}
                if(bindings)db.execSQL("UPDATE automation_connection_bindings SET revoked=1 WHERE scope=?",arrayOf(s.key))
                ApprovedProcessingIntakeJournal.withdraw(db,s)
                true
            }
        }==true){"WAITING_PROTECTED_STORE"}
    }
}
