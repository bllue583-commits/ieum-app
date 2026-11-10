# 이음 설계도 — 지금 배포된 v3.6(빌드 1049), 구현된 그대로

> 이 문서는 "앱이 지금 어떻게 동작하는가"의 기준이다. 버전별 지시와 변경 이력은 넣지 않는다(그것은 `history/`의 DESIGN_V3.md(v2.6~v3.0), DESIGN_V4.md(v3.1~v3.4), HANDOFF_HISTORY.md, DESIGN_V5.md(v3.5), DESIGN_V6.md(v3.6)). **다음 네 버전(v3.7~v4.0)의 설계는 [DESIGN_V7.md](DESIGN_V7.md)이고, 구현이 끝난 버전부터 이 문서에 흡수한다**. 설계와 구현이 달랐던 항목은 각 버전 "다르게 정한 것"·"구현 대조"의 최종 결정을 사실로 적었다(v3.5는 DESIGN_V5 6절 19줄). 숫자는 코드 상수에서 확인했고, 문서와 코드가 아직 다른 곳은 13절에 모았다. 근거는 괄호 안에 파일명이나 `함수명`으로 적는다. 확인하지 못한 것은 "(추정)".
>
> 기준 코드: `feature/lean-calls` 857bc75(앱 코드는 2a0cfd7), `docs/VERSION` 3.6, 우편함 `app/version.json` versionCode 1049. 작성 2026-11-06, v3.5·v3.6 반영 2026-11-07, 실측·미해결 목록 갱신 2026-11-10.

## 1. 한눈에 보기

| 항목 | 내용 |
| --- | --- |
| 목적 | 건설사 직원(부장, 현장 하나)인 아내가 하루 약 2시간 하는 업무 통화를 자동으로 정리해 체크할 일, 요약, 업체·사람 맥락을 보여 주고, 전화가 오거나 걸 때 그 상대와 약속한 일을 통화 화면 위 카드로 띄운다. 매일 아침 5:30에는 어제 통화 정리와 오늘 챙길 일을 아내 카카오톡 "나와의 채팅"으로 보낸다(v3.5)(PRODUCT.md 1절, HANDOFF.md 1절) |
| 사용자 | 실사용자 아내(앱 설정 만지기를 싫어함), 관리자 남편. 개발·시험·판단은 Claude가 한다 |
| 기기 | Galaxy S26, 안드로이드 16. 삼성 통화 녹음(자동, `Recordings/Call`, m4a) |
| 현재 버전 | v3.6, 빌드 1049(11/7 21:44, 전력 정책 + 카카오 새 앱 키 재빌드; 1047·1048은 같은 날 앞선 빌드). 서명 인증서 SHA-256 `e4ee1db4…2c586`(1039~1049 같음, `app/version.json` `certSha256`). 카카오 네이티브 앱 키는 빌드 때 `BuildConfig.KAKAO_APP_KEY`로 주입(GitHub 비밀 `IEUM_KAKAO_APP_KEY`, 비어 있으면 카톡 기능이 설정에서 숨고 로그인 Activity도 꺼짐) |
| 버전 규칙 | 이름은 `docs/VERSION` 한 줄. 사용자에게 보이는 변경을 배포할 때마다 0.1씩 올린다. 문서만 바꾸거나 같은 회차 재배포는 그대로. 빌드 번호는 `1000 + GITHUB_RUN_NUMBER`이고 업데이트 순서에만 쓴다. 폰 알림 문구는 `docs/UPDATE_NOTE.txt` 첫 줄(최대 120자)(release-apk.yml, HANDOFF.md 0절) |

**원칙(사용자 결정, 10절 표에 날짜)**
1. 자동, 버튼 없음: 설정을 마친 뒤 사용자가 하는 일은 읽기와 [다 됐어요]뿐. AI는 완료 처리하지 않는다.
2. 녹음 파일은 폰 밖으로 나가지 않는다. 자막과 맥락 글만 비공개 우편함에 올라간다.
3. 50대 기준 화면: 읽는 글 18sp 이상, 쉬운 말, 영어 상태값·가운데 점·말줄임표·빨강 금지, "할 일" 대신 "체크할 일", 기한 대신 요청 날짜.
4. 지키지 못할 약속 문구 금지: 녹음이 없으면 "정리하고 있어요"라고 하지 않는다.
5. 화면과 보고의 숫자는 녹음 기준("녹음 n개 중 m개"). 통화기록 총수는 하트비트·워치독·통화 목록 검증에만.
6. 앱이 일을 못 하면 스스로 말한다(신호등, 자가 진단 알림, 하트비트, 워치독).
7. 근거를 보여 준다: 체크할 일은 자막 원문 인용이 있어야 남는다.

## 2. 전체 구조

### 2.1 구성 요소

| 구성 요소 | 어디 | 하는 일 |
| --- | --- | --- |
| 폰 앱 | `android/app/src/main/java/kr/callbrief/`(패키지 `kr.callbrief`), 파이프라인 `pipeline/`, 화면 `pipeline/screens/`, 카드 `pipeline/call/`, 아침 보고 `pipeline/report/` | 녹음 발견, 자막, 대조, 요청 구성·업로드, 결과 받기·적용, 알림, 카드, 하트비트, 업데이트, 카톡 아침 보고 |
| 카카오 SDK | Kakao Android SDK v2 **2.24.0**(`v2-user`·`v2-talk`·`v2-share`, 저장소 `devrepo.kakao.com`은 `com.kakao.sdk` 그룹에만), `KakaoSdkSender` | 로그인(동의 항목 `talk_message`만), 이미지 업로드(`ShareClient.uploadImage`), "나에게 보내기"(`TalkApiClient.sendDefaultMemo`) |
| Whisper 네이티브 | `android/app/src/main/cpp/ieum_whisper.cpp`(whisper.cpp 1.7.6, JNI open/transcribeChunk/close) | 폰 안 음성 인식. 모델 `ggml-large-v3-turbo-q5_0` 574,041,195바이트(`PipelineModelManager.DEFAULT`) |
| 우편함 | 비공개 저장소 `bllue583-commits/ieum-inbox` | 요청·결과·장부·새벽 정리·상태·지시문·워치독·릴리스 APK |
| 예약 작업 3개 | claude.ai 예약 작업(Claude Code 원격 세션) | 매시간 분석, 새벽 정리, 통화 목록 검증(2.3) |
| 워치독 | 원본 `tools/inbox/watchdog.py`, 우편함 `tools/watchdog.py` | 매시간·새벽 루틴의 마지막 단계에서 돌며 GitHub 이슈(라벨 `ieum-watch`)를 열고 닫는다. Actions 워치독은 없다(8절) |
| GitHub 워크플로 | 앱 저장소 `.github/workflows/` | ci, release-apk, update-smoke, device-test, real-device-test, inbox-e2e, cleanup-branches(9절) |

### 2.2 흐름

```
[폰]
 통화 끝(PHONE_STATE IDLE) ─▶ aftercall 발견(15초 뒤) ─▶ followup 발견(150초 뒤, 새 파일 저장)
 15분 주기 발견 / 통화기록 변화 140초 뒤 발견 ──┘
   ─▶ 암호화 금고에 사본 ─▶ 자막 대기열 ─▶ Whisper 2분 조각 자막 ─▶ call_records(TRANSCRIBED)
   ─▶ 통화기록 대조(FirstPass.match) ─▶ 요청 구성(번호·메일 가림) ─▶ 우편함 inbox/ 업로드
                                                      │
[우편함 ieum-inbox]                                   ▼
 inbox/<해시>.json, inbox/b-<시각>-<해시>.json, ledger/edits/*.json, tidy/state.json, status/phone.json
                                                      │
[매시간 분석, 매시 34분 07~22시, Sonnet 5.5]           ▼
 pull ─▶ edits 반영 ─▶ 기록마다 분석·장부 고침 ─▶ results/, ledger/ledger.json(rev+1), story/today.json
 ─▶ "analyze N calls" push ─▶ status/last-run-hourly.json "status" push ─▶ watchdog.py --apply
                                                      │
[폰, 예정 실행 +3·+8·+15분]                            ▼
 장부 받기(applyLedger) ─▶ 결과 검증·필터·적용 ─▶ 결과 파일 삭제 ─▶ 통화별 알림 "정리됐어요" ─▶ 상대 카드 갱신
                                                      │
[폰, 밤 21:00~04:59 하루 한 번] tidy/state.json ─▶ [새벽 정리 04:46, Opus 5.5] issues·합치기·tidy/proposal.json·report/<오늘>.json
 ─▶ [폰 05:30 ReportFetch, 없으면 05:35·05:45·06:00] 카카오 "나에게 보내기": 피드 이미지 1통 + 오늘 챙길 일 글 1통
 ─▶ [폰] applyTidy ─▶ 아침 06시 앱 알림(카톡 성공 날은 조용히), 저녁 19시 정리 알림
[릴리스] lean-calls push ─▶ ci ─▶ release-apk ─▶ 우편함 릴리스·app/version.json ─▶ 폰 1시간 안 확인·무인 설치
```

### 2.3 저장소, 브랜치, 배포 경로

| 항목 | 값 |
| --- | --- |
| 앱 저장소 | `github.com/bllue583-commits/ieum-app`, 11/5 08시부터 공개(비밀 없음). 작업 브랜치이자 원본 `feature/lean-calls`. `main`은 옛 코드 + `cleanup-branches.yml`(HANDOFF.md 3절) |
| 우편함 | `ieum-inbox`(비공개) 기본 브랜치 |
| 매시간 분석 | `trig_016vQxos9z6p1pdPJ28MjrsV`, `CRON_TZ=Asia/Seoul 34 7-22 * * *`(하루 16번), Sonnet 5.5, 지시문 `routines/hourly.md` 버전 8 |
| 새벽 정리 | `trig_01Sp6piuvasw52ZR1KPupumB`, `CRON_TZ=Asia/Seoul 46 4 * * *`(04:46, v3.5에서 05:16에서 옮김), Opus 5.5, 지시문 `routines/tidy.md` 버전 7 |
| 통화 목록 검증 | `trig_01KyuDNZWkmrQpJj8e8cr661`, 09:00, Opus 5.5, 프롬프트가 원본, 기억 `status/verify-log.md`(숫자만) |
| 예약 작업 프롬프트 | 짧은 안내만: 우편함 pull(실패하면 "확인 필요: 저장소 접근"만 답) → `routines/*.md`의 첫 "---" 아래를 따른다. 예약 작업은 1시간보다 짧은 간격을 허용하지 않는다(OPERATIONS.md 2절) |
| 배포 경로 | lean-calls push → ci 통과 대기(40분) → (시작·빌드 파일이 바뀌었으면) update-smoke 통과 대기(70분) → 서명 APK → 서명 인증서 비교(카카오 키 해시도 실행 요약에 찍음) → `docs/routines/*.md`·`tools/inbox/watchdog.py`·`tools/report_check.py`를 우편함에 먼저(바뀐 것만) → 릴리스 `v<빌드>`와 `app/version.json`(versionCode, versionName, apk, sha256, size, notes, promptVersion, tidyVersion, certSha256), 릴리스는 3개만 남김(release-apk.yml, `tools/release/version_fields.py`) |

## 3. 통화 → 보고 흐름

### 3.1 녹음 발견
| 규칙 | 값 | 근거 |
| --- | --- | --- |
| 폴더 | 사용자가 고른 SAF 폴더(보통 `Recordings/Call`), 지속 권한 | `CallFolder`, `SelectedRecordingSource` |
| 주기 | 15분 주기 작업 + 통화기록 변화 140초 뒤 + 통화 끝(IDLE) 15초 뒤 aftercall(expedited로 바로 시작해 워커 안에서 15초 대기) + 새 파일이 보이면 150초 뒤 followup(2분 미만 파일은 "쓰는 중"이라 두 번 보고 조용해야 저장). 통화 끝부터 자막 시작까지 약 2.5~3분 | `AutomaticRecordingDiscovery` `AFTERCALL_WAIT_MS`·`FOLLOWUP_DELAY_SECONDS`·`CALLLOG_DELAY_SECONDS` |
| 한 사이클 한도 | 75초 | `CYCLE_BUDGET_MS` |
| 컷오프 | 2026-10-01 00:00 KST 이전(파일 이름 시각 우선, 없으면 수정 시각)은 세지 않고 버림, 대기열의 옛 행은 `SKIPPED/BEFORE_START` | `FirstPass.FROM_MS`, `CallTranscriptionStore.SKIP_BEFORE_START_SQL` |
| 목록 한도 | 남긴 문서 2,000개(`KEEP_LIMIT`), 훑기 50,000개(`SCAN_LIMIT`). 넘치면 최신 유지, `inventoryTruncated` | `SelectedRecordingSource` |
| 녹음 크기 | 60,000,000바이트까지(옛 DB의 25MB CHECK는 한 번 넓힘) | `AudioBinding.MAX_BYTES`, `VaultSizeLimit.widen` |
| 시도 기록 | 시도마다 `lastDiscoveryAttemptMs`·`lastDiscoveryOutcome`(코드), 성공은 `lastDiscoveryMs`. 트리거는 finally에서 재등록 | `AutomaticRecordingDiscovery` |
| 제외 | 사용자의 제외 번호·이름 규칙(`LocalPrivacy`)은 `EXCLUDED`로 저장하지 않는다 | `CallTranscriptionRunner` |

**녹음 공백 판정**(`RecordingGap.judge`, 발견이 성공할 때마다): 대상 = 최근 24시간 받은·건 통화, 길이 ≥ 30초, 개인 아님, 통화 끝이 마지막 발견 성공보다 15분 이상 앞. 통화마다 창 [시작 − 2분, 끝 + 5분]에 폴더 파일이 있으면 withFile, 그중 기록과 이어진 것이 matched. 판정 순서: 폴더 오류 `folder` → 목록 잘림 `tooMany` → 대상 < 3 `ok` → withFile < 0.3n `noRecording` → < 0.7n `partial` → matched < 0.5·withFile인데 자막 대기·진행 중까지 더하면 0.5 이상 `pendingText` → `noMatch` → `ok`. 아내에게는 `noRecording`·`partial`·`folder`만, 6시간 넘게 떨어진 두 번 연속 판정부터 말한다(`CONFIRM_MS`). 48시간 넘은 판정은 보이지 않는다(`STALE_MS`). `noMatch`는 하트비트·워치독, 그리고 48시간 넘게 이어지면 신호등 녹음 줄 주황(v3.5, `StatusLights.noMatchLong`)에만, `pendingText`는 녹음 줄에 초록 글로만.

