package org.hkrpg.server.protocol;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.game.inventory.GameItem;
import emu.lunarcore.proto.ItemListOuterClass.ItemList;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.SessionState;
import emu.lunarcore.server.packet.send.PacketGetQuestDataScRsp;
import java.io.IOException;
import java.util.*;

/** 4.6 commands that have no legacy Quickbuf counterpart. */
public final class VersionedPacketHandler {
  public static boolean handle(GameSession session, int command, byte[] bytes) throws IOException {
    var protocol = session.getProtocol();
    if (!protocol.experimental()) return false;
    if (Tutorial46.handle(session, command, bytes)) return true;
    if (PlayerReturn46.handle(session, command, bytes)) return true;
    if (LoginBootstrap46.handle(session, command, bytes)) return true;
    if (TextJoin46.handle(session, command, bytes)) return true;
    if (GachaInitial46.handle(session, command, bytes)) return true;
    if (CavalryActivity46.handle(session, command, bytes)) return true;
    if (TimelineActivity46.handle(session, command, bytes)) return true;
    if (PropTimeline46.handle(session, command, bytes)) return true;
    if (RaidDifficulty46.handle(session, command, bytes)) return true;
    if (LocalLegend46.handle(session, command, bytes)) return true;
    if (BattleResult46.handle(session, command, bytes)) return true;
    if (SceneEventBattle46.handle(session, command, bytes)) return true;
    if (MemoryChallenge46.handle(session, command, bytes)) return true;
    if (Profile46.handle(session, command, bytes)) return true;
    if (BookCollection46.handle(session, command, bytes)) return true;
    if (PlayerOutfit46.handle(session, command, bytes)) return true;
    if (LineupLeader46.handle(session, command, bytes)) return true;
    if (HeartDial46.handle(session, command, bytes)) return true;
    if (Cocoon46.handle(session, command, bytes)) return true;
    if (Music46.handle(session, command, bytes)) return true;
    if (ClockPark46.handle(session, command, bytes)) return true;
    if (AetherDivide46.handle(session, command, bytes)) return true;
    if (BoxingClub46.handle(session, command, bytes)) return true;
    if (MatchThree46.handle(session, command, bytes)) return true;
    if (DiceCombat46.handle(session, command, bytes)) return true;
    if (DicePve46.handle(session, command, bytes)) return true;
    if (DiceMainPageRoll46.handle(session, command, bytes)) return true;
    if (DiceProgression46.handle(session, command, bytes)) return true;
    if (OfferingQuery46.handle(session, command, bytes)) return true;
    if (KraterOffering46.handle(session, command, bytes)) return true;
    if (Offering46.handle(session, command, bytes)) return true;
    if (FirstTalk46.handle(session, command, bytes)) return true;
    if (SceneInteraction46.handle(session, command, bytes)) return true;
    if (SceneEntityTeleport46.handle(session, command, bytes)) return true;
    if (SceneMap46.handle(session, command, bytes)) return true;
    if (Picture46.handle(session, command, bytes)) return true;
    if (Expedition46.handle(session, command, bytes)) return true;
    if (Mail46.handle(session, command, bytes)) return true;
    if (TrainWorld46.handle(session, command, bytes)) return true;
    if (ChessRogue46.handle(session, command, bytes)) return true;
    if (Universe46.handle(session, command, bytes)) return true;
    if (RogueHandbook46.handle(session, command, bytes)) return true;
    if (RogueHandbookReward46.handle(session, command, bytes)) return true;
    if (RogueDialogue46.handle(session, command, bytes)) return true;
    if (RogueRevival46.handle(session, command, bytes)) return true;
    if (RogueEnhancement46.handle(session, command, bytes)) return true;
    if (RogueBuffShop46.handle(session, command, bytes)) return true;
    if (RogueMiracleShop46.handle(session, command, bytes)) return true;
    if (RogueDownload46.handle(session, command, bytes)) return true;
    if (StoryParty46.handle(session, command, bytes)) return true;
    if (RogueLineup46.handle(session, command, bytes)) return true;
    if (FormalLineup46.handle(session, command, bytes)) return true;
    if (RogueTalent46.handle(session, command, bytes)) return true;
    if (CycleScore46.handle(session, command, bytes)) return true;
    if (Gameplay46.handle(session, command, bytes)) return true;
    if (Parkour46.handle(session, command, bytes)) return true;
    if (WolfBro46.handle(session, command, bytes)) return true;
    if (Mascot46.handle(session, command, bytes)) return true;
    if (WorldGroupProperty46.handle(session, command, bytes)) return true;
    if (WolfBroGroup46.handle(session, command, bytes)) return true;
    if (Raid46.handle(session, command, bytes)) return true;
    if (ItemUse46.handle(session, command, bytes)) return true;
    if (ItemSale46.handle(session, command, bytes)) return true;
    if (ItemComposition46.handle(session, command, bytes)) return true;
    if (SelectedRelic46.handle(session, command, bytes)) return true;
    if (MaterialSubmit46.handle(session, command, bytes)) return true;
    if (TrialActivity46.handle(session, command, bytes)) return true;
    if (StoryLine46.handle(session, command, bytes)) return true;
    if (command == protocol.clientCommand("ContentPackageGetDataCsReq")) {
      var request = protocol.clientSchema().parse("ContentPackageGetDataCsReq", bytes);
      if (!request.getUnknownFields().asMap().isEmpty())
        throw new ProtocolException("Unknown content package fields");
      if (session.getState() == SessionState.ACTIVE)
        ContentPackage46.send(session, "ContentPackageGetDataScRsp");
      return true;
    }
    if (command != protocol.clientCommand("TakeQuestRewardCsReq")) return false;
    if (session.getState() != SessionState.ACTIVE) return true;
    var request = protocol.clientSchema().parse("TakeQuestRewardCsReq", bytes);
    if (!request.getUnknownFields().asMap().isEmpty())
      throw new ProtocolException("Unknown quest reward fields");
    // Raw EMHALMBFHKG field 2: Honey HandlerTakeQuestRewardCsReq iterates this list.
    @SuppressWarnings("unchecked")
    var requested =
        (List<Integer>)
            request.getField(request.getDescriptorForType().findFieldByName("ODJIJFJCHHB"));
    if (requested.isEmpty() || requested.size() > 100)
      throw new ProtocolException("Invalid quest reward batch");
    var ids = new LinkedHashSet<>(requested);
    var successes = new ArrayList<Integer>();
    var items = new ArrayList<GameItem>();
    Retcode firstError = Retcode.SUCC;
    for (int id : ids) {
      var result = session.getPlayer().getQuestManager().claim(id);
      if (result.retcode() == Retcode.SUCC) {
        successes.add(id);
        items.addAll(result.items());
      } else if (firstError == Retcode.SUCC) firstError = result.retcode();
    }
    var type = protocol.clientSchema().message("TakeQuestRewardScRsp");
    var response =
        DynamicMessage.newBuilder(type)
            .setField(
                type.findFieldByName("retcode"),
                (successes.isEmpty() ? firstError : Retcode.SUCC).getVal());
    requested.forEach(id -> response.addRepeatedField(type.findFieldByName("quest_id_list"), id));
    successes.forEach(
        id -> response.addRepeatedField(type.findFieldByName("succ_quest_id_list"), id));
    var reward = ItemList.newInstance();
    items.forEach(item -> reward.addItemList(item.toProto()));
    response.setField(
        type.findFieldByName("reward"),
        protocol
            .clientSchema()
            .parse("ItemList", protocol.translate("ItemList", reward.toByteArray(), true)));
    session.sendClientPacket("TakeQuestRewardScRsp", response.build().toByteArray());
    session.send(new PacketGetQuestDataScRsp(session.getPlayer()));
    return true;
  }

  private VersionedPacketHandler() {}
}
