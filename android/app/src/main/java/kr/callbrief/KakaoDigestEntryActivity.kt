package kr.callbrief

import android.os.Bundle
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import kr.callbrief.kakao.android.KakaoConfiguration
import kr.callbrief.kakao.android.KakaoDigestLinkRouter
import java.time.LocalDate

/** Kakao link is navigation only. No coordinator.tick, scheduling, login, grant,
 * analysis, send action, or owner selection is invoked by incoming intent. */
internal open class KakaoDigestEntryActivity:ComponentActivity() {
    private var selected:DailyReportRuntime.Scope?=null
    protected open fun configuration()=KakaoConfiguration.fromResources(this)
    protected open fun ownerReady():Boolean {
        if(GoogleConnectionSession.get(this).ownerBinding().isBlank())return false
        selected=DailyReportRuntime.scope(this);return true
    }
    protected open fun ownerStillCurrent()=selected?.let{DailyReportRuntime.current(this,it)}==true
    protected open suspend fun storedReports(date:LocalDate):List<Pair<String,String>> = withContext(Dispatchers.IO) {
        val scope=checkNotNull(selected);check(ownerStillCurrent())
        KakaoStoredReportReader.read(scope.context,scope.profile,date)
    }
    override fun onCreate(saved:Bundle?) {
        super.onCreate(saved);window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val date=KakaoDigestLinkRouter.reportDate(intent.data,configuration())
        if(date==null || !runCatching{ownerReady()}.getOrDefault(false)){finish();return}
        val body=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(24,24,24,24)}
        val scroll=ScrollView(this).apply{addView(body)};setContentView(scroll)
        fun label(value:String)=TextView(this).apply{text=value;textSize=18f;isSaveEnabled=false;isSaveFromParentEnabled=false;setPadding(0,16,0,16)}
        body.addView(label("$date · 현재 선택 계정의 앱 보관 보고"))
        body.addView(label("이 링크는 연결·발송을 실행하지 않아요. 원래 메시지의 계정을 자동 선택하지 않아요."))
        body.addView(Button(this).apply{setText(android.R.string.ok);textSize=18f;minHeight=(56*resources.displayMetrics.density).toInt();setOnClickListener{finish()}})
        lifecycleScope.launch{
            val result=runCatching{storedReports(date)};ensureActive()
            if(isFinishing || isDestroyed || !ownerStillCurrent()){finish();return@launch}
            result.onSuccess{rows ->if(rows.isEmpty())body.addView(label("현재 계정에 해당 날짜의 보관 보고가 없어요.")) else rows.forEach{body.addView(label(it.first));body.addView(label(it.second).apply{tag="kakao_stored_report"})}}
                .onFailure{body.addView(label("보관 보고를 확인하지 못했어요. 원본과 발송 기록은 유지해요."))}
        }
    }
    override fun onResume(){super.onResume();if(selected!=null && !ownerStillCurrent())finish()}
}
