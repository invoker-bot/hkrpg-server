package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.inventory.GameItem;
import emu.lunarcore.game.mail.Mail;
import emu.lunarcore.server.packet.Retcode;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

/** Real resource rows; player ownership and inventory match the blocked welcome mail. */
class MailDuplicateAvatarTest {
  final MailAttachmentClaimTest support = new MailAttachmentClaimTest();

  @BeforeEach
  void setup() throws Exception {
    support.setup();
    try (var input = getClass().getResourceAsStream("/fixtures/mail-avatar-4.6.json")) {
      assertNotNull(input);
      var fixture =
          JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8))
              .getAsJsonObject();
      for (var raw : fixture.getAsJsonArray("items"))
        support.resources.install(
            GameData.getItemExcelMap(), ResourceLoader.getGson().fromJson(raw, ItemExcel.class));
      for (var raw : fixture.getAsJsonArray("avatars"))
        support.resources.install(
            GameData.getAvatarExcelMap(),
            ResourceLoader.getGson().fromJson(raw, AvatarExcel.class));
    }
  }

  @AfterEach
  void cleanup() {
    support.cleanup();
  }

  GameAvatar own(int id, int rank, int pending) {
    var avatar = new GameAvatar(GameData.getAvatarExcelMap().get(id));
    avatar.setOwner(support.player);
    avatar.getData().setRank(rank);
    support.player.getAvatars().getAvatars().put(id, avatar);
    if (pending > 0) {
      support.player.getInventory().addItems(List.of(support.item(id + 10000, pending)), false);
    }
    support.player.deliveries = 0;
    return avatar;
  }

  MailAttachmentClaimTest.SavedMail send(int... pairs) throws Exception {
    var items = new ArrayList<GameItem>();
    for (int i = 0; i < pairs.length; i += 2) items.add(support.item(pairs[i], pairs[i + 1]));
    return support.send(items.toArray(GameItem[]::new));
  }

  MailAttachments.Batch claim(Mail mail) {
    return support.mailbox.claimMailAttachments(support.ids(mail.getUniqueId()));
  }

  Map<Integer, Integer> amounts(List<GameItem> items) {
    var result = new TreeMap<Integer, Integer>();
    items.forEach(item -> result.merge(item.getItemId(), item.getCount(), Math::addExact));
    return result;
  }

  @Test
  void exactWelcomeGiftWithSixStoredEidolonsCanBeClaimedOnlyOnce() throws Exception {
    var march = own(1001, 0, 6);
    var dan = own(1002, 0, 6);
    var mail = send(2, 1000000, 101, 100, 102, 100, 1001, 1, 1002, 1);
    var original = amounts(mail.getAttachments());
    support.mailbox.markReadMail(mail.getUniqueId());
    var result = claim(mail);
    assertEquals(Retcode.SUCC, result.retcode());
    assertEquals(List.of(mail), result.mails());
    var expected = Map.of(2, 1000000, 101, 100, 102, 100, 252, 40);
    assertEquals(expected, amounts(result.items()));
    assertEquals(expected, mail.getAttachmentClaim().getExpected());
    assertEquals(expected, mail.getAttachmentClaim().getReceived());
    assertEquals(1000000, support.player.getScoin());
    assertEquals(100, support.stock(101));
    assertEquals(100, support.stock(102));
    assertEquals(40, support.stock(252));
    assertEquals(6, support.stock(11001));
    assertEquals(6, support.stock(11002));
    assertSame(march, support.player.getAvatars().getAvatars().get(1001));
    assertSame(dan, support.player.getAvatars().getAvatars().get(1002));
    assertEquals(original, amounts(mail.getAttachments()));
    var persisted = new Gson().fromJson(mail.document, Mail.class);
    assertEquals(MailAttachmentClaim.Phase.CLAIMED, persisted.getAttachmentClaim().getPhase());
    assertEquals(expected, persisted.getAttachmentClaim().getReceived());
    assertEquals(Retcode.MAIL_NO_MAIL_TAKE_ATTACHMENT, claim(mail).retcode());
    assertEquals(40, support.stock(252));
    assertEquals(1, support.player.deliveries);
  }

  @ParameterizedTest
  @CsvSource({"0,0", "3,2", "5,0"})
  void ownedCharacterWithRoomGivesOneEidolonWithoutReplacingTheCharacter(int rank, int pending)
      throws Exception {
    var avatar = own(1001, rank, pending);
    var mail = send(1001, 1, 235, 2);
    var result = claim(mail);
    assertEquals(Retcode.SUCC, result.retcode());
    assertEquals(Map.of(11001, 1, 235, 2), amounts(result.items()));
    assertEquals(pending + 1, support.stock(11001));
    assertEquals(rank, avatar.getRank());
    assertEquals(0, support.stock(252));
  }

  @ParameterizedTest
  @CsvSource({"1001,20", "1003,100"})
  void maxedCharacterGivesTheExistingRarityBasedOverflowCurrency(int id, int amount)
      throws Exception {
    own(id, 6, 0);
    var result = claim(send(id, 1));
    assertEquals(Retcode.SUCC, result.retcode());
    assertEquals(Map.of(252, amount), amounts(result.items()));
    assertEquals(amount, support.stock(252));
  }

  @ParameterizedTest
  @CsvSource({"4,2,0", "5,1,20"})
  void explicitEidolonsInTheSameMailCountTowardTheConversionLimit(
      int rank, int eidolons, int currency) throws Exception {
    own(1001, rank, 0);
    var result = claim(send(1001, 1, 11001, 1));
    assertEquals(Retcode.SUCC, result.retcode());
    assertEquals(eidolons, support.stock(11001));
    assertEquals(currency, support.stock(252));
    assertEquals(eidolons + currency, result.items().stream().mapToInt(GameItem::getCount).sum());
  }

  @Test
  void anUnownedCardStillCreatesAnOwnedCharacterAlongsideConvertedAndOrdinaryItems()
      throws Exception {
    own(1001, 0, 0);
    var result = claim(send(1001, 1, 1002, 1, 235, 2));
    assertEquals(Retcode.SUCC, result.retcode());
    assertEquals(Map.of(11001, 1, 1002, 1, 235, 2), amounts(result.items()));
    assertEquals(
        support.player.getUid(), support.player.getAvatars().getAvatars().get(1002).getOwnerUid());
    assertEquals(2, support.player.getAvatars().getAvatarCount());
  }

  @ParameterizedTest
  @ValueSource(ints = {11001, 252})
  void missingConversionTargetRejectsTheEntireMailBeforeReservationOrDelivery(int missing)
      throws Exception {
    own(1001, missing == 252 ? 6 : 0, 0);
    GameData.getItemExcelMap().keySet().remove(missing);
    var mail = send(235, 2, 1001, 1);
    assertEquals(Retcode.MAIL_ATTACHEMENT_INVALID, claim(mail).retcode());
    assertEquals(0, support.stock(235));
    assertEquals(0, support.player.deliveries);
    assertEquals(MailAttachmentClaim.Phase.READY, mail.getAttachmentClaim().getPhase());
  }

  @Test
  void overflowingConvertedCurrencyRejectsEverythingBeforeCreditingTheWallet() throws Exception {
    own(1001, 6, 0);
    var limit = GameData.getItemExcelMap().get(252).getPileLimit();
    support.player.getInventory().addItems(List.of(support.item(252, limit - 19)), false);
    support.player.deliveries = 0;
    var mail = send(2, 1000, 1001, 1);
    assertEquals(Retcode.MAIL_ATTACHEMENT_INVALID, claim(mail).retcode());
    assertEquals(0, support.player.getScoin());
    assertEquals(limit - 19, support.stock(252));
    assertEquals(0, support.player.deliveries);
  }

  @Test
  void aConversionTargetCannotSilentlyClampThePromisedReward() throws Exception {
    own(1001, 6, 0);
    support.resources.install(
        GameData.getItemExcelMap(),
        new Gson()
            .fromJson(
                "{\"ID\":252,\"ItemMainType\":\"Material\",\"ItemSubType\":\"Material\",\"PileLimit\":19}",
                ItemExcel.class));
    var mail = send(235, 2, 1001, 1);
    assertEquals(Retcode.MAIL_ATTACHEMENT_INVALID, claim(mail).retcode());
    assertEquals(0, support.stock(235));
    assertEquals(0, support.stock(252));
    assertEquals(MailAttachmentClaim.Phase.READY, mail.getAttachmentClaim().getPhase());
  }

  @Test
  void interruptedConvertedDeliveryStaysPendingAndDoesNotReplayAnyReward() throws Exception {
    own(1001, 6, 0);
    var mail = send(235, 2, 1001, 1);
    support.player.deliveryLimit = 1;
    assertEquals(Retcode.PLAYER_DATA_ERROR, claim(mail).retcode());
    assertEquals(Map.of(235, 2, 252, 20), mail.getAttachmentClaim().getExpected());
    assertEquals(Map.of(235, 2), mail.getAttachmentClaim().getReceived());
    assertEquals(MailAttachmentClaim.Phase.PENDING, mail.getAttachmentClaim().getPhase());
    assertEquals(Retcode.SYSTEM_BUSY, claim(mail).retcode());
    assertEquals(2, support.stock(235));
    assertEquals(0, support.stock(252));
  }

  @Test
  void nativeTakeResponseReportsActualConvertedItemsAndSuccessfulMail() throws Exception {
    own(1001, 6, 0);
    var mail = send(1001, 1, 235, 2);
    var request = support.message("TakeMailAttachmentCsReq");
    request.addRepeatedField(
        request.getDescriptorForType().findFieldByName("mail_id_list"), mail.getUniqueId());
    var response = support.nativeRequest("TakeMailAttachmentCsReq", request).response;
    assertEquals(0, support.field(response, "retcode"));
    assertEquals(List.of(mail.getUniqueId()), support.field(response, "succ_mail_id_list"));
    assertEquals(20, support.stock(252));
    assertEquals(
        2,
        ((List<?>)
                support.field(
                    (com.google.protobuf.DynamicMessage) support.field(response, "attachment"),
                    "item_list"))
            .size());
  }

  @ParameterizedTest
  @CsvSource({"0,6,11001,1", "6,0,252,20"})
  void duplicateUsesTheMailedPathRankWhileAnotherPathIsActive(
      int mailedRank, int activeRank, int rewardId, int rewardCount) throws Exception {
    var avatar = own(1001, 0, 0);
    var mailed =
        new emu.lunarcore.game.avatar.AvatarMultiPath(
            support.player, GameData.getAvatarExcelMap().get(1001));
    var active =
        new emu.lunarcore.game.avatar.AvatarMultiPath(
            support.player, GameData.getAvatarExcelMap().get(1224));
    mailed.getData().setRank(mailedRank);
    active.getData().setRank(activeRank);
    support.player.getAvatars().getMultiPaths().put(1001, mailed);
    support.player.getAvatars().getMultiPaths().put(1224, active);
    avatar.setMultiPath(active);
    var result = claim(send(1001, 1));
    assertEquals(Retcode.SUCC, result.retcode());
    assertEquals(Map.of(rewardId, rewardCount), amounts(result.items()));
    assertSame(active, avatar.getMultiPath());
    assertEquals(activeRank, active.getRank());
    assertEquals(mailedRank, mailed.getRank());
  }
}
