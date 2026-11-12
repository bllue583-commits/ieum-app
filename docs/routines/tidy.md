# 이음 새벽 정리 (매일) 지시문
지시문 버전 8

이 파일이 예약 작업 "이음 새벽 정리 (매일)" 프롬프트의 원본이다(설계도 C2, "새벽 정리" 절). v3.4(11/6)부터 예약 작업 프롬프트는 짧은 안내다(ieum-inbox를 pull하고, 실패하면 "확인 필요: 저장소 접근"만 답하고 끝냄, 아니면 저장소의 `routines/tidy.md`를 읽고 첫 "---" 다음부터 끝까지를 지시문으로 따름). 이 파일이 원본이고 배포 때 ieum-inbox `routines/tidy.md`로 복사한다.
지금 예약 작업에 들어 있는 판(11/4 2판: 중복 방지, 자가 보고 설계도 B3 포함)과 같다. 저장소 문장만 clone 안내를 더해 고쳤다.
v3.1(r12-3): 폰이 처음 정리 중(firstPassDone이 false)이어도 최근 14일 records를 올린다. 1번에서 firstPassDone으로 멈추지 않고, 그동안은 3번 대신 최근 14일만 읽고 tidiedOnce를 쓰지 않는다(처음 정리가 끝난 뒤 10월부터 전체 정리가 한 번 일어난다).
v3.5(11/6, DESIGN_V5 1절, 2.2절, 버전 7): 9번 뒤에 9-1 "아침 보고서"를 더했다. 1절 형식의 `report/YYYY-MM-DD.json`(한국 시각 오늘)을 쓰고 `tools/report_check.py`로 검사한 뒤 10번 "tidy" 커밋에 넣는다. 폰이 05:30에 그 파일을 받아 카카오톡 "나와의 채팅"으로 보낸다. 30일 지난 report 파일은 지운다. 자가 보고에 `reportWritten`. 루틴 시각은 05:16에서 04:46으로 옮겼다(05:30 전에 끝나게).
v3.7(11/10, DESIGN_V7 3.2 M1, M3, M4, 버전 8): 시각을 04:46에서 05:05로 옮겼다(매시간 분석이 03:34, 04:34에도 돌아 밤에 만든 자막이 분석된 뒤에 정리한다). 폰은 21:00부터 04:58까지 결과를 적용할 때마다 state를 올린다(전에는 21시대 한 번). state에 `pending{yesterdayCalls, yesterdayMin}`(어제 통화 중 아직 자막이나 분석을 기다리는 수와 분)과 records의 `doneMs`(폰이 결과를 적용한 시각)가 실린다. 9-1 보고서에 `head.pendingCalls`와 `late[]`(그제 이전 통화 중 지난 보고 뒤에 정리된 것, 최대 5줄)를 더했다. 카카오톡 첫 줄의 대기 수는 폰이 05:30 보내기 직전에 다시 센다. 같은 v3.7(r17-0)에서 메일 기능을 지웠다(9번 history와 9-1 `yesterday`의 메일 언급 삭제). 폰은 새 메일을 가져오지 않고, v3.7 전에 정리된 메일 기록만 records에 남을 수 있어 4번의 "지난 통화, 문자, 메일"은 그대로 둔다.
루틴 시간: 매일 05:05 (`CRON_TZ=Asia/Seoul 5 5 * * *`, v3.7부터. 그 전 04:46은 `46 4`. 예약 작업 시각은 오케스트레이터가 배포 시각에 바꾼다).
v3.4(11/6, DESIGN_V4 4.3-1, 4.3-2, 4.3-7, 버전 6): 저장소 접근 실패 길(`git pull`이나 push가 실패하면 고치지 않고 "확인 필요: 저장소 접근"으로 보고하고 끝냄), 2번에 업체 이름 고침(renameCompany), 6번 다시 쓰기에서 숫자를 바꾸지 않는 규칙(폰이 숫자가 달라진 다시 쓰기를 거부함), 자가 보고를 `status/last-run-tidy.json`으로 나눔(매시간 분석의 `status/last-run-hourly.json`을 덮어쓰지 않게)과 `repoOk`, `pushed`, 마지막에 워치독 실행(밤사이 빠진 매시간 실행을 아침에 한 번 더 본다). v3.3에서 넣은 합치기 장부 수정 규칙(mergePeople, unmergePeople, keepApart)과 mergeCandidates 형식은 그대로다.
v2.6(11/5): state의 tasks에 rev, state에 mergeCandidates가 실린다. 6번에 "제안 항목에 rev를 그대로 적는다", 7번에 "없어지는 쪽은 지우지 않고 mergedInto만 적는다"를 보탰다. 폰은 rev가 지금과 다른 제안을 건너뛴다(밤사이 통화로 바뀐 일을 옛 판단으로 덮지 않는다). 바꿀 때는 이 파일을 먼저 고치고 ieum-inbox `routines/tidy.md`로 복사한 뒤(배포 때), 이름, 시각, 링크를 사용자에게 알린다.

