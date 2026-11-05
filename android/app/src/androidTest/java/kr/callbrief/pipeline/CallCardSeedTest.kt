package kr.callbrief.pipeline

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kr.callbrief.SecureSettings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** v3.0 r11-3 (design 7.4): the state the emulator check tools/device/call_card_test.sh needs, called one method at a time
 * (`am instrument -e class kr.callbrief.pipeline.CallCardSeedTest#seed`). Made-up data only ([PreviewSeed]): 010-1111-2222
 * is 김민수 of △△철강 with a contact card, 010-7777-8888 is a personal number. Not part of the pipeline device test. */
@RunWith(AndroidJUnit4::class)
class CallCardSeedTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val storage get()=CallPipelineStartup.storage(context)

    /** The preview day, every contact card worked out, numbers without a card off. */
    @Test fun seed() {
        PreviewSeed.ensure(storage)
        val cards=ContactCards.rebuildAll(kr.callbrief.IeumApplication.store(storage))
        SecureSettings(storage).update { it.put(PipelineSettingsKeys.CARD_UNKNOWN,false).put(PipelineSettingsKeys.CARD_INCOMING,true).put(PipelineSettingsKeys.CARD_OUTGOING,true) }
        val known=ContactCards.get(storage,"01011112222")
        val personal=kr.callbrief.IeumApplication.store(storage).isPersonal("01077778888")
        Log.i("IEUM_RESULT","call_card_seed cards=$cards known=${known!=null} personal=$personal")
        assertNotNull("a card for the test number",known)
        assertTrue("the personal number",personal)
        assertNull("no card for the unknown number",ContactCards.get(storage,"01099990000"))
    }
    /** Settings "전화부에 없는 번호도" on. */
    @Test fun unknownOn() { setUnknown(true) }
    /** Settings "전화부에 없는 번호도" off. */
    @Test fun unknownOff() { setUnknown(false) }
    private fun setUnknown(on:Boolean) {
        SecureSettings(storage).update { it.put(PipelineSettingsKeys.CARD_UNKNOWN,on) }
        assertEquals(on,SecureSettings(storage).snapshot().optBoolean(PipelineSettingsKeys.CARD_UNKNOWN,!on))
        Log.i("IEUM_RESULT","call_card_unknown on=$on")
    }
}