### 3.2 자막
| 규칙 | 값 | 근거 |
| --- | --- | --- |
| 모델 | large-v3-turbo q5_0(574MB), 정리 동의 직후 어떤 연결에서든 받기 시작, 크기·SHA-256 검증 | `PipelineModelManager`, `PipelineModelDownloadWork` |
| 조건(v3.6 전력 정책) | 실행 작업에 WorkManager 제약 없음. 깰 때마다 앱이 배터리(sticky `ACTION_BATTERY_CHANGED`)를 읽어 `PowerPolicy.decide`로 판정. **전원 꽂힘**(`EXTRA_PLUGGED≠0`, 삼성 보호의 notCharging·full 포함, 끊김 2분 미만 유지, 꽂혀도 잔량이 10분에 2% 이상 내려가면 배터리로): 모든 녹음, 유선 6·무선 4스레드, 끝나면 바로 다음 실행, 달궈짐(headroom ≥ 0.85, 배터리 40℃, 20분 안 조각이 식은 중앙값의 1.5배)이면 10분 뒤, 05~07시 90% 미만이면 쉼. **배터리**(40% 이상, 절전·통화 중·달궈짐 아님): 오늘(KST) 20초~3분 통화만 최신순(SQL에서 거름, 길이는 해독 길이→통화기록→파일 크기 추정), 4스레드, 하루 20분 또는 소모 5%(화면 꺼진 실행만), 실행 사이 30분. 그 밖은 쉼. 스위치 [충전 중에만 글자로 바꾸기](기본 끔). SEVERE 이상은 20분 쉼. 지연은 별도 틱(`calls-transcription-later`), 전원 트리거 `ieum.calls.transcribe.plug`(`requiresCharging`, 배터리일 때만 걸어 둠). 처음 정리의 옛 녹음은 꽂혔을 때만 | `PowerPolicy`, `CallTranscriptionWork`, `FirstPass.powerOk`, DESIGN_V6 2절 |
| 낭비 제거(v3.6) | W1 실행 9분 안에 안 들어갈 조각은 시작 안 함(예상 = 최근 조각 속도 × 1.25, 첫 조각은 예외, `Yield`로 시도 환불·멈춤 안 셈) · W2 예상이 432초(기한의 80%)를 넘을 조각은 미리 반으로(분할 표시 기록) · W3 한 실행 안 모델 세션 이어 쓰기(취소·오류 뒤엔 새로) · W4 `CodecException`(일시 오류 제외)·`ANDROID_STT_PCM_SIZE`는 바로 영구 실패, 기한 2회·INTERRUPTED는 `charging_only` · P7 스레드 ≤ `/proc/self/status` `Cpus_allowed_list` | `CallTranscriptionRunner`, `CallTranscriptionStore`, DESIGN_V6 2.3 |
| 조각 | 최대 2분(`MAX_CHUNK_MS` 120,000), 자르는 점은 ±2초 안 100ms 창 중 가장 조용한 곳(10ms 격자), 끝 조각 0.1초 미만은 앞 조각에 붙임 | `PipelineAudioMath` |
| 조각 저장 | 끝난 조각은 `call_transcription_parts`(조각 길이 `chunk_ms`가 다르면 무효)에 저장해 이어 한다. 같은 조각이 3번 연속 중지되면 반으로(10초 미만은 안 쪼갬) | `CallTranscriptionStore`, `SPLIT_AFTER_STOPS` |
| 대기열 순서 | (처음 정리 중이면 옛 녹음은 맨 뒤, 그 안에서는 통화 시각 오름차순) ① 조각이 하나라도 저장됐거나 중지·기한으로 멈춘 녹음(대기열 진입순) ② 24시간 넘게 기다린 새 녹음(진입순) ③ 나머지 새 녹음 최신 통화순 | `CallTranscriptionStore.nextQueued` |
| 시간 한도 | WorkManager 약 10분. 실행 6분이 지나면 새 녹음을 집지 않음(첫 녹음은 예외). 그 실행에서 조각을 못 끝낸 시간 한도 중지는 시도 환불(실행의 첫 녹음이 아닐 때만, 한 녹음 환불 6회까지). 이유 없는 중지는 시작 9분 뒤면 시간 한도로 본다 | `NEW_WORK_MS`, `MAX_REFUNDED_STALLS`, `TIME_LIMIT_NEAR_MS` |
| 기한 | 네이티브 조각당 540초(`NATIVE_DEADLINE_SECONDS`). 넘기면 `ANDROID_STT_DEADLINE`으로 그 조각을 반으로 다시. 반 조각이 또 넘기면 일반 실패 | `CallTranscriptionRunner.deadline` |
| 시도·실패 | 최대 3회(`MAX_ATTEMPTS`). RUNNING이 11분 넘으면 낡은 것으로 본다. 실패 사유 코드(예: `ANDROID_STT_PCM_SIZE`, `ANDROID_STT_NOT_COMPLETED`, `ANDROID_STT_CPU_UNSUPPORTED`, `EMPTY_TRANSCRIPT`)는 하트비트 `transcription.failReasons` | `STALE_RUNNING_MS` |
| 실패 재큐(v3.5, v3.6에서 바뀜) | **전원 꽂힘 실행의 시작에서만**(v3.6 W5, 영구 실패 제외, 멈춤 횟수·`deadline_count`·반 조각 없는 분할 표시도 초기화) FAILED 줄을 새 빌드에서 한 번 더 대기열로(reason `REQUEUED`, attempts 0). 같은 빌드에서 실패했거나(`failed_build`) 이미 살린 줄(`retried_build`)은 건너뛰고, 줄마다 **총 2회**(`requeue_count`), 마지막 실패가 `INTERRUPTED`(네이티브 크래시 의심)면 **1회**까지. 이번 빌드가 살린 수는 하트비트 `transcription.requeuedFailed` | `CallTranscriptionStore.requeueFailedOnce`, `MAX_REQUEUES`, `MAX_REQUEUES_INTERRUPTED` |
| VAD | 30ms 창이 0.006 이상이고 조각 소리 90분위의 0.3 이상이면 말소리. 골든 변형 하한 0.0025/0.15는 스위치만 있음 | `SPEECH_FLOOR`, `LOUD_SHARE`, `*_LOW` |
| 디코딩 | 기본 greedy, 온도 증가·best_of는 whisper.cpp 기본(0.2, 5), `no_context=true`, `token_timestamps=true`, `suppress_nst` 꺼짐, 언어 한국어, 스레드는 전력 정책(유선 6, 무선·배터리 4)과 쓸 수 있는 코어 수 중 작은 값(v3.6). 초기 프롬프트 꺼짐(`PROMPT_ENABLED=false`) | `ieum_whisper.cpp`, `AndroidWhisperEngine`, `WhisperCallTranscriber.threads` |
| 불확실 숫자 | 토큰 확률 최솟값 0.5 미만인 숫자를 `Segment.uncertainNumbers`로(이어 받은 조각에서는 빠짐) | `AndroidWhisperEngine.UNCERTAIN_P` |
| 후처리 | 반복 환각 제거, 세그먼트 줄바꿈 이음, 줄별 시각 `segments_json` `{"v":1,"s","d","u"}`(글은 없음) | `TranscriptClean`, `CallRecordStore` |
| 속도 | 아내 폰 S26 백그라운드 6스레드 실측(11/6~7): 2분 조각당 223초(식음)~330초(지속)~473초, 녹음 1분당 111~195초. 10/30의 85초/분은 앞쪽 측정. **v3.6 실측(11/8~11/10, 4스레드·`cpuset=background`·코어 4): 식은 중앙값 376~393초, 녹음 1분당 약 3.5분(밤 184분에 녹음 49분). 조각당 encode 약 176초·decode 약 52초(누적 178조각) — 인코더가 77%.** 목표 ≤ 280초는 미달(DESIGN_V7 2.1 T5, 4절) | DESIGN_V6 1.1, 하트비트 `power.chunk`·`transcription.decode` |
| 골든 측정 | 설정 "상태와 도움" 끝 [골든 측정](`GoldenMeasureActivity`, 충전 중 WorkManager). 사용자가 녹음과 정답을 넣으면 변형별 CER·업체명·숫자 적중·조각 ms만 하트비트 `golden`에. 음성·정답은 폰 밖으로 안 나감. B1~B7 상수는 기본값 그대로 | `GoldenMeasureActivity`, DESIGN_V4 3.5 |

### 3.3 통화기록 대조(`FirstPass.match`)
대상은 받은·건 통화이고 길이 > 0인 통화기록만(`answered`).
1. **파일 이름 우선.** 파일 이름에 번호(PhoneKey)나 전화부 이름(NameKey)이 있으면, 그 상대의 통화 중 파일 시각 ±30분(`PARTY_RANGE_MS`), 같은 KST 날짜(또는 평소 창 안), 길이 일치(20% 또는 15초)인 것이 후보. 하나면 창 밖이어도 잇는다. 여럿이면 평소 창 안의 것만 점수 단계로, 창 안에 없으면 잇지 않음(`several`).
2. **평소 창.** 통화기록 시작이 녹음 시작 2분 전~1분 뒤이고 길이가 맞아야 한다. 파일 이름의 번호가 다르면 잇지 않는다. 이름만 있고 통화기록 이름과 다르면 후보가 정확히 하나일 때만(제외 번호 항목에는 안 잇는다). 이름도 번호도 없으면 후보가 정확히 하나일 때만.
3. 점수 = 시작 차이(30초 단위) + 길이 차이(5% 단위), 창 밖 쌍은 +500, 이름 없는 시각 쌍은 +1,000. 점수순으로 녹음 하나·통화기록 하나씩.
4. 못 이은 이유 계수(noCandidate, length, nameNull, nameDiffers, numberDiffers, several), 시작 차이 10초 구간, noCandidate의 가장 가까운 통화와 차이(10분 구간, 60분 넘으면 `far`와 부호 있는 시간 구간, 10/1 이전은 `before`), 끝 시각 이름 쌍 `byEnd`, 상대로 이은 `byParty` → 하트비트 `callLogMatch`.
5. 통화기록과 못 이어도 파일 이름의 번호로 상대 키를 붙인다(이름만이면 전화부에 그 이름 번호가 하나일 때만)(DESIGN_V4 2-2 구현).

### 3.4 요청 구성(`ClaudeCallAnalyzer`, `CallRecordStore.request`, `CallInbox`)
| 항목 | 값 |
| --- | --- |
| 형식 | 요청 파일 `format` 3, `promptVersion` 8. 키: id, recordKey(기록 id 해시), counterpartId(장부 사람 id `p_…`), atMs, createdMs, message. system·schema는 싣지 않는다(`CallInbox.CARRY_RULES=false`) |
| 맥락 머리 | [사용자](내 정보가 비면 역할만), [현장 상황 메모], [상대], [이 번호를 쓰는 사람들], [업체], [업체 메모], [이 상대와의 최근 통화 요약], [오늘 다른 통화 요약], [열린 체크할 일](owner·person·quote 40자), [최근 끝낸 체크할 일], [달력](통화일부터 14일, 공휴일 표시), [용어](현장 용어 34개), [공종 목록](21개), [이름 목록], [통화기록](전화부 이름·방향·실제 시각), [통화 방향], [처음 정리], `<texts>`(같은 상대 최근 7일 업무 문자·메일 원문 3,000자), `<transcript>` |
| 이름 목록 | 최근 30일 상대 먼저, 그다음 갱신순. 번호 모양 이름은 넣지 않고 사람·카드 이름에 `noNumber` |
| 번호 가리기 | `maskNumbers`: 메일 주소 → `[메일 주소]`(도메인이 글자로 시작할 때만, 단가 "@15,000"은 남김) → 전화번호(9~10자리 유선 포함) → 11자리 이상 숫자열 → 계좌 모양 → `[번호]`. 날짜 YYYY-MM-DD는 남김 |
| 메일 | "보낸 사람"은 이름만(주소는 상대 id가 대신)(`mailSender`) |
| 처음 정리 묶음 | 기록 시각이 7일 넘은 것은 old: 15건씩 `inbox/b-<첫 시각>-<해시>.json`, 동시에 2묶음, 하루 4묶음, 6시간 결과가 없으면 쉼(C7 후퇴), 덜 찬 묶음은 가장 오래된 기록이 6시간 기다리면 보냄. old 기록은 최근 7일 것만 체크할 일을 만든다. 7일 안은 live(단건)(`FirstPass`) |
| 재전송 | 보낸 뒤 6시간 답이 없으면 다시(`RESEND_MS`). 같은 내용 정리 자료는 다시 올리지 않음(`CallRecordWork`) |

### 3.5 매시간 루틴(`docs/routines/hourly.md` 버전 8)
- 0번: `git pull`, `ls inbox ledger/edits`. 둘 다 비면 자가 보고와 워치독만 하고 "새 기록 없음". pull·push 실패는 아무것도 하지 않고 "확인 필요: 저장소 접근". 한 실행 처리 상한 20건(묶음 items도 셈), 남은 것은 `leftover`.
- 순서: edits(rename, mergePeople, unmergePeople→keepApart, renameCompany) → selftest-(결과만, 장부 안 고침) → 단건 atMs순 → b- 묶음 이름순 최대 2개.
- 기록마다 Read로 읽고 직접 판단(스크립트 일괄 생성·내용 출력 금지), [분석 규칙](앱 `ClaudeCallAnalyzer.SYSTEM`과 같음, `RoutineDocTest`)대로 결과, 장부(현장 메모 600자, 업체 메모 400자, 사람은 counterpartId로 추가, locked 칸은 안 바꿈, 다르게 들린 이름은 aliases).
- 5-1 `story/today.json` {day, text(2~3문장 200자), calls, atMs}: 오늘 업무 통화가 있을 때만.
- 장부 rev+1 → "analyze N calls" 커밋 → 자가 보고 `status/last-run-hourly.json` "status" 커밋(항상) → `python3 tools/watchdog.py --root . --apply bllue583-commits/ieum-inbox --out /tmp/ieum-watchdog-plan.json`.
- 답 세 줄(처리 건수만, 내용 없음). 첫 줄이 "확인 필요"일 때만 알림이 의미 있다.

### 3.6 결과 받기(`ResultChain`, `CallRecordWork`)
| 규칙 | 값 |
| --- | --- |
| 시각 | 업로드 뒤 자가 보고의 cron(`routineCrons`, 읽지 못하면 `34 7-22` KST)에서 다음 예정 실행을 구해 +3, +8, +15분. 답이 남으면 다음 예정 실행으로, 업로드 뒤 최대 24시간. 22:34 뒤 업로드는 다음 날 07:34 기준. 고유 작업 `ieum.calls.results` |
| 안전망 | 매시간 틱 `ieum.calls.inbox`(1시간 주기). 두 패스가 겹치면 뒤 것은 최대 10분 기다림(`PASS_WAIT_MS`) |
| 조건부 읽기 | 결과 목록과 `app/version.json`을 ETag로(304는 비용 없음) |
| 검증 | 형식이 깨지면 bad/, 적용 예외 3회면 bad/. 받은 표시가 있는데 24시간 넘게 ANALYZED가 아니면 표시를 풀어 다시 보냄(`STUCK_MS`), 두 번째 bad면 FAILED `RESULT_NOT_APPLIED` |
| 필터 `filter` | 인용 8자 이상이 자막·texts에 정확히 한 번(또는 여러 번이면 제목 핵심어가 앞뒤 한 문장 안), 아니면 인용 길이 ±20% 창의 3-gram 포함률 ≥ 0.9(0.7~0.9는 남기되 tentative `quote`). update·done_suggested는 아는 taskId만. 한 기록 체크할 일 최대 5개. 자막·이름 목록에 없는 사람·업체 이름은 버림. 현장 메모 600·업체 메모 400·역할 20자 |
| 숫자 가드 `NumberGuard.check` | 대상은 체크할 일의 title·due. 한글 수사·단위 동의어 정규화 뒤 제목의 숫자가 인용이나 인용 구간 ±30초(시각이 없으면 ±6줄)에 없으면 `number`. due 근거 날짜 표현이 없으면 `date`, 통화일보다 앞이면 null, 60일 넘게 뒤면 tentative. 불확실 숫자 토큰이면 `asr`. 같은 업체 7일 안 혼동 쌍(13/30 … 19/90)은 합치지 않고 나중 일에 tentative와 ai_note 메모(`confusable`) |
| tentative | 가드가 붙인 것과 모델이 붙인 것("알아볼게요") 모두 화면 배지 "확인 필요" |
| corrected | 결과 최상위 `[{"heard","written"}]`, 저장만 하고 화면에는 아직 안 씀. 숫자·날짜·규격은 교정 대상이 아님 |
| 지연 | 통화 끝 → 결과 적용 분 단위, 최근 7일 통화만 p50·p90 → 하트비트 `latency`(처음 정리·7일 넘는 값은 `outliers`) |

