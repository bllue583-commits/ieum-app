# 이음 새벽 정리 (매일) 지시문
지시문 버전 4

이 파일이 예약 작업 "이음 새벽 정리 (매일)" 프롬프트의 원본이다(설계도 C2, "새벽 정리" 절). 루틴 프롬프트에는 아래 "---" 다음부터 끝까지를 그대로 붙여 넣는다.
지금 예약 작업에 들어 있는 판(11/4 2판: 중복 방지, 자가 보고 설계도 B3 포함)과 같다. 저장소 문장만 clone 안내를 더해 고쳤다.
v3.1(r12-3): 폰이 처음 정리 중(firstPassDone이 false)이어도 최근 14일 records를 올린다. 1번에서 firstPassDone으로 멈추지 않고, 그동안은 3번 대신 최근 14일만 읽고 tidiedOnce를 쓰지 않는다(처음 정리가 끝난 뒤 10월부터 전체 정리가 한 번 일어난다).
v2.6(11/5): state의 tasks에 rev, state에 mergeCandidates가 실린다. 6번에 "제안 항목에 rev를 그대로 적는다", 7번에 "없어지는 쪽은 지우지 않고 mergedInto만 적는다"를 보탰다. 폰은 rev가 지금과 다른 제안을 건너뛴다(밤사이 통화로 바뀐 일을 옛 판단으로 덮지 않는다). 바꿀 때는 이 파일을 먼저 고치고 루틴 프롬프트에 전문을 붙여 넣은 뒤, 이름, 시각, 링크를 사용자에게 알린다.

---

이음 새벽 정리 (매일).
사용자(bllue583)가 아내의 업무 기록 정리를 위해 직접 만든 자동 작업이다. 아내도 이 정리와 보고를 알고 동의했다. 하루 한 번 06시 아침 보고 전에 돈다. 대화나 질문 없이 아래만 하고 끝낸다. 가능한 한 자세하고 꼼꼼하게 대조한다. 저장소는 bllue583-commits/ieum-inbox 이다. 작업 폴더에 없으면 ls /home/claude 로 찾고, 그래도 없으면 gh repo clone bllue583-commits/ieum-inbox 로 받는다.

작업 폴더는 ieum-inbox 저장소의 기본 브랜치다. 읽는 파일:
- ledger/ledger.json: 장부 {rev, updatedMs, siteMemo, companies[{name, trade, memo}], people[{id, name, company, role, isStaff, aliases[], locked[]}], issues[{id, title, companies[], status, recordKeys[], taskIds[]}], issueSuggestions[], corrections[], tidiedOnce}
- tidy/state.json: 폰이 밤에 올린 자료 {generatedMs, firstPassDone, tasks[{id, title, due, status, company, createdMs, locked, rev}], records[{key, atMs, kind, counterpartId, company, summary, keyPoints}], recentlyDone[], mergeCandidates[](있을 때만)}
- tidy/proposal.json: 이번에 쓸 제안(이전 것이 있으면 덮어쓴다)
- tidy/project.md, tidy/history/YYYY-MM.md, tidy/briefing.md: 이 작업의 기억(9번)

중복 방지: `git pull` 직후, 기본 브랜치에 오늘(한국 시각) 날짜로 메시지가 "tidy"인 커밋이 이미 있으면 다른 정리 작업이 먼저 끝낸 것이다. 그때는 장부와 제안을 건드리지 않고 마지막 단계(자가 보고)만 한 뒤 11번 형식으로 "이미 정리됨"이라고 보고한다.

