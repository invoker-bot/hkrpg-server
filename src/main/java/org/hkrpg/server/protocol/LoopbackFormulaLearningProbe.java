package org.hkrpg.server.protocol;

import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.server.packet.PacketCodec;
import io.netty.buffer.*;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import kcp.highway.*;

/** Actual source recipe book, composition and learned-list restoration over UDP/KCP. */
public final class LoopbackFormulaLearningProbe {
  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final CompletableFuture<Map<String, Object>> completed = new CompletableFuture<>();
  private final Map<String, Object> result = new LinkedHashMap<>();
  private boolean resume;
  private int step, mail;

  private static Object field(DynamicMessage m, String name) {
    return m.getField(m.getDescriptorForType().findFieldByName(name));
  }

  private static int number(DynamicMessage m, String name) {
    return (Integer) field(m, name);
  }

  private static void require(boolean ok, String reason) {
    if (!ok) throw new IllegalStateException(reason);
  }

  private DynamicMessage.Builder request(String name) {
    return DynamicMessage.newBuilder(protocol.clientSchema().message(name));
  }

  private static void set(DynamicMessage.Builder b, String name, Object value) {
    b.setField(b.getDescriptorForType().findFieldByName(name), value);
  }

  private void send(Ukcp c, String name, DynamicMessage.Builder b) {
    var packet =
        Unpooled.wrappedBuffer(
            PacketCodec.encode(protocol.clientCommand(name), new byte[0], b.build().toByteArray()));
    try {
      c.write(packet);
    } finally {
      packet.release();
    }
  }

  private void query(Ukcp c, String name, int next) {
    step = next;
    send(c, name, request(name));
  }

  private void use(Ukcp c, int count, int next) {
    var b = request("UseItemCsReq");
    set(b, "use_item_id", 404005);
    set(b, "use_item_count", count);
    step = next;
    send(c, "UseItemCsReq", b);
  }

  private void compose(Ukcp c, int next) {
    var b = request("ComposeItemCsReq");
    set(b, "compose_id", 2102);
    set(b, "count", 1);
    step = next;
    send(c, "ComposeItemCsReq", b);
  }

  private Map<Integer, Integer> items(DynamicMessage list) {
    var values = new TreeMap<Integer, Integer>();
    for (var v : (List<?>) field(list, "item_list")) {
      var item = (DynamicMessage) v;
      require(
          values.put(number(item, "item_id"), number(item, "num")) == null,
          "Duplicate item in response");
    }
    return values;
  }

  private void bag(DynamicMessage bag, int books, int materials, int food, boolean learned) {
    var counts = new HashMap<Integer, Integer>();
    for (var v : (List<?>) field(bag, "material_list")) {
      var item = (DynamicMessage) v;
      require(
          counts.put(number(item, "tid"), number(item, "num")) == null,
          "Duplicate inventory stack");
    }
    require(
        counts.getOrDefault(404005, 0) == books
            && counts.getOrDefault(408001, 0) == materials
            && counts.getOrDefault(180002, 0) == materials
            && counts.getOrDefault(400005, 0) == food,
        "Actual book/material/output balances changed: " + counts);
    require(
        field(bag, "KOFNOJCMEFA").equals(learned ? List.of(404005) : List.of()),
        "Native learned formula list is wrong");
    require(
        ((List<?>) field(bag, "CAMEGAGKPGL")).isEmpty(),
        "Learned formula went into unrelated native bag field15");
    result.put("remainingBooks", books);
    result.put("remainingIngredients", materials);
    result.put("food", food);
    result.put("learnedFormulas", learned ? List.of(404005) : List.of());
  }

  private void finish() {
    result.put("savedFormulaAndInventoryRestored", resume);
    result.put("passed", true);
    result.put("clientGameplayVerified", false);
    completed.complete(result);
  }