### 3.7 적용
- **장부 우선:** 장부 rev가 오르면 먼저 받는다(`takeLedger` → `CallRecordStore.applyLedger`). 장부가 원본이고 결과의 메모는 쓰지 않는다. 사람은 전화번호(메일은 주소) 기준으로 폰이 `p_…` id를 만들고(`people.ledger_id`), 번호는 폰에만 있다.
- 결과는 통화 시각순으로 적용(`applyAnalysis`), 사용자가 고친 항목·완료한 항목은 AI가 바꾸지 않는다. 같은 제목·최근 끝낸 일은 다시 만들지 않는다(제목에서 이름·업체·동작어를 지운 뒤 유사도 0.6 이상이면 건너뛰고 `mergeCandidates`로).
- **사람·업체 합치기:** 마이그레이션 자동 합치기는 같은 번호 + 이름이 같거나 한쪽이 빈 경우만. 그 밖(2음절 자모 1 차이, 성+직책)은 `person_merge_candidates` → 새벽 정리 `personCandidates`. 합친 원래 행은 `mergeLog`(최대 100)에. 설정 [합친 사람 되돌리기]: 두 사람 모두 장부 id가 있으면 `mergePeople` edit, 되돌리면 안 보낸 edit은 지우고 보냈으면 `unmergePeople`. 루틴은 unmerge면 mergedInto를 지우고 둘 다 `keepApart`(다시 합치지 않음).
- **이름 고치기:** 사람은 `rename` edit + `name_locked`(장부 반영 전까지 유지), 옛 이름은 `person_aliases`. 업체는 `renameCompany` edit + `company_aliases`.
- **applyTidy:** 제안 rev가 저장된 TIDY_REV보다 클 때 한 번. 항목 rev(workflow_items.revision)가 다르면 건너뜀, AI가 만든 그대로인 항목에만, 숫자가 달라진 rewrite는 거부, merge는 상태 `merged`로 모든 목록에서 빠짐.
- 적용 뒤 바뀐 번호의 상대 카드를 다시 계산(`ContactCards.touch`, 매시간 틱 끝 `warm`).

### 3.8 새벽 정리(`docs/routines/tidy.md` 버전 7)
- 폰: 정리 동의가 켜져 있고 21:00~04:59(KST), 마지막 전송 12시간 뒤면 `tidy/state.json`(열린 체크할 일과 rev·잠금, 기록 요약을 해시 키로, mergeCandidates, personCandidates)을 올린다. 처음 정리 완료 + `tidiedOnce` 거짓이면 10/1부터 전체, 아니면 최근 14일(`tidyAll`). 내용이 같으면 안 올린다. 04:46 실행은 그 전날 밤 올린 자료를 읽는다.
- 루틴(04:46, Opus, 폰의 05:30 전송 전에 끝내도록 v3.5에서 05:16에서 옮김): 오늘 "tidy" 커밋이 있으면 "이미 정리됨". state가 없거나 24시간 넘었으면 "정리할 것 없음: 이유"(보고서도 쓰지 않음, 폰이 06:00에 자기 자료로 보냄). issues(진행 중인 일 최대 20, id `i_`+8자), 팔로업 create, `tidy/proposal.json` {rev, basedOnMs, tasks[rewrite·merge·done_suggested·create]}, 장부 정리(mergedInto만, 지우지 않음), 기억 파일 `tidy/project.md`·`tidy/history/YYYY-MM.md`·`tidy/briefing.md`, **9-1 아침 보고서**(3.12), 자가 보고 `status/last-run-tidy.json`(v3.5 `reportWritten`), 워치독. 완료 처리는 하지 않는다.

### 3.9 알림
| 알림 | 시각·조건 | 문구·동작 | 근거 |
| --- | --- | --- | --- |
| 통화별 | 업무 상대로 확인된(상대 카드나 전화부 이름이 있는) 연결 통화가 끝나면. 발신은 통화기록 길이 > 0일 때만, 길이를 모르면 HOLD 10분 | "○○와 통화를 정리하고 있어요 / 정리하고 있어요, 끝나면 통화 목록에 보여요" + [메모 남기기](RemoteInput → 사용자 체크할 일). 결과가 오면 "정리됐어요" + 첫 문장 + 체크할 일 1줄. 6시간 넘으면(기록이 있을 때) "정리가 늦어지고 있어요, 끝나면 통화 목록에 보여요"(v3.5: 보고·시각 약속 없음). 통화 끝 2분 뒤 발견이 성공했는데 창 안에 파일이 없으면 "이 통화는 녹음이 없어 정리할 수 없어요". `noRecording` 판정 중에는 띄우지 않음. 잠금 화면 글 "통화 정리됨 / 확인할 통화 / 통화 정리 중". 저녁 보고 전에 끝난 통화와 36시간 넘은 것은 지움. **알림 그룹 묶음(v3.5):** 모든 통화별 알림이 그룹 `ieum.calls.followup.group` 하나로 묶이고, 2개 이상이면 요약 알림 "통화 알림 n개 / 정리됨 a건, 정리 중 b건, 확인할 통화 c건"(소리 없음, 누르면 오늘 통화). 채널 `ieum.calls.followup`(소리 없음), 스위치 "통화마다 알림"(기본 켬) | `CallFollowupNotice`, `GROUP`, `summaryWords` |
| 저녁 정리 | 19시(설정 가능). 시작하면 결과 받기를 같은 작업 안에서 최대 3분 | 제목 "녹음 n개 중 정리 m개, 새 체크할 일 k개"(v3.5, 홈과 같은 녹음 기준 `TodayStory.countLine`; 녹음 수 = 그날 통화 기록 + 아직 기록이 없는 자막 대기·실패 녹음 `CallTranscriptionStore.withoutRecord`). 통화기록에는 통화가 있는데 녹음이 하나도 없으면 "오늘은 녹음된 통화가 없어요 / 녹음이 없어 정리한 통화가 없어요". 줄 최대 7개("외 n개"). 누르면 그날 통화(dayMs) | `CallDigestText.evening`, `CallDigestPrefetch.TIMEOUT_MS` |
| 아침 보고(앱 알림) | 06시(설정 "아침 보고 › 이음 알림", 4~11시) | 제목 "체크할 일 n개", 첫 줄 "지금 할 일: …"에 [다 됐어요] 액션(누르면 다음 항목으로, 다 끝나면 "체크할 일을 다 끝냈어요"), 진행 중인 일 최대 3줄, 저녁 보고 뒤 정리된 통화가 있으면 "늦게 정리된 통화 n건", 받아 둔 새 버전이 설치를 기다리면 "새 버전 설치가 기다리고 있어요"(v3.5). 기한·"지남" 없음. **순서(v3.5, 카톡 보고와 같음):** 확인 필요(`needs_confirmation`·tentative) → 오늘·어제 요청 → 나머지, 각 묶음 안은 최근 요청 먼저. **카톡과의 관계(v3.5):** 오늘 보고가 카톡으로 다 나갔으면(`KakaoReport.sentToday`, 스위치 켬) 첫 줄 "카카오톡 나와의 채팅에 아침 보고를 보냈어요"를 단 **조용한** 알림(조용한 그룹 `ieum.calls.quiet`), 단 업데이트 대기·"늦게 정리된 통화" 줄이 있으면 소리 있게. 카톡 전송이 진행 중이면 최대 2분 기다린다. 실패·일부만·미연결·스위치 끔·키 없는 빌드면 예전 알림 그대로 | `CallDigestText.morning`·`ranked`·`withUpdate`, `CallDigestKakao.postMorning`, `NotificationActions` |
| 자가 진단 | 저녁 보고 작업 끝(밤중 건너뜀) | 자막 밀림, 모델 실패, 폴더 풀림, 결과 없음, 저장 80%, 녹음 공백(1일째, 2일째, 그 뒤 7일마다), 업데이트 하루 미설치(하루 안이면 "새 버전 설치가 기다리고 있어요" `update_waiting`, v3.5), 분석 루틴 4슬롯 놓침(`routine_late`), 카톡 연결·켬인데 오늘 다 나가지 않음 "아침 보고가 카카오톡으로 안 갔어요"(`kakao_missed`, v3.5) | `SelfCheckNotices` |
| 업데이트 | 확인이 필요할 때 | 문구는 version.json notes, [지금 업데이트]. 확인 대기 24시간이면 세션을 버리고 다시 커밋해 알림을 새로 띄운다(v3.5, 3.11) | `CallUpdate` |

### 3.10 하트비트(`status/phone.json`, `PhoneStatus`)
매시간 틱 끝과 [문제 알리기]에서 덮어쓴다. 숫자·코드·참거짓만, 이름·번호·파일 이름·통화 내용 없음(`PhoneStatusTest`). 문자열 값은 `code()`로 최상위에 두고 나머지 객체는 `counts()`로 숫자만 남긴다.

| 묶음 | 키 |
| --- | --- |
| 기본 | atMs, appVersion, versionCode, consent, folder, notifyAllowed, batteryFree, standbyBucket(5→EXEMPTED, 50→NEVER), tokenExpiresMs, storageUsedPercent, dbPercent, dbOpenMs, homeReadMs, dbRecovery, storeOpenError, rescheduledMs |
| 발견·자막 | lastDiscoveryMs, lastDiscoveryAttemptMs, lastDiscoveryOutcome, inventoryTruncated, heldUnsupported, queuedCount, oldestQueuedMs, newQueuedCount, newOldestQueuedMs, oldQueuedCount, lastTranscribedMs, modelState, audioChannels, audioChannelCorr, transcriptionFailed, transcription{failReasons, queuedByLen, doneByLen48h, long48h{total,done,ratePct}, oldestWaitMin, runs, decode} |
| 업로드·결과 | lastUploadMs, lastResultMs, pendingAnalysis, lastInboxError, lastInboxErrorSinceMs, resultChain{round,nextMs}, results{applyFailed,bad,receivedNotApplied}, droppedItems, duplicateSkipped, latency{p50Min,p90Min,n,outliers}, guard, recordTime{fileVsRecordMin} |
| 처음 정리 | firstPass{done,toTranscribe,audioMinutesLeft,waitingForSummary,batchesToday,bound,blockedBy} |
| 통화기록 | callLog{allowed,fromMs,todayFromMs,since,today(total,incoming,outgoing,missed,rejected,other,recorded,unrecordedCalls,personal),latestDeviceLogMs,latestStoredLogMs,deviceTotal(최대 20,000),storedTotal,mismatchSinceMs}, callLogMatch |
| v3.1 | permissions{phoneState,overlay,redirectRole,installAllowed}, recordingGap{calls,withFile,matched,newestFileMs,sinceMs}, recordingGapVerdict, followup{noRecording} |
| 카드·업데이트·기타 | callCard(4절 통계), updateAvailableCode, updateReadySinceMs, lastUpdateError, update{error}, messages{mailError}, golden |
| v3.5 아침 보고 | kakao{on(스위치 "카카오톡으로 받기"), linked, sent(다 나간 날 누계), failed(실패 누계), lastSentMs(마지막으로 다 나간 시각), error(코드 80자, 없으면 null)}, report{day(마지막으로 보낸 보고 날짜 YYYY-MM-DD), source(`routine` 또는 `phone`)}. 두 객체는 늘 있다(워치독이 키로 판정). `transcription.requeuedFailed`(이번 빌드가 살린 실패 자막 수) |
| v3.6 전력 | power{batteryPct,plugged,status,powerSave,thermal,headroom,tempDeciC,currentUa,cpuset,cpusAllowed,today·prev{day("yyyy-MM-dd"),battery{runs,ms,min,audioMin,mAhScreenOff},plugged{…},budgetMin,budgetHit,각종 Skips,plugDropped,killedChunkMs,deadlineMs,yieldCount,modelLoads,modelLoadMs,stopReasons},chunk{coldMedianMs,p50Ms,lastMs,threads,tokensLast(null)},waiting{n,oldestMin},chargingOnly}(전체 키는 OPERATIONS.md) |

통화 목록 대조표 `status/calllog.txt`는 이름·번호 전체가 든 사람용 글이다(사용자 결정 11/4, 아내 동의, 비공개 우편함에만, 같은 내용이면 안 올림)(`CallLogReport`, OPERATIONS.md 4절).

### 3.11 업데이트(`CallUpdate`)
- 확인: 앱을 열 때 바로, 6시간 주기 작업(`ieum.update`), 그리고 결과 받기마다 `app/version.json`을 ETag로 읽어 처음 보는 높은 versionCode면 즉시. 네트워크 종류 무관(모바일 데이터도).
- 받기: 1초마다 진행률(설정 "앱 버전" 줄 "받는 중 n%"), 3분 멈추면 멈춤 처리, sha·크기 불일치면 지우고 다시.
- 무인 설치 조건(모두 맞아야 함): API 31 이상, 매니페스트 `UPDATE_PACKAGES_WITHOUT_USER_ACTION`, 자기 자신을 업데이트하는 세션 + `USER_ACTION_NOT_REQUIRED`, `canRequestPackageInstalls()` 참(아니면 `INSTALL_SOURCE_NOT_ALLOWED`와 선택 처음 설정 단계), 새 APK targetSdk ≥ 기기 최소(안드로이드 16: 34, 우리 36), 다른 스토어가 소유권을 갖지 않음. 하나라도 어긋나면 `STATUS_PENDING_USER_ACTION` → `USER_ACTION_REQUIRED`, 알림 한 번 누름. 스위치 "새 버전은 자동으로 설치"(기본 켬). 사이드로드한 앱의 첫 자가 설치도 이 확인이 한 번 필요하다(11/6 아내 폰).
- 재알림(v3.5): 같은 versionCode의 세션이 열려 있으면 결과를 기다리지만, 확인 대기(`wait_since_ms`, 없으면 세션 시작 `session_ms`)가 24시간(`CONFIRM_AGAIN_MS`)을 넘으면 세션을 abandon하고 다시 커밋해 안드로이드 확인 알림을 새로 띄운다(알림을 밀어 지운 경우 대비). 받아 둔 새 버전이 기다리는 동안 아침 앱 알림과 저녁 자가 진단에 "새 버전 설치가 기다리고 있어요" 한 줄(`installWaiting`), 홈 배너는 그대로.
- 서명: release-apk가 직전 릴리스와 인증서를 비교하고 version.json `certSha256`과도 비교, 다르면 배포하지 않는다. "서명이 다르다"면 앱을 지우지 않는다(지우면 저장된 통화가 사라짐).

