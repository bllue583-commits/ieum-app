package kr.callbrief.pipeline

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** r18-lock: an emulator (update-smoke, device-test) never writes the real mailbox; a phone always does. */
class EmulatorInboxTest {
    private fun emu(fp:String,hw:String="",model:String="",product:String="",mf:String="")=EmulatorInbox.isEmulator(fp,hw,model,product,mf)

    @Test fun emulatorImagesAreEmulators() {
        // The AVD of update-smoke and device-test: api 34 google_apis x86_64.
        assertTrue(emu("google/sdk_gphone64_x86_64/emu64xa:14/UE1A.230829.036/10995154:userdebug/dev-keys","ranchu","sdk_gphone64_x86_64","sdk_gphone64_x86_64","Google"))
        assertTrue(emu("generic/sdk/generic:8.0.0/OSR1/1:userdebug/test-keys","goldfish","Android SDK built for x86","sdk"))
        assertTrue(emu("generic_x86_64/sdk_x86_64/x:9/PSR1/1:userdebug/test-keys"))
        assertTrue(emu("","ranchu"))
        assertTrue(emu("x","vbox86","x","vbox86p","Genymotion"))
    }
    @Test fun phonesAreNot() {
        assertFalse(emu("samsung/a52qnsxx/a52q:16/BP2A.250605.031/A525FXXU9GYK1:user/release-keys","qcom","SM-A525F","a52qnsxx","samsung"))
        assertFalse(emu("samsung/dm1qxxx/dm1q:16/BP2A/S911BXXU:user/release-keys","qcom","SM-S911B","dm1qxxx","samsung"))
        assertFalse(emu("google/husky/husky:16/BP2A/1:user/release-keys","husky","Pixel 8 Pro","husky","Google"))
        assertFalse(emu("robolectric","robolectric","robolectric","robolectric","robolectric"))
        assertFalse(EmulatorInbox.isEmulator(null,null,null,null,null))
    }
    @Test fun onAnEmulatorWritesAreDroppedReadsStay() {
        val writes=mutableListOf<String>()
        val store=object:CallInboxStore {
            override fun put(path:String,json:String) { writes+="put $path" }
            override fun replace(path:String,json:String) { writes+="replace $path" }
            override fun delete(path:String,sha:String) { writes+="delete $path" }
            override fun list(dir:String)=listOf("a.json" to "1")
            override fun get(path:String)="{\"v\":1}"
        }
        val g=EmulatorInbox.guard(store,emulator=true)
        g.put(PhoneStatus.PATH,"{}");g.replace(PhoneStatus.PATH,"{}");g.delete("results/a.json","1")
        assertTrue(writes.isEmpty())
        assertEquals("{\"v\":1}",g.get("app/version.json"));assertEquals(1,g.list("results").size)
        val f=File.createTempFile("ieum",".apk");g.download("x",f);assertEquals("{\"v\":1}",f.readText());f.delete()
        val p=EmulatorInbox.guard(store,emulator=false)
        p.replace(PhoneStatus.PATH,"{}");assertEquals(listOf("replace status/phone.json"),writes)
    }
}
