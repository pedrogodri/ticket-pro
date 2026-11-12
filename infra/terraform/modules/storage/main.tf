variable "bucket" {
  type = string
}

resource "aws_s3_bucket" "receipts" {
  bucket        = var.bucket
  force_destroy = true
}