### 3.12 아침 보고(카카오톡, v3.5, `pipeline/report/`)
**보고서(새벽 정리 9-1번):** 04:46 새벽 정리가 `report/YYYY-MM-DD.json`(한국 시각 오늘)을 쓴다. 한 줄 JSON `{day, generatedMs, tidyRev, head{calls,newChecks,todayChecks,issues}, yesterday[{who,what}]×8, today[{id,what,who,since,status,check}]×8, inProgress[{what}]×3, check[{what,who}]×3}`. `yesterday`는 어제 업무 통화·문자·메일을 업체(없으면 사람) 단위로 묶어 약속·금액·날짜가 있는 줄 먼저. `today`는 확인 필요·오늘·어제 요청 먼저, 그다음 최근 요청순. `since`는 "오늘 요청"·"어제 요청"·"3일 전 요청", `status`는 뒤의 문자·메일·통화에서 보인 이행 흔적("메일 발송 확인 완료", 없으면 빈 문자열, 완료 처리는 안 함). 한 줄 60자, 보고체, 가운데 점·말줄임표·영어 상태값·"기한"·"지남"·칸 끝 "까지" 금지, 전화번호 금지. `tools/report_check.py`(형식·줄 수·금지어·전화번호·"~해요"체 검사, 내용은 출력하지 않음)를 세 번 안에 통과해야 "tidy" 커밋에 넣고, 못 하면 파일을 지우고 답 첫 줄에 " | 확인 필요: 아침 보고서". 30일 지난 report 파일은 지운다.

| 단계 | 동작 | 근거 |
| --- | --- | --- |
| 연결 | 설정 "아침 보고"의 [카카오톡 연결하기] → 카카오 SDK 로그인(카카오톡 앱이 있으면 앱, 없으면 계정) → 동의 항목 `talk_message`만(프로필·친구 목록 안 받음). 성공하면 `KAKAO_LINKED`, SDK가 토큰을 갱신. 토큰 오류(`TOKEN_*`)면 `KAKAO_LINKED=false`로 내리고 "연결이 풀렸어요" | `KakaoReport.link`·`linkState`, `KakaoSdkSender` |
| 받기 | 고유 작업 `ieum.report.morning`이 매일 05:30 KST(`REPORT_SEND_HOUR`·`MINUTE`, 앱 알림 시각 `MORNING_HOUR`와 별개)에 `report/<오늘>.json`을 GitHub contents API로 ETag 읽기(304는 저장본). 없거나(404) 날짜가 다르면 05:35·05:45·06:00 재시도(`TRIES` 0·5·15·30분). 06:00에도 없으면 **폰 폴백**: 폰의 열린 체크할 일(홈 `firstLook` 규칙 먼저)로 만든 짧은 보고 1통, `report.source="phone"`. 네트워크 연결 제약으로 기다리되 **09:00이 지나면 보내지 않는다**(`CUTOFF`, 그날은 앱 알림만). 한 날의 사슬이 끝나면 다음 날 05:30을 건다. 앱 시작·부팅·매시간 틱이 KEEP으로 다시 건다. 키 없는 빌드, 스위치 끔, 미연결이면 아무것도 안 함 | `ReportFetch`, `ReportMorningWork` |
| 조립 | ① **피드 1통:** 보고서 전체를 세로 PNG 한 장으로 그림(폭 1080px, 420dpi 기준 제목 28sp·절 22sp·본문 20sp, Pretendard, 높이 2400px·5MB 안) + 제목 "[이음 아침 보고] 11/7(금)" + 본문 "어제 통화 n건, 새 체크 m개, 오늘 챙길 일 k개" + 버튼 [이음에서 보기](통화 탭, 어제) ② **글 1통:** "[오늘 챙길 일] 11/7(금)" + 상위 4개 번호 목록 + "외 n건", 200자 안, 버튼은 체크 탭(오늘 챙길 일이 없으면 생략) ③ **폴백 글 최대 4통**(각 200자, 줄 단위로 끊음): "[이음 아침 보고]"+머리+어제 정리 / "[어제 정리 계속]"(①에 다 못 들어갔을 때만, 남은 수 "외 n건"은 여기서) / "[오늘 챙길 일]" / "[진행 중과 확인 필요]". 폰 폴백은 글 1통 "[이음 아침 보고] 11/7(금) (새벽 정리 없이 폰에서 만든 보고)" + 상위 4개 | `KakaoReport.compose`·`render`, `LIMIT` 200 |
| 보내기 | 이미지 업로드(`uploadImage`) → 피드 → 1초 → 글. 업로드나 피드가 실패하면(토큰 오류가 아니면) 폴백 글을 1초 간격으로, 각 통 실패하면 한 번 더. 토큰 오류는 바로 멈춤. 받는 사람은 **아내 본인 "나와의 채팅"뿐**(친구 보내기·`friends` 동의 안 씀) | `KakaoReport.deliver` |
| 기록 | 한 통이라도 나가면 `REPORT_DAY`·`REPORT_SOURCE`를 써서 **같은 날 다시 보내지 않는다**. 전부 성공이면 `KAKAO_LAST_SENT_MS`, `KAKAO_SENT_COUNT`+1, 오류 지움. 하나라도 실패면 `KAKAO_FAILED_COUNT`+1, `KAKAO_LAST_ERROR`(코드 80자). 한 통도 못 나갔고 토큰 오류가 아니면 다음 재시도가 다시 보낸다 | `KakaoReport.send` |
| 시험 전송 | 설정 [지금 보내 보기]: 오늘 받은 보고서가 있으면 그것, 없으면 폰 폴백. 하루 1회(`kakaoTestSentDay`), `REPORT_DAY`·카운터·마지막 전송은 안 건드림(새벽에 눌러도 05:30 정식 보고가 막히지 않음) | `KakaoReport.sendNow` |
| 버튼 | 메시지 버튼은 `kakao<앱 키>://kakaolink?screen=calls&dayMs=…` 또는 `screen=tasks`. `MainActivity`가 intent-filter로 받아 알림 extra와 같은 길로 연다. `dayMs`는 최근 5년~이틀 뒤만, 그 밖은 오늘. 그 밖의 값은 홈 | `MainActivity.kakaoTarget`·`kakaoDay`, AndroidManifest |

## 4. 통화 카드

| 항목 | 동작 | 근거 |
| --- | --- | --- |
| 권한 | READ_PHONE_STATE(필수 처음 설정), READ_CALL_LOG(필수), 다른 앱 위에 표시(선택), 발신 역할 `ROLE_CALL_REDIRECTION`(설정의 선택 항목 "거는 전화에 번호를 더 빨리 알기"만, 카드는 역할 없이 뜸) | `SettingsScreen.steps`, `callCardGroup` |
| 번호 | 수신은 `PHONE_STATE`의 `EXTRA_INCOMING_NUMBER`, 발신은 OFFHOOK 방송의 번호(역할이 있으면 `OutgoingCallService.onPlaceCall`이 먼저 적고 즉시 `placeCallUnmodified`, 20초 안에 OFFHOOK이면 발신 확정). 첫 방송에 번호가 없으면 다음 방송을 기다린다. CallScreeningService는 쓰지 않는다(전화부 상대를 넘기지 않음) | `CallSession`, `OUTGOING_WINDOW_MS` |
| 서비스 | 포그라운드 서비스 `CallCardService`(phoneCall, 거부되면 specialUse). 이미 돌면 정적 참조로 상태 전달, start는 첫 시작에만. 시작 거부면 `fgsRefused`를 세고 카드를 직접 띄운 뒤 0.5초 뒤 한 번 더 시작. IDLE 3초 뒤 종료, 10분마다 `isInCall` 점검, 4시간이면 무조건 종료. 오버레이 권한이 없어도 서비스는 시작한다(알림 대체) | `STOP_AFTER_IDLE_MS`, `CHECK_MS`, `MAX_LIFE_MS` |
| 판정 순서 | IDLE이면 없음 → 개인·제외(`ignored`) → 통화 대기(`waiting`) → 설정을 못 읽으면 `off` → 방향 스위치(전화 올 때/걸 때, 기본 켬) 꺼짐 `off` → 번호 없음 `noNumber` → 카드가 있거나 "아직 정리된 통화가 없는 번호도"(기본 끔)면 표시, 아니면 `noCard` | `CallCardService.verdict` |
| 콜드 캐시 | 벨 경로는 큰 DB(CallRecordStore)를 열지 않는다. 카드 파일의 `blocked(phone_key)` 표(개인 + 제외 번호)만 읽고 카드 파일 열기는 통화당 1회 이하. 제외 규칙이 바뀌면 blocked 표를 다시 쓴다. 읽기 실패는 개인으로 친다. blocked 표를 한 번도 쓰지 않은 첫 통화(업데이트 직후)만 옛 경로. 설정은 통화당 한 번 스냅숏. 개인 번호 "기억"은 통화가 끝난 뒤 큰 DB에서 | `ContactCards`, DESIGN_V4 4.6 |
| 수신 | 벨: 펼친 카드(위치 아래), 받으면(OFFHOOK) 3초 뒤 위쪽 띠로 접힘, 띠를 누르면 펼침/접힘, 끊으면 사라짐 → 통화별 알림 | `COLLAPSE_AFTER_MS` 3,000 |
| 발신 | 처음부터 화면 위쪽 4~25% 안에 펼친 채 3초 → 띠. 요약·할 일이 없으면 띠 대신 숨김 | `OUT_TOP`, `OUT_BOTTOM` |
| 잠금 | 잠금 상태 벨: 오버레이 창과 내용 있는 알림을 함께 띄우고 `ACTION_USER_PRESENT`에 알림을 지움. 서비스 알림(741)과 카드 알림(3011)은 오버레이 쪽에서 하나로. 알림 공개 내용은 "잠금 화면에서도 이름과 한 줄 보기"(기본 켬)면 이름·업체·체크할 일 1줄(번호·금액·자막 없음) | `CallCardOverlay`, `KEY_LOCK_DETAIL` |
| 통화 대기 | OFFHOOK 중 RINGING이면 첫 상대 카드를 숨기고 다음 IDLE까지 아무것도 띄우지 않음. phoneKey·시작 시각 유지, CallEnd는 첫 통화 하나 | `CallSession` |
| [닫기] | 그 통화 동안만 숨김. 손잡이로 끌어 옮기면 위치 기억, 설정 [카드 위치 처음으로] | `KEY_POS_Y` |
| 위치 상수 | 기본 위쪽 끝 38%, 벨 중 높이 한도 34%, 아래 끝 ≤ 72%(끌어 옮긴 위치도 이 안으로), 통화 중 띠 위쪽 8~20%(기본 10%) | `DEFAULT_POS_Y`, `MAX_HEIGHT`, `BOTTOM_LIMIT`, `STRIP_TOP*` |
| 창 | `TYPE_APPLICATION_OVERLAY`, 포커스 없음, 터치는 카드 영역만, `FLAG_LAYOUT_IN_SCREEN`, **`FLAG_SECURE`**(스크린샷에 검게 나오는 것이 정상). 1단계 이름 줄 → 16ms 뒤 나머지 | `CallCardOverlay` |

**카드 글(3초용, `CallCardView.Lines`)**: 입력은 상대 카드 v2와 지금 시각(KST). 첫 줄 고르는 순서 ① `dueMs`가 지났거나 7일 안인 열린 체크할 일 ② `checkReason`이 있는 일(배지 "확인 필요") ③ 14일 안 지난 통화 요약 첫 문장 ④ 진행 중인 일(기한을 해석할 수 없는 일 포함) ⑤ thin("3일 전 통화, 30일간 5번") ⑥ 모르는 번호("아직 정리된 통화가 없는 번호예요"). 같은 순위면 기한 빠른 것, 그다음 최근 요청. `dueMs`는 순서를 정하는 데만 쓰고 글에는 쓰지 않는다. **체크할 일 줄의 상태 글(v3.5, 기한 표현 없음, `statusWords`):** "제목, 상태" 꼴로, 상태는 ⓐ 끝났는지 확인 중(`needs_confirmation`)이면 "끝났는지 확인" ⓑ 뒤의 문자·메일·통화가 남긴 메모("11/5 메일: 끝난 것으로 보여요")가 있고 숫자 경고 메모가 아니면 "메일에서 끝난 것으로 보여요" ⓒ 아니면 요청 날짜("오늘 요청", "어제 요청", "3일 전 요청"). "금요일까지"·"n일 지났어요"는 쓰지 않는다. 첫 줄 20sp 굵게, 36자(2줄 × 18자) 안 띄어쓰기 경계, 말줄임표 없음. 둘째 줄 "김민수 대표, ○○철강, 3일 전"(쉼표 구분, 가운데 점 없음). 셋째 줄 체크할 일 14자 + "외 n개", 넷째 진행 중인 일 14자. 전화번호만 가리고 금액·날짜·수량은 보인다(잠금 알림 한 줄은 금액도 지움). 띠·잠금 알림(`noticeLines`, `strip`)도 같은 순서. 모르는 번호 카드는 최근 문자 대신 통화기록 한 줄(벨 경로에서 큰 DB를 열지 않으려고).

**통계(`CardStats` → 하트비트 `callCard`)**: 누계와 `today`(KST 하루). 키 calls, shown, shownOutgoing, eligibleOutgoing, noCard, thin, noNumber, noNumberIn, noNumberOut, noRole, ignored, off, locked(잠금이면 창·알림을 함께 띄워도 locked), cold(프로세스 시작 5초 안 첫 방송), fgsRefused, noPermission(`canDrawOverlays` 거짓일 때만), noNotify, expanded, closedUnder2s(2초 안 닫음). 시간 ringToCardMs·ringToCardMsCold {p50,max,n}(최근 50통화, 수신기 elapsedRealtime → 창 추가나 알림 게시), cardOpenMs. 번호 없음은 통화가 끝날 때만 센다. v3.x가 통화를 하나라도 보면 null 대신 0.

