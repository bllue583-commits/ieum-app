package kr.callbrief.kakao.android
import kr.callbrief.kakao.core.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.UUID
import javax.crypto.spec.SecretKeySpec
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class ConnectionBoundaryTests {
    private fun store()=PrivateKakaoStore(RuntimeEnvironment.getApplication(),"synthetic_"+UUID.randomUUID().toString().replace("-",""),object:PrivateKakaoStore.KeyAccess {
        private var key:javax.crypto.SecretKey?=null
        override fun existing()=key
        override fun createForEmptyStore()=SecretKeySpec(ByteArray(32){3},"AES").also{key=it}
    })
    private fun session()=VerifiedSession("1001",12345,"SYNTHETIC_PATCH_TOKEN",Instant.now().plusSeconds(3600),true)
    @Test fun lateConnectionCannotRestoreDisabledConsent(){val c=LocalReportConsent(store());val epoch=c.beginConnection();c.disable();try{c.connected("synthetic_owner_001",session(),epoch);fail()}catch(_:IllegalStateException){};assertFalse(c.snapshot().enabled)}
    @Test fun oldConfirmationCannotEnableAfterDisable(){val c=LocalReportConsent(store());val epoch=c.beginConnection();val s=session();c.connected("synthetic_owner_001",s,epoch);c.disable();try{c.enable("synthetic_owner_001",s,epoch);fail()}catch(_:IllegalStateException){};assertFalse(c.snapshot().enabled)}
    @Test fun connectionAndOptInEpochSurviveReopen(){val s=store();val c=LocalReportConsent(s);val n=c.beginConnection();val verified=session();c.connected("synthetic_owner_001",verified,n);c.enable("synthetic_owner_001",verified,n);val saved=LocalReportConsent(s).snapshot();assertTrue(saved.enabled);assertTrue(saved.generation>n);c.disable();assertTrue(c.snapshot().generation>saved.generation)}
    @Test fun fractionalEpochCannotBeTruncated(){val s=store();s.update{it.put("channelEpoch",1.5)};try{LocalReportConsent(s).snapshot();fail()}catch(_:IllegalStateException){}}
    @Test fun sameDayChangedSnapshotDoesNotBypassUnknown(){val c=RuntimeEnvironment.getApplication();val key=DeliveryCoordinator.sha256(UUID.randomUUID().toString());SqliteDeliveryLedger(c).use{assertTrue(it.claim(key,DeliveryCoordinator.sha256("first"),Instant.now()).acquired);it.complete(key,DeliveryResult(DeliveryResult.Status.UNKNOWN))};SqliteDeliveryLedger(c).use{assertFalse(it.claim(key,DeliveryCoordinator.sha256("changed"),Instant.now().plusSeconds(86400)).acquired)}}
    @Test fun defaultBuildCannotConnectOrSend(){val cfg=KakaoConfiguration.fromResources(RuntimeEnvironment.getApplication());assertFalse(cfg.canLogin);assertFalse(cfg.liveSendEnabled)}
    @Test fun newOptInNeverDrainsPriorCalendarReports(){val store=store();val c=LocalReportConsent(store);val epoch=c.beginConnection();val v=session();c.connected("synthetic_owner_001",v,epoch);c.enable("synthetic_owner_001",v,epoch);val zone=java.time.ZoneId.of("Asia/Seoul");val start=Instant.parse(store.snapshot().getJSONObject("reportConsent").getString("confirmedAt")).atZone(zone).toLocalDate();assertFalse(c.automaticDateAllowed("synthetic_owner_001",start.minusDays(1),zone));assertTrue(c.automaticDateAllowed("synthetic_owner_001",start,zone));assertFalse(c.automaticDateAllowed("synthetic_other_001",start,zone))}
    @Test fun activationTimestampIsNotRemintedOnReopenOrMissingField(){val store=store();val c=LocalReportConsent(store);val epoch=c.beginConnection();val v=session();c.connected("synthetic_owner_001",v,epoch);c.enable("synthetic_owner_001",v,epoch);val before=store.snapshot().getJSONObject("reportConsent").getString("confirmedAt");val date=Instant.parse(before).atZone(java.time.ZoneId.of("UTC")).toLocalDate();assertTrue(LocalReportConsent(store).automaticDateAllowed("synthetic_owner_001",date,java.time.ZoneId.of("UTC")));assertEquals(before,store.snapshot().getJSONObject("reportConsent").getString("confirmedAt"));store.update{it.getJSONObject("reportConsent").remove("confirmedAt")};assertTrue(runCatching{LocalReportConsent(store).automaticDateAllowed("synthetic_owner_001",date,java.time.ZoneId.of("UTC"))}.isFailure);assertFalse(store.snapshot().getJSONObject("reportConsent").has("confirmedAt"))}

}
