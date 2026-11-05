package kr.callbrief.pipeline

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import kr.callbrief.AndroidWhisperEngine
import kr.callbrief.SecureSettings
import kr.callbrief.SttDecode
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/* v3.3 r13-2 (DESIGN_V4 3.2-4, B0): the golden measurement on real recordings. The user picks 3 to 5 call recordings,
 * pastes what was really said, and the phone transcribes each recording once per variant (today's decoding and the
 * switches of 3.2-5..8). Only numbers leave the phone: CER, company/person/number hits and ms per piece, in the heartbeat's
 * golden block (PipelineSettingsKeys.GOLDEN). The recordings stay where they are (only their link is kept) and the answers
 * and the variant transcripts stay in an encrypted file of this app. Reached from 설정 "상태와 도움" (the link is r13-3's). */

/** The scorer of tools/audio/golden_score.py (normalize, distance, score_real), line for line; tools/audio/golden_parity.json
 * pins both to the same numbers (GoldenScoreTest, test_golden_score.py). */
internal object GoldenScore {
    private const val SINO="영일이삼사오육칠팔구"
    private val NATIVE_ONES=listOf("","한","두","세","네","다섯","여섯","일곱","여덟","아홉")
    private val NATIVE_TENS=listOf("","열","스물","서른","마흔","쉰","예순","일흔","여든","아흔")
    private val NATIVE_COUNTERS=listOf("시간","시","개","대","명","장","살","군데","가지","통")
    private val LETTERS=mapOf('a' to "에이",'b' to "비",'c' to "씨",'d' to "디",'e' to "이",'f' to "에프",'g' to "지",'h' to "에이치",'i' to "아이",
        'j' to "제이",'k' to "케이",'l' to "엘",'m' to "엠",'n' to "엔",'o' to "오",'p' to "피",'q' to "큐",'r' to "알",
        's' to "에스",'t' to "티",'u' to "유",'v' to "브이",'w' to "더블유",'x' to "엑스",'y' to "와이",'z' to "지")
    private val SYMBOLS=listOf("㎥" to "루베","%" to "퍼센트","㎜" to "밀리","㎝" to "센티","㎏" to "킬로")
    val NUMBER_UNITS=listOf("개월","시간","루베","미리","밀리","센티","킬로","키로","퍼센트","층","톤","일","월","원","만","천",
        "분","시","개","대","명","장","호","동","번","차","회","%","㎥","m3","mm","kg","m")
    private val THOUSANDS=Regex("(?<=\\d),(?=\\d{3})")
    private val DIGITS=Regex("(\\d+)")
    private val M3=Regex("(?<=\\d)\\s*m3\\b")
    private val NUMBER=Regex("\\d+(?:\\.\\d+)?")

