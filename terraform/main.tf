terraform {
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

provider "aws" {
  region = var.aws_region
}

data "aws_ami" "al2023_arm" {
  most_recent = true
  owners      = ["amazon"]

  filter {
    name   = "name"
    values = ["al2023-ami-*-arm64"]
  }

  filter {
    name   = "architecture"
    values = ["arm64"]
  }
}

data "aws_vpc" "default" {
  default = true
}

resource "aws_key_pair" "checker" {
  key_name   = "ticket-checker-key"
  public_key = file(var.public_key_path)
}

resource "aws_security_group" "checker" {
  name        = "ticket-checker-sg"
  description = "Ticket checker: SSH inbound + all outbound"
  vpc_id      = data.aws_vpc.default.id

  ingress {
    description = "SSH"
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_instance" "checker" {
  ami                    = data.aws_ami.al2023_arm.id
  instance_type          = var.instance_type
  key_name               = aws_key_pair.checker.key_name
  vpc_security_group_ids = [aws_security_group.checker.id]

  user_data = templatefile("${path.module}/user_data.sh.tpl", {
    checker_script                   = var.checker_script
    slack_webhook_url                = var.slack_webhook_url
    interpark_session_id             = var.interpark_session_id
    interpark_cookies_pcid           = var.interpark_cookies_pcid
    interpark_cookies_interparkstamp = var.interpark_cookies_interparkstamp
    interpark_cookies_eccs           = var.interpark_cookies_eccs
    interpark_cookies_captgm         = var.interpark_cookies_captgm
    interpark_cookies_ent_token      = var.interpark_cookies_ent_token
    melon_name                       = var.melon_name
    melon_prod_id                    = var.melon_prod_id
    melon_schedule_no                = var.melon_schedule_no
    melon_seat_id                    = var.melon_seat_id
    melon_volume                     = var.melon_volume
    melon_selected_grade_volume      = var.melon_selected_grade_volume
    melon_cookies_pcid               = var.melon_cookies_pcid
    melon_cookies_jsessionid         = var.melon_cookies_jsessionid
    melon_cookies_keycookie          = var.melon_cookies_keycookie
    melon_cookies_tkt_poc_id         = var.melon_cookies_tkt_poc_id
    melon_cookies_netfunnel_id       = var.melon_cookies_netfunnel_id
  })

  tags = {
    Name = "ticket-checker"
  }
}
