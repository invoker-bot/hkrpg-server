package org.hkrpg.server;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.memory.MemoryBackend;
import emu.lunarcore.Config;
import emu.lunarcore.LunarCore;
import emu.lunarcore.database.DatabaseManager;
import emu.lunarcore.game.account.Account;
import io.javalin.Javalin;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import javax.crypto.Cipher;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class CnPassportRoutesTest {
    @TempDir Path temp;
    MongoServer backend;
    DatabaseManager database;
    Javalin app;
    HttpClient client;
    KeyPair key;
    final Map<Field, Object> saved = new HashMap<>();

    @BeforeEach void setup() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(1024);
        key = generator.generateKeyPair();
        Path privateKey = temp.resolve("passport.der");
        Files.write(privateKey, key.getPrivate().getEncoded());
        for (String name : List.of("config", "accountDatabase")) {
            var field = LunarCore.class.getDeclaredField(name);
            field.setAccessible(true);
            saved.put(field, field.get(null));
        }
        var options = new JsonObject();
        options.addProperty("cnPassportPrivateKeyPath", privateKey.toString());
        var config = new JsonObject();
        config.add("loginOptions", options);
        field("config").set(null, new Gson().fromJson(config, Config.class));
        backend = new MongoServer(new MemoryBackend());
        var address = backend.bind();
        var info = new Config.DatabaseInfo();
        info.useInternal = false;
        info.uri = "mongodb://127.0.0.1:" + address.getPort() + "/?directConnection=true";
        database = new DatabaseManager(info, LunarCore.ServerType.DISPATCH);
        field("accountDatabase").set(null, database);
        app = Javalin.create();
        CnSdkRoutes.register(app);
        app.start("127.0.0.1", 0);
        client = HttpClient.newHttpClient();
    }

    Field field(String name) { return saved.keySet().stream().filter(f -> f.getName().equals(name)).findFirst().orElseThrow(); }

    @AfterEach void cleanup() throws Exception {
        if (client != null) client.close();
        if (app != null) app.stop();
        if (database != null) database.close();
        if (backend != null) backend.shutdownNow();
        for (var entry : saved.entrySet()) entry.getKey().set(null, entry.getValue());
    }

    JsonObject post(String path, Object body) throws Exception {
        String raw = body instanceof String text ? text : new Gson().toJson(body);
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + path))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(raw)).build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), path);
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    String encrypted(String value) throws Exception {
        var cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, key.getPublic());
        return Base64.getEncoder().encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    }

    JsonObject login(String name) throws Exception {
        var response = post("/account/ma-cn-passport/app/loginByPassword",
            Map.of("account", encrypted(name), "password", encrypted("local-test")));
        assertEquals(0, response.get("retcode").getAsInt());
        return response.getAsJsonObject("data");
    }

    @Test void nativePassportRouteReturnsAnErrorEnvelopeForInvalidLogin() throws Exception {
        var response = post("/account/ma-cn-passport/app/loginByPassword", "null");
        assertEquals(-202, response.get("retcode").getAsInt());
        assertEquals(0, database.getDatastore().find(Account.class).count());
    }

    @Test void encryptedNamesSelectSeparateAccountsAndNeverUseTheFixedAppLoginName() throws Exception {
        var first = login("acceptance-one");
        var second = login("acceptance-two");
        assertNotEquals(first.getAsJsonObject("user_info").get("aid"), second.getAsJsonObject("user_info").get("aid"));
        assertEquals("acceptance-one", first.getAsJsonObject("user_info").get("account_name").getAsString());
        assertNotNull(database.getObjectByField(Account.class, "username", "acceptance-one"));
        assertNull(database.getObjectByField(Account.class, "username", "player"));
        assertEquals(2, database.getDatastore().find(Account.class).count());
    }

    @Test void nestedSessionTokensAreBoundToTheActualAccountAndCanReachCombo() throws Exception {
        var data = login("resume-one");
        String uid = data.getAsJsonObject("user_info").get("mid").getAsString();
        var token = data.getAsJsonObject("token");
        var verified = post("/account/ma-cn-session/app/verify", Map.of("mid", uid, "token", token));
        assertEquals(0, verified.get("retcode").getAsInt());
        var other = login("resume-two").getAsJsonObject("user_info").get("mid").getAsString();
        assertEquals(-201, post("/account/ma-cn-session/app/verify", Map.of("mid", other, "token", token)).get("retcode").getAsInt());
        assertEquals(-201, post("/account/ma-cn-session/app/exchange", Map.of("mid", other, "src_token", token, "dst_token_type", 4)).get("retcode").getAsInt());
        var exchange = post("/account/ma-cn-session/app/exchange", Map.of("mid", uid, "src_token", token, "dst_token_type", 4));
        assertEquals(0, exchange.get("retcode").getAsInt());
        var gameToken = exchange.getAsJsonObject("data").getAsJsonObject("token");
        assertEquals(4, gameToken.get("token_type").getAsInt());
        var combo = post("/hkrpg_cn/combo/granter/login/v2/login", Map.of("data", new Gson().toJson(Map.of("uid", uid, "token", gameToken.get("token").getAsString()))));
        assertEquals(0, combo.get("retcode").getAsInt());
        assertFalse(combo.getAsJsonObject("data").get("combo_token").getAsString().isBlank());
    }

    @Test void malformedCiphertextAndSessionRequestsNeverCreateAccounts() throws Exception {
        for (Object body : List.of("null", "[]", "{", "{}", Map.of("account", "plain", "password", "plain"),
                Map.of("account", encrypted(""), "password", encrypted("local-test")),
                Map.of("account", encrypted("good"), "password", "not-ciphertext"))) {
            assertEquals(-202, post("/account/ma-cn-passport/app/loginByPassword", body).get("retcode").getAsInt());
        }
        for (String route : List.of("/account/ma-cn-session/app/verify", "/account/ma-cn-session/app/exchange")) {
            for (String raw : List.of("null", "[]", "{", "{}", "{\"mid\":1,\"token\":\"bad\"}"))
                assertEquals(-202, post(route, raw).get("retcode").getAsInt());
        }
        assertEquals(0, database.getDatastore().find(Account.class).count());
    }

    @Test void missingLocalKeyAndDisabledAutoCreationFailClosed() throws Exception {
        LunarCore.getConfig().serverOptions.autoCreateAccount = false;
        var body = Map.of("account", encrypted("new-name"), "password", encrypted("local-test"));
        assertEquals(-201, post("/account/ma-cn-passport/app/loginByPassword", body).get("retcode").getAsInt());
        Files.delete(temp.resolve("passport.der"));
        assertEquals(-202, post("/account/ma-cn-passport/app/loginByPassword", body).get("retcode").getAsInt());
        assertEquals(0, database.getDatastore().find(Account.class).count());
    }
}
