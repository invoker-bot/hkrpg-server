package org.hkrpg.server.gm;

import java.util.List;
import org.hkrpg.server.gm.GmModels.*;

public interface GmBackend {
  Status status();

  List<PlayerRow> players();

  List<CatalogItem> catalog();

  List<CommandRow> commands();

  default List<BannerRow> bannerHistory() { return List.of(); }

  Result execute(Operation operation);
}
