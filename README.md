# 🎫 ticket-checker

멜론티켓 / 인터파크 취소표를 주기적으로 확인하여 알림을 보내주는 도구

| 버전 | 알림 | 실행 환경 | 세션 설정 |
|---|---|---|---|
| **Android 앱** | 푸시 알림 | 안드로이드 폰 (24/7 상시 가동) | WebView에서 자동 추출 |
| **Python 스크립트** | Slack Webhook | 로컬 PC / 서버 | 개발자도구로 수동 입력 |

---

## 📱 Android 앱 (`android/`)

### 특징

- WebView로 인터파크 / 멜론티켓에 직접 로그인 → 세션 자동 추출 (개발자도구 불필요)
- 포그라운드 서비스로 24시간 백그라운드 폴링 (20~40초 랜덤 간격)
- 취소표 발생 시 푸시 알림 → 탭하면 해당 플랫폼 예매 페이지로 이동
- 인터파크 등급별 잔여석 / 가격 알림
- 배터리 최적화 예외 설정으로 안정적인 상시 가동

### 설치

**[⬇️ APK 다운로드](https://github.com/column-wise/ticket-checker/releases/latest)**

1. APK 파일 다운로드
2. 설정 → 보안 → **출처를 알 수 없는 앱 허용** 활성화
3. APK 파일 열어서 설치

> 직접 빌드하려면 Android Studio에서 `android/` 폴더를 열어 실행

### 사용 방법

1. **인터파크 탭** — 로그인 후 예매 페이지에서 좌석 선택 단계까지 진입 → 세션 자동 감지
2. **멜론티켓 탭** — 로그인 후 예매 팝업 진입 → 세션 자동 감지
3. **모니터링 탭** — 감지된 세션 확인, 등급 선택, 서비스 시작
4. 배터리 최적화 예외 요청 팝업 → **허용** (안정적인 백그라운드 실행에 필요)

### 알림 종류

| 알림 | 내용 |
|---|---|
| 🎫 취소표 발생 | 등급별 잔여석 + 가격 (인터파크) / 잔여 매수 (멜론) |
| ⚠️ 세션 만료 | 앱 열어서 해당 탭 재진입 후 세션 갱신 |
| ❌ 연속 오류 | 5회 이상 API 호출 실패 |

---

## 🐍 Python 스크립트 (`scripts/`)

로컬 PC나 상시 가동 서버에서 실행하는 CLI 버전. Slack Webhook으로 알림 전송.

### 설치

```bash
cd scripts
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

### 멜론티켓

#### melon_target.json 설정

`melon_target.example.json`을 복사해서 `melon_target.json`으로 이름 변경

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

**공연 정보 / 쿠키 확인 방법**
1. 멜론티켓 로그인 후 예매 팝업 진입
2. 개발자도구(F12) → Network → `seatStateInfo.json` 요청 클릭
3. Payload에서 `prodId`, `scheduleNo`, `seatId` 확인
4. Application → Cookies → `ticket.melon.com`에서 쿠키 값 복사

#### 실행

```bash
python melon_checker.py
```

---

### 인터파크

#### interpark_target.json 설정

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

**공연 정보 / 쿠키 확인 방법**
1. 인터파크 로그인 후 예매 페이지에서 좌석 선택 단계까지 진입
2. 개발자도구(F12) → Network → `BookInfoXml.asp?Flag=OrderSeatGrade` 요청 클릭
3. URL에서 `GoodsCode`, `PlaceCode`, `PlaySeq`, `SessionId` 확인
4. Application → Cookies → `poticket.interpark.com`에서 쿠키 값 복사

**`watch_grades`** — 감시할 등급명 목록. 생략하거나 빈 배열이면 전 등급 감시

#### 실행

```bash
python interpark_checker.py
```

---

## ⚠️ 클라우드 배포 불가 (인터파크)

인터파크는 AWS를 포함한 클라우드 IP 대역을 CloudFront WAF로 차단합니다.
EC2 등 클라우드 서버에서 실행하면 모든 API 요청이 403으로 차단됩니다.

**대안:**
- **Android 앱** (권장) — 폰을 충전기에 꽂아두고 24/7 가동
- 로컬 PC에서 직접 실행
- 항상 켜져 있는 가정용 기기 (라즈베리파이, NAS 등)
- 한국 클라우드 (네이버 클라우드, KT Cloud 등) — 차단 여부 사전 확인 필요
