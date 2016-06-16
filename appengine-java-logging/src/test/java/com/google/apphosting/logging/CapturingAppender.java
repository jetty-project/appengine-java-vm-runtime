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

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.UnsynchronizedAppenderBase;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

/**
 * Logback Appender that captures encoded events for later assertion against
 */
public class CapturingAppender extends UnsynchronizedAppenderBase<ILoggingEvent>
    implements Iterable<String> {
  private List<String> events = new LinkedList<>();
  private JsonLayout jsonLayout = new JsonLayout();

  @Override protected void append(ILoggingEvent eventObject) {
    events.add(jsonLayout.doLayout(eventObject));
  }

  public JsonLayout getJsonLayout() {
    return jsonLayout;
  }

  public List<String> getEvents() {
    return events;
  }

  @Override public Iterator<String> iterator() {
    return events.iterator();
  }
}
