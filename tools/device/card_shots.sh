#!/usr/bin/env bash
# v3.3 (DESIGN_V4 3.3-3): pictures of the call card in four ways (light and dark, letters 1.0 and 1.3 times), drawn by the host
# test CallCardShotsTest (Robolectric, no emulator needed). Made-up names only.
#
# Writes evidence/shots/card_{light,dark}_{10,13}.png. Run from the repository root after the toolchain is set up
# (GitHub: the ci job; here: run_gradle.py with the shared Gradle lock).
set -eu
out="$(pwd)/evidence/shots"; mkdir -p "$out"
cd android
IEUM_SHOTS_DIR="$out" ./gradlew --no-daemon :app:testDebugUnitTest --tests 'kr.callbrief.pipeline.call.CallCardShotsTest'
ls "$out"/card_*.png
