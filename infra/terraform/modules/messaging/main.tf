locals {
  topics = toset(values(var.topic_by_type))

  # One subscription per (service, source topic), filtering on the `type` message attribute.
  subscription_pairs = merge([
    for svc, types in var.subscriptions : {
      for topic in distinct([for t in types : var.topic_by_type[t]]) :
      "${svc}|${topic}" => {
        service = svc
        topic   = topic
        types   = sort([for t in types : t if var.topic_by_type[t] == topic])
      }
    }
  ]...)
}

resource "aws_sns_topic" "this" {
  for_each = local.topics
  name     = each.value
}

resource "aws_sqs_queue" "dlq" {
  for_each                  = var.subscriptions
  name                      = "ingressoja-${each.key}-dlq"
  message_retention_seconds = var.message_retention_seconds
}

resource "aws_sqs_queue" "this" {
  for_each                   = var.subscriptions
  name                       = "ingressoja-${each.key}"
  visibility_timeout_seconds = var.visibility_timeout_seconds
  message_retention_seconds  = var.message_retention_seconds

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.dlq[each.key].arn
    maxReceiveCount     = var.max_receive_count
  })
}

resource "aws_sqs_queue_policy" "allow_sns" {
  for_each  = var.subscriptions
  queue_url = aws_sqs_queue.this[each.key].id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "sns.amazonaws.com" }
      Action    = "sqs:SendMessage"
      Resource  = aws_sqs_queue.this[each.key].arn
      Condition = {
        ArnEquals = {
          "aws:SourceArn" = [for p in values(local.subscription_pairs) : aws_sns_topic.this[p.topic].arn if p.service == each.key]
        }
      }
    }]
  })
}

resource "aws_sns_topic_subscription" "this" {
  for_each      = local.subscription_pairs
  topic_arn     = aws_sns_topic.this[each.value.topic].arn
  protocol      = "sqs"
  endpoint      = aws_sqs_queue.this[each.value.service].arn
  filter_policy = jsonencode({ type = each.value.types })
}
