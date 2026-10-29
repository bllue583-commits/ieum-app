package kr.callbrief.pipeline

import kr.callagent.drive.StrictJson
import java.nio.charset.StandardCharsets.UTF_8
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.time.LocalDate
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Contract violation. The code is a stable machine reason shared with the Node validator. */
class PipelineContractException(val code:String):IllegalStateException(code)

/** Android side of docs/contracts/README.md (C0-C3, sealed envelope). Pure JVM: no org.json,
 * no Android API, so the same code runs in plain unit tests against the Node golden fixtures.
 * Values are StrictJson trees: Map<String,Any?>, List<Any?>, String, Long, Boolean, null. */
object PipelineContract {
    const val STATUS="ieum.device-status.v1"
    const val TRANSCRIPT="ieum.transcript.v1"
    const val ANALYSIS="ieum.analysis.v3"
    const val REPORT="ieum.daily-report.v1"
    const val DEVICE="ieum.device.v1"
    const val SEALED="ieum.sealed.v1"
    const val DOC_LIMIT=524288
    val STATUSES=listOf("open","in_progress","waiting","needs_confirmation","done")
    private val KIND=mapOf(STATUS to "status",TRANSCRIPT to "c1",ANALYSIS to "c2",REPORT to "c3")
    private const val MAX_SAFE=9007199254740991L
    private val random=SecureRandom()

    // ---- small strict helpers -------------------------------------------------
    fun require(ok:Boolean,code:String) { if(!ok)throw PipelineContractException(code) }
    @Suppress("UNCHECKED_CAST") fun obj(v:Any?,code:String="OBJECT_REQUIRED"):Map<String,Any?> { require(v is Map<*,*>,code);return v as Map<String,Any?> }
    @Suppress("UNCHECKED_CAST") fun list(v:Any?,max:Int,code:String="LIST_REQUIRED"):List<Any?> { require(v is List<*> && v.size<=max,code);return v as List<Any?> }
    fun exact(v:Any?,vararg keys:String):Map<String,Any?> { val m=obj(v,"UNEXPECTED_FIELDS");require(m.keys==keys.toSet(),"UNEXPECTED_FIELDS");return m }
    fun int(v:Any?,min:Long=0,max:Long=MAX_SAFE):Long { require(v is Long && v>=min && v<=max,"INTEGER_RANGE");return v as Long }
    fun text(v:Any?,max:Int):String { require(v is String && v.isNotEmpty() && v.length<=max,"TEXT_REQUIRED");return v as String }
    fun optionalText(v:Any?,max:Int):String { require(v is String && v.length<=max,"TEXT_RANGE");return v as String }
    fun id(v:Any?):String { require(v is String && Regex("[A-Za-z0-9_-]{1,256}").matches(v),"IDENTIFIER_REQUIRED");return v as String }
    fun hash(v:Any?):String { require(v is String && Regex("[0-9a-f]{64}").matches(v),"HASH_REQUIRED");return v as String }
    fun bool(v:Any?):Boolean { require(v is Boolean,"BOOLEAN_REQUIRED");return v as Boolean }
    fun isoDate(v:Any?):String { require(v is String && Regex("\\d{4}-\\d{2}-\\d{2}").matches(v) && runCatching{LocalDate.parse(v as String).toString()==v}.getOrDefault(false),"DATE_REQUIRED");return v as String }
    private fun nullableDate(v:Any?)=if(v==null)null else isoDate(v)
    private fun status(v:Any?):String { require(v is String && v in STATUSES,"TASK_STATUS");return v as String }

