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

import com.google.appengine.api.datastore.DatastoreService;
import com.google.appengine.api.datastore.DatastoreServiceFactory;
import com.google.appengine.api.datastore.Transaction;
import com.google.appengine.api.memcache.MemcacheSerialization;
import com.google.appengine.spi.ServiceFactoryFactory;
import com.google.apphosting.api.ApiProxy;
import com.google.apphosting.logging.LogContext;
import com.google.apphosting.runtime.DatastoreSessionStore;
import com.google.apphosting.runtime.DeferredDatastoreSessionStore;
import com.google.apphosting.runtime.MemcacheSessionStore;
import com.google.apphosting.runtime.SessionStore;
import com.google.apphosting.runtime.jetty9.NoOpSessionManager;
import com.google.apphosting.runtime.jetty9.SessionManager;
import com.google.apphosting.runtime.jetty9.SessionManager.AppEngineSession;
import com.google.apphosting.runtime.timer.Timer;
import com.google.apphosting.utils.config.AppEngineConfigException;
import com.google.apphosting.utils.config.AppEngineWebXml;
import com.google.apphosting.utils.config.AppEngineWebXmlReader;
import com.google.apphosting.utils.servlet.HttpServletRequestAdapter;
import com.google.apphosting.vmruntime.VmApiProxyDelegate;
import com.google.apphosting.vmruntime.VmApiProxyEnvironment;
import com.google.apphosting.vmruntime.VmEnvironmentFactory;
import com.google.apphosting.vmruntime.VmMetadataCache;
import com.google.apphosting.vmruntime.VmRequestUtils;
import com.google.apphosting.vmruntime.VmRuntimeFileLogHandler;
import com.google.apphosting.vmruntime.VmRuntimeUtils;
import com.google.apphosting.vmruntime.VmTimer;

import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.quickstart.PreconfigureDescriptorProcessor;
import org.eclipse.jetty.quickstart.QuickStartDescriptorGenerator;
import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.server.HttpOutput;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.session.AbstractSessionManager;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.log.JavaUtilLog;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.WebAppContext;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.ServletRequest;
import javax.servlet.ServletRequestEvent;
import javax.servlet.ServletRequestListener;
import javax.servlet.http.HttpSession;

/**
 * WebAppContext for VM Runtimes. This class extends the "normal" AppEngineWebAppContext with
 * functionality that installs a request specific thread local environment on each incoming
 * request.
 */