**상대 카드 저장소(`ContactCards`)**: 별도 봉인 파일 `contact-cards-v1.sealed`, 표 `contact_cards(phone_key PK,json,updated_ms)`, `blocked(phone_key)`, `card_meta(k,v)`. 카드 JSON v2: v, people[{personId,name,role,isStaff}], company{id,name,trade}, lastCallMs, lastDirection, lastDurationMs, calls30d, recent[{atMs,kind,summaryFirst}]×3(40자), openTasks[{id,title,requestedMs,status,aiNote,tentative,dueMs,checkReason}]×5, issues[{title,status}]×3, texts[{atMs,dir,first}]×2, thin, updatedMs. dueMs는 due가 "YYYY-MM-DD"일 때만, checkReason은 ai_note가 있으면 `confusable` 아니면 `unsure`. 통화기록만 있는 번호도 thin 카드(30일). v1 파일은 읽고 다음 갱신에 v2로. 조회는 메모리 → 파일, 바뀐 번호만 1.5초 모아 다시 씀.

## 5. 화면

### 5.1 구조
- Activity 하나 `MainActivity`(singleTop, 세로 고정) + `enum Screen { HOME, CALLS, TASKS, COMPANIES, SETTINGS, PERSON }`, 화면은 `screens/*Screen.kt`. 하단 탭 다섯(홈, 통화, 체크, 업체, 설정), 사람 화면은 탭이 없고 연 화면의 탭이 켜진 채. 현재 탭을 다시 누르면 상세를 닫고 목록 맨 위. 뒤로: 상세 닫기 → 홈 → 종료. 알림은 `screen`·`dayMs`·`callId` extra로 연다(카톡 보고 버튼의 실행 파라미터도 같은 extra로 바꿔 연다, 3.12). 화면 전환 120ms 페이드, 시작 화면 `LaunchIntro`(약 2초, 누르면 건너뜀). 골든 측정만 별도 숨김 Activity.
- 즉시 반응: 600ms 두 번 누름 방지(`CallUi.once`), 누르는 즉시 busy 글, [다 됐어요]는 바로 흐려지고 "끝낸 일로 옮겼어요 [되돌리기]" 띠. 저장소는 프로세스 수명 동안 한 번 열고(`IeumApplication.store`) 메인 스레드 접근은 예외.
- 화면은 XML·Compose 없이 코드로 View를 만든다(`CallUi.kt`).

### 5.2 화면별
| 화면 | 보이는 것(위부터) |
| --- | --- |
| 홈 | 인사·날짜 → (설정에서 할 일이 있을 때만) "설정에서 확인할 일이 있어요 ›"(녹음 공백 확정, 업데이트가 사람 확인 대기·실패, 필수 처음 설정 남음, 모델 받기 실패; 누르면 설정의 그 줄) → **오늘 통화 이야기**(제목 "녹음 n개 중 정리 m개"(v3.5, 녹음 기준 `TodayStory.countLine`), `story/today.json` text는 문장 단위로 120자 안(`TodayStory.clip`, 문장 중간에서 자르지 않음, maxLines 7은 안전망), 없거나 날짜가 다르면 오늘 정리된 업무 통화 "상대: 첫 문장" 최신 3개, 정리된 통화 0이면 "오늘은 아직 정리된 통화가 없어요" 한 줄) → 먼저 볼 체크할 일 5개("외 n개 보기 ›") → 오늘 통화 메모(정리 전이면 "오늘 녹음 n개를 정리하고 있어요") → "오늘 한눈에" 숫자 셋(오늘 녹음, 오늘 생긴 체크, 남은 체크할 일)과 알약 "녹음 n개 중 정리 m개" → 업체별 체크할 일 → 이번 주 통화 막대. 녹음 수 = 오늘 통화 기록 + 아직 기록이 없는 자막 대기·실패 녹음(`CallTranscriptionStore.withoutRecord`), 통화기록 총수는 쓰지 않음 |
| 통화 | 날짜 넘기기(전날/오늘로/다음날), 그날 모든 통화기록이 한 줄씩(받은·건·부재중·개인 통화 "개인 내용" 포함), 문자·메일 기록, 검색(250ms, 사람 절 최대 5명). 상태 글: 자막 작업과 창이 맞으면 "녹음을 글자로 바꾸는 중이에요", 통화 뒤 10분까지 "녹음을 찾는 중이에요", 그 뒤 "녹음 없음", 6시간 넘으면 "정리가 늦어지고 있어요". 빈 자막은 "녹음에서 말소리를 찾지 못했어요"(버튼 없음), `RESULT_NOT_APPLIED`는 "정리 결과를 적용하지 못했어요" + [다시 정리하기](보낸 기록을 지워 바로 재전송). 상세: 요약, 중요한 내용, 체크할 일([다 됐어요], 접기), [통화 내용 전체 보기](14일) |
| 체크 | 남은 체크할 일을 요청 날짜 묶음(오늘 요청한 일, 어제 요청한 일, 그전에 요청한 일, 오래된 일), 진행 중, 끝낸 일. 행(G3): 업체 배지 → 제목(META 이름과 같은 "○○ 님께" 접두는 지움) → META "김민수 님, 오늘 요청"(16sp, 이름 누르면 사람 화면) → AI 메모 → "확인 필요"·"끝났는지 확인" 배지 → [다 됐어요]. 통화에서 온 항목만 눌림. 30개 먼저 그리고 나눠 그림. [직접 적기] |
| 업체 | 업체 목록(최근 통화순, 공종 배지, 열린 일 수), 업체 없는 사람(우리 직원 등). 상세: 업체 메모, 사람(누르면 사람 화면), 진행 중인 일, 체크할 일, 최근 통화 10건, [업체 이름 고치기] |
| 사람 | 이름(30) + 직책·업체, [전화 걸기] [문자 보내기](번호는 화면에 안 씀), 공유 번호면 "이 번호를 쓰는 사람" 칩, 마지막 통화, 체크할 일, 진행 중인 일, 최근 통화와 문자 10건, [이름 고치기], [이 번호는 개인으로 두기](카드 즉시 삭제) |
| 설정 | (주황 불이 있으면 "지금 상태"가 맨 위) → **지금 할 일과 진행 상황**(처음 설정 단계, 처음 정리 진행·쉬는 이유, 모델 받기 %, 녹음 공백 안내; 할 일이 없으면 "지금 할 일이 없어요. 모두 잘 되고 있어요") → 연결(녹음 폴더, 문자, 통화기록, 통화 끝 알아차리기, 메일 IMAP 네이버·다음·Gmail·직접 입력) → 통화 카드(카드 보기 허용, 전화 올 때, 전화 걸 때, 거는 전화 역할, 아직 정리된 통화가 없는 번호도, 잠금 화면에서도 이름과 한 줄 보기, [카드 위치 처음으로]) → 정리(정리 동의, 알림 시각 "저녁 통화 정리"(15~23시, 기본 19시), 통화마다 알림, 새 버전은 자동으로 설치, 설치 허용, 내 정보 세 칸, 개인으로 둔 번호) → **아침 보고**(v3.5, 아래) → 상태와 도움(신호등, 앱 버전 줄·[지금 확인]·[지금 업데이트], 저장 공간 %, 음성 인식 모델, 현장 상황 메모, 처리한 통화 수, [기다리는 통화 다시 정리하기], [합친 사람 되돌리기], [상대 카드 다시 만들기], [문제 알리기], [골든 측정]) |

**설정 묶음 순서(v3.5):** (주황 불이면 지금 상태) → 지금 할 일과 진행 상황 → 연결 → 통화 카드 → 정리 → 아침 보고 → 상태와 도움.

**"정리" 묶음의 "글자로 바꾸기"(v3.6):** "배터리를 아끼려고 긴 녹음은 충전 중에 글자로 바꿔요. 오늘 배터리로 n분 썼어요(한도 20분)" 줄과 스위치 [충전 중에만 글자로 바꾸기](`TRANSCRIBE_CHARGING_ONLY`, 기본 끔, 바꾸면 자막 작업을 다시 건다). "지금 할 일과 진행 상황"에 충전 대기가 있으면 "녹음 n개는 충전기에 꽂으면 글자로 바꿔요". 통화 탭 줄·통화별 알림은 통화 끝 10분 뒤부터 그 통화 녹음이 충전을 기다리면 "충전하면 이어서 정리해요", 저녁 알림 마지막 줄 "충전하면 n개를 이어서 정리해요"(`PowerText`).

**"아침 보고" 묶음(`SettingsScreen.morningGroup`, 정리 묶음의 "아침 체크할 일" 시각을 옮겨 승격):**
| 부분 | 보이는 것 |
| --- | --- |
| 카카오톡(키 있는 빌드만) | 스위치 [카카오톡으로 받기](`MORNING_KAKAO`, 기본 켬). 끄면 "꺼 두면 아침 보고는 이음 알림으로만 와요." 켜고 **연결됨**: 초록 "연결됨, 매일 5:30에 보내요", 마지막 전송 "오늘 5:30에 보냈어요"(어제, "11월 4일 …", 없으면 "아직 보낸 적이 없어요"), [지금 보내 보기](결과 한 줄과 토스트, "시험으로 하루에 한 번 보내요. 매일 5:30 보고는 그대로 가요."), 글자 링크 [연결 끊기]. **연결이 풀림**(토큰 오류): 앰버 "카카오톡 연결이 풀렸어요" + [다시 연결]. **연결 안 됨**: "카카오톡을 연결하면 매일 아침 5:30에 나와의 채팅으로 어제 통화 정리와 오늘 챙길 일을 보내요." + [카카오톡 연결하기] |
| 이음 알림 | 안내 한 줄(연결·켬이면 "카카오톡으로 보낸 날에는 이음 알림이 조용히 와요. 보내지 못한 날에는 평소처럼 와요.", 아니면 "매일 이 시각에 이음 알림으로 체크할 일을 알려 드려요.") + 시각 "아침 체크할 일"(4~11시, 기본 6시). 키 없는 빌드는 이 시각 줄만 |

### 5.3 처음 설정(`SettingsScreen.steps`)
| 구분 | 단계(순서대로) |
| --- | --- |
| 필수 | 녹음 폴더 연결 → 정리 동의(직후 모델 받기 시작) → 통화기록 읽기 허용 → 통화가 끝난 걸 바로 알아차리기(전화 상태) → 알림 허용(두 번 거절 뒤엔 알림 설정 열기) → 배터리 제한 풀기 |
| 선택([나중에 할게요]) | 문자 읽기 허용 → 통화 화면 위에 이음 카드 보기(돌아왔는데 안 켜졌으면 "목록에서 '이음'을 찾아 스위치를 켜 주세요" + [다시 열기]) → 새 버전을 저절로 설치하기(설치 출처 허용) |
- 막힌 단계가 있어도 다음 단계로 갈 수 있다. 이유 문장을 버튼 바로 위에 먼저 보인다. "처음 설정 남은 n단계"(필수가 남으면 앰버). 권한이 한 번 요청 뒤에도 막히면 "앱 정보 → ⋮ → 제한된 설정 허용" 안내와 [앱 정보 열기](`ReadingPermission`). 설정의 처음 설정은 접지 않는다.

### 5.4 신호등(`StatusLights.lights`, 설정 "지금 상태")
| 줄 | 주황 조건(위에서부터 처음 맞는 것) |
| --- | --- |
| 녹음 가져오기 | 폴더 미연결 / 권한 풀림 / 발견 성공이 24시간 넘음 / 녹음 공백 판정 문구(확정된 `noRecording`·`partial`·`folder`, `tooMany`) / (v3.5) `noMatch`가 48시간 넘게 이어짐 "녹음이 2일 넘게 통화와 이어지지 않아요. 관리하는 분께 알려 주세요." `pendingText`는 초록 글 |
| 글자로 바꾸기 | 모델 지원 안 됨·실패·없음 / (v3.6) 충전을 기다리는 녹음의 가장 오래된 것이 36시간 넘음 "녹음 n개가 이틀째 충전을 기다려요. 밤에 충전기에 꽂아 두면 정리돼요"(그 전엔 초록 "충전하면 n개를 바꿔요"; "밀려 있어요"는 대기 수가 충전 대기 수보다 많을 때만) / 대기열이 있고 가장 오래된 진입이 6시간 넘고 마지막 자막도 6시간 넘음 / (v3.5) 지금 FAILED인 자막이 있음 "글자로 바꾸지 못한 녹음이 n건 있어요. 새 버전이 오면 한 번 더 바꿔 봐요."(수는 `StatusLights.read`가 IO 스레드의 같은 저장소 읽기에서 셈) |
| 통화 정리 | 정리 동의 꺼짐 / 분석 루틴 예정 실행을 2회 놓침("분석이 n시간 안 돌았어요", 슬롯 뒤 40분이 지나도 자가 보고가 없으면 놓친 것, 받은편지함 오류가 더 늦으면 세지 않음) / 저장 공간 80% 이상 / 정리 못 한 통화(빈 자막 제외) / 기다리는 통화 |
| 알림 | 알림 꺼짐 / (v3.5, 스위치 켬일 때) 카카오톡 연결이 풀림 "카카오톡 연결이 풀렸어요. 위 아침 보고 묶음에서 다시 연결해 주세요." / 연결·켬인데 실패가 있고 마지막 성공이 3일 넘음(성공한 적이 없으면 실패 3회 이상) "아침 보고가 3일 넘게 카카오톡으로 가지 않았어요. …지금 보내 보기를 눌러 주세요." 누르면 아침 보고 묶음 |
| 업데이트 | 설치 오류 문구 / 준비된 지 24시간 넘음 / 준비됨 |
| 통화 카드 | 끄면 초록 / 오버레이 권한 없음 / 전화 상태 없음 / 통화기록 없음 / 대기 버킷이 EXEMPTED·NONE·UNKNOWN 밖 / 누적 통화 5건 이상에서 번호 없음 20% 이상 / 카드 없음 60% 이상. 역할이 없으면 초록 안내 |
- 빨강은 쓰지 않는다. 4회 놓침은 저녁 자가 진단 `routine_late`로 알린다. 줄을 누르면 고칠 묶음으로 스크롤. 주황 불이 하나라도 있으면 신호등이 페이지 맨 위로 가므로 문구의 "위 ○○ 묶음"을 "아래 ○○ 묶음"으로 바꿔 보인다(v3.5, `StatusLights.below`).

### 5.5 디자인 토큰(`CallUi`)
| 항목 | 값 |
| --- | --- |
| 글꼴 | Pretendard(SIL OFL) 굵기 4종, 하나의 가족 |
| 크기(sp) | 제목 30 Bold, 큰 숫자 36 Bold, 절 20 SemiBold, 본문·요약 19, 곁글 18, META 16(목록 행 META 줄 전용 `meta()`), 캡션 16(차트 라벨·한 줄 안 일부), 배지 15 SemiBold, 탭 16. 버튼 외 글은 18sp 이상이 규칙이고 16sp는 `meta()`와 배지만 `ui_min_sp` 태그로 예외 |
| 색(밝게) | 바탕 #F2F4F6, 시트 #FFFFFF, 선 #EEF0F2, 글 #191F28, 보조·흐림 #6B7684, 회색 바탕 캡션 #5F6B78, 강조 #3268F0(화면당 한 곳), 앰버 #B26B00·연한 #FFF4E0·막대 #F5A524, 확인 #0B8A5F. 어두운 모드 색 별도(밤 설정 따라감) |
| 모양 | 흰 둥근 시트(테두리·그림자 없음), 사람은 원, 업체는 둥근 사각형, 2px 선 아이콘, 터치 44dp 이상, 8 격자(가장자리 20, 시트 안 16, 행 위아래 14, 절 24) |
| 문구 규칙 | 가운데 점(·)·말줄임표(…, ...) 금지(쉼표·띄어쓰기로), 목록 글은 자르지 않고 첫 문장만(`firstSentence`), 영어 상태값 금지, "~해요" 체, "기한"·"마감"·"급함" 금지, "할 일"이 아니라 "체크할 일" |
| "확인 필요" 배지 | 18sp 굵게, 앰버 막대 바탕에 진한 글자(대비 4.5:1 이상), 글자가 뜻을 전함 |
| 검사 | `tools/check_ui_rules.py`(ci): ① 한국어 화면 글에 가운데 점·말줄임표 ② 빨강 계열 색 상수 ③ ellipsize·WARN_SOFT·"기한" ④ 영어 상태값 ⑤ 새 `caption(` 경고(파일별 기준선). 단위 시험 `CallUiSeniorTest`·`ContextLedgerTest`가 18sp와 영어 금지를 검사 |

