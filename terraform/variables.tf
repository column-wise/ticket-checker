variable "aws_region" {
  description = "AWS region"
  default     = "ap-northeast-2"
}

variable "instance_type" {
  description = "EC2 instance type (t4g.nano ≈ $3/월)"
  default     = "t4g.nano"
}

variable "public_key_path" {
  description = "로컬 SSH 공개키 경로"
  default     = "~/.ssh/id_rsa.pub"
}

variable "checker_script" {
  description = "실행할 체커 스크립트"
  default     = "interpark_checker.py"

  validation {
    condition     = contains(["interpark_checker.py", "melon_checker.py"], var.checker_script)
    error_message = "checker_script은 'interpark_checker.py' 또는 'melon_checker.py' 이어야 합니다."
  }
}

# ── 공통 ──────────────────────────────────────────────────────────────────────

variable "slack_webhook_url" {
  description = "Slack Incoming Webhook URL"
  sensitive   = true
}

# ── 인터파크 ──────────────────────────────────────────────────────────────────

variable "interpark_session_id" {
  description = "인터파크 SessionId (만료 시 갱신 필요)"
  sensitive   = true
  default     = ""
}

variable "interpark_cookies_pcid" {
  sensitive = true
  default   = ""
}

variable "interpark_cookies_interparkstamp" {
  sensitive = true
  default   = ""
}

variable "interpark_cookies_eccs" {
  sensitive = true
  default   = ""
}

variable "interpark_cookies_captgm" {
  sensitive = true
  default   = ""
}

variable "interpark_cookies_ent_token" {
  sensitive = true
  default   = ""
}

# ── 멜론티켓 ──────────────────────────────────────────────────────────────────

variable "melon_name" {
  description = "공연명"
  default     = ""
}

variable "melon_prod_id" {
  default = ""
}

variable "melon_schedule_no" {
  default = ""
}

variable "melon_seat_id" {
  default = ""
}

variable "melon_volume" {
  default = "1"
}

variable "melon_selected_grade_volume" {
  default = "1"
}

variable "melon_cookies_pcid" {
  sensitive = true
  default   = ""
}

variable "melon_cookies_jsessionid" {
  sensitive = true
  default   = ""
}

variable "melon_cookies_keycookie" {
  sensitive = true
  default   = ""
}

variable "melon_cookies_tkt_poc_id" {
  sensitive = true
  default   = ""
}

variable "melon_cookies_netfunnel_id" {
  sensitive = true
  default   = ""
}
