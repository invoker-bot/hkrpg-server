package emu.lunarcore.server.http.handlers;

import org.jetbrains.annotations.NotNull;

import emu.lunarcore.LunarCore;
import emu.lunarcore.server.http.HttpServer;

import io.javalin.http.Context;
import io.javalin.http.Handler;

public class QueryDispatchHandler implements Handler {
    private final HttpServer server;

    public QueryDispatchHandler(HttpServer server) {
        this.server = server;
    }

    @Override
    public void handle(@NotNull Context ctx) throws Exception {
        if (org.hkrpg.server.protocol.ClientVersionCheck.rejectMismatch(ctx)) return;
        // Region env_type controls which SDK login UI and endpoints the client
        // selects. CN production/release clients must keep the CN environment.
        String version = ctx.queryParam("version");
        String sdkEnvironment = version != null && (version.startsWith("CNPROD") || version.startsWith("CNREL"))
                ? "0" : "2";
        // Log
        if (LunarCore.getConfig().getLogOptions().connections) {
            LunarCore.getLogger().info("Client request: query_dispatch (SDK environment {})", sdkEnvironment);
        }

        // Send region list to client
        ctx.result(server.getRegionList(sdkEnvironment));
    }

}
