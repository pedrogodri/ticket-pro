package br.furb.ticketpro.order.platform.jobs;

import java.time.Duration;

/**
 * A batch job run periodically by {@link PeriodicJobRunner} (RFC 0004 §5.7). Each run is one
 * transaction on the worker pool and must be safe in N replicas (FOR UPDATE SKIP LOCKED).
 */
public interface PeriodicJob {

  String name();

  Duration interval();

  int batchSize();

  /** Processes at most {@link #batchSize()} rows and returns how many it processed. */
  int runOnce() throws Exception;
}