  private void receive(ByteBuf bytes, Ukcp c) throws Exception {
    while (bytes.isReadable()) {
      var frame = PacketCodec.decodeNext(bytes);
      require(frame != null, "Truncated formula frame");
      String name =
          List.of(
                  "PlayerGetTokenScRsp",
                  "PlayerLoginScRsp",
                  "PlayerLoginFinishScRsp",
                  "GetMailScRsp",
                  "TakeMailAttachmentScRsp",
                  "GetBagScRsp",
                  "UseItemScRsp",
                  "ComposeItemScRsp")
              .stream()
              .filter(n -> protocol.clientCommand(n) == frame.cmdId())
              .findFirst()
              .orElse(null);
      if (name == null) continue;
      var m = protocol.clientSchema().parse(name, frame.body());
      boolean denied =
          Set.of(4, 6, 10, 21, 23).contains(step)
              && (name.equals("UseItemScRsp") || name.equals("ComposeItemScRsp"));
      require(
          denied ? number(m, "retcode") != 0 : number(m, "retcode") == 0,
          "Wrong formula response code name="
              + name
              + " step="
              + step
              + " code="
              + number(m, "retcode"));
      switch (name) {
        case "PlayerGetTokenScRsp" -> {
          result.put("uid", field(m, "uid"));
          send(c, "PlayerLoginCsReq", request("PlayerLoginCsReq"));
        }
        case "PlayerLoginScRsp" ->
            send(c, "PlayerLoginFinishCsReq", request("PlayerLoginFinishCsReq"));
        case "PlayerLoginFinishScRsp" ->
            query(c, resume ? "GetBagCsReq" : "GetMailCsReq", resume ? 20 : 1);
        case "GetMailScRsp" -> {
          var matching =
              ((List<?>) field(m, "mail_list"))
                  .stream()
                      .map(v -> (DynamicMessage) v)
                      .filter(v -> field(v, "title").equals("Formula stock fixture"))
                      .toList();
          require(matching.size() == 1, "No unique isolated stock mail");
          mail = number(matching.getFirst(), "id");
          var b = request("TakeMailAttachmentCsReq");
          b.addRepeatedField(b.getDescriptorForType().findFieldByName("mail_id_list"), mail);
          step = 2;
          send(c, "TakeMailAttachmentCsReq", b);
        }
        case "TakeMailAttachmentScRsp" -> {
          require(
              field(m, "succ_mail_id_list").equals(List.of(mail)),
              "Stock mail was not actually claimed");
          require(
              items((DynamicMessage) field(m, "attachment"))
                  .equals(Map.of(404005, 2, 408001, 1, 180002, 1)),
              "Stock mail actual grants differ");
          result.put("nativeActualBookAndMaterialStock", true);
          query(c, "GetBagCsReq", 3);
        }
        case "GetBagScRsp" -> {
          switch (step) {
            case 3 -> {
              bag(m, 2, 1, 0, false);
              compose(c, 4);
            }
            case 5 -> {
              bag(m, 2, 1, 0, false);
              result.put("nativeLockedRecipeCannotDebit", true);
              use(c, 2, 6);
            }
            case 7 -> {
              bag(m, 2, 1, 0, false);
              result.put("nativeBulkBookUseRejected", true);
              use(c, 1, 8);
            }
            case 9 -> {
              bag(m, 1, 1, 0, true);
              result.put("nativeLearnedFormulaField13", true);
              use(c, 1, 10);
            }
            case 11 -> {
              bag(m, 1, 1, 0, true);
              result.put("nativeDuplicateLearningCannotDebit", true);
              compose(c, 12);
            }
            case 13 -> {
              bag(m, 1, 0, 1, true);
              result.put("nativeActualRecipeOutputAndDebit", true);
              finish();
            }
            case 20 -> {
              bag(m, 1, 0, 1, true);
              use(c, 1, 21);
            }
            case 22 -> {
              bag(m, 1, 0, 1, true);
              result.put("nativeSavedDuplicateLearningRejected", true);
              compose(c, 23);
            }
            case 24 -> {
              bag(m, 1, 0, 1, true);
              result.put("nativeSavedMissingIngredientsRejected", true);
              finish();
            }
            default -> throw new IllegalStateException("Unexpected bag step " + step);
          }
        }
        case "UseItemScRsp" -> {
          require(number(m, "use_item_id") == 404005, "Book identity not echoed");
          if (step == 8) {
            require(
                number(m, "use_item_count") == 1 && number(m, "formula_id") == 404005,
                "Native actual formula confirmation absent");
            require(
                items((DynamicMessage) field(m, "return_data")).isEmpty(),
                "Recipe learning fabricated rewards");
            result.put("nativeActualFormulaField15Confirmation", true);
            query(c, "GetBagCsReq", 9);
          } else {
            require(
                number(m, "use_item_count") == 0 && number(m, "formula_id") == 0,
                "Rejected book use claims consumption or unlock");
            query(c, "GetBagCsReq", step == 6 ? 7 : step == 10 ? 11 : 22);
          }
        }
        case "ComposeItemScRsp" -> {
          require(number(m, "compose_id") == 2102, "Wrong recipe identity");
          var actual = items((DynamicMessage) field(m, "return_item_list"));
          require(
              actual.equals(step == 12 ? Map.of(400005, 1) : Map.of()),
              "Composition actual output is wrong");
          query(c, "GetBagCsReq", step == 4 ? 5 : step == 12 ? 13 : 24);
        }
      }
    }
  }

  public static void main(String[] args) {
    var client = new KcpClient();
    Ukcp connection = null;
    int exit = 1;
    try {
      var p = new LoopbackFormulaLearningProbe();
      var credentials =
          JsonParser.parseReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))
              .getAsJsonObject();
      p.resume = credentials.has("resume") && credentials.get("resume").getAsBoolean();

      var token = p.request("PlayerGetTokenCsReq");
      set(token, "account_uid", credentials.get("accountUid").getAsString());
      set(token, "token", credentials.get("comboToken").getAsString());
      set(token, "platform", 1);
      var config = new ChannelConfig();
      config.nodelay(true, 40, 2, true);
      config.setMtu(1400);
      config.setSndwnd(256);
      config.setRcvwnd(256);
      config.setUseConvChannel(true);
      config.setConv(ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE));
      config.setTimeoutMillis(25000);
      config.setAckNoDelay(true);
      client.init(
          config,
          new KcpListener() {
            public void onConnected(Ukcp c) {
              p.send(c, "PlayerGetTokenCsReq", token);
            }

            public void handleReceive(ByteBuf b, Ukcp c) {
              try {
                p.receive(b, c);
              } catch (Throwable e) {
                p.completed.completeExceptionally(e);
              }
            }

            public void handleException(Throwable e, Ukcp c) {
              p.completed.completeExceptionally(e);
            }

            public void handleClose(Ukcp c) {
              if (!p.completed.isDone())
                p.completed.completeExceptionally(
                    new IllegalStateException("Formula probe disconnected"));
            }
          });
      connection =
          client.connect(new InetSocketAddress("127.0.0.1", Integer.parseInt(args[0])), config);
      System.out.write(
          (new Gson().toJson(p.completed.get(20, TimeUnit.SECONDS)) + "\n")
              .getBytes(StandardCharsets.UTF_8));
      System.out.flush();
      exit = 0;
    } catch (Exception e) {
      System.err.println(
          "Formula learning probe failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    } finally {
      if (connection != null) connection.close();
      client.stop();
    }
    System.exit(exit);
  }
}
