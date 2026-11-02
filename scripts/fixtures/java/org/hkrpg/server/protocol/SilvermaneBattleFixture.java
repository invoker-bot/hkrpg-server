import com.google.gson.Gson;
import com.mongodb.client.*;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.h2.H2Backend;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import java.nio.file.*;
import java.util.*;
import org.bson.Document;

/** Only an explicitly marked, stopped, single-player disposable database can be seeded. */
public class SilvermaneBattleFixture {
  static void require(boolean value,String why) {if(!value)throw new IllegalStateException(why);}
  public static void main(String[] args) throws Exception {
    var directory=Path.of(args[0]).toRealPath();var root=Path.of("").toRealPath();
    require(directory.startsWith(root.resolve("build/smoke"))&&directory.getFileName().toString().startsWith("silvermane-"),"Not owned fixture");
    require(Files.readString(directory.resolve("silvermane-fixture-marker")).equals("silvermane-isolated-20261102"),"Missing marker");
    String mode=args[1];int uid=Integer.parseInt(args[2]);
    require(Set.of("seed","inspect").contains(mode)&&uid>0,"Invalid operation");
    var backend=new H2Backend(directory.resolve("data/database.mv").toString());var server=new MongoServer(backend);var address=server.bind();
    try(var client=MongoClients.create("mongodb://127.0.0.1:"+address.getPort()+"/?directConnection=true")) {
      var db=client.getDatabase("hkrpg");var players=db.getCollection("players");
      require(players.countDocuments()==1,"Expected single fixture player");
      var filter=new Document("_id",uid);var before=Objects.requireNonNull(players.find(filter).first());
      if(mode.equals("seed")) {
        var g=before.get("gameplay",Document.class);
        require(!g.get("mainMissions",Document.class).containsKey("1010101"),"Already seeded");
        var update=new Document("planeId",20101).append("floorId",20101001).append("entryId",2010101).append("worldId",201)
            .append("pos",new Document("x",22560).append("y",0).append("z",-97000))
            .append("rot",new Document("x",0).append("y",0).append("z",0));
        update.append("gameplay.mainMissions.1010101",Document.parse(new Gson().toJson(new QuestState(1010101))));
        for(int id:new int[]{101010102,101010106,101010107,101010108,101010109,101010110,101010111,101010112,101010120}) {
          var state=new QuestState(id);if(id!=101010106&&id!=101010110&&id!=101010112)state.advance(1,1,100);
          update.append("gameplay.subMissions."+id,Document.parse(new Gson().toJson(state)));
        }
        require(players.updateOne(filter,new Document("$set",update)).getModifiedCount()==1,"Seed failed");
        backend.commit();backend.getMvStore().sync();
      }
      var store=dev.morphia.Morphia.createDatastore(client,"hkrpg");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");store.getMapper().map(Player.class,QuestState.class);
      var player=Objects.requireNonNull(store.find(Player.class).filter(dev.morphia.query.filters.Filters.eq("_id",uid)).first());
      var g=player.getGameplay();var state=g.getSubMissions().get(101010110);
      require(g.getScenePropStates().valid(),"Invalid prop record");
      if(mode.equals("seed"))require(state.getPhase()==QuestState.Phase.DOING&&!g.getSubMissions().containsKey(101010113),"Seed prewrote victory");
      else require(state.getPhase()==QuestState.Phase.FINISHED&&g.getSubMissions().get(101010113).getPhase()==QuestState.Phase.DOING,"Victory did not persist");
      System.out.println("SILVERMANE_INSPECT="+new Gson().toJson(Map.of("passed",true,"uid",uid,"mode",mode,
          "actualPlayerRestored",true,"victoryPhase",state.getPhase(),"followingGoalAccepted",g.getSubMissions().containsKey(101010113))));
    } finally {server.shutdownNow();}
  }
}