    private fun under10000(value:Long):String {
        var n=value;val out=StringBuilder()
        for((unit,v) in listOf("천" to 1000L,"백" to 100L,"십" to 10L)) { val d=n/v;if(d>0) out.append(if(d==1L) "" else SINO[d.toInt()].toString()).append(unit);n%=v }
        if(n>0) out.append(SINO[n.toInt()])
        return out.toString()
    }
    private fun digitByDigit(digits:String)=digits.map { if(it=='0') "공" else SINO[it-'0'].toString() }.joinToString("")
    fun sino(digits:String):String {
        val trimmed=digits.trimStart('0')
        if(trimmed.isEmpty()) return "영"
        if(trimmed.length>16) return digitByDigit(trimmed) // 10^16 and up, as the script does
        var n=trimmed.toLong()
        if(n>=10_000_000_000_000_000L) return digitByDigit(trimmed)
        val out=StringBuilder()
        for((unit,v) in listOf("조" to 1_000_000_000_000L,"억" to 100_000_000L,"만" to 10_000L)) { val d=n/v;if(d>0) out.append(under10000(d)).append(unit);n%=v }
        return out.append(under10000(n)).toString()
    }
    private fun native(n:Int)=if(n==20) "스무" else NATIVE_TENS[n/10]+NATIVE_ONES[n%10]
    fun readNumbers(input:String):String {
        val text=THOUSANDS.replace(input,"")
        return DIGITS.replace(text) { m ->
            val digits=m.value;val rest=text.substring(m.range.last+1).trimStart()
            val value=digits.trimStart('0').let { if(it.length in 1..2) it.toInt() else if(it.isEmpty()) 0 else 100 }
            when {
                value in 1..99 && NATIVE_COUNTERS.any { rest.startsWith(it) } && !rest.startsWith("개월") -> native(value)
                digits.length>1 && digits.startsWith("0") -> digits.map { if(it!='0') SINO[it-'0'].toString() else "공" }.joinToString("")
                else -> sino(digits)
            }
        }
    }
    /** Python's str.isalnum: letters, and every kind of number (decimal, letter-like, other). */
    private fun alnum(c:Int)=Character.isLetterOrDigit(c) || Character.getType(c).let { it==Character.LETTER_NUMBER.toInt() || it==Character.OTHER_NUMBER.toInt() }
    fun normalize(input:String):String {
        var text=input.lowercase()
        for((k,v) in SYMBOLS) text=text.replace(k,v)
        text=M3.replace(text,"루베")
        text=readNumbers(text)
        val out=StringBuilder()
        text.codePoints().forEach { cp -> val mapped=if(cp<128) LETTERS[cp.toChar()] else null
            if(mapped!=null) out.append(mapped) else out.appendCodePoint(cp) }
        val kept=StringBuilder();out.toString().codePoints().forEach { if(alnum(it)) kept.appendCodePoint(it) }
        return kept.toString()
    }
    /** Levenshtein distance over code points. */
    fun distance(a:String,b:String):Int {
        val x=a.codePoints().toArray();val y=b.codePoints().toArray()
        var prev=IntArray(y.size+1) { it }
        for(i in 1..x.size) { val cur=IntArray(y.size+1);cur[0]=i
            for(j in 1..y.size) cur[j]=minOf(prev[j]+1,cur[j-1]+1,prev[j-1]+(if(x[i-1]!=y[j-1]) 1 else 0))
            prev=cur }
        return prev[y.size]
    }
    fun numberEntities(reference:String):List<String> {
        val text=THOUSANDS.replace(reference.lowercase(),"")
        val out=ArrayList<String>()
        for(m in NUMBER.findAll(text)) {
            val rest=text.substring(m.range.last+1).trimStart()
            val unit=NUMBER_UNITS.firstOrNull { rest.startsWith(it) }.orEmpty()
            val form=normalize(m.value+unit)
            if(form.isNotEmpty() && form !in out) out+=form
        }
        return out
    }
    data class Hits(val total:Int,val hit:Int)
    data class Score(val distance:Int,val refChars:Int,val hypChars:Int,val names:Map<String,Hits>,val number:Hits) {
        val cer get()=distance.toDouble()/maxOf(1,refChars)
    }
    /** One real recording (score_real): distance on the normalized texts; for each kind of name ([names], the ledger's) and
     * for the numbers written in the reference, how many of those said in the reference the hypothesis has too. */
    fun scoreReal(reference:String,hypothesis:String,names:Map<String,List<String>>):Score {
        val ref=normalize(reference);val hyp=normalize(hypothesis)
        val kinds=names.keys.sorted().associateWith { kind ->
            val forms=ArrayList<String>()
            for(n in names[kind].orEmpty()) { val f=normalize(n);if(f.codePointCount(0,f.length)>=2 && ref.contains(f) && f !in forms) forms+=f }
            Hits(forms.size,forms.count { hyp.contains(it) })
        }
        val numbers=numberEntities(reference)
        return Score(distance(ref,hyp),ref.codePointCount(0,ref.length),hyp.codePointCount(0,hyp.length),kinds,Hits(numbers.size,numbers.count { hyp.contains(it) }))
    }
}

/** The variants of DESIGN_V4 3.2-5..8 measured on each recording. [BASE] is the app as it is. Only constants change here;
 * a variant is adopted (3단계) by a separate commit after the numbers are in. */
internal enum class GoldenVariant(val code:String,val label:String,val decode:SttDecode=SttDecode.DEFAULT,val context:Boolean=false,
    val sentence:Boolean=false,val floor:Float=PipelineAudioMath.SPEECH_FLOOR,val loudShare:Float=PipelineAudioMath.LOUD_SHARE) {
    BASE("base","지금 방식"),
    FALLBACK("fallback","다시 듣기 줄이기",SttDecode(temperatureInc=0.4f,bestOf=2)),
    CONTEXT("context","앞 조각 이어 주기",context=true),
    SENTENCE("sentence","이름 힌트 문장",sentence=true),
    BEAM2("beam2","후보 2개로 듣기",SttDecode(beamSize=2)),
    NST("nst","잡음 글자 막기",SttDecode(suppressNonSpeech=true)),
    VAD_LOW("vadLow","작은 목소리 살리기",floor=PipelineAudioMath.SPEECH_FLOOR_LOW,loudShare=PipelineAudioMath.LOUD_SHARE_LOW);
    companion object { fun of(code:String)=entries.firstOrNull { it.code==code } }
}

