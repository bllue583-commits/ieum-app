# v3.6 구현 대조 검토 (Opus 중간 작업자, 읽기 전용, 2026-11-07 19시, feature/lean-calls 5fa39cf)

> 설계 [DESIGN_V6.md](../history/DESIGN_V6.md)와 구현(f4d61d2·a99a7b3·b1f9f45·6d0edad)을 대조. 약어 P=`pipeline/PowerPolicy.kt`, R=`pipeline/CallTranscriptionRunner.kt`, S=`pipeline/CallTranscriptionStore.kt`, T=`pipeline/PowerText.kt`, D=DESIGN_V6. 우편함 개인정보 파일은 읽지 않음.

## 종합 판정
구조는 설계대로다. 실행 작업 하나 + 전원 트리거, `PowerPolicy` 순수 함수, W1~W5, P7, 화면·워치독이 모두 들어갔고 차이는 대부분 8절에 근거와 함께 적혀 있다. 다만 6d0edad의 검토 반영 수정이 배터리 실행 안의 재판정과 부딪혀 **배터리 모드가 한 실행에 조각 하나만 하도록 퇴행**했다. 통화기록 길이 추정과 일부 계측(decode today/prev, yieldMs·decodeAudioMs)은 8절 없이 빠졌다. → "설계대로이되 고쳐야 할 회귀 하나"(v3.6.1).

## 항목별
1. **전력 상태 판정 — 설계대로+8절.** 꽂힘 `EXTRA_PLUGGED≠0`(P:81,92), 상태 4·5도 꽂힘(P:79, R16One:119), 무선 디바운스 2분(P:140), 잔량 하락 2%·full/notCharging 제외(P:315-320, D:218), 넉넉 조건 40%·절전·통화 중·달궈짐(P:167-170), 15% 미만도 PowerPolicy("low", R16One:106), sticky BATTERY_CHANGED(P:87), 조각 사이 20초마다 재판정(R:428-431).
2. **배터리 규칙 — 대체로 설계대로.** 오늘·20초~3분·최신순 SQL(S `nextShort`, `ORDER BY q_at DESC`), 예산 20분/5%(P:149-153), 조각 시작한 실행만(R:468), 간격은 doWork 안(P:172-175), 스위치(P:160). **다르고 8절에 없음:** 길이 추정 `lengthSql`(S:186-190)이 `call_log`이 아니라 `call_records.duration_ms`를 봄 — `call_records`는 자막이 끝난 뒤에야 생기므로(`CallRecordStore.kt:400-408`) 대기 녹음에는 거의 값이 없고, 실제로는 `audio_ms` 아니면 바이트÷`bytesPerMs`뿐. 끝난 녹음이 없는 새 설치에서는 `bytesPerMs=null`이라 배터리로는 아무것도 안 함(R16One:213이 이 동작을 확인).
3. **꽂힘 규칙 — 설계대로+8절.** 유선 6·무선 4(P:164-165), 달궈짐 3조건·10분(P:141,162), SEVERE 20분(P:158, R:462·486), 05~07시 90%(P:163), 꽂힘이 풀리면 조각 사이에서 끝내고 바로 다시 걺(R:430,:488). 05~07시는 그 두 시간뿐이라 낮 책상 충전은 안 막음(P:142).
4. **작업 구조 — 설계대로+8절.** 실행 작업 이름 하나·제약 없음(R:540-542), 전원 트리거는 걸기만(R:380-382), 배터리일 때만 걸고 꽂히면 취소(R:410,:549-554), `later`는 틱·KEEP(R:544-547,:375). enqueue 4곳(`AutomaticRecordingDiscovery.kt:110`, `PipelineModelDownloadWork.kt:21`, `SettingsScreen.kt:381`, schedule) 모두 같은 이름, 간격은 doWork에서. 동시에 도는 경로 없음.
5. **낭비 제거.** W1 설계대로(P:184,186, R:229, `Yield` 환불·멈춤 횟수 안 셈 R:312, `requeueNow`에 YIELD R:346). W2 설계대로(432초·`askSplit` R:256). W3 설계대로(세션을 `run()` 소유 R:192,:275, 녹음마다 `previous` 비움 R:208,:92, 오류·멈춤이면 닫고 재사용 안 함 R:237,:244, 스레드 수 실행 중 고정 R:445). W4 타당(8절: 첫 실패에서 영구, 일시 코덱 제외 R:320,:342; INTERRUPTED → `charging_only` S `recoverRunning`; 기한 2회 `noteDeadline`; `EMPTY_TRANSCRIPT`는 자막 작업 코드가 아님). W5 설계대로+8절(꽂힘 실행에서만 R:443, 멈춤·`deadline_count` 초기화, 반 조각 있는 분할 표시 유지 S:71-81). P7 타당(8절, `Cpus_allowed_list` P:181,:209).
6. **계측 — 일부만.** 채워짐: killedChunkMs·deadlineMs·yieldCount·modelLoads·modelLoadMs(R:432-439), mAh는 배터리·시작/끝 화면 꺼짐만(R:471), 판정만 한 기동은 runs에 안 셈(R:411-418). **키만 있고 값 없음:** `yieldMs`, `decodeAudioMs`, stopReasons `constraint`(8절 D:215는 있다고 적음). 온도·headroom·cpuset은 시작 때만(R:406), 끝·하루 최고 없음. `transcription.decode` today/prev 분리 안 됨(`PhoneStatus.kt:318-320`, 8절 없음).
7. **화면·알림·워치독 — 설계대로+8절.** 3.2 문구(T:9,54-59), 상태 글 10분(`CallsScreen`, `CallFollowupNotice.chargerPass`), 신호등 36시간(`StatusLights` `chargeLong`), 저녁 줄(`CallDigestWork` `withCharger`), `watchdog.py` `power_rules` 3개(배터리 규칙은 분 기준만, 8절). 낮음: 꽂혀 있을 때도 "충전하면…" 표시, 예산 소진된 오늘·짧음은 대기 수에 안 들어감(S `powerWaiting`).

