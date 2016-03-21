/**
 * Copyright 2015 Google Inc. All Rights Reserved.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS-IS" BASIS,
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
import com.google.apphosting.runtime.DatastoreSessionStore;
import com.google.apphosting.runtime.DeferredDatastoreSessionStore;
import com.google.apphosting.runtime.MemcacheSessionStore;
import com.google.apphosting.runtime.SessionStore;
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
import com.google.apphosting.vmruntime.VmRuntimeLogHandler;
import com.google.apphosting.vmruntime.VmRuntimeUtils;
import com.google.apphosting.vmruntime.VmTimer;

import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.quickstart.PreconfigureDescriptorProcessor;
import org.eclipse.jetty.quickstart.QuickStartDescriptorGenerator;
import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.security.SecurityHandler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.session.AbstractSessionManager;
import org.eclipse.jetty.server.session.SessionHandler;
import org.eclipse.jetty.servlet.ServletHandler;
import org.eclipse.jetty.util.ConcurrentArrayQueue;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.WebAppContext;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.TimeUnit;

import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletRequestEvent;
import javax.servlet.ServletRequestListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

/**
 * WebAppContext for VM Runtimes. This class extends the "normal" AppEngineWebAppContext with
 * functionality that installs a request specific thread local environment on each incoming request.
 */
