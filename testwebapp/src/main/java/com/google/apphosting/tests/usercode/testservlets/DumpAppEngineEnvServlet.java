package com.google.apphosting.tests.usercode.testservlets;

import com.google.apphosting.api.ApiProxy;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class DumpAppEngineEnvServlet extends HttpServlet
{
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
    {
        resp.setContentType("text/plain");
        PrintWriter out = resp.getWriter();

        dumpLogging(out);
        dumpEnvironment(out);
        dumpDelegate(out);
    }

    private void dumpDelegate(PrintWriter out)
    {
        ApiProxy.Delegate delegate = ApiProxy.getDelegate();
        out.printf("ApiProxy.Delegate = %s%n", delegate);
        if (delegate == null) return;
        dumpLocation(out, delegate.getClass());
    }

    private void dumpEnvironment(PrintWriter out)
    {
        ApiProxy.Environment env = ApiProxy.getCurrentEnvironment();
        out.printf("ApiProxy.Environment = %s%n", env);
        if (env == null) return;
        dumpLocation(out, env.getClass());

        out.printf("  appId = %sn%n", env.getAppId());
        for (Map.Entry<String, Object> attr : env.getAttributes().entrySet())
        {
            out.printf("  attribute[%s] = %s%n", attr.getKey(), attr.getValue());
        }
        out.printf("  authDomain = %s%n", env.getAuthDomain());
        out.printf("  email = %s%n", env.getEmail());
        out.printf("  moduleId = %s%n", env.getModuleId());
        out.printf("  remainingMillis = %d%n", env.getRemainingMillis());
        out.printf("  versionId = %s%n", env.getVersionId());
    }

    private void dumpLocation(PrintWriter out, Class<?> aClass)
    {
        String resourceRef = aClass.getName().replaceFirst("\\.class$", "").replace('.', '/') + ".class";
        out.printf("    ## resource: %s%n", resourceRef);
        try
        {
            Enumeration<URL> urls = Thread.currentThread().getContextClassLoader().getResources(resourceRef);
            while (urls.hasMoreElements())
            {
                out.printf("    # url: %s%n", urls.nextElement().toExternalForm());
            }
        }
        catch (IOException e)
        {
            e.printStackTrace(out);
        }
    }

    private void dumpLogging(PrintWriter out)
    {
        out.println("Logging: ");
        LogManager logmgr = LogManager.getLogManager();
        out.printf("LogManager: %s%n", logmgr);
        List<String> lognames = new ArrayList<>();
        lognames.addAll(Collections.list(logmgr.getLoggerNames()));
        Collections.sort(lognames);
        for (String logname : lognames)
        {
            Logger logger = logmgr.getLogger(logname);
            out.printf(" [%s] (%s) = %s%n", logname, asLevel(logger.getLevel()), logger);
            Handler handlers[] = logger.getHandlers();
            if (handlers != null)
            {
                for (Handler handler : handlers)
                {
                    out.printf("   %s (%s)%n", handler, asLevel(handler.getLevel()));
                    dumpLocation(out, handler.getClass());
                }
            }
        }
    }

    private String asLevel(Level level)
    {
        if (level == null)
        {
            return "";
        }

        return level.getName();
    }
}