---

이음 새벽 정리 (매일).
사용자(bllue583)가 아내의 업무 기록 정리를 위해 직접 만든 자동 작업이다. 아내도 이 정리와 보고를 알고 동의했다. 하루 한 번 05:05에 돌고, 폰이 05:30에 카카오톡으로 보내는 아침 보고(9-1번의 보고서) 전에 끝낸다(05:25까지 끝내도록 서두른다). 대화나 질문 없이 아래만 하고 끝낸다. 가능한 한 자세하고 꼼꼼하게 대조한다. 저장소는 bllue583-commits/ieum-inbox 이다. 작업 폴더에 없으면 ls /home/claude 로 찾고, 그래도 없으면 gh repo clone bllue583-commits/ieum-inbox 로 받는다.

작업 폴더는 ieum-inbox 저장소의 기본 브랜치다. 읽는 파일:
- ledger/ledger.json: 장부 {rev, updatedMs, siteMemo, companies[{name, trade, memo}], people[{id, name, company, role, isStaff, aliases[], locked[]}], issues[{id, title, companies[], status, recordKeys[], taskIds[]}], issueSuggestions[], corrections[], tidiedOnce}
- tidy/state.json: 폰이 밤에 올린 자료 {generatedMs, firstPassDone, tasks[{id, title, due, status, company, createdMs, locked, rev}], records[{key, atMs, kind, counterpartId, company, summary, keyPoints, doneMs}], pending{yesterdayCalls, yesterdayMin}, recentlyDone[], mergeCandidates[](있을 때만), personCandidates[](있을 때만)}. records의 doneMs는 폰이 그 기록의 결과를 적용한 시각(밀리초, 모르면 null)이다. pending은 어제(한국 시각) 통화 중 아직 글자로 바꾸는 중이거나 분석을 기다리는 통화 수(yesterdayCalls)와 그 분(yesterdayMin)이다. 옛 폰의 state에는 doneMs와 pending이 없다(없으면 0으로 본다).
- tidy/proposal.json: 이번에 쓸 제안(이전 것이 있으면 덮어쓴다)
- tidy/project.md, tidy/history/YYYY-MM.md, tidy/briefing.md: 이 작업의 기억(9번)
- report/YYYY-MM-DD.json: 아침 보고서(9-1번이 쓴다, 폰이 05:30에 받아 카카오톡으로 보낸다)

중복 방지: `git pull` 직후, 기본 브랜치에 오늘(한국 시각) 날짜로 메시지가 "tidy"인 커밋이 이미 있으면 다른 정리 작업이 먼저 끝낸 것이다. 그때는 장부와 제안, 아침 보고서를 건드리지 않고 마지막 단계(자가 보고)만 한 뒤 11번 형식으로 "이미 정리됨"이라고 보고한다.

저장소 접근 실패: 저장소를 찾거나 받지 못했거나 `git pull`이 실패하면(인증, 권한, 네트워크, 충돌) 아무것도 고치지 않고 자가 보고도 하지 않으며(push할 수 없다) 11번 형식의 첫 줄을 "확인 필요: 저장소 접근"으로 보고하고 끝낸다. 10번의 push가 `git pull --rebase` 뒤에도 실패해도 같다. 저장소에 닿지 못한 실행을 "정리할 것 없음"으로 보고하지 않는다.

