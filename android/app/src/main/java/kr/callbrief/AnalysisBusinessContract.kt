package kr.callbrief

import kr.callagent.drive.WireV0Codec
import org.json.JSONArray
import org.json.JSONObject

/** Consumes the existing, synthetic v0 wire. Structure/quotes are not meaning attestation.
 * No sentence rules, identity inference, native CLAIM fabrication or new worker wire. */
internal object AnalysisBusinessContract {
    fun inspect(payload:ByteArray,request:ByteArray):JSONObject {
        check(WireV0Codec.candidateStructureValid(payload,request)) { "ANALYSIS_PROFILE_REVIEW_ONLY" }
        val result=JSONObject(String(payload,Charsets.UTF_8));val high=result.getJSONObject("high")
        val packet=JSONObject(high.getString("packetJson"));val candidate=JSONObject(high.getString("candidateJson"))
        check(result.getString("state")=="PROPOSAL" && result.getInt("schemaVersion")==0) { "ANALYSIS_PROFILE_REVIEW_ONLY" }
        val source=packet.getJSONArray("sources").getJSONObject(0)
        val raw=source.getString("raw_text");val ranges=source.getJSONArray("uncertain_ranges")
        val claims=JSONArray()
        val input=candidate.getJSONArray("claims")
        for(i in 0 until input.length()) {
            val c=input.getJSONObject(i);val category=c.getString("category")
            val kind=when(category) { "document"->"DOCUMENT";"request"->"TODO";"status"->"PROGRESS";else->"REVIEW_ONLY" }
            // Offsets stay code points in the receipt. Conversion is for Android text selection only.
            val evidence=JSONArray()
            val quotes=c.getJSONArray("evidence")
            for(j in 0 until quotes.length()) {
                val q=JSONObject(quotes.getJSONObject(j).toString())
                q.put("utf16_start",raw.offsetByCodePoints(0,q.getInt("start")))
                q.put("utf16_end",raw.offsetByCodePoints(0,q.getInt("end")));evidence.put(q)
            }
            val overlaps=(0 until evidence.length()).any { q ->(0 until ranges.length()).any { r ->
                evidence.getJSONObject(q).getInt("start")<ranges.getJSONObject(r).getInt("end") && evidence.getJSONObject(q).getInt("end")>ranges.getJSONObject(r).getInt("start")
            } }
            claims.put(JSONObject(c.toString()).put("suggested_kind",kind).put("evidence",evidence)
                .put("selectable",kind!="REVIEW_ONLY" && c.getString("certainty")=="explicit" && !overlaps)
                .put("due_display",c.optString("due_expression").takeUnless { it=="null" }.orEmpty()))
        }
        return JSONObject().put("summary",candidate.getString("summary")).put("claims",claims)
            .put("raw_text",raw).put("packet_json",high.getString("packetJson"))
            .put("candidate_json",high.getString("candidateJson")).put("source_pack_sha",candidate.getString("source_pack_sha256"))
            .put("canonical_write_allowed",false).put("execution_attested",false)
    }
}
