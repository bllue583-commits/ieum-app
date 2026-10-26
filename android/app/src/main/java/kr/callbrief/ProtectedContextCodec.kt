package kr.callbrief

import kr.callbrief.context.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Exact local ledger codec, only stored inside the existing sealed DB. No Java deserialization,
 * native wire change, execution/approval flags or endpoint export. Candidate JSON stays a proposal. */
internal object ProtectedContextCodec {
    private fun array(values:Iterable<*>)=JSONArray().apply{values.forEach{put(it)}}
    private fun objects(a:JSONArray)=(0 until a.length()).map{a.getJSONObject(it)}
    private fun nullable(o:JSONObject,k:String)=if(o.isNull(k))null else o.getString(k)
    private fun ref(r:SourceRef)=JSONObject().put("owner",r.key.source.owner).put("channel",r.key.source.channel.name).put("namespace",r.key.source.namespace).put("externalId",r.key.source.externalId).put("sourceVersion",r.key.version).put("sha256",r.sha256)
    private fun ref(o:JSONObject)=SourceRef(VersionKey(SourceId(o.getString("owner"),Channel.valueOf(o.getString("channel")),o.getString("namespace"),o.getString("externalId")),o.getLong("sourceVersion")),o.getString("sha256"))
    private fun claim(r:ClaimRef)=JSONObject().put("source",ref(r.source)).put("claimId",r.claimId)
    private fun claim(o:JSONObject)=ClaimRef(ref(o.getJSONObject("source")),o.getString("claimId"))
    private fun evidence(e:Evidence)=JSONObject().put("source",ref(e.source)).put("start",e.start).put("end",e.end).put("quote",e.quote)
    private fun evidence(o:JSONObject)=Evidence(ref(o.getJSONObject("source")),o.getInt("start"),o.getInt("end"),o.getString("quote"))
    fun event(e:NormalizedEvent)=JSONObject().put("ref",ref(e.ref)).put("body",e.body).put("occurredAt",e.occurredAt?.toString() ?: JSONObject.NULL).put("observedAt",e.observedAt.toString()).put("senderRaw",e.senderRaw).put("localBindingRef",e.localBindingRef).put("operation",e.operation.name)
        .put("claims",array(e.claims.map{c->JSONObject().put("id",c.id).put("kind",c.kind.name).put("targetId",c.targetId).put("values",JSONObject().apply{c.values.forEach{(k,v)->put(k.name,v ?: JSONObject.NULL)}}).put("evidence",evidence(c.evidence)).put("certainty",c.certainty.name).put("operation",c.operation.name).put("supersedes",c.supersedes?.let(::claim) ?: JSONObject.NULL).put("origin",c.origin?.let(::claim) ?: JSONObject.NULL)}))
    fun event(o:JSONObject):NormalizedEvent=NormalizedEvent(ref(o.getJSONObject("ref")),o.getString("body"),nullable(o,"occurredAt")?.let(Instant::parse),Instant.parse(o.getString("observedAt")),o.getString("senderRaw"),o.getString("localBindingRef"),Operation.valueOf(o.getString("operation")),objects(o.getJSONArray("claims")).map{c->
        val v=c.getJSONObject("values");Claim(c.getString("id"),Kind.valueOf(c.getString("kind")),c.getString("targetId"),v.keys().asSequence().associate{Field.valueOf(it) to nullable(v,it)},evidence(c.getJSONObject("evidence")),Certainty.valueOf(c.getString("certainty")),ClaimOperation.valueOf(c.getString("operation")),c.optJSONObject("supersedes")?.let(::claim),c.optJSONObject("origin")?.let(::claim))})
    private fun key(o:JSONObject):ItemKey=ItemKey(WorkScope(o.getString("owner"),o.getString("personId"),o.getString("workId"),nullable(o,"companyId")),Kind.valueOf(o.getString("kind")),o.getString("targetId"))
    fun encode(s:ContextState):String=JSONObject().put("owner",s.owner).put("revision",s.revision).put("events",array(s.events.values.map(::event)))
        .put("collisions",array(s.collisions.values.map{array(it.map(::event))}))
        .put("receipts",array(s.receipts.values.map{r->JSONObject().put("id",r.id).put("source",ref(r.source)).put("status",r.status.name).put("reason",r.reason?.name ?: JSONObject.NULL)}))
        .put("projection",JSONObject(CandidateJson.encode(s,Fences(0,0,0,0,0,0,0)))).toString()
    fun decode(text:String):ContextState {
        val o=JSONObject(text);val events=objects(o.getJSONArray("events")).map(::event);val p=o.getJSONObject("projection")
        val items=objects(p.getJSONArray("items")).associate{i->val fields=i.getJSONObject("fields");key(i.getJSONObject("key")) to CandidateItem(fields.keys().asSequence().associate{k->val f=fields.getJSONObject(k);Field.valueOf(k) to FactValue(nullable(f,"value"),objects(f.getJSONArray("provenance")).map(::claim).toSet(),objects(f.getJSONArray("origins")).map(::claim).toSet())},if(i.isNull("expectedItemRevision"))null else i.getLong("expectedItemRevision"))}
        val holds=objects(p.getJSONArray("holds")).map{h->Hold(HoldReason.valueOf(h.getString("reason")),h.optJSONObject("source")?.let(::ref),h.optJSONObject("claim")?.let(::claim),h.optJSONObject("item")?.let(::key),nullable(h,"field")?.let(Field::valueOf))}.toSet()
        val collisions=o.getJSONArray("collisions").let{a->(0 until a.length()).associate{i->val e=objects(a.getJSONArray(i)).map(::event).toSet();e.first().ref.key to e}}
        val receipts=objects(o.getJSONArray("receipts")).map{r->IngestReceipt(r.getString("id"),ref(r.getJSONObject("source")),ReceiptStatus.valueOf(r.getString("status")),nullable(r,"reason")?.let(HoldReason::valueOf))}.associateBy{it.source.key}
        return ContextState(o.getString("owner"),o.getLong("revision"),events.associateBy{it.ref.key},collisions,receipts,CandidateProjection(items,holds,objects(p.getJSONArray("activeSources")).map(::ref).toSet()))
    }
}
