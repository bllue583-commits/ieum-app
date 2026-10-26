# 2026-10-26 재개 계획 — 제품 구현 아님

현재 상태는 **DESIGN_ONLY / PRODUCT_NOT_IMPLEMENTED**입니다. 이 폴더의 JSON 기대값과 Python 검사는 설계 입력의 무결성/시간 수식을 검사합니다. Android/DB/STT/실서비스/보고 전달 합격 증거가 아닙니다. Windows 작업은 code34 소스 확보에 실패해 제품 코드 변경 0, 제품 통합 패치 0, 제품 시험 NOT_RUN이었습니다.

확인한 현재 설계: [이음 기능 설계도와 검증 일지](https://chatgpt.com/space/page_257b0a73cf808191a03c02362edc2673), sequence54 (2026-10-26). snapshot 참조 `libfile_84ed0a527b1481918b60b675b63a19c1`. 현재 Page를 직접 읽었으며 snapshot ZIP은 이번 이관에서 내려받지 않았습니다.

## 포함한 인계와 검증된 바이트

| 폴더 | Library 원본 | ZIP bytes / SHA256 |
| --- | --- | --- |
| `context` | `libfile_d386016b998c8191988e8c88fdde5f3e` | 22596 / `2ae123642b04f51f4ebb78b2c1906c9f21883b1b76e4bae51fa5968d82d8b7ef` |
| `kst-report` | `libfile_c5d3ee87e8188191a5dacbd396510d2b` | 30897 / `5dc4b15d1fba3c98bf020f6a0d509cd321b21d73a819434380a7356e0c7ad266` |

공식 Library helper로 Mac에 정상 materialize한 ZIP에서 계약·합성 입력·검사기는 원문에서 추출했고 Git에서 LF 줄바꿈만 정규화합니다. 검사에 필요한 kst provenance는 Windows 개인 경로 두 곳만 비식별화했습니다. 개인 Windows 작업경로·전송 로그·과거 차단 보고·중복 설계 발췌·산출 manifest는 제외했습니다. `context`의 30개 제품 시나리오와 `kst-report`의 40개 제품 시나리오는 모두 NOT_RUN입니다.

## 우선순위와 계약

1. 허용된 녹음 수집 → 동일 audio/job의 자동 접수·실제 STT → high 문맥 분석·dot 검토 → 같은 결과 파일 회수·기존 Store.accept를 잇습니다. code34 공개 local Whisper 성공은 보조 증거입니다.
2. 분석 전 case/task/시각을 임의로 만들지 않습니다. source identity와 content hash는 별개이며 번호 일치가 사람·업무 의미 확정은 아닙니다. 새 wire/DB 스키마는 이 문서가 승인하지 않습니다.
3. 관련 SMS·네이버 메일을 허용 범위·근거와 함께 종합합니다. 수동 DONE/메모/잠금·owner/source/lease/revision·보관/숨김을 유지합니다.
4. KST 보고일 D의 범위는 `[D-1 00:00,D 00:00)`, 전날23시 1차·보고 전23~24시/늦은 수집 증분,06시 부분보고·새 revision을 설계합니다. occurredAt과 수집 cursor를 분리합니다. 제안의 KST 고정 적용은 현재 제품과 대조 후 별도 변경합니다.
5. 저장·생성·배송 상태를 나누고 ACK 없는 응답 소실은 UNKNOWN으로 둡니다. 실제 운영 인수 전 정각 실행/정확히 한 번 전달을 주장하지 않습니다. 카카오는 선택이며 현재 무인 발송 DISABLED입니다.

```sh
python3 docs/plans/context/verify_fixture_integrity.py
python3 docs/plans/kst-report/verify_contract.py
```

검사기가 같은 폴더에 결과 JSON을 기록하므로 실행 후 `git diff`를 확인합니다. 생성 검사결과는 기능 구현과 별도로 취급합니다. 새 외부 계정 연결·실자료 읽기·새 자동화·NAS 변경은 이 인계에 포함하지 않습니다.
