#!/bin/bash
# Prepares a local Android build toolchain for the ieum-app checkouts (JDK 17, SDK platform 36, build-tools 35, Robolectric jar).
set -u
T=/home/claude/toolchain
mkdir -p "$T" && cd "$T"
echo "== $(date) start"

# JDK 17 (Ubuntu package)
if [ ! -x /usr/lib/jvm/java-17-openjdk-amd64/bin/java ]; then
  DEBIAN_FRONTEND=noninteractive apt-get install -y -q openjdk-17-jdk-headless > "$T/apt-jdk.log" 2>&1; echo "apt jdk exit: $?"
fi
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
"$JAVA_HOME/bin/java" -version 2>&1 | grep -v 'Picked up'

# Android command-line tools
export ANDROID_SDK_ROOT="$T/android-sdk"
mkdir -p "$ANDROID_SDK_ROOT/cmdline-tools"
if [ ! -x "$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" ]; then
  curl -fsSL "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip" -o cmdtools.zip
  python3 -c "import zipfile;zipfile.ZipFile('cmdtools.zip').extractall('cmdtools')"
  rm -rf "$ANDROID_SDK_ROOT/cmdline-tools/latest" && mv cmdtools/cmdline-tools "$ANDROID_SDK_ROOT/cmdline-tools/latest" && rm -rf cmdtools cmdtools.zip
  chmod +x "$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/"*
fi
yes | "$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" --licenses > /dev/null 2>&1
"$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" "platforms;android-36" "build-tools;35.0.0" "platform-tools" > "$T/sdkmanager.log" 2>&1
echo "sdkmanager exit: $?"
ls "$ANDROID_SDK_ROOT/platforms" "$ANDROID_SDK_ROOT/build-tools" 2>/dev/null

# Robolectric runtime jar (shared); retry on proxy 429
mkdir -p "$T/robolectric-jar"
for i in 1 2 3 4 5; do
  [ -f "$T/robolectric-jar/android-all.jar" ] && break
  curl -fsSL "https://repo.maven.apache.org/maven2/org/robolectric/android-all-instrumented/15-robolectric-13954326-i7/android-all-instrumented-15-robolectric-13954326-i7.jar" -o "$T/robolectric-jar/android-all.jar" && break
  rm -f "$T/robolectric-jar/android-all.jar"; sleep 10
done
ls -la "$T/robolectric-jar/android-all.jar"

# Environment file for workers
cat > "$T/env.sh" <<EOF
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_SDK_ROOT=$T/android-sdk
export ANDROID_HOME=$T/android-sdk
export GRADLE_USER_HOME=/home/claude/gradle-user
export IEUM_SKIP_ANDROID_NATIVE=1
export PATH=/usr/lib/jvm/java-17-openjdk-amd64/bin:\$PATH
EOF
echo "== $(date) toolchain ready"

# First build in the main checkout to warm the Gradle cache (compile + core tests)
cd /home/claude/ieum-app && . "$T/env.sh"
python3 tools/prepare_host_tests.py "$T/robolectric-jar/android-all.jar" > "$T/prepare_host_tests.log" 2>&1; echo "prepare_host_tests exit: $?"
rm -f evidence/warmup.log
python3 run_gradle.py --log warmup.log :core:test :app:compileDebugUnitTestKotlin > "$T/warmup.out" 2>&1
echo "warmup exit: $?"; tail -3 "$T/warmup.out"
grep -E 'BUILD (SUCCESSFUL|FAILED)|^e: |error:' evidence/warmup.log | tail -20
echo "== $(date) done"
