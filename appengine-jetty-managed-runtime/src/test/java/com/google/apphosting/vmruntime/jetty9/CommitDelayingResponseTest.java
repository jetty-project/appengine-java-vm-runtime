package com.google.apphosting.vmruntime.jetty9;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

import com.google.appengine.repackaged.com.google.api.client.util.IOUtils;
import com.google.apphosting.vmruntime.CommitDelayingResponse;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.EnumSet;
import java.util.concurrent.ThreadLocalRandom;

import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class CommitDelayingResponseTest {
  public static class CommitDelayFilter implements Filter {
    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
        throws IOException, ServletException {
      if (response instanceof HttpServletResponse) {
        CommitDelayingResponse commitDelay =
            new CommitDelayingResponse((HttpServletResponse) response);
        chain.doFilter(request, commitDelay);
      } else {
        chain.doFilter(request, response);
      }
    }

    @Override
    public void destroy() {
    }
  }


  public static class ContentGenServlet extends HttpServlet {
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
        throws ServletException, IOException {
      int size = Integer.parseInt(request.getParameter("size"));
      response.setStatus(200);
      response.setContentLength(size);
      byte[] buf = new byte[size];
      ThreadLocalRandom.current().nextBytes(buf);
      IOUtils.copy(new ByteArrayInputStream(buf), response.getOutputStream());
    }
  }


  private Server server;
  private URI serverURI;

  @Before
  public void startServer() throws Exception {
    server = new Server();
    ServerConnector connector = new ServerConnector(server);
    connector.setPort(0);
    server.addConnector(connector);

    // The setup
    ServletContextHandler context = new ServletContextHandler();
    context.addServlet(ContentGenServlet.class, "/gen");
    context.addFilter(CommitDelayFilter.class, "/gen/*", EnumSet.of(DispatcherType.REQUEST));
    server.setHandler(context);

    server.start();
    serverURI = server.getURI();
  }

  @After
  public void stopServer() throws Exception {
    server.stop();
  }

  @Test
  public void testSub32k() throws IOException {
    testRequestSize((32 * 1024) - 100);
    testRequestSize((32 * 1024) - 1);
  }

  @Test
  public void test32k() throws IOException {
    testRequestSize(32 * 1024);
  }

  @Test
  public void test32kPlus() throws IOException {
    testRequestSize((32 * 1024) + 100);
    testRequestSize((32 * 1024) + 1);
  }

  private void testRequestSize(int size) throws IOException {
    URI requestUri = serverURI.resolve("/gen?size=" + size);
    System.err.printf("Request: %s%n", requestUri.toASCIIString());
    HttpURLConnection connection = (HttpURLConnection) requestUri.toURL().openConnection();
    assertThat("Response code", connection.getResponseCode(), is(200));
    try (InputStream in = connection.getInputStream();
        ByteArrayOutputStream buf = new ByteArrayOutputStream()) {
      IOUtils.copy(in, buf);
      assertThat("Received response size", buf.size(), is(size));
    }
  }
}
