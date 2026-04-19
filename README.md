# 🎫 ticket-checker

멜론티켓 / 인터파크 취소표를 주기적으로 확인하여 Slack으로 알림을 보내주는 스크립트

## 공통 설정

### 설치

```bash
pip install requests
```

### slack_config.json

`slack_config.example.json`을 복사해서 `slack_config.json`으로 이름 변경 후 Webhook URL 입력

```json
{
  "webhook_url": "https://hooks.slack.com/services/YOUR/WEBHOOK/URL"
}
```

Slack Webhook URL 발급: [Slack API - Incoming Webhooks](https://api.slack.com/messaging/webhooks)

---

## 멜론티켓

### 동작 방식

1. 20~40초 랜덤 간격으로 멜론티켓 API 폴링
2. 취소표 발생 시 Slack 알림 전송
3. 세션 만료 감지 시 쿠키 갱신 알림 전송

### target.json 설정

`target.example.json`을 복사해서 `target.json`으로 이름 변경

```json
{
  "prodId": "212811",
  "scheduleNo": "100003",
  "seatId": "5_0",
  "volume": "1",
  "selectedGradeVolume": "1",
  "cookies": {
    "PCID": "...",
    "JSESSIONID": "...",
    "keyCookie": "...",
    "TKT_POC_ID": "...",
    "NetFunnel_ID": "..."
  }
}
```

**공연 정보 확인 방법**
1. 멜론티켓 로그인 후 예매 페이지로 이동
2. 개발자도구(F12) → Network → `seatStateInfo.json` 요청 클릭
3. Payload 탭에서 `prodId`, `scheduleNo`, `seatId` 값 확인

**쿠키 확인 방법**
1. 개발자도구(F12) → Application → Cookies → `ticket.melon.com`
2. 위 항목 값 복사해서 붙여넣기

> ⚠️ 로그인 세션 쿠키 기반으로, 만료 시 Slack으로 갱신 알림이 옵니다.

### 실행

```bash
python checker.py
```

### 알림 종류

| 알림 | 내용 |
|---|---|
| 🎫 취소표 발생 | `rmdSeatCnt > 0` — 예매 가능 매수 표시 |
| ⚡ 상태 변화 감지 | `chkResult > 0` — 상태 변화 가능성 |
| ⚠️ 세션 만료 | target.json의 cookies 갱신 필요 |
| ❌ 연속 오류 | 5회 이상 API 호출 실패 |

---

## 인터파크

### 동작 방식

1. 20~40초 랜덤 간격으로 인터파크 API 폴링
2. 등급별 잔여석(`RemainCnt`) 확인
3. 취소표 발생 시 등급명 / 잔여 매수 / 가격과 함께 Slack 알림 전송
4. 세션 만료 감지 시 갱신 알림 전송

### interpark_target.json 설정

`interpark_target.example.json`을 복사해서 `interpark_target.json`으로 이름 변경

```json
{
  "GoodsCode": "26005670",
  "PlaceCode": "26000407",
  "BizCode": "WEBBR",
  "PlaySeq": "001",
  "PlayDate": "20260808",
  "GoodsName": "공연명",
  "SessionId": "26005670_M...",
  "watch_grades": ["스탠딩R", "스탠딩S"],
  "cookies": {
    "pcid": "...",
    "interparkstamp": "...",
    "ECCS": "...",
    "CAPTGM": "...",
    "ent_token": "..."
  }
}
```

**공연 정보 확인 방법**
1. 인터파크 로그인 후 예매 페이지로 이동 (좌석 선택 단계까지 진입)
2. 개발자도구(F12) → Network → `BookInfoXml.asp?Flag=OrderSeatGrade` 요청 클릭
3. URL에서 `GoodsCode`, `PlaceCode`, `PlaySeq`, `SessionId` 값 확인

**쿠키 확인 방법**
1. 개발자도구(F12) → Application → Cookies → `poticket.interpark.com` / `.interpark.com`
2. `pcid`, `interparkstamp`, `ECCS`, `CAPTGM`, `ent_token` 값 복사해서 붙여넣기

**`watch_grades`** — 감시할 등급명 목록. 생략하면 전 등급 감시

> ⚠️ 로그인 세션이 아닌 방문 세션 쿠키 기반으로, 멜론티켓보다 오래 유지됩니다.
> 만료 시 Slack으로 갱신 알림이 옵니다.

### 실행

```bash
python interpark_checker.py
```

### 알림 종류

| 알림 | 내용 |
|---|---|
| 🎫 취소표 발생 | 등급별 잔여 매수 및 가격 표시 |
| ⚠️ 세션 만료 | interpark_target.json의 SessionId / cookies 갱신 필요 |
| ❌ 연속 오류 | 5회 이상 API 호출 실패 |
