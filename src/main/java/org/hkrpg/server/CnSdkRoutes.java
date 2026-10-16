package org.hkrpg.server;

import emu.lunarcore.server.http.handlers.*;
import emu.lunarcore.LunarCore;
import com.google.gson.JsonParser;
import io.javalin.Javalin;
import io.javalin.http.Handler;

/** Auth endpoint aliases for the CN client. Full SDK configuration remains a separate acceptance item. */
public final class CnSdkRoutes {
    public static void register(Javalin app) {
        app.post("/hkrpg_cn/mdk/shield/api/login", tracked(new UsernameLoginHandler(), "login"));
        app.post("/hkrpg_cn/mdk/shield/api/verify", tracked(new TokenLoginHandler(), "verify"));
        app.post("/hkrpg_cn/combo/granter/login/v2/login", tracked(new ComboTokenGranterHandler(), "combo"));
        app.post("/hkrpg_cn/account/ma-passport/api/appLoginByPassword", new AppLoginHandler());
        CnPassportRoutes.register(app);
        // The managed PC SDK loads these before using its legacy MDK login URL.
        // Keep the bundled CN identity (app 8 / hkrpg_cn), without changing auth.
        app.get("/hkrpg_cn/mdk/shield/api/loadConfig", new HttpJsonResponse("""
            {"retcode":0,"message":"OK","data":{"id":8,"game_key":"hkrpg_cn",
            "client":"PC","identity":"I_IDENTITY","guest":false,"server_guest":false,
            "scene":"S_NORMAL","name":"崩坏：星穹铁道","thirdparty":[],"thirdparty_login_configs":{}}}
            """));
        app.get("/hkrpg_cn/combo/granter/api/getConfig", new HttpJsonResponse("""
            {"retcode":0,"message":"OK","data":{"protocol":true,"qr_enabled":false,
            "log_level":"INFO","announce_url":"","app_name":"崩坏：星穹铁道",
            "enable_announce_pic_popup":false,"functional_switch_configs":{}}}
            """));
        app.get("/hkrpg_cn/mdk/agreement/api/getAgreementInfos",
            new HttpJsonResponse("{\"retcode\":0,\"message\":\"OK\",\"data\":{\"marketing_agreements\":[]}}"));
        app.post("/hkrpg_cn/combo/granter/api/compareProtocolVersion",
            new HttpJsonResponse("{\"retcode\":0,\"message\":\"OK\",\"data\":{\"modified\":false,\"protocol\":null}}"));
    }

    private static Handler tracked(Handler handler, String operation) {
        return ctx -> {
            handler.handle(ctx);
            if (LunarCore.getConfig().getLogOptions().connections) {
                try {
                    int code = JsonParser.parseString(ctx.result()).getAsJsonObject().get("retcode").getAsInt();
                    LunarCore.getLogger().info("CN MDK {} retcode={}", operation, code);
                } catch (RuntimeException ignored) {
                    // Diagnostics must not affect the response or expose any payload.
                }
            }
        };
    }
    private CnSdkRoutes() {}
}