## 6. 데이터

### 6.1 폰 저장소
| 저장소 | 방식 | 내용 |
| --- | --- | --- |
| 통화 DB `callbrief-local-v2.db` | `ProtectedDatabase`: 파일 하나를 통째로 암호화(Keystore), 열면 메모리 SQLite로 복원, 트랜잭션마다 직렬화·암호화·fsync. 한도 16MB·10만 행, 80%면 경고. `.new`/`.bak` 복구 결과는 `dbRecovery` | 아래 표들 |
| 상대 카드 `contact-cards-v1.sealed` | 같은 방식 | 4절 |
| 설정 | `SecureSettings`(암호화 JSON) | 설정 키(`PipelineSettingsKeys` 상수 83개, 값 코드 일부 포함, `CallPorts.kt`). v3.5: `MORNING_KAKAO`, `KAKAO_LINKED`, `KAKAO_LAST_SENT_MS`, `KAKAO_LAST_ERROR`, `KAKAO_SENT_COUNT`, `KAKAO_FAILED_COUNT`, `REPORT_DAY`, `REPORT_SOURCE`, 그리고 `reportBody`·`reportETag`(받은 보고서와 ETag), `kakaoTestSentDay`. 카카오 토큰은 SDK가 따로 보관 |
| 녹음 금고 | `EncryptedAudioVault`(암호화 사본) | 자막 DONE 14일 뒤 원본 사본 삭제(`PRUNE_AFTER_MS`, 하루 1회 `ieum.calls.vault-prune`), `vault_pruned` |

**통화 DB 표**(`CallRecordStore.SCHEMA`, `STORE_V4_SCHEMA`, `CallTranscriptionStore`, 발견 원장 SQL)
| 표 | 핵심 열·용도 |
| --- | --- |
| call_records | record_id, recording_id, started_ms, duration_ms, counterpart, counterpart_key, transcript, segments_json, state(TRANSCRIBED/ANALYZED/FAILED/WAITING_CONSENT), summary, analysis_json, error, created_ms, analyzed_ms |
| workflow_items | 체크할 일·서류: kind(TODO/DOCUMENT), title, due_text, status, revision, evidence_quote, created_ms, done_ms, ai_note, source, tentative, last_call_ms |
| workflow_links, workflow_item_history, call_task_bindings | 항목과 기록의 연결, 사용자 수정 이력(있으면 AI가 안 바꿈), 결과가 만든 항목의 마지막 rev |
| call_inbox_sent | 보낸 시각 sent_ms, 받은 시각 result_ms |
| site_memo, companies(name, trade, memo, name_key), people(name, phone_key, company_id, role, is_staff, ledger_id, name_locked, name_key) | 장부 사본 |
| person_aliases, company_aliases, people_phones | 별칭(공백 제거 키), 사람 하나의 여러 번호·공유 번호 |
| call_links, task_companies, issue_companies | 기록↔사람·업체, 항목↔업체, 진행 중인 일↔업체 |
| call_log, call_record_log | 10/1 이후 폰 통화기록 전부(number_key, name, start_ms, duration_ms, type), 기록↔통화기록(direction) |
| ledger_edits | 우편함 `ledger/edits/`로 갈 사용자 수정 |
| issues | 진행 중인 일(ord, title, status, companies, record_keys) |
| personal_numbers, personal_streaks | 개인 번호(2회 연속 판정 뒤 기억, `PERSONAL_STREAK`), 연속 횟수 |
| person_merge_candidates | 자동으로 합치지 않은 쌍 |
| app_migration | `MIGRATION_VERSION`=4 |
| call_transcription_jobs(state QUEUED/RUNNING/DONE/FAILED/SKIPPED, reason, attempts, queued_ms, audio_ms, v3.5 failed_build·retried_build·requeue_count), _parts, _stalls, _splits, vault_pruned | 자막 대기열과 조각(v3.5 열은 실패 재큐용, 3.2) |
| recordings, source_versions, local_files, local_visibility, folder_grants 등 | 발견 원장(`vault2_ledger.sql`, `call_core_schema.sql`) |
- 보관: 자막·segments_json은 결과 받은 뒤 14일에 비움(`TRANSCRIPT_KEEP_DAYS`), 요약은 유지. 분석 전체는 90일 뒤 요약과 이름으로 줄임(`ANALYSIS_KEEP_DAYS`).

### 6.2 우편함 폴더(ieum-inbox)
| 경로 | 쓰는 쪽 | 내용 |
| --- | --- | --- |
| `inbox/<해시32>.json`, `inbox/b-<시각>-<해시>.json`, `inbox/selftest-*` | 폰 / inbox-e2e | 요청(파일 이름에 번호·이름 없음) |
| `results/` | 매시간 분석 | 결과. 폰이 적용 뒤 지움 |
| `bad/` | 양쪽 | 깨진 요청, 세 번 실패한 결과 |
| `ledger/ledger.json`, `ledger/edits/*.json` | 루틴 / 폰 | 장부 원본, 사용자 수정 |
| `tidy/state.json`, `tidy/proposal.json`, `tidy/project.md`, `tidy/history/`, `tidy/briefing.md` | 폰 / 새벽 정리 | 새벽 정리 입력·제안·기억 |
| `story/today.json` | 매시간 분석 | 오늘 통화 이야기 |
| `report/YYYY-MM-DD.json` | 새벽 정리(v3.5) | 아침 보고서(3.12). 폰이 05:30부터 ETag로 읽음, 30일 뒤 루틴이 지움 |
| `status/phone.json`, `status/calllog.txt`, `status/last-run-hourly.json`, `status/last-run-tidy.json`, `status/last-run.json`(v3.3까지, 새 파일이 없을 때만 읽음), `status/verify-log.md` | 폰 / 루틴 | 상태 |
| `routines/hourly.md`·`tidy.md`, `tools/watchdog.py`, `tools/report_check.py` | release-apk | 지시문·워치독·보고서 검사기 사본 |
| `app/version.json`, 릴리스 | release-apk | 업데이트 |

### 6.3 장부 `ledger/ledger.json`
`{rev, updatedMs, siteMemo(600자), companies[{name,trade,memo(400자),aliases,locked,mergedInto}], people[{id "p_…", name, company, role, isStaff, aliases[], locked[], mergedInto, keepApart[]}], issues[{id "i_…", title, companies[], status, recordKeys[], taskIds[]}], issueSuggestions[], corrections[], tidiedOnce}`. **원본은 저장소 장부**(사용자 결정 11/4)이고 폰은 rev가 오르면 받아 반영한다. 장부에는 전화번호·녹음·자막·문자 원문이 없다. edits 종류: `rename{id,name}`, `mergePeople{from,into}`, `unmergePeople{from,into}`, `renameCompany{from,to}`(모두 atMs). 업체의 mergedInto는 루틴이 다음 정리에서 이름이 안 보이면 지운다.

## 7. 개인정보와 보안

| 무엇 | 폰 밖으로 | 비고 |
| --- | --- | --- |
| 녹음 파일 | 안 나감 | 폰 금고에 암호화 사본, 14일 뒤 삭제 |
| 자막 | 우편함 요청으로 나감 | 번호·계좌·메일 주소 가림. 폰에서는 14일 뒤 비움 |
| 문자·메일 원문 | 같은 상대 최근 7일 업무 것 3,000자까지 요청에 | 개인 번호·제외 번호는 안 올림. 사진은 안 올림 |
| 전화번호 | 요청·장부·하트비트에 없음 | 요청은 counterpartId, "전화부에 없는 번호" 표시만. **예외: `status/calllog.txt`**(이름·번호 전체, 사용자 결정 11/4, 비공개 우편함, 화면·로그·알림·문제 알리기에 안 씀) |
| 이름 | 요청(이름 목록)·장부에 있음 | 번호 모양 부분 제거(`noNumber`, `safeName`) |
| 메일 주소 | 없음 | `[메일 주소]`, 보낸 사람은 이름만 |
| 개인 통화 | 결과 business=false 2회 연속이면 번호를 기억해 다음부터 안 올림, 설정에서 되돌림. 개인·제외 번호 통화는 카드·알림·기록 없음(통화 목록에는 "개인 내용" 한 줄) |
| 골든 측정 | 음성·정답 안 나감, 점수 숫자만 |
| 하트비트 | 숫자·코드만 |
| 아침 보고서 `report/*.json`(v3.5) | 우편함(비공개)에 이름·업체·요약·체크할 일, 30일 뒤 루틴이 지움 | 전화번호·자막·문자 원문 없음(`report_check.py`가 전화번호 검사) |
| 카카오톡 아침 보고(v3.5) | **카카오 서버로 나감**: 이름·업체·요약·체크할 일·금액·날짜 | 받는 사람은 **아내 본인 계정 "나와의 채팅"뿐**(친구 보내기·`friends` 동의 안 씀, 사용자 결정 11/6 08:40). 보고 이미지는 `uploadImage`로 카카오 CDN에 올라가 **URL만 알면 열리는 형태로 100일** 보관된다(URL은 길고 무작위, 나와의 채팅에만 감; 사용자에게 11/6 14시 알림, 원하면 글만 보내는 방식으로 바꿀 수 있음). 전화번호·자막·문자 원문은 넣지 않는다. 카카오 토큰은 아내 폰의 SDK에만 있고 루틴·저장소에는 없다 |

**알려진 약점(사실대로)**
- 우편함 토큰(`IEUM_INBOX_TOKEN`, 우편함만 다루는 fine-grained 토큰)이 빌드 때 `BuildConfig.IEUM_INBOX_TOKEN`에 그대로 들어간다. APK를 가진 사람은 우편함을 읽고 쓸 수 있다(`build.gradle.kts`).
- `IEUM_SIGNING_SEED` 비밀이 비어 있어 서명 키가 우편함 토큰에서 파생된다(`tools/derive_signing_key.py`, keystore 비밀번호 고정 "ieum-signing"). 토큰을 바꾸면 서명이 바뀌어 업데이트가 막힌다. 먼저 현재 토큰 값을 시드로 복사해야 한다(사용자 할 일).
- 배포 APK는 `assembleDebug` 산출물이다(디버그 빌드 유형, `SYNTHETIC_TOOLS_ENABLED=true`)(release-apk.yml). 비디버그 릴리스는 보류.
- 폰은 받은 APK의 sha256만 확인하고 서명 인증서는 비교하지 않는다(`CallUpdate.kt`; 남은 방어는 안드로이드의 같은 서명자 검사). 번호 해시는 소금 없는 SHA-256이고 개인 번호 해시가 코드에 있다(`LocalPrivacy.kt`). 무인 설치는 통화·자막 중인지 보지 않는다. 순서 있는 해결은 DESIGN_V7 3.2절.
- 앱 저장소가 공개다(저장소 안에 비밀은 없음, `tools/scan_export.py`가 ci에서 검사). 비공개 복귀는 사용자 할 일. 카카오 네이티브 앱 키는 빌드 비밀로 주입하고 패키지 `kr.callbrief`·키 해시(서명 인증서와 같은 것)로 묶여 다른 앱이 쓰지 못한다.
- 캡처 차단 `FLAG_SECURE`는 통화 카드 창에만 있다. 앱 본 화면은 캡처를 허용한다(사용자 결정 10/30).
- 루틴 실행 로그에 통화 내용이 남지 않게 지시문에 명시돼 있지만 강제 장치는 없다(달에 한 번 실행 기록 1건 확인, OPERATIONS.md 6절).

## 8. 감시와 운영

### 8.1 워치독 규칙(`tools/inbox/watchdog.py`)
루틴(매시간·새벽) 끝에서 `--apply`로 돈다. 같은 제목의 열린 이슈가 있으면 댓글도 달지 않고, 문제가 없어지면 "정상으로 돌아왔어요."로 닫는다. "n시간 계속"은 우편함 git 기록의 `status/phone.json`으로 센다(최근 50시간, 200개). 새 이슈가 있으면 루틴 답 첫 줄이 "확인 필요: 감시 이슈 n건". gh가 실패하면 그 단계만 건너뜀.

