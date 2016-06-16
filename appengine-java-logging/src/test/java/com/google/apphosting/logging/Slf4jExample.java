package com.google.apphosting.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ThreadLocalRandom;

public class Slf4jExample implements Runnable {
  private static final Logger LOG = LoggerFactory.getLogger(Slf4jExample.class);

  @Override public void run() {
    ThreadLocalRandom rand = ThreadLocalRandom.current();
    LOG.trace("A Slf4j Trace Event: {}", rand.nextInt());
    LOG.debug("A Slf4j Debug Event: {}", rand.nextInt());
    LOG.info("A Slf4j Info Event: {}", rand.nextInt());
    LOG.warn("A Slf4j Warn Event: {}", rand.nextInt());
    LOG.error(String.format("A Slf4j Error Event: %d", rand.nextInt()),
        new RuntimeException("Generic Error"));
  }
}