1. `git pull` 한다. tidy/state.json이 없거나 generatedMs가 지금보다 24시간 넘게 오래됐으면 아무것도 고치지 않고 마지막 단계(자가 보고)만 한 뒤 11번 형식으로 "정리할 것 없음: 이유"라고 보고한다(이유 예: "폰 자료가 이틀째 안 옴"). firstPassDone이 false여도 멈추지 않는다(폰이 아직 처음 정리 중이면 최근 14일 기록만 올라와 있다, 3번).
2. ledger/edits/*.json(사용자 수정)이 있으면 먼저 장부에 반영한다. rename이면 그 id의 사람 이름을 바꾸고 예전 이름은 aliases에, locked에 "name"을 넣는다. 반영한 파일은 `git rm` 한다.
3. 읽을 범위: state의 firstPassDone이 false이면(폰이 아직 처음 정리 중) state의 records(최근 14일)를 날짜순으로 전부 읽고 그 범위 안에서만 정리한다. 이때는 7번의 tidiedOnce를 쓰지 않는다(처음 정리가 끝나면 폰이 10월부터 전체를 올리고 그때 처음부터 다시 정리한다). firstPassDone이 true이고 장부의 tidiedOnce가 true가 아니면 10월 1일부터의 records를 날짜순으로 전부 읽고 서로 대조해 처음부터 다시 정리한다. 그 뒤로는 9번의 기억 파일을 먼저 읽고, 열린 issues에 묶인 기록(recordKeys)과 최근 7일 기록 전체를 읽는다.
4. 진행 중인 일(issues): 지난 통화, 문자, 메일을 "진행 중인 일" 단위로 묶고 한 줄 현황을 쓴다(예: "철근 단가 재견적: 업체 회신 대기, 금요일 계약 예정"). 진행 중인 것만 최근 것부터 20개까지 둔다. 있던 일은 id를 유지하고, 새 일의 id는 "i_"와 8자다. 끝난 일은 뺀다(끝난 일의 경과는 project.md와 history에 남는다). issueSuggestions와 corrections를 판단해 반영한 뒤 비운다.
5. 팔로업: 약속했는데 이후 기록에 이행 흔적이 없는 것, 물어보고 답을 못 받은 것, 상대가 하기로 하고 소식이 없는 것을 찾아 "○○에게 견적 회신 받았는지 확인"처럼 체크할 일로 제안한다(op create, 근거 날짜와 상대를 basis에 남긴다). recentlyDone에 있는 일은 다시 살리지 않는다.
6. tidy/proposal.json을 {"rev": 이전 rev+1, "basedOnMs": state의 generatedMs, "tasks": [...]}로 쓴다. tasks 항목은 다음 중 하나다.
   - {"op":"rewrite","id","rev","title","due"}: 흐릿한 항목을 누가, 무엇을, 언제까지로 다시 쓴다(due는 YYYY-MM-DD, 모르면 키를 뺀다).
   - {"op":"merge","id","rev","into"}: 중복을 하나로 합친다. 남길 항목에 기한이 없으면 합쳐지는 항목의 기한을 넘긴다.
   - {"op":"done_suggested","id","rev"}: 나중 기록에서 끝난 것으로 보이면 "끝났는지 확인"만 표시한다.
   - {"op":"create","title","due","company","basis"}: 5번의 팔로업.
   create가 아닌 각 제안 항목에는 state의 rev를 그대로 적는다(그 항목의 state tasks에 있는 rev 값, 바꾸거나 계산하지 않는다). 폰은 rev가 지금 값과 다르면(state를 올린 뒤 통화로 바뀐 일) 그 제안을 건너뛴다. create에는 rev가 없다.
   state에 mergeCandidates가 있으면 폰이 서로 비슷해 중복일 수 있다고 본 체크할 일의 id 쌍 목록이다. merge를 판단할 때 이 쌍부터 대조하되, 대상(문서, 공정, 회차)이 다르면 합치지 않는다.
   완료 처리는 하지 않는다(사용자가 누른다). locked가 true인 항목은 바꾸지 않는다. 제목에 가운데 점, 말줄임표, 영어를 쓰지 않는다.
7. 장부 정리: 같은 업체의 다른 표기는 하나로 합치고 없어지는 쪽에 mergedInto를 적는다. 번호 없이 이름으로만 있는 중복 사람도 mergedInto로 합친다(번호가 다른 두 사람은 폰이 합치지 않으므로 합치지 않는다). 없어지는 쪽 업체와 사람 항목은 지우지 말고 mergedInto만 적어 둔다(폰은 그 항목의 mergedInto를 보고 합친다). 다음 새벽 정리에서 폰 state에 그 이름이 더 이상 보이지 않으면 지운다. siteMemo는 600자, 업체 memo는 400자 안으로 짧게 다시 쓴다. locked 항목은 바꾸지 않는다. 끝나면 tidiedOnce를 true로 쓴다. 단 state의 firstPassDone이 false인 날(3번)에는 tidiedOnce를 쓰지 않고 장부에 있던 값을 그대로 둔다.
8. 원칙: 근거 없는 내용을 만들지 않는다. 파일은 Read 도구로 읽고 직접 판단하며, 결과를 스크립트로 일괄 생성하지 않고 파일 내용을 터미널에 출력하지 않는다. 장부와 기억 파일에 전화번호, 녹음, 자막과 문자 원문을 넣지 않는다. JSON은 `python3 -c 'import json;json.load(open("파일"))'`로 형식만 확인한다.
9. 기억 파일을 쓴다. 이 작업은 매번 새로 시작해 지난 실행을 기억하지 못하므로, 아래 파일과 장부가 10월 1일부터 공사가 끝날 때까지의 모든 흐름을 아는 유일한 기억이다. 공사는 몇 달에서 몇 년 이어지므로 지난 기록은 지우거나 줄이지 않는다. 시작할 때(1번 뒤) project.md, 이번 달과 지난달의 history 파일, briefing.md를 먼저 전부 읽고, 진행 중인 일이 더 오래된 달과 이어져 있으면 그 달 파일도 찾아 읽는다. 오늘 정리는 그 흐름에 이어서 한다.
   - tidy/project.md: 공사 전체의 큰 그림(통째로 다시 쓰되, 지난 내용은 지우지 말고 고치거나 보탠다). 순서: "## 현장 개요"(현장, 공사 범위, 우리 쪽 담당), "## 공정 흐름"(공정별 시작·끝·현재 상태를 날짜와 함께, 예: "3층 슬래브 타설 11/12 예정"), "## 업체별 현황"(업체마다 공종, 계약·견적·발주 상태, 주요 담당자, 마지막 연락일, 이 업체와 있었던 중요한 일 날짜순), "## 주요 결정"(언제 누가 무엇을 정했는지 날짜순, 쌓기만 한다), "## 반복되는 문제"(같은 문제가 여러 번 나온 것, 예: 반입 지연).
   - tidy/history/YYYY-MM.md: 달마다 한 파일인 업무 일지. 하루에 한 블록씩 덧붙인다. 처음 전체 정리(firstPassDone이 true이고 tidiedOnce가 true가 아님)면 10월 1일부터 날짜별 블록을 모두 만든다. 처음 정리 중(firstPassDone이 false)에는 state에 있는 날짜의 블록만 만든다. 블록 형식은 "## YYYY-MM-DD (요일)" 다음 줄들에 "- 업체 사람: 무슨 일, 정해진 것 (통화/문자/메일)" 한 줄씩(그날 기록 전부, 개인 내용 제외), 마지막에 "- 남은 일: …". 이미 있는 날짜 블록은 새로 알게 된 것만 고치고 지우지 않는다. 지난달 파일은 그 달 기록이 새로 들어왔을 때만 고친다.
   - tidy/briefing.md: 오늘 아침 기준 한 장짜리 현황판(통째로 다시 쓴다). 순서와 형식:
     "# 이음 현황 YYYY-MM-DD (요일) 아침"
     "## 오늘 꼭 할 일" — 기한이 오늘이거나 지난 체크할 일, 오늘 약속된 일. "- 누구에게 / 무엇을 (근거 날짜)".
     "## 전화해야 할 곳" — 팔로업(5번)과 회신 대기 중 연락이 필요한 것. "- 업체 사람 (전화부 이름): 왜 (마지막 연락 날짜)".
     "## 답을 기다리는 중" — 상대가 하기로 하고 아직 소식 없는 것. "- 업체 사람: 무엇 (약속한 날짜, N일째)".
     "## 이번 주 일정" — 기한이 7일 안인 일과 통화에서 정해진 일정(타설, 반입, 검사 등) 날짜순.
     "## 진행 중인 일" — issues 전부, "- 제목: 현황 (시작 날짜부터 N일째)".
     "## 끝났는지 확인할 일" — done_suggested로 제안한 것.
     "## 어제와 달라진 것" — 어제 briefing과 비교해 새로 생긴 일, 끝난 일, 바뀐 날짜.
     각 줄은 기록(records)과 장부, 기억 파일에 근거가 있어야 하며, 업체·사람 표기는 장부를 따른다. 전화번호, 녹음, 자막과 문자 원문은 넣지 않는다.
10. 장부의 rev를 1 올리고 updatedMs를 지금 시각으로 쓴다. 장부, 제안, project.md, history 파일, briefing.md, 지운 파일을 한 커밋 "tidy"로 push한다. 충돌이 나면 `git pull --rebase` 후 다시 적용한다.
11. 보고: briefing.md 전체를 이 세션 작업 폴더에 `이음_현황_YYYYMMDD.md`로도 쓰고, SendUserFile 도구가 있으면 사용자에게 보낸다. 마지막 답(알림으로 감)은 이 형식 그대로다(한국 시각).
   [이음 새벽 정리 MM/DD HH:MM] 정리 완료 (정리할 것이 없었으면 "정리할 것 없음: 이유", 이미 정리됐으면 "이미 정리됨")
   오늘 꼭 할 일 N개 | 전화할 곳 N곳 | 답 기다리는 일 N개 | 진행 중인 일 N개
   가장 급한 것: 오늘 꼭 할 일 중 맨 위 한 줄(없으면 "없음")
   전화: 전화해야 할 곳 앞의 세 곳을 "업체 사람(이유)"로 쉼표로(없으면 "없음")
   제안 M개 (다시 쓰기 a, 합치기 b, 끝났는지 확인 c, 팔로업 k) | 장부 rev n
   정리할 것이 없었던 날도 briefing.md가 있으면 그 briefing으로 위 형식을 채워 보고한다.

마지막 단계(자가 보고). 모든 처리가 끝나면 (처리할 것이 없었어도, 일부가 실패했어도 항상) `status/last-run.json`을 새로 써서, 장부와 같은 커밋에 넣어라(정리할 것이 없었으면 이 파일만 커밋 "status"로 push한다). 파일이 이미 있으면 통째로 덮어쓴다. 형식은 아래 한 줄 JSON이다.

```json
{"routine":"tidy","startedMs":0,"endedMs":0,"processed":0,"skipped":0,"failed":0,"batches":0,"model":"","limitHit":false,"ledgerRev":0,"promptVersion":"","promptMismatch":false,"loggedTranscript":false}
```

- `routine`: 이 작업은 `tidy`.
- `startedMs`, `endedMs`: 이번 실행의 시작과 끝 시각(밀리초 에포크).
- `processed`: 읽은 기록 수. `skipped`: 건너뛴 수. `failed`: 처리하려다 실패한 수. `batches`: 제안한 항목 수.
- `model`: 이번 실행에 쓴 모델 이름.
- `limitHit`: 사용 한도 오류를 만났으면 `true`, 아니면 `false`.
- `ledgerRev`: 이번에 쓴 장부의 rev. `promptVersion`은 빈 문자열, `promptMismatch`는 `false`로 둔다.
- `loggedTranscript`: 원문을 출력하거나 로그에 남기지 않았으므로 항상 `false`.

이 파일에는 시각, 숫자, 모델 이름, 참/거짓만 쓴다. 자막 원문, 이름, 전화번호, 회사 이름, 요약, 오류 메시지 본문은 절대 쓰지 않는다. 실행이 중간에 막혀 결과를 못 썼더라도 이 파일은 `processed`를 실제 값(0이어도)으로 써서 커밋한다. 커밋 메시지에는 건수 외에 내용을 적지 않는다.
