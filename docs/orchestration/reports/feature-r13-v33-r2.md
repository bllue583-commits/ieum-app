# r13-2 보고서: 자막 정확도 1단계, 골든 측정 화면, 지시문 v7 (feature/r13-v33-r2, 4053f86 + 4309977, 기준 07f4b80)

## 배포 때 할 일
- **hourly 루틴을 v7로 바꾼다.** 앱에 `PROMPT_VERSION=7`이 들어간 APK를 배포할 때 같이 바꾼다.
  - v6 폰도 v7 결과를 읽는다(모르는 키 `corrected`는 무시한다). 지시문은 6을 과도기 요청으로 받는다.
- **tidy 루틴의 mergeCandidates 문장을 고친 것으로 바꾼다.**
  - 폰 형식 `[남긴 체크할 일 id, 기록 key]`에 맞췄다.
  - 버전 번호 5는 그대로 둔다.
- **통합 할 일(오케스트레이터, dca5e1e에서 반영함):**
  - `ClaudeCallAnalyzer`의 SYSTEM을 hourly.md의 [분석 규칙]으로 바꾼다.
  - SCHEMA에 `corrected`를 더한다.
  - `PROMPT_VERSION=7`로 올린다.

## 바꾼 파일
- **`cpp/ieum_whisper.cpp`**
  - `token_timestamps=true`로 켜고, 세그먼트에 `tokens:[[text,p,t0ms,t1ms]]`를 넣는다.
  - `whisper_print_timings` 로그를 파싱해 조각마다 timings(encode, decode, batchd, prompt, sample ms, fallbackP, fallbackH)를 남긴다.
  - JNI `transcribeChunk`에 `float[] options`(temperature_inc, best_of, beam_size, suppress_nst)를 더했다. 기본값은 지금과 같다.
  - 새 함수 `countTokens`(`whisper_tokenize`)를 더했다.
- **`AndroidWhisperEngine.kt`**
  - JNI 선언을 맞췄다.
  - `SttDecode` 변형 스위치를 더했다. 기본값이면 예전 경로를 탄다.
  - `decodeOutput`은 확률이 0.5 미만인 숫자를 `uncertainNumbers`로 넘긴다.
  - A8: 거꾸로 되거나 길이가 0인 세그먼트를 보정한다(repaired). 빈 세그먼트나 깨진 세그먼트만 버린다(dropped).
  - 옛 JSON도 읽는다.
  - `SttPrompt`: 문장체 이름 프롬프트(≤120토큰)와 앞 조각 문맥(≤40토큰).
- **`CallTranscriptionRunner.kt`**
  - `WhisperCallTranscriber`에 decode, promptNames, carryContext, pieceStats 옵션을 더했다. 앱은 모두 기본값을 쓴다.
  - 세그먼트의 uncertainNumbers를 채우고, 조각 숫자를 설정 `sttDecode`에 누계로 쌓는다.
- **`PipelineAudioMath.kt`, `PipelineAudioDecoder.kt`**: `loudShare` 옵션을 더했다(기본 0.3이라 동작은 같다). PipelineAudioDecoder는 소유 밖이라 최소로만 고쳤다.
- **`PhoneStatus.kt`**: 하트비트에 `golden`, `guard`, `transcription.decode`를 더했다. 모두 숫자만 걸러 올린다.
- **`MessageWork.kt` `MessageIntake.mailContent`**
  - "보낸 사람"에는 이름만 쓴다.
  - 본문의 메일 주소는 `[메일 주소]`로 가린다.
  - 단가 '@'는 그대로 둔다.
- **새 파일 `pipeline/GoldenMeasureActivity.kt`**
  - `GoldenScore`: `golden_score.py`를 Kotlin으로 옮긴 것.
  - `GoldenVariant` 7개: base, fallback, context, sentence, beam2, nst, vadLow.
  - `GoldenPlan`.
  - `GoldenStore`: AES-GCM, no-backup 폴더.
  - `GoldenWork`: 충전 중에만 돌고, 6분이 지나면 다시 예약한다.
  - Activity: 녹음 고르기, 정답 붙여넣기(최대 5건), 측정 시작과 다시 하기, 결과 줄. 매니페스트에 exported=false로 등록했다.
