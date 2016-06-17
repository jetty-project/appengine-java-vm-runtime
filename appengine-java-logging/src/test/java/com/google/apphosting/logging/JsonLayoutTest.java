/*
 * Copyright 2016 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.apphosting.logging;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertThat;

import com.google.gson.Gson;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.jul.LevelChangePropagator;
import ch.qos.logback.classic.spi.LoggerContextListener;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.slf4j.bridge.SLF4JBridgeHandler;
import org.slf4j.impl.StaticLoggerBinder;

import java.time.Instant;
import java.util.List;
import java.util.logging.Level;

public class JsonLayoutTest {
  LoggerContext rootContext;
  CapturingAppender capturingAppender;

  @Before
  public void initLogging() {
    // Add slf4j -> JUL bridge
    SLF4JBridgeHandler.removeHandlersForRootLogger();
    SLF4JBridgeHandler.install();

    rootContext = (LoggerContext) StaticLoggerBinder.getSingleton().getLoggerFactory();

    // Establish JUL level propgator
    boolean hasJulPropagator = false;
    for (LoggerContextListener listener : rootContext.getCopyOfListenerList()) {
      if (listener instanceof LevelChangePropagator) {
        hasJulPropagator = true;
      }
    }

    if (!hasJulPropagator) {
      LevelChangePropagator julPropagator = new LevelChangePropagator();
      julPropagator.setResetJUL(true);
      rootContext.addListener(julPropagator);
    }

    // Add root level test appender
    Logger rootLogger = rootContext.getLogger(Logger.ROOT_LOGGER_NAME);
    rootLogger.detachAndStopAllAppenders();

    capturingAppender = new CapturingAppender();
    capturingAppender.getJsonLayout().setMode(JsonLayout.Modes.MODERN);
    capturingAppender.setContext(rootContext);
    capturingAppender.setName("capturing");
    capturingAppender.start();

    rootLogger.addAppender(capturingAppender);

    // Define root level severity level (for tests)
    rootContext.getLogger("com.google.apphosting.logging")
        .setLevel(ch.qos.logback.classic.Level.DEBUG);
  }

  @After
  public void deinitLogging() {
    SLF4JBridgeHandler.uninstall();
    Logger rootLogger = rootContext.getLogger(Logger.ROOT_LOGGER_NAME);
    capturingAppender.stop();
    rootLogger.detachAppender("capturing");
  }

  private String getCapturedMessage() {
    List<String> events = capturingAppender.getEvents();
    assertThat("Events", events.size(), is(1));
    JsonData data = new Gson().fromJson(events.get(0), JsonData.class);
    return data.message;
  }

  @Test
  @Ignore("Only valid when using JsonLayout.Modes.LEGACY")
  public void testJavaUtilLogIncludesLoggerName() throws Exception {
    java.util.logging.Logger logger = java.util.logging.Logger.getLogger("logger");
    logger.info("message");

    String capturedMessage = getCapturedMessage();
    assertThat(capturedMessage, is("logger: message"));
  }

  @Test
  @Ignore("Only valid when using JsonLayout.Modes.LEGACY")
  public void messageIncludesClassName() throws Exception {
    java.util.logging.Logger logger = java.util.logging.Logger.getLogger("logger");
    logger.logp(Level.INFO, "class", null, "message"); // does not propagate

    assertThat(getCapturedMessage(), is("class: message"));
  }

  @Test
  @Ignore("Only valid when using JsonLayout.Modes.LEGACY")
  public void messageIncludesMethodName() throws Exception {
    java.util.logging.Logger logger = java.util.logging.Logger.getLogger("logger");
    logger.logp(Level.INFO, "class", "method", "message"); // does not propagate

    assertThat(getCapturedMessage(), is("class method: message"));
  }

  @Test
  @Ignore("Only valid when using JsonLayout.Modes.LEGACY")
  public void messageIncludesStackTrace() throws Exception {
    java.util.logging.Logger logger = java.util.logging.Logger.getLogger("logger");
    logger.log(Level.WARNING, "message", new Throwable("thrown"));

    String capturedMessage = getCapturedMessage();
    assertThat(capturedMessage, containsString("logger: message" + System.lineSeparator()));
    assertThat(capturedMessage,
        containsString("java.lang.Throwable: thrown" + System.lineSeparator()));
    assertThat(capturedMessage, containsString(
        "\tat " + getClass().getName() + ".messageIncludesStackTrace" + System.lineSeparator()));
  }

  @Test
  public void testCommonsLogging() {
    final Instant now = Instant.now();
    final String expectedThreadName = Thread.currentThread().getName();

    // Generate Events from Commons Logging
    new CommonsLoggingExample().run();

    // Verify that event was captured by logback
    String[][] expected = new String[][] {{"DEBUG", "A CommonsLogging Debug Event: "},
        {"INFO", "A CommonsLogging Info Event: "}, {"WARN", "A CommonsLogging Warn Event: "},
        {"ERROR", "A CommonsLogging Error Event: "}};

    List<String> events = capturingAppender.getEvents();
    assertThat("Events", events.size(), is(expected.length));

    for (int i = 0; i < events.size(); i++) {
      String logLine = events.get(i);
      System.out.printf("logLine[%d] = %s", i, logLine);
      JsonData data = new Gson().fromJson(logLine, JsonData.class);
      assertThat("severity", data.severity, is(expected[i][0]));
      assertThat("timestamp.seconds", data.timestamp.seconds,
          greaterThanOrEqualTo(now.getEpochSecond()));
      assertThat("timestamp.nanos", data.timestamp.nanos, greaterThanOrEqualTo(now.getNano()));
      assertThat("thread name", data.thread, is(expectedThreadName));
      assertThat("logger message", data.message, startsWith(expected[i][1]));
      if (data.severity.equals("ERROR")) {
        assertThat("throwable", data.throwable,
            startsWith("java.lang.RuntimeException: Generic Error"));
      }
    }
  }

  @Test
  public void testJavaUtilLogging() {
    final Instant now = Instant.now();
    final String expectedThreadName = Thread.currentThread().getName();

    // Generate Events from java.util.logging
    new JulExample().run();

    // Verify that event was captured by logback
    String[][] expected =
        new String[][] {{"DEBUG", "A JUL Fine Event: "}, {"INFO", "A JUL Config Event: "},
            {"INFO", "A JUL Info Event: "}, {"WARN", "A JUL Warning Event: "},
            {"ERROR", "A JUL Severe Event: "}};

    List<String> events = capturingAppender.getEvents();
    assertThat("Events", events.size(), is(expected.length));

    for (int i = 0; i < events.size(); i++) {
      String logLine = events.get(i);
      System.out.printf("logLine[%d] = %s", i, logLine);
      JsonData data = new Gson().fromJson(logLine, JsonData.class);
      assertThat("severity", data.severity, is(expected[i][0]));
      assertThat("timestamp.seconds", data.timestamp.seconds,
          greaterThanOrEqualTo(now.getEpochSecond()));
      assertThat("timestamp.nanos", data.timestamp.nanos, greaterThanOrEqualTo(now.getNano()));
      assertThat("thread name", data.thread, is(expectedThreadName));
      assertThat("logger message", data.message, startsWith(expected[i][1]));
      if (data.severity.equals("ERROR")) {
        assertThat("throwable", data.throwable,
            startsWith("java.lang.RuntimeException: Generic Error"));
      }
    }
  }

  @Test
  public void testLog4jLogging() {
    final Instant now = Instant.now();
    final String expectedThreadName = Thread.currentThread().getName();

    // Generate Events from Apache Log4j Logging
    new Log4jExample().run();

    // Verify that event was captured by logback
    String[][] expected =
        new String[][] {{"DEBUG", "A Log4j Debug Event: "}, {"INFO", "A Log4j Info Event: "},
            {"WARN", "A Log4j Warn Event: "}, {"ERROR", "A Log4j Error Event: "}};

    List<String> events = capturingAppender.getEvents();
    assertThat("Events", events.size(), is(expected.length));

    for (int i = 0; i < events.size(); i++) {
      String logLine = events.get(i);
      System.out.printf("logLine[%d] = %s", i, logLine);
      JsonData data = new Gson().fromJson(logLine, JsonData.class);
      assertThat("severity", data.severity, is(expected[i][0]));
      assertThat("timestamp.seconds", data.timestamp.seconds,
          greaterThanOrEqualTo(now.getEpochSecond()));
      assertThat("timestamp.nanos", data.timestamp.nanos, greaterThanOrEqualTo(now.getNano()));
      assertThat("thread name", data.thread, is(expectedThreadName));
      assertThat("logger message", data.message, startsWith(expected[i][1]));
      if (data.severity.equals("ERROR")) {
        assertThat("throwable", data.throwable,
            startsWith("java.lang.RuntimeException: Generic Error"));
      }
    }
  }

  @Test
  public void testSlf4jLogging() {
    final Instant now = Instant.now();
    final String expectedThreadName = Thread.currentThread().getName();

    // Generate Events from Slf4j Logging
    new Slf4jExample().run();

    // Verify that event was captured by logback
    String[][] expected =
        new String[][] {{"DEBUG", "A Slf4j Debug Event: "}, {"INFO", "A Slf4j Info Event: "},
            {"WARN", "A Slf4j Warn Event: "}, {"ERROR", "A Slf4j Error Event: "}};

    List<String> events = capturingAppender.getEvents();
    assertThat("Events", events.size(), is(expected.length));

    for (int i = 0; i < events.size(); i++) {
      String logLine = events.get(i);
      System.out.printf("logLine[%d] = %s", i, logLine);
      JsonData data = new Gson().fromJson(logLine, JsonData.class);
      assertThat("severity", data.severity, is(expected[i][0]));
      assertThat("timestamp.seconds", data.timestamp.seconds,
          greaterThanOrEqualTo(now.getEpochSecond()));
      assertThat("timestamp.nanos", data.timestamp.nanos, greaterThanOrEqualTo(now.getNano()));
      assertThat("thread name", data.thread, is(expectedThreadName));
      assertThat("logger message", data.message, startsWith(expected[i][1]));
      if (data.severity.equals("ERROR")) {
        assertThat("throwable", data.throwable,
            startsWith("java.lang.RuntimeException: Generic Error"));
      }
    }
  }

  // Something that JSON can parser the JSON into
  public static class JsonData {
    public static class LogTimestamp {
      public long seconds;
      public int nanos;
    }

    public LogTimestamp timestamp;
    public String severity;
    public String thread;
    public String message;
    public String logger;
    public String caller;
    public String traceId;
    public String throwable;
  }
}