/** The measurement's state (one JSON object, kept encrypted by [GoldenStore]): {"samples":[{"id","uri","mime","answer",
 * "addedMs"}],"runs":{"<sample id>/<variant>":{"pieces":{"<index>":{"text","ms"}},"done":bool,"chunks":n,"durationMs":n,
 * "error":code}},"startedMs"}. Pure functions, so the plan and the heartbeat numbers can be tested without a phone. */
internal object GoldenPlan {
    const val MAX_SAMPLES=5
    const val MAX_ANSWER_CHARS=20_000
    fun empty()=JSONObject().put("samples",JSONArray()).put("runs",JSONObject())
    fun samples(s:JSONObject):List<JSONObject> = s.optJSONArray("samples")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()
    fun runKey(sample:String,v:GoldenVariant)="$sample/${v.code}"
    fun run(s:JSONObject,sample:String,v:GoldenVariant):JSONObject {
        val runs=s.optJSONObject("runs") ?: JSONObject().also { s.put("runs",it) }
        return runs.optJSONObject(runKey(sample,v)) ?: JSONObject().also { runs.put(runKey(sample,v),it) }
    }
    /** The next sample and variant still to do: every variant of the first sample, then the next sample. */
    fun next(s:JSONObject):Pair<JSONObject,GoldenVariant>? {
        for(sample in samples(s)) for(v in GoldenVariant.entries) {
            val r=s.optJSONObject("runs")?.optJSONObject(runKey(sample.getString("id"),v))
            if(r==null || !r.optBoolean("done") && !r.has("error")) return sample to v
        }
        return null
    }
    fun units(s:JSONObject)=samples(s).size*GoldenVariant.entries.size
    fun finished(s:JSONObject)=samples(s).sumOf { sample -> GoldenVariant.entries.count { v ->
        s.optJSONObject("runs")?.optJSONObject(runKey(sample.getString("id"),v))?.let { it.optBoolean("done") || it.has("error") } == true } }
    /** The finished pieces of one run in order, joined. */
    fun hypothesis(run:JSONObject):String {
        val p=run.optJSONObject("pieces") ?: return ""
        return p.keys().asSequence().mapNotNull { it.toIntOrNull() }.sorted().map { p.getJSONObject(it.toString()).optString("text").trim() }.filter { it.isNotEmpty() }.joinToString(" ")
    }
    private fun pct(x:Double)=Math.round(x*1000)/10.0
    private fun hits(h:GoldenScore.Hits)=JSONObject().put("total",h.total).put("hit",h.hit)
    /** The heartbeat's golden block: numbers only, never the answers, the transcripts or a name. CER is weighted by the
     * reference length over the samples a variant finished; per sample its length and each variant's CER. */
    fun heartbeat(s:JSONObject,names:Map<String,List<String>>,now:Long):JSONObject {
        val variants=JSONObject();val bySample=JSONArray()
        data class Sum(var dist:Long=0,var ref:Long=0,var samples:Int=0,var pieces:Long=0,var ms:Long=0,val hit:MutableMap<String,IntArray> =HashMap())
        val sums=GoldenVariant.entries.associateWith { Sum() }
        for(sample in samples(s)) {
            val id=sample.getString("id");val answer=sample.optString("answer")
            val row=JSONObject();val cer=JSONObject()
            for(v in GoldenVariant.entries) {
                val r=s.optJSONObject("runs")?.optJSONObject(runKey(id,v)) ?: continue
                r.optLong("durationMs",0).takeIf { it>0 }?.let { row.put("lenSec",it/1000) }
                if(!r.optBoolean("done")) continue
                val score=GoldenScore.scoreReal(answer,hypothesis(r),names)
                val sum=sums.getValue(v);sum.dist+=score.distance;sum.ref+=score.refChars;sum.samples++
                r.optJSONObject("pieces")?.let { p -> for(k in p.keys()) { val ms=p.getJSONObject(k).optLong("ms",0);if(ms>0) { sum.pieces++;sum.ms+=ms } } }
                (score.names+("number" to score.number)).forEach { (k,h) -> sum.hit.getOrPut(k) { IntArray(2) }.let { it[0]+=h.total;it[1]+=h.hit } }
                cer.put(v.code,pct(score.cer))
            }
            bySample.put(row.put("cerPct",cer))
        }
        for((v,sum) in sums) if(sum.samples>0) variants.put(v.code,JSONObject().put("samples",sum.samples).put("cerPct",pct(sum.dist.toDouble()/maxOf(1,sum.ref)))
            .put("refChars",sum.ref).put("pieces",sum.pieces).put("msPerPiece",if(sum.pieces>0) sum.ms/sum.pieces else 0)
            .apply { sum.hit.forEach { (k,h) -> put(k,hits(GoldenScore.Hits(h[0],h[1]))) } })
        return JSONObject().put("atMs",now).put("samples",samples(s).size).put("done",finished(s)).put("units",units(s))
            .put("variants",variants).put("bySample",bySample)
    }
}