public class VmRuntimeWebAppContext
  extends WebAppContext implements VmRuntimeTrustedAddressChecker {

  private static final Logger LOG = Log.getLogger(VmRuntimeWebAppContext.class);

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
  // constant.  If it's much larger than this we may need to
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
  private static final String[] quickstartConfigurationClasses  = {
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

  /** Set the quickstart WebXml
   * <p>If set, this context will not start, rather it will generate the
   * quickstart-web.xml file and then stop the server. 
   * If not set, the context will start normally </p>
   * @param quickstartWebXml The location of the quickstart web.xml to generate
   */
  public void setQuickstartWebXml(String quickstartWebXml) {
    if (quickstartWebXml!=null && quickstartWebXml.length()==0)
      quickstartWebXml=null;
    this.quickstartWebXml = quickstartWebXml;
  }

  @Override
  protected void doStart() throws Exception {
    // unpack and Adjust paths.
    Resource base = getBaseResource();
    if (base == null) {
      String war=getWar();
      if (war==null)
        throw new IllegalStateException("No war");
      base = Resource.newResource(getWar());
    }
    Resource dir;
    if (base.isDirectory()) {
      dir = base;
    } else {
      throw new IllegalArgumentException("Bad base:"+base);
    }
    Resource qswebxml = dir.addPath("/WEB-INF/quickstart-web.xml");
    if (qswebxml.exists()) {
      setConfigurationClasses(quickstartConfigurationClasses);
    }
    
    datastoreService = getDatastoreService();
    
    if (quickstartWebXml==null)
      addEventListener(new ContextListener());
    else
      getMetaData().addDescriptorProcessor(preconfigProcessor=new PreconfigureDescriptorProcessor());
    
    super.doStart();
  }
  
  @Override
  protected void startWebapp() throws Exception
  {
    if (quickstartWebXml==null)
      super.startWebapp();
    else
    {
      LOG.info("Generating quickstart web.xml: {}", quickstartWebXml);
      Resource descriptor = Resource.newResource(quickstartWebXml);
      if (descriptor.exists())
        descriptor.delete();
      descriptor.getFile().createNewFile();
      QuickStartDescriptorGenerator generator = new QuickStartDescriptorGenerator(this, preconfigProcessor.getXML());
      try (FileOutputStream fos = new FileOutputStream(descriptor.getFile()))
      {
        generator.generateQuickStartWebXml(fos);
      }
      finally
      {
        System.exit(0);
      }
    }
  }


  @Override
  protected void stopWebapp() throws Exception
  {
      if (quickstartWebXml==null)
        super.stopWebapp();
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
        appEngineWebXml.getAsyncSessionPersistence() ? new DeferredDatastoreSessionStore(
            appEngineWebXml.getAsyncSessionPersistenceQueueName())
            : new DatastoreSessionStore();
    // Write session data to the datastore before we write to memcache.
    return Arrays.asList(datastoreSessionStore, new MemcacheSessionStore());
  }

  /**
   * Checks if the request was made over HTTPS. If so it modifies the request so that
   * {@code HttpServletRequest#isSecure()} returns true, {@code HttpServletRequest#getScheme()}
   * returns "https", and {@code HttpServletRequest#getServerPort()} returns 443. Otherwise it sets
   * the scheme to "http" and port to 80.
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
    setLogger(LOG.getLogger("webapp"));

    // Configure the Jetty SecurityHandler to understand our method of authentication
    // (via the UserService). Only the default ConstraintSecurityHandler is supported.
    AppEngineAuthentication.configureSecurityHandler(
        (ConstraintSecurityHandler) getSecurityHandler(), this);

    setMaxFormContentSize(MAX_RESPONSE_SIZE);
    setConfigurationClasses(preconfigurationClasses);
    // See http://www.eclipse.org/jetty/documentation/current/configuring-webapps.html#webapp-context-attributes
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
   *         appengine-web.xml configuration.
   * @throws IOException If the runtime was unable to find/read appDir.
   */
  public void init(String appengineWebXmlFile)
      throws AppEngineConfigException, IOException {  
    String appDir=getBaseResource().getFile().getCanonicalPath();  
    defaultEnvironment = VmApiProxyEnvironment.createDefaultContext(
        System.getenv(), metadataCache, VmRuntimeUtils.getApiServerAddress(), wallclockTimer,
        VmRuntimeUtils.ONE_DAY_IN_MILLIS, appDir);
    ApiProxy.setEnvironmentForCurrentThread(defaultEnvironment);
    if (ApiProxy.getEnvironmentFactory() == null) {
      // Need the check above since certain unit tests initialize the context multiple times.
      ApiProxy.setEnvironmentFactory(new VmEnvironmentFactory(defaultEnvironment));
    }

    isDevMode = defaultEnvironment.getPartition().equals("dev");
    AppEngineWebXml appEngineWebXml = null;
    File appWebXml = new File(appDir, appengineWebXmlFile);
    if (appWebXml.exists()) {
      AppEngineWebXmlReader appEngineWebXmlReader
              = new AppEngineWebXmlReader(appDir, appengineWebXmlFile);
      appEngineWebXml = appEngineWebXmlReader.readAppEngineWebXml();
    }
    VmRuntimeUtils.installSystemProperties(defaultEnvironment, appEngineWebXml);
    String logConfig = System.getProperty("java.util.logging.config.file");
    if (logConfig!=null && logConfig.startsWith("WEB-INF/"))
      System.setProperty("java.util.logging.config.file", URIUtil.addPaths(appDir, logConfig));
    VmRuntimeLogHandler.init();
    VmRuntimeFileLogHandler.init();

    for (String systemClass : SYSTEM_CLASSES) {
      addSystemClass(systemClass);
    }
    if (appEngineWebXml == null) {
      // No need to configure the session manager.
      return;
    }
    AbstractSessionManager sessionManager;
    if (appEngineWebXml.getSessionsEnabled()) {
      sessionManager = new SessionManager(createSessionStores(appEngineWebXml));
      getSessionHandler().setSessionManager(sessionManager);  
    }
  }

  @Override
  protected SessionHandler newSessionHandler() {
    return new SessionHandler()
    {
      @Override
      public void doScope(String target, Request baseRequest, HttpServletRequest request,
          HttpServletResponse response) throws IOException, ServletException {  
        RequestContext requestContext = getRequestContext(baseRequest);
          requestContext.record("session.doScope");
        super.doScope(target, baseRequest, request, response);
      }

      @Override
      public void doHandle(String target, Request baseRequest, HttpServletRequest request,
          HttpServletResponse response) throws IOException, ServletException {
        RequestContext requestContext = getRequestContext(baseRequest);
        requestContext.record("session.doHandle");
        super.doHandle(target, baseRequest, request, response);
      }
    };
  }

  @Override
  protected SecurityHandler newSecurityHandler() {
    return new ConstraintSecurityHandler()
        {
          @Override
          public void handle(String arg0, Request baseRequest, HttpServletRequest arg2, HttpServletResponse arg3)
              throws IOException, ServletException {
            RequestContext requestContext = getRequestContext(baseRequest);
            requestContext.record("security.handle");
            super.handle(arg0, baseRequest, arg2, arg3);
          }
        };
  }

  @Override
  protected ServletHandler newServletHandler() {
    return new ServletHandler()
        {
      @Override
      public void doScope(String target, Request baseRequest, HttpServletRequest request,
          HttpServletResponse response) throws IOException, ServletException {  
        
        RequestContext requestContext = getRequestContext(baseRequest);
          requestContext.record("servlet.doScope");
        super.doScope(target, baseRequest, request, response);
      }

      @Override
      public void doHandle(String target, Request baseRequest, HttpServletRequest request,
          HttpServletResponse response) throws IOException, ServletException {
        RequestContext requestContext = getRequestContext(baseRequest);
        requestContext.record("servlet.doHandle");
        super.doHandle(target, baseRequest, request, response);
      }
        };
  }

  @Override
  public boolean isTrustedRemoteAddr(String remoteAddr) {
    return VmRequestUtils.isTrustedRemoteAddr(isDevMode, remoteAddr);
  }

  public RequestContext getRequestContext(Request baseRequest) {
    RequestContext requestContext = (RequestContext)baseRequest.getAttribute(RequestContext.class.getName());
    if (requestContext==null) {
      // No instance found, so create a new environment
      requestContext=new RequestContext(baseRequest);
      baseRequest.setAttribute(RequestContext.class.getName(), requestContext);
    }
    return requestContext;
  }

  class RequestContext extends HttpServletRequestAdapter { 
    private final VmApiProxyEnvironment requestSpecificEnvironment;
    private final Queue<String> history = new ConcurrentArrayQueue<>();
    
    RequestContext(Request request) {
      super(request);

      request.setAttribute("history", history);
      Long l=(Long)request.getAttribute("opened");
      if (l!=null)
        record(l.longValue(),"Endpoint Opened");
      l=(Long)request.getAttribute("selected");
      if (l!=null)
        record(l,"Last Selected");
      record("RequestContext created");
      
      this.requestSpecificEnvironment=
      VmApiProxyEnvironment.createFromHeaders(
          System.getenv(), metadataCache, this, VmRuntimeUtils.getApiServerAddress(),
          wallclockTimer, VmRuntimeUtils.ONE_DAY_IN_MILLIS, defaultEnvironment);
      record("VmApiProxyEnv created");
    }

    void record(String event)
    {
      record(System.nanoTime(),event);
    }
    void record(long nano,String event)
    {
      history.offer(String.format("%d,%s", nano, event));
    }
    
    VmApiProxyEnvironment getRequestSpecificEnvironment() {
      return requestSpecificEnvironment;
    }

    public void logHistory() {
      StringBuilder buf = new StringBuilder();
      for (String line : history)
        buf.append(line).append("\n");
      LOG.info("HISTORY "+buf.toString());
    }
  }
  
  public class ContextListener implements ContextHandler.ContextScopeListener, ServletRequestListener {
    @Override
    public void enterScope(org.eclipse.jetty.server.handler.ContextHandler.Context context, Request baseRequest, Object reason) {
      RequestContext requestContext = getRequestContext(baseRequest);
      requestContext.record("enterScope");
      if (LOG.isDebugEnabled())
        LOG.debug("Enter {} -> {}",ApiProxy.getCurrentEnvironment(),requestContext.getRequestSpecificEnvironment());
      ApiProxy.setEnvironmentForCurrentThread(requestContext.getRequestSpecificEnvironment());      
    }

    @Override
    public void requestInitialized(ServletRequestEvent sre) {
      ServletRequest request = sre.getServletRequest();
      Request baseRequest = Request.getBaseRequest(request);
      RequestContext requestContext = getRequestContext(baseRequest);
      requestContext.record("requestInitialized");
      
      // Check for SkipAdminCheck and set attributes accordingly.
      VmRuntimeUtils.handleSkipAdminCheck(requestContext);

      // Change scheme to HTTPS based on headers set by the appserver.
      setSchemeAndPort(baseRequest);
    }
    
    @Override
    public void requestDestroyed(ServletRequestEvent sre) {      
      ServletRequest request = sre.getServletRequest();
      Request baseRequest = Request.getBaseRequest(request);
      RequestContext requestContext = getRequestContext(baseRequest);
      requestContext.record("requestDestroyed");
      
      if (request.isAsyncStarted()) {
        request.getAsyncContext().addListener(new AsyncListener() {
          @Override public void onTimeout(AsyncEvent event) throws IOException {}
          @Override public void onStartAsync(AsyncEvent event) throws IOException {}
          @Override public void onError(AsyncEvent event) throws IOException {}
          
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
    public void exitScope(org.eclipse.jetty.server.handler.ContextHandler.Context context, Request baseRequest) {
      RequestContext requestContext = getRequestContext(baseRequest);
      requestContext.record("exitScope");
      if (LOG.isDebugEnabled())
        LOG.debug("Exit {} -> {}",ApiProxy.getCurrentEnvironment(),defaultEnvironment);
      ApiProxy.setEnvironmentForCurrentThread(defaultEnvironment);
    }
    
    private void complete(Request baseRequest)
    {
      RequestContext requestContext = getRequestContext(baseRequest);
      requestContext.record("complete request");
      VmApiProxyEnvironment env = requestContext.getRequestSpecificEnvironment();

      // Transaction Cleanup 
      Collection<Transaction> txns = datastoreService.getActiveTransactions();
      if (!txns.isEmpty()) {
        handleAbandonedTxns(txns);
      }

      requestContext.record("handledAbandonedTxns");
      
      // Save dirty sessions
      HttpSession session = baseRequest.getSession(false);
      if (session instanceof AppEngineSession) {
        final AppEngineSession aeSession = (AppEngineSession) session;
              if (aeSession.isDirty()) {
                aeSession.save();
                requestContext.record("session.saved");
              }
      }

      
      // Interrupt all API calls
      VmRuntimeUtils.interruptRequestThreads(env, VmRuntimeUtils.MAX_REQUEST_THREAD_INTERRUPT_WAIT_TIME_MS);
      requestContext.record("interruptRequestThreads");
      env.waitForAllApiCallsToComplete(VmRuntimeUtils.MAX_REQUEST_THREAD_API_CALL_WAIT_MS);
      requestContext.record("waitForAllApiCallsToComplete");
      
      requestContext.logHistory();
      
    }

    void handleAbandonedTxns(Collection<Transaction> txns) {
      // TODO(user): In the dev appserver, capture a stack trace whenever a
      // transaction is started so we can print it here.
      for (Transaction txn : txns) {
        try {
          LOG.warn("Request completed without committing or rolling back transaction with id "
              + txn.getId() + ".  Transaction will be rolled back.");
          txn.rollback();
        } catch (Exception e) {
          // We swallow exceptions so that there is no risk of our cleanup
          // impacting the actual result of the request.
          LOG.warn("Swallowing an exception we received while trying to rollback "
              + "abandoned transaction with id " + txn.getId(), e);
        }
      }
    }
  }
}
