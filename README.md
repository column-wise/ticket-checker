# 🎫 melon-ticket-checker

멜론티켓 취소표를 주기적으로 확인하여 Slack으로 알림을 보내주는 스크립트

## 동작 방식

1. 20~40초 랜덤 간격으로 멜론티켓 API를 폴링
2. 취소표 발생 시 Slack 알림 전송
3. 세션 만료 감지 시 쿠키 갱신 알림 전송

## 설치

```bash
pip install requests
```

## 설정

### 1. config.json

`config.example.json`을 복사해서 `config.json`으로 이름 변경 후 Slack Webhook URL 입력

```json
{
  "slack_webhook_url": "https://hooks.slack.com/services/YOUR/WEBHOOK/URL"
}
```

Slack Webhook URL 발급: [Slack API - Incoming Webhooks](https://api.slack.com/messaging/webhooks)

### 2. cookies.json

`cookies.example.json`을 복사해서 `cookies.json`으로 이름 변경 후 브라우저 쿠키 입력

Chrome 기준:
1. 멜론티켓 로그인 후 예매 페이지로 이동
2. 개발자도구(F12) → Application → Cookies → `ticket.melon.com`
3. 아래 항목 값을 복사해서 붙여넣기

```json
{
  "PCID": "...",
  "JSESSIONID": "...",
  "keyCookie": "...",
  "TKT_POC_ID": "...",
  "NetFunnel_ID": "..."
}
```

> ⚠️ 세션 쿠키는 일정 시간 후 만료됩니다. 만료 시 Slack으로 갱신 알림이 옵니다.

### 3. checker.py 파라미터 수정

공연마다 다른 값을 사용하므로 `checker.py` 내 `FORM_DATA`를 수정

```python
FORM_DATA = {
    "prodId": "212811",    # 공연 ID
    "scheduleNo": "100003", # 회차 번호
    "seatId": "5_0",       # 좌석 등급 ID
    "volume": "1",
    "selectedGradeVolume": "1",
}
```

개발자도구 Network 탭에서 `seatStateInfo.json` 요청의 Form Data를 확인

## 실행

```bash
python checker.py
```

## 알림 종류

| 알림 | 내용 |
|---|---|
| 🎫 취소표 발생 | `rmdSeatCnt > 0` — 예매 가능 매수 표시 |
| ⚡ 상태 변화 감지 | `chkResult > 0` — 상태 변화 가능성 |
| ⚠️ 세션 만료 | 쿠키 갱신 필요 |
| ❌ 연속 오류 | 5회 이상 API 호출 실패 |
