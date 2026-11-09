# 이음 배터리 소모 원인 감사 (Opus 중간 작업자, 읽기 전용, 2026-11-07 16시)

> 기준 `feature/lean-calls` 3b4ebcd(v3.5 빌드 1046). 우편함에서는 `status/phone.json`(11/7 14:57 KST)만 읽음. 경로 약어: `K/` = `android/app/src/main/java/kr/callbrief/`, `P/` = `K/pipeline/`. 이 보고를 바탕으로 쓴 설계는 [DESIGN_V6.md](../history/DESIGN_V6.md).

## 1. 자막 작업 스케줄
- **제약은 배터리 부족 아님 하나뿐** (`P/CallTranscriptionRunner.kt:378`). 충전 중, 기기 유휴, expedited, backoff 설정은 없음. 매시간 틱은 KEEP으로 한 번 거는 일만 함(`:303-304`, `:385-390`).
- **한 번 실행이 끝나면 곧바로 다음 실행이 붙음.** 결과가 `MORE`이거나 `STOPPED`인데 조각을 하나라도 끝냈으면 즉시 다시 검(`:276`, `:339`, APPEND_OR_REPLACE `:380`). 발견 작업도 새 녹음을 보면 다시 검(`K/AutomaticRecordingDiscovery.kt:110`). 이 때문에 실측 가동률이 87%까지 나옴.
- **`powerOk`(충전 중이거나 50% 이상)는 일부 녹음에만 적용** (`P/FirstPass.kt:53-56`): 처음 정리가 진행 중이고 7일보다 오래된 녹음에만(`P/CallTranscriptionRunner.kt:333`, `FirstPass.kt:44-46`). 7일 이내 녹음은 배터리가 15% 안팎이 될 때까지 계속 돎. 하트비트상 `firstPass.done=false`, 오래된 대기 27건.
- **시간 한도:**
  - NEW_WORK_MS 6분(`:269`)은 "새 녹음을 시작하지 않는다"는 조건일 뿐이고 진행 중인 녹음의 다음 조각은 막지 않음(`:225`).
  - 조각 하나는 2분(`P/PipelineAudioMath.kt:104`), 네이티브 deadline 540초(`:262`, `cpp/ieum_whisper.cpp:123`).
  - 조각당 330~470초가 걸리므로 10분 실행에서 끝나는 조각은 사실상 1개. 두 번째 조각은 WorkManager가 중단시키고, 끝나지 않은 조각은 저장되지 않음(`:179-187`).
  - deadline에 걸리면 540초를 다 쓴 뒤 그 조각을 반으로 나눠 처음부터 다시 하고 시도 횟수는 돌려줌(`:184`, `:249`). 같은 조각에서 3번 멈춰도 반으로 나눔(`:195`, `:278`).
  - **실행마다 모델(574MB)을 다시 읽음**(`:180`, `K/AndroidWhisperEngine.kt:105`).
- **낭비 규모(추정):** 11/7 가동 669분(68회). 끝난 조각 통계는 누적 36조각, encode+decode 12,014초(약 200분)뿐이고 이 통계는 날짜별로 초기화되지 않음(`P/PhoneStatus.kt:36`, `P/CallTranscriptionRunner.kt:324`). 따라서 Whisper CPU의 약 70% 이상이 버려진 조각에 쓰였다고 봄.
- **배터리·발열을 보는 곳 전부:** `TranscriptionThreads.current`(`:39-44`), `FirstPass.powerOk`(`FirstPass.kt:53`), 배터리 부족 아님 제약(`:378`, `P/VaultPruneWork.kt:23`), 발열은 `currentThermalStatus>=SEVERE`일 때만 retry하거나 중단(`:314-316`, `:329`; LIGHT·MODERATE는 무시), `isIgnoringBatteryOptimizations`는 보고용(`PhoneStatus.kt:362`). 골든 측정만 충전 중 제약이 있는데 사용자가 열 때만 돎.

