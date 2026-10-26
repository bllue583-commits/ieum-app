package kr.callbrief.navermail.android

import android.app.Activity
import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import kr.callbrief.navermail.*
import java.time.Instant
import java.time.LocalDate
import java.util.Locale

/** Non-exported, secure-window foreground UI. Handoff policy is false, including debug builds. */
open class NaverMailSettingsActivity:ComponentActivity() {
    protected open fun ownerStillSelected():Boolean=true
    private lateinit var root:LinearLayout
    private lateinit var vault:NaverCredentialVault
    private var owner=""
    private val policy=if(BuildConfig.REAL_ACCOUNT_ACTIONS_APPROVED)AccountActionPolicy.approvedByHost() else AccountActionPolicy.denied()
    private var accounts=emptyList<NaverCredentialVault.SavedAccount>()
    private var message:String?=null
    private var state=ConnectionState.NOT_CONNECTED
    private var busy=false
    private var generation=0L
    private var retryAfter:Instant?=null
    private var retryAttempt=0
    private fun canAttempt()=retryAfter?.let{!Instant.now().isBefore(it)}?:true
    private var job:Job?=null
    private var dialog:AlertDialog?=null
    private var password:EditText?=null
    @Volatile private var pendingSecret:SecretChars?=null
    @Volatile private var activeSession:ReadOnlyMailbox.Session?=null
    private var afterUnlock:(()->Unit)?=null
    private var intake:MailIntake?=null
    private var batch:ScanBatch?=null
    private val selected=mutableSetOf<MailRef>()
    protected open fun createProposalInbox():MailProposalInbox=object:MailProposalInbox {
        override fun replace(batch:ScanBatch) { /* Preview is returned directly; no plaintext DB or network write. */ }
        override fun purge(accountKey:String) { }
    }
    private val inbox:MailProposalInbox by lazy { createProposalInbox() }
    private val deviceUnlock=registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val action=afterUnlock;afterUnlock=null
        if(result.resultCode==Activity.RESULT_OK)action?.invoke() else { message="기기 잠금 해제를 취소했어요.";state=ConnectionState.DEVICE_UNLOCK_REQUIRED;render() }
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState);window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        owner=intent.getStringExtra(EXTRA_OWNER).orEmpty()
        root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setBackgroundColor(Color.rgb(221,241,248))
            setOnApplyWindowInsetsListener { v,i->v.setPadding(dp(16),i.systemWindowInsetTop+dp(12),dp(16),i.systemWindowInsetBottom+dp(12));i }
        };setContentView(root)
        if(!owner.matches(Regex("[A-Za-z0-9._-]{1,80}"))) { state=ConnectionState.STORAGE_UNAVAILABLE;message="앱 사용자 식별 정보를 확인할 수 없어 연결을 시작하지 않았어요.";render();return }
        vault=NaverCredentialVault(this,owner,policy);render();reload()
    }
    override fun onStop() {
        super.onStop();++generation;password?.text?.clear();password=null;dialog?.dismiss();dialog=null
        pendingSecret?.close();pendingSecret=null;activeSession?.close();activeSession=null
        job?.cancel();job=null;busy=false;batch=null;selected.clear()
        // Revokes pending receipts and erases preview references without deleting saved credentials.
        intake?.stopForBackground();intake=null
        if(state==ConnectionState.CONNECTED)state=ConnectionState.VERIFY_REQUIRED
        render()
    }
    override fun onResume() { super.onResume();if(!ownerStillSelected()) { accounts=emptyList();batch=null;selected.clear();finish();return };if(::vault.isInitialized)reload() }
    private fun reload() {
        if(!::vault.isInitialized || !ownerStillSelected())return
        lifecycleScope.launch {
            val found=withContext(Dispatchers.IO){vault.accounts()}
            if(!ownerStillSelected() || isDestroyed || isFinishing)return@launch
            accounts=found
            state=if(accounts.isEmpty())ConnectionState.NOT_CONNECTED else if(accounts.all{it.state==ConnectionState.DISCONNECTED})ConnectionState.DISCONNECTED else ConnectionState.VERIFY_REQUIRED
            render()
        }
    }
    private fun dp(n:Int)=(n*resources.displayMetrics.density+.5f).toInt()
    private fun label(value:String,size:Float=18f,bold:Boolean=false)=TextView(this).apply {
        text=value;textSize=size;setTextColor(Color.rgb(13,34,112));setPadding(0,dp(6),0,dp(6));if(bold)setTypeface(typeface,Typeface.BOLD)
        isSaveEnabled=false;importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    }
    private fun button(value:String,enabled:Boolean=true,action:()->Unit)=Button(this).apply {
        text=value;textSize=18f;isAllCaps=false;minHeight=dp(56);isEnabled=enabled&&!busy
        setOnClickListener{if(!busy)action()}
    }
    private fun card(parent:LinearLayout)=LinearLayout(this).apply {
        orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(12),dp(16),dp(12))
        background=GradientDrawable().apply{setColor(Color.WHITE);cornerRadius=dp(20).toFloat()}
        parent.addView(this,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(12)})
    }
    private fun render() {
        if(isFinishing||isDestroyed||!::root.isInitialized)return
        root.removeAllViews();root.addView(label("네이버 메일",24f,true));root.addView(button("환경설정으로 돌아가기"){finish()})
        val scroll=ScrollView(this);val content=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        scroll.addView(content);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        val summary=card(content);summary.tag="naver_connection"
        summary.addView(label(MailStateText.connection(state),bold=true).apply{tag="naver_state"})
        summary.addView(label("네이버 로그인은 메일 읽기 연결과 별개예요. 선택한 범위만 읽고, 진행상황·필요서류·할일의 확인 후보를 보여줘요."))
        summary.addView(label("메일 발송·삭제·이동·읽음 변경 없이 읽어요. 첨부파일 내용은 처리하지 않아요."))
        if(!policy.approved)summary.addView(label("현재 검증본: 새 계정·앱 비밀번호 저장·실제 메일 읽기 승인 대기",bold=true).apply{tag="naver_policy"})
        message?.let{summary.addView(label(it).apply{tag="naver_message"})}
        retryAfter?.takeIf{Instant.now().isBefore(it)}?.let { summary.addView(label("재시도 가능 시각: $it · 자동 재시도 없음").apply{tag="naver_retry"}) }
        summary.addView(button("네이버 메일 선택 연결",policy.approved && canAttempt() && ::vault.isInitialized){ unlock{showConnectionForm()} }.apply{tag="naver_connect"})
        summary.addView(button("실제 인증에 필요한 단계 보기"){showGuide()})
        for(row in accounts) {
            val c=card(content);c.tag="naver_account_${row.key}"
            c.addView(label(row.account?.let{mask(it.address)}?:"저장된 계정 정보 확인 필요",22f,true))
            c.addView(label(MailStateText.connection(row.state)))
            row.error?.let{c.addView(label(MailStateText.error(it)))}
            row.scope?.let { s ->
                c.addView(label("받은메일함 · ${s.since} ~ ${s.before.minusDays(1)} (메일 서버 수신일) · 최대 ${s.limit}건\n${s.correspondentAddresses.joinToString()}\n업무 키워드: ${s.keywords.joinToString()}"))
                c.addView(button("선택 범위의 헤더 후보 읽기",policy.approved && canAttempt()){consentScan(s)})
            }
            c.addView(button("인증·권한 다시 확인",policy.approved && canAttempt()){unlock{showConnectionForm()}})
            c.addView(button("연결 해제"){confirmDisconnect(row)})
        }
        batch?.let { b ->
            val info=card(content);info.addView(label("${b.candidates.size}개 업무 관련 후보 · 앱 업무에 미반영",22f,true))
            if(b.coverageIncomplete)info.addView(label("선택 범위를 모두 처리하지 못했어요. 누락·미처리 상태를 확인해 주세요.",bold=true))
            b.candidates.forEach { row ->
                val c=card(content)
                c.addView(label(row.header.subject,22f,true));c.addView(label(row.reasons.joinToString("\n")))
                c.addView(label(row.uncertainties.joinToString("\n")))
                c.addView(label("본문: ${bodyText(row.bodyState)} · 첨부파일 미처리"))
                c.addView(CheckBox(this).apply{
                    text="이 메일 본문을 읽을 후보로 선택";textSize=18f;isChecked=row.header.ref in selected;isEnabled=!busy
                    setOnCheckedChangeListener{_,checked->if(checked&&selected.size<10)selected+=row.header.ref else {selected-=row.header.ref;if(checked)isChecked=false}}
                })
                row.proposals.forEach { p ->c.addView(label("확인 후보: ${p.text}\n근거: 본문 ${p.evidence.start}~${p.evidence.end}\n사용자 확인 필요 · 진행 상태 미확정")) }
            }
            info.addView(button("선택한 본문 읽기 · 최대 10개",policy.approved && canAttempt()){if(selected.isEmpty()){message="본문을 읽을 메일을 먼저 선택해 주세요.";render()}else unlock{readBodies(b,selected.toSet())}})
        }
        if(busy)summary.addView(label("연결 또는 선택 자료 확인 중 · 앱을 벗어나면 중단해요."))
    }
    private fun protected(d:AlertDialog) {
        checkNotNull(d.window).addFlags(WindowManager.LayoutParams.FLAG_SECURE);dialog=d;d.show()
    }
    private fun showGuide() {
        val content=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),0,dp(16),0)}
        content.addView(label("승인 이후 사용자가 직접 진행할 단계",22f,true))
        content.addView(label("1. 네이버ID > 보안설정 > 2단계 인증을 설정해요.\n2. 2단계 인증 관리 > 애플리케이션 비밀번호 관리에서 ‘이음’ 전용 비밀번호를 만들어요.\n3. PC 네이버 메일 > 환경설정 > POP3/IMAP 설정 > IMAP/SMTP 설정 > 사용함으로 저장해요.\n4. 이음의 보안 입력창에 전체 네이버 메일 주소와 앱 비밀번호를 입력해요.\n5. 상대방 이메일과 업무 키워드·읽을 날짜를 확인해요. 연결 확인 뒤 별도로 읽기를 선택해요.\n\n일반 네이버 비밀번호나 로그인 토큰을 입력하지 마세요. 비밀번호를 채팅·메모·로그에 남기지 마세요. 이 화면의 안내만으로 새 연결이 승인되는 것은 아니에요."))
        content.addView(button("공식 네이버 인증 안내"){startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(AUTH_HELP)))})
        content.addView(button("공식 네이버 IMAP 안내"){startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(IMAP_HELP)))})
        content.addView(label("앱 비밀번호의 네이버 권한은 메일 읽기로만 제한된 권한이 아니에요. 이음 구현이 읽기만 수행해요. 연결 해제 후 네이버ID에서 이음 전용 앱 비밀번호도 직접 삭제해 주세요."))
        protected(AlertDialog.Builder(this).setTitle("메일 연결 안내").setView(ScrollView(this).apply{addView(content)}).setPositiveButton("닫기",null).create())
    }
    private fun unlock(action:()->Unit) {
        if(!policy.approved){message=MailStateText.error(MailError.APPROVAL_REQUIRED);render();return}
        val keyguard=getSystemService(KeyguardManager::class.java)
        if(!keyguard.isDeviceSecure){state=ConnectionState.DEVICE_UNLOCK_REQUIRED;message="휴대전화 화면 잠금을 먼저 설정해 주세요.";render();return}
        val intent=keyguard.createConfirmDeviceCredentialIntent("이음 네이버 메일","저장한 앱 비밀번호를 사용하려면 기기 잠금을 해제해 주세요.")
        if(intent==null){state=ConnectionState.DEVICE_UNLOCK_REQUIRED;render();return}
        afterUnlock=action;deviceUnlock.launch(intent)
    }
    private fun field(hintValue:String,secret:Boolean=false)=EditText(this).apply {
        hint=hintValue;textSize=18f;isSingleLine=true;minHeight=dp(56);isSaveEnabled=false;isSaveFromParentEnabled=false
        importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        inputType=if(secret)InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions=EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        if(secret){transformationMethod=PasswordTransformationMethod.getInstance();setTextIsSelectable(false);tag="naver_secret"}
    }
    private fun showConnectionForm() {
        if(!policy.approved)return
        val content=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),0,dp(16),0);isSaveEnabled=false}
        val address=field("전체 네이버 메일 주소");val pass=field("이음 전용 앱 비밀번호",true);password=pass
        val email=field("상대방 이메일 (본인이 확인한 주소)");val business=field("업무 이름 (후보 확인용)")
        val keywords=field("업무 키워드 (쉼표로 구분)");val start=field("시작 날짜 YYYY-MM-DD");val end=field("마지막 날짜 YYYY-MM-DD")
        start.setText(LocalDate.now(java.time.ZoneOffset.UTC).minusDays(6).toString());end.setText(LocalDate.now(java.time.ZoneOffset.UTC).toString())
        content.addView(label("받은메일함만 읽기 · 최대 31일/50건 · 상대방 이메일과 업무 연결을 직접 확인해 주세요. 앱 업무 기록은 자동 변경되지 않아요."))
        listOf(address,pass,email,business,keywords,start,end).forEach{content.addView(it)}
        val confirmed=CheckBox(this).apply{text="상대방 이메일과 이 업무의 관계를 확인했어요";textSize=18f};content.addView(confirmed)
        val d=AlertDialog.Builder(this).setTitle("네이버 메일 선택 연결").setView(ScrollView(this).apply{addView(content)})
            .setNegativeButton("취소",null).setPositiveButton("연결만 확인",null).create()
        d.setOnDismissListener{pass.text.clear();password=null};protected(d)
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val scope=try {
                require(business.text.toString().trim().isNotEmpty())
                val a=AccountBinding(owner,address.text.toString().trim().lowercase(Locale.ROOT))
                val counterpart=email.text.toString().trim().lowercase(Locale.ROOT)
                MailScope(a,"unbound-${hash(business.text.toString().trim())}","confirmed-${hash(counterpart)}",setOf(counterpart),
                    keywords.text.toString().split(',').map{it.trim()}.filter{it.isNotEmpty()}.toSet(),LocalDate.parse(start.text.toString()),LocalDate.parse(end.text.toString()).plusDays(1),relationshipConfirmedByUser=confirmed.isChecked)
            }catch(_:Exception){message="주소·업무 키워드·기간·상대방 확인을 점검해 주세요.";Toast.makeText(this,message,Toast.LENGTH_LONG).show();return@setOnClickListener}
            val chars=CharArray(pass.text.length){pass.text[it]};pass.text.clear();d.dismiss()
            val secret=SecretChars(chars);pendingSecret=secret;busy=true;state=ConnectionState.CONNECTING;render()
            runIo {
                val session=NaverImapMailbox(policy).open(scope.account,secret);activeSession=session
                try{session.examineInbox();currentCoroutineContext().ensureActive();vault.save(scope.account,scope,secret,Instant.now())}
                finally{session.close();activeSession=null;secret.close();pendingSecret=null}
                withContext(Dispatchers.Main){message="계정 연결만 확인했어요. 실제 메일은 아직 읽지 않았어요.";state=ConnectionState.CONNECTED}
            }
        }
    }
    private fun consentScan(scope:MailScope) {
        protected(AlertDialog.Builder(this).setTitle("이 범위의 헤더 후보를 읽을까요?")
            .setMessage("${mask(scope.account.address)}\n받은메일함 · ${scope.since}~${scope.before.minusDays(1)} (메일 서버 수신일)\n${scope.correspondentAddresses.joinToString()}\n업무 키워드: ${scope.keywords.joinToString()}\n최대 ${scope.limit}건. 본문과 첨부파일은 읽지 않아요.")
            .setNegativeButton("취소",null).setPositiveButton("선택 범위 읽기"){_,_->unlock{scan(scope)}}.create())
    }
    private fun scan(scope:MailScope) {
        busy=true;render();val next=MailIntake(policy,NaverImapMailbox(policy),inbox);intake=next;val ticket=next.select(scope)
        runIo { val receipt=next.scan(ticket,vault.credential(scope.account));withContext(Dispatchers.Main){batch=receipt;selected.clear();message="헤더 후보만 읽었어요. 연결 후보와 근거를 확인해 주세요."} }
    }
    private fun readBodies(receipt:ScanBatch,refs:Set<MailRef>) {
        val current=intake?:return;busy=true;render()
        runIo { val result=current.readSelected(receipt,refs,vault.credential(receipt.account));withContext(Dispatchers.Main){batch=result;selected.clear();message="선택한 본문에서 확인 후보를 찾았어요. 업무 기록은 바꾸지 않았어요."} }
    }
    private fun runIo(action:suspend()->Unit) {
        val ticket=++generation
        job=lifecycleScope.launch {
            try{withContext(Dispatchers.IO){action()};retryAttempt=0;retryAfter=null}
            catch(e:CancellationException){throw e}
            catch(e:Exception){if(ticket!=generation)return@launch;val code=if(e is MailFailure)e.code else MailError.INVALID_RESPONSE
                state=when(code){MailError.AUTH_REQUIRED,MailError.IMAP_PERMISSION_OR_AUTH->ConnectionState.REAUTH_REQUIRED
                    MailError.NETWORK,MailError.RATE_LIMITED->ConnectionState.RETRY_WAIT
                    MailError.STORAGE_KEY_LOST,MailError.STORAGE_IO->ConnectionState.STORAGE_UNAVAILABLE
                    else->ConnectionState.VERIFY_REQUIRED};message=MailStateText.error(code)
                val delaySeconds=MailRetry.delaySeconds(code,retryAttempt)
                if(delaySeconds!=null){retryAttempt++;retryAfter=Instant.now().plusSeconds(delaySeconds)
                    lifecycleScope.launch{delay(delaySeconds*1000);if(ticket==generation)render()}}
                else if(code in setOf(MailError.NETWORK,MailError.RATE_LIMITED)){message="일시 오류가 반복됐어요. 자동 재시도는 중단됐어요. 계정·권한을 다시 확인해 주세요.";state=ConnectionState.VERIFY_REQUIRED}}
            finally{if(ticket==generation){pendingSecret?.close();pendingSecret=null;busy=false;if(isActive){accounts=withContext(Dispatchers.IO){vault.accounts()};render()}}}
        }
    }
    private fun confirmDisconnect(row:NaverCredentialVault.SavedAccount) {
        protected(AlertDialog.Builder(this).setTitle("이 계정 연결 해제")
            .setMessage("수집을 중단하고 이 계정의 단말 비밀번호·저장한 설정·메일 후보를 지워요. 네이버 원본 메일은 바꾸지 않아요. 네이버ID에서 이음 전용 앱 비밀번호를 직접 삭제해야 네이버 권한도 폐기돼요.")
            .setNegativeButton("취소",null).setPositiveButton("연결 해제"){_,_->
                row.account?.let{intake?.disconnect(it)};activeSession?.close();job?.cancel();batch=null;selected.clear()
                lifecycleScope.launch { val complete=withContext(Dispatchers.IO){vault.disconnect(row.key)}
                    message=if(complete)"단말 연결을 해제했어요. 네이버ID의 전용 앱 비밀번호도 직접 삭제해 주세요." else "연결을 중단했어요. 단말 자료 삭제 확인이 더 필요해요. 네이버ID의 전용 앱 비밀번호도 직접 삭제해 주세요."
                    state=ConnectionState.DISCONNECTED;accounts=withContext(Dispatchers.IO){vault.accounts()};render() }
            }.create())
    }
    private fun mask(a:String)=a.take(2)+"***@naver.com"
    private fun bodyText(s:BodyState)=when(s){BodyState.NOT_SELECTED->"선택 전 · 읽지 않음";BodyState.PLAIN_AVAILABLE->"선택한 일반 텍스트 처리됨";BodyState.TRUNCATED->"일부만 읽음";BodyState.MIME_UNPROCESSED->"본문 형식 미처리";BodyState.ATTACHMENTS_UNPROCESSED->"본문·첨부파일 미처리"}
    companion object {
        const val EXTRA_OWNER="kr.callbrief.navermail.OWNER"
        const val AUTH_HELP="https://help.naver.com/service/5640/contents/8584?lang=ko&osType=COMMONOS"
        const val IMAP_HELP="https://help.naver.com/service/30029/contents/21344?lang=ko&osType=COMMONOS"
    }
}
