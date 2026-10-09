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

/** Actual source weekly/monthly composition quota, debits and restoration over UDP/KCP. */
public final class LoopbackCompositionLimitProbe {
  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final CompletableFuture<Map<String, Object>> completed = new CompletableFuture<>();
  private final Map<String, Object> result = new LinkedHashMap<>();
  private boolean resume;
  private int step, mail, quotaSnapshots, updates;
  private final Map<Integer, Integer> used = new TreeMap<>();
  private final List<Map<Integer, Integer>> updateSnapshots = new ArrayList<>();

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

  private void compose(Ukcp c, int id, int count, int next, boolean unsupportedConvert) {
    var b = request("ComposeItemCsReq");
    set(b, "compose_id", id);
    set(b, "count", count);
    if (id == 801) {
      var pile = request("PileItem");
      set(pile, "item_id", 110402);
      set(pile, "item_num", count * 2);
      var cost = request("ItemCost");
      set(cost, "pile_item", pile.build());
      var list = request("ItemCostData");
      list.addRepeatedField(list.getDescriptorForType().findFieldByName("item_list"), cost.build());
      set(b, "compose_item_list", list.build());
    }
    if (unsupportedConvert) set(b, "convert_item_list", request("ItemCostData").build());
    step = next;
    send(c, "ComposeItemCsReq", b);
  }

  private Map<Integer, Integer> items(DynamicMessage list) {
    var values = new TreeMap<Integer, Integer>();
    for (var v : (List<?>) field(list, "item_list")) {
      var item = (DynamicMessage) v;
      require(
          values.put(number(item, "item_id"), number(item, "num")) == null,
          "Duplicate output stack");
    }
    return values;
  }

  private void usage(DynamicMessage row) {
    int id = (Integer) row.getField(row.getDescriptorForType().findFieldByNumber(9));
    int count = (Integer) row.getField(row.getDescriptorForType().findFieldByNumber(1));
    require(id > 0 && count >= 0, "Invalid native quota row");
    used.put(id, count);
  }

  private void bag(
      DynamicMessage bag, int monthlyInput, int monthlyOutput, int weeklyInput, int weeklyOutput) {
    var counts = new HashMap<Integer, Integer>();
    for (var v : (List<?>) field(bag, "material_list")) {
      var row = (DynamicMessage) v;
      require(counts.put(number(row, "tid"), number(row, "num")) == null, "Duplicate actual stack");
    }
    require(
        counts.getOrDefault(235, 0) == monthlyInput
            && counts.getOrDefault(236, 0) == monthlyOutput
            && counts.getOrDefault(110402, 0) == weeklyInput
            && counts.getOrDefault(110401, 0) == weeklyOutput,
        "Actual source quota balances differ " + counts);
    require(
        used.getOrDefault(101, -1) == monthlyOutput && used.getOrDefault(801, -1) == weeklyOutput,
        "Native used count differs from real outputs " + used);
    result.put("monthlyInput", monthlyInput);
    result.put("monthlyOutput", monthlyOutput);
    result.put("weeklyInput", weeklyInput);
    result.put("weeklyOutput", weeklyOutput);
    result.put("nativeUsedMonthly", used.get(101));
    result.put("nativeUsedWeekly", used.get(801));
  }

  private void finish() {
    require(
        quotaSnapshots >= 1 && used.size() == 30,
        "Original thirty quota IDs missing from native snapshot");
    require(
        updates == (resume ? 0 : 4),
        "Rejected or resumed request changed quota notifications: " + updates);
    if (!resume)
      require(
          updateSnapshots.equals(
              List.of(Map.of(101, 1), Map.of(101, 2), Map.of(801, 1), Map.of(801, 20))),
          "Wrong used count update history");
    result.put("nativeQuotaSnapshotIds", used.size());
    result.put("nativeQuotaSnapshots", quotaSnapshots);
    result.put("nativeQuotaUpdates", updates);
    result.put("nativeQuotaUpdateHistory", updateSnapshots);
    result.put("savedQuotaAndInventoryRestored", resume);
    result.put("passed", true);
    result.put("clientGameplayVerified", false);
    completed.complete(result);
  }

