package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.GameData;
import emu.lunarcore.game.inventory.*;
import emu.lunarcore.game.mail.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import emu.lunarcore.server.packet.BasePacket;
import emu.lunarcore.server.packet.recv.*;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.concurrent.*;
import org.bson.types.ObjectId;
import org.hkrpg.server.protocol.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import us.hebi.quickbuf.RepeatedInt;

class MailAttachmentClaimTest {
  final WelcomeMailQuantityTest resources = new WelcomeMailQuantityTest();
  final Actor player = new Actor();
  final Mailbox mailbox = player.getMailbox();

  static class Actor extends Player {
    String document;
    boolean failDelivery;
    boolean throwAfterDelivery;
    int deliveryLimit = Integer.MAX_VALUE;
    int deliveries;
    final Mailbox actualMailbox = new Mailbox(this);
    final Inventory inventory =
        new Inventory(this) {
          public List<GameItem> addItems(List<GameItem> items, boolean hint) {
            deliveries++;
            if (failDelivery) return List.of();
            var actual = new ArrayList<GameItem>();
            var gson = new Gson();
            for (var item : items) {
              var copy = gson.fromJson(gson.toJson(item, GameItem.class), DetachedItem.class);
              copy.setExcel(item.getExcel());
              actual.add(copy);
            }
            var delivered =
                super.addItems(actual.subList(0, Math.min(deliveryLimit, actual.size())), hint);
            if (throwAfterDelivery)
              throw new IllegalStateException("Injected interruption after actual save");
            return delivered;
          }

          public boolean addItem(GameItem item) {
            deliveries++;
            return !failDelivery && super.addItem(item);
          }
        };

    public int getUid() {
      return 123456780;
    }

    public Inventory getInventory() {
      return inventory;
    }

    public Mailbox getMailbox() {
      return actualMailbox;
    }

    public void save() {
      document = new Gson().toJson(this, Player.class);
    }

    public void sendPacket(BasePacket packet) {}
  }

  static class DetachedItem extends GameItem {
    public void save() {}
  }

  static class SavedMail extends Mail {
    int saves, deletes;
    String document;

    SavedMail() {
      super("领取验证", "隔离测试", "读取不等于领取");
    }

    public void save() {
      saves++;
      document = new Gson().toJson(this, Mail.class);
    }

    public void delete() {
      deletes++;
    }
  }

  @BeforeEach
  void setup() throws Exception {
    resources.setup();
    player.setNickname("Mail claim fixture");
    player.document = null;
  }

  @AfterEach
  void cleanup() {
    resources.cleanup();
  }

  GameItem item(int id, int count) {
    return new GameItem(GameData.getItemExcelMap().get(id), count) {
      public void save() {}
    };
  }

  SavedMail send(GameItem... items) throws Exception {
    var mail = new SavedMail();
    var id = Mail.class.getDeclaredField("id");
    id.setAccessible(true);
    id.set(mail, new ObjectId());
    for (var item : items) mail.addAttachment(item);
    mailbox.sendMail(mail);
    return mail;
  }

  RepeatedInt ids(int... values) {
    var result = RepeatedInt.newEmptyInstance();
    for (int id : values) result.add(id);
    return result;
  }

  int stock(int id) {
    var item = player.getInventory().getMaterialByItemId(id);
    return item == null ? 0 : item.getCount();
  }

  class NativeSession extends GameSession {
    DynamicMessage response;

    NativeSession() {
      super(null);
      setState(SessionState.ACTIVE);
    }

    public Player getPlayer() {
      return player;
    }

    public ProtocolBridge getProtocol() {
      return ProtocolBridge.forVersion("4.6.0");
    }

    public void send(int command) {
      send(new BasePacket(command));
    }

    public void sendClientPacket(String name, byte[] data) {
      try {
        response = getProtocol().clientSchema().parse(name, data);
      } catch (Exception error) {
        throw new AssertionError(error);
      }
    }

    public void send(BasePacket packet) {
      String name =
          packet.getCmdId() == CmdId.MarkReadMailScRsp
              ? "MarkReadMailScRsp"
              : packet.getCmdId() == CmdId.TakeMailAttachmentScRsp
                  ? "TakeMailAttachmentScRsp"
                  : null;
      if (name != null)
        sendClientPacket(
            name,
            getProtocol()
                .translate(
                    name,
                    packet.getData() == null ? new byte[0] : packet.getData().toByteArray(),
                    true));
    }
  }

