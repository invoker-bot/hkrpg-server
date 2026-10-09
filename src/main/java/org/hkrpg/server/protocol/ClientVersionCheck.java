package org.hkrpg.server.protocol;

import io.javalin.http.Context;
import java.util.regex.Pattern;

public final class ClientVersionCheck {
    private static final Pattern VERSION = Pattern.compile("(?<!\\d)(\\d+\\.\\d+\\.\\d+)(?!\\d)");
    private static final Pattern NON_RELEASE = Pattern.compile("BETA|DEV|PRE", Pattern.CASE_INSENSITIVE);
    public static boolean accepts(String declared, String active) {
        if (declared == null || declared.isBlank()) return true;
        if (NON_RELEASE.matcher(declared).find()) return false;
        var match = VERSION.matcher(declared);
        return match.find() && match.group(1).equals(active);
    }
    public static boolean rejectMismatch(Context ctx) {
        if (accepts(ctx.queryParam("version"), ActiveProtocol.get().version())) return false;
        ctx.status(400).contentType("application/json")
            .result("{\"error\":\"Client version does not match configured wire protocol\"}");
        return true;
    }
    private ClientVersionCheck() {}
}
