# v3.6 배터리 설계 토론 (2026-11-07 16~17시, Fable 초안 ↔ Opus 중간 검토자, 읽기 전용)

> 대상 문서 [../DESIGN_V6.md](../DESIGN_V6.md) 초안(0582abc). 검토자는 코드(`CallTranscriptionRunner.kt`=R, `CallTranscriptionStore.kt`=S, `AndroidWhisperEngine.kt`=E, `cpp/ieum_whisper.cpp`=N)와 안드로이드 공식 문서·AOSP 커밋을 직접 확인했다. 결정은 DESIGN_V6 2~7절에 반영했고, 9절이 요지다.

## 1차: Opus 반박

### 1. 전력 정책
- **1-1 반대 — "짧은 것부터"는 건수만 늘리고 쓸모는 줄임.** 예산 20분에는 모델 로드·해독·4스레드 195초/분이 다 들어가 실제로 바꾸는 녹음은 5~6분, 40초 통화 8~9건. 3분 이하 통화가 하루 30~40건(추정)이면 그날 정리는 20~30%. 짧은 것부터면 10초 "여보세요"가 업무 통화를 밀어냄. **대안:** 3분 이하 중 최신 통화부터, 20초 미만은 충전 때로.
- **1-2 동의(조건부)** — 40%·30분·3분·36시간. 실제 상한은 예산이 정하므로 40%는 보조. 발열 "≤ LIGHT"는 삼성에서 거의 늘 참(추정).
- **1-3 반대 — 20분 = 배터리 2~3%는 낙관.** S26 약 16.7Wh(4,300mAh×3.88V, 추정), 4스레드 Whisper 3~4W면 20분에 1.0~1.3Wh = **6~8%**(추정). **대안:** mAh를 먼저 재고 목표는 "하루 5% 이하"로 적음, mAh 예산은 CHARGE_COUNTER 신뢰 확인 뒤.
- **1-4 반대(근거 약함)** — "낮에는 아예 안 함"이면 6~8% 절약, 잃는 것은 짧은 통화 20~30%. 그래도 스위치 기본 끔 유지에는 동의, 6절이 효과를 잴 수 있어야 함.
- **1-5 대안** — `requiresDeviceIdle`(화면 켜지면 중지돼 조각 버림)·JobScheduler 직접(같은 10분)은 기각. **추가 규칙:** 통화 중(`TelecomManager.isInCall`, `call/CallCardService.kt:146`)에는 시작하지 않음.

### 2. 낭비 제거
- **2-1 동의(수정) W1.** 정상 종료로 `Stopped`를 `noteStall` 없이 던지면 `endStopped`가 환불(R:179, :208-211). 빠진 것 ① 실행의 첫 조각은 항상 시작(R:225의 `taken>0` 방식) — 달궈진 폰에서 473초가 예상 한도를 넘으면 조각 0개로 끝나고 `requeueNow` 거짓(R:276)이라 매시간 기동만 반복. ② 스스로 끝내는 목표는 9분 전 — 안드로이드 14+에서 시간 초과가 잦으면 restricted 버킷(공식 문서), 지금 하루 TIMEOUT 21회.
- **2-2 반대 W3 정적 캐시.** 사실 정정: 모델은 실행마다가 아니라 **녹음마다** 읽음(R:163, :180, :204) — 짧은 통화 여러 건이 들어가는 배터리 실행에서 가장 큰 낭비. 대안 ① `run()` 안에서 세션 하나를 녹음 사이에 이어 씀(정적 참조 불필요) ② 정적 캐시는 충전 연속 실행에서만. 정적 캐시 기각 이유: 네이티브 `cancel`은 취소 표시를 되돌리지 않아(N:86) `onStopped`(R:311) 뒤 핸들은 영구히 못 씀; 스레드 수가 open 때 고정(N:105, :141); 574MB를 품은 백그라운드 프로세스는 LMK 1순위(추정), 안드로이드 14+는 `TRIM_MEMORY_RUNNING_LOW`를 안 보냄(추정, `IeumApplication.kt:29`의 COMPLETE도 안 올 수 있음).
- **2-3 동의(조건) W2.** 예방 분할도 `askSplit`으로 `_splits`에 먼저 써야(S:191), 아니면 다음 실행에서 식었을 때 통째 조각으로 돌아가 저장한 반 조각(id `-(2i+1)`)을 버림(R:193-201). 중앙값 330×1.25=412 < 432라 W2는 달궈진 때만 작동.
- **2-4 반대 고유 작업 2개.** `claim`은 줄 단위 잠금(S:208)이라 두 작업이 다른 녹음을 집어 동시에 돎 → `EXECUTION_LOCK`(E:91)이 둘째를 세워 웨이크락만 쥔 채 시간 초과, 메모리 1.2GB+. 기존 주석도 이름 하나 전제(R:301-302). **대안:** 실행 작업 이름 하나, `requiresCharging`은 걸기만 하는 트리거. enqueue 경로 4곳(`AutomaticRecordingDiscovery.kt:110` APPEND_OR_REPLACE, `PipelineModelDownloadWork.kt:21`, R:304, R:389)이 30분 간격을 깨므로 간격 규칙은 `doWork` 안에서.

