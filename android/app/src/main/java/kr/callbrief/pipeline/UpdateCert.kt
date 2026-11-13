package kr.callbrief.pipeline

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** v3.8 r18-lock (DESIGN_V7 4.2-2): before a downloaded update is installed, its signing certificate is compared with this
 * app's own. Allowed: the same certificate, or an APK whose key lineage (APK Signature Scheme v3, the key rotation of
 * tools/rotate_signing_key.sh) holds this app's certificate as an ancestor. Not allowed: the certificates were read and
 * are unrelated ([Verdict.UNRELATED], heartbeat `update.error` = CERT).
 *
 * A wrong "no" here would leave the phone without updates for good, so anything that could not be read (Android 8, an
 * exception, a null, an empty history) is [Verdict.UNKNOWN]: let through and counted (`update.certUnknown`). Android's own
 * installer refuses a certificate mismatch anyway; this is one more line of defence, never the only one.
 *
 * [selfCheck] reads this app's own installed APK the same way at every start, so the heartbeat (`security.certCheck`) and
 * the update smoke test (logcat tag [LOG]) show that the reading works on this Android, and, after a rotation, that the
 * lineage of the installed APK holds the old certificate (the verdict a phone still on the old key would reach). */
internal object UpdateCert {
    enum class Verdict { SAME, ANCESTOR, UNRELATED, UNKNOWN }
    /** Certificates as SHA-256 lower-case hex. [current]: the signer(s) of the APK now; [history]: its lineage, oldest first
     * where Android says so (the order is not used), with the current one in it. */
    data class Certs(val current:Set<String>,val history:List<String>)

    const val LOG="IEUM_CERT"
    /** Settings keys: downloads let through unread (count), and the start-up self check ("SAME", "ANCESTOR,SAME", "UNKNOWN"). */
    const val UNKNOWN_COUNT="updateCertUnknown";const val SELF="updateCertSelf";const val SELF_AT_MS="updateCertSelfAtMs"

    /** The decision, a pure function. [own]: this app's current signer(s); [apk]: the downloaded APK's; null = not read. */
    fun verdict(own:Set<String>?,apk:Certs?):Verdict {
        if(own.isNullOrEmpty() || apk==null || apk.current.isEmpty()) return Verdict.UNKNOWN
        if(own==apk.current) return Verdict.SAME
        // Several signers (never ours) carry no history: nothing can be said.
        if(own.size!=1 || apk.current.size!=1 || apk.history.isEmpty()) return Verdict.UNKNOWN
        return if(own.single() in apk.history) Verdict.ANCESTOR else Verdict.UNRELATED
    }
    /** Only a certain mismatch blocks. */
    fun allows(v:Verdict)=v!=Verdict.UNRELATED

    fun sha256(s:Signature):String=MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    @Suppress("DEPRECATION")
    private fun certs(info:android.content.pm.PackageInfo?):Certs? {
        if(Build.VERSION.SDK_INT<28) return null
        val si=info?.signingInfo ?: return null
        val current=(si.apkContentsSigners ?: return null).map(::sha256).toSet()
        val history=if(si.hasMultipleSigners()) emptyList() else si.signingCertificateHistory?.map(::sha256).orEmpty()
        return Certs(current,history)
    }
    /** This app as installed (null below Android 9 or when it cannot be read). */
    @Suppress("DEPRECATION")
    internal fun installed(c:Context):Certs? {
        if(Build.VERSION.SDK_INT<28) return null
        return runCatching { certs(c.packageManager.getPackageInfo(c.packageName,PackageManager.GET_SIGNING_CERTIFICATES)) }.getOrNull()
    }
    /** An APK file (null below Android 9 or when it cannot be read). */
    @Suppress("DEPRECATION")
    internal fun archive(c:Context,f:File):Certs? {
        if(Build.VERSION.SDK_INT<28) return null
        return runCatching { certs(c.packageManager.getPackageArchiveInfo(f.absolutePath,PackageManager.GET_SIGNING_CERTIFICATES)) }.getOrNull()
    }
    /** For tests: replaces the reading of this app and of an APK file. */
    @Volatile internal var readHook:((Context,File?)->Certs?)?=null
    private fun own(c:Context):Certs? { val h=readHook;return if(h!=null) h(c,null) else installed(c) }
    private fun apk(c:Context,f:File):Certs? { val h=readHook;return if(h!=null) h(c,f) else archive(c,f) }

    /** The verdict for the downloaded [f]. [count]: an unread one adds one to [UNKNOWN_COUNT] (the download, not every look). */
    fun check(c:Context,f:File,count:Boolean):Verdict {
        val v=runCatching { verdict(own(c)?.current,apk(c,f)) }.getOrDefault(Verdict.UNKNOWN)
        if(v==Verdict.UNKNOWN && count) runCatching { settings(c).update { it.put(UNKNOWN_COUNT,it.optLong(UNKNOWN_COUNT,0)+1) } }
        if(v!=Verdict.SAME) Log.i(LOG,"update apk verdict=$v")
        return v
    }

    /** At every app start: this app's installed certificates against its own APK file, one verdict per certificate of the
     * installed lineage (oldest first as Android gives them). "SAME" unrotated; "ANCESTOR,SAME" after a rotation (the old
     * key would accept this APK); "UNKNOWN" when it cannot be read here. Kept in [SELF] and written to logcat. */
    fun selfCheck(c:Context):String {
        val app=c.applicationContext
        val result=runCatching {
            val mine=own(app);val file=File(app.applicationInfo.sourceDir);val apk=apk(app,file)
            val list=if(mine==null) listOf(Verdict.UNKNOWN) else (mine.history.ifEmpty { mine.current.toList() }).map { verdict(setOf(it),apk) }
            Log.i(LOG,"self installed=${mine?.history?.size ?: -1} archive=${apk?.history?.size ?: -1} verdicts=${list.joinToString(",")}")
            list.joinToString(",")
        }.getOrElse { Log.i(LOG,"self verdicts=UNKNOWN (${it.javaClass.simpleName})");Verdict.UNKNOWN.name }
        runCatching { val s=settings(app);if(s.snapshot().optString(SELF)!=result) s.update { it.put(SELF,result).put(SELF_AT_MS,System.currentTimeMillis()) } }
        return result
    }
    /** The check works on this phone: Android 9+ and the self check read every certificate, none unrelated. */
    fun working(self:String?):Boolean = !self.isNullOrBlank() && self.split(',').all { it==Verdict.SAME.name || it==Verdict.ANCESTOR.name }
    /** Heartbeat `security` (with [kr.callbrief.LocalPrivacy.heartbeat]'s keys added by the caller): yes/no and short codes. */
    fun heartbeat(s:JSONObject):JSONObject = JSONObject().put("certCheck",working(s.optString(SELF)))
        .put("certSelf",s.optString(SELF).takeIf { it.isNotBlank() } ?: JSONObject.NULL)
    fun unknownCount(s:JSONObject)=s.optLong(UNKNOWN_COUNT,0)
    private fun settings(c:Context)=kr.callbrief.SecureSettings(CallPipelineStartup.storage(c))
}
