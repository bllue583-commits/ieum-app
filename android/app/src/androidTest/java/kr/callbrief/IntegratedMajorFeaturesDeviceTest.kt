package kr.callbrief

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kr.co.callsummary.core.android.AndroidSqlPort
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Actual bundled public JNI + protected context/workflow/report stores. The test explicitly
 * performs the same two review commands as the screen; model output never supplies approval. */
@RunWith(AndroidJUnit4::class)
class IntegratedMajorFeaturesDeviceTest {
    @Test fun publicJniCandidateUserReviewSnapshotAndDryRunOutbox()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext;val args=InstrumentationRegistry.getArguments()
        val exercise=args.getString("integrationExercise") ?: "integrated-"+System.currentTimeMillis();val replay=args.getString("integrationReplayOnly")=="true"
        AndroidLocalSttPilot(context,exercise).use{p->
            val analysed=p.integratedAnalyse();assertEquals(if(replay)0 else 2,analysed.getInt("actualAndroidSTTCalls"));assertFalse(analysed.getJSONObject("candidate").getBoolean("canonicalWriteAllowed"))
            if(!replay){assertEquals(0,analysed.getInt("userReviewCount"));assertTrue(analysed.getJSONObject("candidate").getJSONArray("holds").length()>0)}
            val bound=p.integratedBind(true);assertTrue(bound.getJSONObject("candidate").getJSONArray("items").length()>0)
            val digest=IntegratedContextStore.candidateDigest(bound.getJSONObject("candidate"))
            if(!replay) {
                var denied=false;try{p.integratedReviewAndReport(digest,false)}catch(_:IllegalStateException){denied=true};assertTrue(denied)
                AndroidSqlPort(p.scoped,GeneralLocalStore.DATABASE,p.scoped.assets.open("call_core_schema.sql")).use{db->assertEquals(0L,db.query("SELECT count(*) n FROM workflow_reviewed_summaries").single()["n"])}
            }
            val result=p.integratedReviewAndReport(digest,true);assertEquals(2,result.getInt("reviewCount"));assertEquals(2,result.getJSONArray("reports").length());assertEquals("COMPLETE",result.getString("syncState"));assertTrue(result.getString("durableCursor").startsWith("local-reviewed-v1:"))
            val out=result.getJSONArray("outbox");assertEquals(2,out.length());for(i in 0 until out.length())assertEquals("DRY_RUN_RECORDED",out.getJSONObject(i).getString("state"))
            assertEquals(0,result.getInt("externalSends"));assertEquals(if(replay)0 else 2,result.getInt("actualAndroidSTTCalls"))
            val dir=File(context.getExternalFilesDir(null),"integration-device-evidence").apply{mkdirs()}
            File(dir,if(replay)"PROCESS_REOPENED.json" else "INITIAL.json").writeText(JSONObject().put("analysed",analysed).put("bound",bound).put("final",result).toString(2))
        }
    }
}
