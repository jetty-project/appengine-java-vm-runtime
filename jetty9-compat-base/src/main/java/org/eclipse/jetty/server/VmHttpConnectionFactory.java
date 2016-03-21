package org.eclipse.jetty.server;

import org.eclipse.jetty.http.HttpCompliance;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.annotation.Name;

public class VmHttpConnectionFactory extends HttpConnectionFactory{

  public VmHttpConnectionFactory() {
    super();
  }

  public VmHttpConnectionFactory(@Name("config") HttpConfiguration config, @Name("compliance") HttpCompliance compliance) {
    super(config, compliance);
  }

  public VmHttpConnectionFactory(HttpConfiguration config) {
    super(config);
  }

  @Override
  public Connection newConnection(Connector connector, EndPoint endPoint) {
    return configure(new VmHttpConnection(getHttpConfiguration(), connector, endPoint, getHttpCompliance()), connector, endPoint);
  }
  
}
