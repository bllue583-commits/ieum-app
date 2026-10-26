package kr.callbrief

import android.app.Activity
import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import kr.callbrief.navermail.*
import kr.callbrief.navermail.android.BuildConfig as MailBuildConfig
import kr.callbrief.navermail.android.NaverCredentialVault
import org.json.JSONArray
import org.json.JSONObject
import java.time.*

/** Foreground normal recording entrypoint. Never requests a permission or creates a mail account. */
internal open class RelatedReadOnlyActivity:ComponentActivity() {
    protected open fun createSession()=GoogleConnectionSession.get(this)
    protected open fun createCoordinator(context:Context,current:()->Boolean)=RelatedReadOnlyCoordinator(context,current)
    protected open fun savedAccounts(context:Context)=NaverCredentialVault(context,ScopedNaverMailSettingsActivity.ownerNamespace((context as? AccountStorageContext)?.ownerId.orEmpty())).accounts()
    private lateinit var root:LinearLayout;private lateinit var session:GoogleConnectionSession;private lateinit var scoped:Context
    private var owner="";private var epoch=-1L;private var record=""
    @Volatile private var closed=false
    @Volatile private var stopped=false
    @Volatile private var generation=0L
    private var busy=false
    private var job:Job?=null
    @Volatile private var reader:RelatedReadOnlyCoordinator?=null
    private var dialog:AlertDialog?=null
    private var pendingUnlock:(()->Unit)?=null;private var status="";private var data:JSONObject?=null;private var sms=JSONArray();private var mail=JSONArray()
    private var smsScope:RelatedReadOnlyStore.Scope?=null;private var validSms=false;private var mailBinding:RelatedReadOnlyStore.MailBinding?=null
    private var lastSmsScan:JSONObject?=null
    private var accounts=emptyList<NaverCredentialVault.SavedAccount>()
    private val unlock=registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val action=pendingUnlock;pendingUnlock=null
        if(current()&&result.resultCode==Activity.RESULT_OK)action?.invoke()
        else if(current()){status="잠금 해제를 취소했어요. 메일은 읽지 않았어요.";render()}
    }
    private fun current()=!closed&&!stopped&&!isDestroyed&&!isFinishing&&::session.isInitialized&&session.ownerBinding()==owner&&session.epoch()==epoch
    private fun displayTime(ms:Long)=Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm"))
    private fun dp(n:Int)=(n*resources.displayMetrics.density+.5f).toInt()
    private fun text(value:String)=TextView(this).apply{text=value;textSize=18f;setTextColor(Color.rgb(13,34,112));setPadding(0,dp(8),0,dp(8));isSaveEnabled=false}
    private fun button(value:String,tagValue:String="",action:()->Unit)=Button(this).apply{text=value;textSize=18f;isAllCaps=false;minHeight=dp(56);tag=tagValue;isEnabled=!busy;setOnClickListener{if(current()&&!busy)action()}}
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState);window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(Color.rgb(221,241,248));setOnApplyWindowInsetsListener{v,i->v.setPadding(dp(16),i.systemWindowInsetTop+dp(12),dp(16),i.systemWindowInsetBottom+dp(12));i}}
        setContentView(root);record=intent.getStringExtra("recording_id").orEmpty()
        try{session=createSession();val s=session.dailyReportScope();owner=s.ownerId;epoch=s.epoch;scoped=if(owner.isBlank())this else AccountStorageContext(this,owner);check(record.isNotBlank());reload(true)}
        catch(_:Exception){root.addView(text("통화와 현재 앱 사용자를 확인하지 못했어요. 자료를 읽지 않았어요."));root.addView(button("뒤로"){finish()})}
    }
    private fun render() {
        if(!current())return
        root.removeAllViews();root.addView(text("통화의 관련 문자·메일"));root.addView(button("통화 업무로 돌아가기"){finish()})
        val body=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};root.addView(ScrollView(this).apply{addView(body)},LinearLayout.LayoutParams(-1,0,1f))
        body.addView(text("같은 번호·메일 주소는 관련 후보의 근거예요. 사람·업무·완료를 자동 합치지 않아요. 분석에 포함하려면 통화 업무 화면에서 별도로 동의해 주세요."))
        if(status.isNotBlank())body.addView(text(status).apply{tag="related_read_status"})
        body.addView(text("동일 번호 문자 · 직접 확인 번호 끝자리 ${data?.optString("phone_ending").orEmpty()}"))
        body.addView(button("동일 번호 문자 읽기 범위 설정","related_sms_configure"){configureSms()}.apply{isEnabled=!busy&&data!=null})
        smsScope?.let { s ->
            body.addView(text("설정한 범위: ${displayTime(s.fromMs)} ~ ${displayTime(s.untilMs)} (끝 시각 제외)\n표시 시간대: ${ZoneId.systemDefault()}\n${if(s.enabled)"화면에 돌아올 때 후보 새로 읽기" else "읽기 중단"} · ${if(validSms)"연결 확인됨" else "번호·사람·업무·원음 연결 다시 확인 필요"}"))
            body.addView(button("설정한 문자 후보 다시 읽기","related_sms_refresh"){scanSms()})
            body.addView(button("문자 읽기 중단","related_sms_disable"){perform{RelatedReadOnlyStore(scoped).use{it.disableSms(record)};"문자 읽기를 중단했어요. 기존 후보와 원본 문자는 유지해요."}})
        }
        if(!AndroidSmsReadOnlySource(this).hasPermission())body.addView(text("문자 읽기 권한이 설정돼 있지 않아 읽지 않아요. 이 화면은 새 권한을 요청하지 않아요."))
        lastSmsScan?.let { scan->body.addView(text("마지막 조회: ${scan.getInt("message_count")}개 원문 확인 · ${scan.getInt("added_count")}개 새 후보. ${if(scan.getInt("incomplete")==1)"일부 범위·본문은 미처리예요. 이전 후보는 유지해요." else "이전에 보관한 후보는 원본 문자 목록과 별도로 유지해요."}")) }
        for(i in 0 until sms.length()) {
            val c=sms.getJSONObject(i);body.addView(text("문자 후보 · ${displayTime(c.getLong("date_ms"))} · ${if(c.getInt("type")==1)"수신" else "발신"} · 원문 버전 ${c.getLong("version")}"))
            body.addView(button("문자 원문 보기"){preview("관련 문자 후보",c.getString("raw_text"))})
            body.addView(button("이 문자의 업무 관련성 직접 확인","related_sms_confirm"){val expected=JSONObject(checkNotNull(data).toString());confirm("관련성을 확인했나요?","원문·사람·업무를 확인하고 근거로 연결해요. 완료나 분석 포함 동의를 자동 변경하지 않아요.") {perform{RelatedReadOnlyStore(scoped).use{it.confirmSms(record,c.getString("digest"),expected)};"확인한 문자 원문을 근거로 연결했어요. 완료와 분석 포함은 바꾸지 않았어요."}}}.apply{isEnabled=!busy&&validSms})
        }
        body.addView(text("저장된 네이버 메일 범위"))
        if(!MailBuildConfig.REAL_ACCOUNT_ACTIONS_APPROVED)body.addView(text("현재 검증본은 실제 네이버 계정 읽기가 차단돼 있어요. 저장된 범위 연결 코드는 합성 자료로 검증하며 새 로그인·계정 권한은 만들지 않아요."))
        body.addView(button("저장된 메일 범위를 이 업무에 연결","related_mail_configure"){configureMail()}.apply{isEnabled=!busy&&data!=null})
        mailBinding?.let { b->body.addView(text("기존 범위: ${b.selected.since} ~ ${b.selected.before.minusDays(1)} · INBOX 최대 ${b.selected.limit}개\n${if(b.bodies)"주소·업무 키워드가 맞는 일반 텍스트 본문 최대 10개" else "헤더 후보만"}"))
            body.addView(button("기존 계정으로 메일 후보 다시 읽기","related_mail_refresh"){scanMail()}) }
        for(i in 0 until mail.length()) {val c=mail.getJSONObject(i);body.addView(text("관련 메일 후보: ${c.getString("subject")} · 업무 확정 전"));body.addView(button("메일 원문·근거 보기"){val p=JSONObject(c.getString("payload_json"));val raw=p.optString("rawText").takeUnless{it.isBlank()||it=="null"} ?: "본문 읽기 전 또는 아직 처리하지 못한 본문이에요.";preview("메일 후보",c.getString("subject")+"\n\n"+raw+"\n\n이 내용은 업무 반영 전 검토 후보예요. 첨부파일은 읽지 않았어요.")})}
    }
    private fun confirm(title:String,message:String,action:()->Unit) {
        if(!current())return
        val d=AlertDialog.Builder(this).setTitle(title).setMessage(message).setNegativeButton("취소",null).setPositiveButton("확인",null).create();dialog=d;d.show();d.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{if(current()){d.dismiss();action()}}
    }
    private fun preview(title:String,value:String) {if(!current())return;dialog=AlertDialog.Builder(this).setTitle(title).setView(ScrollView(this).apply{addView(text(value))}).setPositiveButton("닫기",null).create().also{it.show();it.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)}}
    private fun configureSms() {
        val expected=JSONObject(checkNotNull(data).toString());val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        val zone=ZoneId.systemDefault();val now=LocalDate.now(zone)
        val start=EditText(this).apply{textSize=18f;setText(now.minusDays(6).toString());isSaveEnabled=false;tag="related_sms_since"}
        val end=EditText(this).apply{textSize=18f;setText(now.toString());isSaveEnabled=false;tag="related_sms_last_day"}
        box.addView(text("확인한 번호의 수신·발신 SMS만 읽어요. MMS·RCS·첨부는 읽지 않아요. 기간은 최대 31일, 조회당 최대 50개예요. 시간대: $zone"));box.addView(text("시작 날짜"));box.addView(start);box.addView(text("마지막 날짜"));box.addView(end)
        val enabled=CheckBox(this).apply{text="이 범위의 문자 후보를 읽고 화면에 돌아오면 새로 조회하는 데 동의해요";textSize=18f;minHeight=dp(56);tag="related_sms_consent"};box.addView(enabled)
        val d=AlertDialog.Builder(this).setTitle("동일 번호 문자 읽기").setView(ScrollView(this).apply{addView(box)}).setNegativeButton("취소",null).setPositiveButton("설정 저장",null).create();dialog=d;d.show();d.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if(!current()||!enabled.isChecked)return@setOnClickListener
            try{val from=LocalDate.parse(start.text.toString()).atStartOfDay(zone).toInstant().toEpochMilli();val until=LocalDate.parse(end.text.toString()).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();d.dismiss();perform{RelatedReadOnlyStore(scoped).use{it.configureSms(record,expected,from,until,true)};"문자 읽기 범위를 저장했어요. 다음 조회부터 기존 문자 권한을 확인해요."}}
            catch(_:Exception){status="날짜를 확인해 주세요. 범위를 저장하지 않았어요.";render()}
        }
    }
    private fun configureMail() {
        val candidates=accounts.filter{it.account!=null&&it.scope!=null&&it.state==ConnectionState.VERIFY_REQUIRED}
        if(candidates.isEmpty()){status="읽을 범위가 저장된 네이버 계정이 없어요. 새 계정은 만들지 않았어요.";render();return}
        val expected=JSONObject(checkNotNull(data).toString());val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        val picker=Spinner(this).apply{adapter=ArrayAdapter(this@RelatedReadOnlyActivity,android.R.layout.simple_spinner_dropdown_item,candidates.map{it.account!!.address.take(2)+"*** · "+it.scope!!.since+" ~ "+it.scope!!.before.minusDays(1)})};box.addView(picker)
        box.addView(text("기존 계정의 확인된 이메일·키워드·기간을 넓히지 않아요. 그 범위가 이 통화의 사람·업무에 관련된 것인지 직접 확인해 주세요."))
        val related=CheckBox(this).apply{text="이 기존 범위의 이메일과 키워드가 현재 사람·업무에 관련됨을 확인했어요";textSize=18f;tag="related_mail_consent"};box.addView(related)
        val bodies=CheckBox(this).apply{text="주소와 업무 키워드가 모두 맞는 일반 텍스트 본문 최대 10개도 읽는 데 동의해요";textSize=18f;tag="related_mail_bodies"};box.addView(bodies)
        val d=AlertDialog.Builder(this).setTitle("저장된 메일 범위 연결").setView(ScrollView(this).apply{addView(box)}).setNegativeButton("취소",null).setPositiveButton("이 업무의 범위 저장",null).create();dialog=d;d.show();d.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{if(current()&&related.isChecked){val selected=candidates[picker.selectedItemPosition];val bodyAllowed=bodies.isChecked;d.dismiss();perform{RelatedReadOnlyStore(scoped).use{it.configureMail(record,expected,selected.scope!!,bodyAllowed)};"기존 메일 범위를 이 업무의 읽기 후보로 연결했어요. 새 계정·권한·메일 발송은 만들지 않았어요."}}}
    }
    private fun scanSms()=perform {ticket->createCoordinator(scoped,{current()&&generation==ticket}).use{reader=it;val found=it.scanSms(record);"문자 후보 ${found.first}개를 새로 보관했어요.${if(found.second)" 일부 범위·본문은 미처리예요." else ""} 업무·완료는 자동 반영하지 않았어요."}}
    private fun scanMail() {
        if(!MailBuildConfig.REAL_ACCOUNT_ACTIONS_APPROVED){status="실계정 메일 읽기 승인 대기예요. 로그인이나 읽기를 시작하지 않았어요.";render();return}
        val keyguard=getSystemService(KeyguardManager::class.java)
        val intent=keyguard.createConfirmDeviceCredentialIntent("이음 메일 후보 읽기","기존 계정의 저장된 비밀번호 사용을 잠금 해제로 확인해 주세요.")
        if(!keyguard.isDeviceSecure||intent==null){status="기기 잠금 해제를 확인하지 못해 메일을 읽지 않았어요.";render();return}
        pendingUnlock={perform{ticket->createCoordinator(scoped,{current()&&generation==ticket}).use{reader=it;val b=it.scanMail(record);"메일 후보 ${b.candidates.size}개 · ${b.selectedBodies.size}개 본문을 검토 대기로 보관했어요. 업무와 완료는 자동 확정하지 않았어요."}}};unlock.launch(intent)
    }
    private fun reload(autoSms:Boolean)=perform {ticket->
        if(autoSms)RelatedReadOnlyStore(scoped).use { store->val s=store.smsScope(record);if(s!=null&&store.current(s)&&AndroidSmsReadOnlySource(this).hasPermission())createCoordinator(scoped,{current()&&generation==ticket}).use{reader=it;it.scanSms(record)} }
        "저장한 읽기 범위와 관련 후보를 다시 확인했어요."
    }
    private fun perform(action:suspend (Long)->String) {
        if(!current()||busy)return;val ticket=++generation;busy=true;render()
        job=lifecycleScope.launch {
            try{val result=try{withContext(Dispatchers.IO){action(ticket)}}catch(e:CancellationException){throw e}catch(e:Exception){readFailureText(e)};ensureActive();if(!current()||ticket!=generation)return@launch
                val loaded=withContext(Dispatchers.IO){RelatedReadOnlyStore(scoped).use{s->Triple(s.smsScope(record),s.smsCandidates(record),s.mailBinding(record)) to s.lastSmsScan(record)}};val workflow=withContext(Dispatchers.IO){RecordingWorkflowStore(scoped).use{it.snapshot(record)}};val a=withContext(Dispatchers.IO){savedAccounts(scoped)}
                ensureActive();if(current()&&ticket==generation){smsScope=loaded.first.first;sms=loaded.first.second;mailBinding=loaded.first.third;lastSmsScan=loaded.second;data=workflow;mail=workflow.optJSONArray("naver_candidates") ?: JSONArray();accounts=a;validSms=withContext(Dispatchers.IO){RelatedReadOnlyStore(scoped).use{store->smsScope?.let{store.current(it)} ?: false}};status=result}
            }catch(e:CancellationException){throw e}catch(e:Exception){if(current()&&ticket==generation)status=when(e.message){"SMS_EXISTING_PERMISSION_REQUIRED"->"문자 읽기 권한이 없어 읽지 않았어요. 새 권한을 요청하지 않았어요.";"RELATED_IDENTITY_CONFIRMATION_REQUIRED","RELATED_PHONE_CONFIRMATION_REQUIRED"->"통화 업무에서 사람·업무·번호를 먼저 직접 확인해 주세요. 자료를 읽지 않았어요.";"EXCLUDED_BEFORE_READ"->"제외 대상이라 읽지 않았어요.";else->"읽기·저장을 확인하지 못했어요. 기존 후보와 완료는 유지하며 다시 확인해 주세요."}}
            finally{if(ticket==generation){reader=null;busy=false;if(current())render()}}
        }
    }
    private fun readFailureText(e:Exception)=when(e.message) {
        "SMS_EXISTING_PERMISSION_REQUIRED"->"문자 읽기 권한이 없어 읽지 않았어요. 새 권한을 요청하지 않았어요."
        "RELATED_IDENTITY_CONFIRMATION_REQUIRED","RELATED_PHONE_CONFIRMATION_REQUIRED"->"통화 업무에서 사람·업무·번호를 먼저 직접 확인해 주세요. 자료를 읽지 않았어요."
        "EXCLUDED_BEFORE_READ"->"제외 대상이라 읽지 않았어요."
        else->"읽기·저장을 확인하지 못했어요. 기존 후보와 완료는 유지하며 다시 확인해 주세요."
    }
    override fun onResume(){super.onResume();stopped=false;if(::session.isInitialized){if(!current()){finish();return};if(!busy&&pendingUnlock==null)reload(true)}}
    override fun onStop(){stopped=true;++generation;reader?.close();job?.cancel();busy=false;dialog?.dismiss();super.onStop()}
    override fun onDestroy(){closed=true;++generation;reader?.close();job?.cancel();pendingUnlock=null;dialog?.dismiss();super.onDestroy()}
}
