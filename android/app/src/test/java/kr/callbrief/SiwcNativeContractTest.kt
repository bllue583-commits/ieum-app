package kr.callbrief

import kr.callagent.drive.StrictJson
import org.junit.Assert.*
import org.junit.Test

class SiwcNativeContractTest {
    private val body="😀 공개 합성 서류를 확인해 주세요."
    private fun contract(items:List<Map<String,Any?>> = emptyList()):SiwcNativeContract {
        val source=mapOf("sourceId" to "public_ko","sourceVersion" to 1L,"audioSha256" to "a".repeat(64),"transcript" to body,"transcriptSha256" to AstraAudioContract.sha(body.toByteArray()))
        val snapshot=mapOf("sourceId" to "public_ko","sourceVersion" to 1L,"audioSha256" to "a".repeat(64),"personId" to "person_1","companyId" to null,"taskId" to "task_1","items" to items)
        return SiwcNativeContract(source,snapshot,"native_job",7)
    }
    private fun analysis(c:SiwcNativeContract)=mapOf("schema_version" to 1L,"job_id" to c.jobId,"source_pack_sha256" to c.pack["source_pack_sha256"],"base_revision" to 7L,"summary" to "공개 합성 검토 후보",
        "claims" to listOf(mapOf("claim_id" to "claim_1","category" to "document","subject_id" to null,"value" to "합성 서류","certainty" to "explicit","evidence" to listOf(mapOf("source_id" to c.sourceId,"start" to 0L,"end" to 2L,"quote" to "😀 ")),"status" to "needs_confirmation","assignee" to null,"due" to null,"due_expression" to null,"replaces" to null)))
    private fun context(c:SiwcNativeContract,a:Map<String,Any?>,action:Map<String,Any?> = action())=mapOf("schema" to "ieum.siwc-context.v1","analysisJobId" to "stage_key","analysisOutputSha256" to AstraAudioContract.sha(StrictJson.bytes(a)),"approvedSnapshotSha256" to c.snapshotSha,"actions" to listOf(action))
    private fun action()=mapOf("claimId" to "claim_1","kind" to "DOCUMENT","targetId" to null,"targetRevision" to null,"operation" to "ASSERT","uncertain" to false,"conflict" to false)
    private fun mutate(a:Map<String,Any?>,f:(Map<String,Any?>)->Map<String,Any?>)=a+mapOf("claims" to listOf(f(SiwcNativeContract.obj((a["claims"] as List<*>).single()))))
    private fun reject(block:()->Unit){assertNotNull(runCatching(block).exceptionOrNull())}
    @Test fun frozenSixFieldsAndSupplementaryCodePointEvidencePass(){val c=contract();val a=analysis(c);assertEquals(a,c.analysis(StrictJson.stringify(a)));c.context(StrictJson.stringify(context(c,a)),"stage_key",StrictJson.stringify(a));assertEquals("android_local_transcript",c.pack["scope"])}
    @Test fun sourceJobHashRevisionAndSchemaCannotBeChanged(){val c=contract();val a=analysis(c);for(x in listOf(a+mapOf("job_id" to "other"),a+mapOf("source_pack_sha256" to "b".repeat(64)),a+mapOf("base_revision" to 8L),a+mapOf("userApproved" to true),a-mapOf("summary" to null).keys))reject{c.analysis(StrictJson.stringify(x))}}
    @Test fun duplicateKeysAndFractionalOffsetsFail(){val c=contract();val raw=StrictJson.stringify(analysis(c));reject{c.analysis(raw.replace("\"summary\":","\"summary\":\"shadow\",\"summary\":"))};reject{c.analysis(raw.replace("\"start\":0","\"start\":0.5"))}}
    @Test fun utf16AndForeignQuoteRejected(){val c=contract();val a=analysis(c);for(e in listOf(mapOf("source_id" to c.sourceId,"start" to 0L,"end" to 1L,"quote" to "😀 "),mapOf("source_id" to "other_v1","start" to 0L,"end" to 2L,"quote" to "😀 ")))reject{c.analysis(StrictJson.stringify(mutate(a){it+mapOf("evidence" to listOf(e))}))}}
    @Test fun foreignSubjectAndUnquotedDeadlineFail(){val c=contract();val a=analysis(c);reject{c.analysis(StrictJson.stringify(mutate(a){it+mapOf("subject_id" to "foreign_person")}))};reject{c.analysis(StrictJson.stringify(mutate(a){it+mapOf("due" to "2026-10-27","due_expression" to "내일")}))}}
    @Test fun contextMustBindCompletedAnalysisAndSnapshot(){val c=contract();val a=analysis(c);val ctx=context(c,a);for(x in listOf(ctx+mapOf("analysisJobId" to "other"),ctx+mapOf("analysisOutputSha256" to "f".repeat(64)),ctx+mapOf("approvedSnapshotSha256" to "f".repeat(64))))reject{c.context(StrictJson.stringify(x),"stage_key",StrictJson.stringify(a))}}
    @Test fun omissionAndUnknownTargetRejected(){val c=contract();val a=analysis(c);reject{c.context(StrictJson.stringify(context(c,a)+mapOf("actions" to emptyList<Any>())),"stage_key",StrictJson.stringify(a))};reject{c.context(StrictJson.stringify(context(c,a,action()+mapOf("targetId" to "unknown","targetRevision" to 1L))),"stage_key",StrictJson.stringify(a))}}
    @Test fun manualDoneRequiresConflictAndTargetRevision(){val c=contract(listOf(mapOf("id" to "document_1","revision" to 2L,"kind" to "DOCUMENT","status" to "done","manual" to true)));val a=analysis(c);val x=action()+mapOf("targetId" to "document_1","targetRevision" to 2L);reject{c.context(StrictJson.stringify(context(c,a,x)),"stage_key",StrictJson.stringify(a))};c.context(StrictJson.stringify(context(c,a,x+mapOf("conflict" to true))),"stage_key",StrictJson.stringify(a));reject{c.context(StrictJson.stringify(context(c,a,x+mapOf("conflict" to true,"targetRevision" to 1L))),"stage_key",StrictJson.stringify(a))}}
    @Test fun uncertaintyAndCancellationMustSurvive(){val c=contract();val a=mutate(analysis(c)){it+mapOf("certainty" to "uncertain")};reject{c.context(StrictJson.stringify(context(c,a)),"stage_key",StrictJson.stringify(a))};c.context(StrictJson.stringify(context(c,a,action()+mapOf("uncertain" to true))),"stage_key",StrictJson.stringify(a));reject{c.context(StrictJson.stringify(context(c,a,action()+mapOf("uncertain" to true,"operation" to "CANCEL"))),"stage_key",StrictJson.stringify(a))}}
    @Test fun replacementCannotBecomeAnOrdinaryNewClaim(){val c=contract();val a=mutate(analysis(c)){it+mapOf("replaces" to "claim_1")};val e=c.normalized("owner","request",StrictJson.stringify(a),StrictJson.stringify(context(c,a)),"stage_key");assertNotNull(e.claims.single().supersedes);assertEquals(kr.callbrief.context.Certainty.NEEDS_REVIEW,e.claims.single().certainty)}
}