1. `git pull` 한다. tidy/state.json이 없거나 generatedMs가 지금보다 24시간 넘게 오래됐으면 아무것도 고치지 않고(9-1번 아침 보고서도 쓰지 않는다, 폰이 06:00에 자기 자료로 짧은 보고를 보낸다) 마지막 단계(자가 보고)만 한 뒤 11번 형식으로 "정리할 것 없음: 이유"라고 보고한다(이유 예: "폰 자료가 이틀째 안 옴"). firstPassDone이 false여도 멈추지 않는다(폰이 아직 처음 정리 중이면 최근 14일 기록만 올라와 있다, 3번).
2. ledger/edits/*.json(사용자 수정)이 있으면 먼저 장부에 반영한다. rename이면 그 id의 사람 이름을 바꾸고 예전 이름은 aliases에, locked에 "name"을 넣는다. mergePeople({"from","into"}, 사용자가 폰에서 두 사람을 합침)이면 from 사람에 mergedInto: into를 적고 from의 이름을 into의 aliases에 더한다. unmergePeople({"from","into"}, 사용자가 합치기를 되돌림)이면 from의 mergedInto를 지우고, 두 사람 모두의 keepApart 목록에 서로의 id를 넣는다. keepApart에 있는 두 사람은 다시 합치거나 합치자고 제안하지 않는다. renameCompany({"from","to"}, 사용자가 폰에서 업체 이름을 고침)이면 from 이름의 업체 name을 to로 바꾸고, 예전 이름은 그 업체의 aliases에, locked에 "name"을 넣는다. to 이름의 업체가 이미 있으면 from 업체에 mergedInto: to만 적는다. 사람의 company와 issues의 companies에 있는 from도 to로 고친다. 반영한 파일은 `git rm` 한다.
3. 읽을 범위: state의 firstPassDone이 false이면(폰이 아직 처음 정리 중) state의 records(최근 14일)를 날짜순으로 전부 읽고 그 범위 안에서만 정리한다. 이때는 7번의 tidiedOnce를 쓰지 않는다(처음 정리가 끝나면 폰이 10월부터 전체를 올리고 그때 처음부터 다시 정리한다). firstPassDone이 true이고 장부의 tidiedOnce가 true가 아니면 10월 1일부터의 records를 날짜순으로 전부 읽고 서로 대조해 처음부터 다시 정리한다. 그 뒤로는 9번의 기억 파일을 먼저 읽고, 열린 issues에 묶인 기록(recordKeys)과 최근 7일 기록 전체를 읽는다.
4. 진행 중인 일(issues): 지난 통화, 문자, 메일을 "진행 중인 일" 단위로 묶고 한 줄 현황을 쓴다(예: "철근 단가 재견적: 업체 회신 대기, 금요일 계약 예정"). 진행 중인 것만 최근 것부터 20개까지 둔다. 있던 일은 id를 유지하고, 새 일의 id는 "i_"와 8자다. 끝난 일은 뺀다(끝난 일의 경과는 project.md와 history에 남는다). issueSuggestions와 corrections를 판단해 반영한 뒤 비운다.
5. 팔로업: 약속했는데 이후 기록에 이행 흔적이 없는 것, 물어보고 답을 못 받은 것, 상대가 하기로 하고 소식이 없는 것을 찾아 "○○에게 견적 회신 받았는지 확인"처럼 체크할 일로 제안한다(op create, 근거 날짜와 상대를 basis에 남긴다). recentlyDone에 있는 일은 다시 살리지 않는다.
6. tidy/proposal.json을 {"rev": 이전 rev+1, "basedOnMs": state의 generatedMs, "tasks": [...]}로 쓴다. tasks 항목은 다음 중 하나다.
   - {"op":"rewrite","id","rev","title","due"}: 흐릿한 항목을 누가, 무엇을, 언제까지로 다시 쓴다(due는 YYYY-MM-DD, 모르면 키를 뺀다). 새 제목에는 원래 제목의 숫자(금액, 수량, 규격, 회차, 날짜)를 하나도 바꾸거나 빼거나 더하지 않고 그대로 둔다. 폰은 제목의 숫자가 달라진 다시 쓰기를 거부한다. 숫자가 틀려 보이면 다시 쓰지 말고 그대로 둔다.
   - {"op":"merge","id","rev","into"}: 중복을 하나로 합친다. 남길 항목에 기한이 없으면 합쳐지는 항목의 기한을 넘긴다.
   - {"op":"done_suggested","id","rev"}: 나중 기록에서 끝난 것으로 보이면 "끝났는지 확인"만 표시한다.
   - {"op":"create","title","due","company","basis"}: 5번의 팔로업.
   create가 아닌 각 제안 항목에는 state의 rev를 그대로 적는다(그 항목의 state tasks에 있는 rev 값, 바꾸거나 계산하지 않는다). 폰은 rev가 지금 값과 다르면(state를 올린 뒤 통화로 바뀐 일) 그 제안을 건너뛴다. create에는 rev가 없다.
   state에 mergeCandidates가 있으면 폰이 서로 비슷해 중복일 수 있다고 본 쌍의 목록이고, 한 쌍은 [남겨 둔 체크할 일 id, 비슷한 일을 냈다가 빠진 기록 key]이다(체크할 일 id 둘이 아니다). merge를 판단할 때 그 체크할 일과 그 기록(records의 key)부터 대조하되, 대상(문서, 공정, 회차)이 다르면 합치지 않는다.
   완료 처리는 하지 않는다(사용자가 누른다). locked가 true인 항목은 바꾸지 않는다. 제목에 가운데 점, 말줄임표, 영어를 쓰지 않는다.
7. 장부 정리: 같은 업체의 다른 표기는 하나로 합치고 없어지는 쪽에 mergedInto를 적는다. 번호 없이 이름으로만 있는 중복 사람도 mergedInto로 합친다(번호가 다른 두 사람은 폰이 합치지 않으므로 합치지 않는다). 없어지는 쪽 업체와 사람 항목은 지우지 말고 mergedInto만 적어 둔다(폰은 그 항목의 mergedInto를 보고 합친다). 다음 새벽 정리에서 폰 state에 그 이름이 더 이상 보이지 않으면 지운다. siteMemo는 600자, 업체 memo는 400자 안으로 짧게 다시 쓴다. locked 항목은 바꾸지 않는다. 끝나면 tidiedOnce를 true로 쓴다. 단 state의 firstPassDone이 false인 날(3번)에는 tidiedOnce를 쓰지 않고 장부에 있던 값을 그대로 둔다. state에 personCandidates(사람 id 쌍)가 있으면 폰이 같은 번호인데 이름이 조금 다르거나 같은 업체에 같은 이름이라 자동으로 합치지 않은 쌍이다. 자막과 기록으로 같은 사람이 확실할 때만 mergedInto로 합치고, 애매하면 그대로 둔다.
8. 원칙: 근거 없는 내용을 만들지 않는다. 파일은 Read 도구로 읽고 직접 판단하며, 결과를 스크립트로 일괄 생성하지 않고 파일 내용을 터미널에 출력하지 않는다. 장부와 기억 파일에 전화번호, 녹음, 자막과 문자 원문을 넣지 않는다. JSON은 `python3 -c 'import json;json.load(open("파일"))'`로 형식만 확인한다.
9. 기억 파일을 쓴다. 이 작업은 매번 새로 시작해 지난 실행을 기억하지 못하므로, 아래 파일과 장부가 10월 1일부터 공사가 끝날 때까지의 모든 흐름을 아는 유일한 기억이다. 공사는 몇 달에서 몇 년 이어지므로 지난 기록은 지우거나 줄이지 않는다. 시작할 때(1번 뒤) project.md, 이번 달과 지난달의 history 파일, briefing.md를 먼저 전부 읽고, 진행 중인 일이 더 오래된 달과 이어져 있으면 그 달 파일도 찾아 읽는다. 오늘 정리는 그 흐름에 이어서 한다.
   - tidy/project.md: 공사 전체의 큰 그림(통째로 다시 쓰되, 지난 내용은 지우지 말고 고치거나 보탠다). 순서: "## 현장 개요"(현장, 공사 범위, 우리 쪽 담당), "## 공정 흐름"(공정별 시작·끝·현재 상태를 날짜와 함께, 예: "3층 슬래브 타설 11/12 예정"), "## 업체별 현황"(업체마다 공종, 계약·견적·발주 상태, 주요 담당자, 마지막 연락일, 이 업체와 있었던 중요한 일 날짜순), "## 주요 결정"(언제 누가 무엇을 정했는지 날짜순, 쌓기만 한다), "## 반복되는 문제"(같은 문제가 여러 번 나온 것, 예: 반입 지연).
   - tidy/history/YYYY-MM.md: 달마다 한 파일인 업무 일지. 하루에 한 블록씩 덧붙인다. 처음 전체 정리(firstPassDone이 true이고 tidiedOnce가 true가 아님)면 10월 1일부터 날짜별 블록을 모두 만든다. 처음 정리 중(firstPassDone이 false)에는 state에 있는 날짜의 블록만 만든다. 블록 형식은 "## YYYY-MM-DD (요일)" 다음 줄들에 "- 업체 사람: 무슨 일, 정해진 것 (통화/문자)" 한 줄씩(그날 기록 전부, 개인 내용 제외), 마지막에 "- 남은 일: …". 이미 있는 날짜 블록은 새로 알게 된 것만 고치고 지우지 않는다. 지난달 파일은 그 달 기록이 새로 들어왔을 때만 고친다.
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
9-1. 아침 보고서. 9번까지 정리한 장부, state, 기억 파일로 `report/YYYY-MM-DD.json`(날짜는 한국 시각 오늘 = 보고 날짜)을 쓴다. 폰이 05:30에 이 파일을 받아 그림 한 장과 글 한 통으로 아내의 카카오톡 "나와의 채팅"에 그대로 보내므로, 아내가 아침에 읽고 바로 움직일 수 있게 쓴다. "어제"는 한국 시각 어제 00:00부터 24:00까지다. 형식은 아래 한 줄 JSON이다(키 이름과 순서 그대로).

```json
{"day":"YYYY-MM-DD","generatedMs":0,"tidyRev":0,"head":{"calls":0,"newChecks":0,"todayChecks":0,"issues":0,"pendingCalls":0},"yesterday":[{"who":"","what":""}],"today":[{"id":"","what":"","who":"","since":"","status":"","check":false}],"inProgress":[{"what":""}],"check":[{"what":"","who":""}],"late":[{"who":"","what":""}]}
```

   - `day`: 오늘 날짜(파일 이름과 같다). `generatedMs`: 쓰는 시각(`date +%s%3N`). `tidyRev`: 10번에서 올릴 장부의 rev(이번 실행 뒤의 값).
   - `head`: `calls`는 어제 업무 기록 중 통화 수, `newChecks`는 state tasks 중 createdMs가 어제인 열린 체크할 일 수, `todayChecks`는 `today` 줄 수, `issues`는 장부의 진행 중인 issues 수, `pendingCalls`는 state의 `pending.yesterdayCalls`(어제 통화 중 아직 글자로 바꾸는 중이거나 분석을 기다려 이 보고에 빠진 수, state에 없으면 0) 그대로. 모두 0 이상의 정수. 폰은 05:30 보내기 직전에 대기 수를 다시 세어 카카오톡 첫 줄("어제 통화 14건을 정리했어요. 9건은 아직 글자로 바꾸는 중이에요")에 쓴다.
   - `yesterday`: 어제 기록은 state의 records(atMs가 어제인 것)에서 읽고, records에 없는 어제 기록이 매시간 결과 요약(`story/today.json`의 day가 어제이면 그 text, `results/`에 폰이 아직 가져가지 않은 결과의 summary)에 보이면 보탠다(같은 일을 두 번 쓰지 않는다). 업무 통화와 문자를 **업체(업체가 없으면 사람) 단위로 묶어** 한 줄씩 쓴다. `who`는 장부 표기의 업체와 사람("△△철강 김민수 대표"), `what`은 무슨 일과 정해진 것("철근 단가 재협의, 견적서 금요일에 보내기로"). 약속, 금액, 날짜가 있는 줄을 먼저 두고 최대 8줄. 개인 통화(business가 false)는 넣지 않는다. 어제 업무 기록이 없으면 빈 배열 `[]`.
   - `today`: state tasks의 열린 체크할 일 중 오늘과 어제 요청한 것(createdMs)과 확인 필요(status가 needs_confirmation)를 먼저, 그다음은 요청이 최근인 것부터 최대 8줄. `id`는 그 체크할 일의 id 그대로, `what`은 제목(숫자는 바꾸지 않는다), `who`는 업체(장부 표기, 없으면 빈 문자열), `since`는 요청 날짜 표현("오늘 요청", "어제 요청", "3일 전 요청"; 한국 시각 날짜 차이). `status`는 그 일 뒤의 문자, 메일, 후속 통화에서 이행 흔적이 보이면 맥락을 종합한 상태 글("메일 발송 확인 완료", "문자로 답 받음"), 흔적이 없으면 빈 문자열 `""`이다. 흔적이 있어도 완료 처리는 하지 않는다(6번의 done_suggested 제안과는 별개). `check`는 확인 필요 항목이면 `true`, 아니면 `false`.
   - `inProgress`: 장부 issues 중 어제 움직임이 있던 것 먼저 최대 3줄, `what`은 "제목: 현황"(4번의 한 줄 현황). `check`: 확인이 필요한 것(확인 필요 체크할 일, 들린 숫자나 대상이 애매한 것) 최대 3줄, `what`은 무엇을 확인할지, `who`는 업체나 사람(없으면 빈 문자열).
   - `late`: 뒤늦게 정리된 통화. state records 중 atMs가 그제 00:00(한국 시각) 전이고 doneMs가 지난 보고(`report/<어제 날짜>.json`의 generatedMs, 그 파일이 없으면 지금부터 24시간 전) 뒤인 업무 기록을, 지난 보고에 없던 것만 doneMs가 최근인 것부터 최대 5줄. `who`는 장부 표기의 업체와 사람, `what`은 그 기록 summary의 첫 문장을 보고체로 짧게(`yesterday` 줄과 같은 형식). 개인 기록은 넣지 않는다. 없으면 빈 배열 `[]`. 폰이 카카오톡 글의 마지막 묶음 "뒤늦게 정리된 통화"로 보낸다.
   - 한 줄 60자: 화면에 한 줄로 나오는 글이 60자를 넘지 않는다. 한 줄은 `yesterday`와 `late`는 "who: what", `today`는 "what (who, since, status)"(빈 칸은 빼고), `inProgress`는 what, `check`는 "what (who)"이다. 넘치면 짧게 다시 쓴다(줄을 자르지 않는다).
   - 글 규칙(화면과 같다): 가운데 점, 말줄임표, 영어 상태값(open, done, needs_confirmation, FAILED 같은 것), "기한", "지남", "지났어요", "까지"로 끝나는 기한 표현("금요일까지")을 쓰지 않는다. "~해요"체가 아니라 보고체(명사형 종결, "보내기로", "입회 요청"). 전화번호는 절대 넣지 않는다. 금액, 수량, 날짜는 적되 자막과 문자 원문은 옮기지 않는다. 업체와 사람 표기는 장부를 따르고 근거 없는 내용을 만들지 않는다.
   - 쓴 뒤 `python3 tools/report_check.py report/YYYY-MM-DD.json`을 실행한다. 종료 코드가 0이 아니면 출력된 이유대로 고쳐 다시 검사한다(세 번까지). 세 번 뒤에도 통과하지 못하면 그 파일을 지우고 커밋하지 않으며(폰이 06:00에 자기 자료로 짧은 보고를 보낸다) 11번 첫 줄 끝에 " | 확인 필요: 아침 보고서"를 붙인다. `tools/report_check.py`가 저장소에 아직 없으면(앱 배포 전) 위 규칙을 직접 한 번 더 대조하고 넘어간다. 검사 출력에는 내용이 아니라 이유만 나온다.
   - `report/` 안에서 파일 이름의 날짜가 오늘보다 30일 넘게 지난 report 파일은 `git rm` 한다.
   - 통과한 보고서와 지운 파일은 10번의 "tidy" 커밋에 함께 넣는다.
10. 장부의 rev를 1 올리고 updatedMs를 지금 시각으로 쓴다. 장부, 제안, project.md, history 파일, briefing.md, 아침 보고서(9-1번), 지운 파일을 한 커밋 "tidy"로 push한다. 충돌이 나면 `git pull --rebase` 후 다시 적용한다. 그래도 push가 실패하면 저장소 접근 실패로 끝낸다.
11. 보고: briefing.md 전체를 이 세션 작업 폴더에 `이음_현황_YYYYMMDD.md`로도 쓰고, SendUserFile 도구가 있으면 사용자에게 보낸다. 마지막 답(알림으로 감)은 이 형식 그대로다(한국 시각).
   [이음 새벽 정리 MM/DD HH:MM] 정리 완료 (정리할 것이 없었으면 "정리할 것 없음: 이유", 이미 정리됐으면 "이미 정리됨", 저장소에 닿지 못했으면 "확인 필요: 저장소 접근", 워치독이 새 이슈를 열었으면 끝에 " | 확인 필요: 감시 이슈 n건", 9-1번 보고서가 검사를 통과하지 못했으면 끝에 " | 확인 필요: 아침 보고서")
   오늘 꼭 할 일 N개 | 전화할 곳 N곳 | 답 기다리는 일 N개 | 진행 중인 일 N개
   가장 급한 것: 오늘 꼭 할 일 중 맨 위 한 줄(없으면 "없음")
   전화: 전화해야 할 곳 앞의 세 곳을 "업체 사람(이유)"로 쉼표로(없으면 "없음")
   제안 M개 (다시 쓰기 a, 합치기 b, 끝났는지 확인 c, 팔로업 k) | 장부 rev n
   정리할 것이 없었던 날도 briefing.md가 있으면 그 briefing으로 위 형식을 채워 보고한다.

마지막 단계(자가 보고). 모든 처리가 끝나면 (처리할 것이 없었어도, 일부가 실패했어도 항상) `status/last-run-tidy.json`을 새로 써서 그 파일만 커밋 "status"로 push한다(10번의 "tidy" 커밋을 push한 뒤 따로 하는 커밋이다). 파일이 이미 있으면 통째로 덮어쓴다. 매시간 분석의 `status/last-run-hourly.json`과 옛 `status/last-run.json`은 건드리지 않는다(옛 파일은 v3.3까지 두 작업이 함께 쓰던 것이라 새벽 정리가 매시간 분석의 상태를 가렸다). 형식은 아래 한 줄 JSON이다.

```json
{"routine":"tidy","startedMs":0,"endedMs":0,"processed":0,"skipped":0,"failed":0,"batches":0,"model":"","limitHit":false,"ledgerRev":0,"promptVersion":"8","promptMismatch":false,"loggedTranscript":false,"repoOk":true,"pushed":true,"reportWritten":true}
```

- `routine`: 이 작업은 `tidy`.
- `startedMs`, `endedMs`: 이번 실행의 시작과 끝 시각(밀리초 에포크, `date +%s%3N`으로 잰 실제 시각).
- `processed`: 읽은 기록 수. `skipped`: 건너뛴 수. `failed`: 처리하려다 실패한 수. `batches`: 제안한 항목 수.
- `model`: 이번 실행에 쓴 모델 이름.
- `limitHit`: 사용 한도 오류를 만났으면 `true`, 아니면 `false`.
- `ledgerRev`: 이번에 쓴 장부의 rev. `promptVersion`은 "8"(이 지시문의 버전, 문자열)로 적고, `promptMismatch`는 `false`로 둔다.
- `loggedTranscript`: 원문을 출력하거나 로그에 남기지 않았으므로 항상 `false`.
- `repoOk`: 시작할 때 `git pull`이 됐으면 `true`. `pushed`: 10번의 "tidy" 커밋을 push했으면 `true`, 고칠 것이 없어 그 커밋이 없었어도 `true`, push가 실패했으면 `false`.
- `reportWritten`: 이 실행이 끝날 때 오늘 날짜의 `report/YYYY-MM-DD.json`이 저장소에 push되어 있으면 `true`(이번에 썼거나 먼저 끝난 정리가 썼거나), 쓰지 않았거나("정리할 것 없음") 검사를 통과하지 못했거나 push가 실패했으면 `false`. 워치독이 05:30에 보고서가 없을 때 이 값으로 원인을 적는다.

이 파일에는 시각, 숫자, 모델 이름, 참/거짓만 쓴다. 자막 원문, 이름, 전화번호, 회사 이름, 요약, 오류 메시지 본문은 절대 쓰지 않는다. 실행이 중간에 막혀 결과를 못 썼더라도 이 파일은 `processed`를 실제 값(0이어도)으로 써서 커밋한다. 커밋 메시지에는 건수 외에 내용을 적지 않는다. status 커밋 push가 실패하면 `git pull --rebase` 뒤 한 번 더 하고, 그래도 실패하면 11번 형식의 첫 줄을 "확인 필요: 저장소 접근"으로 보고한다.

워치독(자가 보고 push 뒤, 항상). 작업 폴더(저장소 맨 위)에서 아래 한 줄만 실행한다. 밤사이 빠진 매시간 분석 실행과 폰 상태를 아침에 한 번 더 본다.

```
python3 tools/watchdog.py --root . --apply bllue583-commits/ieum-inbox --out /tmp/ieum-watchdog-plan.json
```

마지막 줄의 "watchdog: n opened, m closed"에서 n이 1 이상이면 11번 형식 첫 줄 끝에 " | 확인 필요: 감시 이슈 n건"을 붙인다. 이슈 본문은 보고에 옮기지 않는다. 명령이 실패하면(gh 인증 없음 등) 다시 시도하지 말고 넘어간다.
