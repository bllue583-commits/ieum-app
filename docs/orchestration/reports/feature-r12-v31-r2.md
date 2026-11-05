# r12-2 보고서 (감시, 하트비트, 무인 설치, 업데이트 보이기) — feature/r12-v31-r2 60436f2

- 시그니처 변경 없음. 새 설정 키 없음(UPDATE_PROGRESS만 씀).
- CallUpdate.kt: state/setupStep/openInstallSourceSettings/checkNow/notInstalledForADay 채움. UPDATE_PROGRESS {"phase","code","doneBytes","totalBytes","atMs"} 1초마다(일반 prefs에도). 네트워크 조건 모두 CONNECTED, 6시간 작업 `ieum.update` 하나(옛 ieum.update.any 취소), checkSoon 30초 지연 없앰. 새 오류 코드 INSTALL_SOURCE_NOT_ALLOWED, USER_ACTION_REQUIRED(errorWords 문구 있음). 무인 설치 조건 6가지 주석. `progressWords(percent,done,total)` 공용.
- AndroidManifest: UPDATE_PACKAGES_WITHOUT_USER_ACTION.
- PipelineModelDownloadWork.kt: CONNECTED + 저장 공간, 대기 작업 REPLACE, .part 이어받기.
- watchdog.py v31_rules 4개(옛 버전, 루틴 지시문, 전화 상태 권한, 녹음 판정). "n시간 계속"은 ieum-inbox git 기록의 status/phone.json으로(최근 50시간). watchdog.yml 변경 불필요.
- tools/release/version_fields.py(신설, --merge): promptVersion, tidyVersion, certSha256.
- tidy.md 머리 "지시문 버전 4". UiV2Test 옛 Wi-Fi 시험 → updateUsesAnyConnection.
- 시험: UpdateAutoInstallTest +11, watchdog V31RulesTest 6, version_fields 4. 로컬 r12-2-test-all-3 전체 544 중 1 실패(RoutineDocTest 30분 기대 — r12-3이 고침), 파이썬 전부 OK, check_ui_rules 0, scan_export 0.
- 설계와 다르게: 워치독 지속 시간은 git 기록으로, 이슈 제목 고정(버전 번호는 본문).
- 남은 것: 화면 Wi-Fi 문구(SettingsScreen, HomeScreen, ModelWish → r12-5; StatusLights:64-65 → r12-1). tidy.md 68행 "promptVersion은 빈 문자열" → 지시문 버전을 적게(통합 때 확인).
- 워크플로(오케스트레이터): release-apk Publish 단계에 `APKSIGNER=... python3 tools/release/version_fields.py --root . --apk "$RUNNER_TEMP/ieum.apk" --merge version.json`, 서명 비교에 version.json certSha256 폴백. update-smoke는 앱 자신 설치 경로(run-as로 cache/update에 APK·prefs 넣고 `--ez install_update true`), 첫 smoke(prev=v3.0)는 경고만.
