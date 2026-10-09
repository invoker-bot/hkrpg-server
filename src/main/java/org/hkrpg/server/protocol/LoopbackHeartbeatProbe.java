package org.hkrpg.server.protocol;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.server.packet.PacketCodec;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import kcp.highway.*;
import java.net.InetSocketAddress;
import java.util.concurrent.*;

/** Verifies a local KCP path with 4.6 heartbeat bytes; never logs account credentials. */
public final class LoopbackHeartbeatProbe {
    public static void main(String[] args) {
        int exit = 1;
        var client = new KcpClient();
        try {
            int port = Integer.parseInt(args[0]);
            var protocol = ProtocolBridge.forVersion("4.6.0");
            var type = protocol.clientSchema().message("PlayerHeartBeatCsReq");
            long timestamp = 1234567890123L;
            byte[] body = DynamicMessage.newBuilder(type).setField(type.findFieldByName("client_time_ms"), timestamp).build().toByteArray();
            var completed = new CompletableFuture<Boolean>();
            var config = new ChannelConfig();
            config.nodelay(true, 40, 2, true);
            config.setMtu(1400);
            config.setSndwnd(256);
            config.setRcvwnd(256);
            config.setUseConvChannel(true);
            config.setConv(1234567);
            config.setTimeoutMillis(10_000);
            config.setAckNoDelay(true);
            client.init(config, new KcpListener() {
                @Override public void onConnected(Ukcp connection) {
                    var buffer = Unpooled.wrappedBuffer(PacketCodec.encode(44, new byte[0], body));
                    try { connection.write(buffer); } finally { buffer.release(); }
                }
                @Override public void handleReceive(ByteBuf bytes, Ukcp connection) {
                    try {
                        var frame = PacketCodec.decodeNext(bytes);
                        if (frame == null || frame.cmdId() != 62) throw new IllegalStateException("Unexpected heartbeat frame");
                        var response = protocol.clientSchema().parse("PlayerHeartBeatScRsp", frame.body());
                        Object echoed = response.getField(response.getDescriptorForType().findFieldByName("client_time_ms"));
                        if (!Long.valueOf(timestamp).equals(echoed)) throw new IllegalStateException("Heartbeat timestamp changed");
                        completed.complete(true);
                    } catch (Exception ex) { completed.completeExceptionally(ex); }
                }
                @Override public void handleException(Throwable error, Ukcp connection) { completed.completeExceptionally(error); }
                @Override public void handleClose(Ukcp connection) {
                    if (!completed.isDone()) completed.completeExceptionally(new IllegalStateException("Connection closed before heartbeat"));
                }
            });
            client.connect(new InetSocketAddress("127.0.0.1", port), config);
            completed.get(10, TimeUnit.SECONDS);
            System.out.println("{\"passed\":true,\"wireVersion\":\"4.6.0\",\"transport\":\"UDP/KCP\",\"clientGameplayVerified\":false}");
            exit = 0;
        } catch (Exception ex) { System.err.println("Loopback heartbeat failed: " + ex.getMessage()); }
        finally { client.stop(); }
        System.exit(exit);
    }
    private LoopbackHeartbeatProbe() {}
}
