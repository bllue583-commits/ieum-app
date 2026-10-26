package kr.callbrief

import android.content.Context
import android.os.Looper
import kr.callagent.drive.StrictJson
import kr.co.callsummary.core.android.AndroidSqlPort
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.format.DateTimeFormatterBuilder
import java.util.UUID

/** Protected, durable LOCAL contract double. No socket, HTTP, auth header, OAuth,
 * cookie, user text/audio, Sites write, model execution or canonical application. */
internal class SyntheticTextLocalFixtureChannel(private val context:Context,private val clock:()->Long=System::currentTimeMillis,
    private val scope:SyntheticTextConnectionScope=LOCAL_SCOPE):SyntheticTextMcpChannel {
    companion object {
        const val DATABASE="ieum-sg-local-fixture-v1.db"
        val LOCAL_SCOPE=SyntheticTextConnectionScope("PUBLIC_LOCAL_SG_FIXTURE","PUBLIC_LOCAL_SG_SESSION",1,SyntheticTextOrigin.LOCAL_CONTRACT_FIXTURE)
        private const val SCHEMA="CREATE TABLE IF NOT EXISTS fixture_generations(binding_key TEXT NOT NULL,proof_id TEXT NOT NULL,generation_id TEXT NOT NULL,revision INTEGER NOT NULL,body TEXT NOT NULL,PRIMARY KEY(binding_key,proof_id),UNIQUE(binding_key,generation_id));"
    }
    override val origin=SyntheticTextContract.LOCAL_ORIGIN
    override fun binding()=scope
    private fun error(id:Long,reason:String):ByteArray=JSONObject().put("jsonrpc","2.0").put("id",id).put("result",JSONObject().put("resultType","complete").put("isError",true).put("structuredContent",JSONObject().put("error",JSONObject().put("code",-32003).put("reason",reason)))).toString().toByteArray()
    override suspend fun exchange(request:ByteArray):ByteArray {
        check(BuildConfig.SYNTHETIC_TOOLS_ENABLED && scope.origin==SyntheticTextOrigin.LOCAL_CONTRACT_FIXTURE);check(Looper.myLooper()!=Looper.getMainLooper())
        val rpc=SyntheticTextContract.obj(StrictJson.parse(request));val id=SyntheticTextContract.integer(rpc,"id")
        return try {
            require(rpc["jsonrpc"]=="2.0"&&rpc["method"]=="tools/call");val params=SyntheticTextContract.obj(rpc["params"]);val tool=params["name"] as String;val args=SyntheticTextContract.obj(params["arguments"])
            val value=AndroidSqlPort(context,DATABASE,ByteArrayInputStream(SCHEMA.toByteArray())).use { port ->port.requireProtectedPersistence();port.transaction {
                fun row(where:String,value:Any?)=port.query("SELECT * FROM fixture_generations WHERE binding_key=? AND $where=?",scope.key,value).singleOrNull()
                fun checked(r:Map<String,Any?>):JSONObject=JSONObject(r["body"].toString()).also { if(clock()>=it.getLong("expires_at"))throw SyntheticTextFailure("job_expired") }
                if(tool=="start_demo_generation") {
                    SyntheticTextContract.exact(args,setOf("sample_id","proof_request_id"));require(args["sample_id"]==SyntheticTextContract.SAMPLE);val proof=args["proof_request_id"] as String;SyntheticTextContract.uuid(proof)
                    var old=row("proof_id",proof);val duplicate=old!=null
                    if(old==null) {
                        val now=clock();val gen=UUID.randomUUID().toString();val body=JSONObject().put("job_id",SyntheticTextContract.JOB).put("queue_id",SyntheticTextContract.QUEUE).put("sample_id",SyntheticTextContract.SAMPLE).put("generation_id",gen).put("proof_request_id",proof).put("created_at",DateTimeFormatterBuilder().appendInstant(3).toFormatter().format(Instant.ofEpochMilli(now))).put("expires_at",now+SyntheticTextContract.TTL).put("status","queued").put("result",JSONObject.NULL).put("result_id",JSONObject.NULL).put("result_hash",JSONObject.NULL).put("demo",true)
                        port.execute("INSERT INTO fixture_generations VALUES(?,?,?,0,?)",scope.key,proof,gen,body.toString());old=row("proof_id",proof)
                    }
                    val body=checked(requireNotNull(old));SyntheticTextContract.obj(StrictJson.parse(body.toString().toByteArray())).filterKeys { it in setOf("job_id","generation_id","proof_request_id","created_at","expires_at","status","demo") }+mapOf("revision" to old!!["revision"],"duplicate" to duplicate)
                } else {
                    require(args["job_id"]==SyntheticTextContract.JOB);val gen=args["generation_id"] as String;SyntheticTextContract.uuid(gen);val old=row("generation_id",gen) ?: throw SyntheticTextFailure("not_found");val body=checked(old)
                    if(tool=="save_demo_result") {
                        require(args["expected_revision"]==0L);val rid=args["result_id"] as String;GenerationResultId.requireFor(gen,rid)
                        val result=SyntheticTextContract.validateInput(SyntheticTextContract.obj(args["result"]));val hash=SyntheticTextContract.fingerprint(result)
                        val duplicate=(old["revision"] as Number).toLong()==1L
                        if(duplicate) { if(body.getString("result_id")!=rid||body.getString("result_hash")!=hash)throw SyntheticTextFailure("result_conflict") }
                        else {
                            if(clock()>=body.getLong("expires_at"))throw SyntheticTextFailure("job_expired")
                            body.put("status","result_ready").put("result",JSONObject(result)).put("result_id",rid).put("result_hash",hash)
                            port.execute("UPDATE fixture_generations SET body=?,revision=1 WHERE binding_key=? AND generation_id=? AND revision=0",body.toString(),scope.key,gen)
                        }
                        checked(requireNotNull(row("generation_id",gen)));mapOf("job_id" to SyntheticTextContract.JOB,"generation_id" to gen,"revision" to 1L,"status" to "result_ready","duplicate" to duplicate,"demo" to true)
                    } else {
                        SyntheticTextContract.exact(args,setOf("job_id","generation_id"));val full=SyntheticTextContract.obj(StrictJson.parse(body.toString().toByteArray()))+mapOf("revision" to old["revision"])
                        when(tool) { "get_demo_job"->full+mapOf("fixture" to SyntheticTextContract.fixture,"existing_checklist" to emptyList<Any>(),"note" to "LOCAL synthetic fixed-text contract double; no actual Site or model.");"get_demo_result"->full.filterKeys { it in setOf("job_id","generation_id","revision","status","expires_at","result","result_id","result_hash","demo") };else->throw SyntheticTextFailure("unknown_tool") }
                    }
                }
            } }
            StrictJson.bytes(mapOf("jsonrpc" to "2.0","id" to id,"result" to mapOf("resultType" to "complete","isError" to false,"content" to listOf(mapOf("type" to "text","text" to StrictJson.stringify(value))),"structuredContent" to value)))
        } catch(e:SyntheticTextFailure) { error(id,e.reason) }
        catch(_:Exception) { error(id,"storage_unavailable") }
    }
}
