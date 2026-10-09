package org.hkrpg.server;

import io.netty.channel.Channel;
import io.netty.channel.socket.DatagramChannel;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import kcp.highway.KcpServer;

/** Startup barrier for the pinned KCP dependency, which discards asynchronous bind futures. */
public final class TransportStartup {
  public static void awaitBound(KcpServer server, InetSocketAddress expected, Duration timeout) {
    if (server == null) throw new IllegalArgumentException("Missing KCP server");
    // lib/kcp.jar has no socket accessor. Inspect only this pinned class's owned list;
    // a dependency with a different layout must fail before publishing readiness.
    try {
      var field = KcpServer.class.getDeclaredField("localAddresss");
      field.setAccessible(true);
      if (!(field.get(server) instanceof List<?> owned))
        throw new IllegalStateException("KCP socket list is unavailable");
      var channels = new ArrayList<Channel>();
      for (var item : owned) {
        if (!(item instanceof DatagramChannel channel))
          throw new IllegalStateException("KCP owns an invalid datagram channel");
        channels.add(channel);
      }
      awaitBound(channels, expected, timeout);
    } catch (ReflectiveOperationException | SecurityException incompatible) {
      throw new IllegalStateException("Cannot inspect pinned KCP sockets", incompatible);
    }
  }

  static void awaitBound(
      List<? extends Channel> channels, InetSocketAddress expected, Duration timeout) {
    if (expected == null
        || expected.isUnresolved()
        || timeout == null
        || timeout.isZero()
        || timeout.isNegative())
      throw new IllegalArgumentException(
          "A resolved address and positive bind timeout are required");
    long budget;
    try {
      budget = timeout.toNanos();
    } catch (ArithmeticException excessive) {
      throw new IllegalArgumentException("Bind timeout is too large", excessive);
    }
    if (channels == null || channels.isEmpty())
      throw new IllegalStateException("KCP did not create any datagram sockets");
    var sockets = new ArrayList<DatagramChannel>();
    for (var item : channels) {
      if (!(item instanceof DatagramChannel channel))
        throw new IllegalStateException("KCP owns an invalid datagram channel");
      sockets.add(channel);
    }
    long began = System.nanoTime();
    while (true) {
      if (Thread.currentThread().isInterrupted())
        throw new IllegalStateException(
            "Interrupted while binding KCP sockets", new InterruptedException());
      boolean bound = true;
      for (var channel : sockets) {
        if (!channel.isOpen())
          throw new IllegalStateException("KCP socket closed before startup on " + expected);
        if (!channel.isActive()) {
          bound = false;
          continue;
        }
        var actual = channel.localAddress();
        if (actual == null
            || actual.getPort() <= 0
            || (expected.getPort() != 0 && actual.getPort() != expected.getPort())
            || !expected.getAddress().equals(actual.getAddress()))
          throw new IllegalStateException("KCP socket is not bound to " + expected);
      }
      if (bound) return;
      long remaining = budget - (System.nanoTime() - began);
      if (remaining <= 0)
        throw new IllegalStateException("Timed out binding KCP sockets on " + expected);
      try {
        TimeUnit.NANOSECONDS.sleep(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(10)));
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Interrupted while binding KCP sockets", interrupted);
      }
    }
  }

  private TransportStartup() {}
}
