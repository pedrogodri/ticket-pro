provider "aws" {
  region     = var.aws_region
  access_key = "test"
  secret_key = "test"

  skip_credentials_validation = true
  skip_requesting_account_id  = true
  skip_metadata_api_check     = true
  s3_use_path_style           = true

  endpoints {
    sns            = var.aws_endpoint
    sqs            = var.aws_endpoint
    s3             = var.aws_endpoint
    secretsmanager = var.aws_endpoint
    sts            = var.aws_endpoint
  }
}

module "messaging" {
  source = "./modules/messaging"

  topic_by_type              = var.topic_by_type
  subscriptions              = var.subscriptions
  visibility_timeout_seconds = var.visibility_timeout_seconds
  max_receive_count          = var.max_receive_count
  message_retention_seconds  = var.message_retention_seconds
}

module "storage" {
  source = "./modules/storage"

  bucket = var.receipts_bucket
}

module "secrets" {
  source = "./modules/secrets"

  secret_id = var.jwt_secret_id
  key_id    = var.jwt_key_id
}