- **`tools/audio/golden_score.py`**
  - `number_entities`와 `score_real`을 더했다.
  - 10^16 이상의 수에서 나던 예외를 고쳤다.
  - `golden_parity.json`(합성 7건)을 더했다.
  - 시험 `RealGoldenTest`를 더했다.
- **`docs/routines/hourly.md` v7**
  - 9번: 숫자 확실성.
  - 10번: 제목에 이름과 호칭을 넣지 않는다.
  - 14번: 자막 오인 교정. `corrected`에 적고, 숫자, 날짜, 규격은 고치지 않는다.
  - 17번: 메일에는 보낸 사람 이름만 쓴다.
  - 결과 형식에 `corrected`를 더했다.
- **`docs/routines/tidy.md`**: mergeCandidates 문장 하나를 고쳤다.
- **시험**
  - `RoutineDocTest` v7.
  - `AndroidWhisperContractTest`: A8 기대값을 바꿨다.
  - `UiV2Test`: 내보내지 않는 골든 Activity를 허용하게 바꿨다.

## 시험 결과
- **더한 시험**
  - `SttV33Test` 10: 불확실 숫자, 옛 JSON, A8 계수, 프롬프트 토큰 상한, 문맥, 기본 디코딩, VAD 기본값.
  - `GoldenV33Test` 6: Kotlin과 python 같은 값, 하트비트 숫자만, 요청 본문에 '@' 없음.
  - Python 시험 3.
- **로컬 시험**: `r13-2-compile-2`, `r13-2-test-1~3` 모두 BUILD SUCCESSFUL. check_ui_rules 0건, scan_export 0건.
- **ci**
  - 첫 실행 42068140098은 UiV2Test에서 실패해 고쳤다.
  - 두 번째 실행 42069507185는 성공이다.
- **device-test**
  - 실행 42069507285는 성공이다. 새 .so와 새 JNI로 한국어 자막이 정상이고, 골든 base 3통화도 정상이다.
  - 빠른 모드로만 돌렸다.

## 설계와 다르게 정한 것
- `corrected`는 최상위 배열 `[{"heard","written"}]`로 정했다. 숫자가 섞인 쌍을 버리는 일은 r13-1 filter가 한다.
- 본문의 메일 주소도 가린다. 단가 '@'는 그대로 둔다.
- 골든 지표
  - 업체명 지표는 장부의 업체와 사람 이름 가운데 정답에 나온 것으로 잰다.
  - 숫자 지표는 정답에 아라비아 숫자와 단위로 적힌 것으로 잰다. 화면에서 그렇게 적으라고 안내한다.
- 골든 측정은 포그라운드 서비스가 아니라 충전 중에 WorkManager로 돌린다.
- 조각 숫자는 설치 뒤 누계로 쌓는다.

## 못 한 것
- 3.2-2 이름 목록 순서는 CallRecordStore(r13-1 소유)에 있어 못 했다. 통합 때 dca5e1e로 반영했다.
- 이어 받은 조각에서는 `uncertainNumbers`가 빠진다. parts JSON에 4번째 칸이 없어서다(다음 묶음).
- 토큰 타임스탬프는 네이티브 JSON에만 있다.
- 합성 골든 turbo 90분 측정은 하지 않았다.
  - 방법: device-test를 `full=true golden=compare only=tg_goldenSet`으로 돌리고 `golden_score.py`로 채점한 뒤 `docs/reviews/golden_turbo_<날짜>.md`에 숫자만 적는다.
- 실녹음 골든은 배포 뒤 폰에서 사용자 받아쓰기로 한다. 변형 7개를 다 돌리면 통화 15분 기준 충전 중 약 2시간이다.
- B1~B7 상수는 바꾸지 않았다.

## 다른 묶음이 알아야 할 것
- **r13-3**: 설정 "상태와 도움"에 숨긴 링크 한 줄로 `kr.callbrief.pipeline.GoldenMeasureActivity`("골든 측정")를 연다.
- **하트비트 키**
  - `golden{atMs,samples,done,units,variants{<code>{samples,cerPct,refChars,pieces,msPerPiece,company,person,number}},bySample}`
  - `guard{...}`
  - `transcription.decode{...}`
- **새 설정 키**: `sttDecode`. 골든 상태는 `noBackupFilesDir/golden/state.bin`에 둔다.
- **매니페스트가 바뀌어 update-smoke가 돈다.**
