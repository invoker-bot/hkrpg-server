package org.hkrpg.server.gameplay;

import emu.lunarcore.game.inventory.GameItem;
import emu.lunarcore.game.inventory.ItemParamMap;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.shop.ShopService;
import emu.lunarcore.server.packet.Retcode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Local gift fulfillment. Reservations and reward receipts are persisted before delivery. */
public final class RecommendGifts {
  public record Result(Retcode code, List<GameItem> items, String reason) {}
  private static final Map<Player,Boolean> BLOCKED = Collections.synchronizedMap(new WeakHashMap<>());
  private static Result result(Retcode code, String reason) { return new Result(code,List.of(),reason); }
  private static final String PREFIX = "recommend-gift:";

  private static boolean valid(RecommendGiftState state) {
    if (state.getOrders().size()>5 || state.getUnlocked()<0 || state.getUnlocked()>4
        || state.getClaimed()<0 || state.getClaimed()>state.getUnlocked()
        || state.getPendingClaimTo()<0 || state.getPendingClaimTo()>state.getUnlocked()
        || state.getPendingClaimKey()==null) return false;
    if ((state.getPendingClaimTo()==0) != state.getPendingClaimKey().isEmpty()) return false;
    if (state.getPendingClaimTo()>0 && (state.getPendingClaimTo()<=state.getClaimed()
        || !state.getPendingClaimKey().equals(PREFIX+"daily:"+state.getClaimed()+":"+state.getPendingClaimTo()))) return false;
    var counts = new HashMap<String,Integer>();
    for (var entry : state.getOrders().entrySet()) {
      var order=entry.getValue();
      if (entry.getKey()==null || !entry.getKey().matches("[a-f0-9]{64}") || order==null || order.getTime()<=0) return false;
      var offer=RecommendGiftCatalog.find(order.getProduct());
      if (offer==null || counts.merge(offer.productId(),1,Integer::sum)>offer.max()) return false;
    }
    if (state.getDailyStart()==0) return state.getUnlocked()==0 && state.getClaimed()==0
        && state.getPendingClaimTo()==0 && state.getLastLoginDay()==0;
    long purchaseDay=DailyManager.day(Instant.ofEpochSecond(state.getDailyStart()));
    return state.getOrders().values().stream().anyMatch(o -> o.isCompleted() && o.getProduct().equals("rpgchnyear3tier5") && o.getTime()==state.getDailyStart())
        && state.getLastLoginDay()>=purchaseDay && state.getLastLoginDay()<purchaseDay+10;
  }
  private static boolean prepare(Player player) {
    if (player==null || BLOCKED.containsKey(player)) return false;
    var state=player.getGameplay().getRecommendGifts();
    if (!valid(state)) return false;
    try {
      boolean changed=false;
      for (var entry : state.getOrders().entrySet()) {
        var order=entry.getValue();
        if (!order.isCompleted() && player.getRewardLedger().granted(PREFIX+entry.getKey())) {
          order.complete();
          if (RecommendGiftCatalog.find(order.getProduct()).type()==16)
            state.startDaily(order.getTime(), DailyManager.day(Instant.ofEpochSecond(order.getTime())));
          changed=true;
        }
      }
      if (state.getPendingClaimTo()>0 && player.getRewardLedger().granted(state.getPendingClaimKey())) {
        state.finishDaily(); changed=true;
      }
      if (changed) player.save();
      return valid(state);
    } catch (RuntimeException failed) {
      BLOCKED.put(player,true);
      org.hkrpg.server.GameDiagnostics.saveFailed(player,"recommend-gift-reconcile",0,0,failed);
      return false;
    }
  }
  public static int bought(Player player, RecommendGiftCatalog.Offer offer) {
    synchronized (player) {
      if (!prepare(player)) return offer.max();
      return (int)player.getGameplay().getRecommendGifts().getOrders().values().stream()
          .filter(o -> o.getProduct().equals(offer.productId())).count();
    }
  }
  private static String digest(String text) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
    catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
  }
  private static ItemParamMap items(Map<Integer,Integer> rewards) {
    var result=new ItemParamMap(); rewards.forEach(result::add); return result;
  }
  public static Result claim(Player player, String product, String requestId, long now) {
    var offer=RecommendGiftCatalog.find(product);
    if (player==null || offer==null || now<=0 || requestId==null || !requestId.matches("[A-Za-z0-9_-]{1,128}"))
      return result(Retcode.REQ_PARA_INVALID,"invalid-order");
    synchronized (player) {
      if (!prepare(player)) return result(Retcode.SYSTEM_BUSY,"invalid-or-uncertain-state");
      var state=player.getGameplay().getRecommendGifts();
      String id=digest(requestId), key=PREFIX+id;
      var order=state.getOrders().get(id);
      if (order!=null && !order.getProduct().equals(product)) return result(Retcode.REQ_PARA_INVALID,"order-conflict");
      if (order!=null && order.isCompleted()) return result(Retcode.SUCC,"duplicate");
      if (state.getOrders().entrySet().stream().anyMatch(e -> !e.getKey().equals(id) && !e.getValue().isCompleted()))
        return result(Retcode.SYSTEM_BUSY,"pending-order");
      if (order==null) {
        long count=state.getOrders().values().stream().filter(o -> o.getProduct().equals(product)).count();
        if (count>=offer.max()) return result(Retcode.REQ_PARA_INVALID,"purchase-limit");
        if (!ShopService.canDeliver(player,offer.initialRewards())) return result(Retcode.ITEM_REWARD_EXCEED_LIMIT,"inventory-capacity");
        order=new RecommendGiftState.Order(product,now);
        state.getOrders().put(id,order);
        try { player.save(); }
        catch (RuntimeException failed) {
          state.getOrders().remove(id); BLOCKED.put(player,true);
          org.hkrpg.server.GameDiagnostics.saveFailed(player,"recommend-gift-reserve",offer.type(),id.hashCode(),failed);
          return result(Retcode.SYSTEM_BUSY,"uncertain-reservation");
        }
      }
      if (!ShopService.canDeliver(player,offer.initialRewards())) return result(Retcode.ITEM_REWARD_EXCEED_LIMIT,"inventory-capacity");
      try {
        var claim=player.getRewardLedger().claimItems(key,items(offer.initialRewards()));
        if (claim.retcode()!=Retcode.SUCC && claim.retcode()!=Retcode.QUEST_REWARD_ALREADY_TAKEN)
          return result(claim.retcode(),"delivery-pending");
        order.complete();
        if (offer.type()==16) state.startDaily(order.getTime(),DailyManager.day(Instant.ofEpochSecond(order.getTime())));
        player.save();
        return new Result(Retcode.SUCC,claim.items(),claim.items().isEmpty()?"duplicate":"granted");
      } catch (RuntimeException failed) {
        BLOCKED.put(player,true);
        org.hkrpg.server.GameDiagnostics.saveFailed(player,"recommend-gift-delivery",offer.type(),id.hashCode(),failed);
        return result(Retcode.SYSTEM_BUSY,"uncertain-delivery");
      }
    }
  }
  public static boolean onLoginQuery(Player player, long now) {
    if (player==null || now<=0) return false;
    synchronized (player) {
      if (!prepare(player)) return false;
      var s=player.getGameplay().getRecommendGifts();
      if (s.getDailyStart()==0 || s.getUnlocked()>=4) return true;
      long login=player.getLastActiveTime();
      if (login<=0 || login>now) return true;
      long today=DailyManager.day(Instant.ofEpochSecond(now)), loginDay=DailyManager.day(Instant.ofEpochSecond(login));
      long start=DailyManager.day(Instant.ofEpochSecond(s.getDailyStart()));
      if (today==loginDay && today>start && today> s.getLastLoginDay() && today<start+10) {
        s.unlock(today);
        try { player.save(); }
        catch (RuntimeException failed) {
          BLOCKED.put(player,true);
          org.hkrpg.server.GameDiagnostics.saveFailed(player,"recommend-gift-login",16,0,failed);
          return false;
        }
      }
      return true;
    }
  }
  public static Result daily(Player player, long now) {
    if (player==null || now<=0) return result(Retcode.REQ_PARA_INVALID,"invalid-player");
    synchronized (player) {
      if (!onLoginQuery(player,now)) return result(Retcode.SYSTEM_BUSY,"invalid-or-uncertain-state");
      var s=player.getGameplay().getRecommendGifts();
      if (s.getDailyStart()==0 || DailyManager.day(Instant.ofEpochSecond(now))>=DailyManager.day(Instant.ofEpochSecond(s.getDailyStart()))+10)
        return result(Retcode.REQ_PARA_INVALID,"gift-expired-or-missing");
      int to=s.getPendingClaimTo()>0 ? s.getPendingClaimTo() : s.getUnlocked();
      int count=to-s.getClaimed();
      if (count<=0) return result(Retcode.REQ_PARA_INVALID,"no-unclaimed-login");
      var rewards=new HashMap<Integer,Integer>();
      RecommendGiftCatalog.DAILY_REWARDS.forEach((id,n)->rewards.put(id,Math.multiplyExact(n,count)));
      if (!ShopService.canDeliver(player,rewards)) return result(Retcode.ITEM_REWARD_EXCEED_LIMIT,"inventory-capacity");
      if (s.getPendingClaimTo()==0) {
        s.reserveDaily(to,PREFIX+"daily:"+s.getClaimed()+":"+to);
        try { player.save(); }
        catch (RuntimeException failed) {
          BLOCKED.put(player,true);
          org.hkrpg.server.GameDiagnostics.saveFailed(player,"recommend-gift-daily-reserve",16,to,failed);
          return result(Retcode.SYSTEM_BUSY,"uncertain-reservation");
        }
      }
      try {
        var claim=player.getRewardLedger().claimItems(s.getPendingClaimKey(),items(rewards));
        if (claim.retcode()!=Retcode.SUCC && claim.retcode()!=Retcode.QUEST_REWARD_ALREADY_TAKEN)
          return result(claim.retcode(),"delivery-pending");
        s.finishDaily(); player.save();
        return new Result(Retcode.SUCC,claim.items(),"granted");
      } catch (RuntimeException failed) {
        BLOCKED.put(player,true);
        org.hkrpg.server.GameDiagnostics.saveFailed(player,"recommend-gift-daily-delivery",16,to,failed);
        return result(Retcode.SYSTEM_BUSY,"uncertain-delivery");
      }
    }
  }
  public static long expiry(RecommendGiftState s) {
    return s.getDailyStart()==0 ? 0 : (DailyManager.day(Instant.ofEpochSecond(s.getDailyStart()))+10)*86400L-14400L;
  }
  private RecommendGifts() {}
}
