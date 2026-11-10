# 평가 도구 (11/10 도구 토론에서 채택)

채점표([docs/SCORECARD.md](../../docs/SCORECARD.md)) 4·5번 항목과 보안·코드 건강을 재는 도구다. 정답지와 실제 자막은 **이 저장소에 넣지 않는다**(폰이나 관리자 PC에서 돌리고 숫자만 우편함 `status/gold.json`으로).

| 무엇 | 도구 | 버린 대안 |
| --- | --- | --- |
| 글자 오류율 | `ko_cer.py` = jiwer 4.0.0 + 한국어 숫자 정규화("삼십만 원" = "30만원" = 300000원, "두 장" = 2장) + 문장부호·띄어쓰기 무시 + 짝 부트스트랩 | evaluate(jiwer를 감쌀 뿐), sclite(직접 빌드, 한국어 정규화 없음), g2pk2(방향이 반대이고 "30만원"을 틀리게 읽음) |
| 약속 추출 정밀도·재현율 | `promise_eval.py` = 같은 사람 + 내용 유사도 0.6 이상(rapidfuzz) + 숫자 집합 일치로 1:1 짝짓기, 숫자만 다른 것은 따로 셈 | promptfoo(집합 짝짓기는 결국 직접 코드), deepeval(LLM 심판이라 재현성 낮음) |
| 비밀 스캔 | gitleaks 8.28.0 (`gitleaks git <저장소> --redact --log-opts=--all`) — 11/10 전체 이력 0건 | trufflehog(실제 서비스에 접속해 검증) |
| 소스 보안 | mobsfscan 1.1.0 + semgrep — 11/10 ERROR 1(StrandHogg 2.0, targetSdk 36이라 낮음), 나머지는 오탐·의도 | semgrep 단독 |
| 복잡도 | detekt 1.23.8, lizard 1.25.1 — 11/10 복잡도 15 넘는 함수 91개 | |

```sh
python3 -m venv venv && venv/bin/pip install jiwer==4.0.0 rapidfuzz
venv/bin/python tools/eval/ko_cer.py tools/eval/sample/stt_sample.jsonl        # 정규화 전후 CER, 짝 부트스트랩
venv/bin/python tools/eval/promise_eval.py tools/eval/sample/gold.json tools/eval/sample/pred.json
```

`sample/`은 지어낸 문장이다. 정규화를 안 하면 엔진 순위가 뒤집힌다(표본: 정규화 전 A 17.5%·B 10.2%, 정규화 뒤 A 0%·B 1.5%).

정답지 형식(통화 한 건): `{"call_id": "...", "promises": [{"who": "me|them", "what": "...", "evidence": "자막 인용", "numbers": ["30만원"]}]}`. 자막 정답은 `{"id", "ref", "a", "b"}` 줄(JSONL).
