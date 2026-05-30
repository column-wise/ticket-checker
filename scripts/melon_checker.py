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

SLACK_CONFIG_FILE = Path("slack_config.json")
TARGET_FILE = Path("melon_target.json")

API_URL = "https://ticket.melon.com/tktapi/product/seatStateInfo.json"
CALLBACK = "melonChecker"

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


def load_slack_config():
    if not SLACK_CONFIG_FILE.exists():
        raise FileNotFoundError("slack_config.json 없음. slack_config.json을 먼저 만들어주세요.")
    cfg = json.loads(SLACK_CONFIG_FILE.read_text(encoding="utf-8"))
    if not cfg.get("webhook_url"):
        raise ValueError("slack_config.json에 webhook_url을 입력해주세요.")
    return cfg


def load_target():
    if not TARGET_FILE.exists():
        raise FileNotFoundError("target.json 없음. 공연 정보와 쿠키를 입력해주세요.")
    data = json.loads(TARGET_FILE.read_text(encoding="utf-8"))
    for key in ("prodId", "scheduleNo", "seatId"):
        if not data.get(key):
            raise ValueError(f"target.json에 {key} 값이 없습니다.")
    if not data.get("cookies"):
        raise ValueError("target.json에 cookies가 없습니다.")
    return data


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


def check_ticket(target, webhook_url):
    session = requests.Session()
    session.cookies.update(target["cookies"])

    form_data = {
        "prodId": target["prodId"],
        "scheduleNo": target["scheduleNo"],
        "seatId": target["seatId"],
        "volume": target.get("volume", "1"),
        "selectedGradeVolume": target.get("selectedGradeVolume", "1"),
    }

    try:
        resp = session.post(
            API_URL,
            params={"v": "1", "callback": CALLBACK},
            data=form_data,
            headers=HEADERS,
            timeout=15,
        )
        resp.raise_for_status()

        data = parse_response(resp.text)
        cnt = data.get("rmdSeatCnt", 0)
        chk = data.get("chkResult", 0)

        log.info(f"\n  잔여석:\t{cnt}\n  chkResult:\t{chk}")

        name = target.get("name", "")
        name_line = f"*{name}*\n" if name else ""
        link = f"🔗 https://ticket.melon.com/performance/index.htm?prodId={target['prodId']}"

        if chk < 0 or "login" in resp.text.lower():
            log.warning("세션 만료 감지")
            send_slack(
                webhook_url,
                "⚠️ 멜론티켓 세션이 만료되었습니다.\n"
                "melon_target.json의 cookies를 브라우저에서 다시 내보내서 교체해주세요.",
            )
            return "session_expired"

        if cnt > 0:
            send_slack(
                webhook_url,
                f"🎫 멜론티켓 취소표 발생!\n{name_line}*{cnt}매* 예매 가능!\n{link}",
            )
            log.info(f"취소표 감지: {cnt}매")
            return "available"

        if chk > 0:
            send_slack(
                webhook_url,
                f"⚡ 멜론티켓 상태 변화 감지!\n{name_line}chkResult={chk}\n{link}",
            )
            log.info(f"chkResult 양수 감지: {chk}")
            return "chk_positive"

        return "none"

    except Exception as e:
        log.error(f"API 호출 실패: {e}")
        return "error"


def main():
    slack = load_slack_config()
    webhook_url = slack["webhook_url"]

    log.info("티켓 체커 시작 (20~40초 랜덤 간격)")
    try:
        t = load_target()
        name = t.get("name", "")
        start_msg = "🔍 멜론티켓 취소표 모니터링 시작" + (f"\n*{name}*" if name else "")
    except Exception:
        start_msg = "🔍 멜론티켓 취소표 모니터링 시작"
    send_slack(webhook_url, start_msg)

    consecutive_errors = 0

    while True:
        try:
            target = load_target()
        except (FileNotFoundError, ValueError) as e:
            log.error(str(e))
            break

        result = check_ticket(target, webhook_url)

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
        log.info(f"다음 확인까지 {delay:.1f}초 대기\n")
        time.sleep(delay)


if __name__ == "__main__":
    main()
