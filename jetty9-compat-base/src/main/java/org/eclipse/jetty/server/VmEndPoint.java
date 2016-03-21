package org.eclipse.jetty.server;

import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

import org.eclipse.jetty.io.ManagedSelector;
import org.eclipse.jetty.io.SelectChannelEndPoint;
import org.eclipse.jetty.util.thread.Scheduler;

public class VmEndPoint extends SelectChannelEndPoint {
  public volatile long opened;
  public volatile long lastSelected;
  
  public VmEndPoint(SocketChannel channel, ManagedSelector selector, SelectionKey key, Scheduler scheduler,
      long idleTimeout) {
    super(channel, selector, key, scheduler, idleTimeout);
    opened=System.nanoTime();
  }
  
  @Override
  public Runnable onSelected() {
    lastSelected=System.nanoTime();
    return super.onSelected();
  }
}