public class VmRuntimeWebAppContext extends WebAppContext
    implements VmRuntimeTrustedAddressChecker {

  private static final Logger logger = Logger.getLogger(VmRuntimeWebAppContext.class.getName());

  // It's undesirable to have the user app override classes provided by us.
  // So we mark them as Jetty system classes, which cannot be overridden.
  private static final String[] SYSTEM_CLASSES = {
    // The trailing dot means these are all Java packages, not individual classes.
    "com.google.appengine.api.",
    "com.google.appengine.tools.",
    "com.google.apphosting.",
    "com.google.cloud.sql.jdbc.",
    "com.google.protos.cloud.sql.",
    "com.google.storage.onestore.",
  };
  // constant. If it's much larger than this we may need to
  // restructure the code a bit.
  protected static final int MAX_RESPONSE_SIZE = 32 * 1024 * 1024;

  private final VmMetadataCache metadataCache;
  private final Timer wallclockTimer;
  private VmApiProxyEnvironment defaultEnvironment;
  private DatastoreService datastoreService;
  private String quickstartWebXml;
  private PreconfigureDescriptorProcessor preconfigProcessor;

  // Indicates if the context is running via the Cloud SDK, or the real runtime.

  boolean isDevMode;

  static {
    // Set SPI classloader priority to prefer the WebAppClassloader.
    System.setProperty(
        ServiceFactoryFactory.USE_THREAD_CONTEXT_CLASSLOADER_PROPERTY, Boolean.TRUE.toString());
    // Use thread context class loader for memcache deserialization.
    System.setProperty(
        MemcacheSerialization.USE_THREAD_CONTEXT_CLASSLOADER_PROPERTY, Boolean.TRUE.toString());
  }

  // List of Jetty configuration only needed if the quickstart process has been
  // executed, so we do not need the webinf, wedxml, fragment and annotation configurations
  // because they have been executed via the SDK.
  private static final String[] quickstartConfigurationClasses = {
    org.eclipse.jetty.quickstart.QuickStartConfiguration.class.getCanonicalName(),
    org.eclipse.jetty.plus.webapp.EnvConfiguration.class.getCanonicalName(),
    org.eclipse.jetty.plus.webapp.PlusConfiguration.class.getCanonicalName(),
    org.eclipse.jetty.webapp.JettyWebXmlConfiguration.class.getCanonicalName()
  };

  // List of all the standard Jetty configurations that need to be executed when there
  // is no quickstart-web.xml.
  private static final String[] preconfigurationClasses = {
    org.eclipse.jetty.webapp.WebInfConfiguration.class.getCanonicalName(),
    org.eclipse.jetty.webapp.WebXmlConfiguration.class.getCanonicalName(),
    org.eclipse.jetty.webapp.MetaInfConfiguration.class.getCanonicalName(),
    org.eclipse.jetty.webapp.FragmentConfiguration.class.getCanonicalName(),
    org.eclipse.jetty.plus.webapp.EnvConfiguration.class.getCanonicalName(),
    org.eclipse.jetty.plus.webapp.PlusConfiguration.class.getCanonicalName(),
    org.eclipse.jetty.annotations.AnnotationConfiguration.class.getCanonicalName()
  };

  public String getQuickstartWebXml() {
    return quickstartWebXml;
  }

  public VmMetadataCache getMetadataCache() {
    return metadataCache;
  }

  /**
   * Set the quickstart WebXml.
   *
   * <p> If set, this context will not start, rather it will generate the
   * quickstart-web.xml file and then stop the server. If not set, the context will start normally
   * </p>
   *
   * @param quickstartWebXml The location of the quickstart web.xml to generate
   */
  public void setQuickstartWebXml(String quickstartWebXml) {
    if (quickstartWebXml != null && quickstartWebXml.length() == 0) {
      quickstartWebXml = null;
    }
    this.quickstartWebXml = quickstartWebXml;
  }

  @Override
  protected void doStart() throws Exception {
    // unpack and Adjust paths.
    Resource base = getBaseResource();
    if (base == null) {
      String war = getWar();
      if (war == null) {
        throw new IllegalStateException("No war");
      }
      base = Resource.newResource(getWar());
    }
    Resource dir;
    if (base.isDirectory()) {
      dir = base;
    } else {
      throw new IllegalArgumentException("Bad base:" + base);
    }
    Resource qswebxml = dir.addPath("/WEB-INF/quickstart-web.xml");
    if (qswebxml.exists()) {
      setConfigurationClasses(quickstartConfigurationClasses);
    }

    datastoreService = getDatastoreService();

    if (quickstartWebXml == null) {
      addEventListener(new ContextListener());
    } else {
      getMetaData()
          .addDescriptorProcessor(preconfigProcessor = new PreconfigureDescriptorProcessor());
    }

    super.doStart();
  }

  @Override
  protected void startWebapp() throws Exception {
    if (quickstartWebXml == null) {
      super.startWebapp();
    } else {
      logger.info("Generating quickstart web.xml: " + quickstartWebXml);
      Resource descriptor = Resource.newResource(quickstartWebXml);
      if (descriptor.exists()) {
        descriptor.delete();
      }
      descriptor.getFile().createNewFile();
      QuickStartDescriptorGenerator generator =
          new QuickStartDescriptorGenerator(this, preconfigProcessor.getXML());
      try (FileOutputStream fos = new FileOutputStream(descriptor.getFile())) {
        generator.generateQuickStartWebXml(fos);
      } finally {
        System.exit(0);
      }
    }
  }

  @Override
  protected void stopWebapp() throws Exception {
    if (quickstartWebXml == null) {
      super.stopWebapp();
    }
  }

  /**
   * Broken out to facilitate testing.
   */
  DatastoreService getDatastoreService() {
    return DatastoreServiceFactory.getDatastoreService();
  }

  /**
   * Creates a List of SessionStores based on the configuration in the provided AppEngineWebXml.
   *
   * @param appEngineWebXml The AppEngineWebXml containing the session configuration.
   * @return A List of SessionStores in write order.
   */
  private static List<SessionStore> createSessionStores(AppEngineWebXml appEngineWebXml) {
    DatastoreSessionStore datastoreSessionStore =
        appEngineWebXml.getAsyncSessionPersistence()
            ? new DeferredDatastoreSessionStore(
                appEngineWebXml.getAsyncSessionPersistenceQueueName())
            : new DatastoreSessionStore();
    // Write session data to the datastore before we write to memcache.
    return Arrays.asList(datastoreSessionStore, new MemcacheSessionStore());
  }

  /**
   * Checks if the request was made over HTTPS. If so it modifies the request so that {@code
   * HttpServletRequest#isSecure()} returns true, {@code HttpServletRequest#getScheme()} returns
   * "https", and {@code HttpServletRequest#getServerPort()} returns 443. Otherwise it sets the
   * scheme to "http" and port to 80.
   *
   * @param request The request to modify.
   */
  private void setSchemeAndPort(Request request) {
    String https = request.getHeader(VmApiProxyEnvironment.HTTPS_HEADER);
    if ("on".equals(https)) {
      request.setSecure(true);
      request.setScheme(HttpScheme.HTTPS.toString());
      request.setAuthority(request.getServerName(), 443);
    } else {
      request.setSecure(false);
      request.setScheme(HttpScheme.HTTP.toString());
      request.setAuthority(request.getServerName(), defaultEnvironment.getServerPort());
    }
  }

  /**
   * Creates a new VmRuntimeWebAppContext.
   */
  public VmRuntimeWebAppContext() {
    setServerInfo(VmRuntimeUtils.getServerInfo());
    setLogger(new JavaUtilLog(VmRuntimeWebAppContext.class.getName()));

    // Configure the Jetty SecurityHandler to understand our method of authentication
    // (via the UserService). Only the default ConstraintSecurityHandler is supported.
    AppEngineAuthentication.configureSecurityHandler(
        (ConstraintSecurityHandler) getSecurityHandler(), this);

    setMaxFormContentSize(MAX_RESPONSE_SIZE);
    setConfigurationClasses(preconfigurationClasses);
    // See
    // http://www.eclipse.org/jetty/documentation/current/configuring-webapps.html#webapp-context-attributes
    // We also want the Jetty container libs to be scanned for annotations.
    setAttribute("org.eclipse.jetty.server.webapp.ContainerIncludeJarPattern", ".*\\.jar");
    metadataCache = new VmMetadataCache();
    wallclockTimer = new VmTimer();
    ApiProxy.setDelegate(new VmApiProxyDelegate());
  }

  /**
   * Initialize the WebAppContext for use by the VmRuntime.
   *
   * This method initializes the WebAppContext by setting the context path and application folder.
   * It will also parse the appengine-web.xml file provided to set System Properties and session
   * manager accordingly.
   *
   * @param appengineWebXmlFile The appengine-web.xml file path (relative to appDir).
   * @throws AppEngineConfigException If there was a problem finding or parsing the
   *                                  appengine-web.xml configuration.
   * @throws IOException              If the runtime was unable to find/read appDir.
   */
  public void init(String appengineWebXmlFile) throws AppEngineConfigException, IOException {
    String appDir = getBaseResource().getFile().getCanonicalPath();
    defaultEnvironment =
        VmApiProxyEnvironment.createDefaultContext(
            System.getenv(),
            metadataCache,
            VmRuntimeUtils.getApiServerAddress(),
            wallclockTimer,
            VmRuntimeUtils.ONE_DAY_IN_MILLIS,
            appDir);
    ApiProxy.setEnvironmentForCurrentThread(defaultEnvironment);
    if (ApiProxy.getEnvironmentFactory() == null) {
      // Need the check above since certain unit tests initialize the context multiple times.
      ApiProxy.setEnvironmentFactory(new VmEnvironmentFactory(defaultEnvironment));
    }

    isDevMode = "dev".equals(defaultEnvironment.getPartition());
    AppEngineWebXml appEngineWebXml = null;
    File appWebXml = new File(appDir, appengineWebXmlFile);
    if (appWebXml.exists()) {
      AppEngineWebXmlReader appEngineWebXmlReader =
          new AppEngineWebXmlReader(appDir, appengineWebXmlFile);
      appEngineWebXml = appEngineWebXmlReader.readAppEngineWebXml();
    }
    VmRuntimeUtils.installSystemProperties(defaultEnvironment, appEngineWebXml);
    String logConfig = System.getProperty("java.util.logging.config.file");
    if (logConfig != null && logConfig.startsWith("WEB-INF/")) {
      System.setProperty("java.util.logging.config.file", URIUtil.addPaths(appDir, logConfig));
    }
    VmRuntimeFileLogHandler.init();

    for (String systemClass : SYSTEM_CLASSES) {
      addSystemClass(systemClass);
    }
    if (appEngineWebXml == null) {
      // No need to configure the session manager.
      return;
    }
    org.eclipse.jetty.server.SessionManager sessionManager;
    if (appEngineWebXml.getSessionsEnabled()) {
      sessionManager = new SessionManager(createSessionStores(appEngineWebXml));
    } else {
      sessionManager = new NoOpSessionManager();
    }
    getSessionHandler().setSessionManager(sessionManager);

    VmRuntimeInterceptor.init(appEngineWebXml);
  }



  @Override
  public boolean isTrustedRemoteAddr(String remoteAddr) {
    return VmRequestUtils.isTrustedRemoteAddr(isDevMode, remoteAddr);
  }

  public RequestContext getRequestContext(Request baseRequest) {
    if (baseRequest == null) {
      return null;
    }
    RequestContext requestContext =
        (RequestContext) baseRequest.getAttribute(RequestContext.class.getName());
    if (requestContext == null) {
      // No instance found, so create a new environment
      requestContext = new RequestContext(baseRequest);
      baseRequest.setAttribute(RequestContext.class.getName(), requestContext);
    }
    return requestContext;
  }

  class RequestContext extends HttpServletRequestAdapter {
    private final VmApiProxyEnvironment requestSpecificEnvironment;

    RequestContext(Request request) {
      super(request);
      requestSpecificEnvironment =
          VmApiProxyEnvironment.createFromHeaders(
              System.getenv(),
              metadataCache,
              this,
              VmRuntimeUtils.getApiServerAddress(),
              wallclockTimer,
              VmRuntimeUtils.ONE_DAY_IN_MILLIS,
              defaultEnvironment);
      if (requestSpecificEnvironment.isRequestTicket()) {
        final HttpOutput httpOutput = request.getResponse().getHttpOutput();
        final HttpOutput.Interceptor nextOutput = httpOutput.getInterceptor();
        httpOutput.setInterceptor(new VmRuntimeInterceptor(requestSpecificEnvironment, nextOutput));
      }
    }

    VmApiProxyEnvironment getRequestSpecificEnvironment() {
      return requestSpecificEnvironment;
    }

    @Override
    public String toString() {
      return String.format(
          "RequestContext@%x %s==%s",
          hashCode(), request.getRequestURI(), requestSpecificEnvironment);
    }
  }

  public class ContextListener
      implements ContextHandler.ContextScopeListener, ServletRequestListener {
    @Override
    public void enterScope(
        org.eclipse.jetty.server.handler.ContextHandler.Context context,
        Request baseRequest,
        Object reason) {
      RequestContext requestContext = getRequestContext(baseRequest);
      if (requestContext == null) {
        logger.fine("enterScope no request");
      } else {
        VmApiProxyEnvironment environment = requestContext.getRequestSpecificEnvironment();
        String traceId = environment.getTraceId();
        if (traceId != null) {
          LogContext.current().put("traceId", environment.getTraceId());
        }
        if (logger.isLoggable(Level.FINE)) {
          logger.fine("enterScope " + requestContext);
        }
        ApiProxy.setEnvironmentForCurrentThread(environment);
      }
    }

    @Override
    public void requestInitialized(ServletRequestEvent sre) {
      ServletRequest request = sre.getServletRequest();
      Request baseRequest = Request.getBaseRequest(request);

      RequestContext requestContext = getRequestContext(baseRequest);
      if (logger.isLoggable(Level.FINE)) {
        logger.fine("requestInitialized " + requestContext);
      }

      // Check for SkipAdminCheck and set attributes accordingly.
      VmRuntimeUtils.handleSkipAdminCheck(requestContext);

      // Change scheme to HTTPS based on headers set by the appserver.
      setSchemeAndPort(baseRequest);
    }

    @Override
    public void requestDestroyed(ServletRequestEvent sre) {
      ServletRequest request = sre.getServletRequest();
      Request baseRequest = Request.getBaseRequest(request);
      if (logger.isLoggable(Level.FINE)) {
        logger.fine("requestDestroyed " + getRequestContext(baseRequest));
      }
      if (request.isAsyncStarted()) {
        request
            .getAsyncContext()
            .addListener(
                new AsyncListener() {
                  @Override
                  public void onTimeout(AsyncEvent event) throws IOException {}

                  @Override
                  public void onStartAsync(AsyncEvent event) throws IOException {}

                  @Override
                  public void onError(AsyncEvent event) throws IOException {}

                  @Override
                  public void onComplete(AsyncEvent event) throws IOException {
                    complete(baseRequest);
                  }
                });
      } else {
        complete(baseRequest);
      }
    }

    @Override
    public void exitScope(
        org.eclipse.jetty.server.handler.ContextHandler.Context context, Request baseRequest) {
      if (logger.isLoggable(Level.FINE)) {
        if (baseRequest == null) {
          logger.fine("exitScope");
        } else {
          logger.fine("exitScope " + getRequestContext(baseRequest));
        }
      }
      ApiProxy.setEnvironmentForCurrentThread(defaultEnvironment);
      LogContext.current().remove("traceId");
    }

    private void complete(Request baseRequest) {
      RequestContext requestContext = getRequestContext(baseRequest);
      if (logger.isLoggable(Level.FINE)) {
        logger.fine("complete " + requestContext);
      }
      VmApiProxyEnvironment env = requestContext.getRequestSpecificEnvironment();

      // Transaction Cleanup
      Collection<Transaction> txns = datastoreService.getActiveTransactions();
      if (!txns.isEmpty()) {
        handleAbandonedTxns(txns);
      }

      // Save dirty sessions
      HttpSession session = baseRequest.getSession(false);
      if (session instanceof AppEngineSession) {
        final AppEngineSession aeSession = (AppEngineSession) session;
        if (aeSession.isDirty()) {
          aeSession.save();
        }
      }

      // Interrupt all API calls
      VmRuntimeUtils.interruptRequestThreads(
          env, VmRuntimeUtils.MAX_REQUEST_THREAD_INTERRUPT_WAIT_TIME_MS);
      env.waitForAllApiCallsToComplete(VmRuntimeUtils.MAX_REQUEST_THREAD_API_CALL_WAIT_MS);
    }

    void handleAbandonedTxns(Collection<Transaction> txns) {
      // TODO(user): In the dev appserver, capture a stack trace whenever a
      // transaction is started so we can print it here.
      for (Transaction txn : txns) {
        try {
          logger.warning(
              "Request completed without committing or rolling back transaction with id "
                  + txn.getId()
                  + ".  Transaction will be rolled back.");
          txn.rollback();
        } catch (Exception e) {
          // We swallow exceptions so that there is no risk of our cleanup
          // impacting the actual result of the request.
          logger.log(
              Level.WARNING,
              "Swallowing an exception we received while trying to rollback "
                  + "abandoned transaction with id "
                  + txn.getId(),
              e);
        }
      }
    }
  }
}