  private void receive(ByteBuf bytes, Ukcp c) throws Exception {
    while (bytes.isReadable()) {
      var frame = PacketCodec.decodeNext(bytes);
      require(frame != null, "Truncated quota frame");
      String name =
          List.of(
                  "PlayerGetTokenScRsp",
                  "PlayerLoginScRsp",
                  "PlayerLoginFinishScRsp",
                  "GetMailScRsp",
                  "TakeMailAttachmentScRsp",
                  "GetBagScRsp",
                  "ComposeItemScRsp",
                  "ComposeLimitNumCompleteScNotify",
                  "ComposeLimitNumUpdateScNotify")
              .stream()
              .filter(n -> protocol.clientCommand(n) == frame.cmdId())
              .findFirst()
              .orElse(null);
      if (name == null) continue;
      var m = protocol.clientSchema().parse(name, frame.body());
      if (name.equals("ComposeLimitNumCompleteScNotify")) {
        used.clear();
        for (var row : (List<?>) m.getField(m.getDescriptorForType().findFieldByNumber(4))) {
          var value = (DynamicMessage) row;
          int id = (Integer) value.getField(value.getDescriptorForType().findFieldByNumber(9));
          require(!used.containsKey(id), "Duplicate quota ID");
          usage(value);
        }
        quotaSnapshots++;
        continue;
      }
      if (name.equals("ComposeLimitNumUpdateScNotify")) {
        var row = (DynamicMessage) m.getField(m.getDescriptorForType().findFieldByNumber(3));
        usage(row);
        updates++;
        updateSnapshots.add(
            Map.of(
                (Integer) row.getField(row.getDescriptorForType().findFieldByNumber(9)),
                (Integer) row.getField(row.getDescriptorForType().findFieldByNumber(1))));
        continue;
      }
      boolean denied = name.equals("ComposeItemScRsp") && Set.of(8, 14, 16, 21, 23).contains(step);
      require(
          denied ? number(m, "retcode") != 0 : number(m, "retcode") == 0,
          "Wrong quota response name=" + name + " step=" + step + " code=" + number(m, "retcode"));
      switch (name) {
        case "PlayerGetTokenScRsp" -> {
          result.put("uid", field(m, "uid"));
          send(c, "PlayerLoginCsReq", request("PlayerLoginCsReq"));
        }
        case "PlayerLoginScRsp" -> {
          var basic = (DynamicMessage) field(m, "basic_info");
          require(
              number(basic, "world_level") == 3,
              "Isolated source world requirement was not preserved");
          result.put("fixtureWorldLevel", 3);
          send(c, "PlayerLoginFinishCsReq", request("PlayerLoginFinishCsReq"));
        }
        case "PlayerLoginFinishScRsp" ->
            query(c, resume ? "GetBagCsReq" : "GetMailCsReq", resume ? 20 : 1);
        case "GetMailScRsp" -> {
          var matching =
              ((List<?>) field(m, "mail_list"))
                  .stream()
                      .map(v -> (DynamicMessage) v)
                      .filter(v -> field(v, "title").equals("Composition quota stock fixture"))
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
              "Source stock mail not actually claimed");
          require(
              items((DynamicMessage) field(m, "attachment")).equals(Map.of(235, 2400, 110402, 42)),
              "Actual stock grants differ");
          result.put("nativeActualSourceStock", true);
          query(c, "GetBagCsReq", 3);
        }
        case "GetBagScRsp" -> {
          switch (step) {
            case 3 -> {
              bag(m, 2400, 0, 42, 0);
              result.put("nativeThirtyInitialQuotaIds", true);
              compose(c, 101, 1, 4, false);
            }
            case 5 -> {
              bag(m, 1600, 1, 42, 0);
              result.put("nativeMonthlyFirstSourceDebit", true);
              compose(c, 101, 1, 6, false);
            }
            case 7 -> {
              bag(m, 800, 2, 42, 0);
              result.put("nativeMonthlySecondSourceDebit", true);
              compose(c, 101, 1, 8, false);
            }
            case 9 -> {
              bag(m, 800, 2, 42, 0);
              result.put("nativeMonthlyExcessRejectedWithoutDebit", true);
              compose(c, 801, 1, 10, false);
            }
            case 11 -> {
              bag(m, 800, 2, 40, 1);
              result.put("nativeWeeklyFirstSourceDebit", true);
              compose(c, 801, 19, 12, false);
            }
            case 13 -> {
              bag(m, 800, 2, 2, 20);
              result.put("nativeWeeklyBatchSourceDebit", true);
              compose(c, 801, 1, 14, false);
            }
            case 15 -> {
              bag(m, 800, 2, 2, 20);
              result.put("nativeWeeklyExcessRejectedWithoutDebit", true);
              compose(c, 101, 1, 16, true);
            }
            case 17 -> {
              bag(m, 800, 2, 2, 20);
              result.put("nativeUnknownConversionRejectedWithoutDebit", true);
              finish();
            }
            case 20 -> {
              bag(m, 800, 2, 2, 20);
              result.put("nativeSavedQuotaSnapshotRestored", true);
              compose(c, 101, 1, 21, false);
            }
            case 22 -> {
              bag(m, 800, 2, 2, 20);
              result.put("nativeSavedMonthlyExcessRejected", true);
              compose(c, 801, 1, 23, false);
            }
            case 24 -> {
              bag(m, 800, 2, 2, 20);
              result.put("nativeSavedWeeklyExcessRejected", true);
              finish();
            }
            default -> throw new IllegalStateException("Unexpected bag step " + step);
          }
        }
        case "ComposeItemScRsp" -> {
          int id = Set.of(10, 12, 14, 23).contains(step) ? 801 : 101;
          require(number(m, "compose_id") == id, "Quota recipe identity differs");
          int count = denied ? 0 : step == 12 ? 19 : 1;
          require(number(m, "count") == count, "Rejected or actual output count differs");
          require(
              items((DynamicMessage) field(m, "return_item_list"))
                  .equals(denied ? Map.of() : Map.of(id == 101 ? 236 : 110401, count)),
              "Actual quota recipe output differs");
          query(c, "GetBagCsReq", step + 1);
        }
      }
    }
  }

  public static void main(String[] args) {
    var client = new KcpClient();
    Ukcp connection = null;
    int exit = 1;
    try {
      var p = new LoopbackCompositionLimitProbe();
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
                    new IllegalStateException("Composition quota probe disconnected"));
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
          "Composition limit probe failed: "
              + e.getClass().getSimpleName()
              + ": "
              + e.getMessage());
    } finally {
      if (connection != null) connection.close();
      client.stop();
    }
    System.exit(exit);
  }
}