### 3. 안드로이드 API
- **3-1 반대 — "충전 중 = PLUGGED≠0 = requiresCharging"은 틀림.** AOSP `BatteryController`는 `isBatteryCharging()`(잔량이 실제로 오를 때), `PowerTracker` 주석 "전원 연결이 곧 충전은 아님", 연결만으로 인정하는 것은 top 앱 예외. 위험: 삼성 배터리 보호(80·85%·적응형)로 충전이 멈추면 제약이 풀려 밤 작업을 시스템이 중지, `onStopped`가 조각을 즉시 버림. "충전이 끊기면 그 조각을 끝내고"는 제약 방식으로 불가. 꽂는 즉시도 과장(`BATTERY_CHARGING_STATE_UPDATE_DELAY`). **대안:** 트리거만 `requiresCharging`, 실행 작업은 제약 없이, 앱이 조각마다 plugged·status 확인.
- **3-2 추정** — CHARGE_COUNTER(µAh)는 삼성에서 보통 나오나 연료계 갱신이 거침; 10분 실행 수백 mAh면 잴 만함. `CURRENT_NOW` 표본, `EXTRA_TEMPERATURE`(신뢰 높음), `EXTRA_STATUS` 추가.
- **3-3 동의(보강)** — `getCurrentThermalStatus` API 29+, `getThermalHeadroom` API 30+(1초보다 자주면 NaN). 삼성은 클럭을 먼저 낮추고 status는 늦게 오름(추정). **대안:** headroom ≥ 0.85 또는 조각 ms ≥ 식은 중앙값의 1.5배면 "달궈짐"(자기 측정). `Result.retry()`(R:316)는 백오프라 "10분 뒤"가 안 됨 → 지연을 명시해 다시 걺.
- **3-4 사실** — 일반 작업 10분(실측 669÷68=9.8분). 안드로이드 16은 실행 시간 할당량 강화(EXEMPTED는 영향 작음, 추정).

