# r12-8 보고서: 긴 통화 자막 완주 (DESIGN_V4 2-3절 1~6, 브랜치 `feature/r12-v32-r8`, 커밋 4fa07dc, 기준 8bdfb0d)

2-3절 1~6을 모두 구현했다. GitHub ci는 성공했다(https://github.com/bllue583-commits/ieum-app/actions/runs/42061755977).
- 네이티브 코드(`cpp/`)는 바꾸지 않았다.
- 시그니처에는 기본값 인자만 더했다.

## 바꾼 파일
- **`CallTranscriptionStore.kt`**
  - **A2 `nextQueued` 순서:**
    1. 진행 중인 녹음(조각이 저장됐거나 사유가 STOPPED 또는 ANDROID_STT_DEADLINE)을 대기열에 먼저 들어온 순서로
    2. 24시간 넘게 기다린 녹음
    3. 새 통화를 최신순으로
    - 처음 정리는 뒤로 간다. 그 안에서는 진행 중이 아닌 것을 오래된 것부터 한다.
  - **A4:** `call_transcription_splits` 표와 `askSplit`, `splitAsked`.
  - **A1:** `audio_ms` 열과 `noteLength`.
  - **하트비트용:** `heartbeat()`, `TranscriptionStats`, `TranscriptionRuns`, 상수 `WAITED_MS`, `DEADLINE`.
- **`CallTranscriptionRunner.kt`**
  - **A3:**
    - 실행이 6분(`NEW_WORK_MS`)을 넘기면 새 녹음을 집지 않고 MORE로 끝낸다.
    - 실행의 첫 녹음이 아닌 것이 시간 한도로 멈추면 정체로 세지 않는다(`harmlessStop`의 `laterInRun`).
  - **A4:** 중지 신호 없이 `ANDROID_STT_NOT_COMPLETED`로 끝나면 반 쪼개기를 표시한다. 상태를 QUEUED와 `ANDROID_STT_DEADLINE`으로 두고, 시도 횟수를 돌려준 뒤 같은 실행에서 반으로 다시 한다.
  - **A5:** 1600샘플 미만 조각은 런타임에 넘기지 않는다.
  - 녹음 길이를 기록하고, 실행마다 `noteRun`을 남긴다.
- **`PipelineAudioMath.kt`:** 0.1초 미만 끝 조각을 앞 조각에 합친다. `PipelineChunker`의 확정 대기 길이를 늘렸다.
- **`PhoneStatus.kt`:** 하트비트 최상위에 `transcription`을 더했다(counts 필터를 거친다).
- **`CallPorts.kt`:** `TRANSCRIPTION_RUNS="transcriptionRuns"`.
- **`tools/inbox/watchdog.py`:** 규칙 (마) "긴 통화의 자막이 끝나지 않아요"를 더했다.
  - 조건: `long48h.ratePct`가 80 미만인 상태가 24시간 이어질 때.
  - 본문: 건수, 비율, 가장 오래 기다린 시간, 실패 사유 상위 3개.
  - 시험 `test_watchdog.py`도 함께 더했다.
- **새 파일 `R12EightTest.kt`:** 시험 8개.
  1. 9분 녹음 하나와 30초 통화 8개가 있고, 실행 사이에 새 통화가 3개씩 들어온다. 긴 녹음이 3번 실행 안에 DONE이 된다. 정체 0, FAILED 0.
  2. 6분이 지나면 새 녹음을 집지 않는다. `laterInRun`을 판정한다.
  3. 기한 초과가 반 쪼개기를 거쳐 DONE이 된다. 실패 0, 시도 1.
  4. 기한 초과 녹음이 새 통화보다 먼저 처리된다.
  5. A2 순서를 지킨다.
  6. 0.07초 끝 조각을 병합한다(전체 자르기, 스트리밍 자르기 모두). 0.05초짜리 녹음은 NO_SPEECH가 된다.
  7. 하트비트 숫자가 `PhoneStatus.build`에 반영된다.
  8. 저장소의 `heartbeat()` 값을 확인한다.

## 로컬 결과
- `r12-8-compile-1`: BUILD SUCCESSFUL, `e:` 0건.
- `r12-8-test-1`: 관련 클래스 10개, 시험 86개, BUILD SUCCESSFUL, 실패 0.
- check_ui_rules 0건, scan_export 0건, `tools/inbox` unittest 30개 OK.
- 전체 시험과 lint는 GitHub ci로 판정했고 성공했다.

## 설계와 다르게 정한 것
- **A3 시도 환불:** "그 실행의 첫 녹음이 아닌 것"으로 범위를 좁혔다. 첫 녹음까지 환불하면 끝없이 돌 수 있다.
- **A3 6분 제한:** 실행의 첫 녹음에는 적용하지 않는다. 아무 일도 하지 않는 실행이 생기지 않게 하려는 것이다.
- **A4 기한 초과:** 실패로 세지 않고 바로 반으로 다시 한다. 반 조각이 또 기한을 넘기면 일반 오류로 센다.
- **A5 병합:** 합친 조각이 최대 2분을 0.1초 넘길 수 있다. 런타임 상한보다 훨씬 작아서 문제없다.
- **길이를 모를 때:** 바이트당 ms로 추정하고, 추정할 근거가 없으면 unknown으로 둔다.
- **"100초 넘는 통화"의 범위:** 통화 시각 기준 최근 48시간이다. ARCHIVED 같은 건너뜀은 빼고, NO_SPEECH는 완료로 센다.

## 못 한 것
- 배포 뒤 확인 기준(24시간 동안 100초 넘는 통화의 완료율 80% 이상, 대기 감소)은 실기기 하트비트로만 확인할 수 있다.
- 실행 기록은 오늘과 어제 것만 남긴다.

## 다른 묶음이 알아야 할 것
- **하트비트 `transcription`**(숫자만):
  - `failReasons`
  - `queuedByLen{lt1m,m1to3,m3to10,m10plus,unknown}`
  - `doneByLen48h`
  - `long48h{total,done,ratePct}`
  - `oldestWaitMin`
  - `runs{day,runs,ms,minutes,ends,prev}`
- **새 설정 키:** `transcriptionRuns`.
- **새 사유:** `ANDROID_STT_DEADLINE`(QUEUED에서만 쓴다).
- **DB:** `call_transcription_jobs.audio_ms` 열과 `call_transcription_splits` 표를 더했다.
- **r12-7 소유 파일:** 고치지 않았다.
