variable "topic_by_type" {
  type = map(string)
}

variable "subscriptions" {
  type = map(list(string))
}

variable "visibility_timeout_seconds" {
  type = number
}

variable "max_receive_count" {
  type = number
}

variable "message_retention_seconds" {
  type = number
}
