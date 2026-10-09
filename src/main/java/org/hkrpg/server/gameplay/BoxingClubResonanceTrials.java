package org.hkrpg.server.gameplay;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.*;
import emu.lunarcore.data.excel.SpecialAvatarExcel;
import emu.lunarcore.game.player.Player;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Second-season source trials plus their independently scheduled availability. */
final class BoxingClubResonanceTrials {
  private static final Map<String, String> ORIGINAL =
      Map.ofEntries(
          Map.entry(
              "3071004/0", "aa04b66d87190e11495f339b6f2af41d922f5e0607ffd34f9d77c755d1f4cbc6"),
          Map.entry(
              "3071004/1", "4b114cdc1e2fe5ae82d0f7049d4d3016f843805bec0a7846341fd32cce603efa"),
          Map.entry(
              "3071004/2", "776615e6b3730f4c05273021e1574812fe0b8ab3863151e340ecb80c4c117c30"),
          Map.entry(
              "3071004/3", "06ba35a5d46fdff7bc723c866768e5f5c0661838af01648657a6daa3a459fe75"),
          Map.entry(
              "3071004/4", "561938f9015d61e73722f508072e2b6b72b8b66b0eba595a8c78cd7899617d45"),
          Map.entry(
              "3071004/5", "7b990829f72a98afe21e893c4f1cc8ec5d1d4a608405d4052e5963b6e480cb63"),
          Map.entry(
              "3071004/6", "ba2922165e98887eb0c1201e6132363aa8377c6b2e238754727ab6e137c93827"),
          Map.entry(
              "3071005/0", "4b08fc63276560d70c1ea88f5d37751c0f1b015d68cac4121277fdcc0e860c60"),
          Map.entry(
              "3071005/1", "450a51fb57d1792fe31b6f101114493e8c736efd8601e5e4b4c0db972eb9daf4"),
          Map.entry(
              "3071005/2", "ebe1a7b7e20f433fb68d484c99e807aa2f0e5a95a72ecc403d784a7e043b5e50"),
          Map.entry(
              "3071005/3", "1822c407f280e03fb438f30dcb5d1b46158cd3ab4ee3f2d19601f59c145cda62"),
          Map.entry(
              "3071005/4", "96bcf81f49d8a2e2df25aad3e9286b68537c0b0701800e78abbbb174ff07788d"),
          Map.entry(
              "3071005/5", "c3bd7503ddb78f41c8fbdf034f37a547485b43564f2ba79a44d0f73b05af53d0"),
          Map.entry(
              "3071005/6", "1f8ed3496819c327d2ff91fd6661ddb1d8bb4caa7c73b1637ee5f6d990aaf2cb"),
          Map.entry(
              "3071006/0", "d5fe989c7cbb9256efa1c40a5410287f3c2af839a65f2d87d6bc51e8b2971472"),
          Map.entry(
              "3071006/1", "e81c7e39afd38b369c069255d358f66635d329c1fecbd04a775985877cff0ed4"),
          Map.entry(
              "3071006/2", "a5b0b9fd47c9c4e51c3d81134b281521ec8d95efe8e242a84da117ed9de3e06e"),
          Map.entry(
              "3071006/3", "85ff46c3dea7185e0e7e2c45f19f6f26d01ac53f8e54e3bde7d796bc8f9424bb"),
          Map.entry(
              "3071006/4", "a1d70e8367129c89c9fd24953c53a6cfdc397d52c7b0346b75f87654bd18f5b7"),
          Map.entry(
              "3071006/5", "81819a8e5054fb91f96316c6958c67e3c8dfbf0cda323b44105a6af5f2ba8f7a"),
          Map.entry(
              "3071006/6", "6afeb7fb6053ce3df455938374fc80446af91d0e90651c3cd599134e5f83a107"),
          Map.entry(
              "3071008/0", "f27cc810ff045bb141bf2146806109c835a5b3fe0d79466fca03952ac92edb6b"),
          Map.entry(
              "3071008/1", "c6c5c79621e641258b037313e34f695eb22701c0e3f81ef811bcfb87cca5a172"),
          Map.entry(
              "3071008/2", "83c6c12a676b7a5f9650ff7f4c824f9c6186ea67b886a983f85982bb6f15202d"),
          Map.entry(
              "3071008/3", "fd71a4f41797b5fc00270a30307d242559ae9b4e589ed4faa2627f9939a13b06"),
          Map.entry(
              "3071008/4", "21d4b86fb439c4a94add5cc5764731d83c61918534a7c2cb71afdf580beabc03"),
          Map.entry(
              "3071008/5", "2c6b85f3a7423bc85a6112d7aeddef79ced040eb90c13d1e731a9dcf623d505b"),
          Map.entry(
              "3071008/6", "d25a6fa847235cfb0be037449e1c15bd692cf29ea7e506ec1631772beaa7d5e0"),
          Map.entry(
              "3071009/0", "354c4d6198afbd82955f32dd2713a0f2989a49b73e8f1c83fdc99b976d606aa7"),
          Map.entry(
              "3071009/1", "6d0550b402abd0a32019a5fc5b504a5281130f3597a3a02e51d4e1e3e545f9a1"),
          Map.entry(
              "3071009/2", "998cd2f7de4ee72a70dce56f0ae170a9abc4050168e8ef3536b7a702898f8669"),
          Map.entry(
              "3071009/3", "b41acb931b29c5e8dfe85a62676e5067097bf49fa29659f2d1ab92438b1a1457"),
          Map.entry(
              "3071009/4", "59b98b8fa1aaff0593f492e39b9f025d3123248dc35cf45466c5a22a9da0724d"),
          Map.entry(
              "3071009/5", "2956049f0fac0fc5d22f218e22dc47139737d0df80c993a9251c871e06035c0a"),
          Map.entry(
              "3071009/6", "6e5bb3b70ecca380c4ae90bfe8e8ecc81dac8c6b479ffad5f01f41ed3663991c"),
          Map.entry(
              "3071102/0", "34cb85c4983f452c5047f1cb2789d6fe1c11a96e61bd29f117e73fca293566f2"),
          Map.entry(
              "3071102/1", "a6a5ce002288a5eb6401eab8d45b3eb9ff4cfa83d7716ab8c83034f0a818e727"),
          Map.entry(
              "3071102/2", "07d6e7d9b90084a7f2b4a649d7cc01e5a031abc8aaacc077bd5e55d0184a01e8"),
          Map.entry(
              "3071102/3", "a4b4778206c71d20726ece8c4af69b5a065f6bd1f978527a9e5609a065881219"),
          Map.entry(
              "3071102/4", "78c0850dc5716e351eab0a5456ae021cc574f07557523bec424d68cd1d6ffee3"),
          Map.entry(
              "3071102/5", "be2842a006f36fec96655e1d61a1be51643c9858caff23e0aec0d1f8ea902017"),
          Map.entry(
              "3071102/6", "0823681b5c9f3b449d581934269ba6842d9403b10ecc76300bf078b406aa950b"),
          Map.entry(
              "3071106/0", "8f3669e0e5deced747dcd32300b9c2801fcecac2729e5c0c2ca52b712c16b754"),
          Map.entry(
              "3071106/1", "836e278d44478c07f3f8226f5bd72d9774d50dafa4663fb9f26dde63fdfcf048"),
          Map.entry(
              "3071106/2", "da02094299b9ee6b92a6a4be71401bc61accb3b3f156f2b768c6f272fb98cccb"),
          Map.entry(
              "3071106/3", "f5e2f6fbd1e35cda0f1614a1f12b75ad4f35a53c4672f13248fa1a0d5cbd4dce"),
          Map.entry(
              "3071106/4", "3f558014480dd3b1154878461ae36b3b3f3883ce0fbb872bb88f3058135aaf8a"),
          Map.entry(
              "3071106/5", "f801cf32ffa6d29fa81c049b3f94d6abd8c72e2a9bd12f7fe40d2fdf536f3245"),
          Map.entry(
              "3071106/6", "b17ebae9a5d4300a7c5ce2f9082ad60c9cc902c3b4c8685eb6106a5779181854"),
          Map.entry(
              "3071108/0", "ed9e309047a6c4fabf58130a7e8af1572bcecc957132d64efdff4f54a7fa753d"),
          Map.entry(
              "3071108/1", "fb906168c24f2c5f827f9fe9850d264dca88eaf779028d7f692737f6e1ce827c"),
          Map.entry(
              "3071108/2", "24a7588592c15e7d006398e5db47cb8663a8d99b7072cb509cbf0bf4758faaaa"),
          Map.entry(
              "3071108/3", "d5ce5838e74ac81a3ba87415aff071be75f4ede8d2bc0a0e08930eda229bab94"),
          Map.entry(
              "3071108/4", "04de16206995c52aeb0529579bcf8a162ae16980fa3cba30bf61a39da4599457"),
          Map.entry(
              "3071108/5", "bf739eb218cfb042d976f37dbfefc1ee5b7a0d38341039e8581acf4f63ab917b"),
          Map.entry(
              "3071108/6", "de48fb8a023ee32e71ed34ef2aa5f76eff0dc8b14cc36330fe047d859f16c1d8"),
          Map.entry(
              "3071111/0", "19ccaabf949304c3c9be600fdc38918a8118088f14f0521c483f5546dd74b41e"),
          Map.entry(
              "3071111/1", "9e14bcf5aeede207862f0a85339b0992538d47e4ddfb5d856752c1c6e3483fff"),
          Map.entry(
              "3071111/2", "f83df811721413a58b2c3d1008ad5c8fbd125203b87a5c0413357ff0b508a13e"),
          Map.entry(
              "3071111/3", "01b8077069b07c2eb075b06b0e8d4e684e6fb9547d6b103536e57237388ba7b2"),
          Map.entry(
              "3071111/4", "095bc82d870e0ac19eb7a79a0cbd2fe56836a2c96561d3a87baab09ffc777ccf"),
          Map.entry(
              "3071111/5", "4b9ca6ea32a6be7f2dd0851bd2dd9f5be413ae2701298340dcde387c87335f66"),
          Map.entry(
              "3071111/6", "d43ede20b400eba95155bafe83b2c66a7bcfb6e78fa3855b54259757d1697fcb"),
          Map.entry(
              "3071204/0", "9eb67eb791e7df223b95f7c273c44c96d603b45d431e987a4300a89bd00f39be"),
          Map.entry(
              "3071204/1", "cee1f12c8f1e3220a153d0e5de423cdad6d9c7b6e629d9d1c5ff62e99d5f0d2c"),
          Map.entry(
              "3071204/2", "766440f88a7405678e01cca9966f36d5c68ec17c867d59e52a4716e65749c715"),
          Map.entry(
              "3071204/3", "1c8037aa6f5b782bbb51b3d815db7df9f0a0b3e0f0c06bd41dfae1970e414433"),
          Map.entry(
              "3071204/4", "ef9055fdd489c7228faa30b90b3d001963119e0f0827a4f8e72ceb96b7a50ea4"),
          Map.entry(
              "3071204/5", "56fe679434143e912cafbcb7bda3e5e9c700523ff67e5c0baa9e28f162c6a340"),
          Map.entry(
              "3071204/6", "dbc40fe277fec15b5337df017d3ee2a6124d8e28906e5c10a63d29f45bd54c97"),
          Map.entry(
              "3071210/0", "3262e619634bf69d0bcb6c79e9d22b7698acb4c7754d111d5c4293c86310abd3"),
          Map.entry(
              "3071210/1", "f64c4739ba34139cfdc674ea5ddca1a0aaf31ae38f357cfded1bed577f68c63a"),
          Map.entry(
              "3071210/2", "6284b071d384579705e698c8807dbcebb233bd8a1d18c6c8c884823106fa1e6b"),
          Map.entry(
              "3071210/3", "960a81b0d6a060eebe8c1c2315d7f6a6ed75bc2b133ffecaf816c140fdf3f6d9"),
          Map.entry(
              "3071210/4", "b2910415aa6ffe635791355f9bdeb287dd321be441ba052c3be5dc5a6944f43e"),
          Map.entry(
              "3071210/5", "152ad8b3683b1c0aede06db7b7d620ac796e5ef46003fbc48d620d6b43a75080"),
          Map.entry(
              "3071210/6", "c75f65011f259fdeffc6b204ebff01f357f5a6cb45267b980a1a2c0d6803ddef"),
          Map.entry(
              "3071212/0", "cf81bca713593cd64984125f7b1cffc312c1d898eea79a0a76b14641205af57d"),
          Map.entry(
              "3071212/1", "225d10ac041ef926fa2f1f5d4d1a3d7764ac31b2a26700b6288fe2cd00b5329d"),
          Map.entry(
              "3071212/2", "45407dc05c4875ec475ec5d06d6eb7733e115df53d21bb8b693775c74e9eb655"),
          Map.entry(
              "3071212/3", "15566e7c63e82907137087f75315f843f310b1131184099bd822e596a70cded7"),
          Map.entry(
              "3071212/4", "a21dffb297460d8d2b539655d1cad8635125efff7e676f09f5ba4fee1ef31928"),
          Map.entry(
              "3071212/5", "d97a96c4d145d3dbd467db854f1ce8f891fc7a9a63b2c4c97d827a6225302e61"),
          Map.entry(
              "3071212/6", "d22abe5ac6ecff7fadd5e504de6ef34e7cdcd1dddc0a5c818e908b3f05dbeeea"),
          Map.entry(
              "3071213/0", "5d0e90bf0205cb2595905806d1c907848bafd87f2ecfc618dfb32ff46b8634fb"),
          Map.entry(
              "3071213/1", "4af1b0cc356df371f3d85557768330808473ae1e6952567cc20fb5fb64b914c8"),
          Map.entry(
              "3071213/2", "03674b9b01e477da91edba74deac3e13673b4d1d1068935ddc882b5f7724ce41"),
          Map.entry(
              "3071213/3", "31bdd1112dcf0961d8bda43e60f59f30a30b6d9ccad0da593d714439e58e0c27"),
          Map.entry(
              "3071213/4", "5e99f042a2ca87f500ee571fbed8858fad4da4e452e7d9f7fae09eb1c79efb2f"),
          Map.entry(
              "3071213/5", "e47881fe49555453ff77959e556ca782ec321d9ceafd4fd3571769d78b808723"),
          Map.entry(
              "3071213/6", "941f98eb20a0fa9a4de677bd42e0654f9555646ae2aeb1cebf3ca017140ec236"),
          Map.entry(
              "3071302/0", "c37d87adce2df9f7ee3bbafba2f4465429de9bdd613d0c512ed0548f32711267"),
          Map.entry(
              "3071302/1", "5bed5a2677e202699542845b7716acdbc712c8a7f8c279ae792c2d58853e369c"),
          Map.entry(
              "3071302/2", "413fddd55b34d2a4d31a6e8be298c1715e23b007b4ad9f088aca3f71ca032570"),
          Map.entry(
              "3071302/3", "f8a542bd925dded7f5caf695a218983ecdfe269c1a55d1fe070c60d448276f71"),
          Map.entry(
              "3071302/4", "7d1fc7d4b0510528f25b17ec5f8be9dbb770f13be0f953f6e4aa0cc2da50c1df"),
          Map.entry(
              "3071302/5", "64ef9c38cdcbe48b4320445b0e1b3a27b2f463f131a1ed943f48fc98f02e9772"),
          Map.entry(
              "3071302/6", "d137188f0724c2bb281e0e78b87e4bee5a6b24b8d89efee5704358957bd30b42"));
  private static final Map<String, String> PRESETS =
      Map.ofEntries(
          Map.entry(
              "Config/BattleLineupPage/SkillTreePointPreset/SkillTreePointPreset_01.json",
              "254409515cf6d126e69d0926729ad14fdd7575243190fb188161e86a99a12c81"),
          Map.entry(
              "Config/BattleLineupPage/SkillTreePointPreset/SkillTreePointPreset_1.json",
              "4f96842dabca72838c5118ee3d61247d4608f7106a0fab770562401ebd1aefdf"),
          Map.entry(
              "Config/BattleLineupPage/SkillTreePointPreset/SkillTreePointPreset_2.json",
              "9cce82d97bddafc09acb3a129bbc693675a2b4a344972056be00860ca05587f9"),
          Map.entry(
              "Config/BattleLineupPage/SkillTreePointPreset/SkillTreePointPreset_3.json",
              "e4feccd27fc5af78ce7a2d7de058db4c58ee1bd6c9cf0908011a5a30aebd991b"),
          Map.entry(
              "Config/BattleLineupPage/SkillTreePointPreset/SkillTreePointPreset_4.json",
              "8f80c7758340a1f878a47708b331e36e41f80c40df80a57c513f4ac652332215"),
          Map.entry(
              "Config/BattleLineupPage/SkillTreePointPreset/SkillTreePointPreset_5.json",
              "58ca205ada499257c4dc24674cbc95e487eda3d5d0affa8554e27509178c200a"));
  private static BoxingClubResonanceTrials cached;
  private final Path root;
  private final String fileHash;
  private final Map<String, SpecialAvatarExcel> rows = new HashMap<>();
  private final Map<Integer, Set<Integer>> allowed = new HashMap<>();
  private final Map<Integer, Integer> modules = new HashMap<>();

