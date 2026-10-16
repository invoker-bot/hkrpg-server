package org.hkrpg.server;

import emu.lunarcore.server.http.handlers.*;
import io.javalin.Javalin;

/** Auth endpoint aliases for the CN client. Full SDK configuration remains a separate acceptance item. */
public final class CnSdkRoutes {
    public static void register(Javalin app) {
        app.post("/hkrpg_cn/mdk/shield/api/login", new UsernameLoginHandler());
        app.post("/hkrpg_cn/mdk/shield/api/verify", new TokenLoginHandler());
        app.post("/hkrpg_cn/combo/granter/login/v2/login", new ComboTokenGranterHandler());
        app.post("/hkrpg_cn/account/ma-passport/api/appLoginByPassword", new AppLoginHandler());
        CnPassportRoutes.register(app);
    }
    private CnSdkRoutes() {}
}