| 이슈 제목 | 조건 |
| --- | --- |
| 폰이 조용해요 | phone.json 없음 또는 atMs 12시간 넘음 |
| 녹음 가져오기가 멈췄어요 | 폰은 살아 있고 시도는 있는데 발견 성공이 12시간 넘음 |
| 통화 목록을 빠뜨리고 있어요 | deviceTotal ≠ storedTotal이 2시간 넘음 |
| 자막이 밀려요 | 새 녹음 대기열 진입이 6시간 넘음(처음 정리 중엔 남은 녹음이 있는데 마지막 자막이 24시간 넘을 때만). v3.6: 새 대기 수가 충전 대기 수(`power.waiting.n`)보다 많을 때만 |
| (v3.6) 폰 배터리를 많이 써요 | `power.today.battery.min` > 60이 다른 날에도(prev 또는 이전 하트비트) |
| (v3.6) 충전을 기다리는 녹음이 쌓여요 | `power.waiting.oldestMin` > 2160(36시간) |
| (v3.6) 자막 조각이 버려지고 있어요 | `killedChunkMs + deadlineMs`가 실행 시간의 20% 넘는 날이 today·prev 연속 |
| 새 버전이 설치되지 않아요 | 폰이 받아 둔 새 버전이 24시간 넘게 미설치 |
| 폰 저장 공간이 거의 찼어요 | dbPercent > 80 |
| 우편함 오류가 계속돼요 | 같은 lastInboxError가 2시간 넘음 |
| 통화 카드 권한이 꺼져 있어요 | `callCard.today.noPermission` ≥ 3(locked는 안 셈) |
| 업데이트 실패 | lastUpdateError(USER_ACTION_REQUIRED, INSTALL_SOURCE_NOT_ALLOWED 제외) |
| (가) 폰이 옛 버전에 머물러 있어요 | version.json versionCode > 폰, version.json 마지막 변경 커밋 뒤 24시간, 하트비트가 12시간 안 |
| (나) 루틴 지시문이 옛 버전이에요 | 루틴별 자가 보고 promptVersion이 version.json promptVersion(hourly)·tidyVersion(tidy)과 다름(version.json 변경 뒤 끝난 실행만) |
| (다) 전화 상태 권한이 빠졌어요 | permissions.phoneState=false 24시간 |
| (라) 녹음 공백 5종 | recordingGapVerdict(noRecording, partial, folder, tooMany, noMatch)가 48시간 같음 |
| (마) 긴 통화의 자막이 끝나지 않아요 | transcription.long48h.ratePct < 80이 24시간 |
| 분석이 멈췄어요 | inbox 가장 오래된 요청이 루틴 시간(07~23시)으로 3시간 넘음 |
| 루틴이 일을 안 해요 / 한도에 걸렸어요 / 지시문 버전이 달라요 | hourly 자가 보고 processed=0인데 inbox가 있음 / limitHit / promptMismatch |
| 매시간 분석이 예정 시각에 돌지 않았어요 | hourly 자가 보고 status 커밋이 cron상 2슬롯 연속 없음(슬롯 뒤 40분, 앞 5분 허용, 30시간 되돌아봄) |
| 루틴이 결과를 저장소에 올리지 못했어요 | 어느 루틴이든 pushed=false |
| 우편함 토큰이 곧 만료돼요 | tokenExpiresMs가 14일 안 |
| (v3.5) 아침 보고서가 안 만들어졌어요 | 한국 시각 05:30이 지났는데 `report/<오늘>.json`이 없음(있는지만 보고 내용은 안 읽음). 새벽 정리 자가 보고 promptVersion ≥ 7일 때만(배포 당일 오경보 방지). 04:46 워치독은 05:30 전이라 실제 판정은 그날 07:34부터의 매시간 워치독. 본문에 `reportWritten`으로 원인 |
| (v3.5) 카카오톡 보고가 안 갔어요 | 하트비트 `kakao.on`이 참(키 없는 옛 하트비트는 참으로 봄)이고 `kakao.linked=true`인데 09:00이 지나도 `lastSentMs`가 어제 00:00 전(어제·오늘 두 아침 연속), 어제 09:00 전부터 연결돼 있었을 때만 |
| (v3.5) 카카오톡 연결이 풀렸어요 | `kakao.on`이 거짓이 아니고 `kakao.linked=false`이며 `kakao.error`가 `TOKEN_`으로 시작 |

### 8.2 자가 보고와 루틴 실패 길
- `status/last-run-hourly.json`·`last-run-tidy.json` 한 줄 JSON: routine, startedMs, endedMs, processed, skipped, failed, batches, model, limitHit, ledgerRev, promptVersion, promptMismatch, loggedTranscript(항상 false), repoOk, pushed, (hourly만) kinds, firstPassItems, formats, requestVersions, leftover, cron/crons, (tidy만, v3.5) reportWritten(오늘 보고서를 push했으면 참). 처리할 것이 없어도 항상 쓴다(`docs/routines/self-report.md`).
- 루틴이 저장소에 못 닿으면 자가 보고도 못 쓰고 "확인 필요: 저장소 접근"으로 답한다 → 예약 작업 알림. 루틴이 죽으면 워치독도 멈추므로 폰 신호등 "통화 정리" 줄(2회 놓침 주황)과 저녁 자가 진단(4회)이 둘째 눈이다. 새벽 정리가 죽어도 폰이 06:00에 자기 자료로 짧은 카톡 보고를 보낸다(3.12). 폰은 결과 받기 때 `endedMs`·`cron`을 `routineEndedMs`·`routineCrons`에 둔다.
- 통화 목록 검증(09:00): `status/calllog.txt`와 저장·분석 결과를 대조해 검증 파일과 다섯 줄 알림, `status/verify-log.md`에 숫자만.
- 문제 찾기 순서와 증상별 볼 곳은 OPERATIONS.md 4~6절.

## 9. 시험과 배포 게이트