  private BoxingClubResonanceTrials(Path root) throws Exception {
    this.root = root;
    Path file = root.resolve("ExcelOutput/SpecialAvatar.json");
    if (!Files.isRegularFile(file) || Files.size(file) > 64 * 1024 * 1024)
      throw new IllegalArgumentException();
    fileHash = hash(Files.readString(file).replace("\r\n", "\n"));
    try (var reader = new JsonReader(Files.newBufferedReader(file))) {
      reader.beginArray();
      while (reader.hasNext()) {
        var raw = JsonParser.parseReader(reader).getAsJsonObject();
        String key =
            raw.get("SpecialAvatarID").getAsString()
                + "/"
                + (raw.has("WorldLevel") ? raw.get("WorldLevel").getAsString() : "0");
        if (ORIGINAL.containsKey(key)) {
          if (!ORIGINAL.get(key).equals(hash(canonical(raw).toString())))
            throw new IllegalArgumentException("Changed original boxing trial");
          if (rows.putIfAbsent(
                  key, ResourceLoader.getGson().fromJson(raw, SpecialAvatarExcel.class))
              != null) throw new IllegalArgumentException("Duplicate trial");
        }
      }
      reader.endArray();
    }
    if (!rows.keySet().equals(ORIGINAL.keySet()))
      throw new IllegalArgumentException("Missing trial");
    var challenges =
        JsonParser.parseString(
                Files.readString(root.resolve("ExcelOutput/BoxingClubChallenge.json")))
            .getAsJsonArray();
    for (var raw : challenges) {
      var row = raw.getAsJsonObject();
      if (!"Second".equals(row.get("Type").getAsString())) continue;
      int id = row.get("ChallengeID").getAsInt();
      var ids = new HashSet<Integer>();
      for (var trial : row.getAsJsonArray("SpecialAvatarIDList")) ids.add(trial.getAsInt());
      if (ids.isEmpty() || allowed.putIfAbsent(id, Set.copyOf(ids)) != null)
        throw new IllegalArgumentException();
      modules.put(id, row.get("SpecialAvatarActivityModule").getAsInt());
    }
    if (allowed.size() != 7 || !sourcesValid()) throw new IllegalArgumentException();
  }