### 4. whisper.cpp
- **4-1 반대 P6** — `no_context=true`(N:143)라 조각 사이 문맥 없음, 224토큰은 한 조각 안 창 사이만. prompt 1.5초라 원인 아님. `token_timestamps`는 후처리라 작음(추정), best_of는 폴백(0건)에만.
- **4-2 반대 P4** — 주석에 "S26 CPU에서 flash attention 약 1.7배 느림"(N:100). 이미 측정·기각.
- **4-3 추정(가장 유력)** — 작업 프로세스가 background cpuset(일부 코어)에 묶이고 ggml 스핀 배리어 6개가 적은 코어를 다투면 잘게 쪼개지는 decode가 특히 느림. 정황: "8스레드 약 3배 느림" 주석(R:64), 85초/분(10/30)은 앱을 앞에 띄운 측정일 가능성(추정), encode 창당 28초도 기대보다 수 배. **P7:** 스레드 = `sched_getaffinity` 코어 수. 계측 `/proc/self/cpuset`·`Cpus_allowed_list`.
- **4-4 빠진 변형** — P8 q8_0(같은 가중치 더 정밀, 정확도 같거나 나음, dotprod·i8mm 커널이 q5_0보다 빠른 경우 많음(추정), 약 0.87GB), P9 q4_0+재배열(i8mm, CER 확인), P10 `n_max_text_ctx` 64. `audio_ctx` 축소 기각, 배치 디코딩 해당 없음. 타이밍에 토큰 수가 없어 ms/토큰 불가(N:175).

### 5. 빠진 것
- **5-1 계측** — `STOP_REASON_CONSTRAINT_CHARGING`·TIMEOUT 횟수, plugged·status 따로, 배터리 온도·headroom, cpuset, 조각당 토큰 수, 녹음당 모델 로드 ms, 통화 중 건너뛴 횟수.
- **5-2 반대 6절** — "latency p50 ≤ 90분"은 1-1과 모순(설계대로면 실패). "06시 충전 대기 0"은 밤사이 통화 때문에 불가 → "전날 21시 이전 녹음 0". "이음 ≤ 10%"는 측정 시각(21시, 충전 전)을 정해야 하고 6-1로는 어려움. "gapSkips > 0"은 효과가 아님.
- **5-3 위험** — 배터리 보호 정지, 밤 6스레드 발열로 아침에 충전 덜 됨(무선이면 4스레드), 낮 짧은 충전(차량)에 큰 작업, timeout 잦으면 restricted.
- **5-4 반대 파일 소유** — `power.today`(killedChunkMs, modelLoads, mAh)·decode today/prev는 Runner·`SttPieceStats`(r16-1)에서 생김 → 키·계약은 r16-0. perToken은 N(r16-3) 없이 불가.
- **5-5 시험** — Robolectric `ShadowBatteryManager`(setIsCharging, setIntProperty)·`ShadowPowerManager` 발열(추정, headroom 불확실). WorkManager 제약은 흉내 불가 → 순수 함수 `PowerPolicy.decide(state, budget, now)`로. 에뮬레이터는 `dumpsys battery set status 2` 추가(ac만으로는 충전 중 아님).

