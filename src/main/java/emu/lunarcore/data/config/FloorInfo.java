package emu.lunarcore.data.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.annotations.SerializedName;

import emu.lunarcore.game.enums.PropState;
import emu.lunarcore.game.scene.triggers.TriggerOpenTreasureWhenMonsterDie;
import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import lombok.Getter;

/**
 *  Original name: LevelFloorInfo
 */
@Getter
public class FloorInfo {
    private int FloorID;
    private int StartGroupIndex;
    private int StartAnchorID;
    private int[] LayerToAreaMask;

    private List<FloorDimensionInfo> DimensionList;
    private Map<Integer, GroupInstanceCommonInfo> GroupInstanceCommonMap;

    @SerializedName(value = "GroupInstanceList")
    private List<FloorGroupSimpleInfo> SimpleGroupList;

    // Cached data
    private transient boolean loaded;
    private transient List<ExtraDataInfo> savedValues;
    private transient Int2ObjectMap<GroupInfo> groups;
    private transient final Int2ObjectMap<GroupInfo> indexedGroups = new Int2ObjectLinkedOpenHashMap<>();
    private transient volatile Map<Integer, Int2ObjectMap<GroupInfo>> resourceGroups = Map.of();
    private transient Int2ObjectMap<PropInfo> cachedTeleports;
    private transient List<PropInfo> unlockedCheckpoints; // DEBUG
    @Getter(lombok.AccessLevel.NONE)
    private transient volatile Map<Integer, java.util.Set<Integer>> dimensionGroupIds;

    public FloorInfo() {
        this.groups = new Int2ObjectLinkedOpenHashMap<>();
        this.cachedTeleports = new Int2ObjectOpenHashMap<>();
        this.unlockedCheckpoints = new ArrayList<>();
    }

    public GroupInfo getGroupInfoByIndex(int groupIndex) {
        var simpleGroup = SimpleGroupList.get(groupIndex);
        return indexedGroups.isEmpty() ? this.getGroups().get(simpleGroup.getID()) : indexedGroups.get(groupIndex);
    }

    /** Preserve every authored resource before selecting the current scene dimension. */
    public synchronized void registerGroup(int index, GroupInfo group) {
        if (loaded || SimpleGroupList == null || index < 0 || index >= SimpleGroupList.size()
            || group == null || group.getId() != SimpleGroupList.get(index).getID()
            || indexedGroups.containsKey(index)) throw new IllegalArgumentException("Invalid floor resource index");
        indexedGroups.put(index, group);
        resourceGroups = Map.of();
    }

    public boolean hasLoadedGroups() { return !groups.isEmpty() || !indexedGroups.isEmpty(); }

    public Int2ObjectMap<GroupInfo> getGroups(int dimension) {
        if (indexedGroups.isEmpty()) return groups;
        var cached = resourceGroups.get(dimension);
        if (cached != null) return cached;
        synchronized (this) {
            cached = resourceGroups.get(dimension);
            if (cached != null) return cached;
            var result = new Int2ObjectLinkedOpenHashMap<GroupInfo>();
            var origins = new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap();
            if (DimensionList != null) for (var info : DimensionList) {
                if (info.getID() != dimension || info.getGroupIndexList() == null) continue;
                for (int index : info.getGroupIndexList()) {
                    var group = indexedGroups.get(index);
                    if (group == null) continue;
                    var previous = result.get(group.getId());
                    if (previous == null) {
                        result.put(group.getId(), group);
                        origins.put(group.getId(), index);
                    } else if (previous != group) {
                        var path = SimpleGroupList.get(origins.get(group.getId())).getGroupPath();
                        if (path == null || path.isBlank() || !path.equals(SimpleGroupList.get(index).getGroupPath()))
                            throw new IllegalStateException("Duplicate group resource in dimension " + dimension);
                    }
                }
            }
            var next = new java.util.HashMap<>(resourceGroups);
            cached = it.unimi.dsi.fastutil.ints.Int2ObjectMaps.unmodifiable(result);
            next.put(dimension, cached);
            resourceGroups = Map.copyOf(next);
            return cached;
        }
    }

    public AnchorInfo getStartAnchorInfo() {
        GroupInfo group = this.getGroupInfoByIndex(StartGroupIndex);
        if (group == null) return null;

        return getAnchorInfo(group, StartAnchorID);
    }

    public FloorDimensionInfo getMainDimension() {
        if (this.getDimensionList() == null || this.getDimensionList().isEmpty()) {
            return null;
        }

        return this.getDimensionList().stream()
                .filter(d -> d.getID() == 0)
                .findFirst()
                .orElse(null);
    }

    /** Keep all referenced resources available; choosing a dimension is a separate runtime action. */
    public java.util.Set<Integer> getReferencedGroupIndices() {
        dimensionGroups(); // Validate the indices before starting asynchronous file loads.
        var indices = new java.util.LinkedHashSet<Integer>();
        if (DimensionList != null)
            for (var dimension : DimensionList)
                if (dimension.getGroupIndexList() != null)
                    for (int index : dimension.getGroupIndexList()) indices.add(index);
        return java.util.Collections.unmodifiableSet(indices);
    }

    public boolean isGroupInDimension(int groupId, int dimensionId) {
        // Historical/test floors without dimension metadata retain their existing group behavior.
        if (DimensionList == null || DimensionList.isEmpty()) return dimensionId == 0;
        return dimensionGroups().getOrDefault(dimensionId, java.util.Set.of()).contains(groupId);
    }

