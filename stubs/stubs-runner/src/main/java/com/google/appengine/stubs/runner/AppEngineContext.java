package com.google.appengine.stubs.runner;

import com.google.apphosting.api.ApiProxy;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.webapp.WebAppContext;

public class AppEngineContext extends WebAppContext
{
    private final ApiProxy.Environment env;

    public AppEngineContext(ApiProxy.Environment env)
    {
        this.env = env;
    }

    @Override
    protected void enterScope(Request request, Object reason)
    {
        ApiProxy.setEnvironmentForCurrentThread(env);
        super.enterScope(request, reason);
    }

    @Override
    protected void exitScope(Request request)
    {
        ApiProxy.clearEnvironmentForCurrentThread();
        super.exitScope(request);
    }
}