/** The measurement's state in this app's no-backup folder, AES-GCM with its own Android Keystore key. Answers and the
 * variant transcripts never leave it. */
internal class GoldenStore(context:Context) {
    private val file=File(File(context.noBackupFilesDir,"golden"),"state.bin")
    private fun key():SecretKey {
        val ks=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS,null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun read():JSONObject = synchronized(LOCK) { runCatching {
        val b=file.readBytes();val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,b,0,12))
        JSONObject(String(c.doFinal(b,12,b.size-12),Charsets.UTF_8))
    }.getOrNull() ?: GoldenPlan.empty() }
    fun update(change:(JSONObject)->Unit):JSONObject = synchronized(LOCK) {
        val s=read();change(s)
        val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key())
        file.parentFile?.mkdirs();val tmp=File(file.parentFile,"state.tmp")
        tmp.writeBytes(c.iv+c.doFinal(s.toString().toByteArray(Charsets.UTF_8)));check(tmp.renameTo(file)){"GOLDEN_WRITE"}
        s
    }
    fun clear() = synchronized(LOCK) { file.delete() }
    companion object { private const val ALIAS="callbrief.golden.v1";private val LOCK=Any() }
}

/** Runs the measurement while charging, one piece at a time, saving each; like the transcription work it begins no new
 * piece after [NEW_WORK_MS] and queues itself again, so a long recording goes over several runs. */
