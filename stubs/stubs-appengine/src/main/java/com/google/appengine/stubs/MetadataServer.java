package com.google.appengine.stubs;

import java.net.URI;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class MetadataServer extends Server
{
    private static final Logger LOG = Log.getLogger(MetadataServer.class);

    private URI serverUri;
    private final ServerConnector localConnector;

    public MetadataServer()
    {
        super();

        HandlerCollection handlers = new HandlerCollection();
        handlers.addHandler(new MetadataRequestHandler());
        handlers.addHandler(new DefaultHandler());
        setHandler(handlers);

        localConnector = new ServerConnector(this);
        localConnector.setPort(0);
        localConnector.setHost("localhost");

        addConnector(localConnector);
    }

    public URI getServerUri()
    {
        if (serverUri == null)
        {
            int port = localConnector.getLocalPort();
            String host = localConnector.getHost();
            serverUri = URI.create("http://" + host + ":" + port + "/");
        }

        return serverUri;
    }

    @Override
    protected void doStart() throws Exception
    {
        LOG.info("MetadataServer starting");
        super.doStart();
        LOG.info("MetadataServer starting");
    }
}
