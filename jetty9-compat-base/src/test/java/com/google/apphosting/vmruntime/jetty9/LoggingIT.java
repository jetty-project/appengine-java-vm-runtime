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

package com.google.apphosting.vmruntime.jetty9;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThat;

import com.google.gson.Gson;

import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.methods.GetMethod;
import org.eclipse.jetty.toolchain.test.PathAssert;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class LoggingIT extends VmRuntimeTestBase {

  public void testGet() throws Exception {

    //runner.dump();

    HttpClient httpClient = new HttpClient();
    httpClient.getHttpConnectionManager().getParams().setConnectionTimeout(30000);
    String query = "nano=" + Long.toHexString(System.nanoTime());
    GetMethod get = new GetMethod(createUrl("/testLogging?" + query).toString());
    int httpCode = httpClient.executeMethod(get);

    assertThat(httpCode, equalTo(200));

    String body = get.getResponseBodyAsString();
    assertThat(body, equalTo("FINE\nSEVERE\nfalse\n\n"));

    File logs = runner.getLogDir();
    File log = new File(logs, "app.0.log.json");

    PathAssert.assertFileExists("JSON Log", log);

    // Look for the log entry with our query string
    try (BufferedReader in = new BufferedReader(
        new InputStreamReader(new FileInputStream(log), StandardCharsets.UTF_8))) {
      boolean foundServletLog = false;
      int lineCount = 0;
      String line;
      while ((line = in.readLine()) != null) {
        lineCount++;
        // Look for line indicating we are in the LoggingServlet
        if (line.contains("LogTest Hello " + query)) {
          foundServletLog = true;
          break;
        }
      }

      assertThat(
          "Servlet Log search (searched " + lineCount + " lines for " + query + ") in log: " + log,
          foundServletLog, is(true));

      JsonData data = new Gson().fromJson(line, JsonData.class);
      assertThat(data.severity, is("INFO"));
      assertThat(data.message, containsString("LogTest Hello " + query));

      line = in.readLine();
      data = new Gson().fromJson(line, JsonData.class);
      assertThat(data.severity, is("ERROR"));
      assertThat(data.logger, is("com.foo.bar"));
      assertThat(data.message, containsString("not null"));

      line = in.readLine();
      data = new Gson().fromJson(line, JsonData.class);
      assertThat(data.severity, is("ERROR"));
      assertThat(data.logger, is("com.foo.bar"));
      assertThat(data.message, nullValue());
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