class GoldenWork(context:Context,params:WorkerParameters):Worker(context,params) {
    @Volatile private var engine:AndroidWhisperEngine?=null
    override fun onStopped() { runCatching { engine?.cancel() };super.onStopped() }
    private class Later:RuntimeException("GOLDEN_LATER")
    override fun doWork():Result {
        val app=applicationContext;val store=GoldenStore(app);val settings=SecureSettings(app)
        val model=PipelineModelManager(app);val file=model.verifiedModelFile() ?: return Result.success()
        val began=SystemClock.elapsedRealtime();var piecesThisRun=0
        val names=names(app)
        while(!isStopped) {
            val (sample,v)=GoldenPlan.next(store.read()) ?: break
            val id=sample.getString("id")
            try {
                val uri=Uri.parse(sample.getString("uri"))
                val size=app.contentResolver.openAssetFileDescriptor(uri,"r")?.use { it.length } ?: -1L
                val input=app.contentResolver.openInputStream(uri)?.use { PipelineBytesInput.read(it,if(size>0) size else 1L shl 20) } ?: error("GOLDEN_NO_RECORDING")
                val done=GoldenPlan.run(store.read(),id,v).optJSONObject("pieces")?.keys()?.asSequence()?.mapNotNull { it.toIntOrNull() }?.toSet() ?: emptySet()
                val transcriber=WhisperCallTranscriber(AndroidWhisperEngine(app).also { engine=it },model,threadSource={ TranscriptionThreads.current(app) },
                    decode=v.decode,promptNames=if(v.sentence) names.prompt else null,carryContext=v.context)
                val info=input.use { inp -> transcriber.open(file) { isStopped }.use { session ->
                    PipelineAudioDecoder(speechFloor=v.floor,loudShare=v.loudShare).decode(inp,sample.optString("mime").ifEmpty { "audio/mp4" }) { index,_,pcm ->
                        if(index in done) return@decode
                        if(isStopped) throw CallTranscriptionRunner.Stopped()
                        if(piecesThisRun>0 && SystemClock.elapsedRealtime()-began>=NEW_WORK_MS) throw Later()
                        val t0=SystemClock.elapsedRealtime()
                        val text=if(pcm.size<CallTranscriptionRunner.MIN_PIECE_SAMPLES) "" else session.transcribe(pcm) { isStopped }.joinToString(" ") { it.text.trim() }
                        val ms=SystemClock.elapsedRealtime()-t0
                        if(isStopped) throw CallTranscriptionRunner.Stopped()
                        store.update { s -> val r=GoldenPlan.run(s,id,v);(r.optJSONObject("pieces") ?: JSONObject().also { r.put("pieces",it) }).put(index.toString(),JSONObject().put("text",text).put("ms",ms)) }
                        piecesThisRun++
                    } } }
                store.update { s -> GoldenPlan.run(s,id,v).put("done",true).put("chunks",info.chunks).put("durationMs",info.durationMs) }
                publish(app,store,settings,names)
            } catch(_:Later) { enqueue(app,ExistingWorkPolicy.APPEND_OR_REPLACE);return Result.success() }
            catch(e:Exception) {
                if(isStopped) return Result.success()
                val code=e.message?.takeIf { it.matches(Regex("[A-Z0-9_]{1,60}")) } ?: e.javaClass.simpleName.take(60)
                store.update { s -> GoldenPlan.run(s,id,v).put("error",code) }
                publish(app,store,settings,names)
            }
        }
        publish(app,store,settings,names)
        return Result.success()
    }
    /** The ledger's names: by kind for the scores; "업체 이름 직책" lines (recently called companies first) for the prompt. */
    internal class Names(val byKind:Map<String,List<String>>,val prompt:List<String>)
    private fun names(app:Context):Names = runCatching {
        val db=kr.callbrief.IeumApplication.store(CallPipelineStartup.storage(app))
        val companies=db.companies().map { it.name };val people=db.peopleForSearch()
        val prompt=companies.flatMap { c -> people.filter { it.second==c }.map { (p,_) -> listOfNotNull(c,p.name,p.role).joinToString(" ") }.ifEmpty { listOf(c) } }+
            people.filter { it.second==null }.map { (p,_) -> listOfNotNull(p.name,p.role).joinToString(" ") }
        Names(mapOf("company" to companies,"person" to people.map { it.first.name }),prompt)
    }.getOrDefault(Names(emptyMap(),emptyList()))
    private fun publish(app:Context,store:GoldenStore,settings:SecureSettings,names:Names) = runCatching {
        val hb=GoldenPlan.heartbeat(store.read(),names.byKind,System.currentTimeMillis())
        settings.update { it.put(PipelineSettingsKeys.GOLDEN,hb.toString()) }
    }
    companion object {
        private const val NAME="golden-measure"
        /** The same as the transcription work (r12-8 A3): a piece takes up to about three minutes, the system stops at ten. */
        const val NEW_WORK_MS=6*60_000L
        fun enqueue(context:Context,policy:ExistingWorkPolicy=ExistingWorkPolicy.KEEP) {
            WorkManager.getInstance(context).enqueueUniqueWork(NAME,policy,OneTimeWorkRequestBuilder<GoldenWork>()
                .setConstraints(Constraints.Builder().setRequiresCharging(true).setRequiresBatteryNotLow(true).build()).build())
        }
        fun cancel(context:Context) { WorkManager.getInstance(context).cancelUniqueWork(NAME) }
    }
}

