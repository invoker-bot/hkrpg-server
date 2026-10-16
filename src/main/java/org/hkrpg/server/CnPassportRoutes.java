package org.hkrpg.server;

import com.google.gson.*;
import emu.lunarcore.LunarCore;
import emu.lunarcore.game.account.*;
import emu.lunarcore.server.http.objects.NewLoginResJson;
import emu.lunarcore.util.AuthTokens;
import io.javalin.Javalin;
import io.javalin.http.Context;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.*;
import javax.crypto.Cipher;

/** Native AccountPlat SDK routes, distinct from the older per-game SDK aliases. */
public final class CnPassportRoutes {
    private static final Gson JSON = new Gson();

    public static void register(Javalin app) {
        app.post("/account/ma-cn-passport/app/loginByPassword", ctx -> handle(ctx, "login"));
        app.post("/account/ma-cn-session/app/verify", ctx -> handle(ctx, "verify"));
        app.post("/account/ma-cn-session/app/exchange", ctx -> handle(ctx, "exchange"));
    }

    private static void handle(Context ctx, String operation) {
        var result = new NewLoginResJson();
        result.retcode = -202;
        result.message = "Invalid local passport request";
        try {
            if (ctx.body().length() > 8192) throw new IllegalArgumentException();
            var body = JsonParser.parseString(ctx.body()).getAsJsonObject();
            if (operation.equals("login")) login(body, result);
            else session(body, operation.equals("exchange"), result);
        } catch (JsonParseException | IllegalStateException | IllegalArgumentException e) {
            // Never include request bodies, account names, passwords or tokens in diagnostics.
        } catch (Exception e) {
            result.retcode = -500;
            result.message = "Local passport unavailable";
        }
        if (LunarCore.getConfig().getLogOptions().connections)
            LunarCore.getLogger().info("CN passport {} retcode={}", operation, result.retcode);
        ctx.contentType("application/json").result(JSON.toJson(result));
    }

    private static String string(JsonObject object, String name) {
        var value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException();
        String text = value.getAsString();
        if (text.isBlank()) throw new IllegalArgumentException();
        return text;
    }

    private static JsonObject object(JsonObject parent, String name) {
        var value = parent.get(name);
        if (value == null || !value.isJsonObject()) throw new IllegalArgumentException();
        return value.getAsJsonObject();
    }

    private static int tokenType(JsonObject object, String name) {
        var value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException();
        return switch (value.getAsString()) {
            case "1" -> 1;
            case "4" -> 4;
            default -> throw new IllegalArgumentException();
        };
    }

    private static String decrypt(String encoded, PrivateKey key) throws GeneralSecurityException {
        if (encoded.length() > 1024) throw new IllegalArgumentException();
        byte[] raw = Base64.getDecoder().decode(encoded);
        var cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.DECRYPT_MODE, key);
        byte[] plain = cipher.doFinal(raw);
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(plain)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            throw new IllegalArgumentException();
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    private static void login(JsonObject body, NewLoginResJson result) {
        String name;
        try {
            String location = LunarCore.getConfig().getLoginOptions().cnPassportPrivateKeyPath;
            if (location == null || location.isBlank()) return;
            var path = Path.of(location);
            if (!Files.isRegularFile(path) || Files.size(path) > 8192) return;
            var key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(path)));
            name = decrypt(string(body, "account"), key);
            String password = decrypt(string(body, "password"), key);
            if (name.isBlank() || name.length() > 128 || name.chars().anyMatch(Character::isISOControl)
                    || password.isBlank() || password.length() > 256) return;
        } catch (GeneralSecurityException | java.io.IOException | IllegalArgumentException e) {
            return;
        }
        // Password semantics remain the emulator's existing local-account convention.
        // The decrypted name selects the account; the old fixed AppLogin account is never used.
        var database = LunarCore.getAccountDatabase();
        Account account = database.getObjectByField(Account.class, "username", name);
        if (account == null && LunarCore.getConfig().getServerOptions().autoCreateAccount) {
            account = AccountHelper.createAccount(name, null, 0);
            if (account == null) account = database.getObjectByField(Account.class, "username", name);
        }
        if (account == null) {
            result.retcode = -201;
            result.message = "Local account not found";
            return;
        }
        success(result, account, account.generateDispatchTokenV2(), 1);
    }

    private static void session(JsonObject body, boolean exchange, NewLoginResJson result) {
        String mid = string(body, "mid");
        if (!mid.matches("[1-9][0-9]{0,9}")) throw new IllegalArgumentException();
        var token = object(body, exchange ? "src_token" : "token");
        int type = tokenType(token, "token_type");
        if (exchange && (type != 1 || tokenType(body, "dst_token_type") != 4)) throw new IllegalArgumentException();
        String value = string(token, "token");
        if (value.length() > 2048) throw new IllegalArgumentException();
        var account = LunarCore.getAccountDatabase().getObjectByField(Account.class, "_id", mid);
        if (account == null || !AuthTokens.matches(account.getDispatchToken(), value)) {
            result.retcode = -201;
            result.message = "Invalid local session";
            return;
        }
        success(result, account, value, exchange ? 4 : type);
    }

    private static void success(NewLoginResJson result, Account account, String token, int type) {
        var data = new NewLoginResJson.VerifyData(account.getUid(), account.getEmail(), token);
        data.token.token_type = type;
        data.user_info.mid = account.getUid();
        data.user_info.account_name = account.getUsername();
        result.retcode = 0;
        result.message = "OK";
        result.data = data;
    }

    private CnPassportRoutes() {}
}