    fun sha(bytes:ByteArray):String=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    fun sha(text:String)=sha(text.toByteArray(UTF_8))
    fun canonical(v:Any?):String=StrictJson.stringify(v)
    fun parse(bytes:ByteArray):Map<String,Any?> { require(bytes.size<=DOC_LIMIT,"DOC_SIZE");return obj(try{StrictJson.parseReadOnlyEnvelope(bytes)}catch(e:IllegalArgumentException){throw PipelineContractException("JSON_INVALID")}) }
    fun unsigned(x:Map<String,Any?>)=x.filterKeys{it!="digest"&&it!="signature"}
    fun digestOf(x:Map<String,Any?>):String { val kind=KIND[x["schema"]] ?: throw PipelineContractException("SCHEMA_UNKNOWN");return sha("ieum-pipeline-v1:$kind\n"+canonical(unsigned(x))) }
    fun transcriptIdOf(recordingId:String,sourceVersion:Long,audioSha:String,modelSha:String)=sha(canonical(listOf(recordingId,sourceVersion,audioSha,modelSha)))
    fun analysisIdOf(transcriptId:String,revision:Long)=sha(canonical(listOf("c2",transcriptId,revision)))
    fun reportIdOf(deviceId:String,reportDate:String,revision:Long)=sha(canonical(listOf("c3",deviceId,reportDate,revision)))
    private fun codepoints(s:String)=s.codePoints().toArray()
    /** First exact occurrence, measured in Unicode code points (same as the Node locator). */
    fun locateQuote(transcript:String,quote:String):Pair<Int,Int>? { val i=transcript.indexOf(quote);if(quote.isEmpty()||i<0)return null;val start=transcript.codePointCount(0,i);return start to start+quote.codePointCount(0,quote.length) }
    fun checkQuote(transcript:String,q:Map<String,Any?>) {
        val cp=codepoints(transcript);val start=int(q["start"],0,cp.size.toLong()).toInt();val end=int(q["end"],start+1L,cp.size.toLong()).toInt()
        require(String(cp,start,end-start)==q["quote"],"QUOTE_MISMATCH")
    }

