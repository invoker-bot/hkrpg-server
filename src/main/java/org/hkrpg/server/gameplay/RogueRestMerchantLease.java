package org.hkrpg.server.gameplay;

import dev.morphia.annotations.Entity;
import java.util.*;
import lombok.Getter;

/** A persisted server recipe quote and its purchases; it does not claim an original client menu. */
@Entity(useDiscriminator = false)
@Getter
public final class RogueRestMerchantLease {
  private int schema = 1;
  private String ruleId, runId, sourceCommit, groupPath, groupNormalizedSHA256, dialoguePath, dialogueNormalizedSHA256;
  private int area, map, site, room, entry, plane, floor, group, npc, instance, basePrice, stockLimit;
  private Map<Integer, Purchase> purchases = new HashMap<>();

  @Entity(useDiscriminator = false)
  @Getter
  public static class Purchase {
    private int cost;
    private List<String> acquisitions = new ArrayList<>();
    protected Purchase() {}
    private Purchase(int cost,List<String> acquisitions) {
      this.cost = cost;
      this.acquisitions.addAll(acquisitions);
    }
    boolean valid() {
      return cost > 0 && cost <= 1_000_000 && acquisitions != null && !acquisitions.isEmpty()
          && acquisitions.size() <= 101 && new HashSet<>(acquisitions).size() == acquisitions.size()
          && acquisitions.stream().allMatch(RogueLotteryState::uuid);
    }
  }

  protected RogueRestMerchantLease() {}

  RogueRestMerchantLease(GameplayState.RogueRun run,RogueRestMerchantRecipes.Rule rule) {
    ruleId = RogueRestMerchantRecipes.RULE;
    sourceCommit = RogueRestMerchantRecipes.COMMIT;
    groupPath = RogueRestMerchantRecipes.GROUP;
    groupNormalizedSHA256 = RogueRestMerchantRecipes.GROUP_SHA;
    dialoguePath = RogueRestMerchantRecipes.DIALOGUE;
    dialogueNormalizedSHA256 = RogueRestMerchantRecipes.DIALOGUE_SHA;
    runId = run.getRunId();
    area = run.getAreaId(); map = run.getMapId(); site = run.getSiteId(); room = run.getRoomId();
    entry = 8000103; plane = 80001; floor = 80001003; group = 22; npc = 1013; instance = 400001;
    basePrice = rule.basePrice(); stockLimit = rule.stockLimit();
  }

  boolean matches(GameplayState.RogueRun run) {
    return schema == 1 && Objects.equals(ruleId,RogueRestMerchantRecipes.RULE)
        && Objects.equals(sourceCommit,RogueRestMerchantRecipes.COMMIT)
        && Objects.equals(groupPath,RogueRestMerchantRecipes.GROUP) && Objects.equals(groupNormalizedSHA256,RogueRestMerchantRecipes.GROUP_SHA)
        && Objects.equals(dialoguePath,RogueRestMerchantRecipes.DIALOGUE) && Objects.equals(dialogueNormalizedSHA256,RogueRestMerchantRecipes.DIALOGUE_SHA)
        && runId != null && !runId.isBlank() && runId.equals(run.getRunId())
        && area == 110 && map == 101 && site == 6 && room == 306 && entry == 8000103 && plane == 80001 && floor == 80001003
        && group == 22 && npc == 1013 && instance == 400001 && run.getAreaId() == area && run.getMapId() == map
        && run.getSiteId() == site && run.getRoomId() == room && basePrice > 0 && basePrice <= 800000 && stockLimit > 0 && stockLimit <= 3;
  }

  boolean valid(GameplayState.RogueRun run,GameplayState.RogueShopPlan plan) {
    if (!matches(run) || purchases == null || purchases.size() > stockLimit
        || plan.getOffers().size() > stockLimit) return false;
    for (var offered : plan.getOffers().entrySet()) {
      var offer = offered.getValue();
      if (offer.getCost() < basePrice || offer.getCost() > basePrice + basePrice / 4) return false;
      var receipt = purchases.get(offered.getKey());
      if (offer.isPurchased() != (receipt != null) || receipt != null && (!receipt.valid() || receipt.getCost() != offer.getCost()))
        return false;
    }
    return plan.getOffers().keySet().containsAll(purchases.keySet());
  }

  void record(int id,int cost,Set<String> before,GameplayState.RogueRun run) {
    var acquisitions = run.getMiracles().values().stream().map(GameplayState.RogueMiracle::getAcquisitionId)
        .filter(acquisition -> !before.contains(acquisition)).sorted().toList();
    var purchase = new Purchase(cost,acquisitions);
    if (!purchase.valid() || purchases.putIfAbsent(id,purchase) != null)
      throw new IllegalStateException("Invalid merchant acquisition receipt");
  }
}
