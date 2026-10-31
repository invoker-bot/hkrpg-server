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

/** Isolated 500-light-cone sale using real 4.6 KCP frames and a correlated response. */
public final class LoopbackItemSaleBatchProbe {
  private static final byte[] SALE_HEADER = {8, 43};
  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final CompletableFuture<Map<String, Object>> completed = new CompletableFuture<>();
  private final Map<String, Object> result = new LinkedHashMap<>();
  private boolean resume;
  private int step, mail, deletionUpdates, deleted;
  private long started;
  private Set<Integer> originals = Set.of();
  private final Set<Integer> deletedIds = new HashSet<>();

  private static void require(boolean condition, String reason) {
    if (!condition) throw new IllegalStateException(reason);
  }
  private static Object field(DynamicMessage message, String name) {
    return message.getField(message.getDescriptorForType().findFieldByName(name));
  }
  private static int number(DynamicMessage message, String name) { return (Integer) field(message, name); }
  private DynamicMessage.Builder request(String name) {
    return DynamicMessage.newBuilder(protocol.clientSchema().message(name));
  }
  private static void set(DynamicMessage.Builder value, String field, Object data) {
    value.setField(value.getDescriptorForType().findFieldByName(field), data);
  }
  private void send(Ukcp connection, String name, DynamicMessage.Builder value, byte[] header) {
    var packet = Unpooled.wrappedBuffer(PacketCodec.encode(protocol.clientCommand(name), header, value.build().toByteArray()));
    try { connection.write(packet); } finally { packet.release(); }
  }
  private void send(Ukcp connection, String name, DynamicMessage.Builder value) {
    send(connection, name, value, new byte[0]);
  }
  private void query(Ukcp connection, String name, int next) {
    step = next; send(connection, name, request(name));
  }
  private void sale(Ukcp connection) {
    var cost = request("ItemCostData");
    var list = cost.getDescriptorForType().findFieldByName("item_list");
    for (int id : originals) {
      var entry = request("ItemCost"); set(entry, "equipment_unique_id", id);
      cost.addRepeatedField(list, entry.build());
    }
    var value = request("SellItemCsReq");
    set(value, "cost_data", cost.build()); set(value, "HNLKAEBKKBL", true);
    step = 6; started = System.nanoTime(); send(connection, "SellItemCsReq", value, SALE_HEADER);
  }
  private void bag(DynamicMessage message, int count, int stock) {
    var equipment = ((List<?>) field(message, "equipment_list")).stream().map(v -> (DynamicMessage) v).toList();
    require(equipment.size() == count && equipment.stream().allMatch(v -> number(v, "tid") == 20000),
        "Unexpected light-cone inventory");
    if (count == 500) {
      var ids = new HashSet<Integer>();
      for (var item : equipment) ids.add(number(item, "unique_id"));
      require(ids.size() == 500 && !ids.contains(0), "Expected 500 independent light cones");
      originals = Set.copyOf(ids);
    }
    int material = 0;
    for (var value : (List<?>) field(message, "material_list")) {
      var item = (DynamicMessage) value;
      if (number(item, "tid") == 221) material += number(item, "num");
    }
    require(material == stock, "Unexpected returned material count");
    result.put("remainingLightCones", count); result.put("materials", material);
  }
  private void done() {
    result.put("passed", true); result.put("clientGameplayVerified", false);
    result.put("restored", resume); completed.complete(result);
  }
  private void receive(ByteBuf bytes, Ukcp connection) throws Exception {
    while (bytes.isReadable()) {
      var frame = PacketCodec.decodeNext(bytes);
      require(frame != null, "Truncated packet");
      String name = List.of("PlayerGetTokenScRsp", "PlayerLoginScRsp", "PlayerLoginFinishScRsp",
              "GetMailScRsp", "TakeMailAttachmentScRsp", "GetBagScRsp", "PlayerSyncScNotify", "SellItemScRsp")
          .stream().filter(n -> protocol.clientCommand(n) == frame.cmdId()).findFirst().orElse(null);
      if (name == null) continue;
      var message = protocol.clientSchema().parse(name, frame.body());
      require(message.getUnknownFields().asMap().isEmpty(), "Unexpected native response field");
      if (name.equals("PlayerSyncScNotify")) {
        if (step == 6) {
          var ids = (List<?>) field(message, "del_equipment_list");
          if (!ids.isEmpty()) {
            deletionUpdates++; deleted += ids.size();
            for (var id : ids) require(id instanceof Integer && originals.contains(id)
                && deletedIds.add((Integer) id), "Deleted ID differs from selected batch or is repeated");
          }
        }
        continue;
      }
      require(number(message, "retcode") == 0, "Native " + name + " failed at step " + step);
      switch (name) {
        case "PlayerGetTokenScRsp" -> {
          result.put("uid", field(message, "uid")); send(connection, "PlayerLoginCsReq", request("PlayerLoginCsReq"));
        }
        case "PlayerLoginScRsp" -> send(connection, "PlayerLoginFinishCsReq", request("PlayerLoginFinishCsReq"));
        case "PlayerLoginFinishScRsp" -> query(connection, resume ? "GetBagCsReq" : "GetMailCsReq", resume ? 8 : 1);
        case "GetMailScRsp" -> {
          var matches = ((List<?>) field(message, "mail_list")).stream().map(v -> (DynamicMessage) v)
              .filter(v -> field(v, "title").equals("Batch decomposition stock fixture")).toList();
          require(matches.size() == 1, "Stock mail missing");
          mail = number(matches.getFirst(), "id");
          var request = request("TakeMailAttachmentCsReq");
          request.addRepeatedField(request.getDescriptorForType().findFieldByName("mail_id_list"), mail);
          step = 2; send(connection, "TakeMailAttachmentCsReq", request);
        }
        case "TakeMailAttachmentScRsp" -> {
          require(field(message, "succ_mail_id_list").equals(List.of(mail)), "Stock claim failed");
          query(connection, "GetBagCsReq", 5);
        }
        case "GetBagScRsp" -> {
          if (step == 5) { bag(message, 500, 0); sale(connection); }
          else if (step == 7 || step == 8) { bag(message, 0, 500); done(); }
          else throw new IllegalStateException("Unexpected inventory response");
        }
        case "SellItemScRsp" -> {
          require(step == 6 && Arrays.equals(frame.header(), SALE_HEADER), "Response correlation missing");
          require(deletionUpdates == 1 && deleted == 500 && deletedIds.equals(originals),
              "Expected one deletion update, got " + deletionUpdates + " updates for " + deleted + " items");
          var list = (DynamicMessage) field(message, "return_item_list");
          var items = (List<?>) field(list, "item_list");
          require(items.size() == 1 && number((DynamicMessage) items.getFirst(), "item_id") == 221
              && number((DynamicMessage) items.getFirst(), "num") == 500, "Wrong sale reward");
          result.put("deletionUpdates", deletionUpdates); result.put("deleted", deleted);
          result.put("correlatedReply", true); result.put("saleMillis", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
          query(connection, "GetBagCsReq", 7);
        }
      }
    }
  }
  public static void main(String[] args) {
    var client = new KcpClient(); Ukcp connection = null; int exit = 1;
    try {
      var probe = new LoopbackItemSaleBatchProbe();
      var credentials = JsonParser.parseReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).getAsJsonObject();
      probe.resume = credentials.has("resume") && credentials.get("resume").getAsBoolean();
      var token = probe.request("PlayerGetTokenCsReq");
      set(token, "account_uid", credentials.get("accountUid").getAsString());
      set(token, "token", credentials.get("comboToken").getAsString()); set(token, "platform", 1);
      var config = new ChannelConfig(); config.nodelay(true, 40, 2, true); config.setMtu(1400);
      config.setSndwnd(256); config.setRcvwnd(256); config.setUseConvChannel(true);
      config.setConv(ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE));
      config.setTimeoutMillis(60000); config.setAckNoDelay(true);
      client.init(config, new KcpListener() {
        public void onConnected(Ukcp c) { probe.send(c, "PlayerGetTokenCsReq", token); }
        public void handleReceive(ByteBuf b, Ukcp c) {
          try { probe.receive(b, c); } catch (Throwable e) { probe.completed.completeExceptionally(e); }
        }
        public void handleException(Throwable e, Ukcp c) { probe.completed.completeExceptionally(e); }
        public void handleClose(Ukcp c) {
          if (!probe.completed.isDone()) probe.completed.completeExceptionally(new IllegalStateException("Disconnected"));
        }
      });
      connection = client.connect(new InetSocketAddress("127.0.0.1", Integer.parseInt(args[0])), config);
      System.out.write((new Gson().toJson(probe.completed.get(60, TimeUnit.SECONDS)) + "\n").getBytes(StandardCharsets.UTF_8));
      System.out.flush(); exit = 0;
    } catch (Exception e) {
      System.err.println("Batch sale probe failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    } finally { if (connection != null) connection.close(); client.stop(); }
    System.exit(exit);
  }
}
