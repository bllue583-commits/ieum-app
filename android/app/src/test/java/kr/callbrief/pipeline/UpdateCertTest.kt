package kr.callbrief.pipeline

import kr.callbrief.ProtectedTextHostTestBase
import kr.callbrief.SecureSettings
import kr.callbrief.ShadowTextAtomicFile
import kr.callbrief.ShadowTextKeys
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File

/** v3.8 r18-lock (DESIGN_V7 4.2-2): the update's signing certificate is compared with this app's before it is installed.
 * Same or an ancestor: installed. Read and unrelated: not installed, `update.error` = CERT. Not readable: let through and
 * counted (`update.certUnknown`), because a wrong "no" would leave the phone without updates for good. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextKeys.ExistingApprovedSettings::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class UpdateCertTest:ProtectedTextHostTestBase() {
    private val app get()=RuntimeEnvironment.getApplication()
    private val old="a".repeat(64);private val new="b".repeat(64);private val other="c".repeat(64)
    private val next=kr.callbrief.BuildConfig.VERSION_CODE+1
    private val apk=ByteArray(20_000) { (it%251).toByte() }
    private val sha=java.security.MessageDigest.getInstance("SHA-256").digest(apk).joinToString(""){"%02x".format(it)}
    private fun s():JSONObject=SecureSettings(app).snapshot()
    @After fun reset() { UpdateCert.readHook=null }

    private inner class Store:CallInboxStore {
        override fun put(path:String,json:String) {}
        override fun list(dir:String)=emptyList<Pair<String,String>>()
        override fun delete(path:String,sha:String) {}
        override fun get(path:String)=JSONObject().put("versionCode",next).put("versionName","v3.8").put("apk","releases/assets/1")
            .put("sha256",sha).put("size",apk.size).toString()
        override fun download(path:String,to:File,progress:(Long,Long)->Unit) { to.writeBytes(apk);progress(apk.size.toLong(),apk.size.toLong()) }
    }
    /** This app signed by [own]; any APK file by [apkCurrent] with the lineage [history] (null: unreadable). */
    private fun phone(own:String?,apkCurrent:String?,history:List<String>) {
        UpdateCert.readHook={ _,f -> if(f==null) own?.let { UpdateCert.Certs(setOf(it),listOf(it)) } else apkCurrent?.let { UpdateCert.Certs(setOf(it),history) } }
    }

    @Test fun verdictIsSameAncestorUnrelatedOrUnknown() {
        val v=UpdateCert::verdict
        assertEquals(UpdateCert.Verdict.SAME,v(setOf(old),UpdateCert.Certs(setOf(old),listOf(old))))
        // The first rotated release on a phone still on the old key: the old key is the lineage's first certificate.
        assertEquals(UpdateCert.Verdict.ANCESTOR,v(setOf(old),UpdateCert.Certs(setOf(new),listOf(old,new))))
        // After it: the phone has the new key, the APK the same lineage.
        assertEquals(UpdateCert.Verdict.SAME,v(setOf(new),UpdateCert.Certs(setOf(new),listOf(old,new))))
        assertEquals(UpdateCert.Verdict.UNRELATED,v(setOf(old),UpdateCert.Certs(setOf(other),listOf(other))))
        assertEquals(UpdateCert.Verdict.UNRELATED,v(setOf(new),UpdateCert.Certs(setOf(other),listOf(old,other))))
        // Reading failed somewhere: never a "no".
        assertEquals(UpdateCert.Verdict.UNKNOWN,v(null,UpdateCert.Certs(setOf(other),listOf(other))))
        assertEquals(UpdateCert.Verdict.UNKNOWN,v(setOf(old),null))
        assertEquals(UpdateCert.Verdict.UNKNOWN,v(emptySet(),UpdateCert.Certs(setOf(other),listOf(other))))
        assertEquals(UpdateCert.Verdict.UNKNOWN,v(setOf(old),UpdateCert.Certs(emptySet(),emptyList())))
        assertEquals(UpdateCert.Verdict.UNKNOWN,v(setOf(old),UpdateCert.Certs(setOf(other),emptyList())))
        assertEquals(UpdateCert.Verdict.UNKNOWN,v(setOf(old),UpdateCert.Certs(setOf(other,new),emptyList())))
        for(x in UpdateCert.Verdict.values()) assertEquals(x!=UpdateCert.Verdict.UNRELATED,UpdateCert.allows(x))
    }

    @Test fun workingOnlyWhenTheSelfCheckReadEverything() {
        assertTrue(UpdateCert.working("SAME"));assertTrue(UpdateCert.working("ANCESTOR,SAME"))
        assertFalse(UpdateCert.working("UNKNOWN"));assertFalse(UpdateCert.working("ANCESTOR,UNKNOWN"));assertFalse(UpdateCert.working(""))
        assertFalse(UpdateCert.working(null));assertFalse(UpdateCert.working("UNRELATED"))
    }

    @Test fun anUnrelatedApkIsNotKeptAndSaysCert() {
        shadowOf(app.packageManager).setCanRequestPackageInstalls(true)
        phone(old,other,listOf(other))
        try { CallUpdate.check(app,Store());fail("an unrelated certificate must stop it") } catch(e:IllegalStateException) { assertEquals("CERT",e.message) }
        assertNull("not ready, not installed",CallUpdate.ready(app))
        assertFalse(CallUpdate.file(app).exists())
        assertEquals("CERT",s().optString(PipelineSettingsKeys.UPDATE_ERROR_CODE))
        assertEquals(0,app.packageManager.packageInstaller.allSessions.size)
    }

    @Test fun ancestorAndUnreadableGoThroughTheUnreadableCounted() {
        shadowOf(app.packageManager).setCanRequestPackageInstalls(true)
        phone(old,new,listOf(old,new))
        assertTrue(CallUpdate.check(app,Store()))
        assertNotNull(CallUpdate.ready(app));assertTrue(CallUpdate.verified(app))
        assertEquals(0L,UpdateCert.unknownCount(s()))
        CallUpdate.discard(app)
        phone(old,null,emptyList())
        assertTrue(CallUpdate.check(app,Store()))
        assertNotNull(CallUpdate.ready(app))
        assertEquals(1L,UpdateCert.unknownCount(s()))
        assertTrue("an unreadable file is still installed (Android's installer decides)",CallUpdate.verified(app))
        assertEquals("verified() does not count again",1L,UpdateCert.unknownCount(s()))
    }

    @Test fun aReadyFileThatTurnsOutUnrelatedIsNotVerified() {
        shadowOf(app.packageManager).setCanRequestPackageInstalls(true)
        phone(old,old,listOf(old))
        assertTrue(CallUpdate.check(app,Store()))
        phone(old,other,listOf(other))
        assertFalse(CallUpdate.verified(app))
        assertEquals("CERT",s().optString(PipelineSettingsKeys.UPDATE_ERROR_CODE))
    }

    @Test fun selfCheckGoesToTheHeartbeat() {
        phone(old,old,listOf(old))
        assertEquals("SAME",UpdateCert.selfCheck(app))
        assertEquals(true,UpdateCert.heartbeat(s()).get("certCheck"))
        // After a rotation: installed lineage old -> new, the installed APK carries it.
        UpdateCert.readHook={ _,f -> if(f==null) UpdateCert.Certs(setOf(new),listOf(old,new)) else UpdateCert.Certs(setOf(new),listOf(old,new)) }
        assertEquals("ANCESTOR,SAME",UpdateCert.selfCheck(app))
        assertEquals(true,UpdateCert.heartbeat(s()).get("certCheck"))
        UpdateCert.readHook={ _,_ -> null }
        assertEquals("UNKNOWN",UpdateCert.selfCheck(app))
        val h=PhoneStatus.security(s())
        assertEquals(false,h.get("certCheck"));assertEquals("UNKNOWN",h.get("certSelf"))
        assertTrue(h.has("saltedHash"));assertTrue(h.has("legacyHashes"))
    }
}
