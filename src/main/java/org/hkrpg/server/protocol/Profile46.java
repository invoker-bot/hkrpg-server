package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.Gameplay46.*;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import emu.lunarcore.server.packet.send.PacketGetBasicInfoScRsp;
import emu.lunarcore.server.packet.send.PacketPlayerSyncScNotify;
import emu.lunarcore.server.packet.send.PacketStaminaInfoScNotify;
import java.io.IOException;
import org.hkrpg.server.gameplay.StaminaPurchases;

/** Native profile queries, verified stamina preference, and separate nickname/character steps. */
public final class Profile46 {
  private record Choice(String nickname, int gender, boolean modify) {}

  public static boolean handle(GameSession session, int command, byte[] bytes) throws IOException {
    if (!session.getProtocol().experimental() || command != 41 && command != 80 && command != 45 && command != 8)
      return false;
    if (session.getState() != SessionState.ACTIVE) return true;
    var player = session.getPlayer();
    synchronized (player) {
      if (command == 8) {
        var receipts = session.getReplyCorrelation();
        boolean identified = bytes.length == 0 && receipts.hasRequestIdentity();
        byte[] cached = identified ? receipts.cachedResponseBody() : null;
        if (cached != null) {
          session.sendClientPacket("ExchangeStaminaScRsp", cached);
          return true;
        }
        boolean remember = identified && receipts.canCacheResponseBody();
        var result = !identified ? StaminaPurchases.failure(Retcode.REQ_PARA_INVALID, player.getTodayStaminaPurchases())
            : !remember ? StaminaPurchases.failure(Retcode.SYSTEM_BUSY, player.getTodayStaminaPurchases())
            : player.purchaseStamina();
        var response = builder(session, "ExchangeStaminaScRsp");
        set(response, "retcode", result.retcode().getVal());
        set(response, "exchange_times", result.times());
        if (result.retcode() == Retcode.SUCC) {
          set(response, "stamina_add", result.added());
          set(response, "last_recover_time", Math.max(0L, player.getNextStaminaRecover() / 1000
              - emu.lunarcore.LunarCore.getConfig().getServerOptions().getStaminaRecoveryRate()));
          var cost = builder(session, "ItemCost");
          var pile = builder(session, "PileItem");
          set(pile, "item_id", 1); set(pile, "item_num", result.price());
          set(cost, "pile_item", pile.build()); add(response, "item_cost_list", cost.build());
        }
        byte[] body = response.build().toByteArray();
        if (remember) receipts.cacheResponseBody(body);
        try {
          if (result.retcode() == Retcode.SUCC) {
            player.sendPacket(new PacketPlayerSyncScNotify(player));
            player.sendPacket(new PacketStaminaInfoScNotify(player));
          }
        } finally {
          session.sendClientPacket("ExchangeStaminaScRsp", body);
        }
        return true;
      }
      if (command == 45) {
        var response = builder(session, "UpdatePlayerSettingScRsp");
        var setting = builder(session, "UpdatePlayerSetting");
        Boolean enabled = readQuickStaminaSetting(bytes);
        var social = enabled == null ? TrainCakeSocialSettings46.read(bytes) : null;
        var result = enabled != null ? player.setQuickStaminaRecovery(enabled)
            : social != null ? org.hkrpg.server.gameplay.TrainCakeSocialSettings.update(player, social)
            : Retcode.REQ_PARA_INVALID;
        // False is a selected oneof value. On save failure echo the actual rolled-back state.
        if (enabled != null) set(setting, "HGPFFGKNPFB", player.isQuickStaminaRecovery());
        if (social != null) set(setting, "CMFCOOEEECB", TrainCakeSocialSettings46.toProto(session,
            org.hkrpg.server.gameplay.TrainCakeSocialSettings.current(player)));
        set(response, "player_setting", setting.build());
        set(response, "retcode", result.getVal());
        if (result != Retcode.SUCC) org.hkrpg.server.GameDiagnostics.outcome(session,
            "UpdatePlayerSettingCsReq", 0, result, "player-setting-rejected", "");
        session.sendClientPacket("UpdatePlayerSettingScRsp", response.build().toByteArray());
        return true;
      }
      if (command == 80) {
        var response = builder(session, "GetBasicInfoScRsp");
        if (bytes.length == 0) {
          var core = new PacketGetBasicInfoScRsp(player);
          // The inherited empty unk1/unk2 setting messages have no verified 4.6 meaning.
          ((emu.lunarcore.proto.GetBasicInfoScRspOuterClass.GetBasicInfoScRsp) core.getData())
              .clearPlayerSettingInfo();
          response.mergeFrom(
              session
                  .getProtocol()
                  .translate("GetBasicInfoScRsp", core.getData().toByteArray(), true));
          // The native basic-info callback unconditionally reads field 11 and its field 13.
          // Build verified 4.6 containers instead of translating the unrelated legacy unk1/unk2.
          var settingsField = response.getDescriptorForType().findFieldByName("player_setting_info");
          var settings = DynamicMessage.newBuilder(settingsField.getMessageType());
          var nested = settings.getDescriptorForType().findFieldByName("OJPAODIHAJE");
          settings.setField(nested, DynamicMessage.getDefaultInstance(nested.getMessageType()));
          // Native IsFarmStageAutoAddStamina: update oneof380, login PlayerSettingInfo field711.
          set(settings, "HGPFFGKNPFB", player.isQuickStaminaRecovery());
          var social = player.getGameplay().getTrainCakeSocialSettings();
          if (social != null)
            set(settings, "CMFCOOEEECB", TrainCakeSocialSettings46.toProto(session, social));
          response.setField(settingsField, settings.build());
          set(response, "cur_day", DailyRefresh46.day(java.time.Instant.ofEpochMilli(emu.lunarcore.LunarCore.currentServerTime())));
          set(response, "exchange_times", player.getTodayStaminaPurchases());
          set(response, "is_gender_set", player.hasCreatedCharacter());
        } else set(response, "retcode", Retcode.REQ_PARA_INVALID.getVal());
        session.sendClientPacket("GetBasicInfoScRsp", response.build().toByteArray());
        return true;
      }
      var choice = read(bytes);
      Retcode result = Retcode.REQ_PARA_INVALID;
      if (choice != null) {
        if (!choice.modify() && choice.gender() == 0)
          result = player.setInitialNickname(choice.nickname());
        else if (choice.modify() && choice.gender() >= 1 && choice.gender() <= 2)
          result = player.setInitialProfile(choice.nickname(), choice.gender() == 1 ? 8001 : 8002);
      }
      var response = builder(session, "SetPlayerInfoScRsp");
      set(response, "retcode", result.getVal());
      set(response, "is_modify", choice != null && choice.modify());
      var type = response.getDescriptorForType().findFieldByName("cur_avatar_path");
      var path = type.getEnumType().findValueByNumber(player.getCurAvatarPathId(8001));
      if (path != null) response.setField(type, path);
      session.sendClientPacket("SetPlayerInfoScRsp", response.build().toByteArray());
      return true;
    }
  }

