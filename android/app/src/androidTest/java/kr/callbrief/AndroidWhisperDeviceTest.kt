package kr.callbrief

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File

@RunWith(AndroidJUnit4::class)
class AndroidWhisperDeviceTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun proof(name:String,x:JSONObject){val d=File(context.getExternalFilesDir(null),"code34-device-evidence").apply{mkdirs()};File(d,name+".json").writeText(x.toString(2))}
    @Test fun A3401RealAndroidSTTToExistingHistoryAndReopenedDuplicate() {
        val exercise="device-"+System.currentTimeMillis()
        val first=AndroidLocalSttPilot(context,exercise).use{it.cycle()};proof("A3401_INITIAL",first)
        assertEquals(2,first.getInt("actualAndroidSTTCalls"));assertEquals(2,first.getInt("historyCount"));assertEquals(8,first.getInt("newFakeNativeIDs"));assertEquals("arm64-v8a",first.getString("androidABI"))
        val jobs=first.getJSONArray("jobs");assertEquals(2,jobs.length());for(i in 0 until jobs.length())assertEquals("APPLIED_SAFE_DERIVED",jobs.getJSONObject(i).getString("state"))
        val again=AndroidLocalSttPilot(context,exercise).use{it.cycle(true)};proof("A3401_REOPENED",again)
        assertEquals(0,again.getInt("actualAndroidSTTCalls"));assertEquals(2,again.getInt("historyCount"));assertEquals(0,again.getInt("newFakeNativeIDs"))
        fun binding(x:JSONObject)=x.getJSONArray("bindings").let{a->(0 until a.length()).associate{val b=a.getJSONObject(it);b.getString("job") to b.toString()}}
        assertEquals(binding(first),binding(again));assertEquals(first.getInt("taskCount"),again.getInt("taskCount"))
    }
    @Test fun A3402WrongModelAndPreCancelledNativeRunCannotExecute() {
        val exercise="pin-"+System.currentTimeMillis();AndroidLocalSttPilot(context,exercise).use{p->
            p.engine.prepare();val file=File(p.scoped.noBackupFilesDir,"android-local-stt/ggml-tiny-q5_1.bin")
            java.io.RandomAccessFile(file,"rw").use{it.seek(0);it.writeByte(0)}
            var rejected=false;try{p.engine.verify()}catch(e:IllegalStateException){rejected=e.message=="ANDROID_STT_MODEL_PIN"};assertTrue(rejected);assertEquals(0,p.engine.calls)
            val id=AndroidWhisperNative.begin();AndroidWhisperNative.cancel(id)
            var stopped=false;try{AndroidWhisperNative.transcribe(id,file.path,FloatArray(1600),"auto")}catch(e:IllegalStateException){stopped=e.message=="ANDROID_STT_STOPPED"}finally{AndroidWhisperNative.release(id)}
            assertTrue(stopped)
            val flag=java.util.concurrent.atomic.AtomicBoolean(false)
            val closed=AndroidWhisperRunner(p.scoped,p.engine,{true},flag);closed.close();closed.cancel();assertTrue(flag.get())
            proof("A3402",JSONObject().put("wrongModelRejected",rejected).put("preCancelledNativeRejected",stopped).put("cancelAfterCloseSafe",true).put("actualSTTCalls",0).put("actualAndroid",true))
        }
    }
}
