package kr.callbrief

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.util.Log
import kr.callbrief.pipeline.CallPipelineStartup
import kr.callbrief.pipeline.CallRecordStore

/** Registers the call pipeline whenever the app process starts, not only when the main screen opens. */
class IeumApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CallPipelineStartup.init(this)
        kr.callbrief.pipeline.CallUpdate.checkSoon(this)
        // The schema check and migration run once, here, off the main thread; screens then only read (r8-1).
        Thread({ runCatching { store(this) }.onFailure { Log.w(TAG,"store not opened: ${it.javaClass.simpleName}") }
            kr.callbrief.pipeline.ContactCards.warm(this) },"ieum-store-open").start() // r9-2: cards into memory for the call card
    }
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level:Int) {
        super.onTrimMemory(level)
        if(level>=ComponentCallbacks2.TRIM_MEMORY_COMPLETE) shutdown()
    }

    companion object {
        private const val TAG="IeumApplication"
        /** Settings keys with the last measured times in ms (no content): the sealed store's load (read, decrypt, restore)
         * and one home screen read. */
        const val DB_OPEN_MS="dbOpenMs"
        const val HOME_READ_MS="homeReadMs"
        private val lock=Any()
        private var held:CallRecordStore?=null
        private var heldFor:Context?=null

        /** The one call store of this process (r8-1). Its open handle keeps the decrypted store in memory, so other opens
         * neither decrypt nor check the schema again. Callers do not close it. Every write is still sealed to disk in its
         * own transaction, exactly as before. A handle that was held for review is replaced by a fresh open. */
        internal fun store(context:Context):CallRecordStore {
            val app=context.applicationContext ?: context
            synchronized(lock) {
                held?.let { if(heldFor===app && it.usable()) return it }
                held?.let { runCatching { it.close() } };held=null;heldFor=null
                val s=CallRecordStore(CallPipelineStartup.storage(app))
                held=s;heldFor=app
                val ms=kr.co.callsummary.core.android.ProtectedDatabase.lastStateMs()
                if(ms>=0) runCatching { SecureSettings(app).update { it.put(DB_OPEN_MS,ms) } }
                return s
            }
        }
        /** Lets the process store go (memory trim, tests). The next [store] reads the sealed file again. */
        fun shutdown() { synchronized(lock) { held?.let { runCatching { it.close() } };held=null;heldFor=null };kr.callbrief.pipeline.ContactCards.shutdown() }
    }
}
