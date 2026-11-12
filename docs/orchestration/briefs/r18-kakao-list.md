# r18: 카톡 아침 보고를 리스트 템플릿으로 (사용자 11/13 결정)

브랜치 `feature/r18-kakao-list`(작업 나무 `/home/claude/wt/klist`, 기준 63ff2b8 = v3.7 빌드 1053). 보고는 한국어.

## 왜
사용자가 카톡 아침 보고를 직접 설계했다. 글 템플릿은 말풍선을 누르면 이음으로 넘어가고(카카오 메시지 API는 200자 템플릿뿐이라 일반 채팅의 "전체보기"가 없다), 그림은 눌러야 다 보인다. 그래서 **리스트 템플릿**으로 간다.

| 구역 | 사용자 결정 |
| --- | --- |
| A 머리 | 오늘 날짜(요일) |
| B 목록 | 챙길 일 중 가장 중요한 top 10, 말풍선 2개(5개씩), 최대 10개 |
| C 항목 | 제목 = 일 제목, 본문 = 요청일 |
| D 썸네일 | 분류 썸네일(아래 5종, 이미 만들어 둠) |
| E 버튼 | [웹으로 보기](그 보고의 자세한 HTML) / [앱으로 보기](이음) |

## 분류 5종 (사용자 11/13 확정: 긴급과 대기는 뺀다. 썸네일 = "이 일에 내가 뭘 해야 하나" 한 가지 기준, 위에서부터 먼저 보여 줌)
| 부호(tag) | 글자 | 붙는 기준 | 그림 |
| --- | --- | --- | --- |
| `important` | 중요 | 금액, 계약, 안전, 공정(일정)이 걸린 일, 그리고 기한(due)이 오늘이거나 지난 일, 통화에서 "오늘 중", "급하다"고 한 일(예전 긴급을 여기로 합침) | `kakao_tag_important.png` |
| `confirm` | 확인 | 숫자나 대상이 애매해 되물어야 하는 일(status needs_confirmation, certainty tentative) | `kakao_tag_confirm.png` |
| `request` | 요청 | 그 밖의 보통 일(상대가 하기로 한 일도 여기). **근거가 없으면 이것(기본값)** | `kakao_tag_request.png` |
| `regular` | 정기 | 매주, 매월 반복되는 보고, 점검, 정산 | `kakao_tag_regular.png` |
| `optional` | 선택 | 제안, 개선처럼 하면 좋지만 안 해도 당장 문제없는 일 | `kakao_tag_optional.png` |

- "중요"는 근거(금액, 계약, 안전, 공정, 기한, 통화의 말)가 있을 때만. 전부 중요면 아무것도 중요해 보이지 않는다.
- 그림은 `android/app/src/main/res/drawable-nodpi/kakao_tag_*.png`(400×400, 이미 있음, 고치지 말 것). `kakao_tag_urgent.png`, `kakao_tag_waiting.png`는 지운다.
- 대기(owner "them")가 없어졌으므로 state tasks에 `owner`를 더하지 않는다(폰의 tidyState는 건드리지 않는다).

