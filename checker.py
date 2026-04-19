import requests
import json
import time
import random
import re
import logging
from pathlib import Path

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s  %(levelname)s  %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S",
)
log = logging.getLogger(__name__)

CONFIG_FILE = Path("config.json")
COOKIES_FILE = Path("cookies.json")

API_URL = "https://ticket.melon.com/tktapi/product/seatStateInfo.json"
CALLBACK = "melonChecker"

FORM_DATA = {
    "prodId": "212811",
    "scheduleNo": "100003",
    "seatId": "5_0",
    "volume": "1",
    "selectedGradeVolume": "1",
}

HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36",
    "Accept": "text/javascript, application/javascript, */*; q=0.01",
    "Accept-Language": "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7",
    "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8",
    "Origin": "https://ticket.melon.com",
    "Referer": "https://ticket.melon.com/reservation/popup/stepTicket.htm",
    "X-Requested-With": "XMLHttpRequest",
    "sec-ch-ua": '"Google Chrome";v="147", "Not.A/Brand";v="8", "Chromium";v="147"',
    "sec-ch-ua-mobile": "?0",
    "sec-ch-ua-platform": '"Windows"',
    "sec-fetch-dest": "empty",
    "sec-fetch-mode": "cors",
    "sec-fetch-site": "same-origin",
}


def load_config():
    if not CONFIG_FILE.exists():
        raise FileNotFoundError("config.json 없음. config.json을 먼저 만들어주세요.")
    cfg = json.loads(CONFIG_FILE.read_text(encoding="utf-8"))
    if not cfg.get("slack_webhook_url"):
        raise ValueError("config.json에 slack_webhook_url을 입력해주세요.")
    return cfg


def load_cookies():
    if not COOKIES_FILE.exists():
        raise FileNotFoundError("cookies.json 없음. 브라우저에서 쿠키를 내보내주세요.")
    return json.loads(COOKIES_FILE.read_text(encoding="utf-8"))


def parse_response(text):
    match = re.search(r"\{.*\}", text, re.DOTALL)
    if match:
        return json.loads(match.group())
    raise ValueError(f"응답 파싱 실패: {text[:200]}")


def send_slack(webhook_url, message):
    try:
        r = requests.post(webhook_url, json={"text": message}, timeout=10)
        r.raise_for_status()
        log.info("슬랙 알림 전송 완료")
    except Exception as e:
        log.error(f"슬랙 전송 실패: {e}")


def check_ticket(cookies, webhook_url):
    session = requests.Session()
    session.cookies.update(cookies)

    try:
        resp = session.post(
            API_URL,
            params={"v": "1", "callback": CALLBACK},
            data=FORM_DATA,
            headers=HEADERS,
            timeout=15,
        )
        resp.raise_for_status()

        data = parse_response(resp.text)
        cnt = data.get("rmdSeatCnt", 0)
        chk = data.get("chkResult", 0)

        log.info(f"rmdSeatCnt={cnt}  chkResult={chk}")

        # 세션 만료 감지: chkResult가 음수이거나 응답에 로그인 관련 내용이 포함된 경우
        if chk < 0 or "login" in resp.text.lower():
            log.warning("세션 만료 감지")
            send_slack(
                webhook_url,
                "⚠️ 멜론티켓 세션이 만료되었습니다.\n"
                "cookies.json을 브라우저에서 다시 내보내서 교체해주세요.",
            )
            return "session_expired"

        if cnt > 0:
            send_slack(
                webhook_url,
                f"🎫 취소표 발생! *{cnt}매* 예매 가능!\n"
                f"https://ticket.melon.com/performance/index.htm?prodId=212811",
            )
            log.info(f"취소표 감지: {cnt}매")
            return "available"

        if chk > 0:
            send_slack(
                webhook_url,
                f"⚡ chkResult={chk} 감지! 상태 변화 가능성 있음.\n"
                f"https://ticket.melon.com/performance/index.htm?prodId=212811",
            )
            log.info(f"chkResult 양수 감지: {chk}")
            return "chk_positive"

        return "none"

    except Exception as e:
        log.error(f"API 호출 실패: {e}")
        return "error"


def main():
    config = load_config()
    webhook_url = config["slack_webhook_url"]

    log.info("티켓 체커 시작 (20~40초 랜덤 간격)")
    send_slack(webhook_url, "🔍 멜론티켓 취소표 모니터링 시작")

    consecutive_errors = 0

    while True:
        try:
            cookies = load_cookies()
        except FileNotFoundError as e:
            log.error(str(e))
            break

        result = check_ticket(cookies, webhook_url)

        if result == "error":
            consecutive_errors += 1
            if consecutive_errors >= 5:
                send_slack(
                    webhook_url,
                    f"❌ {consecutive_errors}회 연속 오류. 네트워크 또는 API 문제를 확인해주세요.",
                )
        else:
            consecutive_errors = 0

        if result == "session_expired":
            log.info("세션 만료 — 60초 대기 후 재시도")
            time.sleep(60)
            continue

        delay = random.uniform(20, 40)
        log.info(f"다음 확인까지 {delay:.1f}초 대기")
        time.sleep(delay)


if __name__ == "__main__":
    main()
