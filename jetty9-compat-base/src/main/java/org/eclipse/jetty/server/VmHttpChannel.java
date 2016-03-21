package org.eclipse.jetty.server;

import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpTransport;

public class VmHttpChannel extends HttpChannelOverHttp {

  public VmHttpChannel(HttpConnection httpConnection, Connector connector, HttpConfiguration config, EndPoint endPoint,
      HttpTransport transport) {
    super(httpConnection, connector, config, endPoint, transport);
  }

  @Override
  public boolean startRequest(String method, String uri, HttpVersion version)
  {
    getRequest().setAttribute("opened",new Long(((VmEndPoint)getEndPoint()).opened));
    getRequest().setAttribute("selected",new Long(((VmEndPoint)getEndPoint()).lastSelected));
    return super.startRequest(method,uri,version);
  }
  
}
