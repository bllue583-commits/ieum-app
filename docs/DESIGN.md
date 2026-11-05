# 이음 설계도 — 지금 배포된 v3.4(빌드 1045), 구현된 그대로

> 이 문서는 "앱이 지금 어떻게 동작하는가"의 기준이다. 버전별 지시와 변경 이력은 넣지 않는다(그것은 `history/`의 DESIGN_V3.md(v2.6~v3.0), DESIGN_V4.md(v3.1~v3.4), HANDOFF_HISTORY.md). 설계와 구현이 달랐던 항목은 각 버전 "다르게 정한 것"·"구현 대조"의 최종 결정을 사실로 적었다. 숫자는 코드 상수에서 확인했고, 문서와 코드가 달랐던 곳은 13절에 모았다. 근거는 괄호 안에 파일명이나 `함수명`으로 적는다. 확인하지 못한 것은 "(추정)".
>
> 기준 코드: `feature/lean-calls`, `docs/VERSION` 3.4, 우편함 `app/version.json` versionCode 1045. 작성 2026-11-06.

## 1. 한눈에 보기

| 항목 | 내용 |
| --- | --- |
| 목적 | 건설사 직원(부장, 현장 하나)인 아내가 하루 약 2시간 하는 업무 통화를 자동으로 정리해 체크할 일, 요약, 업체·사람 맥락을 보여 주고, 전화가 오거나 걸 때 그 상대와 약속한 일을 통화 화면 위 카드로 띄운다(PRODUCT.md 1절, HANDOFF.md 1절) |
| 사용자 | 실사용자 아내(앱 설정 만지기를 싫어함), 관리자 남편. 개발·시험·판단은 Claude가 한다 |
| 기기 | Galaxy S26, 안드로이드 16. 삼성 통화 녹음(자동, `Recordings/Call`, m4a) |
| 현재 버전 | v3.4, 빌드 1045(1044에서 안 쓰는 코드만 지운 빌드). 서명 인증서 SHA-256 `e4ee1db4…2c586`(1039~1045 같음, `app/version.json` `certSha256`) |
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
| 폰 앱 | `android/app/src/main/java/kr/callbrief/`(패키지 `kr.callbrief`), 파이프라인 `pipeline/`, 화면 `pipeline/screens/`, 카드 `pipeline/call/` | 녹음 발견, 자막, 대조, 요청 구성·업로드, 결과 받기·적용, 알림, 카드, 하트비트, 업데이트 |
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
[폰, 밤 21~04시 하루 한 번] tidy/state.json ─▶ [새벽 정리 05:16, Opus 5.5] issues·합치기·tidy/proposal.json
 ─▶ [폰] applyTidy ─▶ 아침 06시 보고 알림, 저녁 19시 정리 알림
