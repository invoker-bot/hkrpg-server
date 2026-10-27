package emu.lunarcore.server.game;

import java.net.InetSocketAddress;

import emu.lunarcore.LunarCore;
import emu.lunarcore.game.account.Account;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.BasePacket;
import emu.lunarcore.server.packet.CmdIdUtils;
import emu.lunarcore.server.packet.SessionState;
import emu.lunarcore.server.packet.PacketCodec;
import emu.lunarcore.util.Utils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.ints.Int2LongMap;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import kcp.highway.Ukcp;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import us.hebi.quickbuf.ProtoMessage;
import org.hkrpg.server.protocol.ActiveProtocol;
import org.hkrpg.server.protocol.ProtocolBridge;
import org.hkrpg.server.protocol.ProtocolException;
import org.hkrpg.server.protocol.UnsupportedInboundCommandException;
import org.hkrpg.server.protocol.VersionedPacketHandler;

@Getter
public class GameSession {
    private final GameServer server;
    private final Int2LongMap packetCooldown;
    private InetSocketAddress address;

    private Account account;
    private Player player;

    @Setter private boolean sendHello = false;

    // Network
    @Getter(AccessLevel.PRIVATE) private Ukcp ukcp;

    // Flags
    private SessionState state = SessionState.WAITING_FOR_TOKEN;
    private boolean useSecretKey;
    private final ProtocolBridge protocol;
    private String rogueFinishSentRun = "";
    @Setter private String composeLimitPeriod = "";
    private final org.hkrpg.server.protocol.MissionNotifications46 missionNotifications =
            new org.hkrpg.server.protocol.MissionNotifications46();
    private final org.hkrpg.server.protocol.ReplyCorrelation46 replyCorrelation =
            new org.hkrpg.server.protocol.ReplyCorrelation46();

    public boolean hasSentRogueFinish(String runId) {
        return rogueFinishSentRun.equals(runId);
    }

    public void markRogueFinishSent(String runId) {
        rogueFinishSentRun = runId;
    }

    protected GameSession(GameServer server) {
        this.server = server;
        this.packetCooldown = new Int2LongOpenHashMap();
        this.protocol = ActiveProtocol.get();
    }

    public GameSession(GameServer server, Ukcp ukcp) {
        this(server);
        this.ukcp = ukcp;
        this.address = this.ukcp.user().getRemoteAddress();
    }

    public int getUid() {
        return this.player.getUid();
    }

    public boolean useSecretKey() {
        return useSecretKey;
    }

    public void setAccount(Account account) {
        this.account = account;
    }

    public void setPlayer(Player player) {
        this.player = player;
        this.player.setSession(this);
        this.getServer().registerPlayer(player);
    }

    public void setUseSecretKey(boolean key) {
        this.useSecretKey = key;
    }

    public void setState(SessionState state) {
        this.state = state;
    }

    public void onConnect() {
        if (LunarCore.getConfig().getLogOptions().connections) {
            LunarCore.getLogger().info("Client connected from " + address.getHostString());
        }
    }

    public void onDisconnect() {
        if (LunarCore.getConfig().getLogOptions().connections) {
            LunarCore.getLogger().info("Client disconnected from " + address.getHostString());
        }

        this.state = SessionState.INACTIVE;

        if (player != null) {
            // Handle player logout event
            player.onLogout();

            // Save first
            player.save();

            // Deregister player from server
            this.getServer().deregisterPlayer(player);
        }
    }

    public void onMessage(ByteBuf packet) {
        try {
            // Decrypt and turn back into a packet
            // Crypto.xor(packet.array(), useSecretKey() ? Crypto.ENCRYPT_KEY : Crypto.DISPATCH_KEY);

            // Decode
            while (packet.readableBytes() > 0) {
                var frame = PacketCodec.decodeNext(packet);
                if (frame == null) return;
                try (var reply = replyCorrelation.enter(getProtocol(), frame.cmdId(), frame.header())) {
                    if (VersionedPacketHandler.handle(this, frame.cmdId(), frame.body())) continue;
                    ProtocolBridge.Packet converted;
                    try {
                        converted = protocol.inbound(frame.cmdId(), frame.body());
                    } catch (UnsupportedInboundCommandException unsupported) {
                        // Match the core's unhandled-command behavior. An optional module query must
                        // not terminate login or discard later frames in this same KCP message.
                        // Never guess a response command, acknowledge success, or log its payload.
                        LunarCore.getLogger().warn("Unhandled {} inbound command: {}", protocol.version(), frame.cmdId());
                        continue;
                    }
                    int opcode = converted.cmdId();
                    byte[] data = converted.body();

                    // Log packet
                    if (LunarCore.getConfig().getLogOptions().packets) {
                        if (!(LunarCore.getConfig().getLogOptions().filterLoopingPackets && CmdIdUtils.IGNORED_LOG_PACKETS.contains(opcode))) {
                            logPacket("RECV", opcode, data);
                        }
                    }

                    // Handle
                    getServer().getPacketHandler().handle(this, opcode, data);
                }
            }
        } catch (Exception e) {
            LunarCore.getLogger().warn("Rejected {} packet from {}: {}", protocol.version(), address, e.getMessage());
            if (e instanceof ProtocolException) { setState(SessionState.INACTIVE); close(); }
        } finally {
            // packet.release();
        }
    }