  NativeSession nativeRequest(String name, DynamicMessage.Builder request) throws Exception {
    var session = new NativeSession();
    var raw = request.build().toByteArray();
    if (!VersionedPacketHandler.handle(session, session.getProtocol().clientCommand(name), raw)) {
      var legacy = session.getProtocol().translate(name, raw, false);
      if (name.equals("MarkReadMailCsReq")) new HandlerMarkReadMailCsReq().handle(session, legacy);
      else new HandlerTakeMailAttachmentCsReq().handle(session, legacy);
    }
    return session;
  }

  DynamicMessage.Builder message(String name) {
    return DynamicMessage.newBuilder(
        ProtocolBridge.forVersion("4.6.0").clientSchema().message(name));
  }

  Object field(DynamicMessage message, String name) {
    return message.getField(message.getDescriptorForType().findFieldByName(name));
  }

  @Test
  void nativeReadResponseEchoesTheActualMailId() throws Exception {
    var mail = send(item(235, 2));
    var request = message("MarkReadMailCsReq");
    request.setField(request.getDescriptorForType().findFieldByName("id"), mail.getUniqueId());
    var session = nativeRequest("MarkReadMailCsReq", request);
    assertEquals(mail.getUniqueId(), field(session.response, "id"));
    assertTrue(mail.isRead());
  }

  @Test
  void nativeOptionalRewardCannotSilentlyClaimOrdinaryAttachments() throws Exception {
    var mail = send(item(235, 2));
    var request = message("TakeMailAttachmentCsReq");
    request.addRepeatedField(
        request.getDescriptorForType().findFieldByName("mail_id_list"), mail.getUniqueId());
    request.setField(request.getDescriptorForType().findFieldByName("optional_reward_id"), 1);
    var session = nativeRequest("TakeMailAttachmentCsReq", request);
    assertEquals(List.of(), field(session.response, "succ_mail_id_list"));
    assertEquals(0, stock(235));
  }

  @Test
  void openingNewMailStillAllowsItsActualAttachmentToBeClaimed() throws Exception {
    var mail = send(item(235, 2));
    mailbox.readMail(mail.getUniqueId());
    assertTrue(mail.isRead());
    assertEquals(List.of(mail), mailbox.takeMailAttachments(ids(mail.getUniqueId())));
    assertEquals(2, stock(235));
    assertTrue(mailbox.takeMailAttachments(ids(mail.getUniqueId())).isEmpty());
    assertEquals(2, stock(235));
  }

  @Test
  void failedDeliveryDoesNotReturnSuccessfulMailOrMarkItClaimed() throws Exception {
    var mail = send(item(235, 2));
    player.failDelivery = true;
    assertTrue(mailbox.takeMailAttachments(ids(mail.getUniqueId())).isEmpty());
    assertEquals(0, stock(235));
  }

  @Test
  void claimedMailNoLongerAdvertisesClaimableAttachments() throws Exception {
    var mail = send(item(235, 2));
    assertEquals(1, mailbox.takeMailAttachments(ids(mail.getUniqueId())).size());
    assertEquals(0, mail.toProto().getAttachment().getItemList().length());
    assertEquals(2, stock(235));
  }

  @Test
  void readingDoesNotMakeUnclaimedAttachmentsDeletable() throws Exception {
    var mail = send(item(235, 2));
    mailbox.readMail(mail.getUniqueId());
    assertTrue(mailbox.deleteMail(ids(mail.getUniqueId())).isEmpty());
    assertEquals(0, mail.deletes);
    assertEquals(1, mailbox.size());
  }

  @Test
  void aFullSecondPileRejectsTheWholeMailBeforeGrantingTheFirst() throws Exception {
    var excel = GameData.getItemExcelMap().get(235);
    assertTrue(player.getInventory().addItem(item(235, excel.getPileLimit())));
    int calls = player.deliveries;
    var mail = send(item(231, 3), item(235, 2));
    assertTrue(mailbox.takeMailAttachments(ids(mail.getUniqueId())).isEmpty());
    assertEquals(0, stock(231));
    assertEquals(excel.getPileLimit(), stock(235));
    assertEquals(calls, player.deliveries);
  }

  @Test
  void alreadyOwnedAttachmentCannotBeInsertedAgainOrReassigned() throws Exception {
    var owned = item(20000, 1);
    assertTrue(player.getInventory().addItem(owned));
    int uid = owned.getInternalUid(), calls = player.deliveries;
    var mail = send(owned);
    assertTrue(mailbox.takeMailAttachments(ids(mail.getUniqueId())).isEmpty());
    assertEquals(uid, owned.getInternalUid());
    assertSame(owned, player.getInventory().getItemByUid(uid));
    assertEquals(calls, player.deliveries);
  }

