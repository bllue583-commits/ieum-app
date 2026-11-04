package kr.callbrief

import android.app.Application
import kr.callbrief.pipeline.CallPipelineStartup

/** Registers the call pipeline whenever the app process starts, not only when the main screen opens. */
class IeumApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CallPipelineStartup.init(this)
        kr.callbrief.pipeline.CallUpdate.checkSoon(this)
    }
}
