output "public_ip" {
  value = aws_instance.checker.public_ip
}

output "ssh_command" {
  value = "ssh -i ~/.ssh/id_rsa ec2-user@${aws_instance.checker.public_ip}"
}

output "update_session_command" {
  description = "SessionId 갱신 시 실행할 명령어"
  value       = "ssh -i ~/.ssh/id_rsa ec2-user@${aws_instance.checker.public_ip} 'sudo nano /opt/ticket-checker/interpark_target.json && sudo systemctl restart ticket-checker'"
}