  static synchronized BoxingClubResonanceTrials load() {
    try {
      var root = Path.of(LunarCore.getConfig().getResourceDir()).toAbsolutePath().normalize();
      if (BoxingClubCatalog.load(root) == null) return null;
      if (cached != null && cached.root.equals(root) && cached.sourcesValid()) return cached;
      cached = new BoxingClubResonanceTrials(root);
      return cached;
    } catch (Exception invalid) {
      return null;
    }
  }

  boolean sourcesValid() {
    try {
      if (BoxingClubCatalog.load(root) == null
          || !fileHash.equals(
              hash(
                  Files.readString(root.resolve("ExcelOutput/SpecialAvatar.json"))
                      .replace("\r\n", "\n")))) return false;
      for (var entry : PRESETS.entrySet()) {
        var file = root.resolve(entry.getKey());
        if (!Files.isRegularFile(file)
            || Files.size(file) > 128 * 1024
            || !entry.getValue().equals(hash(Files.readString(file).replace("\r\n", "\n"))))
          return false;
      }
      return true;
    } catch (Exception invalid) {
      return false;
    }
  }

  boolean available(Player p, int challenge, Instant now) {
    if (now == null || !allowed.containsKey(challenge) || !ActivityAccess.unlocked(p, 40003))
      return false;
    int module = modules.get(challenge);
    long time = now.getEpochSecond();
    return GameplayDefinitions.windows.stream()
            .filter(
                w ->
                    w.panelId() == 40003
                        && w.moduleId() == module
                        && w.beginTime() > 0
                        && w.endTime() > w.beginTime()
                        && w.beginTime() <= time
                        && time < w.endTime())
            .count()
        == 1;
  }

