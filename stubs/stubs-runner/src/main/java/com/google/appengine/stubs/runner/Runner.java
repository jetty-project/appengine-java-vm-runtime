package com.google.appengine.stubs.runner;

import com.google.appengine.stubs.MetadataServer;

import java.io.File;
import java.io.FileNotFoundException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class Runner
{
    private static final Logger LOG = Log.getLogger(Runner.class);

    public static void main(String args[])
    {
        // Start Metadata Server
        MetadataServer metadata = new MetadataServer();

        try
        {
            LOG.info("Starting metadata server");
            metadata.start();

            // Initialize App Server
            LOG.info("Init app server");
            AppServer app = new AppServer();
            app.setMetadataServerRef(metadata.getServerUri());
            URI stubsWebAppURI = findStubsWebAppURI();
            app.setRootWebApp(stubsWebAppURI);

            try
            {
                // Start App Server
                LOG.info("Starting app server");
                app.start();
                LOG.info("App Server is started at {}", app.getURI());
                app.join();
            }
            catch (Throwable t)
            {
                LOG.warn(t);
            }
            finally
            {
                quietStop(app);
            }
        }
        catch (Throwable t)
        {
            LOG.warn(t);
        }
        finally
        {
            quietStop(metadata);
        }
    }

    private static URI findStubsWebAppURI() throws FileNotFoundException
    {
        String path = System.getProperty("appengine.webapp.uri");
        if(path != null)
            return URI.create(path);

        Path pwd = new File(System.getProperty("user.dir")).toPath();
        Path webapp = pwd.resolve("target/webapps/stubs-webapp.war");
        if(Files.exists(webapp))
            return webapp.toUri();

        throw new FileNotFoundException("Unable to find stubs-webapp.war");
    }

    private static void quietStop(LifeCycle lifecycle)
    {
        try
        {
            lifecycle.stop();
        }
        catch (Exception ignored)
        {
            LOG.ignore(ignored);
        }
    }
}