    public void send(BasePacket packet) {
        // Test
        if (packet.getCmdId() <= 0) {
            if (LunarCore.getConfig().getLogOptions().packets) {
                LunarCore.getLogger().warn("Tried to send packet with missing cmd id!");
            }
            return;
        }

        // Send
        this.send(packet.build());

        // Log
        if (LunarCore.getConfig().getLogOptions().packets) {
            if (!(LunarCore.getConfig().getLogOptions().filterLoopingPackets && CmdIdUtils.IGNORED_LOG_PACKETS.contains(packet.getCmdId()))) {
                logPacket("SEND", packet.getCmdId(), packet.getData());
            }
        }
    }

    /**
     * Sends a cached packet with the specified cmd id. If the packet isnt cacheable, then an empty packet is sent.
     * @param cmdId
     */
    public void send(int cmdId) {
        // Test
        if (cmdId <= 0) {
            if (LunarCore.getConfig().getLogOptions().packets) {
                LunarCore.getLogger().warn("Tried to send packet with missing cmd id!");
            }
            return;
        }

        // Get packet from the server's packet cache. This will allow us to reuse empty packets if needed.
        if (this.ukcp != null) {
            var cached = getServer().getPacketCache().getCachedPacket(cmdId);
            byte[] bytes = new byte[cached.readableBytes()];
            cached.getBytes(cached.readerIndex(), bytes);
            this.send(bytes);
        }

        // Log
        if (LunarCore.getConfig().getLogOptions().packets) {
            if (!(LunarCore.getConfig().getLogOptions().filterLoopingPackets && CmdIdUtils.IGNORED_LOG_PACKETS.contains(cmdId))) {
                logPacket("SEND", cmdId, Utils.EMPTY_BYTE_ARRAY);
            }
        }
    }

    public void send(byte[] bytes) {
        sendFrames(getProtocol().outboundFrames(bytes, getPlayer()));
    }

    public void sendClientPacket(String name, byte[] bytes) {
        var bridge = getProtocol();
        var body = bridge.experimental() ? org.hkrpg.server.protocol.Scene46.enrich(bridge.clientSchema(), name, bytes, getPlayer()) : bytes;
        if (bridge.experimental()) body = org.hkrpg.server.protocol.StoryParty46.enrich(bridge.clientSchema(), name, body, getPlayer());
        if (bridge.experimental()) body = org.hkrpg.server.protocol.PresetLineup46.enrich(bridge.clientSchema(), name, body, getPlayer());
        if (bridge.experimental()) body = org.hkrpg.server.protocol.FormulaLearning46.enrich(bridge.clientSchema(), name, body, getPlayer());
        sendFrames(PacketCodec.encode(bridge.clientCommand(name), Utils.EMPTY_BYTE_ARRAY, body));
    }

    /** Independently verified native commands whose upstream names collide across modes. */
    public void sendClientPacket(int command, byte[] bytes) {
        if (!getProtocol().experimental()) throw new org.hkrpg.server.protocol.ProtocolException("Native packet requires experimental protocol");
        sendFrames(PacketCodec.encode(command, Utils.EMPTY_BYTE_ARRAY, bytes));
    }

    private void sendFrames(byte[] bytes) {
        writeTransport(replyCorrelation.outbound(bytes));
    }

    protected void writeTransport(byte[] bytes) {
        if (this.ukcp != null) {
            ByteBuf buf = Unpooled.wrappedBuffer(bytes);
            this.ukcp.write(buf);
            buf.release();
        }
    }

    public void logPacket(String sendOrRecv, int opcode, ProtoMessage<?> payload) {
        logPacket(sendOrRecv, opcode, payload != null ? payload.toByteArray() : Utils.EMPTY_BYTE_ARRAY);
    }

    public void logPacket(String sendOrRecv, int opcode, byte[] payload) {
        LunarCore.getLogger().info(sendOrRecv + ": " + CmdIdUtils.getCmdIdName(opcode) + " (" + opcode + ")" + System.lineSeparator() + Utils.bytesToHex(payload));
    }

    public void close() {
        if (this.ukcp != null) {
            this.ukcp.close();
        }
    }
}