  SpecialAvatarExcel row(Player p, int challenge, int trial) {
    if (!allowed.getOrDefault(challenge, Set.of()).contains(trial)
        || p.getWorldLevel() < 0
        || p.getWorldLevel() > 6) return null;
    var expected = rows.get(trial + "/" + p.getWorldLevel());
    var live = p.getTrialManager().specialRow(trial);
    return expected != null
            && expected.isIsUseWorldLevel()
            && "TYPE_TRIAL".equals(expected.getType())
            && BoxingClubRules.equal(expected, live)
        ? live
        : null;
  }

  static boolean declared(int challenge, int id) {
    var source = load();
    return source != null && source.allowed.getOrDefault(challenge, Set.of()).contains(id);
  }

  private static JsonElement canonical(JsonElement value) {
    if (value.isJsonObject()) {
      var result = new JsonObject();
      new TreeSet<>(value.getAsJsonObject().keySet())
          .forEach(k -> result.add(k, canonical(value.getAsJsonObject().get(k))));
      return result;
    }
    if (value.isJsonArray()) {
      var result = new JsonArray();
      value.getAsJsonArray().forEach(v -> result.add(canonical(v)));
      return result;
    }
    return value;
  }

  private static String hash(String text) throws Exception {
    return HexFormat.of()
        .formatHex(
            MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
  }
}