[릴리스] lean-calls push ─▶ ci ─▶ release-apk ─▶ 우편함 릴리스·app/version.json ─▶ 폰 1시간 안 확인·무인 설치
```

### 2.3 저장소, 브랜치, 배포 경로

| 항목 | 값 |
| --- | --- |
| 앱 저장소 | `github.com/bllue583-commits/ieum-app`, 11/5 08시부터 공개(비밀 없음). 작업 브랜치이자 원본 `feature/lean-calls`. `main`은 옛 코드 + `cleanup-branches.yml`(HANDOFF.md 3절) |
| 우편함 | `ieum-inbox`(비공개) 기본 브랜치 |
| 매시간 분석 | `trig_016vQxos9z6p1pdPJ28MjrsV`, `CRON_TZ=Asia/Seoul 34 7-22 * * *`(하루 16번), Sonnet 5.5, 지시문 `routines/hourly.md` 버전 8 |
| 새벽 정리 | `trig_01Sp6piuvasw52ZR1KPupumB`, 05:16, Opus 5.5, 지시문 `routines/tidy.md` 버전 6 |
| 통화 목록 검증 | `trig_01KyuDNZWkmrQpJj8e8cr661`, 09:00, Opus 5.5, 프롬프트가 원본, 기억 `status/verify-log.md`(숫자만) |
| 예약 작업 프롬프트 | 짧은 안내만: 우편함 pull(실패하면 "확인 필요: 저장소 접근"만 답) → `routines/*.md`의 첫 "---" 아래를 따른다. 예약 작업은 1시간보다 짧은 간격을 허용하지 않는다(OPERATIONS.md 2절) |
| 배포 경로 | lean-calls push → ci 통과 대기(40분) → (시작·빌드 파일이 바뀌었으면) update-smoke 통과 대기(70분) → 서명 APK → 서명 인증서 비교 → `docs/routines/*.md`·`tools/inbox/watchdog.py`를 우편함에 먼저(바뀐 것만) → 릴리스 `v<빌드>`와 `app/version.json`(versionCode, versionName, apk, sha256, size, notes, promptVersion, tidyVersion, certSha256), 릴리스는 3개만 남김(release-apk.yml, `tools/release/version_fields.py`) |

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

**녹음 공백 판정**(`RecordingGap.judge`, 발견이 성공할 때마다): 대상 = 최근 24시간 받은·건 통화, 길이 ≥ 30초, 개인 아님, 통화 끝이 마지막 발견 성공보다 15분 이상 앞. 통화마다 창 [시작 − 2분, 끝 + 5분]에 폴더 파일이 있으면 withFile, 그중 기록과 이어진 것이 matched. 판정 순서: 폴더 오류 `folder` → 목록 잘림 `tooMany` → 대상 < 3 `ok` → withFile < 0.3n `noRecording` → < 0.7n `partial` → matched < 0.5·withFile인데 자막 대기·진행 중까지 더하면 0.5 이상 `pendingText` → `noMatch` → `ok`. 아내에게는 `noRecording`·`partial`·`folder`만, 6시간 넘게 떨어진 두 번 연속 판정부터 말한다(`CONFIRM_MS`). 48시간 넘은 판정은 보이지 않는다(`STALE_MS`). `noMatch`는 하트비트·워치독에만, `pendingText`는 녹음 줄에 초록 글로만.

### 3.2 자막
| 규칙 | 값 | 근거 |
| --- | --- | --- |
| 모델 | large-v3-turbo q5_0(574MB), 정리 동의 직후 어떤 연결에서든 받기 시작, 크기·SHA-256 검증 | `PipelineModelManager`, `PipelineModelDownloadWork` |
| 조건 | 배터리 부족 아님(WorkManager `requiresBatteryNotLow`). 처음 정리의 옛 녹음은 충전 중이거나 50% 이상일 때만 | `CallTranscriptionRunner.schedule`, `FirstPass.powerOk` |
| 조각 | 최대 2분(`MAX_CHUNK_MS` 120,000), 자르는 점은 ±2초 안 100ms 창 중 가장 조용한 곳(10ms 격자), 끝 조각 0.1초 미만은 앞 조각에 붙임 | `PipelineAudioMath` |
| 조각 저장 | 끝난 조각은 `call_transcription_parts`(조각 길이 `chunk_ms`가 다르면 무효)에 저장해 이어 한다. 같은 조각이 3번 연속 중지되면 반으로(10초 미만은 안 쪼갬) | `CallTranscriptionStore`, `SPLIT_AFTER_STOPS` |
| 대기열 순서 | (처음 정리 중이면 옛 녹음은 맨 뒤, 그 안에서는 통화 시각 오름차순) ① 조각이 하나라도 저장됐거나 중지·기한으로 멈춘 녹음(대기열 진입순) ② 24시간 넘게 기다린 새 녹음(진입순) ③ 나머지 새 녹음 최신 통화순 | `CallTranscriptionStore.nextQueued` |
| 시간 한도 | WorkManager 약 10분. 실행 6분이 지나면 새 녹음을 집지 않음(첫 녹음은 예외). 그 실행에서 조각을 못 끝낸 시간 한도 중지는 시도 환불(실행의 첫 녹음이 아닐 때만, 한 녹음 환불 6회까지). 이유 없는 중지는 시작 9분 뒤면 시간 한도로 본다 | `NEW_WORK_MS`, `MAX_REFUNDED_STALLS`, `TIME_LIMIT_NEAR_MS` |
| 기한 | 네이티브 조각당 540초(`NATIVE_DEADLINE_SECONDS`). 넘기면 `ANDROID_STT_DEADLINE`으로 그 조각을 반으로 다시. 반 조각이 또 넘기면 일반 실패 | `CallTranscriptionRunner.deadline` |
| 시도·실패 | 최대 3회(`MAX_ATTEMPTS`). RUNNING이 11분 넘으면 낡은 것으로 본다. 실패 사유 코드(예: `ANDROID_STT_PCM_SIZE`, `ANDROID_STT_NOT_COMPLETED`, `ANDROID_STT_CPU_UNSUPPORTED`, `EMPTY_TRANSCRIPT`)는 하트비트 `transcription.failReasons` | `STALE_RUNNING_MS` |
| VAD | 30ms 창이 0.006 이상이고 조각 소리 90분위의 0.3 이상이면 말소리. 골든 변형 하한 0.0025/0.15는 스위치만 있음 | `SPEECH_FLOOR`, `LOUD_SHARE`, `*_LOW` |
| 디코딩 | 기본 greedy, 온도 증가·best_of는 whisper.cpp 기본(0.2, 5), `no_context=true`, `token_timestamps=true`, `suppress_nst` 꺼짐, 언어 한국어, 스레드 = CPU 수 − 1(1~6, S26은 6). 초기 프롬프트 꺼짐(`PROMPT_ENABLED=false`) | `ieum_whisper.cpp`, `AndroidWhisperEngine`, `WhisperCallTranscriber.threads` |
| 불확실 숫자 | 토큰 확률 최솟값 0.5 미만인 숫자를 `Segment.uncertainNumbers`로(이어 받은 조각에서는 빠짐) | `AndroidWhisperEngine.UNCERTAIN_P` |
| 후처리 | 반복 환각 제거, 세그먼트 줄바꿈 이음, 줄별 시각 `segments_json` `{"v":1,"s","d","u"}`(글은 없음) | `TranscriptClean`, `CallRecordStore` |
| 속도 | S26 녹음 1분당 약 85초(6스레드), 침묵 건너뛰기 통화형 72초(10/30 측정, 옛 모델 기준일 수 있음(추정)) | HANDOFF_HISTORY |
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

### 3.8 새벽 정리(`docs/routines/tidy.md` 버전 6)
- 폰: 정리 동의가 켜져 있고 21:00~04:59(KST), 마지막 전송 12시간 뒤면 `tidy/state.json`(열린 체크할 일과 rev·잠금, 기록 요약을 해시 키로, mergeCandidates, personCandidates)을 올린다. 처음 정리 완료 + `tidiedOnce` 거짓이면 10/1부터 전체, 아니면 최근 14일(`tidyAll`). 내용이 같으면 안 올린다.
- 루틴(05:16, Opus): 오늘 "tidy" 커밋이 있으면 "이미 정리됨". state가 없거나 24시간 넘었으면 "정리할 것 없음: 이유". issues(진행 중인 일 최대 20, id `i_`+8자), 팔로업 create, `tidy/proposal.json` {rev, basedOnMs, tasks[rewrite·merge·done_suggested·create]}, 장부 정리(mergedInto만, 지우지 않음), 기억 파일 `tidy/project.md`·`tidy/history/YYYY-MM.md`·`tidy/briefing.md`, 자가 보고 `status/last-run-tidy.json`, 워치독. 완료 처리는 하지 않는다.

### 3.9 알림
| 알림 | 시각·조건 | 문구·동작 | 근거 |
| --- | --- | --- | --- |
| 통화별 | 업무 상대로 확인된(상대 카드나 전화부 이름이 있는) 연결 통화가 끝나면. 발신은 통화기록 길이 > 0일 때만, 길이를 모르면 HOLD 10분 | "○○와 통화를 정리하고 있어요 / 끝나면 여기에 요약이 와요" + [메모 남기기](RemoteInput → 사용자 체크할 일). 결과가 오면 "정리됐어요" + 첫 문장 + 체크할 일 1줄. 6시간 넘으면 "정리가 늦어지고 있어요"(기록이 있을 때만 "저녁 보고에 넣어 드릴게요"). 통화 끝 2분 뒤 발견이 성공했는데 창 안에 파일이 없으면 "이 통화는 녹음이 없어 정리할 수 없어요". `noRecording` 판정 중에는 띄우지 않음. 잠금 화면 글 "통화 정리됨 / 확인할 통화 / 통화 정리 중". 저녁 보고 전에 끝난 통화와 36시간 넘은 것은 지움. 채널 `ieum.calls.followup`(소리 없음), 스위치 "통화마다 알림"(기본 켬) | `CallFollowupNotice` |
| 저녁 정리 | 19시(설정 가능). 시작하면 결과 받기를 같은 작업 안에서 최대 3분 | 제목 "오늘 통화 n건, 새 체크할 일 m개"(정리된 통화 0이면 "오늘 통화 n건, 녹음된 통화 0건"), 통화 수는 홈과 같은 `CallsScreen.callCount`, 줄 최대 7개("외 n개"). 누르면 그날 통화(dayMs) | `CallDigestText.evening`, `CallDigestPrefetch.TIMEOUT_MS` |
| 아침 보고 | 06시(설정 가능) | 제목 "체크할 일 n개", 첫 줄 "지금 할 일: …"에 [다 됐어요] 액션(누르면 다음 항목으로, 다 끝나면 "체크할 일을 다 끝냈어요"), 진행 중인 일 최대 3줄, 저녁 보고 뒤 정리된 통화가 있으면 "늦게 정리된 통화 n건". 기한·"지남" 없음, 요청 날짜순 | `CallDigestText.morning`, `NotificationActions` |
| 자가 진단 | 저녁 보고 작업 끝(밤중 건너뜀) | 자막 밀림, 모델 실패, 폴더 풀림, 결과 없음, 저장 80%, 녹음 공백(1일째, 2일째, 그 뒤 7일마다), 업데이트 하루 미설치, 분석 루틴 4슬롯 놓침(`routine_late`) | `SelfCheckNotices` |
| 업데이트 | 확인이 필요할 때 | 문구는 version.json notes, [지금 업데이트] | `CallUpdate` |

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
| 카드·업데이트·기타 | callCard(4.5), updateAvailableCode, updateReadySinceMs, lastUpdateError, update{error}, messages{mailError}, golden |

통화 목록 대조표 `status/calllog.txt`는 이름·번호 전체가 든 사람용 글이다(사용자 결정 11/4, 아내 동의, 비공개 우편함에만, 같은 내용이면 안 올림)(`CallLogReport`, OPERATIONS.md 4절).

### 3.11 업데이트(`CallUpdate`)
- 확인: 앱을 열 때 바로, 6시간 주기 작업(`ieum.update`), 그리고 결과 받기마다 `app/version.json`을 ETag로 읽어 처음 보는 높은 versionCode면 즉시. 네트워크 종류 무관(모바일 데이터도).
- 받기: 1초마다 진행률(설정 "앱 버전" 줄 "받는 중 n%"), 3분 멈추면 멈춤 처리, sha·크기 불일치면 지우고 다시.
- 무인 설치 조건(모두 맞아야 함): API 31 이상, 매니페스트 `UPDATE_PACKAGES_WITHOUT_USER_ACTION`, 자기 자신을 업데이트하는 세션 + `USER_ACTION_NOT_REQUIRED`, `canRequestPackageInstalls()` 참(아니면 `INSTALL_SOURCE_NOT_ALLOWED`와 선택 처음 설정 단계), 새 APK targetSdk ≥ 기기 최소(안드로이드 16: 34, 우리 36), 다른 스토어가 소유권을 갖지 않음. 하나라도 어긋나면 `STATUS_PENDING_USER_ACTION` → `USER_ACTION_REQUIRED`, 알림 한 번 누름. 스위치 "새 버전은 자동으로 설치"(기본 켬).
- 서명: release-apk가 직전 릴리스와 인증서를 비교하고 version.json `certSha256`과도 비교, 다르면 배포하지 않는다. "서명이 다르다"면 앱을 지우지 않는다(지우면 저장된 통화가 사라짐).

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

**카드 글(3초용, `CallCardView.Lines`)**: 입력은 상대 카드 v2와 지금 시각(KST). 첫 줄 고르는 순서 ① `dueMs`가 지났거나 7일 안인 열린 체크할 일("견적서, 금요일까지") ② `checkReason`이 있는 일(배지 "확인 필요") ③ 14일 안 지난 통화 요약 첫 문장 ④ 진행 중인 일(기한을 해석할 수 없는 일 포함) ⑤ thin("3일 전 통화, 30일간 5번") ⑥ 모르는 번호("아직 정리된 통화가 없는 번호예요"). 같은 순위면 기한 빠른 것, 그다음 최근 요청. 첫 줄 20sp 굵게, 36자(2줄 × 18자) 안 띄어쓰기 경계, 말줄임표 없음. 둘째 줄 "김민수 대표, ○○철강, 3일 전"(쉼표 구분, 가운데 점 없음). 셋째 줄 체크할 일 14자 + "외 n개", 넷째 진행 중인 일 14자. 전화번호만 가리고 금액·날짜·수량은 보인다(잠금 알림 한 줄은 금액도 지움). 띠·잠금 알림(`noticeLines`, `strip`)도 같은 순서. 모르는 번호 카드는 최근 문자 대신 통화기록 한 줄(벨 경로에서 큰 DB를 열지 않으려고).

**통계(`CardStats` → 하트비트 `callCard`)**: 누계와 `today`(KST 하루). 키 calls, shown, shownOutgoing, eligibleOutgoing, noCard, thin, noNumber, noNumberIn, noNumberOut, noRole, ignored, off, locked(잠금이면 창·알림을 함께 띄워도 locked), cold(프로세스 시작 5초 안 첫 방송), fgsRefused, noPermission(`canDrawOverlays` 거짓일 때만), noNotify, expanded, closedUnder2s(2초 안 닫음). 시간 ringToCardMs·ringToCardMsCold {p50,max,n}(최근 50통화, 수신기 elapsedRealtime → 창 추가나 알림 게시), cardOpenMs. 번호 없음은 통화가 끝날 때만 센다. v3.x가 통화를 하나라도 보면 null 대신 0.

**상대 카드 저장소(`ContactCards`)**: 별도 봉인 파일 `contact-cards-v1.sealed`, 표 `contact_cards(phone_key PK,json,updated_ms)`, `blocked(phone_key)`, `card_meta(k,v)`. 카드 JSON v2: v, people[{personId,name,role,isStaff}], company{id,name,trade}, lastCallMs, lastDirection, lastDurationMs, calls30d, recent[{atMs,kind,summaryFirst}]×3(40자), openTasks[{id,title,requestedMs,status,aiNote,tentative,dueMs,checkReason}]×5, issues[{title,status}]×3, texts[{atMs,dir,first}]×2, thin, updatedMs. dueMs는 due가 "YYYY-MM-DD"일 때만, checkReason은 ai_note가 있으면 `confusable` 아니면 `unsure`. 통화기록만 있는 번호도 thin 카드(30일). v1 파일은 읽고 다음 갱신에 v2로. 조회는 메모리 → 파일, 바뀐 번호만 1.5초 모아 다시 씀.

## 5. 화면

### 5.1 구조
- Activity 하나 `MainActivity`(singleTop, 세로 고정) + `enum Screen { HOME, CALLS, TASKS, COMPANIES, SETTINGS, PERSON }`, 화면은 `screens/*Screen.kt`. 하단 탭 다섯(홈, 통화, 체크, 업체, 설정), 사람 화면은 탭이 없고 연 화면의 탭이 켜진 채. 현재 탭을 다시 누르면 상세를 닫고 목록 맨 위. 뒤로: 상세 닫기 → 홈 → 종료. 알림은 `screen`·`dayMs`·`callId` extra로 연다. 화면 전환 120ms 페이드, 시작 화면 `LaunchIntro`(약 2초, 누르면 건너뜀). 골든 측정만 별도 숨김 Activity.
- 즉시 반응: 600ms 두 번 누름 방지(`CallUi.once`), 누르는 즉시 busy 글, [다 됐어요]는 바로 흐려지고 "끝낸 일로 옮겼어요 [되돌리기]" 띠. 저장소는 프로세스 수명 동안 한 번 열고(`IeumApplication.store`) 메인 스레드 접근은 예외.
- 화면은 XML·Compose 없이 코드로 View를 만든다(`CallUi.kt`).

### 5.2 화면별
| 화면 | 보이는 것(위부터) |
| --- | --- |
| 홈 | 인사·날짜 → (설정에서 할 일이 있을 때만) "설정에서 확인할 일이 있어요 ›"(녹음 공백 확정, 업데이트가 사람 확인 대기·실패, 필수 처음 설정 남음, 모델 받기 실패; 누르면 설정의 그 줄) → **오늘 통화 이야기**("오늘 통화 n건", `story/today.json` text 최대 4줄 자르지 않음, 없거나 날짜가 다르면 오늘 정리된 업무 통화 "상대: 첫 문장" 최신 3개, 정리된 통화 0이면 "오늘은 아직 정리된 통화가 없어요" 한 줄) → 먼저 볼 체크할 일 5개("외 n개 보기 ›") → 오늘 통화 메모 → "오늘 한눈에" 숫자 셋(오늘 통화, 오늘 생긴 체크, 남은 체크할 일) → 업체별 체크할 일 → 이번 주 통화 막대 |
| 통화 | 날짜 넘기기(전날/오늘로/다음날), 그날 모든 통화기록이 한 줄씩(받은·건·부재중·개인 통화 "개인 내용" 포함), 문자·메일 기록, 검색(250ms, 사람 절 최대 5명). 상태 글: 자막 작업과 창이 맞으면 "녹음을 글자로 바꾸는 중이에요", 통화 뒤 10분까지 "녹음을 찾는 중이에요", 그 뒤 "녹음 없음", 6시간 넘으면 "정리가 늦어지고 있어요". 빈 자막은 "녹음에서 말소리를 찾지 못했어요"(버튼 없음), `RESULT_NOT_APPLIED`는 "정리 결과를 적용하지 못했어요" + [다시 정리하기](보낸 기록을 지워 바로 재전송). 상세: 요약, 중요한 내용, 체크할 일([다 됐어요], 접기), [통화 내용 전체 보기](14일) |
| 체크 | 남은 체크할 일을 요청 날짜 묶음(오늘 요청한 일, 어제 요청한 일, 그전에 요청한 일, 오래된 일), 진행 중, 끝낸 일. 행(G3): 업체 배지 → 제목(META 이름과 같은 "○○ 님께" 접두는 지움) → META "김민수 님, 오늘 요청"(16sp, 이름 누르면 사람 화면) → AI 메모 → "확인 필요"·"끝났는지 확인" 배지 → [다 됐어요]. 통화에서 온 항목만 눌림. 30개 먼저 그리고 나눠 그림. [직접 적기] |
| 업체 | 업체 목록(최근 통화순, 공종 배지, 열린 일 수), 업체 없는 사람(우리 직원 등). 상세: 업체 메모, 사람(누르면 사람 화면), 진행 중인 일, 체크할 일, 최근 통화 10건, [업체 이름 고치기] |
| 사람 | 이름(30) + 직책·업체, [전화 걸기] [문자 보내기](번호는 화면에 안 씀), 공유 번호면 "이 번호를 쓰는 사람" 칩, 마지막 통화, 체크할 일, 진행 중인 일, 최근 통화와 문자 10건, [이름 고치기], [이 번호는 개인으로 두기](카드 즉시 삭제) |
| 설정 | (주황 불이 있으면 "지금 상태"가 맨 위) → **지금 할 일과 진행 상황**(처음 설정 단계, 처음 정리 진행·쉬는 이유, 모델 받기 %, 녹음 공백 안내; 할 일이 없으면 "지금 할 일이 없어요. 모두 잘 되고 있어요") → 연결(녹음 폴더, 문자, 통화기록, 통화 끝 알아차리기, 메일 IMAP 네이버·다음·Gmail·직접 입력) → 통화 카드(카드 보기 허용, 전화 올 때, 전화 걸 때, 거는 전화 역할, 아직 정리된 통화가 없는 번호도, 잠금 화면에서도 이름과 한 줄 보기, [카드 위치 처음으로]) → 정리(정리 동의, 알림 시각, 통화마다 알림, 새 버전은 자동으로 설치, 설치 허용, 내 정보 세 칸, 개인으로 둔 번호) → 상태와 도움(신호등, 앱 버전 줄·[지금 확인]·[지금 업데이트], 저장 공간 %, 음성 인식 모델, 현장 상황 메모, 처리한 통화 수, [기다리는 통화 다시 정리하기], [합친 사람 되돌리기], [상대 카드 다시 만들기], [문제 알리기], [골든 측정]) |

### 5.3 처음 설정(`SettingsScreen.steps`)
| 구분 | 단계(순서대로) |
| --- | --- |
| 필수 | 녹음 폴더 연결 → 정리 동의(직후 모델 받기 시작) → 통화기록 읽기 허용 → 통화가 끝난 걸 바로 알아차리기(전화 상태) → 알림 허용(두 번 거절 뒤엔 알림 설정 열기) → 배터리 제한 풀기 |
| 선택([나중에 할게요]) | 문자 읽기 허용 → 통화 화면 위에 이음 카드 보기(돌아왔는데 안 켜졌으면 "목록에서 '이음'을 찾아 스위치를 켜 주세요" + [다시 열기]) → 새 버전을 저절로 설치하기(설치 출처 허용) |
- 막힌 단계가 있어도 다음 단계로 갈 수 있다. 이유 문장을 버튼 바로 위에 먼저 보인다. "처음 설정 남은 n단계"(필수가 남으면 앰버). 권한이 한 번 요청 뒤에도 막히면 "앱 정보 → ⋮ → 제한된 설정 허용" 안내와 [앱 정보 열기](`ReadingPermission`). 설정의 처음 설정은 접지 않는다.

### 5.4 신호등(`StatusLights.lights`, 설정 "지금 상태")
| 줄 | 주황 조건(위에서부터 처음 맞는 것) |
| --- | --- |
| 녹음 가져오기 | 폴더 미연결 / 권한 풀림 / 발견 성공이 24시간 넘음 / 녹음 공백 판정 문구(확정된 `noRecording`·`partial`·`folder`, `tooMany`). `pendingText`는 초록 글 |
| 글자로 바꾸기 | 모델 지원 안 됨·실패·없음 / 대기열이 있고 가장 오래된 진입이 6시간 넘고 마지막 자막도 6시간 넘음 |
| 통화 정리 | 정리 동의 꺼짐 / 분석 루틴 예정 실행을 2회 놓침("분석이 n시간 안 돌았어요", 슬롯 뒤 40분이 지나도 자가 보고가 없으면 놓친 것, 받은편지함 오류가 더 늦으면 세지 않음) / 저장 공간 80% 이상 / 정리 못 한 통화(빈 자막 제외) / 기다리는 통화 |
| 알림 | 알림 꺼짐 |
| 업데이트 | 설치 오류 문구 / 준비된 지 24시간 넘음 / 준비됨 |
| 통화 카드 | 끄면 초록 / 오버레이 권한 없음 / 전화 상태 없음 / 통화기록 없음 / 대기 버킷이 EXEMPTED·NONE·UNKNOWN 밖 / 누적 통화 5건 이상에서 번호 없음 20% 이상 / 카드 없음 60% 이상. 역할이 없으면 초록 안내 |
- 빨강은 쓰지 않는다. 4회 놓침은 저녁 자가 진단 `routine_late`로 알린다. 줄을 누르면 고칠 묶음으로 스크롤.

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
| 설정 | `SecureSettings`(암호화 JSON) | 설정 키 약 75개(`PipelineSettingsKeys`, `CallPorts.kt`) |
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
| call_transcription_jobs(state QUEUED/RUNNING/DONE/FAILED/SKIPPED, reason, attempts, queued_ms, audio_ms), _parts, _stalls, _splits, vault_pruned | 자막 대기열과 조각 |
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
| `status/phone.json`, `status/calllog.txt`, `status/last-run-hourly.json`, `status/last-run-tidy.json`, `status/last-run.json`(v3.3까지, 새 파일이 없을 때만 읽음), `status/verify-log.md` | 폰 / 루틴 | 상태 |
| `routines/hourly.md`·`tidy.md`, `tools/watchdog.py` | release-apk | 지시문·워치독 사본 |
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

**알려진 약점(사실대로)**
- 우편함 토큰(`IEUM_INBOX_TOKEN`, 우편함만 다루는 fine-grained 토큰)이 빌드 때 `BuildConfig.IEUM_INBOX_TOKEN`에 그대로 들어간다. APK를 가진 사람은 우편함을 읽고 쓸 수 있다(`build.gradle.kts`).
- `IEUM_SIGNING_SEED` 비밀이 비어 있어 서명 키가 우편함 토큰에서 파생된다(`tools/derive_signing_key.py`, keystore 비밀번호 고정 "ieum-signing"). 토큰을 바꾸면 서명이 바뀌어 업데이트가 막힌다. 먼저 현재 토큰 값을 시드로 복사해야 한다(사용자 할 일).
- 배포 APK는 `assembleDebug` 산출물이다(디버그 빌드 유형, `SYNTHETIC_TOOLS_ENABLED=true`)(release-apk.yml). 비디버그 릴리스는 보류.
- 앱 저장소가 공개다(저장소 안에 비밀은 없음, `tools/scan_export.py`가 ci에서 검사). 비공개 복귀는 사용자 할 일.
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
| 자막이 밀려요 | 새 녹음 대기열 진입이 6시간 넘음(처음 정리 중엔 남은 녹음이 있는데 마지막 자막이 24시간 넘을 때만) |
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

### 8.2 자가 보고와 루틴 실패 길
- `status/last-run-hourly.json`·`last-run-tidy.json` 한 줄 JSON: routine, startedMs, endedMs, processed, skipped, failed, batches, model, limitHit, ledgerRev, promptVersion, promptMismatch, loggedTranscript(항상 false), repoOk, pushed, (hourly만) kinds, firstPassItems, formats, requestVersions, leftover, cron/crons. 처리할 것이 없어도 항상 쓴다(`docs/routines/self-report.md`).
- 루틴이 저장소에 못 닿으면 자가 보고도 못 쓰고 "확인 필요: 저장소 접근"으로 답한다 → 예약 작업 알림. 루틴이 죽으면 워치독도 멈추므로 폰 신호등 "통화 정리" 줄(2회 놓침 주황)과 저녁 자가 진단(4회)이 둘째 눈이다. 폰은 결과 받기 때 `endedMs`·`cron`을 `routineEndedMs`·`routineCrons`에 둔다.
- 통화 목록 검증(09:00): `status/calllog.txt`와 저장·분석 결과를 대조해 검증 파일과 다섯 줄 알림, `status/verify-log.md`에 숫자만.
- 문제 찾기 순서와 증상별 볼 곳은 OPERATIONS.md 4~6절.

## 9. 시험과 배포 게이트

| 게이트 | 내용 | 근거 |
| --- | --- | --- |
| 단위 시험 | `:core:test :app:testDebugUnitTest`(Robolectric) + lint. 소스의 @Test 742개(실행 건수는 마지막 기록이 v3.1 통합 582건, v3.4는 ci 통과만 확인(추정)) | `android-ci.yml` |
| 화면 규칙 | `tools/check_ui_rules.py` 위반 0, 도구 시험(`test_check_ui_rules.py`, `test_watchdog.py`, `test_version_fields.py`, `test_golden_score.py`, `test_call_card_script.py`) | ci "UI rules and tool tests" |
| 비밀 스캔 | `tools/scan_export.py`(비밀 값은 실패, 개인 경로는 경고) | ci "Export scan" |
| 업데이트 문구 | 앱 코드가 바뀌었는데 UPDATE_NOTE가 그대로면 경고 | ci |
| `RoutineDocTest` | hourly.md의 [분석 규칙]·[결과 형식]이 `SYSTEM`·`SCHEMA`와 같고 버전이 맞는지 | 단위 시험 |
| ci → release-apk | lean-calls·main push. 같은 커밋 ci 성공 필수(40분 안). `tools/needs_update_smoke.sh`가 매니페스트·IeumApplication·StartupReceiver·CallSinkImpl·빌드 설정 변경을 보면 update-smoke "Install, update, start" 통과도 필수(70분). 에뮬레이터 부팅 실패(인프라)만 경고 | `release-apk.yml` |
| update-smoke | 직전 릴리스 위에 덮어 설치, 시작, 충돌 없음, 예약 작업 다섯 개, 앱 자신 설치 단계(`appops REQUEST_INSTALL_PACKAGES allow` 뒤 사용자 확인 없이 versionCode 상승) | `update-smoke.yml`, `tools/update_smoke.sh` |
| device-test | feature/** push(안드로이드·도구 경로) 또는 수동. 에뮬레이터 API 34: 모델 SHA-256, 한국어 Whisper(평소 base-q5_1, 음성 인식 파일이 바뀌면 전체), 화면 9장(1_home, 2_today, 3_tasks, 4_settings, 5_companies, 6_company_detail, 7_call_detail, 8_person, 9_home_large 1.3배), 골든(push는 base), 통화 카드 11항목(ringingWindow, stripAfter3s, goneAfterIdle, serviceGone, outgoing, unknownOff, personalNothing, outgoingNoRole, callWaiting, coldWindow, lockedNotice; 기준 벨→창 3,000ms, 콜드는 `am kill`, 통화 대기는 에뮬레이터가 재현 못 해 null, 잠금 위 창은 기록만). 카드 샷 4종은 ci `IEUM_SHOTS_DIR` 산출물 | `device-test.yml`, `tools/device/call_card_test.sh` |
| real-device-test | Firebase Test Lab 최신 Galaxy S(S26), 공개 음성만, 수동, 하루 5번 | `real-device-test.yml` |
| inbox-e2e | feature/** push(그 파일) 또는 수동으로 `inbox/selftest-<번호>.json` 투입, 07:40~23:40 KST 매시 40분에 결과를 폰 파서·필터로 검증 후 삭제 | `inbox-e2e.yml` |
| cleanup-branches | lean-calls에 다 들어간 feature/* 삭제(수동, dry_run) | `cleanup-branches.yml` |
| S26 체크리스트 | 사용자 10분: 잠긴 채 받기, 측면 버튼 잠금 뒤 벨, 다른 앱 중 벨(통화 팝업), 밀어 닫은 뒤 받기·걸기, 받기·거절 터치, 스피커·키패드·스크린샷, 이어폰·블루투스, 1.3배 글자, 긴 문자·사진 문자·채팅+(RCS). 통과 기준 1~8 O(2번 창은 관찰), 9·10 O, 11은 O 또는 "채팅+ 끄기" 안내 | `docs/REAL_DEVICE_CHECKLIST.md` |
| 배포 뒤 확인 기준(1045 설치 뒤 48시간) | matched/withFile ≥ 0.8, long48h.ratePct ≥ 80, callCard null 아님·shown > 0·shownOutgoing/eligibleOutgoing ≥ 90%·발신 noNumber 0·fgsRefused 0·ringToCardMs p50 < 1,000·cold 3건 이상에서 ringToCardMsCold p50 < 1,500, last-run-hourly 슬롯마다 갱신·pushed=true, 무인 설치 2시간 안 versionCode 상승, 요청 본문에 '@' 없음 | DESIGN_V4 2-2, 2-3, 4.5 |

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
| 11/6 | v3.5 이후는 아직 설계하지 않는다 |
| 11/6 08:26 | 카드·체크할 일에는 "언제까지"·"지났어요"가 아니라 문자·메일 등 맥락을 종합한 상태("메일 발송 확인 완료", "문자로 답 받음")를 보여 준다. 약속 날짜는 판단 입력일 뿐 화면에 남기지 않는다. 완료 처리는 그대로 사람이 한다(v3.4.x에서 적용, 지금 카드의 "n일 지났어요"는 뺄 것) |
| (Claude 결정, 설계 표) | 지키지 못할 약속 문구 금지, 마이그레이션 자동 합치기는 같은 번호+같은 이름만, 합치기 되돌리기와 keepApart, 숫자 확실성은 화면에 앰버 배지, 숫자만 다른 일은 합치지 않음, 자막 오인 교정은 이름·호칭·업체명만, 잠금 화면 카드 공개 범위는 설정(기본 켬), 발신 역할은 선택 항목, 처음 설정은 필수/선택, 포그라운드 서비스는 통화 중에만, CallScreeningService 안 씀, 분석은 구독 한도 방식(API 키 경로 삭제) |

## 11. 알려진 한계, 미구현, 보류
- **실측 전:** 아내 폰은 08:01 하트비트 기준 v3.1(1040)이고 1045를 받아 둔 채 설치 대기(사이드로드 뒤 첫 자가 설치는 한 번 눌러야 하는 것으로 보임). 1042~1045의 짝짓기·긴 통화 완주·카드·결과 시각은 폰에서 아직 증명되지 않았다(9절 확인 기준). `latency` p50이 수천 분인 것은 `started_ms` 오염으로 보이며 판단 근거로 쓰지 않는다(`recordTime`으로 진단 중).
- **자막 정확도:** 실녹음 골든(사용자 받아쓰기 3~5건) 전이라 B1(폴백 제한), B2(조각 간 문맥), B3(문장체 초기 프롬프트 ≤ 120토큰, `PROMPT_ENABLED`), B4~B7(beam, suppress_nst, 양자화·i8mm, VAD 하한)은 스위치만 있고 기본값 그대로. 합성 골든(base) CER 18%는 대표성이 없다.
- **다음 후보(3-3):** 충전 중 야간 포그라운드 장기 실행(안드로이드 15+ dataSync 한도), 게인 정규화, 한국어 파인튜닝 모델. 기각: 조각 5분, audio_ctx 축소, max_len.
- **하지 않은 것:** 예약 작업 30분 간격(예약 작업이 허용하지 않음), Actions 분량 조정(device-test push 축소, inbox-e2e 하루 2회 — 비공개 복귀 전에), `corrected`의 화면 표시, 불확실 숫자의 조각 저장 형식 칸, 비디버그 릴리스 빌드, 서명 시드 분리, 우편함 이력 압축, "AI 메모" → "이음 메모" 표기, 아침 알림 [다 됐어요] 2개.
- **S26에서만 알 수 있는 것:** 잠금 화면 위 오버레이가 보이는지, 삼성 "통화 팝업"과의 겹침, 삼성 "절전 앱"과 FGS 거부, phoneCall 타입의 MANAGE_OWN_CALLS 전제, 역할 없이 발신 번호가 늘 오는지, RCS(채팅+)가 `content://mms`에 오는지, 첫 벨 창 1회 실패 원인.
- **카드 다듬기(v3.4.x 후보):** 위치 상수 실측 조정, 1.0배에서 배지 카드의 "외 1개" 줄 넘김.
- 사용자 할 일: S26 체크리스트, 실녹음 골든, cleanup-branches 실행, `IEUM_SIGNING_SEED` 복사, 저장소 비공개 복귀, 예약 작업 알림 조정, Claude Docs 설계도를 이 문서로 갱신.

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
| 새벽 정리 | 05:16 Opus 루틴. 진행 중인 일, 합치기, 체크할 일 다시 쓰기·팔로업 제안 |
| 매시간 분석 | 매시 34분 07~22시 Sonnet 루틴. 요청을 분석해 결과와 장부를 쓴다 |
| 결과 받기 | 폰이 예정 실행 +3·+8·+15분에 결과를 가져와 적용하는 것(`ResultChain`) |
| 하트비트 | 폰이 매시간 올리는 `status/phone.json`(숫자와 코드만) |
| 자가 보고 | 루틴이 실행마다 쓰는 `status/last-run-hourly.json`·`last-run-tidy.json` |
| 워치독 | 루틴 끝에서 도는 `watchdog.py`. 이상이면 GitHub 이슈 |
| 신호등 | 설정 "지금 상태"의 여섯 줄(녹음 가져오기, 글자로 바꾸기, 통화 정리, 알림, 업데이트, 통화 카드). 초록 또는 주황 |
| 녹음 공백 | 통화는 있는데 녹음 파일이 창 안에 없는 상태(`RecordingGap`) |
| 상대 카드 | 번호별로 미리 계산해 작은 봉인 파일에 둔 "이 사람과의 지금 상황"(통화 카드와 사람 화면이 읽음) |
| thin 카드 | 정리된 통화는 없고 통화기록만 있는 번호의 작은 카드("3일 전 통화, 30일간 5번") |
| 통화 카드 | 전화가 올 때·걸 때 통화 화면 위에 뜨는 오버레이. 통화 중에는 띠 |
| 콜드 | 앱 프로세스가 없던 상태에서 벨 방송으로 시작한 경우 |
| 확인 필요 | 잘못 들렸을 수 있는 숫자·날짜나 "알아볼게요" 수준의 일에 붙는 앰버 배지(tentative) |
| 개인 번호 | business=false가 2회 연속인 번호. 카드·알림·요청 없음 |
| 골든 | 정답이 있는 녹음으로 자막 정확도를 재는 것(합성 골든, 실녹음 골든) |
| 지시문 | `docs/routines/*.md`의 "---" 아래. 예약 작업이 우편함 사본을 읽어 따름 |

## 13. 문서와 코드가 달랐던 곳(코드를 따름; 11/6 정리에서 PRODUCT.md·OPERATIONS.md·HANDOFF.md·self-report.md·watchdog.py 주석은 고쳤고, 아래는 기록으로 남김)
- `docs/routines/self-report.md`: 신호등 분석 줄이 "4회면 빨강" → 코드는 빨강 없음, 4회는 저녁 자가 진단 `routine_late`(`StatusLights.ROUTINE_NOTICE`, DESIGN_V4 4.6).
- `docs/PRODUCT.md` 2절: 예약 작업 "매시 정각, 07~23시" → 코드·지시문은 매시 34분 07~22시(`ResultChain.DEFAULT_CRON`).
- `docs/PRODUCT.md` 3.6절: 아침 보고 "오늘 할 일 n개, • [업체] 할 일 — 기한" → 코드는 "체크할 일 n개", "지금 할 일:" 말머리, 기한 없음(`CallDigestText.morning`).
- `docs/PRODUCT.md` 3.3·4.3절: 할 일 카드 "기한 배지, 공정표 막대 기한 지남 빨강", 색 `#1F5C99`·`#B23A2B` → 코드는 요청 날짜 묶음, 빨강 없음, 강조 `#3268F0`(`CallUi` LIGHT 팔레트).
- `docs/PRODUCT.md` 5절: DB 표 7개 → 코드 표는 6.1절처럼 훨씬 많다(`CallRecordStore.SCHEMA`, `STORE_V4_SCHEMA`).
- 옛 HANDOFF(3절 "배포본은 화면 캡처 차단(FLAG_SECURE)") → 코드의 FLAG_SECURE는 통화 카드 창에만 있다(`CallCardOverlay`).
- DESIGN_V3 7.1: 카드 높이 화면의 40% 이하, 세로 38% → 코드는 벨 중 높이 34%, 아래 끝 72%, 기본 위쪽 끝 38%(`CallCardOverlay.MAX_HEIGHT`·`BOTTOM_LIMIT`·`DEFAULT_POS_Y`).
- DESIGN_V4 4.2-3: 첫 줄 "줄당 18자 안팎, 2줄" → 코드는 36자 한도(`FIRST_CHARS`), 셋째·넷째 줄 14자(`ROW_CHARS`)(4.6과 같음).
- DESIGN_V3 6.2: 통화별 알림 잠금 화면 "통화 정리 중"만 → 코드는 상태별 "통화 정리됨 / 확인할 통화 / 통화 정리 중"(`CallFollowupNotice.publicTitle`).
- OPERATIONS.md 4절 `standbyBucket` 값 목록에 EXEMPTED·NEVER가 없음 → 코드는 5→EXEMPTED, 50→NEVER(`PhoneStatus`).
- HANDOFF·OPERATIONS의 새벽 자료 "밤 21~05시" → 코드는 21:00~04:59(`takeTidy`의 `hour in 5..20`이면 안 보냄).
- `android/core` `RecordingPolicy.MAX_BYTES`=25,000,000 → 실제 한도는 금고 `AudioBinding.MAX_BYTES`=60,000,000이고 `RecordingPolicy.safeSize`는 앱에서 부르는 곳이 없다(남은 상수).
- `watchdog.py` 주석·`ROUTINE_END_HOUR`=23("마지막 실행 22:30, 30분 간격") → 실제 cron은 `34 7-22`(규칙 판정은 cron 파싱을 쓰므로 영향은 "분석이 멈췄어요"의 루틴 시간 계산뿐(추정)).
- 문서 여러 곳의 "단위 시험 n건" → 최근 기록된 실행 건수는 v3.1의 582건이고, 지금 소스의 @Test는 742개(9절).