### 6. 결론
- **6-1 추정** — 60% → 약 12~20%. 낮 Whisper 20분 6~8% + 발견·네트워크·DB 1~2% ≈ 9%, 다른 앱 45~55%면 비중 ≈ 15%. "10% 미만"은 예산 10분 이하 또는 "낮에 안 함"(3~5%)이어야. 충전 중 소모는 이 화면에 거의 안 잡힘(추정).
- **6-2 가장 큰 반대 — 진짜 병목은 밤의 처리량인데 효율(r16-3)을 배포 뒤로 미룸.** 하루 120분 × 165초 = 330 CPU분, 밤 7시간(420분)은 낭비 0이어도 152분 어치. 배터리 보호·발열이 끼면 대기열이 매일 쌓여 36시간 주황불 상시. P1·P7·계측은 r16-1과 같이(코드 몇 줄과 스위치 수준).
- 참고: [Android 16 동작 변경](https://developer.android.com/about/versions/16/behavior-changes-all), [작업 예약 배터리 최적화](https://developer.android.com/develop/background-work/background-tasks/optimize-battery), [AOSP BatteryController 변경](https://android.googlesource.com/platform/frameworks/base/+/d708555b3bf98351f675cfd8c7d487674b4daae4%5E2..d708555b3bf98351f675cfd8c7d487674b4daae4), [충전 상태 갱신 기준 커밋](https://android.googlesource.com/platform/frameworks/base/+/171d56029e86).

## 1차: Fable 답변(결정)
| # | 결정 | 반영 |
| --- | --- | --- |
| 1-1 | 받아들임 — 오늘·3분 이하 중 **최신 통화부터**, 20초 미만은 충전 때로 | 2.2-3 |
| 1-2 | 받아들임 — 40%·30분·3분·36시간 유지, 발열 ≤LIGHT는 3-3 대안으로 대체 | 2.1 |
| 1-3 | 받아들임 — 예산 "실행 20분 **또는** 그날 배터리 자막 소모 5%(CHARGE_COUNTER 신뢰 시) 중 먼저". 효과 추정 "60% → 15% 안팎" | 0절, 2.2-3, 6절 |
| 1-4 | 유지(스위치 기본 끔). 48시간 뒤 15% 넘으면 예산 10분으로 | 7절 |
| 1-5 | 받아들임 — 통화 중 조각 시작 안 함 | 2.2 |
| 2-1 | 받아들임 — 첫 조각 항상 시작, 스스로 끝내는 목표 9분 | W1 |
| 2-2 | 받아들임 — 정적 캐시 폐기, 한 실행 안 세션 재사용(같은 스레드 수), onStopped 뒤 재사용 금지 | W3 |
| 2-3 | 받아들임 — 예방 분할도 `askSplit` 기록 | W2 |
| 2-4 | 받아들임 — 실행 작업 이름 하나, 충전 트리거는 걸기만, 판정은 `PowerPolicy.decide` 순수 함수 | 2.2-5 |
| 3-1 | 받아들임(중요) — "전원 연결 모드"는 `EXTRA_PLUGGED≠0`을 앱이 조각마다 판정, 실행 작업 제약 없음. 꽂혔는데 10분 넘게 잔량이 내려가면 배터리 규칙 | 2.1, 2.2 |
| 3-2 | 받아들임 — CURRENT_NOW·온도·status·plugged | 3.1 |
| 3-3 | 받아들임 — 달궈짐 = headroom ≥ 0.85 또는 조각 ms ≥ 식은 중앙값 1.5배, 명시 지연 enqueue | 2.2-2 |
| 4-1·4-2 | 받아들임 — P6은 토큰 수·ms/토큰 계측으로만, P4 삭제 | 4절 |
| 4-3 | 받아들임 — P7(스레드 = affinity 코어 수)·cpuset 계측을 **r16-1로** | 4절, 5절 |
| 4-4 | 받아들임 — P8 q8_0, P9 q4_0+재배열, P10 n_max_text_ctx 64, 토큰 수 | 4절 |
| 5-1~5-5 | 받아들임 전부 — 계측 추가, 6절 기준 교체, 위험 4개 추가, power 키·계약은 r16-0/r16-1, 순수 함수·`set status 2` | 3절, 5~7절 |
| 6-2 | 일부 — P1·P7·계측은 r16-1과 같이, i8mm·q8_0는 r16-3(배포 직후 같은 주) | 5절 |

## 2차: Opus 재반박과 Fable 답변
(아래에 이어 적음)

### Opus 2차
- **A. 남은 반대 — 1-3 일부:** CHARGE_COUNTER 차이는 폰 전체 소모라 이음만의 소모는 못 잼. 화면이 켜진 채 실행되면 과대 계상 → 기준은 "화면 꺼진 실행만". 나머지 결정은 모두 동의. 1차 계산 정정: 밤 7시간 152분 어치는 120분보다 크므로 낭비 0이면 하루치를 다 처리함 — 문제는 여유가 1.27배뿐이라는 것.
- **B. plugged 판정 함정:** 조각마다 `registerReceiver(null, ACTION_BATTERY_CHANGED)` 고정(sticky) 값을 읽으면 됨(꽂혀 있으면 Doze에 안 들어감). 무선 패드는 잠깐 끊겼다 이어짐 → "끊김 2분 미만은 꽂힘 유지" 디바운스. 배터리 보호 중 status는 `NOT_CHARGING`(4)·`FULL`(5)(추정) — 충전 아님으로 쓰면 안 됨. 1% 단위 잔량으로는 10분 하락을 놓침 → `CURRENT_NOW` 부호로, 삼성은 부호·단위(µA/mA)가 기종마다 달라 첫 실행 때 충전 중 값으로 맞춤(추정). PC USB 500mA는 실제로 줄어듦 → 잔량이 오를 때만 충전. 실행 작업 제약을 없애면 `requiresBatteryNotLow`(15%)도 `PowerPolicy.decide`가 맡아야.
- **C. 밤 처리량:** 120분을 420분에 끝내려면 1분당 210초, 조각당 420초가 한계. 모델 로드·해독·발열 여유 20%와 짧은 충전(3.5시간)까지 보면 **조각당 210~280초가 목표**. 식은 실측 223초. P7(cpuset)로 10/30의 170초(85초/분)에 가까워질 수 있음(추정). 지속 발열(330초+)은 P7만으로 불확실, P8(q8_0·i8mm)까지 필요할 수 있음.
- **D. 밤에 충전이 덜 될 위험(추정):** Whisper 6스레드 SoC 4~6W, 배터리 16.7Wh. 유선 25W면 순충전 15W+라 위험 작음(적응형 충전은 80%에서 기다리다 아침에 채움). 무선 10~15W는 배터리 40℃ 넘으면 충전이 3~5W로 줄어 순충전 0 안팎 → 7시간 뒤 60~80%. **대책:** 무선이면 4스레드, `EXTRA_TEMPERATURE` ≥ 40℃면 조각 시작 안 함, 07시 이후에는 남은 일이 있어도 쉼.
- **E. 구현 함정:** ① W1 스스로 끝내기에 `stop()`을 쓰면 `noteStall`이 불려 환불 6회 제한(R:169-171, :210) → 별도 예외(Yield)를 `run()`에서 잡아 QUEUED·STOPPED로 환불, `nextQueued` 진행 중 1순위(S:154)·`requeueNow` 유지 ② 세션 이어 쓰기: `transcribeRecording`의 finally가 닫음(R:204) → 소유를 `run()`으로, 익명 세션 `previous`(R:76, B2)는 녹음마다 비움, 스레드 수(P7 값)는 open 때 ③ 배터리 모드 대기열을 Kotlin에서 `holdOld`처럼 거르면 안 맞는 첫 줄에서 실행이 끝남(R:228) → SQL에서 ④ `audio_ms`는 해독 뒤에야 생김(S:195) → 바이트 수·통화기록 길이로 추정 ⑤ `requeueFailedOnce`는 저장소를 열 때마다(S:59-62) → 호출 자리를 충전 실행으로 ⑥ `noteRun`은 정책 판정만 하고 끝난 기동도 실행으로 셈(R:337) → 예산은 "조각을 시작한 실행"만, `Result.retry()`(R:316, :342)는 명시 지연 enqueue로.

### Fable 2차 답변(최종)
- A 받아들임: mAh는 화면 꺼진 실행만 합산(`power.today.battery.mAhScreenOff`), 그 밖은 분 기준.
- B 받아들임 전부: sticky BATTERY_CHANGED, 무선 디바운스 2분, status 4·5는 꽂힘 유지, 잔량 하락은 CURRENT_NOW 부호(첫 실행 보정) 또는 잔량이 10분에 1% 이상 내려감, 15% 미만은 PowerPolicy.
- C 받아들임: 6절에 "조각당 ms 목표 280초 이하(식은 상태 210초)"를 넣고, P7·계측을 r16-1에, P8·i8mm을 r16-3(배포 직후)로.
- D 일부: 무선 4스레드·40℃ 규칙 받아들임. "07시 이후 쉼"은 낮 책상 충전을 막으므로 "05:00~07:00에 잔량 90% 미만이면 쉼"(아침 출근 전 충전 우선)으로 좁힘.
- E 받아들임 전부 — DESIGN_V6 5절 "구현 함정"으로.
