variable "aws_endpoint" {
  description = "AWS-compatible endpoint (MiniStack). Comes from AWS_ENDPOINT_URL via TF_VAR_aws_endpoint."
  type        = string
  default     = "http://ministack:4566"
}

variable "aws_region" {
  type    = string
  default = "us-east-1"
}

variable "visibility_timeout_seconds" {
  description = "SQS visibility timeout for every consumer queue (RFC 0003 §5.1)."
  type        = number
  default     = 30
}

variable "max_receive_count" {
  description = "Receives before a message is moved to the DLQ (ADR-0001)."
  type        = number
  default     = 3
}

variable "message_retention_seconds" {
  description = "Queue retention: 4 days (RFC 0001 §5.4)."
  type        = number
  default     = 345600
}

variable "topic_by_type" {
  description = "Event type -> SNS topic (docs/arquitetura.md §7)."
  type        = map(string)
  default = {
    UserRegistered              = "ticketpro-auth"
    TokenRevoked                = "ticketpro-auth"
    UserCreationFailed          = "ticketpro-users"
    OrderCreated                = "ticketpro-orders"
    PaymentRequested            = "ticketpro-orders"
    OrderConfirmed              = "ticketpro-orders"
    OrderCancelled              = "ticketpro-orders"
    RefundRequested             = "ticketpro-orders"
    InventoryReserved           = "ticketpro-inventory"
    InventoryRejected           = "ticketpro-inventory"
    InventoryReservationExpired = "ticketpro-inventory"
    TicketsIssued               = "ticketpro-inventory"
    PaymentApproved             = "ticketpro-payments"
    PaymentFailed               = "ticketpro-payments"
    PaymentRefunded             = "ticketpro-payments"
  }
}

variable "subscriptions" {
  description = "Consumer service -> event types it consumes (one SQS queue per service)."
  type        = map(list(string))
  default = {
    "gateway"              = ["TokenRevoked"]
    "auth-service"         = ["UserCreationFailed"]
    "user-service"         = ["UserRegistered", "OrderConfirmed", "TicketsIssued"]
    "order-service"        = ["InventoryReserved", "InventoryRejected", "InventoryReservationExpired", "PaymentApproved", "PaymentFailed", "PaymentRefunded"]
    "inventory-service"    = ["OrderCreated", "OrderConfirmed", "OrderCancelled"]
    "payment-service"      = ["PaymentRequested", "RefundRequested"]
    "notification-service" = ["TicketsIssued", "OrderCancelled"]
  }
}

variable "receipts_bucket" {
  type    = string
  default = "ticketpro-receipts"
}

variable "jwt_secret_id" {
  type    = string
  default = "ticketpro/auth/jwt-signing-key"
}

variable "jwt_key_id" {
  description = "kid of the initial JWT signing key (RFC 0002 §5.4)."
  type        = string
  default     = "2026-11-12-01"
}
