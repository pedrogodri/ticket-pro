variable "secret_id" {
  type = string
}

variable "key_id" {
  type = string
}

resource "tls_private_key" "jwt" {
  algorithm = "RSA"
  rsa_bits  = 2048
}

resource "aws_secretsmanager_secret" "jwt" {
  name                    = var.secret_id
  recovery_window_in_days = 0
}

resource "aws_secretsmanager_secret_version" "jwt" {
  secret_id = aws_secretsmanager_secret.jwt.id
  secret_string = jsonencode({
    current = {
      kid        = var.key_id
      privatePem = tls_private_key.jwt.private_key_pem
    }
    previous = null
  })
}
