# 이음 사용 설문 (v3.9 r20-6, DESIGN_V7 6 — 채점 항목 6·10)

아내가 **혼자 폰에서** 적는 한 장짜리 양식이 [ieum-survey.html](ieum-survey.html)이다. 앱 화면에는 넣지 않는다. 남편이 옆에서 물으면 기대에 맞춰 답하기 쉬우므로(사회적 바람직성 편향) 양식만 건네고 자리를 비운다.

## 하는 법 (사용자, 약 10분)

1. 이 파일(`docs/survey/ieum-survey.html`)을 카카오톡 "나와의 채팅"에 파일로 올린 뒤 아내에게 전달한다. 아내 폰에서 파일을 누르면 브라우저(삼성 인터넷)로 열린다. 인터넷에 아무것도 보내지 않는 화면이다.
2. 아내에게 부탁할 말은 한 줄: "이음 써 본 대로 혼자 적어 줘. 밤 9시쯤 배터리 화면 숫자도 같이." 그다음은 묻지 않는다.
3. 아내가 마지막에 나온 한 줄(`IEUM-SURVEY 1 | sus=… | miss=… | battery=… | tasks=… | date=…`)을 카톡으로 보내 오면:
   ```sh
   python3 -I tools/survey/survey_to_json.py "<받은 한 줄>" --write status/survey.json
   ```
   를 우편함 저장소 안에서 돌려 `status/survey.json`을 만들고 커밋한다(숫자만 들어 있다). `tools/inbox/score.py`가 `sus`(항목 10, 기준 80 이상), `veryDisappointed`(항목 10, "매우 아쉽다"여야 통과), `batterySharePct`(항목 6, 10% 이하)를 읽는다.

## 무엇을 묻나

| 부분 | 내용 | 결과 키 |
| --- | --- | --- |
| 1 | SUS 10문항(1~5) | `sus`(0~100), `susItems` |
| 2 | 내일부터 못 쓰게 되면: 매우 아쉽다 / 조금 아쉽다 / 상관없다(Sean Ellis 질문) | `veryDisappointed`, `disappointment` |
| 3 | 밤 9시 설정 › 배터리 › 사용량에서 이음 옆 % | `batterySharePct` |
| 4 (선택) | 혼자 해 보기 5가지(방금 통화 요약 찾기, 금액을 원문과 대조, 체크할 일 하나 끝내기, 특정 업체의 지난 통화 찾기, 메모 남기기): 쉽게 했다 / 헤맸다 / 못 했다 | `tasks` |

DESIGN_V7 6절의 "소리 내어 쓰기 5과제"는 옆에서 듣는 사람이 있어야 하므로, 아내가 혼자 적는 이 양식에서는 과제별 자기 평가(4번)로 바꿨다. 소리 내어 쓰기를 따로 할 때도 남편이 아닌 사람이 듣는 것이 좋다.

## SUS 한국어판에 대해 (사실대로)

검증된 공식 한국어 번역본을 찾지 못했다. 그래서 Brooke(1996) 원문 10문항을 이음에 맞게("this system" → "이음") 옮기고(번역), 그 한국어를 다시 영어로 옮겨(역번역) 원문과 뜻이 같은지 맞췄다. 검증 연구를 거친 번역이 아니고 응답자도 한 사람이므로 **점수는 참고치**다(SUS 평균 68, Sauro & Lewis 2016). 그래서 채점표는 행동 숫자(하트비트 `use`, `feedback`)를 같이 본다.

| # | 원문 (Brooke 1996) | 한국어 (양식) | 역번역 |
| --- | --- | --- | --- |
| 1 | I think that I would like to use this system frequently. | 이음을 앞으로도 자주 쓰고 싶다. | I want to keep using Ieum often. |
| 2 | I found the system unnecessarily complex. | 이음은 쓸데없이 복잡하다. | Ieum is needlessly complicated. |
| 3 | I thought the system was easy to use. | 이음은 쓰기 쉽다. | Ieum is easy to use. |
| 4 | I think that I would need the support of a technical person to be able to use this system. | 이음을 쓰려면 잘 아는 사람의 도움이 필요할 것 같다. | I think I would need help from someone who knows it well to use Ieum. |
| 5 | I found the various functions in this system were well integrated. | 이음의 여러 기능이 서로 잘 이어져 있다. | Ieum's various functions are well connected to each other. |
| 6 | I thought there was too much inconsistency in this system. | 이음에는 앞뒤가 맞지 않는 부분이 너무 많다. | Ieum has too many parts that don't fit together. |
| 7 | I would imagine that most people would learn to use this system very quickly. | 대부분의 사람은 이음을 금방 배워서 쓸 수 있을 것 같다. | Most people could probably learn to use Ieum quickly. |
| 8 | I found the system very cumbersome to use. | 이음은 쓰기가 아주 번거롭다. | Ieum is very cumbersome to use. |
| 9 | I felt very confident using the system. | 이음을 쓸 때 자신 있게 쓸 수 있었다. | I was able to use Ieum confidently. |
| 10 | I needed to learn a lot of things before I could get going with this system. | 이음을 쓰기 전에 배워야 할 것이 많았다. | There was a lot to learn before using Ieum. |

차이: 4번 "technical person"은 "잘 아는 사람"으로 넓혔다(아내에게 "기술자"는 어색하고 실제로 돕는 사람은 남편). 6번 "inconsistency"는 "앞뒤가 맞지 않는 부분"으로 풀었다. 점수 계산은 원문 그대로: 홀수 문항 (답 − 1), 짝수 문항 (5 − 답), 합 × 2.5.

시험: `python3 -m unittest tools/test_survey_to_json.py`(점수 계산, `score.py`가 읽는 키, 양식과 도구의 문항 수·형식 일치, 양식이 아무것도 보내지 않음).