| 게이트 | 내용 | 근거 |
| --- | --- | --- |
| 단위 시험 | `:core:test :app:testDebugUnitTest`(Robolectric) + lint. 소스의 @Test 816개(v3.5, `R15ZeroTest`·`R15ThreeTest`·`R15FourTest`·`KakaoReportTest` 추가; 실행 건수는 마지막 기록이 v3.1 통합 582건, 그 뒤는 ci 통과만 확인(추정)) | `android-ci.yml` |
| 화면 규칙 | `tools/check_ui_rules.py` 위반 0, 도구 시험(`tools/`·`tools/inbox/`의 `test_*.py` 전부: `test_check_ui_rules.py`, `test_watchdog.py`, `test_version_fields.py`, `test_golden_score.py`, `test_call_card_script.py`, v3.5 `test_report_check.py`) | ci "UI rules and tool tests" |
| 비밀 스캔 | `tools/scan_export.py`(비밀 값은 실패, 개인 경로는 경고) | ci "Export scan" |
| 업데이트 문구 | 앱 코드가 바뀌었는데 UPDATE_NOTE가 그대로면 경고 | ci |
| `RoutineDocTest` | hourly.md의 [분석 규칙]·[결과 형식]이 `SYSTEM`·`SCHEMA`와 같고 버전이 맞는지, tidy.md 버전 7·9-1절이 있는지 | 단위 시험 |
| 카톡 보고(v3.5) | 가짜 전송기(`KakaoReport.Sender`)로 compose 고정 사례, 각 글 ≤ 200자, 렌더 높이 ≤ 2400px·전화번호 없음, 업로드 실패 → 폴백 글, 같은 날 재전송 금지, 404 → 재시도 → 폰 폴백, 토큰 오류 → linked=false, 카톡 성공 날 조용한 아침 알림, 실행 파라미터 → 화면. 실제 카카오 전송은 아내 폰 실전송으로만 확인 | `KakaoReportTest`, `R15ThreeTest`, `R15FourTest` |
| ci → release-apk | lean-calls·main push. 같은 커밋 ci 성공 필수(40분 안). `tools/needs_update_smoke.sh`가 매니페스트·IeumApplication·StartupReceiver·CallSinkImpl·빌드 설정 변경을 보면 update-smoke "Install, update, start" 통과도 필수(70분). 에뮬레이터 부팅 실패(인프라)만 경고 | `release-apk.yml` |
| update-smoke | 직전 릴리스 위에 덮어 설치, 시작, 충돌 없음, 예약 작업 다섯 개, 앱 자신 설치 단계(`appops REQUEST_INSTALL_PACKAGES allow` 뒤 사용자 확인 없이 versionCode 상승) | `update-smoke.yml`, `tools/update_smoke.sh` |
| device-test | feature/** push(안드로이드·도구 경로) 또는 수동. 에뮬레이터 API 34: 모델 SHA-256, 한국어 Whisper(평소 base-q5_1, 음성 인식 파일이 바뀌면 전체), 화면 9장(1_home, 2_today, 3_tasks, 4_settings, 5_companies, 6_company_detail, 7_call_detail, 8_person, 9_home_large 1.3배), 골든(push는 base), 통화 카드 11항목(ringingWindow, stripAfter3s, goneAfterIdle, serviceGone, outgoing, unknownOff, personalNothing, outgoingNoRole, callWaiting, coldWindow, lockedNotice; 기준 벨→창 3,000ms, 콜드는 `am kill`, 통화 대기는 에뮬레이터가 재현 못 해 null, 잠금 위 창은 기록만). `lockedNotice`는 에뮬레이터가 잠기지 않으면(lock screen on=false) 실패로 나온다(v3.5 배포·키 커밋 때 같음, 판정 보류로 바꿀 것). 카드 샷 4종은 ci `IEUM_SHOTS_DIR` 산출물 | `device-test.yml`, `tools/device/call_card_test.sh` |
| real-device-test | Firebase Test Lab 최신 Galaxy S(S26), 공개 음성만, 수동, 하루 5번 | `real-device-test.yml` |
| inbox-e2e | feature/** push(그 파일) 또는 수동으로 `inbox/selftest-<번호>.json` 투입, 07:40~23:40 KST 매시 40분에 결과를 폰 파서·필터로 검증 후 삭제 | `inbox-e2e.yml` |
| cleanup-branches | lean-calls에 다 들어간 feature/* 삭제(수동, dry_run) | `cleanup-branches.yml` |
| S26 체크리스트 | 사용자 10분: 잠긴 채 받기, 측면 버튼 잠금 뒤 벨, 다른 앱 중 벨(통화 팝업), 밀어 닫은 뒤 받기·걸기, 받기·거절 터치, 스피커·키패드·스크린샷, 이어폰·블루투스, 1.3배 글자, 긴 문자·사진 문자·채팅+(RCS). 통과 기준 1~8 O(2번 창은 관찰), 9·10 O, 11은 O 또는 "채팅+ 끄기" 안내 | `docs/REAL_DEVICE_CHECKLIST.md` |
| 배포 뒤 확인 기준(1045 이상 설치 뒤 48시간, 아내 폰은 11/7부터 1046) | matched/withFile ≥ 0.8, long48h.ratePct ≥ 80, callCard null 아님·shown > 0·shownOutgoing/eligibleOutgoing ≥ 90%·발신 noNumber 0·fgsRefused 0·ringToCardMs p50 < 1,000·cold 3건 이상에서 ringToCardMsCold p50 < 1,500, last-run-hourly 슬롯마다 갱신·pushed=true, 무인 설치 2시간 안 versionCode 상승, 요청 본문에 '@' 없음 | DESIGN_V4 2-2, 2-3, 4.5 |
| 배포 뒤 확인 기준(v3.5, 카톡 연결 뒤 3일) | 매일 04:46 `report/<날짜>.json` 생성(워치독 조용), 05:30~05:45 카톡 도착(아내 확인 1회), 하트비트 `kakao.sent` 3 증가·`failed` 0, `report.source="routine"` 3/3, 아침 앱 알림은 조용한 것만 | DESIGN_V5 3절 |
| 배포 뒤 확인 기준(v3.6, 판정 11/10) | 통과: 배터리 자막 ≤ 25분/일, 07시 잔량, 긴 통화 완료율, 워치독 3규칙. 실패: 전날 21시 이전 녹음 대기 0, 조각 식은 중앙값 ≤ 280초(376), 아침 보고 포함률 ≥ 90%(약 60%), 낮 처리 ≥ 8건(0). 미확인: 배터리 화면 이음 ≤ 20%. v3.4 카드 기준·v3.5 카톡 3일 기준은 충족 | DESIGN_V6 6절, HANDOFF.md 4절 |
| 채점표(11/10 등록, 기준선 0/10) | 10항목의 통과 기준을 미리 등록하고 `tools/inbox/score.py`가 우편함 이력에서 계산. 버전마다 배포 뒤 채점 | [SCORECARD.md](SCORECARD.md), DESIGN_V7 8절 |

## 10. 사용자 결정 모음

| 날짜 | 결정 |
| --- | --- |
| 10/30 | UX 기준은 "50대 남자가 봐도 편하고 어떤 기능이 있는지 바로 알 수 있게", 깔끔함을 넘어 세련되게(애플·토스 수준) |
| 10/30 | 모든 화면 글에 가운데 점 금지. 목록 글은 "…"로 자르지 말고 첫 문장만 |
| 10/30 | "할 일"이 아니라 "체크할 일", 기한 대신 요청 날짜, 빨간색 등 급박한 표시 금지. 상대가 할 일도 체크할 일로 |
| 10/30 | 앱 화면 캡처 허용(FLAG_SECURE 제거), 진행에 필요한 권한·결정은 묻지 말고 Claude가 |
| 11/4 | 버전 이름 v2.0부터 배포마다 0.1씩, 빌드 번호는 순서에만 |
| 11/4 | 설계와 구현이 다르면 어느 쪽도 기준으로 삼지 말고 맞는 쪽으로 구현한 뒤 설계도를 고친다. 설계도 먼저 |
| 11/4 | 장부의 원본은 저장소 장부(ieum-inbox), 사람은 전화번호(메일은 주소) 기준 id |
| 11/4 | 처음 정리는 10월 1일부터 묶음 시간순("맥락 정리 한 번" 방식 폐기) |
| 11/4 | 통화 목록 대조 파일 `status/calllog.txt`에 이름·번호 포함(아내 동의, 비공개 우편함) |
| 11/4 | 인수인계 원본은 `feature/lean-calls`(main에 합치면 main) |
| 11/4 | 결정 대기 항목(토큰 위치, 개인 문자, 낮 시간 자막, 새벽 Opus 빈도, 18sp 규칙, Activity 구조)은 "편리함 중점으로" Claude가 정함 |
| 11/4, 11/5 | 예약 작업 추가·수정은 Claude가 해도 됨("예약작업은 너가 수정하면 돼") |
| 11/5 | 버전마다 큰 변화 하나, 버전마다 배포·인수인계, 기기 시험은 2~3개 버전마다 |
| 11/5 | 통화 중 카드는 모모콜 방식(전화가 올 때와 걸 때 이전 진행 상황) |
| 11/5 08시 | 앱 저장소 공개(Actions 무료), v3.4 뒤 비공개 복귀는 사용자가 |
| 11/5 18:25 | 업데이트 다운로드 진행 상황이 설정에 보이고, 앱 시작 때 최신 버전을 검사·받기 |
| 11/5 18:26~27 | 새 버전 확인·다운로드와 Whisper 모델 받기 모두 네트워크 종류 무관("무조건") |
| 11/5 22:16~22:58 | 통화기록 총수는 사용자에게 의미 없음(앱 검증용), 홈에 녹음 진행 상황 불필요(설정으로), 홈은 요약만, 화면·보고 숫자는 녹음 기준 |
| 11/5 22:56(아내) | 홈 맨 위에 오늘 통화를 이야기 형식 2~3줄로 |
| 11/5 23:42 | 녹음과 통화기록 짝짓기에 파일 이름의 상대 정보를 먼저 쓴다 |
| 11/5 | 개인 통화가 통화 목록에 "개인 내용" 한 줄인 것은 정상 |
| 11/6 03시 | v3.4까지 한 번에 배포(48시간 실측을 기다리지 않음) |
| 11/6 06시 | 필요 없는 파일·코드 정리 |
| 11/6 08:28 | v3.5 큰 변화: 매일 아침 5:30 카카오톡으로 어제 일 정리·요약을 보고 형식으로 받는다(설계 DESIGN_V5.md) |
| 11/6 08:26 | 카드·체크할 일에는 "언제까지"·"지났어요"가 아니라 문자·메일 등 맥락을 종합한 상태("메일 발송 확인 완료", "문자로 답 받음")를 보여 준다. 약속 날짜는 판단 입력일 뿐 화면에 남기지 않는다. 완료 처리는 그대로 사람이 한다(v3.5 r15-0에서 카드 상태 글로 적용, 4절) |
| 11/6 08:40 | 카톡 아침 보고를 받는 사람은 아내 본인 계정 "나와의 채팅"뿐(관리자에게는 보내지 않음, 친구 보내기 안 씀) |
| 11/6 09시(Claude, "편리함 중점") | 카톡 보고는 피드 이미지 1통 + 오늘 챙길 일 글 1통, 이미지 실패 시 글 최대 4통(DESIGN_V5 0-1절) |
| 11/6 14시 | 보고 이미지가 카카오 CDN에 URL로 100일 남는 점을 사용자에게 알림(원하면 글 방식으로 전환) |
| 11/6 17시 | **v3.5 배포, 빌드 1046**: 카톡 아침 보고(나에게 보내기, 피드 이미지 + 글), 새벽 정리 04:46·tidy.md v7·보고서 JSON, v3.4.x 묶음(실패 자막 재큐, 녹음 기준 숫자, 약속 문구, 신호등, 알림 묶음, 카드 상태 글, 업데이트 재알림), 독립 검토 반영. 구현에서 다르게 정한 것은 DESIGN_V5 6절 19줄(이 문서에 흡수) |
| 11/7 15:49 | 배터리 문제(이음이 아내 폰 배터리의 약 60%)를 v3.6 큰 변화로. 분석·설계는 Opus 토론(DESIGN_V6) |
| 11/7 18:10 | **v3.6 배포, 빌드 1047·1048(21:44 카카오 새 키로 1049)**: 전력 정책(꽂힘에 몰아서, 배터리는 오늘 짧은 통화 하루 20분/5%), W1~W5, P7, 하트비트 `power`, 설정 스위치 [충전 중에만 글자로 바꾸기](기본 끔), 신호등·통화 상태 글·저녁 줄, 워치독 3규칙, 독립 검토 반영. 다르게 정한 것은 DESIGN_V6 8절(이 문서에 흡수) |
| 11/7 18:57 | v3.7에서 메일 기능 삭제(아내 폰에서 메일이 연결되지 않아 쓰지 않기로). `maskNumbers`의 메일 정규식은 남김 |
| 11/7 21:50 | 카카오 developers 앱을 다른 계정으로 다시 만들고 아내 계정을 멤버로 등록, 새 네이티브 앱 키로 재빌드(1049) → 22:35 연결 |
| 11/10(Claude 제안, DESIGN_V7) | v4.0까지 새 기능 동결, 버전 순서 v3.6.1 카드·밤 처리 → v3.7 아침 보고 완전성 → v3.8 저장소·배포 잠금·엔진 측정대 → v3.9 통화 직후 초벌과 밤 확정 → v4.0 쓰이는 앱. 목표는 미리 등록한 채점표 10/10(`score.py`가 판정). 배포 뒤 판정표를 채우기 전에는 다음 버전을 배포하지 않음 |
| (Claude 결정, 설계 표) | 지키지 못할 약속 문구 금지, 마이그레이션 자동 합치기는 같은 번호+같은 이름만, 합치기 되돌리기와 keepApart, 숫자 확실성은 화면에 앰버 배지, 숫자만 다른 일은 합치지 않음, 자막 오인 교정은 이름·호칭·업체명만, 잠금 화면 카드 공개 범위는 설정(기본 켬), 발신 역할은 선택 항목, 처음 설정은 필수/선택, 포그라운드 서비스는 통화 중에만, CallScreeningService 안 씀, 분석은 구독 한도 방식(API 키 경로 삭제) |

## 11. 알려진 한계, 미구현, 보류 (이 절이 미해결 목록의 유일한 원본 — 11/10 갱신, 해결 계획은 [DESIGN_V7.md](DESIGN_V7.md))

**치명·높음(실측으로 확인됨)**
- **통화 카드가 날마다 전부 되거나 전부 안 됨:** 11/7 0/6, 11/9 38/38, 11/10 오전 0/11. 실패한 날은 전부 `noPermission`인데 앞뒤 하트비트의 오버레이 권한은 true이고, 11/7 13:57에는 통화와 같은 분의 하트비트만 false. 앱 코드에 캐시는 없음 — 통화 순간에만 `canDrawOverlays`가 거짓인 날이 있다(원인 미확정: 시스템 상태에 따른 일시 차단 또는 프로세스 쪽 AppOps 캐시, 추정). 신호등은 화면을 여는 순간의 값만 봐서 초록 → v3.6.1(DESIGN_V7 2절).
- **당일 정리가 안 됨:** 통화 끝→결과 p50 38시간. 아침 보고에 어제 업무 통화의 약 60%만 들어가고 빠진 건수를 말하지 않는다. 원인은 달궈짐 오탐(`slow` 조건), 밀리면 선입선출인 대기열 순서, 07:34 첫 분석과 21시대 `state` 스냅숏, 녹음 1분에 CPU 약 3.5분인 엔진 → v3.7(2절), v3.9(4절).
- **봉인 DB 한도:** `dbPercent` 47(하루 약 3.4%p), 쓰기마다 전체 재봉인, 100%면 모든 쓰기 실패가 `runCatching`에 삼켜짐 → v3.8(3.1절), v3.7에서 `trim` 매일·워치독 70%.
- **v3.6.1 미이행:** 배터리 실행이 `PowerStats.budget`의 자기 표시로 조각 1개에서 끝나는 회귀, 길이 추정 `call_log`, 05~07시 규칙의 `full`·`notCharging`, 속도 표본 스레드별 보관, `yieldMs`·`decodeAudioMs` → v3.7 T3.
- **배포 보안 연쇄(7절 약점):** 평문 토큰, 빈 서명 시드, 폰이 인증서를 비교하지 않음, debug 빌드, 소금 없는 번호 해시 → v3.8(3.2절).
- **사용 숫자 없음:** 앱 열기·알림 누름·[다 됐어요]·카톡에서 들어옴을 세지 않는다 → v3.7 r17-3, v4.0.

**중간**
- 자막 정확도를 실녹음으로 잰 적 없음(`golden: null`). B1~B7 스위치는 기본값 그대로, 합성 골든(base) CER 18%는 대표성 없음 → v3.9 은 골든·금 골든.
- 아침 "늦게 정리된 통화"가 낮 통화를 0건으로 셈(`CallDigestWork.kt:117-130`), 저녁 "충전하면 n개"의 n이 오늘 것만이 아님(`CallTranscriptionRunner.kt:417`).
- 지연 계측이 한 구간뿐이고 7일 넘는 값을 버림(`LATENCY_OUTLIER_MS`) → v3.7 r17-3.
- `CallRecordStore.kt` 1,619줄·책임 12가지, `runCatching` 470곳, 설정 키 85개 → v3.8 r18-3.
- 화면(11/6 지적 중 열림): 첫 실행 설정이 주황 경고로 시작(`SettingsScreen.kt:331-333`), 관리자용 기능 노출(다시 정리·카드 다시 만들기·합친 사람 되돌리기·골든 측정·역할 요청), 카드가 선택 단계, 신호등 "위 ○○ 묶음" 방향 글(`StatusLights.kt:78-79`), "끝나기까지 약 1시간 안"(`SettingsScreen.kt:693`), "AI 메모"(`CallUi.kt:456`), "와이파이에 연결한 뒤"(`SelfCheckNotices.kt:74`), 죽은 코드 "n일 지남"(`CallUi.kt:780-786`) → v4.0 r20-1(문구 세 곳은 v3.7에 끼워도 됨).
- 대조표 `status/calllog.txt` "10/1 이후 전체" 목록이 11/6부터 비어 있음, 옛 `status/last-run.json`이 11/6 05:16에서 멈춤.

**낮음·보류**
- 배터리: 꽂힘 중 `CURRENT_NOW` 부호 보정, device-test `dumpsys battery` 시나리오, `tokensLast`. 아내가 밤에 꽂지 않으면 긴 통화가 밀린다(신호등 36시간).
- 카톡 보고: 텍스트 템플릿 200자, 피드 제목·본문 각 2줄(그래서 전체는 이미지). "나와의 채팅"은 푸시가 안 울릴 수 있어 06시 앱 알림을 조용히 남긴다. 09:00이 지나면 그날 카톡은 보내지 않는다.
- 하지 않은 것: 예약 작업 30분 간격(허용 안 됨), `corrected` 화면 표시(v3.9), 우편함 이력 압축, 아침 알림 [다 됐어요] 2개, Actions 분량 조정(비공개 복귀 전, v3.8).
- 기각(이유는 history): 조각 5분, `max_len`, `flash_attn`(S26 CPU에서 1.7배 느림), 서버 자막(녹음은 폰 밖으로 안 나감), 한도 상향만으로 DB 문제 풀기. `audio_ctx` 축소는 기각에서 **v3.9 측정 후보**(꼬리 창만)로 옮김.
- **S26에서만 알 수 있는 것:** 잠금 화면 위 오버레이가 보이는지, 삼성 "통화 팝업"과의 겹침, 삼성 "절전 앱"과 FGS 거부, 포그라운드 서비스 프로세스가 놓이는 cpuset, 역할 없이 발신 번호가 늘 오는지(실측 23/24), RCS(채팅+)가 `content://mms`에 오는지 → v4.0 r20-3 체크리스트.
- 사용자 할 일은 DESIGN_V7 12절이 원본.

## 12. 용어

| 말 | 뜻 |
| --- | --- |
| 자막 | 폰 Whisper가 녹음에서 만든 글. 2분 조각 단위 |
| 우편함 | 비공개 저장소 ieum-inbox. 폰과 루틴이 파일로 주고받는 곳 |
| 장부 | `ledger/ledger.json`(원본)과 폰의 사본. 현장 메모, 업체, 사람, 진행 중인 일 |
| 체크할 일 | 사용자가 됐는지 확인할 일(내가 할 일과 상대가 하기로 한 일 모두). 화면에 요청 날짜 |
| 진행 중인 일 | 장부 issues. 새벽 정리가 여러 기록을 한 일로 묶고 현황 한 줄 |
| 처음 정리 | 10/1부터의 지난 통화·문자·메일을 묶음(b-)으로 시간순 정리. 끝나면 `FIRST_PASS_DONE` |
| live / old | 처음 정리 중 기록 시각이 7일 안이면 live(단건), 그 전이면 old(묶음) |
| 새벽 정리 | 04:46 Opus 루틴. 진행 중인 일, 합치기, 체크할 일 다시 쓰기·팔로업 제안, 아침 보고서 |
| 아침 보고 | 05:30 카카오톡 "나와의 채팅"으로 오는 보고(피드 이미지 + 글)와 06시 앱 알림. 보고서는 `report/YYYY-MM-DD.json` |
| 나에게 보내기 | 카카오 메시지 API로 로그인한 본인의 "나와의 채팅"에 보내는 것(`talk_message` 동의, 건수 제한 없음) |
| 폰 폴백 | 06:00까지 새벽 정리 보고서가 없을 때 폰이 열린 체크할 일로 만들어 보내는 짧은 카톡 1통 |
| 매시간 분석 | 매시 34분 07~22시 Sonnet 루틴. 요청을 분석해 결과와 장부를 쓴다 |
| 결과 받기 | 폰이 예정 실행 +3·+8·+15분에 결과를 가져와 적용하는 것(`ResultChain`) |
| 하트비트 | 폰이 매시간 올리는 `status/phone.json`(숫자와 코드만) |
| 자가 보고 | 루틴이 실행마다 쓰는 `status/last-run-hourly.json`·`last-run-tidy.json` |
| 워치독 | 루틴 끝에서 도는 `watchdog.py`. 이상이면 GitHub 이슈 |
| 신호등 | 설정 "지금 상태"의 여섯 줄(녹음 가져오기, 글자로 바꾸기, 통화 정리, 알림(v3.5 카톡 상태 포함), 업데이트, 통화 카드). 초록 또는 주황 |
| 녹음 공백 | 통화는 있는데 녹음 파일이 창 안에 없는 상태(`RecordingGap`) |
| 상대 카드 | 번호별로 미리 계산해 작은 봉인 파일에 둔 "이 사람과의 지금 상황"(통화 카드와 사람 화면이 읽음) |
| thin 카드 | 정리된 통화는 없고 통화기록만 있는 번호의 작은 카드("3일 전 통화, 30일간 5번") |
| 통화 카드 | 전화가 올 때·걸 때 통화 화면 위에 뜨는 오버레이. 통화 중에는 띠 |
| 콜드 | 앱 프로세스가 없던 상태에서 벨 방송으로 시작한 경우 |
| 확인 필요 | 잘못 들렸을 수 있는 숫자·날짜나 "알아볼게요" 수준의 일에 붙는 앰버 배지(tentative) |
| 개인 번호 | business=false가 2회 연속인 번호. 카드·알림·요청 없음 |
| 골든 | 정답이 있는 녹음으로 자막 정확도를 재는 것(합성 골든, 실녹음 골든) |
| 지시문 | `docs/routines/*.md`의 "---" 아래. 예약 작업이 우편함 사본을 읽어 따름 |

## 13. 문서와 코드가 다른 곳(코드를 따름; 11/7 v3.5 정리에서 PRODUCT.md·OPERATIONS.md·HANDOFF.md·self-report.md·watchdog.py 주석이 코드와 맞는 것을 확인해 해소된 항목은 지웠다)
- `android/core` `RecordingPolicy.MAX_BYTES`=25,000,000 → 실제 한도는 금고 `AudioBinding.MAX_BYTES`=60,000,000이고 `RecordingPolicy.safeSize`는 앱에서 부르는 곳이 없다(남은 상수).
- 문서 여러 곳의 "단위 시험 n건" → 최근 기록된 실행 건수는 v3.1의 582건이고, 지금 소스의 @Test는 816개(9절).
- 자가 진단 "음성 인식 모델을 다시 받아야 해요"의 본문 "와이파이에 연결한 뒤"(`SelfCheckNotices`) → 결정은 "어떤 연결에서든 받음"(11/5). 코드 문구가 결정과 어긋남(PRODUCT.md 6절 5번).

옛 설계 기록(`history/`의 DESIGN_V3~V6)은 고치지 않으므로 그 문서와 다른 점은 아래처럼 코드가 기준이다.
- DESIGN_V3 7.1: 카드 높이 화면의 40% 이하, 세로 38% → 코드는 벨 중 높이 34%, 아래 끝 72%, 기본 위쪽 끝 38%(`CallCardOverlay.MAX_HEIGHT`·`BOTTOM_LIMIT`·`DEFAULT_POS_Y`).
- DESIGN_V4 4.2-3: 첫 줄 "줄당 18자 안팎, 2줄" → 코드는 36자 한도(`FIRST_CHARS`), 셋째·넷째 줄 14자(`ROW_CHARS`).
- DESIGN_V3 6.2: 통화별 알림 잠금 화면 "통화 정리 중"만 → 코드는 상태별 "통화 정리됨 / 확인할 통화 / 통화 정리 중"(`CallFollowupNotice.publicTitle`).
- DESIGN_V6 2~3절 → 구현은 8절 "다르게 정한 것"대로이고 이 문서 3.2·5.4·8.1·10에 반영했다.
- DESIGN_V5 0~2절(SDK 2.26.0, "카톡 성공이면 앱 알림 없음", 폴백 "[진행 중·확인]", FAILED 재큐 빌드마다 1회 등) → 구현은 6절 "다르게 정한 것" 19줄대로이고 이 문서 3.2·3.9·3.12·8.1에 반영했다.
