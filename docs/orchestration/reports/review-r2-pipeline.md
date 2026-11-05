# R2 파이프라인·자동화·지연 검토 (Opus, 11/5 17시, 기준 157ffab) — 점수 4/10
총평: 코드는 11/5 파이프라인 검증 21건 중 17 닫힘·3 부분·1 열림이지만 폰은 v2.4라 실효 0. "녹음과 이어진 통화 0건"을 앱·하트비트·워치독 어디도 감지·안내하지 않음. 무인 설치는 첫 수동 설치 전제이고 그 뒤에도 조용히 막힐 수 있음(권한 선언 누락, 설치 출처 미허용 시 무기록). 지연 목표 25분은 루틴 처리 시간·사용자 몫(루틴 cron 30분, 지시문 v6 교체 — 둘 다 미확인) 빠진 값, 실제 35~50분.
## 발견
1 치명 폰 버전 정체 경보 없음: 워치독은 updateAvailableCode·updateReadySinceMs(v2.6+ 필드)만 봄(watchdog.py:158-161), version.json과 하트비트 versionCode 비교 없음 → 규칙 추가(옛 폰도 통함).
2 치명 녹음 없음 감지 없음: SelfCheckNotices(:46-59)·StatusLights(:56-61)·저녁 보고(CallDigestWork.kt:32-38,57)·워치독 어디에도 → 폰이 "24시간 받은·건 통화 n건 vs 그 시각 근처 새 녹음 0" 판정, 아내 알림("통화 녹음이 꺼져 있는 것 같아요 → 전화 앱 설정 → 통화 녹음 → 자동 녹음"), 하트비트 recordingGap, 워치독 규칙.
3 높음 통화별 알림이 녹음 존재를 안 봄(CallFollowupNotice.kt:77-94,159-163,219) → 30분 안 발견·자막 작업 없으면 "녹음이 없어 정리할 수 없어요".
4 높음 통화기록 대조: v2.4 match는 CACHED_NAME 완전 일치 필요(2375983 FirstPass.kt:81-86); v3.0은 창 안 후보 하나면 잇지만 창이 기록 시작 −2분~+1분(FirstPass.kt:118-127) → 파일 이름이 통화 끝 시각이면 2분 넘는 통화 전부 noCandidate. 실패 쌍 시각 차이 분포 없음 → "기록 시각−길이" 후보도 시도, missGap 분포.
5 높음 무인 설치: 매니페스트에 UPDATE_PACKAGES_WITHOUT_USER_ACTION 없음(AndroidManifest.xml:3-23) → setRequireUserAction(USER_ACTION_NOT_REQUIRED)(CallUpdate.kt:230) 무효 가능. update-smoke는 adb 설치만(update-smoke.yml:203-217).
6 높음 설치 출처 미허용이면 조용히 포기(CallUpdate.kt:218), 하트비트에 상태 없음 → LAST_UPDATE_ERROR=INSTALL_SOURCE_NOT_ALLOWED, installAllowed, 처음 설정 단계.
7 높음 루틴 지시문: last-run promptVersion 5 = 루틴 프롬프트가 아직 v5(hourly.md:264,274) → 사람·업체 추가 규칙(v6) 안 돌고 있을 가능성. 워치독 "promptVersion ≠ 저장소 hourly.md 버전" 규칙.
8 높음 루틴 주기 */30 미확인, 문서 어긋남(HANDOFF 49·164, OPERATIONS 21 vs hourly.md:4). 자가 보고에 cron·처리 소요.
9 높음 하트비트 Health에 READ_PHONE_STATE·오버레이·역할 항목 없음(PhoneStatus.kt:65-70,243-257) → phoneState/overlay/redirectRole/installAllowed; 워치독 v2.9+ phoneState=false 경보.
10 중간 결과 적용 실패 순환(setReceived 즉시, 3회 실패 → bad/ → 6시간 뒤 재전송, CallRecordWork.kt:86,102-110), 지표 없음 → resultApplyFailed·badResults, 두 번 bad면 FAILED.
11 중간 자막 대기 나이가 updated_ms(claim/finish마다 갱신, StatusLights.kt:151) → queued_ms 열, transcriptionFailed.
12 중간 배터리: 새 녹음 없어도 15분마다 자막 작업 enqueue(AutomaticRecordingDiscovery.kt:106, sawNew 미사용) → 저장 수>0일 때만.
13 중간 저녁 7시 보고 직전 수신 60초만(CallDigestWork.kt:112,160-169) → 18:45 결과 체인 고정.
14 중간 정확도 수치가 폰 모델(ggml-large-v3-turbo-q5_0 574MB, PipelineModelManager.kt:58-60)이 아닌 base → 실제 모델로 골든 1회.
15 낮음 이상 세그먼트 버린 수 미집계(AndroidWhisperEngine.kt:94-105). 16 낮음 FLAGS 열 없는 공급자는 모두 partial(SelectedRecordingSource.kt). 17 낮음 결과 체인 12회 뒤 1시간 틱만.
## recorded 0의 원인 후보(가능성순)
가 v2.4 발견 2000 한도(폴더 전체를 공급자 순서로 끊음; 대기열 가장 오래된 2025-08 → 폴더 수천 개) — queuedCount 49 vs 통화 1,022와 48시간 26건 미업로드를 함께 설명. v2.6+는 10/1 이전 제외·최신 2000·inventoryTruncated.
나 v2.4 match 이름 완전 일치 규칙. 다 시각 창 불일치(끝 시각 명명이면 v2.6+도 실패). 라 삼성 자동 녹음 꺼짐(판정 장치 없음). 마 폴더 권한(낮음).
v2.6+에서 가르는 법: inventoryTruncated → 가, lastDiscoveryOutcome → 마, callLogMatch.last(noTime/noCandidate/nameNull/several) → 나·다, newQueuedCount vs callLog.today.unrecordedCalls → 라.
## 통화 종료→결과 경로(5분 통화): 감지 즉시 → 발견 2.5~3분 → 자막 6~7분 → 업로드 수 초 → 루틴 대기 15/30분 → 루틴 처리 3~8분 → 수신 평균 5분 → 적용 즉시. 평균 35분(30분 주기)/50분(매시), 낮 최악 75~90분, 밤은 다음 날 07시. 단계마다 안전망 있어 1시간 안 재흐름(좋음).
## 11/5 파이프라인 21건: 닫힘 17, 부분 3(#5,#11,#15), 열림 1(#20); recorded 0 열림; push 루틴 깨우기 열림.
## 개정 제안 10: 1 폰 버전 정체 경보 2 녹음 공백 감지·안내 3 통화별 알림 "녹음 없음" 4 무인 설치 완성(권한·오류 코드·단계·smoke) 5 대조 진단(끝 시각 후보·missGap) 6 권한·루틴 상태 하트비트·워치독 7 지연 실측(latency 중앙값)·cron 확정 8 적용·자막 실패 지표 9 새 녹음 있을 때만 enqueue 10 실제 모델 골든 측정.
