package org.hkrpg.server;

import com.google.gson.Gson;
import emu.lunarcore.GameConstants;
import emu.lunarcore.LunarCore;
import io.javalin.Javalin;
import java.util.Map;
import java.util.function.BooleanSupplier;

public final class HealthRoutes {
    public static void register(Javalin app) {
        register(app, LunarCore::isReady);
    }

    static void register(Javalin app, BooleanSupplier readiness) {
        app.get("/health/live", ctx -> {
            ctx.contentType("application/json");
            var protocol = org.hkrpg.server.protocol.ActiveProtocol.get();
            ctx.result(new Gson().toJson(Map.of("status", "UP", "server", "hkrpg-server", "clientVersion", protocol.version(),
                "designTarget", org.hkrpg.server.protocol.ProtocolBridge.DESIGN_TARGET, "experimentalProtocol", protocol.experimental(), "clientAccepted", false)));
        });
        app.get("/health/ready", ctx -> {
            boolean ready = readiness.getAsBoolean();
            ctx.status(ready ? 200 : 503).contentType("application/json");
            ctx.result(new Gson().toJson(Map.of("ready", ready, "mode", LunarCore.getServerType().name())));
        });
        app.get("/api/features", ctx -> {
            try (var stream = HealthRoutes.class.getResourceAsStream("/features.json")) {
                if (stream == null) { ctx.status(500).result("Missing feature catalog"); return; }
                ctx.contentType("application/json").result(stream.readAllBytes());
            }
        });
        app.get("/api/feature-backlog", ctx -> {
            try (var stream = HealthRoutes.class.getResourceAsStream("/feature-backlog.json")) {
                if (stream == null) { ctx.status(500).result("Missing feature backlog"); return; }
                ctx.contentType("application/json").result(stream.readAllBytes());
            }
        });
    }
    private HealthRoutes() {}
}
