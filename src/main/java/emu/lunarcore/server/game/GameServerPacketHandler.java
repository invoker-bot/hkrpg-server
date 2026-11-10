package emu.lunarcore.server.game;

import java.util.Set;

import org.reflections.Reflections;
import org.hkrpg.server.GameDiagnostics;

import emu.lunarcore.LunarCore;
import emu.lunarcore.server.packet.*;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

@SuppressWarnings("unchecked")
public class GameServerPacketHandler {
    private final Int2ObjectMap<PacketHandler> handlers;

    public GameServerPacketHandler() {
        this.handlers = new Int2ObjectOpenHashMap<>();

        this.registerHandlers();
    }

    public void registerPacketHandler(Class<? extends PacketHandler> handlerClass) {
        try {
            Opcodes opcode = handlerClass.getAnnotation(Opcodes.class);

            if (opcode == null || opcode.disabled() || opcode.value() <= 0) {
                return;
            }

            this.handlers.put(opcode.value(), handlerClass.getDeclaredConstructor().newInstance());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void registerHandlers() {
        Reflections reflections = new Reflections(LunarCore.class.getPackageName());
        Set<?> handlerClasses = reflections.getSubTypesOf(PacketHandler.class);

        for (Object obj : handlerClasses) {
            this.registerPacketHandler((Class<? extends PacketHandler>) obj);
        }

        LunarCore.getLogger().info("Game Server registered " + this.handlers.size() + " packet handlers");
    }

    public void handle(GameSession session, int cmdId, byte[] data) {
        PacketHandler handler = this.handlers.get(cmdId);

        if (handler != null) {
            try {
                // Make sure session is ready for packets
                SessionState state = session.getState();

                if (cmdId == CmdId.PlayerHeartBeatCsReq) {
                    // Always continue if packet is ping request
                } else if (cmdId == CmdId.PlayerGetTokenCsReq) {
                    if (state != SessionState.WAITING_FOR_TOKEN) {
                        return;
                    }
                } else if (cmdId == CmdId.PlayerLoginCsReq) {
                    if (state != SessionState.WAITING_FOR_LOGIN) {
                        return;
                    }
                } else {
                    if (state != SessionState.ACTIVE) {
                        return;
                    }
                }

                // Handle packet
                handler.handle(session, data);
            } catch (org.hkrpg.server.protocol.ProtocolException ex) {
                GameDiagnostics.packetFailure(session, cmdId, true, "core-response", ex);
                session.setState(SessionState.INACTIVE);
                session.close();
            } catch (Exception ex) {
                GameDiagnostics.packetFailure(session, cmdId, true, "core-handler", ex);
            }
        } else {
            GameDiagnostics.unhandled(session, cmdId, true);
        }
    }
}