    // ---- keys and signatures (ECDSA P-256 / SHA-256 over "<kind>\n<digest>") ----
    private val UNCOMPRESSED_SPKI_PREFIX=hex("3059301306072a8648ce3d020106082a8648ce3d030107034200")
    private fun hex(s:String)=ByteArray(s.length/2){s.substring(it*2,it*2+2).toInt(16).toByte()}
    fun publicKey(spkiBase64:String):PublicKey {
        val der=Base64.getDecoder().decode(spkiBase64);require(der.size==91 && der.copyOfRange(0,26).contentEquals(UNCOMPRESSED_SPKI_PREFIX),"P256_KEY_REQUIRED")
        return KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der))
    }
    /** Test fixtures and the server use PKCS#8; the app uses Android Keystore keys directly. */
    fun privateKey(pkcs8Base64:String):PrivateKey=KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(pkcs8Base64)))
    fun encode(key:PublicKey):String=Base64.getEncoder().encodeToString(key.encoded)
    fun kid(key:PublicKey)=sha(key.encoded)
    fun kid(spkiBase64:String)=kid(publicKey(spkiBase64))
    private fun signingInput(x:Map<String,Any?>)="${KIND[x["schema"]]}\n${x["digest"]}".toByteArray(UTF_8)
    fun seal(doc:Map<String,Any?>,signer:PrivateKey):Map<String,Any?> {
        val x=LinkedHashMap(unsigned(doc));x["digest"]=digestOf(x)
        val s=Signature.getInstance("SHA256withECDSA");s.initSign(signer);s.update(signingInput(x))
        x["signature"]=Base64.getEncoder().encodeToString(s.sign());return x
    }
    fun verifySignature(x:Map<String,Any?>,signer:PublicKey):Map<String,Any?> {
        hash(x["digest"]);val sig=x["signature"];require(sig is String && sig.length<=200,"SIGNATURE_REQUIRED")
        require(digestOf(x)==x["digest"],"DIGEST_MISMATCH")
        val ok=runCatching{val s=Signature.getInstance("SHA256withECDSA");s.initVerify(signer);s.update(signingInput(x));s.verify(Base64.getDecoder().decode(sig as String))}.getOrDefault(false)
        require(ok,"SIGNATURE_INVALID");return x
    }

    // ---- sealed envelope: ECDH P-256 ephemeral-static, HKDF-SHA256, AES-256-GCM ----
    private fun hmac(key:ByteArray,data:ByteArray):ByteArray { val m=Mac.getInstance("HmacSHA256");m.init(SecretKeySpec(key,"HmacSHA256"));return m.doFinal(data) }
    private fun contentKey(shared:ByteArray,epkDer:ByteArray,aad:String):ByteArray {
        val salt=MessageDigest.getInstance("SHA-256").digest(epkDer);val prk=hmac(salt,shared)
        val info="ieum-sealed-v1".toByteArray(UTF_8)+byteArrayOf(0)+aad.toByteArray(UTF_8)
        return hmac(prk,info+byteArrayOf(1)).copyOf(32).also{prk.fill(0)}
    }
    private fun agree(own:PrivateKey,other:PublicKey):ByteArray { val k=KeyAgreement.getInstance("ECDH");k.init(own);k.doPhase(other,true);return k.generateSecret() }
    fun encrypt(plain:ByteArray,recipient:PublicKey,aad:String):Map<String,Any?> {
        text(aad,512);val g=KeyPairGenerator.getInstance("EC");g.initialize(ECGenParameterSpec("secp256r1"),random);val eph=g.generateKeyPair()
        val epk=eph.public.encoded;require(epk.size==91,"P256_KEY_REQUIRED");val key=contentKey(agree(eph.private,recipient),epk,aad);val iv=ByteArray(12).also(random::nextBytes)
        val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,SecretKeySpec(key,"AES"),GCMParameterSpec(128,iv));c.updateAAD(aad.toByteArray(UTF_8));val ct=c.doFinal(plain);key.fill(0)
        val b=Base64.getEncoder()
        return linkedMapOf("schema" to SEALED,"kid" to kid(recipient),"epk" to b.encodeToString(epk),"iv" to b.encodeToString(iv),"aad" to aad,"ct" to b.encodeToString(ct))
    }
    /** recipientKid: the caller's own public key id (a Keystore private key cannot derive it). */
    fun decrypt(envelope:Map<String,Any?>,recipient:PrivateKey,recipientKid:String,expectedAad:String):ByteArray {
        val e=exact(envelope,"schema","kid","epk","iv","aad","ct");require(e["schema"]==SEALED,"SEALED_SCHEMA");require(e["aad"]==expectedAad,"SEALED_AAD_MISMATCH");require(e["kid"]==recipientKid,"SEALED_RECIPIENT_MISMATCH")
        val d=Base64.getDecoder();val epk=d.decode(text(e["epk"],200));val iv=d.decode(text(e["iv"],32));val ct=d.decode(text(e["ct"],DOC_LIMIT*2))
        require(iv.size==12 && ct.size>16 && ct.size<=DOC_LIMIT+16,"SEALED_SIZE")
        val key=contentKey(agree(recipient,publicKey(e["epk"] as String)),epk,expectedAad)
        try { val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,SecretKeySpec(key,"AES"),GCMParameterSpec(128,iv));c.updateAAD(expectedAad.toByteArray(UTF_8));return c.doFinal(ct) }
        catch(x:java.security.GeneralSecurityException){throw PipelineContractException("SEALED_AUTH_FAILED")} finally{key.fill(0)}
    }
    fun aadFor(x:Map<String,Any?>):String=when(x["schema"]) {
        TRANSCRIPT->"c1:"+x["transcriptId"];ANALYSIS->"c2:"+x["analysisId"];REPORT->"c3:"+x["reportId"];STATUS->"status:${x["deviceId"]}:${x["generatedAtMs"]}"
        else->throw PipelineContractException("SCHEMA_UNKNOWN")
    }
    fun sealTo(doc:Map<String,Any?>,recipient:PublicKey)=encrypt(StrictJson.bytes(doc),recipient,aadFor(doc))
    fun openFrom(envelope:Map<String,Any?>,recipient:PrivateKey,recipientKid:String,expectedAad:String,validate:(Map<String,Any?>)->Unit):Map<String,Any?> {
        val doc=parse(decrypt(envelope,recipient,recipientKid,expectedAad));validate(doc);require(aadFor(doc)==expectedAad,"SEALED_AAD_MISMATCH");return doc
    }

    // ---- C0 device status ------------------------------------------------------
    fun validateStatus(v:Any?):Map<String,Any?> {
        val x=exact(v,"schema","deviceId","generatedAtMs","pendingTranscription","unknownTime","failedTranscription","lastTranscribedAtMs","modelSha256","digest","signature")
        require(x["schema"]==STATUS,"STATUS_SCHEMA");hash(x["deviceId"]);int(x["generatedAtMs"],1)
        for(k in listOf("pendingTranscription","unknownTime","failedTranscription"))int(x[k],0,100000)
        if(x["lastTranscribedAtMs"]!=null)int(x["lastTranscribedAtMs"],1);if(x["modelSha256"]!=null)hash(x["modelSha256"])
        require(digestOf(x)==x["digest"],"DIGEST_MISMATCH");return x
    }

    // ---- C1 transcript ------------------------------------------------------------
    private fun validateBusiness(v:Any?) {
        val b=exact(v,"boardRevision","linkedCaseId","cases");int(b["boardRevision"]);if(b["linkedCaseId"]!=null)id(b["linkedCaseId"])
        val ids=mutableSetOf<String>();var tasks=0
        for(cv in list(b["cases"],50,"CASE_CAPACITY")) {
            val c=exact(cv,"caseId","label","summary","revision","tasks");require(ids.add(id(c["caseId"])),"CASE_DUPLICATE")
            text(c["label"],120);optionalText(c["summary"],5000);int(c["revision"]);val ts=list(c["tasks"],250,"TASK_CAPACITY");tasks+=ts.size;val tids=mutableSetOf<String>()
            for(tv in ts) {
                val t=exact(tv,"taskId","title","assignee","due","status","manualFields");require(tids.add(id(t["taskId"])),"TASK_DUPLICATE")
                text(t["title"],240);optionalText(t["assignee"],100);require(t["due"]==null||(t["due"] is String&&(t["due"] as String).length<=120),"TASK_DUE");status(t["status"])
                val mf=list(t["manualFields"],4);require(mf.all{it in listOf("title","assignee","due","status")}&&mf.toSet().size==mf.size,"MANUAL_FIELDS")
            }
        }
        require(tasks<=1000,"TASK_CAPACITY");require(b["linkedCaseId"]==null||b["linkedCaseId"] in ids,"LINKED_CASE_MISSING")
    }
    fun validateTranscript(v:Any?):Map<String,Any?> {
        val x=exact(v,"schema","transcriptId","deviceId","createdAtMs","source","stt","business","policy","digest","signature")
        require(x["schema"]==TRANSCRIPT,"TRANSCRIPT_SCHEMA");hash(x["deviceId"]);int(x["createdAtMs"],1)
        val s=exact(x["source"],"recordingId","sourceVersion","audioSha256","durationMs","originalStartedAtMs")
        id(s["recordingId"]);int(s["sourceVersion"],1);hash(s["audioSha256"]);val duration=int(s["durationMs"],1,6*3600000L);if(s["originalStartedAtMs"]!=null)int(s["originalStartedAtMs"],1)
        val t=exact(x["stt"],"engine","model","modelSha256","runtimeSha256","language","vadApplied","segments","text","textSha256")
        require(t["engine"]=="WHISPER_CPP_LOCAL","STT_ENGINE");text(t["model"],128);hash(t["modelSha256"]);hash(t["runtimeSha256"])
        require(t["language"] in listOf("ko","en","auto"),"STT_LANGUAGE");bool(t["vadApplied"]);val body=text(t["text"],131072);hash(t["textSha256"])
        val segs=list(t["segments"],4096,"SEGMENTS_REQUIRED");require(segs.isNotEmpty(),"SEGMENTS_REQUIRED");var last=0L;val joined=StringBuilder()
        for(gv in segs){val g=exact(gv,"startMs","endMs","text");val start=int(g["startMs"],last);last=int(g["endMs"],start+1,duration+1000);joined.append(text(g["text"],8000))}
        require(joined.toString()==body && sha(body)==t["textSha256"],"TRANSCRIPT_TEXT_BINDING")
        require(x["transcriptId"]==transcriptIdOf(s["recordingId"] as String,s["sourceVersion"] as Long,s["audioSha256"] as String,t["modelSha256"] as String),"TRANSCRIPT_ID")
        validateBusiness(x["business"])
        val p=exact(x["policy"],"epoch","consentEpoch","exclusionChecked");int(p["epoch"]);int(p["consentEpoch"]);require(p["exclusionChecked"]==true,"EXCLUSION_CHECK_REQUIRED")
        require(StrictJson.bytes(x).size<=DOC_LIMIT,"DOC_SIZE");require(digestOf(x)==x["digest"],"DIGEST_MISMATCH");return x
    }

    // ---- C2 analysis -----------------------------------------------------------------
    fun validateAnalysis(v:Any?,transcript:Map<String,Any?>?=null):Map<String,Any?> {
        val x=exact(v,"schema","analysisId","transcriptId","deviceId","revision","baseBoardRevision","targetCaseId","provider","createdAtMs","caseSuggestion","summary","cumulativeSummary","taskChanges","usage","digest","signature")
        require(x["schema"]==ANALYSIS,"ANALYSIS_SCHEMA");hash(x["transcriptId"]);hash(x["deviceId"]);val rev=int(x["revision"],1);int(x["baseBoardRevision"]);int(x["createdAtMs"],1)
        if(x["targetCaseId"]!=null)id(x["targetCaseId"]);require(x["analysisId"]==analysisIdOf(x["transcriptId"] as String,rev),"ANALYSIS_ID")
        val p=exact(x["provider"],"name","model","requestId");require(p["name"]=="ANTHROPIC_API","PROVIDER");text(p["model"],128);text(p["requestId"],256)
        val u=exact(x["usage"],"inputTokens","outputTokens");int(u["inputTokens"]);int(u["outputTokens"])
        val cs=exact(x["caseSuggestion"],"caseId","confidence","quote","start","end");if(cs["caseId"]!=null)id(cs["caseId"]);require(cs["confidence"] in listOf("high","medium","low"),"CASE_CONFIDENCE");text(cs["quote"],1000)
        text(x["summary"],3000);text(x["cumulativeSummary"],5000)
        val changes=list(x["taskChanges"],30,"TASK_CHANGE_CAPACITY").map{tv->
            val t=exact(tv,"op","taskId","title","assignee","due","status","quote","start","end")
            require(t["op"] in listOf("create","update","unchanged"),"TASK_OP");require((t["op"]=="create")==(t["taskId"]==null),"TASK_ID_FOR_OP")
            if(t["taskId"]!=null)id(t["taskId"]);text(t["title"],240);optionalText(t["assignee"],100);nullableDate(t["due"]);status(t["status"]);text(t["quote"],1000);t
        }
        require(changes.none{it["status"]=="done"},"DONE_REQUIRES_CONFIRMATION")
        if(transcript!=null) {
            val b=obj(transcript["business"]);val text=obj(transcript["stt"])["text"] as String
            require(transcript["transcriptId"]==x["transcriptId"]&&transcript["deviceId"]==x["deviceId"]&&b["boardRevision"]==x["baseBoardRevision"]&&b["linkedCaseId"]==x["targetCaseId"],"ANALYSIS_TRANSCRIPT_BINDING")
            checkQuote(text,cs);changes.forEach{checkQuote(text,it)}
            val cases=list(b["cases"],50).map(::obj).associateBy{it["caseId"] as String}
            require(cs["caseId"]==null||cs["caseId"] in cases,"SUGGESTED_CASE_UNKNOWN")
            val scope=(x["targetCaseId"] ?: cs["caseId"]) as String?;val known=scope?.let{cases[it]}?.let{c->list(c["tasks"],250).map{obj(it)["taskId"]}.toSet()} ?: emptySet()
            require(changes.all{it["taskId"]==null||it["taskId"] in known},"TASK_UNKNOWN")
        }
        require(digestOf(x)==x["digest"],"DIGEST_MISMATCH");return x
    }

    // ---- C3 daily report -----------------------------------------------------------
    const val KST_OFFSET_MS=9*3600000L
    fun reportRange(reportDate:String):Pair<Long,Long> { isoDate(reportDate);val until=LocalDate.parse(reportDate).toEpochDay()*86400000L-KST_OFFSET_MS;return (until-86400000L) to until }
    fun validateReport(v:Any?):Map<String,Any?> {
        val x=exact(v,"schema","reportId","deviceId","reportDate","zone","range","revision","generatedAtMs","cases","pending","weekly","digest","signature")
        require(x["schema"]==REPORT&&x["zone"]=="Asia/Seoul","REPORT_SCHEMA");val device=hash(x["deviceId"]);val date=isoDate(x["reportDate"]);val rev=int(x["revision"],1);int(x["generatedAtMs"],1)
        require(x["reportId"]==reportIdOf(device,date,rev),"REPORT_ID")
        val r=exact(x["range"],"fromMs","untilMs");val (from,until)=reportRange(date);require(r["fromMs"]==from&&r["untilMs"]==until,"REPORT_RANGE")
        for(cv in list(x["cases"],100,"REPORT_CASES")) {
            val c=exact(cv,"caseId","label","summary","cumulativeSummary","newTasks","changedTasks","needsConfirmation","transcriptIds")
            if(c["caseId"]!=null)id(c["caseId"]);text(c["label"],120);text(c["summary"],3000);optionalText(c["cumulativeSummary"],5000)
            for(tv in list(c["newTasks"],60)){val t=exact(tv,"title","due","status");text(t["title"],240);nullableDate(t["due"]);status(t["status"])}
            for(tv in list(c["changedTasks"],60)){val t=exact(tv,"taskId","title","due","status");id(t["taskId"]);text(t["title"],240);nullableDate(t["due"]);status(t["status"])}
            for(tv in list(c["needsConfirmation"],60)){val t=exact(tv,"taskId","title","reason");if(t["taskId"]!=null)id(t["taskId"]);text(t["title"],240);require(t["reason"] in listOf("DONE_CLAIMED","MANUAL_TASK","CASE_UNCONFIRMED","DUE_CONFLICT"),"CONFIRM_REASON")}
            list(c["transcriptIds"],200).forEach(::hash)
        }
        val p=exact(x["pending"],"untranscribed","awaitingAnalysis","failed","unknownTime","deviceStatusAtMs")
        for(k in listOf("untranscribed","awaitingAnalysis","failed","unknownTime"))int(p[k],0,100000);if(p["deviceStatusAtMs"]!=null)int(p["deviceStatusAtMs"],1)
        val w=exact(x["weekly"],"fromMs","untilMs","summary");val wf=int(w["fromMs"]);int(w["untilMs"],wf);optionalText(w["summary"],5000)
        require(w["untilMs"]==until&&wf==until-7*86400000L,"WEEKLY_RANGE")
        require(digestOf(x)==x["digest"],"DIGEST_MISMATCH");return x
    }

    // ---- device registration and inbox paths ------------------------------------------
    fun validateDevice(v:Any?):Map<String,Any?> {
        val x=exact(v,"schema","deviceId","signingKey","encryptionKey","label","registeredAtMs");require(x["schema"]==DEVICE,"DEVICE_SCHEMA")
        publicKey(text(x["encryptionKey"],200));require(x["deviceId"]==kid(text(x["signingKey"],200)),"DEVICE_ID");text(x["label"],80);int(x["registeredAtMs"],1);return x
    }
    object Paths {
        fun device(deviceId:String)="devices/${hash(deviceId)}.json"
        fun status(deviceId:String,generatedAtMs:Long)="inbox/${hash(deviceId)}/status/${int(generatedAtMs,1)}.sealed.json"
        fun transcript(deviceId:String,transcriptId:String)="inbox/${hash(deviceId)}/transcripts/${hash(transcriptId)}.sealed.json"
        fun analysisDir(deviceId:String)="outbox/${hash(deviceId)}/analysis"
        fun reportDir(deviceId:String)="outbox/${hash(deviceId)}/reports"
        fun analysis(deviceId:String,transcriptId:String,revision:Long)="${analysisDir(deviceId)}/${hash(transcriptId)}-r${int(revision,1)}.sealed.json"
        fun report(deviceId:String,reportDate:String,revision:Long)="${reportDir(deviceId)}/${isoDate(reportDate)}-r${int(revision,1)}.sealed.json"
    }
}
