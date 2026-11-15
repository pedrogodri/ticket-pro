output "topic_arns" {
  value = { for name, t in aws_sns_topic.this : name => t.arn }
}

output "queue_urls" {
  value = { for svc, q in aws_sqs_queue.this : svc => q.url }
}