## 버그 사냥
- **(b)(e) 높음 — 배터리 실행이 자기 자신 때문에 간격에 걸림.** 6d0edad의 `else if(open>last) last=now`(P:333). 배터리 실행은 첫 조각 전에 `batteryRunStartMs`를 쓰고(R:434), `keepGoing`이 `decide`를 다시 부르면(R:429) `budget()`이 그 실행 자신을 "돌고 있는 실행"으로 보고 `lastBatteryRunEndMs=now`로 만들어 `Wait("gap")`. 결과: **배터리 실행은 조각 1개·녹음 1건만 하고 YIELD**(R:229,:288), 30분 뒤 모델을 다시 읽음. W3와 "짧은 통화 여러 개를 한 실행에"를 깨는 회귀. 3분 통화는 실행 두 번(30분+). 순수 함수 시험(R16One:183-185)은 통과하고 `keepGoing` 연결 시험이 없음.
- (a) 문제없음(밤새 배터리 규칙에 묶이는 경로 없음; 잔량이 오르면 하락 판정 해제 P:318, 보호 상태 제외. 30분 넘게 기동이 없으면 표본을 다시 재서 10분 동안 하락 판정이 풀림 P:302, 작음).
- (c) 문제없음(꽂힘은 STOPPED 1순위 유지; 배터리 `nextShort`는 진행 중 우선 없음 — 낮음). (d) 문제없음. (f) 문제없음("느림"은 20분 지나면 해제 P:288) — 단 6↔4 스레드가 바뀔 때마다 rates·coldRates 초기화(P:268)라 낮 배터리(4)·밤 꽂힘(6)을 오가면 식은 중앙값이 거의 안 쌓임(중간). (g) 문제없음(P:102-105). (h) 낮음: 전원 트리거는 배터리 기동이 있을 때만 다시 걸려 뽑은 뒤 다음 기동 전에 꽂으면 매시간 틱 대기(최대 1시간). (i) 중간~낮음: 삼성 보호(85%)에서는 90%에 못 닿아 매일 05~07시가 의미 없이 쉼(P:163이 status를 안 봄). 기타 낮음: `getThermalHeadroom` 1초 안 중복 호출(R:469,:485-488, NaN은 null로 걸러 피해 없음).

