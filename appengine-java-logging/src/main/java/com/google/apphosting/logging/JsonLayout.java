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

import com.google.gson.stream.JsonWriter;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.CoreConstants;
import ch.qos.logback.core.LayoutBase;

import java.io.IOException;
import java.io.StringWriter;
import java.time.Instant;
import java.util.Map;

/**
 * A logback layout definition for JSON format output
 */
public class JsonLayout<E> extends LayoutBase<E> {

  enum Modes {
    /**
     * Legacy Conjoined Message mode.
     */
    LEGACY,
    /**
     * Modern Split JSON Keys mode.
     */
    MODERN
  }


  private Modes mode = Modes.LEGACY;

  public void setMode(Modes mode) {
    this.mode = mode;
  }

  public Modes getMode() {
    return mode;
  }

  @Override
  public String doLayout(E event) {
    if (event instanceof ILoggingEvent) {
      return doLoggingEventLayout((ILoggingEvent) event);
    } else {
      return "";
    }
  }

  public String doLoggingEventLayout(ILoggingEvent record) {
    Instant timestamp = Instant.ofEpochMilli(record.getTimeStamp());

    StringWriter out = new StringWriter();

    // Write using a simple JsonWriter rather than the more sophisticated Gson as we generally
    // will not need to serialize complex objects that require introspection and reflection.
    try (JsonWriter writer = new JsonWriter(out)) {
      // writer.setIndent("  ");
      writer.setSerializeNulls(false);
      writer.setHtmlSafe(false);

      writer.beginObject();
      writer.name("timestamp").beginObject().name("seconds").value(timestamp.getEpochSecond())
          .name("nanos").value(timestamp.getNano()).endObject();
      writer.name("severity").value(record.getLevel().toString());
      writer.name("thread").value(record.getThreadName());

      switch (mode) {
        case LEGACY:
          StringBuilder conjoinedMessage = new StringBuilder();
          if (record.getCallerData() != null) { // never null!
            conjoinedMessage.append(record.getCallerData()[0].toString());
          } else {
            // which means the logger name is lost 100% of the time
            conjoinedMessage.append(record.getLoggerName());
          }
          conjoinedMessage.append(": ");
          conjoinedMessage.append(record.getFormattedMessage());
          if (record.getThrowableProxy() != null) {
            conjoinedMessage.append(System.lineSeparator());
            conjoinedMessage.append(asThrowable(record.getThrowableProxy()));
          }

          writer.name("message").value(conjoinedMessage.toString());
          break;
        case MODERN:
          writer.name("logger").value(record.getLoggerName());
          writer.name("message").value(record.getFormattedMessage());

          if (record.getCallerData() != null) {
            writer.name("caller").value(record.getCallerData()[0].toString());
          }

          // Throwable
          IThrowableProxy throwableProxy = record.getThrowableProxy();
          if (throwableProxy != null) {
            writer.name("throwable").value(asThrowable(throwableProxy));
          }
          break;
      }

      Map<String, String> mdc = record.getMDCPropertyMap();
      if (mdc != null) {
        // treat "traceId" special - allow in top level JSON
        String traceId = mdc.remove("traceId");
        if (traceId != null) {
          writer.name("traceId").value(traceId);
        }
        // treat all other MDC entries as optional under a "mdc" map in JSON
        // this will prevent MDC usage from overwriting the core entries
        // "timestamp", "severity", "thread", "message", "throwable"
        if (mdc.size() > 0) {
          writer.beginObject();
          for (Map.Entry<String, String> mdcEntry : mdc.entrySet()) {
            writer.name(mdcEntry.getKey()).value(mdcEntry.getValue());
          }
          writer.endObject();
        }
      }
      writer.endObject();
    } catch (IOException e) {
      // Should not happen as StringWriter does not throw IOException
      throw new AssertionError(e);
    }

    out.append(System.lineSeparator());
    return out.toString();
  }

  private String asThrowable(IThrowableProxy throwableProxy) {
    StringBuilder buf = new StringBuilder(128);
    IThrowableProxy cause = throwableProxy;
    while (cause != null) {
      ThrowableProxyUtil.subjoinFirstLine(buf, cause);
      buf.append(CoreConstants.LINE_SEPARATOR);

      for (StackTraceElementProxy step : cause.getStackTraceElementProxyArray()) {
        buf.append(CoreConstants.TAB);
        buf.append(step.toString());
        buf.append(CoreConstants.LINE_SEPARATOR);
      }

      cause = cause.getCause();
    }
    return buf.toString();
  }
}