/** 설정 "상태와 도움" 아래 숨김 [골든 측정] (DESIGN_V4 3.2-4). The link on the settings screen is r13-3's (SettingsScreen.kt). */
class GoldenMeasureActivity:ComponentActivity() {
    private var pendingUri:Uri?=null;private var pendingMime:String=""
    private val pick=registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri==null) return@registerForActivityResult
        runCatching { contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        pendingUri=uri;pendingMime=contentResolver.getType(uri).orEmpty();render()
    }
    override fun onCreate(savedInstanceState:Bundle?) { super.onCreate(savedInstanceState);render() }
    override fun onResume() { super.onResume();render() }
    private fun render() {
        val a:Activity=this
        val column=CallUi.page(a)
        CallUi.band(column,"골든 측정","실제 통화로 받아쓰기 정확도를 재요",back={ finish() })
        column.addView(CallUi.text(a,"통화 녹음을 고르고, 그 통화에서 실제로 한 말을 받아 적어 붙여 넣어 주세요. 1분, 3분, 5분 넘는 통화와 상대 목소리가 작은 통화를 하나씩 넣으면 좋아요. 숫자는 아라비아 숫자로 적어 주세요."))
        column.addView(CallUi.text(a,"녹음과 정답은 이 폰 밖으로 나가지 않아요. 맞힌 비율 같은 숫자만 보내요. 측정은 충전 중에 돌고, 한 통화에 일곱 가지 방식을 차례로 써 봐서 시간이 꽤 걸려요."))
        val state=GoldenStore(a).read()
        val samples=GoldenPlan.samples(state)
        samples.forEachIndexed { i,s ->
            val card=CallUi.card(a)
            val len=GoldenVariant.entries.firstNotNullOfOrNull { v -> state.optJSONObject("runs")?.optJSONObject(GoldenPlan.runKey(s.getString("id"),v))?.optLong("durationMs",0)?.takeIf { it>0 } }
            card.addView(CallUi.text(a,"통화 ${i+1}"+(len?.let { ", ${it/60000}분 ${it/1000%60}초" } ?: ""),bold=true))
            card.addView(CallUi.text(a,"정답 ${s.optString("answer").length}자"))
            card.addView(CallUi.button(a,"이 통화 빼기") { GoldenStore(a).update { st -> st.put("samples",JSONArray(GoldenPlan.samples(st).filter { it.getString("id")!=s.getString("id") })) };render() })
            column.addView(card)
        }
        val uri=pendingUri
        if(uri!=null) {
            val box=EditText(a).apply { hint="여기에 정답을 붙여 넣어 주세요";textSize=CallUi.BODY;minLines=5;gravity=Gravity.TOP
                inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE }
            column.addView(CallUi.text(a,"고른 녹음의 정답",bold=true));column.addView(box,LinearLayout.LayoutParams(-1,-2))
            column.addView(CallUi.bigButton(a,"","정답 저장하기") {
                val answer=box.text.toString().trim().take(GoldenPlan.MAX_ANSWER_CHARS)
                if(answer.length<20) { box.error="정답을 조금 더 넣어 주세요";return@bigButton }
                GoldenStore(a).update { st -> st.optJSONArray("samples")?.put(JSONObject().put("id","s${System.currentTimeMillis()}").put("uri",uri.toString())
                    .put("mime",pendingMime).put("answer",answer).put("addedMs",System.currentTimeMillis())) }
                pendingUri=null;render()
            })
        } else if(samples.size<GoldenPlan.MAX_SAMPLES) column.addView(CallUi.button(a,"통화 녹음 고르기") { pick.launch(arrayOf("audio/*")) })
        if(samples.isNotEmpty() && uri==null) {
            val units=GoldenPlan.units(state);val done=GoldenPlan.finished(state)
            column.addView(CallUi.text(a,if(done==0) "아직 측정 전이에요" else "측정 $units 가지 중 $done 가지가 끝났어요"))
            column.addView(CallUi.bigButton(a,"","측정 시작하기") { GoldenWork.enqueue(a,ExistingWorkPolicy.KEEP);render() })
            column.addView(CallUi.button(a,"측정 결과 지우고 다시 하기") {
                GoldenWork.cancel(a);GoldenStore(a).update { it.put("runs",JSONObject()) }
                runCatching { SecureSettings(a).update { it.remove(PipelineSettingsKeys.GOLDEN) } };render()
            })
            resultLines(state).forEach { column.addView(CallUi.text(a,it)) }
        }
    }
    /** One line a variant: its share of letters heard wrong and its hits (numbers only, nothing of the call). */
    private fun resultLines(state:JSONObject):List<String> {
        val hb=runCatching { JSONObject(SecureSettings(this).snapshot().optString(PipelineSettingsKeys.GOLDEN)) }.getOrNull() ?: return emptyList()
        val v=hb.optJSONObject("variants") ?: return emptyList()
        if(GoldenPlan.samples(state).isEmpty()) return emptyList()
        return GoldenVariant.entries.mapNotNull { g -> v.optJSONObject(g.code)?.let { o ->
            fun h(k:String)=o.optJSONObject(k)?.let { "${it.optInt("hit")}/${it.optInt("total")}" } ?: "0/0"
            "${g.label}: 틀린 글자 ${o.optDouble("cerPct")}%, 업체 ${h("company")}, 사람 ${h("person")}, 숫자 ${h("number")}, 조각당 ${o.optLong("msPerPiece")/1000}초"
        } }
    }
}
