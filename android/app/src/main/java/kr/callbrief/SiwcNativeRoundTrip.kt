package kr.callbrief

import kr.callagent.drive.StrictJson

/** The caller must approve each exact invocation key in its current SIWC session grant.
 * Preparing the second request never authorizes or sends it. Unknown stages are not retried. */
internal object SiwcNativeRoundTrip {
    const val ANALYSIS_POLICY="Return JSON only with exactly schema_version=1, job_id, source_pack_sha256, base_revision, summary, claims. Copy job_id/base_revision/source_pack_sha256 from sourcePack. summary max3000 code points. claims max50; each exactly claim_id,category,subject_id,value,certainty,evidence,status,assignee,due,due_expression,replaces. category=context/date/document/entity/participant/quantity/request/role/status. certainty=explicit/uncertain. status=null/done/in_progress/needs_confirmation/open/waiting. subject_id=null unless a confirmed ID is present. evidence is 1..10 exact source_id,start,end,quote; source_id=source.sourceId+'_v'+source.sourceVersion; offsets Unicode code points, end exclusive, from exact transcript. due=null or ISO date with quoted due_expression. Preserve uncertainty, correction, cancellation and required documents; do not invent names, times or approval. No automatic canonical changes."
    const val CONTEXT_POLICY="Return JSON only with exactly schema='ieum.siwc-context.v1',analysisJobId,analysisOutputSha256,approvedSnapshotSha256,actions. Copy the three binding fields from input. Include each analysis claim exactly once in actions, each exactly claimId,kind,targetId,targetRevision,operation,uncertain,conflict. kind=TASK/DOCUMENT/PROGRESS/APPOINTMENT; operation=ASSERT/CANCEL. New targetId/targetRevision are null; existing IDs/kind/revision must match approvedSnapshot.items. Retain uncertainty and mark conflicts with DONE/manual items. Never invent a target; unsupported meanings remain uncertain. This is a review proposal, not user consent."
    fun analysis(t:SiwcNativeReviewStore.Ticket,scope:String)=SiwcAstraProtocol.Invocation(scope,SiwcAstraProtocol.Stage.ANALYSIS,
        "gpt-6-astra","xhigh",AstraAudioContract.sha(StrictJson.bytes(t.contract.pack)),mapOf("sourcePack" to t.contract.pack),ANALYSIS_POLICY)
    fun context(t:SiwcNativeReviewStore.Ticket,jobs:SiwcInferenceJobs,analysis:SiwcAstraProtocol.Invocation)=
        jobs.context(analysis,t.contract.snapshot,"gpt-6-astra","xhigh",CONTEXT_POLICY)
    fun validateAnalysis(t:SiwcNativeReviewStore.Ticket):(String)->Unit={t.contract.analysis(it);Unit}
    fun validateContext(t:SiwcNativeReviewStore.Ticket,jobs:SiwcInferenceJobs,analysis:SiwcAstraProtocol.Invocation):(String)->Unit {
        val output=jobs.completed(analysis,validateAnalysis(t)).output
        return {t.contract.context(it,analysis.key(),output);Unit}
    }
}

/** Explicit synthetic result-file exercise only. This never constructs HTTP/auth clients. Actual
 * public JNI text is the input, while both model completions are generated fixtures. */
internal class SiwcPublicFixturePort(private val contract:SiwcNativeContract):SiwcInferencePort {
    var calls=0;private set
    init {check(BuildConfig.SYNTHETIC_TOOLS_ENABLED && contract.source["audioSha256"] in PUBLIC_HASHES) { "SIWC_PUBLIC_FIXTURE_ONLY" }}
    override fun models(scope:String)=listOf("gpt-6-astra")
    override fun invoke(request:SiwcAstraProtocol.Invocation,stopped:()->Boolean):SiwcAstraProtocol.Completion {
        check(!stopped());calls++
        val output=if(request.stage==SiwcAstraProtocol.Stage.ANALYSIS)analysis() else {
            val body=SiwcNativeContract.parse(String(request.body(),Charsets.UTF_8))
            val content=SiwcNativeContract.obj(SiwcNativeContract.list(body["input"],1).single())["content"] as String
            val input=SiwcNativeContract.parse(content)
            mapOf("schema" to "ieum.siwc-context.v1","analysisJobId" to input["analysisJobId"],"analysisOutputSha256" to input["analysisOutputSha256"],"approvedSnapshotSha256" to input["approvedSnapshotSha256"],
                "actions" to listOf(mapOf("claimId" to "public_fixture_task","kind" to "TASK","targetId" to null,"targetRevision" to null,"operation" to "ASSERT","uncertain" to false,"conflict" to false)))
        }
        val response=mapOf("type" to "response.completed","response" to mapOf("id" to "public-fixture-"+request.key(),"model" to request.model,"status" to "completed",
            "output" to listOf(mapOf("type" to "message","role" to "assistant","status" to "completed","content" to listOf(mapOf("type" to "output_text","text" to StrictJson.stringify(output)))))))
        return SiwcAstraProtocol.completed(("data: "+StrictJson.stringify(response)+"\n\n").byteInputStream(),request,stopped)
    }
    private fun analysis():Map<String,Any?> {
        val quote=contract.text.substring(0,contract.text.offsetByCodePoints(0,contract.text.codePointCount(0,contract.text.length).coerceAtMost(80)))
        return mapOf("schema_version" to 1L,"job_id" to contract.jobId,"source_pack_sha256" to contract.pack["source_pack_sha256"],"base_revision" to contract.baseRevision,
            "summary" to "합성 결과파일 후보 · 실제 Astra 호출 아님: $quote","claims" to listOf(mapOf("claim_id" to "public_fixture_task","category" to "request","subject_id" to null,
                "value" to "합성 결과파일 검토: $quote","certainty" to "explicit","evidence" to listOf(mapOf("source_id" to contract.sourceId,"start" to 0L,"end" to quote.codePointCount(0,quote.length).toLong(),"quote" to quote)),
                "status" to "needs_confirmation","assignee" to null,"due" to null,"due_expression" to null,"replaces" to null)))
    }
    companion object {val PUBLIC_HASHES=setOf("69ac671cd328486d14dfdaa413ec0a511c21e5708953fbe3dc2a280a6a2cf20d","7d45a0f1af47bc5a212dd2e53d22566c6037b190708bff51782e387caed20df7")}
}
