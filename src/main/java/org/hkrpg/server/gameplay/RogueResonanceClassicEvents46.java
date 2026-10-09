package org.hkrpg.server.gameplay;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Original classic transfer definitions only; no query action or battle event execution. */
public final class RogueResonanceClassicEvents46 {
  private RogueResonanceClassicEvents46() {}

  public static final class Definition {
    private final int eventId;
    private final String sourceAbility;
    private final Map<Integer, Long> sourceDefaults;
    private final List<RogueResonanceEventParameters46.Binding> parameterBindings;

    private Definition(
        int eventId,
        String sourceAbility,
        Map<Integer, Long> sourceDefaults,
        List<RogueResonanceEventParameters46.Binding> parameterBindings) {
      this.eventId = eventId;
      this.sourceAbility = sourceAbility;
      this.sourceDefaults = Map.copyOf(sourceDefaults);
      this.parameterBindings = List.copyOf(parameterBindings);
    }

    public int eventId() {
      return eventId;
    }

    public String sourceAbility() {
      return sourceAbility;
    }

    public Map<Integer, Long> sourceDefaults() {
      return sourceDefaults;
    }

    public List<RogueResonanceEventParameters46.Binding> parameterBindings() {
      return parameterBindings;
    }

    public RogueResonanceVariables46 newTemplate() {
      return RogueResonanceVariables46.fromSourceDefaults(sourceDefaults, null);
    }

    public Map<Integer, Long> evaluate(RogueResonanceLookup46 context) {
      return RogueResonanceEventParameters46.evaluate(parameterBindings, context);
    }
  }

