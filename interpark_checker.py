import requests
import xml.etree.ElementTree as ET
import json
import time
import random
import logging
from pathlib import Path

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s  %(levelname)s  %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S",
)
log = logging.getLogger(__name__)

SLACK_CONFIG_FILE = Path("slack_config.json")
TARGET_FILE = Path("interpark_target.json")

API_URL = "https://poticket.interpark.com/Book/Lib/BookInfoXml.asp"

HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36",
    "Accept": "application/xml, text/xml, */*",
    "Accept-Language": "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7",
    "Referer": "https://poticket.interpark.com/",
}


def load_slack_config():
    if not SLACK_CONFIG_FILE.exists():
        raise FileNotFoundError("slack_config.json 없음.")
    cfg = json.loads(SLACK_CONFIG_FILE.read_text(encoding="utf-8"))
    if not cfg.get("webhook_url"):
        raise ValueError("slack_config.json에 webhook_url을 입력해주세요.")
    return cfg


def load_target():
    if not TARGET_FILE.exists():
        raise FileNotFoundError("interpark_target.json 없음.")
    data = json.loads(TARGET_FILE.read_text(encoding="utf-8"))
    for key in ("GoodsCode", "PlaceCode", "PlaySeq", "SessionId"):
        if not data.get(key):
            raise ValueError(f"interpark_target.json에 {key} 값이 없습니다.")
    return data


def parse_grades(xml_text):
    root = ET.fromstring(xml_text)
    grades = []
    for table in root.findall("Table"):
        grades.append({
            "grade": table.findtext("SeatGrade"),
            "name": table.findtext("SeatGradeName"),
            "remain": int(table.findtext("RemainCnt") or 0),
            "price": table.findtext("SalesPrice"),
        })
    return grades


def send_slack(webhook_url, message):
    try:
        r = requests.post(webhook_url, json={"text": message}, timeout=10)
        r.raise_for_status()
        log.info("슬랙 알림 전송 완료")
    except Exception as e:
        log.error(f"슬랙 전송 실패: {e}")


def check_ticket(target, webhook_url):
    params = {
        "Flag": "OrderSeatGrade",
        "GoodsCode": target["GoodsCode"],
        "PlaceCode": target["PlaceCode"],
        "BizCode": target.get("BizCode", "WEBBR"),
        "PlaySeq": target["PlaySeq"],
        "SessionId": target["SessionId"],
        "InterlockingGoods": "",
    }

    session = requests.Session()
    session.cookies.update(target.get("cookies", {}))

    try:
        resp = session.get(API_URL, params=params, headers=HEADERS, timeout=15)
        resp.raise_for_status()
        resp.encoding = "utf-8"

        grades = parse_grades(resp.text)

        # 감시 등급 필터 (설정 없으면 전체 감시)
        watch_grades = target.get("watch_grades")

        available = [
            g for g in grades
            if g["remain"] > 0 and (not watch_grades or g["name"] in watch_grades)
        ]

        status = " | ".join(f'{g["name"]} {g["remain"]}매' for g in grades)
        log.info(status)

        if available:
            lines = [f'*{g["name"]}* {g["remain"]}매 ({int(g["price"]):,}원)' for g in available]
            play_date = target.get("PlayDate", "")
            date_str = f"{play_date[:4]}.{play_date[4:6]}.{play_date[6:]}" if play_date else ""
            goods_name = target.get("GoodsName", "")
            send_slack(
                webhook_url,
                f"🎫 인터파크 취소표 발생!\n"
                + (f"*{goods_name}*\n" if goods_name else "")
                + (f"📅 {date_str} {target.get('PlaySeq', '')}회차\n" if date_str else "")
                + "\n".join(lines)
                + f"\n🔗 https://ticket.interpark.com/Ticket/Goods/GoodsInfo.asp?GoodsCode={target['GoodsCode']}",
            )
            return "available"

        return "none"

    except ET.ParseError as e:
        log.error(f"XML 파싱 실패 (세션 만료 가능성): {e}")
        send_slack(
            webhook_url,
            "⚠️ 인터파크 응답 파싱 실패.\n"
            "interpark_target.json의 SessionId 또는 cookies를 갱신해주세요.",
        )
        return "session_expired"

    except Exception as e:
        log.error(f"API 호출 실패: {e}")
        return "error"


def main():
    slack = load_slack_config()
    webhook_url = slack["webhook_url"]

    log.info("인터파크 티켓 체커 시작 (20~40초 랜덤 간격)")
    send_slack(webhook_url, "🔍 인터파크 취소표 모니터링 시작")

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
        log.info(f"다음 확인까지 {delay:.1f}초 대기")
        time.sleep(delay)


if __name__ == "__main__":
    main()