## 2. Whisper 스레드와 우선순위
- **스레드 수:** 충전 중이거나 50% 이상이면 6개, 아니면 4개(`:40`). 녹음마다 시작할 때 한 번 읽고 네이티브에서 1~8로 제한(`ieum_whisper.cpp:97`, `:141`).
- **우선순위와 코어 친화도를 정하는 코드는 없음.** `setThreadPriority`, nice, affinity 모두 0건이라 일반 우선순위로 큰 코어를 점유(추정).
- **성능 설정:** `flash_attn=false`, `use_gpu=false`(`:100`), greedy 디코딩·폴백 시 best_of 5(`K/AndroidWhisperEngine.kt:44-57`), `token_timestamps=true`(`:140`), 빌드 `armv8.2-a+dotprod+fp16`(i8mm 없음, `cpp/CMakeLists.txt:15`; q5 행렬 연산 성능을 다 못 쓰고 있을 수 있음(추정)).
- **모델:** `ggml-large-v3-turbo-q5_0` 574MB(`P/PipelineModelManager.kt:58-60`).
- **속도가 예상보다 많이 느림.** 주석은 6스레드에서 녹음 1분당 85초(`:64`), 실측은 165초 이상. 마지막 조각 encode 124초 + decode 349초. decode가 encode의 2~3배인 것도 이상(원인 미확인, 측정 필요).
- 온도는 SEVERE 단계에서만 반응. 그 밖에 온도를 보는 코드 없음.

## 3. 실패 재큐(v3.5)
- 새 빌드마다 FAILED 건을 시도 0으로 되돌림. 되살리는 횟수 MAX_REQUEUES 2, INTERRUPTED는 1, 시도 한도 MAX_ATTEMPTS 3(`P/CallTranscriptionStore.kt:66-74`, `:249-250`, `P/CallTranscriptionRunner.kt:260`). 이때 멈춤 횟수와 분할 표시는 지우지 않음.

| 사유 | 한 번 시도에서 쓰는 것 | 최악 |
|---|---|---|
| CodecException (14건) | MediaCodec 해독 중 실패. 앞서 끝난 조각은 저장돼 있어 재시도 땐 해독만 다시 함. 빨리 실패(수 초, 추정). 원인이 고정적이라 재큐해도 같은 실패 | 9회 × 수 초 |
| ANDROID_STT_NOT_COMPLETED (5건) | 반 조각이 540초를 넘기거나 whisper가 오류를 냄. 540초를 다 쓰고 실패 | (최초 540초) + 3시도 × 540초 × 3회차 ≈ 81분/녹음 |
| STALLED (2건) | 멈춤 6번까지는 시도를 돌려주고(`:263-265`) 그 뒤 3번을 셈. 실행당 약 600초 | 첫 회차 약 90분 + 재큐 2회 × 30분 ≈ 150분/녹음 |
| INTERRUPTED (2건) | 프로세스가 죽은 경우. 시도는 그대로 셈(`Store:134-141`) | 3 × 600초 × 2회차 ≈ 60분 |

- 하트비트상 되살린 건 70, 그중 FAILED가 다시 23건.

## 4. 그 밖의 주기 작업(제약 / 일량)
- **발견(15분, 제약 없음)**(`K/AutomaticRecordingDiscovery.kt:216`): 한 번에 최대 75초(`:18`, `:103`). 새 파일을 기다리는 동안 21초 sleep이 들어갈 수 있고(`recording-discovery-android/.../DiscoveryPump.java:42-44`, quiet 20초 `DiscoveryTypes.java:57`) 그동안 작업 웨이크락 유지. SAF 조회는 폴더 트리 깊이 4까지 최대 50,000행이고 매 패스 처음부터 전체 나열(`K/SelectedRecordingSource.kt:45`, `:72-101`). 매 패스 모든 행의 seenRound가 바뀌고(`RecordingDiscovery.java:84`) 문서 UPDATE가 실행돼(`K/GeneralLocalStore.kt:94-100`, `K/ProtectedDiscoveryQueue.kt:44-58`) DB 전체 스냅샷을 1회 이상 다시 씀.
- **통화 1건당 발견 패스 3~4회:** aftercall expedited 15초 sleep 뒤(`:202-209`, `:262`), 통화기록 트리거 140초 뒤 + 통화기록 전체 대조 트랜잭션(`:174-178`, `:249`), followup 150초 뒤·새 파일이 보이는 동안 계속(`:164-170`).
- **매시간 분석 틱(네트워크)**(`P/CallSinkImpl.kt:56-58`, `:113`): 분석, ensureWork, 하트비트(전체 job 조회, 통화기록 업로드 `PhoneStatus.kt:232-240`, `:310`).
- **결과 받기 체인:** 루틴 시각 +3/+8/+15분, 시간당 최대 3회(`P/CallRecordWork.kt:476`, `:574-579`).
- **문자·메일:** 30분마다 네트워크, IMAP 접속(`P/MessageWork.kt:246-247`).
- **업데이트 확인:** 6시간(`P/CallUpdate.kt:493-499`) + 프로세스 시작마다 1회(`K/IeumApplication.kt:16`).
- **프로세스 시작 때마다:** 봉인 DB 전체 복호화·복원, 연락처 카드 데움(`IeumApplication.kt:22-24`).
- **vault-prune:** 하루 1회, 배터리 부족 아님(`VaultPruneWork.kt:21-23`).
- **저녁·아침 보고:** 하루 1회씩. 최대 3분·2분 동안 2초 간격 sleep 폴링(`P/CallDigestWork.kt:254`, `:330`), 아침 받기 `P/report/ReportFetch.kt:97-99`.

