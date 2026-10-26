package kr.callbrief.kakao.android

import android.content.Context
import android.net.Uri
import com.kakao.sdk.auth.model.OAuthToken
import kr.callbrief.kakao.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant
import java.util.Date
import java.util.UUID
import java.util.concurrent.Executors
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class AndroidStorageTests {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private class TestKeys : PrivateKakaoStore.KeyAccess {
        var key: SecretKey?=null; var creations=0
        override fun existing()=key
        override fun createForEmptyStore(): SecretKey { creations++; return SecretKeySpec(ByteArray(32) { 7 },"AES").also { key=it } }
    }
    private fun namespace()="synthetic_"+UUID.randomUUID().toString().replace("-","")
    private fun hash(s: String)=DeliveryCoordinator.sha256(s)
    private fun token(a: String="SYNTHETIC_ACCESS_TOKEN",r: String="SYNTHETIC_REFRESH_TOKEN")=
        OAuthToken(a,Date.from(Instant.now().plusSeconds(3600)),r,Date.from(Instant.now().plusSeconds(7200)),null,listOf("talk_message"))
    @Test fun defaultConfigHasNoCredentialsAndCannotLogin() {
        val c=KakaoConfiguration.fromResources(context)
        assertFalse(c.canLogin); assertFalse(c.liveSendEnabled); assertEquals("",c.nativeAppKey); assertEquals(0,c.developerAppId)
    }
    @Test fun tokenRotationIsEncryptedAndSurvivesReopen() {
        val n=namespace(); val keys=TestKeys(); val store=PrivateKakaoStore(context,n,keys)
        val manager=EncryptedKakaoTokenManager(store)
        manager.setToken(token()); manager.setToken(token("SYNTHETIC_ACCESS_ROTATED","SYNTHETIC_REFRESH_ROTATED"))
        val reread=EncryptedKakaoTokenManager(PrivateKakaoStore(context,n,keys)).getToken()!!
        assertEquals("SYNTHETIC_REFRESH_ROTATED",reread.refreshToken)
        assertEquals("SYNTHETIC_ACCESS_ROTATED",reread.accessToken)
        val disk=File(context.noBackupFilesDir,"kakao-report-v1/$n.sealed").readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(disk.contains("SYNTHETIC")); assertEquals(1,keys.creations)
    }
    @Test fun missingKeyNeverRegeneratesOrOverwritesStoredState() {
        val n=namespace(); val keys=TestKeys(); val store=PrivateKakaoStore(context,n,keys)
        EncryptedKakaoTokenManager(store).setToken(token())
        val file=File(context.noBackupFilesDir,"kakao-report-v1/$n.sealed"); val before=file.readBytes(); keys.key=null
        try { store.update { it.put("anything","new") }; fail("must fail closed") } catch(expected: IllegalStateException) { }
        assertArrayEquals(before,file.readBytes()); assertEquals(1,keys.creations)
    }
    @Test fun corruptedStateCannotSilentlyResetConsent() {
        val n=namespace(); val keys=TestKeys(); val store=PrivateKakaoStore(context,n,keys)
        store.update { it.put("synthetic",true) }
        val file=File(context.noBackupFilesDir,"kakao-report-v1/$n.sealed")
        val bytes=file.readBytes(); bytes[bytes.lastIndex]=(bytes.last().toInt() xor 1).toByte(); file.writeBytes(bytes)
        try { store.snapshot(); fail("must reject corruption") } catch(expected: Exception) { }
        assertArrayEquals(bytes,file.readBytes()); assertEquals(1,keys.creations)
    }
    @Test fun consentIsExplicitAccountAndProfileBoundAndClearedOnNewConnection() {
        val store=PrivateKakaoStore(context,namespace(),TestKeys()); val consent=LocalReportConsent(store)
        val s=VerifiedSession("1001",12345,"SYNTHETIC_ACCESS_TOKEN",Instant.now().plusSeconds(3600),true)
        assertFalse(consent.snapshot().enabled)
        consent.beginConnection(); consent.connected("synthetic_user_001",s)
        try { consent.enable("synthetic_user_002",s); fail("wrong profile") } catch(expected: IllegalStateException) { }
        consent.enable("synthetic_user_001",s)
        assertTrue(consent.snapshot().enabled); assertEquals("1001",consent.snapshot().subject)
        consent.beginConnection(); assertFalse(consent.snapshot().enabled)
    }
    @Test fun inFlightClaimSurvivesProcessReopenAndNeverExpires() {
        val key=hash(namespace()); val payload=hash("synthetic payload")
        SqliteDeliveryLedger(context).use { assertTrue(it.claim(key,payload,Instant.now()).acquired) }
        SqliteDeliveryLedger(context).use {
            val later=it.claim(key,payload,Instant.now().plusSeconds(86400*300L))
            assertFalse(later.acquired); assertEquals(DeliveryResult.Status.UNKNOWN,later.result.status)
        }
    }
    @Test fun sentRecordBlocksEditedPayloadAndTestSuccessIsSeparate() {
        val key=hash(namespace()); val testKey=hash(namespace()); val payload=hash("synthetic payload")
        SqliteDeliveryLedger(context).use {
            assertTrue(it.claim(key,payload,Instant.now()).acquired)
            it.complete(key,DeliveryResult(DeliveryResult.Status.SENT)) // fixture only, no transport/login is called
            assertEquals(DeliveryResult.Status.ALREADY_SENT,it.claim(key,hash("edited synthetic payload"),Instant.now()).result.status)
            assertTrue(it.claim(testKey,payload,Instant.now()).acquired)
            it.complete(testKey,DeliveryResult(DeliveryResult.Status.TEST_CONTRACT_PASSED))
            assertEquals(DeliveryResult.Status.TEST_CONTRACT_PASSED,it.claim(testKey,payload,Instant.now()).result.status)
        }
    }
    @Test fun explicitRejectionRetriesAreBoundedAndQuotaDueIsRespected() {
        val key=hash(namespace()); val payload=hash("synthetic payload"); val now=Instant.now()
        SqliteDeliveryLedger(context).use {
            assertTrue(it.claim(key,payload,now).acquired)
            it.complete(key,DeliveryResult(DeliveryResult.Status.RATE_LIMITED,now.plusSeconds(3600)))
            assertFalse(it.claim(key,payload,now.plusSeconds(3599)).acquired)
            assertTrue(it.claim(key,payload,now.plusSeconds(3601)).acquired)
            it.complete(key,DeliveryResult(DeliveryResult.Status.SCOPE_REQUIRED))
            assertFalse(it.claim(key,payload,now).acquired)
            assertTrue(it.claim(key,payload,now.plusSeconds(7200)).acquired)
            it.complete(key,DeliveryResult(DeliveryResult.Status.AUTH_REQUIRED))
            assertEquals(DeliveryResult.Status.ATTEMPTS_EXHAUSTED,it.claim(key,payload,now.plusSeconds(10000)).result.status)
        }
    }
    @Test fun twoIndependentDatabaseHandlesCannotBothClaim() {
        val key=hash(namespace()); val payload=hash("synthetic payload"); val pool=Executors.newFixedThreadPool(4)
        try {
            val futures=(1..12).map { pool.submit<Boolean> { SqliteDeliveryLedger(context).use { it.claim(key,payload,Instant.now()).acquired } } }
            assertEquals(1,futures.count { it.get() })
        } finally { pool.shutdownNow() }
    }
    @Test fun unknownAndChangedPayloadNeverRepost() {
        val key=hash(namespace()); val payload=hash("synthetic payload")
        SqliteDeliveryLedger(context).use {
            assertTrue(it.claim(key,payload,Instant.now()).acquired); it.complete(key,DeliveryResult(DeliveryResult.Status.UNKNOWN))
            assertFalse(it.claim(key,hash("edited synthetic payload"),Instant.now().plusSeconds(100000)).acquired)
        }
    }
    @Test fun deepLinkOnlyOpensAnExactDateAndNeverContainsASendAction() {
        val key="00000000000000000000000000000000" // synthetic fixture, never registered/initialized
        val cfg=KakaoConfiguration(key,12345,"https://example.org/ieum",true,false)
        assertEquals("2026-10-11",KakaoDigestLinkRouter.reportDate(Uri.parse("kakao$key://kakaolink?screen=digest&date=2026-10-11"),cfg)?.toString())
        for(link in listOf("kakao$key://oauth?screen=digest&date=2026-10-11","kakao$key://kakaolink?screen=digest&date=2026-10-11&send=true",
            "kakao$key://kakaolink?screen=digest&date=2026-10-11&date=2026-10-12","kakao$key://kakaolink?screen=digest&date=2026-02-30"))
            assertNull(KakaoDigestLinkRouter.reportDate(Uri.parse(link),cfg))
    }
}
