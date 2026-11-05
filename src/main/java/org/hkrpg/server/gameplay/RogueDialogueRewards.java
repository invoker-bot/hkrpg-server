package org.hkrpg.server.gameplay;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** The 40398 reward categories are authored; their amounts and draw policy are server rules. */
public final class RogueDialogueRewards {
  private static final Gson GSON = new Gson();
  private static final Map<String, String> SOURCES = Map.of(
      "Config/Level/Rogue/RogueNPC/RogueNPC40398.json",
      "fa21d43c722cfcb21030f761ed97c61a8b287c006d9e727c193e722d388fbb2a",
      "Config/Level/Rogue/RogueDialogue/Event40398/Opt40398.json",
      "f3201fa896d601aa5f009ebc4df7f46228073023211179fb4ab02c58bb65a02c",
      "Config/Level/Rogue/RogueDialogue/Event40398/Act40398.json",
      "65ab43311653fcaaa795554e2341d4fd5377b9cf928c70f42a58c221c738f9de");
  private static final String SOURCE = "724b139d8c9c32d12552eb95745a4fee72bfe48b:40398:0";
  private static String currentSource = "";
  private static int fragmentReward;

  public static void load(Path resources, Path config) {
    currentSource = "";
    fragmentReward = 0;
    try {
      boolean matched = true;
      for (var source : SOURCES.entrySet()) {
        var file = resources.resolve(source.getKey());
        if (!Files.isRegularFile(file)) { matched = false; break; }
        // The fixed Git blobs use LF; the supported Windows resource checkout uses CRLF.
        byte[] normalized = Files.readString(file, StandardCharsets.UTF_8)
            .replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(normalized));
        if (!source.getValue().equals(sha)) { matched = false; break; }
      }
      if (!Files.isRegularFile(config)) return;
      var data = JsonParser.parseString(Files.readString(config)).getAsJsonObject();
      if ((!data.keySet().equals(Set.of("schema", "eventId", "fragmentReward"))
          && !data.keySet().equals(Set.of("schema", "eventId", "fragmentReward", "enabled")))
          || integer(data, "schema") != 1 || integer(data, "eventId") != 40398
          || integer(data, "fragmentReward") <= 0)
        throw new IllegalArgumentException("Unsupported event recipe");
      if (data.has("enabled")) {
        var enabled = data.get("enabled");
        if (!enabled.isJsonPrimitive() || !enabled.getAsJsonPrimitive().isBoolean())
          throw new IllegalArgumentException("Expected a boolean event recipe switch");
        if (!enabled.getAsBoolean()) return;
      }
      if (matched) currentSource = SOURCE;
      fragmentReward = integer(data, "fragmentReward");
    } catch (Exception malformed) {
      throw new IllegalStateException("Invalid RogueDialogueRewards.json or event source", malformed);
    }
  }

  private static int integer(JsonObject row, String key) {
    var value = row.get(key);
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
      throw new IllegalArgumentException("Expected an integer event recipe field");
    return value.getAsBigDecimal().intValueExact();
  }

  public static boolean bind(Player player, GameplayState.RogueDialogue lease) {
    if (lease.getRewardState() != null || lease.getDialogue() != 40398 || lease.getProgress() != 0
        || currentSource.isEmpty() || fragmentReward <= 0) return false;
    lease.rewardState(new RogueDialogueRewardState(player.getGameplay().getRogue(), lease,
        currentSource, fragmentReward, candidates(player)));
    return true;
  }

  private static boolean eligible(Player player, int id) {
    var rows = GameplayDefinitions.blessings.stream().filter(b -> b.MazeBuffID() == id
        && b.MazeBuffLevel() == 1 && b.RogueVersion() == 1).toList();
    if (rows.size() != 1) return false;
    var row = rows.getFirst();
    var battle = GameData.getMazeBuffExcelMap().get(id, 1);
    return row.AeonID() == 0 && row.BattleEventBuffType() == null
        && Set.of("Common", "Rare", "Legendary").contains(Objects.toString(row.RogueBuffCategory(), ""))
        && (row.UnlockIDList() == null || row.UnlockIDList().stream().allMatch(u -> RogueUnlocks.open(player, u)))
        && !player.getGameplay().getRogue().getBlessings().containsKey(id)
        && battle != null && battle.getID() == id && battle.getLv() == 1;
  }

  private static List<Integer> candidates(Player player) {
    return GameplayDefinitions.blessings.stream().map(GameplayDefinitions.Blessing::MazeBuffID)
        .distinct().filter(id -> eligible(player, id)).sorted().toList();
  }

  private static boolean context(Player player, RogueEventNpc actor, RogueDialogueRewardState state) {
    var run = player.getGameplay().getRogue();
    if (state == null || actor == null || !actor.valid() || actor.getScene().getPlayer() != player
        || !state.valid(run, actor.binding(), currentSource) || actor.binding().getDialogue() != 40398
        || actor.binding().getProgress() != 0 || player.isInBattle() || !run.isInside()
        || run.getPhase() != GameplayState.RogueRun.Phase.EXPLORING) return false;
    var sites = GameplayDefinitions.maps.getOrDefault(run.getMapId(), List.of()).stream()
        .filter(site -> site.SiteID() == run.getSiteId()).toList();
    return sites.size() == 1 && sites.getFirst().NextSiteIDList() != null
        && !sites.getFirst().NextSiteIDList().isEmpty()
        && org.hkrpg.server.protocol.RogueDialogue46.active(player.getScene()).size() == 1;
  }

  public static boolean selectable(Player player, RogueEventNpc actor, int option) {
    var state = actor.binding().getRewardState();
    var run = player.getGameplay().getRogue();
    if (!context(player, actor, state) || state.getOption() != 0 || run.pending()
        || !Objects.toString(run.getEventRewardLease(), "").isEmpty()
        || run.getCleared().contains(run.getSiteId()) || run.getCoins() < 0) return false;
    return switch (option) {
      case 14101 -> run.getCoins() <= Integer.MAX_VALUE - state.fragments();
      case 14102 -> run.getSelectionId() >= 0 && run.getSelectionId() < Integer.MAX_VALUE
          && state.candidates().stream().anyMatch(id -> eligible(player, id));
      case 14103 -> true;
      default -> false;
    };
  }

  public static boolean selected(RogueEventNpc actor, int option) {
    var state = actor.binding().getRewardState();
    return state != null && context(actor.getScene().getPlayer(), actor, state)
        && state.getOption() == option;
  }

  /** Closing presentation acknowledges an accepted choice; it never claims or clears a reward. */
  public static boolean canFinish(Player player, RogueEventNpc actor) {
    if (actor == null) return false;
    var state = actor.binding().getRewardState();
    if (!context(player, actor, state) || state.getOption() == 0) return false;
    var run = player.getGameplay().getRogue();
    if (state.isComplete())
      return run.getCleared().contains(run.getSiteId())
          && Objects.toString(run.getEventRewardLease(), "").isEmpty();
    return state.getOption() == 14102 && blessingAllowed(player, state.getBatch(), state.getBlessing());
  }

  public static Retcode select(Player player, RogueEventNpc actor, int option) {
    synchronized (player) {
      if (actor == null || !actor.valid()) return Retcode.REQ_PARA_INVALID;
      var state = actor.binding().getRewardState();
      var run = player.getGameplay().getRogue();
      // The original UI can reopen its selected option after reconnect or a lost reply.
      // Acknowledge the saved choice, without drawing, clearing or saving it again.
      if (option > 0 && context(player, actor, state) && state.getOption() == option) {
        boolean accepted = state.isComplete()
            ? option == 14102
                ? acquiredBlessing(player, state.getBatch(), state.getBlessing())
                : canFinish(player, actor)
            : option == 14102 && blessingAllowed(player, state.getBatch(), state.getBlessing());
        if (accepted) {
          if (!state.isComplete()) org.hkrpg.server.protocol.Universe46.syncPending(player);
          return Retcode.SUCC;
        }
      }
      if (!selectable(player, actor, option)) return Retcode.ROGUE_STATUS_NOT_MATCH;
      var before = copy(run);
      var mark = state.mark();
      try {
        if (option == 14102) {
          var available = state.candidates().stream().filter(id -> eligible(player, id)).toList();
          int buff = available.get(new Random(Objects.hash(run.getRunId(),
              actor.binding().getUniqueId(), option)).nextInt(available.size()));
          run.offer(List.of(buff), List.of(buff), List.of(), Map.of(buff, 1), 1, Map.of(buff, 1));
          run.eventRewardLease(actor.binding().key());
          state.choose(option, run.getSelectionId(), buff, false);
        } else {
          if (option == 14101) run.addCoins(state.fragments());
          state.choose(option, 0, 0, true);
          if (!run.clear()) throw new IllegalStateException("Event room changed before completion");
        }
        player.save();
      } catch (RuntimeException failed) {
        run.restoreEventReward(before);
        state.restore(mark);
        throw failed;
      }
      if (option == 14101) org.hkrpg.server.protocol.RogueTalent46.syncCurrency(player);
      if (option == 14102) org.hkrpg.server.protocol.Universe46.syncPending(player);
      org.hkrpg.server.protocol.Universe46.syncMap(player);
      return Retcode.SUCC;
    }
  }

  private static RogueEventNpc pendingActor(Player player) {
    for (var actor : org.hkrpg.server.protocol.RogueDialogue46.active(player.getScene())) {
      var state = actor.binding().getRewardState();
      if (state != null && state.getOption() == 14102 && !state.isComplete()) return actor;
    }
    return null;
  }

  /** Exact persisted event receipt only; acknowledgement must not change any current menu. */
  public static boolean acquiredBlessing(Player player, int batch, int buff) {
    var run = player.getGameplay().getRogue();
    if (batch <= 0 || buff <= 0 || !Objects.toString(run.getEventRewardLease(), "").isEmpty()
        || !run.getCleared().contains(run.getSiteId())
        || run.getBlessings().getOrDefault(buff, 0) < 1
        || !player.getGameplay().getRogueCollectedBuffs().contains(buff)) return false;
    for (var actor : org.hkrpg.server.protocol.RogueDialogue46.active(player.getScene())) {
      var state = actor.binding().getRewardState();
      if (context(player, actor, state) && state.isComplete() && state.getOption() == 14102
          && state.getBatch() == batch && state.getBlessing() == buff) return true;
    }
    return false;
  }

  /** A pending event menu cannot be acquired through an unrelated or corrupted run menu. */
  public static boolean blessingAllowed(Player player, int batch, int buff) {
    var run = player.getGameplay().getRogue();
    var dialogues = run.getDialogues();
    var pending = (dialogues == null ? List.<GameplayState.RogueDialogue>of() : dialogues.values())
        .stream().filter(lease -> lease != null && lease.getRewardState() != null
        && lease.getRewardState().getOption() == 14102 && !lease.getRewardState().isComplete()).toList();
    if (pending.isEmpty()) return Objects.toString(run.getEventRewardLease(), "").isEmpty();
    if (pending.size() != 1) return false;
    var actor = pendingActor(player);
    var state = pending.getFirst().getRewardState();
    return actor != null && actor.binding() == pending.getFirst() && context(player, actor, state)
        && Objects.equals(run.getEventRewardLease(), actor.binding().key())
        && state.getBatch() == batch && state.getBlessing() == buff
        && run.getOffers().equals(List.of(buff)) && run.getMiracleOffers().isEmpty()
        && run.getOfferRerollCosts().isEmpty() && run.offerChoiceTotal() == 1
        && run.offerChoiceIndex() == 1 && run.offerLevel(buff) == 1 && eligible(player, buff)
        && !run.getCleared().contains(run.getSiteId());
  }

  /** Save the event's acquisition, receipt and room clearance in the same Player document. */
  public static boolean acquireBlessing(Player player, int batch, int buff) {
    var actor = pendingActor(player);
    if (actor == null) return false;
    if (!blessingAllowed(player, batch, buff)) throw new IllegalStateException("Invalid event blessing");
    var state = actor.binding().getRewardState();
    var run = player.getGameplay().getRogue();
    var before = copy(run);
    var mark = state.mark();
    var collected = new HashSet<>(player.getGameplay().getRogueCollectedBuffs());
    try {
      run.select(buff);
      run.eventRewardLease("");
      state.complete();
      if (!run.clear()) throw new IllegalStateException("Event room was already cleared");
      player.getGameplay().getRogueCollectedBuffs().add(buff);
      RogueResonance.queue(run);
      player.save();
    } catch (RuntimeException failed) {
      run.restoreEventReward(before);
      state.restore(mark);
      player.getGameplay().getRogueCollectedBuffs().clear();
      player.getGameplay().getRogueCollectedBuffs().addAll(collected);
      throw failed;
    }
    return true;
  }

  private static GameplayState.RogueRun copy(GameplayState.RogueRun run) {
    return GSON.fromJson(GSON.toJson(run), GameplayState.RogueRun.class);
  }

  private RogueDialogueRewards() {}
}
