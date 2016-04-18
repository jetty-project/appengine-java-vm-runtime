package com.google.appengine.stubs;

import static com.google.apphosting.vmruntime.VmApiProxyEnvironment.AFFINITY_ATTRIBUTE;
import static com.google.apphosting.vmruntime.VmApiProxyEnvironment.APPENGINE_HOSTNAME_ATTRIBUTE;
import static com.google.apphosting.vmruntime.VmApiProxyEnvironment.BACKEND_ATTRIBUTE;
import static com.google.apphosting.vmruntime.VmApiProxyEnvironment.INSTANCE_ATTRIBUTE;
import static com.google.apphosting.vmruntime.VmApiProxyEnvironment.PARTITION_ATTRIBUTE;
import static com.google.apphosting.vmruntime.VmApiProxyEnvironment.PROJECT_ATTRIBUTE;
import static com.google.apphosting.vmruntime.VmApiProxyEnvironment.USE_MVM_AGENT_ATTRIBUTE;
import static com.google.apphosting.vmruntime.VmApiProxyEnvironment.VERSION_ATTRIBUTE;


import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class MetadataRequestHandler extends AbstractHandler
{
    private static final Logger LOG = Log.getLogger(MetadataRequestHandler.class);

    private static final String PATH_PREFIX = "/computeMetadata/v1/instance/";
    public static final String PROJECT = "google.com:test-project";
    public static final String PARTITION = "testpartition";
    public static final String VERSION = "testversion";
    public static final String BACKEND = "testbackend";
    public static final String INSTANCE = "frontend1";
    public static final String AFFINITY = "true";
    public static final String APPENGINE_HOSTNAME = "testhostname";

    private HashMap<String, String> responses = new HashMap<>();

    public MetadataRequestHandler()
    {
        addMetadata("STOP", "STOP");
        addMetadata(PROJECT_ATTRIBUTE, PROJECT);
        addMetadata(PARTITION_ATTRIBUTE, PARTITION);
        addMetadata(BACKEND_ATTRIBUTE, BACKEND);
        addMetadata(VERSION_ATTRIBUTE, VERSION);
        addMetadata(INSTANCE_ATTRIBUTE, INSTANCE);
        addMetadata(AFFINITY_ATTRIBUTE, AFFINITY);
        addMetadata(APPENGINE_HOSTNAME_ATTRIBUTE, APPENGINE_HOSTNAME);
        addMetadata(USE_MVM_AGENT_ATTRIBUTE, Boolean.toString(false));
    }

    /**
     * Adds a new metadata value to the server.
     *
     * @param path The path where the value is stored.
     * @param value The value to return.
     */
    public void addMetadata(String path, String value)
    {
        responses.put(PATH_PREFIX + path, value);
    }


    @Override
    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        StringBuilder dbg = new StringBuilder();
        dbg.append("Request URI: ").append(request.getRequestURI()).append(System.lineSeparator());
        dbg.append("Headers: ");

        List<String> names = new ArrayList<>();
        names.addAll(Collections.list(request.getHeaderNames()));

        boolean delim = false;
        for (String name : names)
        {
            if (delim)
                dbg.append("         ");
            dbg.append(name).append(": ").append(request.getHeader(name));
            delim = true;
        }

        LOG.info("Request: {}", dbg);

        if (!"Google".equals(request.getHeader("Metadata-Flavor")))
        {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            baseRequest.setHandled(true);
        }
    }
}
