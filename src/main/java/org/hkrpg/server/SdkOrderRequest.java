package org.hkrpg.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.javalin.http.Context;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bounded extraction of identity and order fields; never log the body or credentials. */
record SdkOrderRequest(String product, String account, String token, String requestId, String roleId) {
  private static final Set<String> NAMES = Set.of("product_id", "productid", "goods_id", "goodsid",
      "open_id", "openid", "account_id", "accountid", "account_uid", "accountuid", "combo_token", "combotoken", "token",
      "request_id", "requestid", "cp_order_id", "cporderid", "order_id", "role_id", "roleid", "game_uid");

  static SdkOrderRequest parse(Context ctx) {
    var values = new HashMap<String, String>();
    addMap(values, ctx.queryParamMap());
    String type = java.util.Objects.toString(ctx.header("Content-Type"), "").toLowerCase(java.util.Locale.ROOT);
    if (type.startsWith("application/x-www-form-urlencoded")) {
      addMap(values, ctx.formParamMap());
    } else {
      String body = ctx.body();
      if (body.length() > 8192) throw new IllegalArgumentException("body-too-long");
      if (!body.isBlank()) {
        var json = JsonParser.parseString(body);
        if (!json.isJsonObject()) throw new IllegalArgumentException("body-not-object");
        flatten(values, json.getAsJsonObject(), 0);
      }
    }
    return new SdkOrderRequest(one(values, "product_id", "productid", "goods_id", "goodsid"),
        one(values, "open_id", "openid", "account_id", "accountid", "account_uid", "accountuid"),
        one(values, "combo_token", "combotoken", "token"),
        one(values, "request_id", "requestid", "cp_order_id", "cporderid", "order_id"),
        one(values, "role_id", "roleid", "game_uid"));
  }

  private static void addMap(Map<String, String> out, Map<String, ? extends List<String>> input) {
    if (input.size() > 128) throw new IllegalArgumentException("too-many-fields");
    for (var entry : input.entrySet()) {
      if (entry.getValue().size() != 1) throw new IllegalArgumentException("duplicate-field");
      put(out, entry.getKey(), entry.getValue().getFirst());
    }
  }

  private static void flatten(Map<String, String> out, JsonObject object, int depth) {
    if (depth > 4 || object.size() > 128) throw new IllegalArgumentException("nested-fields");
    for (var entry : object.entrySet()) {
      JsonElement value = entry.getValue();
      if (value.isJsonObject()) flatten(out, value.getAsJsonObject(), depth + 1);
      else if (value.isJsonPrimitive()) put(out, entry.getKey(), value.getAsString());
      else if (NAMES.contains(entry.getKey().toLowerCase(java.util.Locale.ROOT)))
        throw new IllegalArgumentException("invalid-field");
    }
  }

  private static void put(Map<String, String> out, String raw, String value) {
    String key = raw.toLowerCase(java.util.Locale.ROOT);
    if (!NAMES.contains(key)) return;
    if (value == null || value.length() > 4096) throw new IllegalArgumentException("invalid-field");
    String prior = out.putIfAbsent(key, value);
    if (prior != null && !prior.equals(value)) throw new IllegalArgumentException("conflicting-field");
  }

  private static String one(Map<String, String> values, String... names) {
    String found = null;
    for (var name : names) {
      String candidate = values.get(name);
      if (candidate == null || candidate.isBlank()) continue;
      if (found != null && !found.equals(candidate)) throw new IllegalArgumentException("conflicting-alias");
      found = candidate;
    }
    return found;
  }
}
