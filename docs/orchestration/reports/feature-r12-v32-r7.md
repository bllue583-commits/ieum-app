# r12-7 보고서: 녹음과 통화기록 짝짓기 (브랜치 `feature/r12-v32-r7`, 커밋 44de51c, 기준 f629137)

**결론:** 고쳐서 push했고 GitHub ci는 성공했다(https://github.com/bllue583-commits/ieum-app/actions/runs/42061488487). 실측 "25건 중 1건"의 주된 원인은 짝짓기 규칙이 아니다. 녹음 25건 대부분은 자막이 끝나지 않아 짝지을 통화 기록(call_records)이 아예 없었다. 이 몫은 r12-8(긴 통화 자막 완주)이 맡는다.

## 1. 원인과 근거 (파일:줄은 기준 f629137 기준)

### 1-1. 기록이 아예 없는 몫 (대부분)
- 짝짓기는 기록이 있어야 시작된다.
  - 짝은 call_record_log에만 생긴다(`CallRecordStore.kt:1110`, `RecordingGap.kt:186`).
  - 기록은 자막이 끝나야 생긴다(`CallRecordStore.kt:324`).
  - RecordingGap은 폴더 파일만 보므로, 기록이 없어도 withFile로 센다.
- 실측(calllog.txt 22:58판, 최근 48시간):
  - 녹음과 이어진 통화 7건은 모두 0분 5초~0분 33초짜리 짧은 통화다.
  - 30초가 넘는 통화 가운데 이어진 것은 33초 1건이다. 이것이 matched=1이다.
- 그래서 25건 중 약 22~23건은 "기록 없음"이다.

### 1-2. 기록은 있는데 못 이은 몫 (noCandidate 15, far 15)
- 최근 24시간 기록 7건 가운데 못 이은 것은 2건이다(같은 분에 후보 둘 1건, 개인 번호 1건). 따라서 far 15건은 오래된 기록이다.
- 기록 시각은 맞았다.
  - started_ms는 `coalesce(original_started_at_ms, filename_time_candidate_ms)`로 채워진다.
  - 이어진 쌍의 시작 차이는 0~30초다.
  - 우편함 atMs 18건은 모두 통화기록과 1.2분 안에서 맞는다.
- 10월 처음 정리 기록은 통화와의 차이가 들쭉날쭉했다. 30건 중 13건은 ±4분 안이고, 나머지는 9~188분 떨어져 있다. 원인은 추정으로 두 가지다.
  - 가: 10/1 이전 녹음
  - 나: 통화기록에 없는 통화
- 진단 키로 다음 하트비트에서 가릴 수 있게 했다.
- 코드에 있던 실제 구멍 세 가지를 고쳤다.
  1. started_ms가 NULL인 기록은 대조에서 빠졌다.
  2. 파일 이름에 상대가 있어도, 1분 전~2분 후 창 밖이면 잇지 않았다.
  3. 60분 밖은 far 하나로만 셌다.

## 2. 바꾼 파일
- **`FirstPass.kt` `match`:**
  - 파일 이름에 상대(번호 또는 전화부 이름)가 있으면 그 상대의 같은 날(KST) 통화 가운데, ±30분 안이고 길이가 맞는(20% 또는 15초) 통화를 잇는다.
  - 그런 후보가 여럿이면 창 안의 것만 잇는다. 창 안에 없으면 잇지 않는다(several).
  - 파일 이름에 상대가 없으면 기존 규칙을 따른다.
  - r12-4의 끝 시각 후보는 이 규칙에 흡수했다. byEnd는 통계로만 센다.
- **`CallRecordStore.kt` `reconcileInTransaction`:**
  - 대조 시각은 `COALESCE(r.original_started_at_ms, l.filename_time_candidate_ms, c.started_ms, r.modified_at_ms)`다.
  - 시작 시각이 없는 기록에는 이 시각을 채운다.
  - 상대가 없는 기록은 녹음 파일 이름에서 상대를 채운다.
  - 이름만 있는 기록은, 저장된 통화기록에서 그 이름의 번호가 하나뿐이면 그 번호를 붙인다(제외 번호는 쓰지 않는다).
- **`StoreSafetyTest.kt`:** r12-4 사례를 새 규칙에 맞게 고쳤다.
- **새 파일 `RecordingPairTest.kt`:** 시험 9건.

## 3. 진단 (하트비트 callLogMatch, 숫자만)
- `missGapMin`
  - 부호 있는 10분 구간("-60"~"60")으로 센다.
  - ±60분 밖은 far로 센다.
  - 10/1 이전 기록은 before로 따로 센다.
- `missGapHour`: far인 것만 부호 있는 시간 구간으로 센다(±24에서 자른다).
- `byParty`: 파일 이름의 상대로 창 밖에서 이은 수다. 누계도 있다.
- `byPhoneBook`: 전화부 이름으로 번호를 붙인 수다. 누계도 있다.
- `records24h`, `open24h`: 최근 24시간 기록 수와 그중 못 이은 수다.

## 4. 설계와 다르게 정한 것
1. COALESCE에서 `c.started_ms`를 수정 시각보다 앞에 두었다.
2. 창 밖 짝은 점수에 +500을 붙여, 창 안 짝을 빼앗지 않게 했다.
3. 창 안 후보는 자정을 넘어도 후보로 둔다.
4. 끝 시각 후보는 상대 우선 규칙에 흡수했다.
5. 마이그레이션은 하지 않았다. 못 이은 기록은 패스마다 다시 대조된다. MIGRATION_VERSION은 4 그대로다.
6. 전화부 이름으로 번호를 붙일 때 제외 번호는 쓰지 않는다.
7. missGapMin 상한을 50에서 60으로 바꿨다.

## 5. 시험 (RecordingPairTest 9건)
- **새 녹음 경로:** 삼성식 파일 이름 10개(이름, 여러 번호 형식, 날짜 형식 3가지)와 바로 옆의 다른 통화를 두었다. 10건이 모두 올바른 통화에 이어진다. 시작 시각이 없거나 틀려도 이어진다.
- **처음 정리 경로:** 10건, **되감기:** 다음 패스에 10건이 이어진다.
- **재사용 금지:** 이미 쓴 통화는 다시 쓰지 않는다.
- **전화부 이름:** 번호가 하나면 붙이고, 둘이면 붙이지 않는다.
- **상대 우선 경계:** 12분, 20분은 잇는다. 31분, 날짜가 다른 경우, 길이가 다른 경우는 잇지 않는다.
- **후보 둘:** 둘 다 창 밖이면 잇지 않는다. 하나가 창 안이면 그것을 잇는다.
- **상대 정보 없음:** 기존 규칙을 따른다.
- **진단 구간:** 구간 경계 시험을 넣었다.

## 6. 결과
- 로컬
  - `r12-7-compile-1`: BUILD SUCCESSFUL
  - `r12-7-test-1`: BUILD SUCCESSFUL. RecordingPair 9, StoreSafety 8, FirstPass 22, V24Migration 2, RecordingGap 10, PhoneStatus 6, 실패 0
- check_ui_rules 0건, scan_export 0건
- GitHub ci 전체: success

## 7. 못 한 것
- far 15건이 어느 기록인지는 기기 DB가 없어 확정하지 못했다. 다음 하트비트의 `before`와 `missGapHour`로 판단한다.
- matched/withFile ≥ 0.8은 r12-8의 긴 통화 자막 완주가 전제다.

## 8. 다른 묶음이 알아야 할 것
- **새 하트비트 키:**
  - callLogMatch.last에 `missGapHour`, `byParty`, `records24h`, `open24h`, `byPhoneBook`가 생겼다.
  - missGapMin에 `before`가 생겼다.
- **새 공개 함수:** `FirstPass.inWindow`, `lengthFits`, `sameParty`, `missHour`. `match`의 시그니처는 그대로다.
- **r12-8 파일:** 고치지 않았다. `CallTranscriptionRunner.counterpart`를 호출만 한다.
