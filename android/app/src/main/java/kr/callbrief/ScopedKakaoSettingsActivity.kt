package kr.callbrief

import android.os.Bundle
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.Button
import android.widget.TextView
import android.widget.ScrollView
import androidx.activity.ComponentActivity
import kr.callbrief.kakao.android.KakaoSettingsPanel
import kr.callbrief.kakao.android.KakaoConfiguration
import kr.callbrief.kakao.android.KakaoReportClient

/** Registration/real login/send stay off. Enabling them requires a separate owner coordinator. */
internal class ScopedKakaoSettingsActivity : ComponentActivity() {
    private var epoch=-1L
    private var owner=""
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState);window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL
            setOnApplyWindowInsetsListener { v,i -> v.setPadding(24,i.systemWindowInsetTop+16,24,i.systemWindowInsetBottom+16);i }
        };setContentView(root)
        val scroll=ScrollView(this);val body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL };scroll.addView(body)
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        try {
            val s=GoogleConnectionSession.get(this);epoch=s.epoch();owner=s.ownerBinding()
            val scoped=if(owner.isBlank())this else AccountStorageContext(this,owner)
            val config=KakaoConfiguration.fromResources(this)
            check(owner.isNotBlank()) { "APP_OWNER_REQUIRED" }
            // Pass the scoped client; the SDK global session cannot silently cross owners.
            val client=KakaoReportClient.get(scoped)
            body.addView(KakaoSettingsPanel.create(this,ScopedNaverMailSettingsActivity.ownerNamespace(owner),client,{runCatching{val now=GoogleConnectionSession.get(this);!isFinishing && !isDestroyed && now.epoch()==epoch && now.ownerBinding()==owner}.getOrDefault(false)}))
        } catch(_:Exception) { body.addView(TextView(this).apply { text="카카오 연결 설정 확인 필요 · 실제 연결·발송은 실행하지 않았어요.";textSize=18f }) }
        root.addView(Button(this).apply { text="설정으로 돌아가기";textSize=18f;minHeight=(56*resources.displayMetrics.density).toInt();setOnClickListener { finish() } })
    }
    override fun onResume() { super.onResume();if(!runCatching { val s=GoogleConnectionSession.get(this);s.epoch()==epoch && s.ownerBinding()==owner }.getOrDefault(false))finish() }
}