  @Test
  void duplicateRequestedMailIdsPayOnlyOnceWithoutChangingTheRequest() throws Exception {
    var mail = send(item(235, 2));
    var request = ids(mail.getUniqueId(), mail.getUniqueId());
    assertEquals(List.of(mail), mailbox.takeMailAttachments(request));
    assertEquals(2, request.length());
    assertEquals(2, stock(235));
  }

  @Test
  void takeAllLeavesTheClientRequestUntouched() throws Exception {
    var mail = send(item(235, 2));
    var request = ids();
    assertEquals(List.of(mail), mailbox.takeMailAttachments(request));
    assertEquals(0, request.length());
    assertEquals(2, stock(235));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void partialOrInterruptedDeliveryStaysReservedAndCannotBeRetried(boolean interrupted)
      throws Exception {
    var mail = send(item(235, 2), item(231, 3));
    player.deliveryLimit = 1;
    player.throwAfterDelivery = interrupted;
    var result = mailbox.claimMailAttachments(ids(mail.getUniqueId()));
    assertEquals(Retcode.PLAYER_DATA_ERROR, result.retcode());
    assertTrue(result.mails().isEmpty());
    assertTrue(result.items().isEmpty());
    assertEquals(2, stock(235));
    assertEquals(0, stock(231));
    assertEquals(MailAttachmentClaim.Phase.PENDING, mail.getAttachmentClaim().getPhase());
    assertEquals(Map.of(235, 2, 231, 3), mail.getAttachmentClaim().getExpected());
    assertEquals(Map.of(235, 2), mail.getAttachmentClaim().getReceived());
    int calls = player.deliveries;
    player.throwAfterDelivery = false;
    player.deliveryLimit = Integer.MAX_VALUE;
    assertEquals(
        Retcode.SYSTEM_BUSY, mailbox.claimMailAttachments(ids(mail.getUniqueId())).retcode());
    assertEquals(calls, player.deliveries);
    mailbox.readMail(mail.getUniqueId());
    assertTrue(mailbox.deleteMail(ids(mail.getUniqueId())).isEmpty());
    assertEquals(2, stock(235));
    assertEquals(0, stock(231));
  }

  @Test
  void reservationIsSavedBeforeAnyInventoryDelivery() throws Exception {
    var mail = send(item(235, 2));
    player.failDelivery = true;
    mailbox.claimMailAttachments(ids(mail.getUniqueId()));
    assertEquals(3, mail.saves);
    assertEquals(
        MailAttachmentClaim.Phase.PENDING,
        new Gson().fromJson(mail.document, Mail.class).getAttachmentClaim().getPhase());
  }

  @Test
  void repeatedMaterialRowsReturnOnlyTheDeliveredAmountsAndPreserveTheMailCopy() throws Exception {
    var first = item(235, 2);
    var second = item(235, 3);
    var mail = send(first, second);
    var result = mailbox.claimMailAttachments(ids(mail.getUniqueId()));
    assertEquals(Retcode.SUCC, result.retcode());
    assertEquals(List.of(2, 3), result.items().stream().map(GameItem::getCount).toList());
    assertEquals(5, stock(235));
    assertEquals(2, first.getCount());
    assertEquals(3, second.getCount());
    assertEquals(0, first.getOwnerUid());
    assertEquals(0, second.getInternalUid());
    assertEquals(Map.of(235, 5), mail.getAttachmentClaim().getExpected());
    assertEquals(Map.of(235, 5), mail.getAttachmentClaim().getReceived());
  }

  @Test
  void uniqueGearAttributesAndIndependentOwnershipSurviveActualDelivery() throws Exception {
    var gear = item(20000, 1);
    gear.setLevel(7);
    gear.setExp(321);
    gear.setRank(2);
    gear.setPromotion(1);
    gear.setLocked(true);
    var mail = send(gear);
    var result = mailbox.claimMailAttachments(ids(mail.getUniqueId()));
    assertEquals(Retcode.SUCC, result.retcode());
    var received = result.items().getFirst();
    assertNotSame(gear, received);
    assertEquals(7, received.getLevel());
    assertEquals(321, received.getExp());
    assertEquals(2, received.getRank());
    assertEquals(1, received.getPromotion());
    assertTrue(received.isLocked());
    assertEquals(player.getUid(), received.getOwnerUid());
    assertSame(received, player.getInventory().getItemByUid(received.getInternalUid()));
    assertEquals(
        List.of(received.getInternalUid()), mail.getAttachmentClaim().getReceivedUniqueIds());
    assertEquals(0, gear.getOwnerUid());
    assertEquals(0, gear.getInternalUid());
    assertTrue(mailbox.takeMailAttachments(ids(mail.getUniqueId())).isEmpty());
  }

  @Test
  void aDuplicateAttachmentObjectRejectsTheWholeMail() throws Exception {
    var gear = item(20000, 1);
    var mail = send(item(235, 2), gear, gear);
    assertEquals(
        Retcode.MAIL_ATTACHEMENT_INVALID,
        mailbox.claimMailAttachments(ids(mail.getUniqueId())).retcode());
    assertEquals(0, stock(235));
    assertEquals(0, player.deliveries);
    assertEquals(MailAttachmentClaim.Phase.READY, mail.getAttachmentClaim().getPhase());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void legacyReadRecordsRemainClosedButUnreadRecordsCanBeReadThenClaimed(boolean read)
      throws Exception {
    var mail = send(item(235, 2));
    var claim = Mail.class.getDeclaredField("attachmentClaim");
    claim.setAccessible(true);
    claim.set(mail, null);
    var readField = Mail.class.getDeclaredField("isRead");
    readField.setAccessible(true);
    readField.setBoolean(mail, read);
    int saves = mail.saves;
    mailbox.readMail(mail.getUniqueId());
    var result = mailbox.claimMailAttachments(ids(mail.getUniqueId()));
    if (read) {
      assertEquals(Retcode.MAIL_NO_MAIL_TAKE_ATTACHMENT, result.retcode());
      assertEquals(0, stock(235));
      assertNull(mail.getAttachmentClaim());
      assertEquals(saves, mail.saves);
      assertEquals(0, mail.toProto().getAttachment().getItemList().length());
    } else {
      assertEquals(Retcode.SUCC, result.retcode());
      assertEquals(2, stock(235));
      assertEquals(MailAttachmentClaim.Phase.CLAIMED, mail.getAttachmentClaim().getPhase());
    }
  }

  @Test
  void claimedAndEmptyReadMailsCanBeDeletedOnceWithoutChangingTheRequest() throws Exception {
    var first = send(item(235, 2));
    var second = send();
    assertEquals(Retcode.SUCC, mailbox.claimMailAttachments(ids(first.getUniqueId())).retcode());
    mailbox.readMail(second.getUniqueId());
    var request = ids();
    assertEquals(2, mailbox.deleteMail(request).size());
    assertEquals(0, request.length());
    assertEquals(1, first.deletes);
    assertEquals(1, second.deletes);
    assertTrue(mailbox.deleteMail(ids(first.getUniqueId(), second.getUniqueId())).isEmpty());
  }

  @Test
  void expiredMailCannotBeReadOrPaid() throws Exception {
    var mail = send(item(235, 2));
    var expiry = Mail.class.getDeclaredField("expiry");
    expiry.setAccessible(true);
    expiry.setLong(mail, 1);
    assertEquals(Retcode.MAIL_NOT_EXIST, mailbox.markReadMail(mail.getUniqueId()));
    assertEquals(
        Retcode.MAIL_NOT_EXIST, mailbox.claimMailAttachments(ids(mail.getUniqueId())).retcode());
    assertFalse(mail.isRead());
    assertEquals(0, player.deliveries);
  }

  @Test
  void newClaimedAndPendingMailDocumentsSurviveActualMorphiaReload() throws Exception {
    var complete = send(item(235, 2));
    assertEquals(Retcode.SUCC, mailbox.claimMailAttachments(ids(complete.getUniqueId())).retcode());
    var pending = send(item(235, 3), item(231, 4));
    player.deliveryLimit = 1;
    assertEquals(
        Retcode.PLAYER_DATA_ERROR,
        mailbox.claimMailAttachments(ids(pending.getUniqueId())).retcode());
    var server =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client =
        com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "mail-claim-persistence");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store
          .getMapper()
          .map(Mail.class, GameItem.class, emu.lunarcore.game.inventory.GameItemSubAffix.class);
      for (var source : List.of(complete, pending))
        store.save(new Gson().fromJson(source.document, Mail.class));
      var rows = store.find(Mail.class).iterator().toList();
      assertEquals(2, rows.size());
      for (var row : rows) {
        assertEquals(player.getUid(), row.getOwnerUid());
        var state = row.getAttachmentClaim();
        if (row.getId().equals(complete.getId())) {
          assertEquals(MailAttachmentClaim.Phase.CLAIMED, state.getPhase());
          assertEquals(Map.of(235, 2), state.getExpected());
          assertEquals(state.getExpected(), state.getReceived());
          assertTrue(row.isRead());
          assertEquals(0, row.toProto().getAttachment().getItemList().length());
          assertEquals(
              Retcode.MAIL_NO_MAIL_TAKE_ATTACHMENT, MailAttachments.claim(player, row).retcode());
        } else {
          assertEquals(MailAttachmentClaim.Phase.PENDING, state.getPhase());
          assertEquals(Map.of(235, 3, 231, 4), state.getExpected());
          assertEquals(Map.of(235, 3), state.getReceived());
          assertEquals(Map.of(235, 2, 231, 0), state.getBefore());
          assertEquals(Retcode.SYSTEM_BUSY, MailAttachments.claim(player, row).retcode());
        }
      }
    } finally {
      server.shutdownNow();
    }
  }

