# 평가 도구 (11/10 도구 토론에서 채택)

채점표([docs/SCORECARD.md](../../docs/SCORECARD.md)) 4·5번 항목과 보안·코드 건강을 재는 도구다. 정답지와 실제 자막은 **이 저장소에 넣지 않는다**(폰이나 관리자 PC에서 돌리고 숫자만 우편함 `status/gold.json`으로).

| 무엇 | 도구 | 버린 대안 |
| --- | --- | --- |
| 글자 오류율 | `ko_cer.py` = jiwer 4.0.0 + 한국어 숫자 정규화("삼십만 원" = "30만원" = 300000원, "두 장" = 2장) + 문장부호·띄어쓰기 무시 + 짝 부트스트랩 | evaluate(jiwer를 감쌀 뿐), sclite(직접 빌드, 한국어 정규화 없음), g2pk2(방향이 반대이고 "30만원"을 틀리게 읽음) |
| 약속 추출 정밀도·재현율 | `promise_eval.py` = 같은 사람 + 내용 유사도 0.6 이상 + 숫자 집합 일치로 1:1 짝짓기, 숫자만 다른 것은 따로 셈. v4.0(r20-3)부터 표준 라이브러리만 씀(유사도는 rapidfuzz partial ratio와 같은 정의를 difflib로) | promptfoo(집합 짝짓기는 결국 직접 코드), deepeval(LLM 심판이라 재현성 낮음) |
| 비밀 스캔 | gitleaks 8.28.0 (`gitleaks git <저장소> --redact --log-opts=--all`) — 11/10 전체 이력 0건 | trufflehog(실제 서비스에 접속해 검증) |
| 소스 보안 | mobsfscan 1.1.0 + semgrep — 11/10 ERROR 1(StrandHogg 2.0, targetSdk 36이라 낮음), 나머지는 오탐·의도 | semgrep 단독 |
| 복잡도 | detekt 1.23.8, lizard 1.25.1 — 11/10 복잡도 15 넘는 함수 91개 | |

```sh
python3 -m venv venv && venv/bin/pip install jiwer==4.0.0 rapidfuzz
venv/bin/python tools/eval/ko_cer.py tools/eval/sample/stt_sample.jsonl        # 정규화 전후 CER, 짝 부트스트랩
python3 -I tools/eval/promise_eval.py tools/eval/sample/gold.json tools/eval/sample/pred.json   # 의존성 없음
```

`sample/`은 지어낸 문장이다. 정규화를 안 하면 엔진 순위가 뒤집힌다(표본: 정규화 전 A 17.5%·B 10.2%, 정규화 뒤 A 0%·B 1.5%).

자막 정답은 `{"id", "ref", "a", "b"}` 줄(JSONL).

## 약속 정답지 형식 (v4.0 r20-3에서 확정 — 바꾸면 지난 숫자와 비교할 수 없다)

파일 하나 = 통화들의 JSON 목록. 정답지와 채점 대상(폰 결과를 바꾼 것)이 같은 형식이다.

```json
[{"call_id": "r_0123abcd…", "promises": [
  {"who": "me", "what": "계약금 30만원 송금", "evidence": "계약금 30만원은 금요일까지 보내드릴게요", "numbers": ["30만원"], "due": "2026-11-20"}]}]
```

| 키 | 뜻 |
| --- | --- |
| `call_id` | 폰이 우편함에 올린 요청의 `id`(= `recordKey`, `r_`로 시작). 매시간 분석 결과 파일의 `id`(묶음이면 `results[].id`)와 같아야 짝이 된다 |
| `promises[]` | 그 통화에서 누가 무엇을 하기로 한 것 하나씩. 없으면 빈 목록(약속 없는 통화도 채점 대상이다) |
| `who` | `me`(아내가 할 일) 또는 `them`(상대가 할 일). 채점 대상의 `unknown`은 어느 쪽과도 짝이 안 된다 |
| `what` | 할 일을 체크할 일 제목처럼 짧게. 숫자는 들은 그대로 적는다 |
| `evidence` | 자막 인용(판정용, 채점에는 안 씀). **정답지는 저장소에 넣지 않는다**(관리자 PC나 우편함 `gold/`에만) |
| `numbers` | 그 약속의 금액·수량·층·호·시각 등 단위가 붙은 숫자(날짜 제외). `ko_cer.normalize`로 맞추므로 "삼십만 원" = "30만원" |
| `due` | 기한 `YYYY-MM-DD` 또는 없음(null). `dueExact`로 따로 보고, 통과 판정에는 안 씀 |

규칙: 정답지에 있는 통화만 채점한다. 정답지 통화인데 채점 대상에 그 통화가 아예 없으면(결과를 못 모음) 빼고 `missingCalls`로 센다. 같은 사람 + 내용 유사도 0.6 이상이면 내용이 맞은 것이고, 그중 숫자 집합까지 같아야 맞힌 약속(TP)이다. 숫자만 틀린 것은 `numberMismatch`로 세고 정밀도와 재현율 양쪽에서 틀린 것으로 친다. `numberExact` = 내용이 맞은 짝 중 정답지에 숫자가 있는 것들에서 (둘 다 있는 숫자 수 / 어느 쪽에든 있는 숫자 수).

```sh
# 우편함 결과 폴더(매시간 분석의 results/*.json, 폰이 가져가기 전에 모은 사본)에서: create·update 체크할 일이 약속
python3 -I tools/eval/promise_eval.py gold.json --results 결과폴더 --write gold-commit.json
```

`--write`가 쓰는 파일이 우편함 `status/gold-commit.json`의 형식이다: `commitRecall`, `commitPrecision`, `numberExact`, `commitCalls`, `commitNumbers`(채점기 `tools/inbox/score.py` 항목 4가 읽는 키, 기준은 `scorecard.json`), 그리고 참고로 `tp`, `fp`, `fn`, `numberMismatch`, `unknownOwner`, `dueExact`, `f1`, `missingCalls`, `goldCalls`, `atMs`, `tool`. 숫자만 들어 있으므로 우편함에 올려도 된다(자막 쪽 `status/gold-transcript.json`은 "골든 측정" 화면이 따로 올린다 — 두 파일은 서로 덮어쓰지 않는다). 시험: `python3 -m unittest tools/test_promise_eval.py`.
