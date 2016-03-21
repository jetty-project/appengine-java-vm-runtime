package org.eclipse.jetty.server;

import org.eclipse.jetty.http.HttpCompliance;
import org.eclipse.jetty.io.EndPoint;

public class VmHttpConnection extends HttpConnection {

  public VmHttpConnection(HttpConfiguration config, Connector connector, EndPoint endPoint, HttpCompliance compliance) {
    super(config, connector, endPoint, compliance);
  }

  @Override
  protected HttpChannelOverHttp newHttpChannel()
  {
      return new VmHttpChannel(this, getConnector(), getHttpConfiguration(), getEndPoint(), this);
  }
  
}
