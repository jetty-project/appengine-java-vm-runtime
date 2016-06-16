package com.google.apphosting.logging;

import org.apache.log4j.Logger;

import java.util.concurrent.ThreadLocalRandom;

public class Log4jExample implements Runnable {
  private static final Logger LOG = Logger.getLogger(Log4jExample.class);

  @Override public void run() {
    ThreadLocalRandom rand = ThreadLocalRandom.current();
    LOG.trace(String.format("A Log4j Trace Event: %d", rand.nextInt()));
    LOG.debug(String.format("A Log4j Debug Event: %d", rand.nextInt()));
    LOG.info(String.format("A Log4j Info Event: %d", rand.nextInt()));
    LOG.warn(String.format("A Log4j Warn Event: %d", rand.nextInt()));
    LOG.error(String.format("A Log4j Error Event: %d", rand.nextInt()),
        new RuntimeException("Generic Error"));
  }
}
