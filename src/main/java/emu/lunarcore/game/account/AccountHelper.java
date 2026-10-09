package emu.lunarcore.game.account;

import emu.lunarcore.LunarCore;

/** Helper class for handling account related stuff */
public class AccountHelper {

  public static Account createAccount(String username, String password, int reservedUid) {
    Account account =
        LunarCore.getAccountDatabase().getObjectByField(Account.class, "username", username);

    if (account != null) {
      return null;
    }

    // New identities must be inserted, never upserted over an existing account after a clock
    // rollback, process restart or concurrent allocator collision. Existing token saves retain
    // their original update behavior.
    for (int attempt = 0; attempt < 4096; attempt++) {
      account = new Account(username);
      account.setReservedPlayerUid(reservedUid);
      try {
        LunarCore.getAccountDatabase().getDatastore().insert(account);
        return account;
      } catch (com.mongodb.MongoWriteException collision) {
        if (collision.getError().getCode() != 11000) throw collision;
        if (LunarCore.getAccountDatabase().getObjectByField(Account.class, "username", username)
            != null) return null;
      }
    }
    throw new IllegalStateException("Cannot allocate an unused account ID");
  }

  public static boolean deleteAccount(String username) {
    Account account =
        LunarCore.getAccountDatabase().getObjectByField(Account.class, "username", username);

    if (account == null) {
      return false;
    }

    // Delete the player too
    // IMPORTANT: This will only delete the player from the current game server
    if (LunarCore.getGameServer() != null) {
      LunarCore.getGameServer().deletePlayer(account.getUid());
    }

    // Delete the account first
    return LunarCore.getAccountDatabase().delete(account);
  }
}
