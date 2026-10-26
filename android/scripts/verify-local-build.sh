#!/bin/sh
# Reuse explicitly supplied local tools; never install SDK packages or create an AVD.
set -eu
android_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$android_dir"

if [ -z "${CALLBRIEF_JDK_HOME:-}" ] || [ ! -x "$CALLBRIEF_JDK_HOME/bin/java" ] || [ ! -x "$CALLBRIEF_JDK_HOME/bin/javac" ]; then
    printf '%s\n' 'BLOCKED: JDK17 실행 파일이 없습니다. CALLBRIEF_JDK_HOME에 이미 준비된 JDK17 경로를 지정하세요.'
    exit 3
fi
task_java_version=$("$CALLBRIEF_JDK_HOME/bin/java" -version 2>&1 | head -n 1)
case "$task_java_version" in
    *'version "17.'*) ;;
    *)
    printf '%s\n' 'BLOCKED: 이 소스의 JVM toolchain은 JDK17입니다. 제공된 JDK 버전을 확인하세요.'
    exit 3
    ;;
esac
if [ -z "${CALLBRIEF_SDK_ROOT:-}" ] || [ ! -f "$CALLBRIEF_SDK_ROOT/platforms/android-36/android.jar" ] || [ ! -d "$CALLBRIEF_SDK_ROOT/build-tools/35.0.0" ]; then
    printf '%s\n' 'BLOCKED: platforms;android-36 또는 build-tools;35.0.0이 없습니다. CALLBRIEF_SDK_ROOT에 준비된 SDK 경로를 지정하세요.'
    exit 3
fi
if [ ! -f app/src/main/java/kr/callbrief/LocalRecordingCatalog.kt ]; then
    printf '%s\n' 'BLOCKED: 핵심 저장소 read adapter가 아직 통합되지 않았습니다.'
    exit 3
fi

export JAVA_HOME="$CALLBRIEF_JDK_HOME"
export ANDROID_HOME="$CALLBRIEF_SDK_ROOT"
export ANDROID_SDK_ROOT="$CALLBRIEF_SDK_ROOT"
export ANDROID_USER_HOME="$android_dir/build/local-android-user"
export GRADLE_USER_HOME="${CALLBRIEF_GRADLE_HOME:-$android_dir/build/local-gradle-user}"
export PATH="$JAVA_HOME/bin:$PATH"
mkdir -p "$ANDROID_USER_HOME" "$GRADLE_USER_HOME"
export GRADLE_OPTS="${GRADLE_OPTS:-} -Duser.home=$ANDROID_USER_HOME"
printf '%s\n' '빌드와 합성 JVM 시험을 시작합니다. AVD·실제 통화·네트워크 transport는 실행하지 않습니다.'
exec sh ./gradlew --no-daemon --console=plain -Pandroid.builder.sdkDownload=false :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
