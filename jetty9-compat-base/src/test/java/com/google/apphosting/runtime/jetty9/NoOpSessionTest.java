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

package com.google.apphosting.runtime.jetty9;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;

import junit.framework.TestCase;

/**
 * Tests for NoOpSessions
 *
 */
public class NoOpSessionTest extends TestCase {

  LocalConnector connector;
  Server server;
  ServletContextHandler context;

  /**
   * Servlet that is the target of a forward request.
   *
   */
  public static class ForwardServlet extends HttpServlet {

    /**
     * @see javax.servlet.http.HttpServlet#doGet(javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse)
     */
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
        throws ServletException, IOException {
      HttpSession session = req.getSession(false);
      assertNotNull(session);
      assertEquals("bbb", session.getAttribute("before"));
      session.setAttribute("after", "fff");
    }
  }

  /**
   * Servlet that helps test sessions.
   *
   */
  public static class TestServlet extends HttpServlet {

    /**
     * @see javax.servlet.http.HttpServlet#doGet(javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse)
     */
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
        throws ServletException, IOException {
      String action = req.getParameter("action");
      if (action == null) return;

      if ("notexists".equalsIgnoreCase(action)) {
        HttpSession session = req.getSession(false);
        assertNull(session);
        return;
      }

      if ("create".equalsIgnoreCase(action)) {
        HttpSession session = req.getSession(true);
        assertNotNull(session);
        assertNotNull(session.getId());
        assertNotNull(session.getServletContext());
        return;
      }

      if ("attr".equalsIgnoreCase(action)) {
        HttpSession session = req.getSession(true);
        assertNotNull(session);
        session.setAttribute("foo", "bar");
        assertTrue("bar".equals(session.getAttribute("foo")));
        session.removeAttribute("foo");
        assertNull(session.getAttribute("foo"));
        return;
      }

      if ("forward".equalsIgnoreCase(action)) {
        HttpSession session = req.getSession(true);
        assertNotNull(session);
        session.setAttribute("before", "bbb");
        req.getServletContext().getRequestDispatcher("/forward").forward(req, resp);

        assertEquals("bbb", session.getAttribute("before"));
        assertEquals("fff", session.getAttribute("after"));
        return;
      }

      if ("invalidate".equalsIgnoreCase(action)) {
        HttpSession session = req.getSession(true);
        assertNotNull(session);
        session.setAttribute("a", "b");
        session.invalidate();

        session.getId(); //should not throw
        assertNotNull(session.getServletContext()); //should not throw

        try {
          session.invalidate();
          fail("Invalidate invalid session");
        } catch (IllegalStateException e) {
          //expected result
        }

        try {
          session.getAttribute("a");
          fail("Access attribute of invalid session");
        } catch (IllegalStateException e) {
          //expected result
        }

        try {
          session.setAttribute("e", "f");
          fail("Set attribute of invalid session");
        } catch (IllegalStateException e) {
          //expected result
        }

        try {
          session.getLastAccessedTime();
          fail("LastAccessedTime of invalid session");
        } catch (IllegalStateException e) {
          //expected result
        }

        try {
          session.getCreationTime();
          fail("CreationTime of invalid session");
        } catch (IllegalStateException e) {
          //expected result
        }
      }
    }
  }

  @Override
  protected void setUp() throws Exception {
    this.server = new Server(0);
    this.context = new ServletContextHandler(ServletContextHandler.SESSIONS);
    context.setContextPath("/");
    context
        .getServletHandler()
        .addServletWithMapping(new ServletHolder(new TestServlet()), "/test");
    NoOpSessionManager manager = new NoOpSessionManager();
    context.getSessionHandler().setSessionManager(manager);
    this.server.setHandler(context);
    this.connector = new LocalConnector(server);
    this.server.addConnector(connector);
  }

  @Override
  protected void tearDown() throws Exception {
    this.server.stop();
    super.tearDown();
  }

  /**
   * Test that a new session can be created but that on subsequent requests the
   * session does not exist
   *
   * @throws Exception
   */
  public void testNewSession() throws Exception {
    this.server.start();
    String response =
        connector.getResponses(
            "GET http://localhost:8080/test?action=create HTTP/1.1\r\nHost: localhost:8080\r\nConnection: close\r\n\r\n");
    assertTrue(response.contains("200 OK"));
    assertTrue(!response.contains("Set-Cookie"));
    response =
        connector.getResponses(
            "GET http://localhost:8080/test?action=notexists HTTP/1.1\r\nHost: localhost:8080\r\nConnection: close\r\n\r\n");
    assertTrue(response.contains("200 OK"));
    assertTrue(!response.contains("Set-Cookie"));
  }

  /**
   * Test that a request that forwards to the same context can see a session that
   * was created before the forward.
   *
   * @throws Exception
   */
  public void testSessionForward() throws Exception {
    context
        .getServletHandler()
        .addServletWithMapping(new ServletHolder(new ForwardServlet()), "/forward");
    this.server.start();
    String response =
        connector.getResponses(
            "GET http://localhost:8080/test?action=forward HTTP/1.1\r\nHost: localhost:8080\r\nConnection: close\r\n\r\n");
    assertTrue(response.contains("200 OK"));
    assertTrue(!response.contains("Set-Cookie"));
  }

  /**
   * Test that session attributes can be created and removed
   *
   * @throws Exception
   */
  public void testSessionAttributes() throws Exception {
    this.server.start();
    String response =
        connector.getResponses(
            "GET http://localhost:8080/test?action=attr HTTP/1.1\r\nHost: localhost:8080\r\nConnection: close\r\n\r\n");
    assertTrue(response.contains("200 OK"));
  }

  /**
   * Test that a session can be invalidated and that calling methods
   * on the session throw IllegalStateException as required by the servlet spec.
   *
   * @throws Exception
   */
  public void testInvalidate() throws Exception {
    this.server.start();
    String response =
        connector.getResponses(
            "GET http://localhost:8080/test?action=invalidate HTTP/1.1\r\nHost: localhost:8080\r\nConnection: close\r\n\r\n");
    assertTrue(response.contains("200 OK"));
  }
}