## 할 일
1. **새벽 정리 지시문(`docs/routines/tidy.md`, 9-1번 아침 보고서):** `today` 항목에 `"tag"`를 더한다(위 5개 부호 중 하나, 위 표의 기준을 지시문에 그대로 적는다). `today`는 최대 8줄에서 **최대 10줄**로, 순서는 tag 순서(important, confirm, request, regular, optional) 먼저, 같은 tag 안에서는 지금 규칙(오늘·어제 요청 먼저, 최근 요청부터). (11/13 5종 결정으로 state tasks의 `owner` 추가는 하지 않는다.) 지시문 버전을 올려야 하는지는 `RoutineDocTest`와 `tools/inbox/watchdog.py`(tidyVersion 비교), `version_fields` 규칙을 읽고 정한다(올린다면 9, 관련 시험·문서 전부). `tools/report_check.py`(보고서 검사)가 새 키와 10줄을 받게 고치고 시험도. 옛 폰(1053 이하)이 새 보고서를 받아도 문제없는지(모르는 키 무시, 8개만 읽음) 확인.
2. **폰 구성(`pipeline/report/KakaoReport.kt`):** 보고서 JSON의 `today`에서 항목 {title=what, desc=요청일(since) + (who가 있으면 " · who"), tag}를 만든다(모든 글은 기존 `clean`·번호 가리기를 거친다). tag가 없거나 모르는 값이면 `check==true` → confirm, 아니면 request. tag 순서로 안정 정렬 후 top N.
   - 메시지 종류 `Message.ListCard(header, items, link, webUrl)` 추가.
   - 머리: 첫 말풍선 `"11/13(금) 챙길 일 n개"`(n은 head.todayChecks), 둘째 `"11/13(금) 챙길 일 이어서"`(보여 주지 못한 것이 있으면 `" · 외 m개"`).
   - **기본 리스트 템플릿은 항목이 2~3개뿐이다**(카카오 문서: contents 최소 2, 최대 3). 5개짜리는 사용자가 카카오 개발자 콘솔에서 만든 **사용자 정의 템플릿**(아이템 최대 5)이어야 한다. 그래서:
     - 사용자 정의 템플릿 ID가 있으면(`BuildConfig.KAKAO_LIST_TEMPLATE_ID`, 기본 0 = 없음; gradle 속성/CI 변수 `KAKAO_LIST_TEMPLATE_ID`로 넣는 방식은 기존 `KAKAO_APP_KEY`를 따라 한다): top 10, 말풍선 2개. 항목이 정확히 5개인 말풍선만 사용자 정의 템플릿(`TalkApiClient.sendCustomMemo(templateId, args)`)으로, 나머지는 기본 리스트(2~3개)로. 나누기: 10→5+5, 9→5+3(머리에 "외 1개"), 8→5+3, 7→5+2, 6→3+3, 5→5, 4→2+2, 3→3, 2→2. 인자 이름: `H`(머리), `T1`~`T5`(제목), `D1`~`D5`(본문), `I1`~`I5`(썸네일 URL), `WEB`(웹 보기 경로). 사용자가 콘솔에서 만들 템플릿의 정확한 설정 순서를 `docs/OPERATIONS.md`에 적는다(메시지 템플릿 도구 경로, 리스트형, 아이템 5개, 각 인자, 버튼 두 개와 링크 설정). 공식 문서(https://developers.kakao.com/docs/latest/ko/message-template/custom)를 WebFetch로 확인하고 추측으로 쓰지 말 것 — 이미지나 링크를 인자로 못 넣는다면 그 사실과 대안을 보고.
     - ID가 없으면(지금): 기본 리스트로 top 6, 말풍선 2개(3+3, 5→3+2, 4→2+2, 3→3, 2→2).
   - 챙길 일이 1개 이하이면 리스트를 못 만든다(최소 2): 지금의 글 보고(`chat`, 최대 3통)로.
   - 리스트 전송이 실패하면(토큰 오류 제외) 글 보고(`chat`)로 대신 보낸다. 첫 말풍선이 나간 뒤 둘째가 실패하면 둘째만 한 번 더, 그래도 안 되면 오류로 남긴다(같은 내용을 두 번 보내지 않게).
   - 설정 키 `kakaoPicture`(그림 보고)와 기존 글 보고 경로는 남긴다. 폰이 만든 06:00 보고(SOURCE_PHONE)도 today가 2개 이상이면 리스트로.
3. **썸네일 올리기:** 카카오 리스트의 `image_url`은 URL이어야 한다. 기존 `Sender.uploadImage`(카카오 이미지 업로드)를 써서 분류 그림을 올리고, URL을 설정에 분류별로 기억한다(`{tag:{url,atMs}}`, 10일 지나면 다시 올림 — 카카오 업로드 이미지 보관 기간을 공식 문서로 확인해 그보다 짧게). 그날 필요한 분류만 올린다. 올리기가 실패하면 그 항목은 그림 없이 보낸다(보고가 그림 때문에 안 가면 안 된다).
4. **버튼(E):** [앱으로 보기](기존 `TASKS_LINK`), [웹으로 보기].
   - 웹 보기: 보고 내용은 개인 정보라 서버에 올리지 않는다. **정적 보기 페이지 + 내용은 URL 조각(#) 안**으로 한다: `docs/report/index.html`(공개 앱 저장소의 GitHub Pages로 서비스될 파일)을 만들고, 폰이 보고서 JSON(가린 글)을 deflate + base64url로 `#d=`에 넣은 URL을 만든다. 페이지는 조각을 풀어(브라우저 `DecompressionStream`, 없으면 안내 문구) 보고 전체(오늘 챙길 일 전부와 분류, 진행 중, 확인 필요, 어제 정리, 뒤늦게 정리된 통화)를 읽기 좋게 그린다. 외부 스크립트·글꼴·분석 도구를 부르지 않는다(내용이 밖으로 나가면 안 됨). `<meta name="robots" content="noindex">`, `referrer` 정책 no-referrer. 휴대폰 폭 기준, 밝은/어두운 화면 모두.
   - 웹 주소는 `BuildConfig.KAKAO_WEB_BASE`(기본 빈 문자열 = 웹 버튼 없음). 카카오는 등록된 웹 도메인만 받으므로(사용자가 콘솔 [제품 링크 관리] > [웹 도메인]에 등록해야 함), 빈 값이면 [앱으로 보기]만 붙인다. 값이 있는데 카카오가 거부하면 웹 버튼 없이 한 번 더 보낸다. **GitHub Pages를 켜거나 저장소 설정을 바꾸지 말 것**(오케스트레이터가 사용자에게 알린 뒤 한다).
   - URL 길이: 보고서가 길면 조각이 길어진다. 4,000자를 넘으면 뒤 묶음(어제 정리 → 뒤늦게)부터 줄여 넣고 페이지에 "나머지는 이음에서"를 보여 준다.
5. **하트비트(`PhoneStatus.kt`):** `kakao.style`("list"|"text"|"picture"), `kakao.cards`(리스트 말풍선 수), `kakao.listFallback`(리스트가 안 가서 글로 보낸 누계), `kakao.web`(웹 버튼을 붙였나), 분류별 건수 `kakao.tags{important:n,confirm:n,request:n,regular:n,optional:n}`(숫자만).
6. **시험:** 구성(정렬, 나누기 표 전부, 머리 문구, 기본값 tag, 1개 이하일 때 글), 전송(리스트 성공, 실패 시 글 대체, 둘째만 실패, 토큰 오류, 썸네일 올리기 실패, 캐시 재사용과 10일 뒤 다시 올림), 웹 URL 왕복(파이썬이나 JVM으로 조각을 풀어 같은 JSON이 나오는지), `RoutineDocTest`·`report_check`·워치독 시험 갱신. 기존 `KakaoReportTest`는 `setup()`에서 그림 보고를 켜 두었으니(`PICTURE=true`) 리스트 시험은 끄고 돌린다.
7. **문서:** `docs/DESIGN.md` 3.12절(리스트 보고, 분류표, 대체 순서), `docs/OPERATIONS.md`(하트비트 키, 사용자 정의 템플릿 만드는 순서, 웹 도메인 등록 순서), `docs/routines/tidy.md` 머리말 변경 기록. `docs/VERSION`과 `docs/UPDATE_NOTE.txt`는 건드리지 말 것(오케스트레이터가 정한다).

## 확인 방법
- 컴파일: `cd /home/claude/wt/klist && . /home/claude/toolchain/env.sh && flock /home/claude/gradle.lock python3 run_gradle.py --log r18.log :app:compileDebugKotlin :app:compileDebugUnitTestKotlin` (단위 시험은 로컬에서 못 돌림 — Robolectric 없음).
- 파이썬: `python3 -m unittest discover -s tools/inbox -p 'test_*.py'`, `python3 tools/check_ui_rules.py`(화면 문구 규칙, 말줄임표 금지), `python3 tools/scan_export.py`.
- ci: `git push origin feature/r18-kakao-list` 뒤 `gh api "repos/bllue583-commits/ieum-app/actions/runs?head_sha=<sha>"`에서 이름이 ci인 실행이 success일 때까지. 실패하면 `gh api repos/bllue583-commits/ieum-app/actions/jobs/<job id>/logs | grep '##\[error\]'`.
- 커밋 메시지 끝: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` 과 `Claude-Session: https://claude.ai/code/session_013kyAU6nSXKy8Jb23SkiER1`. `[skip ci]`를 넣지 말 것.

## 금지
- `feature/lean-calls`, `feature/r17-v37`에 push 금지(폰에 자동 배포된다).
- `/home/claude/ieum-inbox`의 `status/calllog.txt`, `inbox/`, `results/`, `ledger/`, `tidy/`, `story/`, `report/` 본문을 읽지 말 것.
- 비밀번호·토큰·키를 파일에 남기지 말 것. 실제 통화 내용·이름·번호를 시험 자료나 문서에 쓰지 말 것(△△철강 같은 가짜 이름만).
- 이 일과 무관한 코드(자막, 카드, 업데이트)는 건드리지 말 것.

## 보고에 넣을 것
바꾼 파일과 동작, 설계와 다르게 정한 것과 이유, 카카오 문서로 확인한 사실(링크)과 확인하지 못한 것, 새 하트비트 키, 시험과 ci 결과, 사용자가 콘솔에서 해야 할 일의 정확한 순서, 실기기에서만 확인되는 것.
