package kr.callbrief.pipeline.call

import kr.callbrief.ProtectedTextHostTestBase
import kr.callbrief.SecureSettings
import kr.callbrief.ShadowTextAtomicFile
import kr.callbrief.ShadowTextKeys
import kr.callbrief.pipeline.PhoneStatus
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.time.ZonedDateTime

/** v4.1 r22-2 (evidence only, nothing worked around): calls whose overlay check is yes before, no during and yes after; the
 * day's first failure and the last drawn card before it; the raw and the restricted app-op modes, the time since the last
 * install and the installer's kind in the cause code; whether the fallback notice was up and could be heard. Synthetic values. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextKeys.ExistingApprovedSettings::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class R22CardStatsTest:ProtectedTextHostTestBase() {
    private val app get()=RuntimeEnvironment.getApplication()
    private val kst=java.time.ZoneId.of("Asia/Seoul")
    private var now=ZonedDateTime.of(2026,11,18,17,0,0,0,kst).toInstant().toEpochMilli()
    @After fun done() { CardStats.resetForHost() }
    private fun heartbeat(installed:Long?=null):JSONObject { CardStats.awaitIdle();return CardStats.heartbeat(SecureSettings(app).snapshot(),now,installed)!! }
    private fun state(s:CallSession.State)=CardStats.follow(app,CallSession.Snapshot(s,"01011112222",CallSession.Direction.OUT,now),CallCardService.SHOW)

    @Test fun aCallAllowedBeforeDeniedDuringAndAllowedAfterIsCounted() {
        CardStats.clock={ now }
        var draw=true;CardStats.canDrawNow={ draw }
        state(CallSession.State.OFFHOOK);CardStats.failure(app,"add:BadToken:d0:m2");state(CallSession.State.IDLE)
        // A call where the check said no at the start and at the end: not a flip.
        draw=false;state(CallSession.State.OFFHOOK);CardStats.failure(app,"add:BadToken:d0:m2");state(CallSession.State.IDLE)
        // A call with a failure the check did not refuse: not a flip either.
        draw=true;state(CallSession.State.OFFHOOK);CardStats.failure(app,"hidden");state(CallSession.State.IDLE)
        val t=heartbeat().getJSONObject("today")
        assertEquals(1,t.getInt(CardStats.FLIP_CALLS));assertEquals(1,t.getInt(CardStats.DENIED_AT_START));assertEquals(1,t.getInt(CardStats.DENIED_AT_END))
        assertEquals(3,t.getInt(CardStats.CALLS))
    }

    @Test fun theDaysFirstFailureAndTheLastDrawnCardBeforeItAreKept() {
        CardStats.clock={ now }
        val drawnAt=now
        CardStats.verdict(app,CardStats.Attempt(CardStats.DRAWN,CallCardOverlay.CAUSE_OK,true))
        now+=9*60_000L // 17:09
        CardStats.failure(app,"add:BadToken:d0:m2")
        now+=60*60_000L;CardStats.failure(app,"add:BadToken:d0:m2") // a later one changes nothing
        val t=heartbeat(installed=1_795_000_000_000L).getJSONObject("today")
        assertEquals(1709,t.getInt(CardStats.FIRST_FAIL_HM));assertEquals(drawnAt,t.getLong(CardStats.OK_BEFORE_MS))
        assertEquals(1_795_000_000_000L,t.getLong(CardStats.INSTALLED_MS))
        // The heartbeat carries them as numbers; a new day starts without them.
        val sent=PhoneStatus.callCardJson(heartbeat()).getJSONObject("today");assertEquals(1709,sent.getInt(CardStats.FIRST_FAIL_HM))
        now+=86_400_000L
        assertFalse(heartbeat().getJSONObject("today").has(CardStats.FIRST_FAIL_HM))
        CardStats.failure(app,"hidden")
        assertEquals(drawnAt,heartbeat().getJSONObject("today").getLong(CardStats.OK_BEFORE_MS))
    }

    @Test fun theCauseCodeSaysTheRawModeTheInstallAndTheInstaller() {
        assertEquals("add:BadToken:d0:m2:r0:u48:is",CallCardOverlay.failCode("addView:BadTokenException",false,2,false,false,true,null,0,raw=0,update="u48",installer="is"))
        // The same raw and restricted mode: no ":r".
        assertEquals("add:BadToken:d0:m2:u1:ik",CallCardOverlay.failCode("addView:BadTokenException",false,2,false,false,true,null,0,raw=2,update="u1",installer="ik"))
        // Before r22 the same code as it was.
        assertEquals("add:BadToken:d0:m2",CallCardOverlay.failCode("addView:BadTokenException",false,2,false,false,true,null,0))
        assertEquals("u1",CardProbe.updateBucket(59*60_000L));assertEquals("u6",CardProbe.updateBucket(5*3_600_000L))
        assertEquals("u24",CardProbe.updateBucket(23*3_600_000L));assertEquals("u48",CardProbe.updateBucket(47*3_600_000L))
        assertEquals("uo",CardProbe.updateBucket(49*3_600_000L))
        assertEquals("is",CardProbe.installerKindOf("kr.callbrief","kr.callbrief"));assertEquals("ip",CardProbe.installerKindOf("com.android.vending","kr.callbrief"))
        assertEquals("ik",CardProbe.installerKindOf("com.google.android.packageinstaller","kr.callbrief"));assertEquals("in",CardProbe.installerKindOf(null,"kr.callbrief"))
        assertEquals("io",CardProbe.installerKindOf("com.example.files","kr.callbrief"))
        // The recent entry keeps the new probe values as codes.
        val e=CardStats.entry(CardStats.Attempt(CardStats.FALLBACK,"hidden",false,probe=CardStats.Probe(2,1,1,false,false,null,0,"u48","is")),now)
        assertEquals(0,CardStats.safe(e).getInt("rawMode"));assertEquals("u48",CardStats.safe(e).getString("upd"));assertEquals("is",CardStats.safe(e).getString("inst"))
    }

    @Test fun theFallbackNoticeIsCountedOncePerCall() {
        CardStats.clock={ now };CardStats.canDrawNow={ true }
        state(CallSession.State.RINGING);CardStats.fallbackNotice(app,true,false);CardStats.fallbackNotice(app,true,true);state(CallSession.State.IDLE)
        state(CallSession.State.RINGING);CardStats.fallbackNotice(app,false,true);state(CallSession.State.IDLE)
        val t=heartbeat().getJSONObject("today")
        assertEquals(1,t.getInt(CardStats.FALLBACK_SHOWN));assertEquals(1,t.getInt(CardStats.FALLBACK_MUTED))
    }
}