## 시험
R16One 23개·R16Two 7개가 설계 5절 사례 대부분을 덮음. **빠진 사례:** ① 작업에 연결한 상태의 배터리 `keepGoing`(있었으면 높음 버그를 잡음) ② `onStopped`·`cancel` 뒤 세션 재사용 금지 ③ `transientCodec` 제외 ④ `plugDropped`·표본 다시 재기 ⑤ 자정 넘는 `batteryRunStartMs` ⑥ 보호 85%에서 아침 규칙 ⑦ `armPlug`·`later`·`requeueNow`가 실제로 무엇을 거는지(WorkManagerTestInitHelper) ⑧ 통화기록 길이(S `lengthSql`) ⑨ StatusLights "밀려 있어요" 대기 수 빼기 ⑩ 꽂힌 상태의 대기 문구.

## device-test 배터리 시나리오 구체안(v3.6.1)
- `tools/device/battery_policy_test.sh` 신설, `device-test.yml`의 "Call card" 단계와 같은 emulator-runner 블록. 증거 `evidence/battery-policy.json`.
- 선행(앱): doWork에 숫자만 남기는 로그 `Log.i("ieum.power","decide mode=… reason=… threads=… pieces=… state=…")`(지금 로그 없음). 설정값이 암호화돼 있으므로 instrumentation 시험 둘: `t7_seedPowerQueue`(오늘 40초·5분, 어제 녹음을 m4a 자산으로 심기), `t7_reportPower`(`IEUM_RESULT power {ledger}` 출력).
- 순서: ① 설치·모델 복사(`run_device_test.sh` 재사용) → `dumpsys battery unplug; set level 60; set status 3` → 심기 → `am start`(schedule). 확인: `mode=BATTERY threads=4`, 오늘 40초만 DONE, `today.battery.runs=1`, `dumpsys jobscheduler`에 PLUG 작업이 CHARGING 제약·실행 작업은 제약 없음. 짧은 통화 2건을 심어 **한 실행에 2건**이 되는지(높음 버그 검출) ② 바로 다시 걺: `reason=gap`, `gapSkips+1`, runs 그대로, LATER 지연 약 30분 ③ `set level 39` → `low`, `set level 10` → `low` ④ `set ac 1; set status 2` + `cmd jobscheduler run -f`로 PLUG: `mode=PLUGGED`, 스레드 min(6, 에뮬레이터 코어), 5분 녹음 진행/DONE, 끝나면 바로 체인 ⑤ `set status 4; set level 85`(보호 중)에서도 PLUGGED 계속 ⑥ `set ac 0` 90초 뒤 재연결 → `debounce`, 3분 → `plugDropped=1` ⑦ `cmd thermalservice override-status 3` → `hot` 20분 지연, 확인 뒤 `reset` ⑧ `dumpsys battery reset`. 아침 규칙은 `adb root`가 필요해 선택.

## 고칠 것(심각도순)
- **높음 1.** 배터리 실행이 자기 자신 때문에 간격에 걸림(P:333 `last=now`가 R:429 `keepGoing`에서도 적용). 실행 중 재판정에서는 시작 때 budget을 쓰거나 자기 표시를 무시. 작업 연결 시험 추가.
- **중간 2.** 배터리 대기열 길이를 `call_records`가 아니라 `call_log`(시각 창 매칭)로 추정(S:186-190); 바이트 비율 없을 때 대비.
- **중간 3.** 계측 생산: `yieldMs`, `decodeAudioMs`, 온도·headroom 끝 값·하루 최고, `transcription.decode` today/prev.
- **중간 4.** 속도 표본을 스레드 수별로 따로(P:268).
- **중간 5.** 05~07시 규칙에서 notCharging·full이면 쉬지 않음(P:163).
- **낮음 6~10.** 꽂혀 있을 때 "충전하면 n개" 숨김·예산 소진 짧은 통화를 대기 수에; 뽑은 직후 전원 트리거 재등록; headroom 중복 호출 정리; `nextShort` 진행 중 우선; 빠진 시험 10개와 device-test 시나리오.
