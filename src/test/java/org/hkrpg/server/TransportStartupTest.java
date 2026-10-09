package org.hkrpg.server;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import java.net.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import kcp.highway.KcpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TransportStartupTest {
  final Duration timeout = Duration.ofSeconds(2);
  final InetSocketAddress ephemeral = new InetSocketAddress("127.0.0.1", 0);
  final EventLoopGroup group = new NioEventLoopGroup(1);
  final List<Channel> channels = new ArrayList<>();
  final List<CountDownLatch> releases = new ArrayList<>();
  final ExecutorService waiter = Executors.newSingleThreadExecutor();

  Bootstrap bootstrap() {
    return new Bootstrap()
        .group(group)
        .channel(NioDatagramChannel.class)
        .handler(new ChannelInboundHandlerAdapter());
  }

  CountDownLatch blockLoop() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    releases.add(release);
    group
        .next()
        .execute(
            () -> {
              entered.countDown();
              try {
                release.await();
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
              }
            });
    assertTrue(entered.await(2, TimeUnit.SECONDS));
    return release;
  }

  Channel bind() throws Exception {
    var channel = bootstrap().bind(ephemeral).sync().channel();
    channels.add(channel);
    return channel;
  }

  @AfterEach
  void close() throws Exception {
    releases.forEach(CountDownLatch::countDown);
    waiter.shutdownNow();
    assertTrue(waiter.awaitTermination(3, TimeUnit.SECONDS));
    for (var channel : channels) channel.close().syncUninterruptibly();
    group.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
  }

  @Test
  void publicationWaitsForAnActualAsynchronousUdpBind() throws Exception {
    var release = blockLoop();
    var bind = bootstrap().bind(ephemeral);
    channels.add(bind.channel());
    var entered = new CountDownLatch(1);
    var result =
        waiter.submit(
            () -> {
              entered.countDown();
              TransportStartup.awaitBound(channels, ephemeral, timeout);
            });
    assertTrue(entered.await(2, TimeUnit.SECONDS));
    assertThrows(TimeoutException.class, () -> result.get(100, TimeUnit.MILLISECONDS));
    assertFalse(bind.isDone());
    release.countDown();
    result.get(2, TimeUnit.SECONDS);
    assertTrue(bind.sync().channel().isActive());
    assertTrue(((InetSocketAddress) bind.channel().localAddress()).getPort() > 0);
  }

  @Test
  void everyDatagramChannelMustFinishBinding() throws Exception {
    var first = bind();
    var release = blockLoop();
    var second = bootstrap().bind(ephemeral);
    channels.add(second.channel());
    var entered = new CountDownLatch(1);
    var result =
        waiter.submit(
            () -> {
              entered.countDown();
              TransportStartup.awaitBound(channels, ephemeral, timeout);
            });
    assertTrue(entered.await(2, TimeUnit.SECONDS));
    assertTrue(first.isActive());
    assertThrows(TimeoutException.class, () -> result.get(100, TimeUnit.MILLISECONDS));
    release.countDown();
    result.get(2, TimeUnit.SECONDS);
    assertTrue(second.sync().channel().isActive());
  }

  @Test
  void anAlreadyBoundChannelCanPublishImmediately() throws Exception {
    var channel = bind();
    assertDoesNotThrow(
        () ->
            TransportStartup.awaitBound(
                channels, (InetSocketAddress) channel.localAddress(), timeout));
  }

  @Test
  void anOpenButUnboundSocketTimesOut() throws Exception {
    channels.add(group.register(new NioDatagramChannel()).sync().channel());
    assertThrows(
        IllegalStateException.class,
        () -> TransportStartup.awaitBound(channels, ephemeral, Duration.ofMillis(30)));
    assertTrue(channels.getFirst().isOpen());
    assertFalse(channels.getFirst().isActive());
  }

  @Test
  void aClosedChannelCannotPublish() throws Exception {
    var channel = bind();
    channel.close().sync();
    assertThrows(
        IllegalStateException.class,
        () -> TransportStartup.awaitBound(channels, ephemeral, timeout));
  }

  @Test
  void aFailedRealBindCannotPublish() throws Exception {
    try (var occupied = new DatagramSocket(ephemeral)) {
      var address = new InetSocketAddress("127.0.0.1", occupied.getLocalPort());
      var failed = bootstrap().bind(address).await();
      channels.add(failed.channel());
      assertFalse(failed.isSuccess());
      assertThrows(
          IllegalStateException.class,
          () -> TransportStartup.awaitBound(channels, address, timeout));
    }
  }

  @Test
  void aSocketOnTheWrongPortCannotPublish() throws Exception {
    var channel = bind();
    int actual = ((InetSocketAddress) channel.localAddress()).getPort();
    var other = new InetSocketAddress("127.0.0.1", actual == 65535 ? 65534 : actual + 1);
    assertThrows(
        IllegalStateException.class, () -> TransportStartup.awaitBound(channels, other, timeout));
  }

  @Test
  void aSocketOnTheWrongAddressCannotPublish() throws Exception {
    var channel = bind();
    var other =
        new InetSocketAddress("127.0.0.2", ((InetSocketAddress) channel.localAddress()).getPort());
    assertThrows(
        IllegalStateException.class, () -> TransportStartup.awaitBound(channels, other, timeout));
  }

  @Test
  void anEmptyKcpSocketListCannotPublish() {
    assertThrows(
        IllegalStateException.class,
        () -> TransportStartup.awaitBound(new KcpServer(), ephemeral, timeout));
  }

  @Test
  void thePinnedKcpAdapterChecksItsActualOwnedChannelList() throws Exception {
    var server = new KcpServer();
    var field = KcpServer.class.getDeclaredField("localAddresss");
    field.setAccessible(true);
    field.set(server, channels);
    var channel = bind();
    assertDoesNotThrow(() -> TransportStartup.awaitBound(server, ephemeral, timeout));
    channel.close().sync();
    assertThrows(
        IllegalStateException.class, () -> TransportStartup.awaitBound(server, ephemeral, timeout));
  }

  @ParameterizedTest
  @ValueSource(longs = {0, -1})
  void aNonPositiveTimeoutCannotPublish(long millis) throws Exception {
    bind();
    assertThrows(
        IllegalArgumentException.class,
        () -> TransportStartup.awaitBound(channels, ephemeral, Duration.ofMillis(millis)));
  }

  @Test
  void aNonDatagramChannelCannotPublishEvenIfActive() {
    channels.add(new EmbeddedChannel());
    assertTrue(channels.getFirst().isActive());
    assertThrows(
        IllegalStateException.class,
        () -> TransportStartup.awaitBound(channels, ephemeral, timeout));
  }

  @Test
  void interruptingStartupFailsAndPreservesTheInterrupt() throws Exception {
    channels.add(group.register(new NioDatagramChannel()).sync().channel());
    var preserved = new AtomicBoolean();
    var result =
        waiter.submit(
            () -> {
              Thread.currentThread().interrupt();
              try {
                var failure =
                    assertThrows(
                        IllegalStateException.class,
                        () -> TransportStartup.awaitBound(channels, ephemeral, timeout));
                assertInstanceOf(InterruptedException.class, failure.getCause());
                preserved.set(Thread.currentThread().isInterrupted());
              } finally {
                Thread.interrupted();
              }
            });
    result.get(2, TimeUnit.SECONDS);
    assertTrue(preserved.get());
  }
}
