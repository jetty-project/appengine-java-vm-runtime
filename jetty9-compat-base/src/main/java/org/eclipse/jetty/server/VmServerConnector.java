package org.eclipse.jetty.server;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

import org.eclipse.jetty.io.ManagedSelector;
import org.eclipse.jetty.io.SelectChannelEndPoint;
import org.eclipse.jetty.util.annotation.Name;

public class VmServerConnector extends ServerConnector{

  public VmServerConnector(
      @Name("server") Server server,
      @Name("acceptors") int acceptors,
      @Name("selectors") int selectors,
      @Name("factories") ConnectionFactory... factories) {
    super(server, acceptors, selectors, factories);
  }


  
  @Override
  protected SelectChannelEndPoint newEndPoint(SocketChannel channel, ManagedSelector selectSet, SelectionKey key)
      throws IOException {
    return new VmEndPoint(channel, selectSet, key, getScheduler(), getIdleTimeout());
  }
  

}
