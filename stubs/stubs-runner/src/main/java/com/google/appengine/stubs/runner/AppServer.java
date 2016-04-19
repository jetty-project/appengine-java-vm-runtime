package com.google.appengine.stubs.runner;


import com.google.apphosting.api.ApiProxy;

import java.net.MalformedURLException;
import java.net.URI;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.webapp.Configuration;

public class AppServer extends Server
{
    private static Logger LOG = Log.getLogger(AppServer.class);

    private URI metadataServerRef;
    private ContextHandlerCollection contexts;

    public AppServer()
    {
        super();

        // Connector
        ServerConnector connector = new ServerConnector(this);
        connector.setPort(9090);
        addConnector(connector);

        // Handlers
        HandlerCollection handlers = new HandlerCollection();
        contexts = new ContextHandlerCollection();
        handlers.addHandler(contexts);
        handlers.addHandler(new DefaultHandler());

        setHandler(handlers);


        // WebApp Configurations

        Configuration.ClassList classlist = Configuration.ClassList.setServerDefault(this);
        // Enable JNDI / etc
        classlist.addAfter("org.eclipse.jetty.webapp.FragmentConfiguration",
                "org.eclipse.jetty.plus.webapp.EnvConfiguration",
                "org.eclipse.jetty.plus.webapp.PlusConfiguration");
        // Enable annotation scanning
        classlist.addBefore("org.eclipse.jetty.webapp.JettyWebXmlConfiguration",
                "org.eclipse.jetty.annotations.AnnotationConfiguration");

        LOG.info("Initialized");
    }

    public void setMetadataServerRef(URI metadataServerRef)
    {
        String serverRef = String.format("%s:%d", metadataServerRef.getHost(), metadataServerRef.getPort());
        LOG.info("Metadata Server Ref : {}", serverRef);

        System.setProperty("metadata_server", serverRef);

        this.metadataServerRef = metadataServerRef;
    }

    public void setRootWebApp(URI rootWebApp) throws MalformedURLException
    {
        LOG.info("Root WebApp : {}", rootWebApp);

        // GAE Deployer
        // VmRuntimeWebAppDeployer deployer = new VmRuntimeWebAppDeployer(contexts, rootWebApp.toASCIIString());
        // this.addBean(deployer);

        AppEngineContext ctx = new AppEngineContext(ApiProxy.getCurrentEnvironment());
        ctx.setContextPath("/");
        ctx.setWar(rootWebApp.toASCIIString());
        contexts.addHandler(ctx);
    }
}