## 5. 서비스, 수신기, 웨이크락
- **CallCardService:** RINGING이고 카드가 필요할 때만 시작. IDLE 3초 뒤 종료, 10분마다 `isInCall` 확인, 최대 4시간(`P/call/CallCardService.kt:52-58`, `:195-205`, `:224`). IDLE을 놓쳐도 최대 10분이면 종료.
- **수신기:** 부팅(`P/StartupReceiver.kt`), PHONE_STATE(`P/call/CallStateReceiver.kt:18-30`).
- **WakeLock·WifiLock 직접 사용과 ContentObserver 없음**(0건). WorkManager가 작업 중 웨이크락을 잡으므로 자막 628분 = 웨이크락 628분.
- 1초 주기 루프는 화면이 보일 때만(`P/screens/HomeScreen.kt:254-268`, `P/screens/SettingsScreen.kt:122-135`).

## 6. 저장소 비용
- 쓰기 트랜잭션 하나: 전체 DB를 JSON으로 → 현재 파일 읽어 SHA 확인 → AES-GCM 암호화·쓰기·fsync → 다시 읽어 SHA 확인(`call-core-android/.../ProtectedDatabase.java:236-259`, `:265-281`, `:284`). 바뀐 행이 없으면 건너뜀(`:248`).
- DB 약 6MB(16MB의 38%, `P/CallRecordStore.kt:89`). 트랜잭션당 약 6MB 직렬화·약 18MB 입출력(추정 0.5~1.5초).
- 자막 1건(조각 N개): claim, savePart×N, clearParts, noteLength, insert, finish ≈ N+5회(`Store:182-218`, `P/CallRecordStore.kt:400`). SecureSettings도 조각마다 갱신. 발견 1회 1~3회, 하트비트 0~1회. Whisper 조각당 330초 이상에 비하면 1% 미만.

## 배터리 소모 추정 순위표
| 순위 | 항목 | 근거 | 하루 추정 |
|---|---|---|---|
| 1 | Whisper 연속 실행 | 87% 가동, 70% 이상 낭비 | 6스레드로 800~1,000분 이상 가동 |
| 2 | 실행마다 모델 재로드 + 프로세스 시작 때 DB 복원 | | 3~10 CPU분(추정) |
| 3 | 발견 패스 | 하루 120~150회, 전체 SAF 나열 + 전체 스냅샷 + sleep 중 웨이크락 | 3~8분(추정) |
| 4 | 분석·결과 체인·하트비트 | 시간당 1~4회, 네트워크 + DB | 2~5분(추정) |
| 5 | 문자·메일 | IMAP, 30분마다 | 1~3분 + 통신 칩 깨움(추정) |
| 6 | 그 밖 | 업데이트, prune, 보고, 카드 서비스 | 1분 미만 |

남은 대기분 녹음 104분. 낭비를 포함하면 6스레드로 약 9~17시간이 더 걸리므로 이틀가량 더 이어짐(추정).

## v3.6에서 고치면 효과가 클 항목
1. 쌓인 녹음 처리에 충전 중 또는 기기 유휴 조건. 배터리 사용 중에는 한 번 끝나도 곧바로 다시 걸지 않게(`:339`).
2. 남은 실행 시간이 예상 조각 시간보다 짧으면 조각을 시작하지 않음. 또는 조각을 짧게 나누거나 장시간 작업(setForeground)으로 10분 중단 낭비 제거.
3. 발열 기준을 LIGHT 또는 MODERATE로 낮추고, 스레드 4개와 백그라운드 우선순위 적용.
4. 모델·빌드: i8mm 빌드, 더 작거나 빠른 모델, decode 시간 원인 조사.
5. CodecException은 다시 큐에 넣지 않고, 재큐할 때 멈춤 횟수 처리를 정리.
6. 하트비트에 날짜별 "버린 조각 ms".

## 효과가 작을 항목
발견 작업의 seenRound 전체 쓰기, DB 전체 스냅샷 구조, 프로세스 시작 때 업데이트 확인, 메일 30분 주기, 카드 서비스, 화면 1초 루프.