  // Numeric bindings and defaults are derived from the frozen original 4.6 records.
  // No original task graph, callback or live query implementation is installed here.
  private static final Map<Integer, Definition> DEFINITIONS =
      Map.ofEntries(
          Map.entry(
              41001,
              definition(
                  41001,
                  "StageAbility_612020",
                  new long[][] {
                    {462955996L, 0L},
                    {59671469L, 0L},
                    {1625755410L, 0L},
                    {866240523L, 0L},
                    {-1862642832L, 0L},
                    {2029039937L, 0L},
                    {-699843418L, 0L},
                    {-1459358305L, 0L},
                    {-654259027L, 0L},
                    {2074624328L, 0L},
                    {508540387L, 0L},
                    {891621938L, 0L},
                    {-936584029L, 0L},
                    {-533299502L, 0L},
                    {-2099383443L, 0L},
                    {609296936L, 0L},
                    {-2119586419L, 0L},
                    {-1202971289L, 8589934592L},
                  },
                  List.of(
                      variable("ADF_1", 462955996),
                      variable("ADF_2", 59671469),
                      variable("ADF_3", 1625755410),
                      variable("ADF_4", 866240523),
                      variable("ADF_5", -1862642832),
                      variable("ADF_6", 2029039937),
                      variable("ADF_7", -699843418),
                      variable("ADF_8", -1459358305),
                      variable("ADF_612024_1", -654259027),
                      variable("ADF_612024_2", 2074624328),
                      variable("ADF_612024_3", 508540387),
                      variable("ADF_612025_1", 891621938),
                      variable("ADF_612026_1", -936584029),
                      variable("ADF_612026_2", -533299502),
                      variable("ADF_612026_3", -2099383443),
                      variable("ADF_612027_1", 609296936),
                      variable("ADF_612027_2", -2119586419),
                      variable("FirstBattle_Flag", -1202971289)))),
          Map.entry(
              41002,
              definition(
                  41002,
                  "StageAbility_612220",
                  new long[][] {
                    {462955996L, 0L},
                    {59671469L, 0L},
                    {1625755410L, 0L},
                    {866240523L, 0L},
                    {-1862642832L, 0L},
                    {2029039937L, 0L},
                    {-699843418L, 0L},
                    {-1459358305L, 0L},
                    {106725636L, 0L},
                    {-1381049501L, 0L},
                    {164831464L, 0L},
                    {-1098724499L, 0L},
                    {1630158856L, 0L},
                    {447156466L, 0L},
                    {-1202971289L, 8589934592L},
                  },
                  List.of(
                      variable("ADF_1", 462955996),
                      variable("ADF_2", 59671469),
                      variable("ADF_3", 1625755410),
                      variable("ADF_4", 866240523),
                      variable("ADF_5", -1862642832),
                      variable("ADF_6", 2029039937),
                      variable("ADF_7", -699843418),
                      variable("ADF_8", -1459358305),
                      variable("ADF_9", 106725636),
                      variable("ADF_612224_1", -1381049501),
                      variable("ADF_612225_1", 164831464),
                      variable("ADF_612226_1", -1098724499),
                      variable("ADF_612226_2", 1630158856),
                      variable("ADF_612227_1", 447156466),
                      variable("FirstBattle_Flag", -1202971289)))),
          Map.entry(
              41003,
              definition(
                  41003,
                  "StageAbility_612320",
                  new long[][] {
                    {462955996L, 0L},
                    {59671469L, 0L},
                    {1625755410L, 0L},
                    {866240523L, 0L},
                    {-1862642832L, 0L},
                    {2029039937L, 0L},
                    {-699843418L, 0L},
                    {-1459358305L, 0L},
                    {106725636L, 0L},
                    {943257672L, 0L},
                    {-1785625683L, 0L},
                    {-219541742L, 0L},
                    {802095171L, 0L},
                    {1205379698L, 0L},
                    {-360704243L, 0L},
                    {42580284L, 0L},
                    {1225582674L, 0L},
                    {822298147L, 0L},
                    {-1906585208L, 0L},
                    {1084420173L, 0L},
                    {-481663768L, 0L},
                    {-1202971289L, 8589934592L},
                  },
                  List.of(
                      variable("ADF_1", 462955996),
                      variable("ADF_2", 59671469),
                      variable("ADF_3", 1625755410),
                      variable("ADF_4", 866240523),
                      variable("ADF_5", -1862642832),
                      variable("ADF_6", 2029039937),
                      variable("ADF_7", -699843418),
                      variable("ADF_8", -1459358305),
                      variable("ADF_9", 106725636),
                      variable("ADF_612324_1", 943257672),
                      variable("ADF_612324_2", -1785625683),
                      variable("ADF_612324_3", -219541742),
                      variable("ADF_612325_1", 802095171),
                      variable("ADF_612325_2", 1205379698),
                      variable("ADF_612325_3", -360704243),
                      variable("ADF_612325_4", 42580284),
                      variable("ADF_612326_1", 1225582674),
                      variable("ADF_612326_2", 822298147),
                      variable("ADF_612326_3", -1906585208),
                      variable("ADF_612327_1", 1084420173),
                      variable("ADF_612327_2", -481663768),
                      variable("FirstBattle_Flag", -1202971289)))),
          Map.entry(
              41004,
              definition(
                  41004,
                  "StageAbility_612420",
                  new long[][] {
                    {462955996L, 0L},
                    {59671469L, 0L},
                    {1625755410L, 0L},
                    {866240523L, 0L},
                    {-1862642832L, 0L},
                    {2029039937L, 0L},
                    {-699843418L, 0L},
                    {-978539967L, 0L},
                    {1750343388L, 0L},
                    {567340998L, 0L},
                    {-1260864969L, 0L},
                    {-857580442L, 0L},
                    {285015996L, 0L},
                    {-1202971289L, 8589934592L},
                  },
                  List.of(
                      variable("ADF_1", 462955996),
                      variable("ADF_2", 59671469),
                      variable("ADF_3", 1625755410),
                      variable("ADF_4", 866240523),
                      variable("ADF_5", -1862642832),
                      variable("ADF_6", 2029039937),
                      variable("ADF_7", -699843418),
                      variable("ADF_612424_1", -978539967),
                      variable("ADF_612424_2", 1750343388),
                      variable("ADF_612425_1", 567340998),
                      variable("ADF_612426_1", -1260864969),
                      variable("ADF_612426_2", -857580442),
                      variable("ADF_612427_1", 285015996),
                      variable("FirstBattle_Flag", -1202971289)))),
          Map.entry(
              41005,
              definition(
                  41005,
                  "StageAbility_612520",
                  new long[][] {
                    {462955996L, 0L},
                    {59671469L, 0L},
                    {1625755410L, 0L},
                    {866240523L, 0L},
                    {-1862642832L, 0L},
                    {2029039937L, 0L},
                    {-699843418L, 0L},
                    {-1459358305L, 0L},
                    {106725636L, 0L},
                    {-1940068020L, 0L},
                    {1345767206L, 0L},
                    {942482679L, 0L},
                    {-1786400676L, 0L},
                    {1204604705L, 0L},
                    {-361479236L, 0L},
                    {-1927563177L, 0L},
                    {1063442204L, 0L},
                    {922279703L, 0L},
                    {1325564230L, 0L},
                    {-1202971289L, 8589934592L},
                  },
                  List.of(
                      variable("ADF_1", 462955996),
                      variable("ADF_2", 59671469),
                      variable("ADF_3", 1625755410),
                      variable("ADF_4", 866240523),
                      variable("ADF_5", -1862642832),
                      variable("ADF_6", 2029039937),
                      variable("ADF_7", -699843418),
                      variable("ADF_8", -1459358305),
                      variable("ADF_9", 106725636),
                      variable("ADF_10", -1940068020),
                      variable("ADF_612524_1", 1345767206),
                      variable("ADF_612524_2", 942482679),
                      variable("ADF_612524_3", -1786400676),
                      variable("ADF_612525_1", 1204604705),
                      variable("ADF_612525_2", -361479236),
                      variable("ADF_612525_3", -1927563177),
                      variable("ADF_612526_1", 1063442204),
                      variable("ADF_612527_1", 922279703),
                      variable("ADF_612527_2", 1325564230),
                      variable("FirstBattle_Flag", -1202971289)))),
          Map.entry(
              41006,
              definition(
                  41006,
                  "StageAbility_612120",
                  new long[][] {
                    {462955996L, 0L},
                    {59671469L, 0L},
                    {1625755410L, 0L},
                    {866240523L, 0L},
                    {-1862642832L, 0L},
                    {2029039937L, 0L},
                    {-699843418L, 0L},
                    {-1459358305L, 0L},
                    {106725636L, 0L},
                    {-1940068020L, 0L},
                    {1670048146L, 0L},
                    {1266763619L, 0L},
                    {-1462119736L, 0L},
                    {1528885645L, 0L},
                    {-37198296L, 0L},
                    {1387723144L, 0L},
                    {-1341160211L, 0L},
                    {1246560643L, 0L},
                    {1649845170L, 0L},
                    {-1202971289L, 8589934592L},
                  },
                  List.of(
                      variable("ADF_1", 462955996),
                      variable("ADF_2", 59671469),
                      variable("ADF_3", 1625755410),
                      variable("ADF_4", 866240523),
                      variable("ADF_5", -1862642832),
                      variable("ADF_6", 2029039937),
                      variable("ADF_7", -699843418),
                      variable("ADF_8", -1459358305),
                      variable("ADF_9", 106725636),
                      variable("ADF_10", -1940068020),
                      variable("ADF_612124_1", 1670048146),
                      variable("ADF_612124_2", 1266763619),
                      variable("ADF_612125_1", 1528885645),
                      variable("ADF_612124_3", -1462119736),
                      variable("ADF_612125_2", -37198296),
                      variable("ADF_612126_1", 1387723144),
                      variable("ADF_612126_2", -1341160211),
                      variable("ADF_612127_1", 1246560643),
                      variable("ADF_612127_2", 1649845170),
                      variable("FirstBattle_Flag", -1202971289)))),
          Map.entry(
              41007,
              definition(
                  41007,
                  "StageAbility_612620",
                  new long[][] {
                    {462955996L, 0L},
                    {59671469L, 0L},
                    {1625755410L, 0L},
                    {866240523L, 0L},
                    {-1862642832L, 0L},
                    {2029039937L, 0L},
                    {-699843418L, 0L},
                    {-1459358305L, 0L},
                    {106725636L, 0L},
                    {-1940068020L, 0L},
                    {-159449476L, 0L},
                    {-1423005439L, 0L},
                    {1305877916L, 0L},
                    {-260206025L, 0L},
                    {-1826289966L, 0L},
                    {122875526L, 0L},
                    {-280409001L, 0L},
                    {-1202971289L, 8589934592L},
                  },
                  List.of(
                      variable("ADF_1", 462955996),
                      variable("ADF_2", 59671469),
                      variable("ADF_3", 1625755410),
                      variable("ADF_4", 866240523),
                      variable("ADF_5", -1862642832),
                      variable("ADF_6", 2029039937),
                      variable("ADF_7", -699843418),
                      variable("ADF_8", -1459358305),
                      variable("ADF_9", 106725636),
                      variable("ADF_10", -1940068020),
                      variable("ADF_612625_1", -159449476),
                      variable("ADF_612626_1", -1423005439),
                      variable("ADF_612626_2", 1305877916),
                      variable("ADF_612626_3", -260206025),
                      variable("ADF_612626_4", -1826289966),
                      variable("ADF_612627_1", 122875526),
                      variable("ADF_612627_2", -280409001),
                      variable("FirstBattle_Flag", -1202971289)))),
          Map.entry(
              41008,
              definition(
                  41008,
                  "StageAbility_612720",
                  new long[][] {
                    {462955996L, 0L},
                    {59671469L, 0L},
                    {1625755410L, 0L},
                    {-86835773L, 0L},
                    {336651730L, 0L},
                    {-1370594712L, 0L},
                    {1358288643L, 0L},
                    {195489229L, 0L},
                    {618976732L, 0L},
                    {-2109906623L, 0L},
                    {477814231L, 0L},
                    {881098758L, 0L},
                    {901301734L, 0L},
                    {498017207L, 0L},
                    {760139233L, 0L},
                    {-805944708L, 0L},
                    {1922938647L, 0L},
                    {-1202971289L, 8589934592L},
                  },
                  List.of(
                      variable("ADF_1", 462955996),
                      variable("ADF_2", 59671469),
                      variable("ADF_3", 1625755410),
                      variable("ADF_612721_1", -86835773),
                      variable("ADF_612722_1", 336651730),
                      variable("ADF_612723_2", -1370594712),
                      variable("ADF_612723_3", 1358288643),
                      variable("ADF_612723_1", 195489229),
                      variable("ADF_612724_1", 618976732),
                      variable("ADF_612724_2", -2109906623),
                      variable("ADF_612725_1", 477814231),
                      variable("ADF_612725_2", 881098758),
                      variable("ADF_612726_1", 901301734),
                      variable("ADF_612726_2", 498017207),
                      variable("ADF_612727_1", 760139233),
                      variable("ADF_612727_2", -805944708),
                      variable("ADF_612727_3", 1922938647),
                      variable("FirstBattle_Flag", -1202971289),
                      fixed("Egg_MaxLayer_612720", 0L)))),
          Map.entry(
              41009,
              definition(
                  41009,
                  "StageAbility_612820",
                  new long[][] {
                    {462955996L, 0L},
                    {59671469L, 0L},
                    {1625755410L, 0L},
                    {-1450889954L, 0L},
                    {1015871375L, 0L},
                    {1419155902L, 0L},
                    {-146928039L, 0L},
                    {-1733214956L, 0L},
                    {-1202971289L, 8589934592L},
                  },
                  List.of(
                      variable("ADF_1", 462955996),
                      variable("ADF_2", 59671469),
                      variable("ADF_3", 1625755410),
                      variable("ADF_612821_1", -1450889954),
                      variable("ADF_612822_1", 1015871375),
                      variable("ADF_612822_2", 1419155902),
                      variable("ADF_612822_3", -146928039),
                      variable("ADF_612823_1", -1733214956),
                      variable("FirstBattle_Flag", -1202971289)))));

  private static Definition definition(
      int id,
      String ability,
      long[][] declarations,
      List<RogueResonanceEventParameters46.Binding> bindings) {
    var defaults = new java.util.HashMap<Integer, Long>();
    for (var declaration : declarations) {
      int key = Math.toIntExact(declaration[0]);
      if (defaults.putIfAbsent(key, declaration[1]) != null)
        throw new IllegalStateException("Repeated original classic declaration");
    }
    return new Definition(id, ability, defaults, bindings);
  }

  private static RogueResonanceEventParameters46.Binding variable(String name, int key) {
    return new RogueResonanceEventParameters46.Binding(
        name,
        RogueResonanceDynamicCell46.Scalar.fromPostfix(
            0, new byte[] {1, 0, 17}, new long[0], new int[] {key}));
  }

  private static RogueResonanceEventParameters46.Binding fixed(String name, long raw) {
    return new RogueResonanceEventParameters46.Binding(
        name, RogueResonanceDynamicCell46.Scalar.fixed(raw));
  }

  public static List<Integer> eventIds() {
    return DEFINITIONS.keySet().stream().sorted().toList();
  }

  public static Optional<Definition> find(int eventId) {
    return Optional.ofNullable(DEFINITIONS.get(eventId));
  }
}
