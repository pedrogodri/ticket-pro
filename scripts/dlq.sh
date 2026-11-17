#!/usr/bin/env bash
# Dead-letter queues (RFC 0003 §5.4): list counts, show messages, redrive them to the source queue.
# Usage: scripts/dlq.sh list
#        scripts/dlq.sh show <service>      (peek, nothing is removed)
#        scripts/dlq.sh redrive <service>   (moves every message back to ticketpro-<service>)
set -uo pipefail
cd "$(dirname "$0")/.."
. scripts/lib/aws.sh; load_env
BASE_URL="${SQS_BASE_URL:-http://ministack:4566/000000000000}"

case "${1:-}" in
  list)
    for url in $(awsl sqs list-queues | jq -r '.QueueUrls[]? | select(endswith("-dlq"))'); do
      n=$(awsl sqs get-queue-attributes --queue-url "$url" --attribute-names ApproximateNumberOfMessages | jq -r '.Attributes.ApproximateNumberOfMessages')
      printf '%-45s %s\n' "${url##*/}" "$n"
    done ;;
  show)
    [ -n "${2:-}" ] || { echo "usage: $0 show <service>"; exit 2; }
    awsl sqs receive-message --queue-url "$BASE_URL/ticketpro-$2-dlq" --max-number-of-messages 10 --visibility-timeout 0 \
      --attribute-names ApproximateReceiveCount \
      | jq -r '.Messages[]? | (.Body | (fromjson? // .) | (.Message? // .) | (fromjson? // .)) as $e
          | "\($e.type // "?") v\($e.version // "?") messageId=\($e.messageId // "?") payload=\($e.payload // $e | tostring | .[0:160])"' ;;
  redrive)
    [ -n "${2:-}" ] || { echo "usage: $0 redrive <service>"; exit 2; }
    dlq="$BASE_URL/ticketpro-$2-dlq"; src="$BASE_URL/ticketpro-$2"; moved=0
    # Only the messages present now: one that fails again comes back to the DLQ and must not loop forever.
    total=$(awsl sqs get-queue-attributes --queue-url "$dlq" --attribute-names ApproximateNumberOfMessages | jq -r '.Attributes.ApproximateNumberOfMessages')
    while [ "$moved" -lt "$total" ]; do
      batch=$(awsl sqs receive-message --queue-url "$dlq" --max-number-of-messages 10 --visibility-timeout 30 --message-attribute-names All)
      [ "$(echo "$batch" | jq '.Messages | length // 0')" -gt 0 ] || break
      while read -r m; do
        # Raw delivery keeps type/version/traceparent in the SQS message attributes: send them back too.
        attrs=$(echo "$m" | jq -c '(.MessageAttributes // {}) | with_entries(.value |= {DataType, StringValue})')
        awsl sqs send-message --queue-url "$src" --message-body "$(echo "$m" | jq -r .Body)" \
          $( [ "$attrs" != "{}" ] && printf -- '--message-attributes %s' "$attrs" ) >/dev/null &&
          awsl sqs delete-message --queue-url "$dlq" --receipt-handle "$(echo "$m" | jq -r .ReceiptHandle)" >/dev/null &&
          moved=$((moved + 1))
      done < <(echo "$batch" | jq -c --argjson left $((total - moved)) '.Messages[:$left][]')
    done
    echo "redrove $moved message(s) from ticketpro-$2-dlq to ticketpro-$2" ;;
  *) echo "usage: $0 list | show <service> | redrive <service>"; exit 2 ;;
esac