  @Test
  void concurrentRepeatedRequestsHaveOneActualPayout() throws Exception {
    var mail = send(item(235, 2));
    var pool = Executors.newFixedThreadPool(8);
    try {
      var futures = new ArrayList<Future<MailAttachments.Batch>>();
      for (int i = 0; i < 24; i++)
        futures.add(pool.submit(() -> mailbox.claimMailAttachments(ids(mail.getUniqueId()))));
      int successes = 0;
      for (var future : futures)
        if (future.get(5, TimeUnit.SECONDS).retcode() == Retcode.SUCC) successes++;
      assertEquals(1, successes);
      assertEquals(2, stock(235));
      assertEquals(1, player.deliveries);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void actualWalletIsSavedBeforeMailCanBecomeClaimed() throws Exception {
    var excel =
        new Gson()
            .fromJson(
                "{\"ID\":2,\"ItemMainType\":\"Virtual\",\"PileLimit\":2147483647}",
                emu.lunarcore.data.excel.ItemExcel.class);
    resources.install(GameData.getItemExcelMap(), excel);
    var mail = send(item(2, 1000));
    assertEquals(Retcode.SUCC, mailbox.claimMailAttachments(ids(mail.getUniqueId())).retcode());
    assertNotNull(player.document, "A claimed currency mail must have a saved player wallet");
    assertEquals(1000, new Gson().fromJson(player.document, Player.class).getScoin());
    assertEquals(
        MailAttachmentClaim.Phase.CLAIMED,
        new Gson().fromJson(mail.document, Mail.class).getAttachmentClaim().getPhase());
  }

  Mail operatorMail(String... quantities) {
    var captured = new Mail[1];
    var target =
        new Player() {
          final Mailbox capture =
              new Mailbox(this) {
                public void sendMail(Mail mail) {
                  captured[0] = mail;
                }
              };

          public Mailbox getMailbox() {
            return capture;
          }
        };
    var arguments =
        new emu.lunarcore.command.CommandArgs(null, new ArrayList<>(List.of(quantities))) {
          public Player getTarget() {
            return target;
          }

          public void sendMessage(String message) {}
        };
    new emu.lunarcore.command.commands.MailCommand().execute(arguments);
    assertNotNull(captured[0]);
    return captured[0];
  }

  @ParameterizedTest
  @ValueSource(ints = {20000, 61011})
  void operatorUniqueItemQuantityCreatesEveryIndependentAttachment(int id) {
    var mail = operatorMail(id + ":2");
    assertEquals(2, mail.getAttachments().size());
    assertNotSame(mail.getAttachments().get(0), mail.getAttachments().get(1));
    assertTrue(
        mail.getAttachments().stream()
            .allMatch(item -> item.getItemId() == id && item.getCount() == 1));
  }

  @Test
  void operatorMixedMailPreservesBothUniqueAndPileQuantities() {
    var mail = operatorMail("20000:2", "235:3");
    var amounts = new TreeMap<Integer, Integer>();
    for (var item : mail.getAttachments())
      amounts.merge(item.getItemId(), item.getCount(), Math::addExact);
    assertEquals(Map.of(20000, 2, 235, 3), amounts);
    assertEquals(3, mail.getAttachments().size());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void missingOrNullClaimPhaseDoesNotBecomeAFreshGrant(boolean explicitNull) throws Exception {
    var mail = send(item(235, 2));
    var raw = com.google.gson.JsonParser.parseString(mail.document).getAsJsonObject();
    var receipt = raw.getAsJsonObject("attachmentClaim");
    if (explicitNull) receipt.add("phase", com.google.gson.JsonNull.INSTANCE);
    else receipt.remove("phase");
    var restored = new Gson().fromJson(raw, SavedMail.class);
    assertEquals(Retcode.PLAYER_DATA_ERROR, MailAttachments.claim(player, restored).retcode());
    assertEquals(0, stock(235));
    assertEquals(0, player.deliveries);
    assertEquals(0, restored.saves);
  }

  @Test
  void nativeReadThenTakeReturnsActualAmountsAndEmptyDuplicateClaim() throws Exception {
    var mail = send(item(235, 2), item(235, 3));
    var read = message("MarkReadMailCsReq");
    read.setField(read.getDescriptorForType().findFieldByName("id"), mail.getUniqueId());
    nativeRequest("MarkReadMailCsReq", read);
    var take = message("TakeMailAttachmentCsReq");
    take.addRepeatedField(
        take.getDescriptorForType().findFieldByName("mail_id_list"), mail.getUniqueId());
    var response = nativeRequest("TakeMailAttachmentCsReq", take).response;
    assertEquals(0, field(response, "retcode"));
    assertEquals(List.of(mail.getUniqueId()), field(response, "succ_mail_id_list"));
    var reward = (DynamicMessage) field(response, "attachment");
    assertEquals(
        5,
        ((List<?>) field(reward, "item_list"))
            .stream().mapToInt(r -> (Integer) field((DynamicMessage) r, "num")).sum());
    assertEquals(5, stock(235));
    var duplicate = nativeRequest("TakeMailAttachmentCsReq", take).response;
    assertEquals(Retcode.MAIL_NO_MAIL_TAKE_ATTACHMENT.getVal(), field(duplicate, "retcode"));
    assertEquals(List.of(), field(duplicate, "succ_mail_id_list"));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8})
  void nativeMalformedAndTruncatingAliasesDoNotClaimAttachments(int kind) throws Exception {
    var mail = send(item(235, 2));
    var buffer = new ByteArrayOutputStream();
    var output = CodedOutputStream.newInstance(buffer);
    switch (kind) {
      case 0 -> output.writeUInt64(2, (1L << 32) + mail.getUniqueId());
      case 1 -> output.writeUInt64(2, 0);
      case 2 -> output.writeUInt64(2, -1L);
      case 3 -> output.writeUInt64(2, Integer.MAX_VALUE + 1L);
      case 4 -> {
        output.writeUInt64(2, mail.getUniqueId());
        output.writeUInt64(4, 0);
        output.writeUInt64(4, 0);
      }
      case 5 -> output.writeUInt64(1, mail.getUniqueId());
      case 6 -> {
        for (int i = 0; i < 501; i++) output.writeUInt64(2, mail.getUniqueId());
      }
      case 7 -> {
        output.writeTag(2, 2);
        output.writeUInt32NoTag(2);
        output.writeRawByte(0x80);
      }
      case 8 -> output.writeFixed32(2, mail.getUniqueId());
    }
    output.flush();
    var session = new NativeSession();
    assertTrue(
        VersionedPacketHandler.handle(
            session,
            session.getProtocol().clientCommand("TakeMailAttachmentCsReq"),
            buffer.toByteArray()));
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), field(session.response, "retcode"));
    assertEquals(List.of(), field(session.response, "succ_mail_id_list"));
    assertEquals(0, stock(235));
    assertEquals(MailAttachmentClaim.Phase.READY, mail.getAttachmentClaim().getPhase());
    assertEquals(0, player.deliveries);
  }

  @ParameterizedTest
  @EnumSource(
      value = SessionState.class,
      mode = EnumSource.Mode.EXCLUDE,
      names = {"ACTIVE"})
  void nativeMailRequestsRequireAnActiveAuthenticatedSession(SessionState state) throws Exception {
    var mail = send(item(235, 2));
    var session = new NativeSession();
    session.setState(state);
    for (var request : List.of("TakeMailAttachmentCsReq", "MarkReadMailCsReq", "DelMailCsReq"))
      assertTrue(
          VersionedPacketHandler.handle(
              session, session.getProtocol().clientCommand(request), new byte[0]));
    assertNull(session.response);
    assertFalse(mail.isRead());
    assertEquals(0, player.deliveries);
  }
}
