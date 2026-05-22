#!/bin/bash
set -e

# 의존성 설치
dnf install -y python3 python3-pip git

# 코드 클론
git clone https://github.com/column-wise/ticket-checker.git /opt/ticket-checker
cd /opt/ticket-checker

# Python 패키지 설치
python3 -m pip install -r requirements.txt

# slack_config.json 생성
cat > /opt/ticket-checker/slack_config.json << 'JSONEOF'
{
  "webhook_url": "${slack_webhook_url}"
}
JSONEOF

%{~ if checker_script == "interpark_checker.py" }

# interpark_target.json 생성
python3 - << 'PYEOF'
import json

target = {
    "GoodsCode": "26006903",
    "PlaceCode": "26000511",
    "BizCode": "WEBBR",
    "PlaySeq": "002",
    "PlayDate": "20261008",
    "GoodsName": "현대카드 슈퍼콘서트 28 위켄드(The Weeknd)",
    "SessionId": "${interpark_session_id}",
    "watch_grades": ["스탠딩"],
    "cookies": {
        "pcid": "${interpark_cookies_pcid}",
        "interparkstamp": "${interpark_cookies_interparkstamp}",
        "ECCS": "${interpark_cookies_eccs}".strip(),
        "CAPTGM": "${interpark_cookies_captgm}".strip(),
        "ent_token": "${interpark_cookies_ent_token}".strip()
    }
}

with open("/opt/ticket-checker/interpark_target.json", "w", encoding="utf-8") as f:
    json.dump(target, f, ensure_ascii=False, indent=2)
PYEOF

%{~ else }

# melon_target.json 생성
python3 - << 'PYEOF'
import json

target = {
    "name": "${melon_name}",
    "prodId": "${melon_prod_id}",
    "scheduleNo": "${melon_schedule_no}",
    "seatId": "${melon_seat_id}",
    "volume": "${melon_volume}",
    "selectedGradeVolume": "${melon_selected_grade_volume}",
    "cookies": {
        "PCID": "${melon_cookies_pcid}",
        "JSESSIONID": "${melon_cookies_jsessionid}",
        "keyCookie": "${melon_cookies_keycookie}",
        "TKT_POC_ID": "${melon_cookies_tkt_poc_id}",
        "NetFunnel_ID": "${melon_cookies_netfunnel_id}"
    }
}

with open("/opt/ticket-checker/melon_target.json", "w", encoding="utf-8") as f:
    json.dump(target, f, ensure_ascii=False, indent=2)
PYEOF

%{~ endif }

# systemd 서비스 등록
cat > /etc/systemd/system/ticket-checker.service << 'SVCEOF'
[Unit]
Description=Ticket Checker
After=network.target

[Service]
Type=simple
WorkingDirectory=/opt/ticket-checker
ExecStart=/usr/bin/python3 /opt/ticket-checker/${checker_script}
Restart=always
RestartSec=10
StandardOutput=journal
StandardError=journal
Environment=PYTHONUNBUFFERED=1

[Install]
WantedBy=multi-user.target
SVCEOF

systemctl daemon-reload
systemctl enable ticket-checker
systemctl start ticket-checker
