package kr.callbrief

import kr.callagent.drive.StrictJson

/** Reads the existing checked local STT journal, not inbound model JSON.
 * This is a text transfer candidate. Creating it does not authorize SIWC transfer or prove Astra ran. */
internal object SiwcLocalTranscript {
    fun sourcePack(store:UnclassifiedAudioStore,request:String):Map<String,Any?> {
        val v=store.view(request)
        check(v.getString("state") in setOf("RESULT_READY","REVIEW_REQUIRED","BOUND")) { "SIWC_TRANSCRIPT_NOT_READY" }
        val receipt=v.getJSONObject("receipt");val observed=v.getJSONObject("observation")
        check(observed.getString("executionKind") in setOf("CURRENT_LOCAL_PROCESS","TRUSTED_LOCAL_REPLAY")) { "SIWC_REAL_LOCAL_TRANSCRIPT_REQUIRED" }
        val text=v.getString("transcript");check(AstraAudioContract.sha(text.toByteArray(Charsets.UTF_8))==v.getString("transcriptSha")) { "SIWC_TRANSCRIPT_HASH" }
        val segments=StrictJson.parse(v.getJSONArray("segments").toString().toByteArray(Charsets.UTF_8))
        return mapOf("sourceId" to v.getString("sourceId"),"sourceVersion" to receipt.getJSONObject("source").getLong("source_version"),
            "audioSha256" to v.getString("audioSha"),"transcript" to text,"transcriptSha256" to v.getString("transcriptSha"),"segments" to segments,
            "localExecution" to mapOf("kind" to observed.getString("executionKind"),"runtimeSha256" to observed.getString("runtimeSha"),
                "modelSha256" to observed.getString("modelSha"),"rawOutputSha256" to observed.getString("rawOutputSha")))
    }
}
