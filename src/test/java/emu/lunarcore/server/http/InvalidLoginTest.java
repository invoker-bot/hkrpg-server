package emu.lunarcore.server.http;

import com.google.gson.JsonParser;
import emu.lunarcore.server.http.handlers.*;
import io.javalin.Javalin;
import java.net.URI;
import java.net.http.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InvalidLoginTest {
    @Test void invalidAuthenticationRequestsReturnAnErrorEnvelope() throws Exception {
        var app = Javalin.create();
        app.post("/login", new UsernameLoginHandler());
        app.post("/verify", new TokenLoginHandler());
        app.post("/combo", new ComboTokenGranterHandler());
        app.post("/app-login", new AppLoginHandler());
        app.start("127.0.0.1", 0);
        try (var client = HttpClient.newHttpClient()) {
            for (String route : new String[]{"/login", "/verify", "/combo", "/app-login"}) {
                var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + route))
                    .POST(HttpRequest.BodyPublishers.ofString("null")).build();
                var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode());
                assertFalse(response.body().isBlank(), route + " returned an empty success response");
                assertEquals(-202, JsonParser.parseString(response.body()).getAsJsonObject().get("retcode").getAsInt());
            }
        } finally { app.stop(); }
    }
}
