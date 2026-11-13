package kr.callbrief.pipeline

import android.os.Build
import android.util.Log

/** v3.8 r18-lock (coordinator 11/13): the update smoke test and device-test run the CI build, real mailbox token inside, on an
 * Android emulator. Its heartbeats (11/13 01:57, 07:01, 09:26: builds 1054-1056, KakaoTalk unlinked, dbPercent 0) landed in
 * the mailbox's status/phone.json between the phone's own and blurred the watchdog and the scorecard history.
 * On an emulator every mailbox write ([CallInboxStore.put], [CallInboxStore.replace], [CallInboxStore.delete]) is dropped;
 * reads (version.json, the update APK, results, reports) stay, so the update path the smoke test checks is unchanged.
 * No test writes the mailbox through the app on purpose (inbox-e2e writes from its own CI scripts), so nothing moves to
 * another path. */
internal object EmulatorInbox {
    const val LOG="IEUM_EMULATOR"
    /** The pure decision from the build properties. Matches the SDK emulator images (goldfish, ranchu, sdk_gphone*,
     * generic fingerprints, "Android SDK built for x86", Genymotion "vbox86"), never a Samsung or other retail phone. */
    fun isEmulator(fingerprint:String?,hardware:String?,model:String?,product:String?,manufacturer:String?):Boolean {
        val fp=fingerprint.orEmpty().lowercase();val hw=hardware.orEmpty().lowercase();val md=model.orEmpty().lowercase()
        val pr=product.orEmpty().lowercase();val mf=manufacturer.orEmpty().lowercase()
        return fp.startsWith("generic") || fp.startsWith("google/sdk_gphone") || fp.contains("/sdk_gphone")
            || hw=="goldfish" || hw=="ranchu" || hw.startsWith("vbox86")
            || pr.startsWith("sdk_gphone") || pr.startsWith("sdk_") || pr=="sdk" || pr.startsWith("emulator") || pr.contains("vbox86")
            || md.contains("android sdk built for") || md.startsWith("sdk_gphone") || md=="emulator"
            || mf=="genymotion"
    }
    val here:Boolean by lazy { runCatching { isEmulator(Build.FINGERPRINT,Build.HARDWARE,Build.MODEL,Build.PRODUCT,Build.MANUFACTURER) }.getOrDefault(false) }
    /** [store] as it is, or on an emulator the same store with its writes dropped. */
    fun guard(store:CallInboxStore,emulator:Boolean=here):CallInboxStore = if(!emulator) store else ReadOnly(store)
    private class ReadOnly(private val inner:CallInboxStore):CallInboxStore {
        override fun put(path:String,json:String) { Log.i(LOG,"mailbox write skipped on an emulator: $path") }
        override fun replace(path:String,json:String) { Log.i(LOG,"mailbox write skipped on an emulator: $path") }
        override fun delete(path:String,sha:String) { Log.i(LOG,"mailbox delete skipped on an emulator: $path") }
        override fun list(dir:String)=inner.list(dir)
        override fun get(path:String)=inner.get(path)
        override fun download(path:String,to:java.io.File,progress:(Long,Long)->Unit)=inner.download(path,to,progress)
    }
}
