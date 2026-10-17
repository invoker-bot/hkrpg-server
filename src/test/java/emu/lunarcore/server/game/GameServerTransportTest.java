package emu.lunarcore.server.game;

import static org.junit.jupiter.api.Assertions.*;

import emu.lunarcore.Config.GameServerConfig;
import emu.lunarcore.server.packet.PacketCodec;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import java.net.*;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import kcp.highway.*;
import org.hkrpg.server.TransportStartup;
import org.hkrpg.server.protocol.ProtocolBridge;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class GameServerTransportTest {
  @ParameterizedTest
  @CsvSource({"4.6.0,8793,1200", "4.6.0,48,1200", "4.2.0,8793,1400"})
  void realKcpReassemblesTheEntireSnapshotAndFollowingFrameInsideTheDatagramBoundary(
      String version, int bodySize, int limit) throws Exception {
    var protocol = ProtocolBridge.forVersion(version);
    var server = new KcpServer();
    var client = new KcpClient();
    var completed = new CompletableFuture<Boolean>();
    var received = new AtomicInteger();
    byte[] body = new byte[bodySize];
    for (int i = 0; i < body.length; i++) body[i] = (byte) (i * 31 + 7);
    byte[] snapshot = PacketCodec.encode(925, new byte[0], body);
    byte[] following = PacketCodec.encode(62, new byte[0], new byte[] {8, 1});
    try {
      server.init(new KcpListener() {
        public void onConnected(Ukcp connection) {}
        public void handleReceive(ByteBuf bytes, Ukcp connection) {
          if (bytes.readUnsignedByte() != 1) throw new IllegalArgumentException("Synthetic request");
          for (byte[] frame : List.of(snapshot, following)) {
            var buffer = Unpooled.wrappedBuffer(frame);
            try { connection.write(buffer); } finally { buffer.release(); }
          }
        }
        public void handleException(Throwable error, Ukcp connection) { completed.completeExceptionally(error); }
        public void handleClose(Ukcp connection) {}
      }, GameServer.createChannelConfig(new GameServerConfig(0), protocol),
          new InetSocketAddress("127.0.0.1", 0));
      TransportStartup.awaitBound(server, new InetSocketAddress("127.0.0.1", 0), Duration.ofSeconds(2));
      var field = KcpServer.class.getDeclaredField("localAddresss");
      field.setAccessible(true);
      var channel = (Channel) ((List<?>) field.get(server)).getFirst();
      var address = (InetSocketAddress) channel.localAddress();
      try (var relay = new Boundary(address, limit)) {
        var config = new ChannelConfig();
        config.nodelay(true, 20, 2, true);
        config.setMtu(limit);
        config.setSndwnd(256);
        config.setRcvwnd(256);
        config.setUseConvChannel(true);
        config.setConv(1234567);
        config.setAckNoDelay(true);
        client.init(config, new KcpListener() {
          public void onConnected(Ukcp connection) {
            var request = Unpooled.wrappedBuffer(new byte[] {1});
            try { connection.write(request); } finally { request.release(); }
          }
          public void handleReceive(ByteBuf bytes, Ukcp connection) {
            try {
              while (bytes.isReadable()) {
                var frame = PacketCodec.decodeNext(bytes);
                assertNotNull(frame, "A complete KCP message must contain the entire frame");
                if (received.getAndIncrement() == 0) {
                  assertEquals(925, frame.cmdId());
                  assertArrayEquals(body, frame.body());
                } else {
                  assertEquals(62, frame.cmdId());
                  assertArrayEquals(new byte[] {8, 1}, frame.body());
                  completed.complete(true);
                }
              }
            } catch (Throwable error) { completed.completeExceptionally(error); }
          }
          public void handleException(Throwable error, Ukcp connection) { completed.completeExceptionally(error); }
          public void handleClose(Ukcp connection) {}
        });
        client.connect(new InetSocketAddress("127.0.0.1", relay.socket.getLocalPort()), config);
        boolean arrived;
        try { arrived = completed.get(3, TimeUnit.SECONDS); }
        catch (TimeoutException timedOut) { arrived = false; }
        assertTrue(arrived, "Snapshot and following frame stalled: largest datagram="
            + relay.largest.get() + ", boundary=" + limit + ", dropped=" + relay.dropped.get());
        assertEquals(2, received.get());
        assertEquals(0, relay.dropped.get());
        assertTrue(relay.largest.get() <= limit);
        if (bodySize > 1200) assertEquals(limit, relay.largest.get());
      }
    } finally {
      client.stop();
      server.stop();
    }
  }

  /** Synthetic loopback boundary; records sizes only, never account data or protocol bodies. */
  private static class Boundary implements AutoCloseable {
    final DatagramSocket socket = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
    final AtomicInteger largest = new AtomicInteger(), dropped = new AtomicInteger();
    final ExecutorService worker = Executors.newSingleThreadExecutor();
    final Future<?> task;

    Boundary(InetSocketAddress server, int limit) throws SocketException {
      task = worker.submit(() -> {
        SocketAddress client = null;
        byte[] buffer = new byte[65535];
        while (!socket.isClosed()) {
          var packet = new DatagramPacket(buffer, buffer.length);
          try {
            socket.receive(packet);
            if (packet.getSocketAddress().equals(server)) {
              largest.accumulateAndGet(packet.getLength(), Math::max);
              if (packet.getLength() > limit) { dropped.incrementAndGet(); continue; }
              if (client != null)
                socket.send(new DatagramPacket(buffer, packet.getLength(), client));
            } else {
              client = packet.getSocketAddress();
              socket.send(new DatagramPacket(buffer, packet.getLength(), server));
            }
          } catch (SocketException closed) {
            if (!socket.isClosed()) throw new CompletionException(closed);
          } catch (Exception error) { throw new CompletionException(error); }
        }
      });
    }

    public void close() throws Exception {
      socket.close();
      worker.shutdown();
      task.get(2, TimeUnit.SECONDS);
      assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS));
    }
  }
}
