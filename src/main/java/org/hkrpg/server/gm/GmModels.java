package org.hkrpg.server.gm;

import java.util.List;

/** Small, explicit HTTP projections. Never serialize Player or Account objects. */
public final class GmModels {
  private GmModels() {}

  public record Status(
      boolean ready,
      boolean gameAvailable,
      int onlinePlayers,
      long uptimeSeconds,
      long usedMemoryMb,
      long maxMemoryMb) {}

  public record PlayerRow(
      int uid,
      String name,
      int level,
      int worldLevel,
      int planeId,
      int floorId,
      boolean inBattle) {}

  public record CatalogItem(
      int id, String name, String category, int rarity, int maxLevel, int maxRank) {}

  public record ItemShortcut(CatalogItem item, String type, List<String> tags,
      List<String> sources, boolean grantable, String note, int maxAmount) {}

  public record CommandRow(
      String label, String description, boolean requireTarget, boolean dangerous) {}

  public record Attachment(int itemId, int amount) {}

  public record Operation(
      String requestId,
      String action,
      int uid,
      int itemId,
      int amount,
      int level,
      int rank,
      String title,
      String sender,
      String content,
      List<Attachment> attachments,
      String command,
      String confirm,
      String bannerKey,
      Integer durationDays,
      Integer bannerId,
      List<Integer> bannerIds,
      List<ShopTarget> shopGoods,
      Integer activityId,
      Long beginTime,
      Long endTime,
      String activityKey) {
    public Operation(String requestId,String action,int uid,int itemId,int amount,int level,int rank,
        String title,String sender,String content,List<Attachment> attachments,String command,String confirm,
        String bannerKey,Integer durationDays,Integer bannerId,List<Integer> bannerIds,List<ShopTarget> shopGoods,
        Integer activityId,Long beginTime,Long endTime){
      this(requestId,action,uid,itemId,amount,level,rank,title,sender,content,attachments,command,confirm,
          bannerKey,durationDays,bannerId,bannerIds,shopGoods,activityId,beginTime,endTime,null);
    }
    public Operation(String requestId, String action, int uid, int itemId, int amount,
        int level, int rank, String title, String sender, String content,
        List<Attachment> attachments, String command, String confirm, String bannerKey,
        Integer durationDays, Integer bannerId, List<Integer> bannerIds, List<ShopTarget> shopGoods) {
      this(requestId,action,uid,itemId,amount,level,rank,title,sender,content,attachments,command,
          confirm,bannerKey,durationDays,bannerId,bannerIds,shopGoods,null,null,null);
    }
    public Operation(String requestId, String action, int uid, int itemId, int amount,
        int level, int rank, String title, String sender, String content,
        List<Attachment> attachments, String command, String confirm, String bannerKey,
        Integer durationDays, Integer bannerId, List<Integer> bannerIds) {
      this(requestId, action, uid, itemId, amount, level, rank, title, sender, content,
          attachments, command, confirm, bannerKey, durationDays, bannerId, bannerIds, null);
    }
    public Operation(String requestId, String action, int uid, int itemId, int amount,
        int level, int rank, String title, String sender, String content,
        List<Attachment> attachments, String command, String confirm, String bannerKey,
        Integer durationDays, Integer bannerId) {
      this(requestId, action, uid, itemId, amount, level, rank, title, sender, content,
          attachments, command, confirm, bannerKey, durationDays, bannerId, null);
    }
    public Operation(String requestId, String action, int uid, int itemId, int amount,
        int level, int rank, String title, String sender, String content,
        List<Attachment> attachments, String command, String confirm) {
      this(requestId, action, uid, itemId, amount, level, rank, title, sender, content,
          attachments, command, confirm, null, null, null);
    }
  }

  public record ShopTarget(int shopId, int goodsId) {}

  public record ActivityModuleRow(int id, boolean resident, boolean configured, boolean disabled,
      String state, long beginTime, long endTime) {}

  public record ActivityRow(int id, int panelId, String name, String type,
      List<ActivityModuleRow> modules, String state, String status, long beginTime, long endTime,
      boolean canManage, boolean canEnable, boolean canStop, String note, String unlockConditions) {}

  public record HistoricalActivityRow(String key,String version,String phase,int phaseIndex,String name,
      List<String> categories,List<String> tags,int activityId,int panelId,List<Integer> modules,String family,String resourceStatus,
      String historicalStart,String historicalEnd,String sourceUrl,String state,String status,long beginTime,
      long endTime,String replayId,boolean canReplay,boolean canStop,boolean canEnable,String note) {}

  public record ShopRow(int id, int type, String name) {}

  public record ShopPrice(int itemId, String name, int amount) {}

  public record ShopGoodsRow(int shopId, int shopType, String shopName, int goodsId,
      int itemId, String name, int itemCount, List<ShopPrice> costs, int limitTimes,
      String refreshType, boolean listed, boolean rotating) {}

  public record CurrentBannerRow(int id, String type, String name, String historyKey,
      String version, String phase, List<BannerItem> up5, List<BannerItem> up4,
      String state, String status, long beginTime, long endTime, boolean permanent,
      boolean disabled, boolean canStop, boolean canEnable,
      List<BannerItem> pool5, List<BannerItem> pool4, List<String> poolIssues, boolean pullable,
      boolean needsSchedule) {
    public CurrentBannerRow(int id, String type, String name, String historyKey,
        String version, String phase, List<BannerItem> up5, List<BannerItem> up4,
        String state, String status, long beginTime, long endTime, boolean permanent,
        boolean disabled, boolean canStop, boolean canEnable,
        List<BannerItem> pool5, List<BannerItem> pool4, List<String> poolIssues, boolean pullable) {
      this(id,type,name,historyKey,version,phase,up5,up4,state,status,beginTime,endTime,
          permanent,disabled,canStop,canEnable,pool5,pool4,poolIssues,pullable,false);
    }
    public CurrentBannerRow(int id, String type, String name, String historyKey,
        String version, String phase, List<BannerItem> up5, List<BannerItem> up4,
        String state, String status, long beginTime, long endTime, boolean permanent,
        boolean disabled, boolean canStop, boolean canEnable) {
      this(id, type, name, historyKey, version, phase, up5, up4, state, status,
          beginTime, endTime, permanent, disabled, canStop, canEnable, up5, up4, List.of(), true);
    }
  }

  public record BannerItem(int id, String name) {}

  public record BannerRow(String key, String version, String phase, String name, int id,
      String type, List<BannerItem> up5, List<BannerItem> up4, boolean replayable,
      String status, long beginTime, long endTime, String historicalStart,
      String historicalEnd, String sourceUrl) {
    public BannerRow(String key, String version, String phase, String name, int id,
        String type, List<BannerItem> up5, List<BannerItem> up4, boolean replayable,
        String status, long beginTime, long endTime) {
      this(key, version, phase, name, id, type, up5, up4, replayable, status, beginTime,
          endTime, "", "", "");
    }
  }

  /** Returned means the legacy handler returned; it does not imply gameplay success. */
  public record Result(String state, List<String> output) {
    public static Result completed(String text) {
      return new Result("completed", List.of(text));
    }
  }

  public record AuditEntry(
      String requestId,
      String fingerprint,
      String at,
      String action,
      int uid,
      String summary,
      String state,
      List<String> output) {}
}