    private Map<Integer, java.util.Set<Integer>> dimensionGroups() {
        var cached = dimensionGroupIds;
        if (cached != null) return cached;
        var result = new java.util.HashMap<Integer, java.util.Set<Integer>>();
        if (DimensionList != null) {
            for (var dimension : DimensionList) {
                if (dimension == null || dimension.getID() < 0 || result.containsKey(dimension.getID()))
                    throw new IllegalStateException("Invalid or duplicate dimension in floor " + FloorID);
                var ids = new java.util.HashSet<Integer>();
                if (dimension.getGroupIndexList() != null) {
                    for (int index : dimension.getGroupIndexList()) {
                        if (SimpleGroupList == null || index < 0 || index >= SimpleGroupList.size()
                            || SimpleGroupList.get(index) == null || SimpleGroupList.get(index).getID() <= 0)
                            throw new IllegalStateException("Invalid dimension group index in floor " + FloorID + ": " + index);
                        ids.add(SimpleGroupList.get(index).getID());
                    }
                }
                result.put(dimension.getID(), java.util.Set.copyOf(ids));
            }
        }
        dimensionGroupIds = cached = Map.copyOf(result);
        return cached;
    }

    public List<ExtraDataInfo> getExtraDatas() {
        if (this.savedValues == null) {
            this.savedValues = new ArrayList<>();

            var dimension = this.getMainDimension();
            if (dimension != null && dimension.getSavedValues() != null) {
                this.savedValues.addAll(dimension.getSavedValues());
            }
        }

        return this.savedValues;
    }

    public List<ExtraDataInfo> getExtraDatas(int dimensionId) {
        if (dimensionId == 0) return getExtraDatas();
        var dimension = DimensionList == null ? null : DimensionList.stream()
            .filter(d -> d.getID() == dimensionId).findFirst().orElse(null);
        if (dimension == null) throw new IllegalArgumentException("Unknown floor dimension: " + dimensionId);
        return dimension.getSavedValues() == null ? List.of() : List.copyOf(dimension.getSavedValues());
    }

    public AnchorInfo getAnchorInfo(int groupId, int anchorId) {
        GroupInfo group = this.getGroups().get(groupId);
        if (group == null) return null;

        return getAnchorInfo(group, anchorId);
    }

    public AnchorInfo getAnchorInfo(GroupInfo group, int anchorId) {
        return group.getAnchorList().stream().filter(a -> a.getID() == anchorId).findFirst().orElse(null);
    }

    public void onLoad() {
        if (this.loaded) return;

        if (!indexedGroups.isEmpty()) {
            var selected = getGroups(0);
            groups.clear();
            // Preserve the legacy query of all referenced IDs, preferring the default resource.
            indexedGroups.int2ObjectEntrySet().stream()
                .sorted(java.util.Comparator.comparingInt(e -> e.getIntKey()))
                .forEach(e -> groups.putIfAbsent(e.getValue().getId(), e.getValue()));
            groups.putAll(selected);
        }

        this.savedValues = getExtraDatas();

        // Cache anchors
        var definitions = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<GroupInfo, Boolean>());
        definitions.addAll(groups.values());
        definitions.addAll(indexedGroups.values());
        for (GroupInfo group : definitions) {
            if (group.getPropList() == null) {
                continue;
            }

            for (PropInfo prop : group.getPropList()) {
                // Set parent group for prop
                prop.setGroup(group);

                // Check if prop can be teleported to

                if (prop.getAnchorID() > 0 && getGroups(0).get(group.getId()) == group && isGroupInDimension(group.getId(), 0)) {
                    // Put inside cached teleport list to send to client when they request map info
                    this.cachedTeleports.put(prop.getMappingInfoID(), prop);
                    if (prop.getState() == PropState.CheckPointEnable) this.unlockedCheckpoints.add(prop);
                } else if (prop.getAnchorID() <= 0 && prop.getInitLevelGraph() != null) {
                    String json = prop.getInitLevelGraph();

                    // Hacky way to setup prop triggers
                    if (json.contains("Maze_GroupProp_OpenTreasure_WhenMonsterDie")) {
                        prop.setTrigger(new TriggerOpenTreasureWhenMonsterDie(group.getId()));
                    } else if (json.contains("Common_Console")) {
                        prop.setCommonConsole(true);
                    }

                    // Keep the authored graph identity for instance loaders and script dispatch.
                    if(!org.hkrpg.server.gameplay.GoldenSpears.keepsSourceValues(group)
                        && !org.hkrpg.server.gameplay.StoryDoors.controller(prop)
                        && !org.hkrpg.server.gameplay.StationEnergyBridges.retainsSource(this, group, prop)
                        && !org.hkrpg.server.gameplay.RogueTutorialCombat.retainsBarrierSource(this, group, prop)) prop.setValueSource(null);
                }
            }
        }

        this.loaded = true;
    }

    @Getter
    public static class FloorDimensionInfo {
        private int ID;
        private List<ExtraDataInfo> SavedValues;
        private IntArrayList GroupIndexList;
    }

    @Getter
    public static class ExtraDataInfo {
        private int ID;
        private String Name;
        private int DefaultValue;
        private List<Integer> AllowedValues;
        private int MaxValue;
        private Integer MinValue;
    }

    @Getter
    public static class FloorGroupSimpleInfo {
        private String GroupPath;
        private String Name;
        private boolean IsDelete;
        private int ID;

        public String getName() {
            if (this.Name == null) {
                return "";
            }

            return this.Name;
        }
    }

    @Getter
    public static class GroupInstanceCommonInfo {
        private boolean IsDelete;
        private GroupCategory Category;
    }

    public enum GroupCategory {
        Normal, Mission, BattleProps, BattleAudiences, Custom, System, Atmosphere;
    }
}
