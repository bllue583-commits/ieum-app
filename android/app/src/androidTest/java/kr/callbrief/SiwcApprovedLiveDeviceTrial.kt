package kr.callbrief

import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kr.callagent.drive.StrictJson
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Never part of an ordinary regression run. This opt-in driver runs in the app process;
 * credentials stay in its protected vault and are never returned to adb or a host script. */
@RunWith(AndroidJUnit4::class)
class SiwcApprovedLiveDeviceTrial {
    private val app get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun save(action:String,value:JSONObject) {
        val dir=File(app.getExternalFilesDir(null),"siwc-approved-live-evidence").apply{mkdirs()}
        File(dir,action+".json").writeText(value.put("androidProcessId",android.os.Process.myPid()).toString(2))
    }
    @Test fun explicitlyApprovedPersonalPublicTrial() {
        val args=InstrumentationRegistry.getArguments();val action=args.getString("trialAction").orEmpty()
        assumeTrue(BuildConfig.DEBUG && BuildConfig.SIWC_PERSONAL_TRIAL_APPROVED && args.getString("trialApproval")=="PERSONAL_PUBLIC_20261026")
        require(action in setOf("LOGIN","AUTH_STATUS","BROWSER_SCREEN","PREPARE","PLAN","ANALYSE","CONTEXT_PLAN","CONTEXT","RECEIVE","OBSERVE"))
        if(action=="BROWSER_SCREEN") {
            val root=InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
            check(root!=null && root.packageName?.toString()=="org.mozilla.firefox"){"SIWC_FIREFOX_NOT_FOREGROUND"}
            val labels=mutableListOf<String>()
            fun scan(node:android.view.accessibility.AccessibilityNodeInfo) {
                if(!node.isPassword && !node.isEditable)for(value in listOf(node.text,node.contentDescription)) {
                    val raw=value?.toString().orEmpty()
                    if(raw.length in 1..1024 && Regex("(?i)sign.?in|log.?in|continue|openai|chatgpt|allow|authorize|mozilla|firefox|privacy|terms|welcome|default|로그인|동의|계정|이메일|시작|개인정보|사용량").containsMatchIn(raw)) {
                        val safe=raw.substringBefore('?').replace(Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"),"[EMAIL_SHOWN]").replace(Regex("[A-Za-z0-9_-]{32,}"),"[OPAQUE_VALUE]")
                        labels.add(safe)
                    }
                }
                for(i in 0 until node.childCount)node.getChild(i)?.let(::scan)
            }
            scan(root);save(action,JSONObject().put("package","org.mozilla.firefox").put("nonEditableLabels",org.json.JSONArray(labels.distinct())));return
        }
        if(action=="LOGIN") {
            ActivityScenario.launch(SiwcRuntimeActivity::class.java).use{s->
                var model:SiwcLoginModel?=null
                val ready=System.currentTimeMillis()+10000
                while(System.currentTimeMillis()<ready && model?.view?.value?.ready!=true){s.onActivity{model=SiwcRuntimeActivity::class.java.getDeclaredField("model").apply{isAccessible=true}.get(it) as SiwcLoginModel};Thread.sleep(50)}
                assertTrue(model!!.view.value.ready)
                if(!model!!.view.value.signedIn){s.onActivity{it.window.decorView.findViewWithTag<Button>("siwc_continue").performClick()}
                    println("SIWC_OFFICIAL_LOGIN_STARTED_WAITING_FOR_USER")
                    val deadline=System.currentTimeMillis()+330000
                    while(System.currentTimeMillis()<deadline && model!!.view.value.busy)Thread.sleep(250)
                }
                save(action,JSONObject().put("status",model!!.view.value.status).put("signedIn",model!!.view.value.signedIn).put("returnRequested",model!!.view.value.returnRequested))
                assertTrue("Real official identity not confirmed; do not automatically restart authorization",model!!.view.value.signedIn)
            };return
        }
        if(action in setOf("AUTH_STATUS","OBSERVE")) {
            SiwcSessionStore(app,SiwcAuthHttp()).use{sessions->SiwcTrialBudget(app).use{budget->
                save(action,JSONObject().put("auth",JSONObject(sessions.audit())).put("budget",JSONObject(budget.snapshot())))
            }};return
        }
        AndroidLocalSttPilot(app,"siwc-live-trial-v1").use{p->
            if(action=="PREPARE")save(action,JSONObject().put("analysed",p.integratedAnalyse()).put("bound",p.integratedBind(true)).put("actualAstraCalls",0))
            else save(action,p.siwcLiveTrial(action,args.getString("trialKey").orEmpty()))
        }
    }
}
