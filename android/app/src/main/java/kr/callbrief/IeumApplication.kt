package kr.callbrief

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.util.Log
import kr.callbrief.pipeline.CallPipelineStartup
import kr.callbrief.pipeline.CallRecordStore
import kr.callbrief.pipeline.PipelineSettingsKeys

/** Registers the call pipeline whenever the app process starts, not only when the main screen opens. */
class IeumApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CallPipelineStartup.init(this)
        kr.callbrief.pipeline.CallUpdate.checkSoon(this)
        // The schema check and migration run once, here, off the main thread; screens then only read (r8-1). A failure is
        // kept (STORE_OPEN_ERROR) and told once by [store] itself (r12-4).
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
        /** r12-4: the [PipelineSettingsKeys.STORE_OPEN_ERROR] value the user was last told about; the notice comes once per value. */
        const val STORE_OPEN_ERROR_TOLD="storeOpenErrorTold"
        /** The one notice (DESIGN_V4 2.4-2). */
        const val STORE_OPEN_TITLE="저장된 통화를 열지 못했어요"
        const val STORE_OPEN_TEXT="저장된 통화를 열지 못했어요. 앱을 지우지 말고 알려 주세요"
        private val lock=Any()
        private var held:CallRecordStore?=null
        private var heldFor:Context?=null
        /** Posts the notice; a test replaces it. */
        @Volatile internal var tell:(Context)->Unit={ c -> kr.callbrief.pipeline.CallDigestNotifier.post(c,kr.callbrief.pipeline.CallDigestWork.STATUS,
            kr.callbrief.pipeline.CallDigestText.Message(STORE_OPEN_TITLE,STORE_OPEN_TEXT)) }

        /** The one call store of this process (r8-1). Its open handle keeps the decrypted store in memory, so other opens
         * neither decrypt nor check the schema again. Callers do not close it. Every write is still sealed to disk in its
         * own transaction, exactly as before. A handle that was held for review is replaced by a fresh open. */
        internal fun store(context:Context):CallRecordStore {
            val app=context.applicationContext ?: context
            synchronized(lock) {
                held?.let { if(heldFor===app && it.usable()) return it }
                held?.let { runCatching { it.close() } };held=null;heldFor=null
                // What the open did with a half-written file (r9-1), for the heartbeat's dbRecovery; also when the open fails.
                val s=try { CallRecordStore(CallPipelineStartup.storage(app)) }
                    catch(e:Exception) { noteOpenError(app,e);throw e }
                    finally { noteRecovery(app) }
                held=s;heldFor=app
                noteOpened(app)
                val ms=kr.co.callsummary.core.android.ProtectedDatabase.lastStateMs()
                if(ms>=0) runCatching { SecureSettings(app).update { it.put(DB_OPEN_MS,ms) } }
                return s
            }
        }
        /** "ExceptionClass:stage", plus the store's own code for a sealed-store refusal ("CoreException:open:TEXT_STORE_..."). */
        internal fun openErrorCode(e:Throwable,stage:String?):String {
            val code=(e as? kr.co.callsummary.core.CoreException)?.message?.takeIf { it.matches(Regex("[A-Z0-9_]{3,60}")) }
            return listOfNotNull(e.javaClass.simpleName.ifEmpty { "Exception" },stage ?: CallRecordStore.STAGE_OPEN,code).joinToString(":")
        }
        /** r12-4 (DESIGN_V4 2.4-2): the error goes to settings (heartbeat storeOpenError) and the user hears about it once per
         * distinct error. Nothing about the content is kept. */
        private fun noteOpenError(app:Context,e:Exception) {
            val v=openErrorCode(e,CallRecordStore.openStage())
            Log.w(TAG,"store not opened: $v")
            var told=true
            runCatching { SecureSettings(app).update { s -> s.put(PipelineSettingsKeys.STORE_OPEN_ERROR,v)
                if(s.optString(STORE_OPEN_ERROR_TOLD)!=v) { told=false;s.put(STORE_OPEN_ERROR_TOLD,v) } } }
            if(!told) runCatching { tell(app) }
        }
        /** An open that works clears the error (the notice may come again for a later, new failure). */
        private fun noteOpened(app:Context) {
            runCatching { SecureSettings(app).update { s -> if(s.has(PipelineSettingsKeys.STORE_OPEN_ERROR)) s.remove(PipelineSettingsKeys.STORE_OPEN_ERROR)
                if(s.has(STORE_OPEN_ERROR_TOLD)) s.remove(STORE_OPEN_ERROR_TOLD) } }
        }
        private fun noteRecovery(app:Context) {
            // The call store's own code only (r12-4): the contact-card file's recovery is not the call store's.
            val code=kr.co.callsummary.core.android.ProtectedDatabase.takeRecovery(GeneralLocalStore.DATABASE)?.takeIf { it.isNotEmpty() } ?: return
            runCatching { SecureSettings(app).update { it.put(kr.callbrief.pipeline.PhoneStatus.DB_RECOVERY_LAST,code) } }
        }
        /** Lets the process store go (memory trim, tests). The next [store] reads the sealed file again. */
        fun shutdown() { synchronized(lock) { held?.let { runCatching { it.close() } };held=null;heldFor=null };kr.callbrief.pipeline.ContactCards.shutdown() }
    }
}
