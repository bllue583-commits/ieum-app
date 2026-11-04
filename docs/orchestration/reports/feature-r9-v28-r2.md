# feature/r9-v28-r2 상대 카드 저장소 (78ffef6, 완성) — 작업자 보고 요약
- pipeline/ContactCards.kt 신설. 별도 암호 파일 contact-cards-v1.sealed, 표 contact_cards(phone_key PK, json, updated_ms). ProtectedDatabase 변경 없음.
- 공개 함수: get(phoneKey), get(context,phoneKey), preload(context):Int, warm(context), touch(store, keys:()->Collection<String?>), touch(store, keys), touchAll(store), flushPending(store):Int, refresh(store, phoneKeys):Int, rebuildAll(store):Int, shutdown(). (브리프의 touch(keys)와 달리 store를 받아 context를 얻음.)
- 카드 JSON v1: v:1, people[{personId,name,role,isStaff}](최근 통화 순), company{id,name,trade}|null, lastCallMs, lastDirection(in/out/missed/rejected), recent[{atMs,kind,summaryFirst}]×3, openTasks[{id,title,requestedMs,status,aiNote}]×5, issues[{title,status}]×3, texts[{atMs,dir,first}]×2, updatedMs. 번호 원문·통화 전문 없음.
- 훅: applyAnalysis, completeItem, undoDone, addUserItem, renamePerson, reconcileCallLog, forgetPersonal, insertMessage(문자·메일). applyLedger·applyTidy는 touchAll(전체 재계산). preload는 IeumApplication.onCreate와 매시간 틱(CallAnalysisWork 끝)에 warm. 개인·제외 번호는 카드 없음(있던 카드 삭제). 키는 PhoneKey.of로 재계산. Robolectric에서는 백그라운드 계산 꺼짐 → 시험은 flushPending.
- **남은 것: r9-1 마이그레이션(v4) 직후 `ContactCards.touchAll(this)` 한 줄**(그 전엔 건드리지 않은 번호에 카드 없음). 설정 [카드 다시 만들기] 버튼은 다음 묶음.
- 측정(호스트 JVM): 300장(603KB) 전체 읽기 68ms(목표 60ms 약간 초과), 캐시 적중 85µs.
- 시험: ContactCardsTest 5/5, CallRecordStoreTest 18/18, 관련 7개 클래스 통과, compile OK.
