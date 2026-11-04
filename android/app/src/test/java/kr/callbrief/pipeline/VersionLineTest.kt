package kr.callbrief.pipeline

import org.junit.Assert.assertEquals
import org.junit.Test

class VersionLineTest {
    @Test fun aNamedVersionShowsTheNameAndTheBuild() {
        assertEquals("앱 버전 v2.0 (빌드 1050)",CallUpdate.versionLine("2.0",1050,null))
        assertEquals("앱 버전 v2.0 (빌드 1050), 새 버전 v2.1 (빌드 1061) 준비됨",
            CallUpdate.versionLine("2.0",1050,CallUpdate.Info(1061,"v2.1","a","s","n")))
    }
    @Test fun anOldStyleNameFallsBackToTheBuildNumber() {
        assertEquals("앱 버전 v1049",CallUpdate.versionLine("0.13.4-android-local-whisper-dev",1049,null))
        assertEquals("앱 버전 v1049, 새 버전 v1050 준비됨",CallUpdate.versionLine("x",1049,CallUpdate.Info(1050,"v1050","a","s","n")))
    }
}
