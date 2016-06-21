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

import static com.google.apphosting.vmruntime.jetty9.VmRuntimeTestBase.JETTY_HOME_PATTERN;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import com.google.apphosting.jetty9.GoogleRequestCustomizer;
import com.google.apphosting.vmruntime.VmRuntimeLogging;

import org.eclipse.jetty.apache.jsp.JettyJasperInitializer;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.NCSARequestLog;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.server.handler.RequestLogHandler;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.PathAssert;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

class JettyRunner extends AbstractLifeCycle implements Runnable {

  private File logs;
  private Server server;
  private final int port;
  private final String webapp;
  private String appengineWebXml;
  private final CountDownLatch started = new CountDownLatch(1);
  private static final String[] preconfigurationClasses = {
    org.eclipse.jetty.webapp.WebInfConfiguration.class.getCanonicalName(),
    org.eclipse.jetty.webapp.WebXmlConfiguration.class.getCanonicalName(),
    org.eclipse.jetty.webapp.MetaInfConfiguration.class.getCanonicalName(),
    org.eclipse.jetty.webapp.FragmentConfiguration.class.getCanonicalName(),
    org.eclipse.jetty.plus.webapp.EnvConfiguration.class.getCanonicalName(),
    org.eclipse.jetty.plus.webapp.PlusConfiguration.class.getCanonicalName(),
    // next one is way too slow for unit testing:
    //org.eclipse.jetty.annotations.AnnotationConfiguration.class.getCanonicalName()
  };

  public JettyRunner() {
    this(-1);
  }

  public JettyRunner(int port) {
    this("webapps/testwebapp", port);
  }

  public JettyRunner(String webapp, int port) {
    this.webapp = webapp;
    this.port = port;
  }

  public File getLogDir() {
    return logs;
  }

  public void dump() {
    server.dumpStdErr();
  }

  public Server getServer() {
    return server;
  }

  public void setAppEngineWebXml(String appengineWebXml) {
    this.appengineWebXml = appengineWebXml;
  }

  public void waitForStarted(long timeout, TimeUnit units) throws InterruptedException {
    if (!started.await(timeout, units) || !server.isStarted()) {
      throw new IllegalStateException("server state=" + server.getState());
    }

    Log.getLogger(Server.class).info("Waited!");
  }

  @Override
  public void doStart() throws Exception {
    try {
      Path jettyBase = IntegrationEnv.getJettyBase();
      logs = jettyBase.resolve("logs").toFile();
      if (!logs.exists()) {
        assertThat("Unable to create directory: " + logs, logs.mkdirs(), is(true));
      }

      // Set GAE SystemProperties
      setSystemProperties(logs);

      // Create the server, connector and associated instances
      QueuedThreadPool threadpool = new QueuedThreadPool();
      server = new Server(threadpool);
      HttpConfiguration httpConfig = new HttpConfiguration();
      if (port >= 0) {
        ServerConnector connector =
            new ServerConnector(server, new HttpConnectionFactory(httpConfig));
        connector.setPort(port);
        server.addConnector(connector);
      } else {
        server.addConnector(new LocalConnector(server));
      }

      MappedByteBufferPool bufferpool = new MappedByteBufferPool();

      // Basic jetty.xml handler setup
      HandlerCollection handlers = new HandlerCollection();
      // TODO is a context handler collection needed for a single context?
      ContextHandlerCollection contexts = new ContextHandlerCollection();
      handlers.setHandlers(new Handler[] {contexts, new DefaultHandler()});
      server.setHandler(handlers);

      // Configuration as done by gae.mod/gae.ini
      httpConfig.setOutputAggregationSize(32768);

      threadpool.setMinThreads(10);
      threadpool.setMaxThreads(500);
      threadpool.setIdleTimeout(60000);

      httpConfig.setOutputBufferSize(32768);
      httpConfig.setRequestHeaderSize(8192);
      httpConfig.setResponseHeaderSize(8192);
      httpConfig.setSendServerVersion(true);
      httpConfig.setSendDateHeader(false);
      httpConfig.setDelayDispatchUntilContent(false);
      GoogleRequestCustomizer requestCustomizer =
          new GoogleRequestCustomizer(port, 443);
      httpConfig.addCustomizer(requestCustomizer);

      // Setup Server as done by gae.xml
      server.addBean(bufferpool);

      httpConfig.setHeaderCacheSize(512);

      RequestLogHandler requestLogHandler = new RequestLogHandler();
      handlers.addHandler(requestLogHandler);

      NCSARequestLog requestLog =
          new NCSARequestLog(logs.getCanonicalPath() + "/request.yyyy_mm_dd.log");
      requestLogHandler.setRequestLog(requestLog);
      requestLog.setRetainDays(2);
      requestLog.setAppend(true);
      requestLog.setExtended(true);
      requestLog.setLogTimeZone("GMT");
      requestLog.setLogLatency(true);
      requestLog.setPreferProxiedForAddress(true);

      // configuration from root.xml
      final VmRuntimeWebAppContext context = new VmRuntimeWebAppContext();
      context.setContextPath("/");
      context.setConfigurationClasses(preconfigurationClasses);

      // Needed to initialize JSP!
      context.addBean(
          new AbstractLifeCycle() {
            @Override
            public void doStop() throws Exception {}

            @Override
            public void doStart() throws Exception {
              JettyJasperInitializer jspInit = new JettyJasperInitializer();
              jspInit.onStartup(Collections.emptySet(), context.getServletContext());
            }
          },
          true);

      // find the sibling testwebapp target
      File webAppLocation = MavenTestingUtils.getTargetFile(webapp);
      PathAssert.assertDirExists("webapp dir", webAppLocation);

      Path logging = webAppLocation.toPath().resolve("WEB-INF/logback.xml");
      System.setProperty(
          VmRuntimeLogging.LOGGING_CONFIGURATION_KEY, logging.toRealPath().toString());

      PathAssert.assertDirExists("WebAppLocation", webAppLocation);

      context.setResourceBase(webAppLocation.getAbsolutePath());
      context.init((appengineWebXml == null ? "WEB-INF/appengine-web.xml" : appengineWebXml));
      context.setParentLoaderPriority(true); // true in tests for easier mocking

      // Hack to find the webdefault.xml
      File webDefault = jettyBase.resolve("etc/webdefault.xml").toFile();
      context.setDefaultsDescriptor(webDefault.getAbsolutePath());

      contexts.addHandler(context);
      // start and join
      server.start();
    } finally {
      Log.getLogger(Server.class).info("Started!");
      started.countDown();
    }
  }

  @Override
  public void doStop() throws Exception {
    server.stop();
  }

  @Override
  public void run() {
    try {
      start();
      if (Log.getLogger(Server.class).isDebugEnabled()) {
        server.dumpStdErr();
      }
      server.join();
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  /**
   * Sets the system properties expected by jetty.xml.
   */
  protected void setSystemProperties(File logs) throws IOException {
    System.setProperty("jetty.appengineport", String.valueOf(findAvailablePort()));
    System.setProperty("jetty.appenginehost", "localhost");
    System.setProperty("jetty.appengine.forwarded", "true");
    System.setProperty("jetty.home", JETTY_HOME_PATTERN);
  }

  public static int findAvailablePort() {
    try (ServerSocket tempSocket = new ServerSocket(0)) {
      return tempSocket.getLocalPort();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  public static void main(String... args) throws Exception {
    TestMetadataServer meta = new TestMetadataServer();
    try {
      meta.start();
      new JettyRunner(8080).run();
    } finally {
      meta.stop();
    }
  }
}
