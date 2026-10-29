package kr.callbrief.pipeline

import kr.callagent.drive.StrictJson
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** Plain JVM: the Node golden fixtures (docs/contracts/fixtures) must validate, verify and
 * open here byte-for-byte, and Android-made envelopes/signatures must round-trip. */
class PipelineContractTest {
    private val dir=File("../../docs/contracts/fixtures").canonicalFile
    private fun load(name:String)=PipelineContract.parse(File(dir,name).readBytes())
    private val keys=load("test-keys.json")
    private fun pub(owner:String,use:String)=PipelineContract.publicKey(PipelineContract.obj(PipelineContract.obj(keys[owner])[use])["publicKey"] as String)
    private fun priv(owner:String,use:String)=PipelineContract.privateKey(PipelineContract.obj(PipelineContract.obj(keys[owner])[use])["privateKey"] as String)
    private fun code(expected:String,block:()->Unit) { try { block();fail("expected $expected") } catch(e:PipelineContractException) { assertEquals(expected,e.code) } }
    private fun <T> copy(x:T):Map<String,Any?> =PipelineContract.parse(StrictJson.bytes(x))

    @Test fun goldenFixturesValidateAndVerify() {
        val t=load("c1-transcript.json");val a=load("c2-analysis.json");val r=load("c3-report.json");val s=load("c0-status.json")
        PipelineContract.validateTranscript(t);PipelineContract.verifySignature(t,pub("device","signing"))
        PipelineContract.validateAnalysis(a,t);PipelineContract.verifySignature(a,pub("server","signing"))
        PipelineContract.validateReport(r);PipelineContract.verifySignature(r,pub("server","signing"))
        PipelineContract.validateStatus(s);PipelineContract.verifySignature(s,pub("device","signing"))
        PipelineContract.validateDevice(load("device.json"))
        val digests=load("digests.json")
        assertEquals(PipelineContract.obj(digests["c2"])["canonicalUnsignedSha256"],PipelineContract.sha(PipelineContract.canonical(PipelineContract.unsigned(a))))
        assertEquals(PipelineContract.obj(digests["c1"])["digest"],PipelineContract.digestOf(t))
    }
    @Test fun nodeEnvelopesOpenOnAndroid() {
        val a=load("c2-analysis.json");val deviceKid=PipelineContract.kid(pub("device","encryption"))
        val opened=PipelineContract.openFrom(load("c2-analysis.sealed.json"),priv("device","encryption"),deviceKid,"c2:"+a["analysisId"]){PipelineContract.validateAnalysis(it)}
        assertEquals(PipelineContract.canonical(a),PipelineContract.canonical(opened))
        val r=load("c3-report.json")
        assertEquals(PipelineContract.canonical(r),PipelineContract.canonical(PipelineContract.openFrom(load("c3-report.sealed.json"),priv("device","encryption"),deviceKid,"c3:"+r["reportId"]){PipelineContract.validateReport(it)}))
        code("SEALED_AAD_MISMATCH"){PipelineContract.openFrom(load("c2-analysis.sealed.json"),priv("device","encryption"),deviceKid,"c2:other"){}}
        code("SEALED_RECIPIENT_MISMATCH"){PipelineContract.openFrom(load("c1-transcript.sealed.json"),priv("device","encryption"),deviceKid,"c1:"+load("c1-transcript.json")["transcriptId"]){}}
    }
    @Test fun androidSealAndSignatureRoundTrip() {
        val t=copy(load("c1-transcript.json"));val resigned=PipelineContract.seal(t,priv("device","signing"))
        PipelineContract.verifySignature(PipelineContract.validateTranscript(copy(resigned)),pub("device","signing"))
        val env=PipelineContract.sealTo(resigned,pub("server","encryption"))
        val serverKid=PipelineContract.kid(pub("server","encryption"))
        val back=PipelineContract.openFrom(copy(env),priv("server","encryption"),serverKid,"c1:"+t["transcriptId"]){PipelineContract.validateTranscript(it)}
        assertEquals(PipelineContract.canonical(resigned),PipelineContract.canonical(back))
    }
    @Test fun tamperingIsRejected() {
        val t=load("c1-transcript.json");val a=load("c2-analysis.json")
        val changed=LinkedHashMap(a);changed["summary"]="다른 요약";code("DIGEST_MISMATCH"){PipelineContract.validateAnalysis(changed,t)}
        val wrongSigner=PipelineContract.seal(changed,priv("device","signing"));code("SIGNATURE_INVALID"){PipelineContract.verifySignature(wrongSigner,pub("server","signing"))}
        val tasks=(a["taskChanges"] as List<*>).map{LinkedHashMap(PipelineContract.obj(it))};tasks[1]["status"]="done"
        val done=LinkedHashMap(a);done["taskChanges"]=tasks;code("DONE_REQUIRES_CONFIRMATION"){PipelineContract.validateAnalysis(PipelineContract.seal(done,priv("server","signing")),t)}
        assertEquals(2 to 4,PipelineContract.locateQuote("가😀나다","나다"))
        assertEquals(PipelineContract.reportRange("2026-10-30"),(1793199600000L to 1793286000000L))
    }
}
