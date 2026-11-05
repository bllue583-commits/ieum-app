# R3 데이터·정확도·보안 검토 (Opus, 11/5 17시, 기준 157ffab) — 점수 5/10
총평: 11/5 데이터 보고서 22건 중 17 닫힘·5 부분. 장부가 빈 이유는 구조적: "진행 중인 일"은 새벽 정리만 만들고 새벽 정리는 firstPassDone 뒤에만 돌며(tidy.md:20, CallRecordWork.kt:175-180,261), 사람 추가 규칙은 루틴 본문 v6에만 있는데 루틴은 아직 v5. 숫자·날짜·규격 오인식 가드 없음(인용만 검증, tentative 저장만 되고 화면 미표시). v2.4→v3.0 직행 마이그레이션 시험 없음, 되돌릴 수 없는 사람 합치기.
## 발견
D1 치명 새벽 정리가 처음 정리 완료 뒤에만 → issues 영원히 0 → 분리(최근 14일 state·issues는 처음 정리 중에도).
D2 높음 루틴 본문 v5(사람 규칙 v6에만, hourly.md:36) → 교체 확인·워치독 규칙.
D3 높음 숫자 가드 없음(ClaudeCallAnalyzer.kt:217,346-369; tentative 미표시 TasksScreen.kt:20-60; 카드 40자에 "(숫자 확인 필요)" 잘림 ContactCards.kt:209-213) → 제목·기한 숫자가 인용/자막에 없으면 tentative+"확인 필요" 표시, 기한 범위 검증.
D4 높음 v2→v4 직행 시험 없음(StoreFoundationTest.kt:69는 v3 시작), 열기 실패 시 Log.w만(IeumApplication.kt:17,45), 16MB/10만 행 초과 시 동일 → 픽스처 시험, storeOpenError 하트비트·알림.
D5 높음 사람 합치기 비가역(CallRecordStore.kt:238-271,1243-1255; NameKey.kt:111 2음절 자모 1 차이도 합침), mergeLog id만, 설계 8절 되돌리기 미구현 → 스냅샷 저장·되돌리기·ledger edit merge, 2음절 유사는 자동 제외.
D6 중간 인용 3-gram 0.9가 CER 18%에서 너무 엄격 가능(ClaudeCallAnalyzer.kt:126,353); DROPPED_ITEMS·DUPLICATE_SKIPPED 하트비트 없음.
D7 중간 결과 받았는데 미적용 기록 재전송 없음(CallRecordWork.kt:121; 푸는 길은 3회 실패뿐) → 24시간 넘으면 받은 표시 해제, receivedNotApplied.
D8 중간 메일 보낸 사람 주소가 요청에 그대로(MessageWork.kt:179, ClaudeCallAnalyzer.kt:306).
D9 중간 mergeCandidates 형식 불일치(폰 [item id, 기록 key] vs tidy.md:31 "id 쌍").
D10 중간 골든은 base+TTS, 폰은 large-v3-turbo-q5_0, 초기 프롬프트 꺼짐(AndroidWhisperEngine.kt:89-93) → turbo 재측정, 장부 이름·용어 초기 프롬프트 A/B.
D11 중간 [이름 목록] updated_ms 순 60/300, 대조 0이라 [통화기록] 없음 → 전화부 이름·최근 30일 상대 우선.
D12 낮음 미분석 자막 무기한 적체 → 30일 정책, dbPercent 70% 경보. D13 낮음 파일 이름 원문 이름에 번호 섞이면 카드 노출(noNumber), 모르는 번호 카드에 분석 전 문자. D14 낮음 장부 rev마다 모든 카드 재계산. D15 낮음 지시문 규칙 10. D16 낮음 복구 실패 영구 정지. D17 낮음 공개 저장소: 비밀 없음, APK는 비공개 우편함 릴리스; 단 IEUM_SIGNING_SEED 비어 있어 서명 키가 우편함 토큰에서 파생(build.gradle.kts:9) → 시드 분리.
## 설계와 다른 것: 3.4-3 mergeCandidates 형식, 3.5 inbox-e2e people 검사 없음, 8절 카드 sourceRev 없음, merge_log 되돌리기 없음, 1절 "처음 정리→새벽 정리" 순서는 설계 변경 필요(D1).
## 개정 제안 10: 1 새벽 정리 분리 2 루틴 버전 감시 3 숫자 가드 4 v2.4→v3.x 직행 시험·storeOpenError 5 합치기 되돌리기 6 정확도 계수·결과 고착 해제 7 turbo 골든·초기 프롬프트 8 이름 힌트·noNumber 9 지시문 v7(D8,D9,D15) 10 용량 정책.
