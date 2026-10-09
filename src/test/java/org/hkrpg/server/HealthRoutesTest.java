package org.hkrpg.server;

import io.javalin.Javalin;
import java.net.URI;
import java.net.http.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HealthRoutesTest {
    @Test void distinguishesLiveFromReadyAndExposesTheFeatureCatalog() throws Exception {
        var app = Javalin.create();
        var ready = new AtomicBoolean();
        HealthRoutes.register(app, ready::get);
        app.start("127.0.0.1", 0);
        try (var client = HttpClient.newHttpClient()) {
            assertEquals(200, get(client, app, "/health/live").statusCode());
            assertEquals(503, get(client, app, "/health/ready").statusCode());
            ready.set(true);
            assertEquals(200, get(client, app, "/health/ready").statusCode());
            var catalog = get(client, app, "/api/features");
            assertEquals(200, catalog.statusCode());
            assertTrue(catalog.body().contains("protocolBaseline"));
        } finally { app.stop(); }
    }
    private HttpResponse<String> get(HttpClient client, Javalin app, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + path)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
