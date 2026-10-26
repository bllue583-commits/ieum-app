package kr.callbrief

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Separate public-only sandbox. Running this is an Android JNI claim; compiling it is not. */
@RunWith(AndroidJUnit4::class)
class UnclassifiedWhisperDeviceTest {
    @Test fun actualUnclassifiedAudioThenExplicitFixtureBindingAndReopenedHistory() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val args=InstrumentationRegistry.getArguments()
        val exercise=args.getString("unclassifiedExercise") ?: "unclassified-"+System.currentTimeMillis()
        val replay=args.getString("unclassifiedReplayOnly")=="true"
        val first=AndroidLocalSttPilot(context,exercise).use{it.cycleUnclassified()}
        assertEquals(if(replay)0 else 2,first.getInt("actualAndroidSTTCalls"));assertEquals(2,first.getInt("historyCount"));assertEquals(if(replay)0 else 8,first.getInt("newFakeNativeIDs"))
        val stages=first.getJSONArray("unclassifiedStages");assertEquals(2,stages.length())
        if(!replay)for(i in 0 until stages.length()) {
            assertEquals("RESULT_READY",stages.getJSONObject(i).getString("state"))
            val before=stages.getJSONObject(i).getJSONObject("contextBeforeBinding")
            for(k in listOf("personId","caseId","taskId","originalTime"))assertTrue(before.isNull(k))
        }
        val again=AndroidLocalSttPilot(context,exercise).use{it.cycleUnclassified(true)}
        assertEquals(0,again.getInt("actualAndroidSTTCalls"));assertEquals(2,again.getInt("historyCount"));assertEquals(0,again.getInt("newFakeNativeIDs"))
        assertEquals(first.getJSONArray("bindings").toString(),again.getJSONArray("bindings").toString())
        val folder=File(context.getExternalFilesDir(null),"unclassified-device-evidence").apply{mkdirs()}
        File(folder,if(replay)"PROCESS_REOPENED.json" else "INITIAL.json").writeText(JSONObject().put("first",first).put("reopened",again).toString(2))
    }
}