  private static Boolean readQuickStaminaSetting(byte[] bytes) {
    if (bytes.length > 32) return null;
    try {
      var outer = CodedInputStream.newInstance(bytes);
      if (outer.readTag() != 10) return null; // UpdatePlayerSettingCsReq.player_setting field1.
      var setting = CodedInputStream.newInstance(outer.readByteArray());
      if (!outer.isAtEnd() || setting.readTag() != (380 << 3)) return null;
      long value = setting.readUInt64();
      return setting.isAtEnd() && (value == 0 || value == 1) ? value == 1 : null;
    } catch (IOException invalid) {
      return null;
    }
  }

  private static Choice read(byte[] bytes) {
    if (bytes.length > 256) return null;
    try {
      var input = CodedInputStream.newInstance(bytes);
      boolean modifySeen = false, genderSeen = false, nameSeen = false, modify = false;
      String nickname = "";
      int gender = 0, tag;
      while ((tag = input.readTag()) != 0) {
        if (tag == 24) {
          long value = input.readUInt64();
          if (modifySeen || value < 0 || value > 1) return null;
          modifySeen = true;
          modify = value == 1;
        } else if (tag == 80) {
          long value = input.readUInt64();
          if (genderSeen || value < 0 || value > 2) return null;
          genderSeen = true;
          gender = (int) value;
        } else if (tag == 98) {
          if (nameSeen) return null;
          nameSeen = true;
          nickname = input.readStringRequireUtf8();
        } else return null;
      }
      // Proto3 omits false/zero fields in the nickname-only confirmation request.
      // Presence still guards duplicate wire fields; neither default confirms a character.
      return nameSeen && input.isAtEnd()
          ? new Choice(nickname, gender, modify)
          : null;
    } catch (IOException invalid) {
      return null;
    }
  }

  private Profile46() {}
}
