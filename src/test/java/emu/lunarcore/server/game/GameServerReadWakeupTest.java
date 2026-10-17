package emu.lunarcore.server.game;

import static org.junit.jupiter.api.Assertions.*;

import emu.lunarcore.Config.GameServerConfig;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.socket.DatagramPacket;
import java.net.SocketAddress;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.*;
import kcp.highway.*;
import kcp.highway.threadPool.IMessageExecutor;
import org.hkrpg.server.protocol.ProtocolBridge;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class GameServerReadWakeupTest {
  @ParameterizedTest
  @CsvSource({"4.6.0,1", "4.6.0,4", "4.2.0,1", "4.2.0,4"})
  void datagramsReceivedDuringAHandlerAreDeliveredWithoutAnotherDatagram(
      String version, int queued) throws Exception {
    var config = GameServer.createChannelConfig(new GameServerConfig(0), ProtocolBridge.forVersion(version));
    config.setConv(1234567);
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var closed = new CountDownLatch(1);
    var complete = new CompletableFuture<List<Integer>>();
    var values = new CopyOnWriteArrayList<Integer>();
    var listener = new KcpListener() {
      public void onConnected(Ukcp connection) {}
      public void handleReceive(ByteBuf bytes, Ukcp connection) {
        try {
          int value = bytes.readUnsignedByte();
          values.add(value);
          if (value == 0) {
            started.countDown();
            if (!release.await(3, TimeUnit.SECONDS)) throw new AssertionError("Handler was not released");
          }
          if (values.size() == queued + 1) complete.complete(List.copyOf(values));
        } catch (Throwable error) { complete.completeExceptionally(error); }
      }
      public void handleClose(Ukcp connection) { closed.countDown(); }
      public void handleException(Throwable error, Ukcp connection) { complete.completeExceptionally(error); }
    };
    var pool = config.getiMessageExecutorPool();
    var receiver = new Receiver(listener, pool.getIMessageExecutor(), config);
    var datagrams = new java.util.ArrayList<ByteBuf>();
    try {
      var first = push(0);
      datagrams.add(first);
      receiver.accept(first);
      assertTrue(started.await(2, TimeUnit.SECONDS), "First KCP payload did not reach the handler");
      for (int i = 1; i <= queued; i++) {
        var datagram = push(i);
        datagrams.add(datagram);
        receiver.accept(datagram);
      }
      assertEquals(queued, receiver.pending(), "The new datagrams must arrive while the reader is busy");
      release.countDown();
      List<Integer> actual;
      try { actual = complete.get(1, TimeUnit.SECONDS); }
      catch (TimeoutException stalled) {
        actual = List.copyOf(values);
      }
      assertEquals(java.util.stream.IntStream.rangeClosed(0, queued).boxed().toList(), actual,
          "The last datagrams stalled without a third packet or client retransmission");
      assertEquals(0, receiver.pending());
    } finally {
      release.countDown();
      receiver.close(false);
      assertTrue(closed.await(2, TimeUnit.SECONDS), "KCP close was not processed");
      pool.stop();
    }
    assertTrue(datagrams.stream().allMatch(buffer -> buffer.refCnt() == 0),
        "Consumed and closed datagrams must release their retained bytes");
  }

  // The pinned KCP wire uses an eight-byte big-endian conversation followed by LE fields.
  // Inject each packet only once: retransmission would hide the missing reader wakeup.
  private static ByteBuf push(int sequence) {
    return Unpooled.buffer(29).writeLong(1234567).writeByte(81).writeByte(0)
        .writeShortLE(256).writeIntLE(sequence + 1).writeIntLE(sequence).writeIntLE(0)
        .writeIntLE(1).writeByte(sequence);
  }

  private static final class Receiver extends Ukcp {
    Receiver(KcpListener listener, IMessageExecutor executor, ChannelConfig config) {
      super((bytes, ignored) -> bytes.release(), listener, executor, config, new IChannelManager() {
        public Ukcp get(DatagramPacket packet) { return null; }
        public void New(SocketAddress address, Ukcp connection, DatagramPacket packet) {}
        public void del(Ukcp connection) {}
        public Collection<Ukcp> getAll() { return List.of(); }
      });
    }
    void accept(ByteBuf bytes) { read(bytes); }
    int pending() { return getReadBuffer().size(); }
  }
}
