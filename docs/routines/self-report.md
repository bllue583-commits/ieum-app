# 루틴 자가 보고 (프롬프트 마지막 단계로 붙일 문단)

아래 문단을 매시간 루틴과 새벽 루틴 지시문의 맨 마지막 단계로 붙여 넣는다. (설계도 B3, 워치독이 읽는다.)

---

마지막 단계. 모든 처리가 끝나면 (처리할 것이 없었어도, 일부가 실패했어도 항상) `status/last-run.json`을 새로 써서, 결과 파일과 같은 커밋에 넣어라. 파일이 이미 있으면 통째로 덮어쓴다. 형식은 아래 한 줄 JSON이다.

```json
{"routine":"hourly","startedMs":0,"endedMs":0,"processed":0,"skipped":0,"failed":0,"batches":0,"model":"","limitHit":false,"ledgerRev":0,"promptVersion":"","promptMismatch":false}
```

- `routine`: 매시간이면 `hourly`, 새벽 정리면 `tidy`.
- `startedMs`, `endedMs`: 이번 실행의 시작과 끝 시각(밀리초 에포크).
- `processed`: 결과를 만든 요청 항목 수. `skipped`: 이미 처리돼 건너뛴 수. `failed`: 처리하려다 실패한 수. `batches`: 읽은 요청 파일 수.
- `model`: 이번 실행에 쓴 모델 이름.
- `limitHit`: 사용 한도 오류를 만났으면 `true`, 아니면 `false`.
- `ledgerRev`: 이번에 쓴 장부의 rev. `promptVersion`: 이 지시문의 버전(앞 8자). 요청의 `promptVersion`이 다르면 그래도 처리하되 `promptMismatch`를 `true`로 쓴다.

이 파일에는 시각, 숫자, 모델 이름, 참/거짓만 쓴다. 자막 원문, 이름, 전화번호, 회사 이름, 요약, 오류 메시지 본문은 절대 쓰지 않는다. 실행이 중간에 막혀 결과를 못 썼더라도 이 파일은 `processed`를 실제 값(0이어도)으로 써서 커밋한다. 커밋 메시지에는 건수 외에 내용을 적지 않는다.
