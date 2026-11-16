package org.hkrpg.server.gameplay;

import com.mongodb.client.MongoClients;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.h2.H2Backend;
import java.nio.file.*;
import org.bson.Document;

/** Seed one disposable, stopped smoke database; never opens the operator's data. */
public final class WantedPosterFixture {
  public static void main(String[] args) throws Exception {
    var root=Path.of("").toRealPath();
    var smoke=root.resolve("build/smoke").toRealPath();
    var directory=Path.of(args[0]).toRealPath();
    if(!directory.startsWith(smoke) || directory.equals(smoke)
        || !directory.getFileName().toString().startsWith("wanted-posters-"))
      throw new IllegalArgumentException("Not an owned wanted-poster smoke directory");
    if(!Files.readString(directory.resolve("fixture-marker")).equals("wanted-posters-v1"))
      throw new IllegalArgumentException("Missing disposable fixture marker");
    int uid=Integer.parseInt(args[1]);
    var file=directory.resolve("data/database.mv").toRealPath();
    if(uid<=0 || !file.startsWith(directory))throw new IllegalArgumentException("Unexpected database");
    var backend=new H2Backend(file.toString());
    var server=new MongoServer(backend);
    var socket=server.bind();
    try(var client=MongoClients.create("mongodb://127.0.0.1:"+socket.getPort()+"/?directConnection=true")) {
      var players=client.getDatabase("hkrpg").getCollection("players");
      var filter=new Document("_id",uid);
      if(players.countDocuments()!=1 || players.find(filter).first()==null)
        throw new IllegalStateException("Expected exactly one isolated player");
      var updates=new Document("worldId",101).append("planeId",10101).append("floorId",10101001)
          .append("entryId",1010101).append("pos",new Document("x",-12098).append("y",233).append("z",-105101))
          .append("gameplay.mainMissions.4040109",doing(4040109));
      for(int id=404010901;id<=404010907;id++)updates.append("gameplay.subMissions."+id,doing(id));
      updates.append("gameplay.subMissions.404010911",doing(404010911));
      if(players.updateOne(filter,new Document("$set",updates)).getMatchedCount()!=1)
        throw new IllegalStateException("Fixture player not found");
      backend.commit();backend.getMvStore().sync();
    } finally {server.shutdownNow();}
    System.out.println("{\"passed\":true,\"operatorDataModified\":false}");
  }
  private static Document doing(int id) {
    return new Document("questId",id).append("progress",0).append("finishTime",0L).append("phase","DOING");
  }
  private WantedPosterFixture() {}
}
