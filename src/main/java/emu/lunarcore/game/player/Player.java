package emu.lunarcore.game.player;

import java.util.*;

import org.bson.types.ObjectId;

import com.mongodb.client.model.Filters;

import dev.morphia.annotations.Entity;
import dev.morphia.annotations.Id;
import dev.morphia.annotations.Indexed;

import emu.lunarcore.game.inventory.tabs.InventoryTabType;
import emu.lunarcore.GameConstants;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.AnchorInfo;
import emu.lunarcore.data.config.FloorInfo;
import emu.lunarcore.data.config.PropInfo;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.account.Account;
import emu.lunarcore.game.avatar.AvatarStorage;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.avatar.AvatarMultiPath;
import emu.lunarcore.game.battle.Battle;
import emu.lunarcore.game.challenge.ChallengeGroupReward;
import emu.lunarcore.game.challenge.ChallengeHistory;
import emu.lunarcore.game.challenge.ChallengeInstance;
import emu.lunarcore.game.challenge.ChallengeManager;
import emu.lunarcore.game.chat.ChatManager;
import emu.lunarcore.game.chat.ChatMessage;
import emu.lunarcore.game.enums.PlaneType;
import emu.lunarcore.game.enums.PropState;
import emu.lunarcore.game.enums.PropType;
import emu.lunarcore.game.friends.FriendList;
import emu.lunarcore.game.friends.Friendship;
import emu.lunarcore.game.gacha.PlayerGachaInfo;
import emu.lunarcore.game.inventory.GameItem;
import emu.lunarcore.game.inventory.Inventory;
import emu.lunarcore.game.mail.Mail;
import emu.lunarcore.game.mail.Mailbox;
import emu.lunarcore.game.player.lineup.LineupManager;
import emu.lunarcore.game.player.lineup.PlayerExtraLineup;
import emu.lunarcore.game.player.lineup.PlayerLineup;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.SceneBuff;
import emu.lunarcore.game.scene.entity.EntityProp;
import emu.lunarcore.game.scene.entity.GameEntity;
import emu.lunarcore.game.trial.TrialInstance;
import emu.lunarcore.game.trial.TrialManager;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.quest.QuestManager;
import org.hkrpg.server.gameplay.*;
import emu.lunarcore.proto.PlayerDisplayInfoOuterClass.PlayerDisplayInfo;
import emu.lunarcore.proto.PlayerMocDisplayInfoOuterClass.PlayerMocDisplayInfo;
import emu.lunarcore.proto.PlayerSimpleInfoOuterClass.PlayerSimpleInfo;
import emu.lunarcore.proto.BoardDataSyncOuterClass.BoardDataSync;
import emu.lunarcore.proto.DisplayAvatarOuterClass.DisplayAvatar;
import emu.lunarcore.proto.FriendOnlineStatusOuterClass.FriendOnlineStatus;
import emu.lunarcore.proto.HeadIconOuterClass.HeadIcon;
import emu.lunarcore.proto.PlatformTypeOuterClass.PlatformType;
import emu.lunarcore.proto.PlayerBasicInfoOuterClass.PlayerBasicInfo;
import emu.lunarcore.proto.PlayerDetailInfoOuterClass.PlayerDetailInfo;
import emu.lunarcore.proto.PlayerSyncScNotifyOuterClass.PlayerSyncScNotify;
import emu.lunarcore.server.game.GameServer;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.game.Syncable;
import emu.lunarcore.server.game.Tickable;
import emu.lunarcore.server.packet.BasePacket;
import emu.lunarcore.server.packet.CmdId;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.send.*;
import emu.lunarcore.util.Location;
import emu.lunarcore.util.Position;
import emu.lunarcore.util.Utils;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import lombok.Getter;
import lombok.Setter;
import us.hebi.quickbuf.RepeatedInt;
import us.hebi.quickbuf.RepeatedMessage;

@Entity(value = "players", useDiscriminator = false)
@Getter
public class Player implements Tickable, Syncable {
    @Id private int uid;
    @Indexed private String accountUid;
    private String name;
    private String signature;
    private int birthday;
    private int headIcon;
    private int headIconFrame;
    private int phoneTheme;
    private int phoneCase;
    private int chatBubble;
    private int currentBgm;
    @Setter private int currentPamSkinId;
    @Setter private int petId;
    private Map<Integer, Integer> curAvatarPaths;
    private PlayerGender gender;

    private int level;
    private int exp; // Total exp
    private int worldLevel;
    private int scoin; // Credits
    private int hcoin; // Jade
    private int mcoin; // Crystals
    private int talentPoints; // Rogue talent points

    private int stamina;
    private double staminaReserve;
    private long nextStaminaRecover;

    private transient Battle battle;
    private transient Scene scene;
    private Position pos;
    private Position rot;
    @Getter @Setter private org.hkrpg.server.gameplay.SceneTeleportLocation sceneTeleportLocation;
    private int planeId;
    private int floorId;
    private int entryId;
    private int worldId;

    private long lastActiveTime;

    // Player managers
    private transient GameSession session;
    private transient final AvatarStorage avatars;
    private transient final Inventory inventory;
    private transient final ChatManager chatManager;
    private transient final FriendList friendList;
    private transient final Mailbox mailbox;
    private transient final ChallengeManager challengeManager;
    private transient final TrialManager trialManager;
    private transient final org.hkrpg.server.gameplay.MissionTrialManager missionTrialManager;
    private transient final QuestManager questManager;
    private transient final MissionManager missionManager;
    private transient final DailyManager dailyManager;
    private transient final LoginActivityManager loginActivityManager;
    private transient final UniverseManager universeManager;
    private transient final RewardLedger rewardLedger;
    private transient final CycleScoreManager cycleScoreManager;
    private GameplayState gameplay;
    private transient final org.hkrpg.server.gameplay.StoryLineManager storyLineManager;
    private transient final org.hkrpg.server.gameplay.RaidTrialManager raidTrialManager;

    // Activity managers

    // Database persistent data
    private LineupManager lineupManager;
    private PlayerGachaInfo gachaInfo;
    private List<ObjectId> assistAvatars;
    private List<ObjectId> displayAvatars;
    private Map<Integer, QuestState> questStates;

    // Instance
    private transient PlayerGameInstance instance;

    // Etc
    private transient boolean isNew;
    private transient boolean loggedIn;
    private transient boolean inAnchorRange;
    private transient int nextBattleId = java.util.concurrent.ThreadLocalRandom.current().nextInt(1, 1_000_000_000);
    private transient PlayerUnlockData unlocks;
    private transient Int2ObjectMap<SceneBuff> foodBuffs;

    @Setter private transient boolean paused;

    @Deprecated // Morphia only
    public Player() {
        this.gender = PlayerGender.GENDER_MAN;
        this.foodBuffs = new Int2ObjectOpenHashMap<>();
        this.assistAvatars = new ArrayList<>();
        this.displayAvatars = new ArrayList<>();
        this.curAvatarPaths = new HashMap<>();
        this.questStates = new HashMap<>();
        this.gameplay = new GameplayState();
        this.storyLineManager = new org.hkrpg.server.gameplay.StoryLineManager(this);
        this.raidTrialManager = new org.hkrpg.server.gameplay.RaidTrialManager(this);

        this.avatars = new AvatarStorage(this);
        this.inventory = new Inventory(this);
        this.chatManager = new ChatManager(this);
        this.friendList = new FriendList(this);
        this.mailbox = new Mailbox(this);
        this.challengeManager = new ChallengeManager(this);
        this.trialManager = new TrialManager(this);
        this.missionTrialManager = new org.hkrpg.server.gameplay.MissionTrialManager(this);
        this.questManager = new QuestManager(this);
        this.rewardLedger = new RewardLedger(this);
        this.cycleScoreManager = new CycleScoreManager(this);
        this.missionManager = new MissionManager(this);
        this.dailyManager = new DailyManager(this);
        this.loginActivityManager = new LoginActivityManager(this);
        this.universeManager = new UniverseManager(this);
    }

    // Called when player is created
    public Player(GameSession session) {
        this();
        this.session = session;
        this.accountUid = getAccount().getUid();
        this.isNew = true;
        this.initUid();
        this.resetPosition();
        this.setLevel(LunarCore.getConfig().getServerOptions().startTrailblazerLevel);

        // Setup player data
        this.name = GameConstants.DEFAULT_NAME;
        this.signature = "";
        this.phoneTheme = 221000;
        this.chatBubble = 220000;
        this.currentPamSkinId = 252000;
        this.stamina = GameConstants.MAX_STAMINA;
        this.nextStaminaRecover = System.currentTimeMillis();

        this.currentBgm = 210000;

        this.lineupManager = new LineupManager(this);
        this.gachaInfo = new PlayerGachaInfo();
        this.unlocks = new PlayerUnlockData(this);

        // Set default head icon for the player
        if (GameConstants.DEFAULT_HEAD_ICONS.length > 0) {
            this.headIcon = GameConstants.DEFAULT_HEAD_ICONS[0];
        }

        // Setup hero paths
        this.getAvatars().validateMultiPaths();

        // Prepare the owned protagonist for the later character choice. The opening mission
        // provides Kafka through MissionTrialManager without changing this formal team.
        GameAvatar avatar = new GameAvatar(GameConstants.TRAILBLAZER_AVATAR_ID);
        this.addAvatar(avatar);
        this.getCurrentLineup().getAvatars().add(avatar.getAvatarId());
        this.getCurrentLineup().save();
    }

    public GameServer getServer() {
        return session.getServer();
    }

    public Account getAccount() {
        return session.getAccount();
    }

    public void setLevel(int lvl) {
        int oldLevel = this.level;
        int newLevel = Math.max(Math.min(lvl, GameConstants.MAX_TRAILBLAZER_LEVEL), 1);
        this.onLevelChange(oldLevel, newLevel);

        this.level = newLevel;
        this.exp = GameData.getPlayerExpRequired(this.level);
        if (this.isOnline()) this.getMissionManager().onLevelChange();

        if (this.isOnline()) {
            this.getSession().send(new PacketPlayerSyncScNotify(this));
            this.save();
        }
    }

    private void onLevelChange(int oldLevel, int newLevel) {
        // Auto upgrades the player's world level when they level up to the right level
        if (LunarCore.getConfig().getServerOptions().autoUpgradeWorldLevel) {
            int maxWorldLevel = 0;

            for (int i = 0; i < GameConstants.WORLD_LEVEL_UPGRADES.length; i++) {
                if (newLevel >= GameConstants.WORLD_LEVEL_UPGRADES[i]) {
                    maxWorldLevel = i;
                } else {
                    break;
                }
            }

            if (maxWorldLevel > this.getWorldLevel()) {
                this.setWorldLevel(maxWorldLevel);
            }
        }
    }

    public boolean isOnline() {
        return this.getSession() != null && this.loggedIn;
    }

    public void setSession(GameSession session) {
        if (this.session == null) {
            this.session = session;
        }
    }

    public boolean setNickname(String nickname) {
        if (nickname != this.name && nickname.length() <= 32) {
            this.name = nickname;
            this.sendPacket(new PacketPlayerSyncScNotify(this));
            this.save();
            return true;
        }

        return false;
    }

    public void setSignature(String signature) {
        if (signature.length() > 50) { // Client's max signature length is 50
            signature = signature.substring(0, 49);
        }
        this.signature = signature;
        this.save();
    }

    public int setBirthday(int birthday) {
        if (this.birthday == 0) {
            int month = birthday / 100;
            int day = birthday % 100;

            if (month >= 1 && month <= 12 && day >= 1 && day <= 31) {
                this.birthday = birthday;
                this.save();
                return this.birthday;
            }
        }

        return 0;
    }

    public void setWorldLevel(int level) {
        if (this.worldLevel == level) {
            return;
        }

        this.worldLevel = level;

        if (this.isOnline()) {
            this.getMissionManager().onLevelChange();
            this.save();
            this.getSession().send(new PacketPlayerSyncScNotify(this));
        }
    }

    public int getCurrentBgm() {
        if (this.currentBgm == 0) {
            this.currentBgm = 210000;
            this.save();
        }
        return this.currentBgm;
    }

    public void setCurrentBgm(int musicId) {
        this.currentBgm = musicId;
        this.save();
    }

    public void resetPosition() {
        if (this.isOnline()) {
            return;
        }

        this.pos = GameConstants.START_POS.clone();
        this.rot = new Position();
        this.planeId = GameConstants.START_PLANE_ID;
        this.floorId = GameConstants.START_FLOOR_ID;
        this.entryId = GameConstants.START_ENTRY_ID;
    }

    public boolean addAvatar(GameAvatar avatar) {
        return getAvatars().addAvatar(avatar);
    }

    public GameAvatar getAvatarById(int avatarId) {
        var trial = getStoryLineManager().avatar(avatarId);
        if (trial != null) return trial;
        return getAvatars().getAvatarById(avatarId);
    }

    public GameAvatar getAvatarById(ObjectId id) {
        return getAvatars().getAvatarById(id);
    }

    public boolean setHeadIcon(int id) {
        if (this.getUnlocks().getHeadIcons().contains(id)) {
            this.headIcon = id;
            if (this.isLoggedIn()) {
                this.save();
            }
            return true;
        }
        return false;
    }

    public boolean setChatBubble(int id) {
        if (this.getUnlocks().getChatBubbles().contains(id)) {
            this.chatBubble = id;
            this.save();
            this.sendPacket(new PacketPlayerSyncScNotify(this));
            return true;
        }
        return false;
    }

    public boolean setPhoneTheme(int id) {
        if (this.getUnlocks().getPhoneThemes().contains(id)) {
            this.phoneTheme = id;
            this.save();
            this.sendPacket(new PacketPlayerSyncScNotify(this));
            return true;
        }
        return false;
    }

    public PlayerLineup getCurrentLineup() {
        return this.getLineupManager().getCurrentLineup();
    }

    public GameAvatar getCurrentLeaderAvatar() {
        return this.getLineupManager().getCurrentLeaderAvatar();
    }

    private void initUid() {
        if (this.uid > 0) return;

        int nextUid = session.getAccount().getReservedPlayerUid();

        if (nextUid == GameConstants.SERVER_CONSOLE_UID) {
            nextUid = 0;
        }

        while (nextUid == 0 || LunarCore.getGameDatabase().checkIfObjectExists(Player.class, nextUid)) {
            nextUid = LunarCore.getGameDatabase().getNextObjectId(Player.class);
        }

        this.uid = nextUid;
    }

    public void addSCoin(int amount) {
        int newAmount = Utils.safeAdd(this.scoin, amount);
        if (this.scoin != newAmount) {
            this.scoin = newAmount;
            this.sendPacket(new PacketPlayerSyncScNotify(this));
        }
    }

    /** Caller persists this balance together with the gameplay operation before notifying clients. */
    public synchronized boolean tryChangeTalentPoints(int amount) {
        long next = (long) this.talentPoints + amount;
        if (next < 0 || next > Integer.MAX_VALUE) return false;
        this.talentPoints = (int) next;
        return true;
    }

    public void addHCoin(int amount) {
        int newAmount = Utils.safeAdd(this.hcoin, amount);
        if (this.hcoin != newAmount) {
            this.hcoin = newAmount;
            this.sendPacket(new PacketPlayerSyncScNotify(this));
        }
    }

    public void addMCoin(int amount) {
        int newAmount = Utils.safeAdd(this.mcoin, amount);
        if (this.mcoin != newAmount) {
            this.mcoin = newAmount;
            this.sendPacket(new PacketPlayerSyncScNotify(this));
        }
    }

    public void addExp(int amount) {
        // Setup
        int oldLevel = this.level;
        int expRequired = GameData.getPlayerExpRequired(level + 1);

        // Add exp
        this.exp += amount;

        // Check for level ups
        while (this.exp >= expRequired && expRequired > 0) {
            this.level += 1;
            expRequired = GameData.getPlayerExpRequired(this.level + 1);
        }

        // Update level and change property
        if (oldLevel != this.level) {
            this.onLevelChange(oldLevel, this.level);
        }

        // Save to database
        this.save();

        // Send packet
        this.sendPacket(new PacketPlayerSyncScNotify(this));
    }

    public int getDisplayExp() {
        return this.exp - GameData.getPlayerExpRequired(this.level);
    }

    public int getCurAvatarPathId(int baseAvatarId) {
        return this.getCurAvatarPaths().getOrDefault(baseAvatarId, 0);
    }

    public AvatarMultiPath getCurAvatarPath(int baseAvatarId) {
        int pathId = this.getCurAvatarPathId(baseAvatarId);
        return this.getAvatars().getMultiPathById(pathId);
    }

    public int setAvatarPath(int pathId) {
        // Get excel
        var excel = GameData.getMultiplePathAvatarExcelMap().get(pathId);
        if (excel == null) return 0;

        // Get path data
        AvatarMultiPath path = this.getAvatars().getMultiPathById(pathId);
        if (path == null) return 0;

        // Get avatar data
        GameAvatar avatar = this.getAvatarById(excel.getBaseAvatarID());
        if (avatar == null) return 0;

        // Set new avatar path
        avatar.setMultiPath(path);

        // Set gender if we are changing the main character
        if (excel.getBaseAvatarID() == GameConstants.TRAILBLAZER_AVATAR_ID && excel.getGender() != null) {
            this.gender = excel.getGender();
        }

        // Set current avatar path and save to database
        this.getCurAvatarPaths().put(excel.getBaseAvatarID(), pathId);
        this.save();

        // Sync with client
        this.sendPacket(new PacketAvatarPathChangedNotify(avatar, path));
        this.sendPacket(new PacketPlayerSyncScNotify(avatar));

        // Success
        return pathId;
    }

    /** Explicitly selected initial identity; the default male avatar is not this proof. */
    public boolean hasCreatedCharacter() {
        var chosen = getGameplay().getStoryValues().get("profile:character-path");
        return Integer.valueOf(8001).equals(chosen) || Integer.valueOf(8002).equals(chosen);
    }

    private static boolean isValidInitialNickname(String nickname) {
        return nickname != null && !nickname.isBlank() && nickname.length() <= 32
                && nickname.codePoints().noneMatch(Character::isISOControl);
    }

    private boolean isInitialProfileBusy() {
        return isInBattle() || getInstance() != null || getGameplay().getTrialRun() != null
                || getGameplay().getStoryLines().isTransitionPending()
                || getGameplay().getRogue().isInside() || getGameplay().getParkourVenue().inside()
                || getGameplay().getCurrentMissionRaid() != 0;
    }

    /** Naming the receptacle does not confirm its gender or finish CreateCharacter. */
    public Retcode setInitialNickname(String nickname) {
        synchronized (this) {
            if (!isValidInitialNickname(nickname)) return Retcode.REQ_PARA_INVALID;
            if (nickname.equals(name)) return Retcode.SUCC;
            if (getGameplay().getStoryValues().containsKey("profile:character-path"))
                return Retcode.GENDER_ERROR;
            if (isInitialProfileBusy()) return Retcode.SYSTEM_BUSY;
            var previousName = name;
            this.name = nickname;
            try {
                save();
            } catch (RuntimeException failure) {
                this.name = previousName;
                throw failure;
            }
            sendPacket(new PacketPlayerSyncScNotify(this));
            return Retcode.SUCC;
        }
    }

    public Retcode setInitialProfile(String nickname, int pathId) {
        synchronized (this) {
            if (!isValidInitialNickname(nickname)
                    || pathId != 8001 && pathId != 8002) return Retcode.REQ_PARA_INVALID;
            if (getGameplay().getStoryValues().containsKey("profile:character-path")) {
                if (!Objects.equals(getGameplay().getStoryValues().get("profile:character-path"), pathId)
                        || !nickname.equals(name) || getCurAvatarPathId(8001) != pathId)
                    return Retcode.GENDER_ERROR;
                getMissionManager().refresh();
                return Retcode.SUCC;
            }
            if (isInitialProfileBusy()) return Retcode.SYSTEM_BUSY;
            var config = GameData.getMultiplePathAvatarExcelMap().get(pathId);
            var path = getAvatars().getMultiPathById(pathId);
            var avatar = getAvatars().getAvatars().get(8001);
            var expected = pathId == 8001 ? PlayerGender.GENDER_MAN : PlayerGender.GENDER_WOMAN;
            if (config == null || config.getAvatarID() != pathId || config.getBaseAvatarID() != 8001
                    || config.getGender() != expected || path == null || path.getExcelId() != pathId
                    || path.getOwner() != this || path.getExcel() == null || path.getData() == null
                    || path.getData().getBaseAvatar() != path || path.getExcel().getId() != pathId
                    || avatar == null || avatar.getOwner() != this || avatar.getAvatarId() != 8001
                    || avatar.getMultiPath() == null
                    || path.getAvatar() != null && path.getAvatar() != avatar)
                return Retcode.GENDER_ERROR;
            var previousPath = avatar.getMultiPath();
            var previousName = name;
            var previousGender = gender;
            var hadPath = getCurAvatarPaths().containsKey(8001);
            var previousPathId = getCurAvatarPaths().get(8001);
            avatar.setMultiPath(path);
            this.gender = expected;
            this.name = nickname;
            getCurAvatarPaths().put(8001, pathId);
            getGameplay().getStoryValues().put("profile:character-path", pathId);
            // Name, gender, selected path and confirmation live in the same player document.
            try {
                save();
            } catch (RuntimeException failure) {
                avatar.setMultiPath(previousPath);
                this.name = previousName;
                this.gender = previousGender;
                if (hadPath) getCurAvatarPaths().put(8001, previousPathId);
                else getCurAvatarPaths().remove(8001);
                getGameplay().getStoryValues().remove("profile:character-path");
                throw failure;
            }
            getMissionManager().refresh();
            sendPacket(new PacketAvatarPathChangedNotify(avatar, path));
            sendPacket(new PacketPlayerSyncScNotify(avatar, this));
            return Retcode.SUCC;
        }
    }

    public int getNextBattleId() {
        return ++nextBattleId;
    }

    public boolean isInBattle() {
        return this.battle != null;
    }

    public void setBattle(Battle battle) {
        // Set battle first
        this.battle = battle;
        if (battle != null) battle.prepareAvatarBuffs();
        // Scene handler
        if (this.getScene() != null) {
            this.getScene().onBattleStart(battle);
        }
    }

    public void forceQuitBattle() {
        if (this.battle != null) {
            this.battle = null;
            this.getSession().send(CmdId.QuitBattleScNotify);
        }
    }

    public synchronized void setInstance(PlayerGameInstance instance) {
        // Sanity check
        if (this.instance == instance) return;

        // Handle event
        if (this.instance != null) {
            this.instance.onLeave();
        }

        // Set new isntance
        this.instance = instance;
    }

    public synchronized <T extends PlayerGameInstance> T getInstance(Class<T> instanceType) {
        // Make sure we have an object to cast to
        if (this.instance == null) {
            return null;
        }

        // Cast to our activity type
        if (instanceType.isInstance(this.instance)) {
            return instanceType.cast(this.instance);
        }

        return null;
    }

    public void addStamina(int amount) {
        this.stamina = Utils.safeAdd(this.stamina, amount);
        this.sendPacket(new PacketStaminaInfoScNotify(this));
    }

    public void spendStamina(int amount) {
        if (amount <= 0) return;
        if (!LunarCore.getConfig().getServerOptions().spendStamina) {
            return;
        }

        int spent = Math.min(this.stamina, amount);
        this.stamina -= spent;
        this.getQuestManager().onStaminaConsumed(spent);
        this.getDailyManager().onStaminaConsumed(spent);
        this.sendPacket(new PacketStaminaInfoScNotify(this));
    }

    public int exchangeReserveStamina(int amount) {
        // Sanity checks
        if (amount <= 0 || this.staminaReserve < amount) {
            return 0;
        }

        this.staminaReserve -= amount;
        this.stamina += amount;

        // Update to client
        this.sendPacket(new PacketStaminaInfoScNotify(this));

        return amount;
    }

    private void updateStamina(long timestamp) {
        // Setup on change flag
        boolean hasChanged = false;

        // Check if we can add stamina
        while (timestamp >= this.nextStaminaRecover) {
            // Add stamina
            if (this.stamina < GameConstants.MAX_STAMINA) {
                this.stamina += 1;
                hasChanged = true;
            } else if (this.stamina < GameConstants.MAX_STAMINA_RESERVE) {
                double rate = LunarCore.getConfig().getServerOptions().getStaminaReserveRecoveryRate();
                double amount = (timestamp - this.nextStaminaRecover) / (rate * 1000D);
                this.staminaReserve = Math.min(this.staminaReserve + amount, GameConstants.MAX_STAMINA_RESERVE);
                hasChanged = true;
            }

            // Calculate next stamina recover time
            if (this.stamina >= GameConstants.MAX_STAMINA) {
                this.nextStaminaRecover = timestamp;
            }

            this.nextStaminaRecover += LunarCore.getConfig().getServerOptions().getStaminaRecoveryRate() * 1000;
        }

        // Send packet
        if (hasChanged && this.isOnline()) {
            this.getSession().send(new PacketStaminaInfoScNotify(this));
        }
    }

    public synchronized boolean addFoodBuff(int type, ItemUseExcel itemUseExcel) {
        // Get maze excel
        var excel = GameData.getMazeBuffExcelMap().get(itemUseExcel.getMazeBuffID(), 1);
        if (excel == null) return false;

        // Create new buff
        var buff = new SceneBuff(itemUseExcel.getMazeBuffID());
        buff.setCount(Math.max(itemUseExcel.getActivityCount(), 1));

        int avatarEntityId = getCurrentLeaderAvatar().getEntityId();
        var oldBuff = this.getFoodBuffs().put(type, buff);

        // Send packets
        if (oldBuff != null) {
            this.sendPacket(new PacketSyncEntityBuffChangeListScNotify(avatarEntityId, oldBuff.getBuffId()));
        }

        this.sendPacket(new PacketSyncEntityBuffChangeListScNotify(avatarEntityId, buff));
        return true;
    }

    public synchronized boolean removeFoodBuffs(int amount) {
        // Sanity check
        if (getFoodBuffs().size() == 0) return false;

        // Cache current avatar entity id
        int avatarEntityId = getCurrentLeaderAvatar().getEntityId();

        // Remove and send packet for each buff removed
        for (var it = getFoodBuffs().int2ObjectEntrySet().iterator(); it.hasNext();) {
            var entry = it.next();
            var buff = entry.getValue();

            if (buff.decrementAndGet() <= 0) {
                it.remove();
                this.sendPacket(new PacketSyncEntityBuffChangeListScNotify(avatarEntityId, buff.getBuffId()));
            } else {
                this.sendPacket(new PacketSyncEntityBuffChangeListScNotify(avatarEntityId, buff));
            }
        }

        return true;
    }

    public synchronized EntityProp interactWithProp(int interactId, int propEntityId) {
        if (getScene() != null && getScene().getEntityById(propEntityId) instanceof EntityProp reading
            && org.hkrpg.server.gameplay.BookPickups.applies(reading))
            return org.hkrpg.server.gameplay.BookPickups.pickUp(this, interactId, propEntityId).prop();
        if (getScene() != null && getScene().getEntityById(propEntityId) instanceof EntityProp golden
            && org.hkrpg.server.gameplay.GoldenSpears.applies(golden))
            return org.hkrpg.server.gameplay.GoldenSpears.interact(this, interactId, propEntityId).prop();
        if (getScene() != null && getScene().getEntityById(propEntityId) instanceof EntityProp candidate
            && org.hkrpg.server.gameplay.WorldChests.applies(candidate))
            return org.hkrpg.server.gameplay.WorldChests.open(this, interactId, propEntityId).prop();
        if (org.hkrpg.server.gameplay.SceneInteractions.validate(this, interactId, propEntityId) != emu.lunarcore.server.packet.Retcode.SUCC) return null;
        // Sanity
        if (this.getScene() == null) return null;

        // Get entity so we can cast it to a prop
        GameEntity entity = getScene().getEntityById(propEntityId);

        EntityProp prop = null;
        if (entity instanceof EntityProp) {
            prop = (EntityProp) entity;
        } else {
            return null;
        }

        // Get interact handler
        InteractExcel interactExcel = GameData.getInteractExcelMap().get(interactId);
        if (interactExcel == null) {
            return prop;
        }

        // Validate
        /*
        if (interactExcel.getSrcState() != null && prop.getState() != interactExcel.getSrcState()) {
            return prop;
        }
        */

        if (org.hkrpg.server.gameplay.ScenePropTransitions.applies(prop))
            return org.hkrpg.server.gameplay.ScenePropTransitions.interact(this, interactId, propEntityId).prop();

        // Save old state
        PropState oldState = prop.getState();
        PropState newState = interactExcel.getTargetState();

        // Set group and prop state
        this.sendPacket(new PacketGroupStateChangeScNotify(getEntryId(), prop.getGroupId(), newState));
        prop.setState(newState);

        // Handle any extra interaction actions
        switch (prop.getExcel().getPropType()) {
            case PROP_DESTRUCT -> {
                if (prop.getState() == PropState.Closed) {
                    // Open chest
                    prop.setState(PropState.Open);
                } else {
                    prop.setState(PropState.Closed);
                }
            }
            case PROP_TREASURE_CHEST -> {
                if (oldState == PropState.ChestClosed && newState == PropState.ChestUsed) {
                    // Handle drops
                    var drops = this.getServer().getDropService().calculateChestDrops(getScene().getWorldId(), prop.getPropId());
                    this.getScene().removeAllMapPropDefInfo();
                    this.sendPacket(new PacketMarkChestChangedScNotify(new ArrayList<>()));
                    this.getInventory().addItems(drops, true);
                }
            }
            case PROP_MAZE_PUZZLE -> {
                // Trigger event
                if (newState == PropState.Open || newState == PropState.Closed) {
                    // Unlock everything in the prop's group
                    for (var p : getScene().getEntitiesByGroup(EntityProp.class, prop.getGroupId())) {
                        if (p.getPropType() == PropType.PROP_TREASURE_CHEST) {
                            p.setState(PropState.ChestClosed);
                        } else if (p.getPropType() == PropType.PROP_MAZE_PUZZLE) {
                            // Skip
                        } else {
                            p.setState(PropState.Open);
                        }
                    }
                }
            }
            case PROP_ORDINARY -> {
                if (prop.getPropInfo() != null && prop.getPropInfo().isCommonConsole()) {
                    // Set platform states
                    getScene().getEntitiesByGroup(EntityProp.class, prop.getGroupId())
                        .stream()
                        .filter(p -> p.getExcel().getPropType() == PropType.PROP_PLATFORM)
                        .forEach(p -> p.setState(newState));
                } else if (prop.getPropId() == 104039) {
                    // Purification prop
                    getScene().getEntitiesByGroup(EntityProp.class, prop.getGroupId())
                        .stream()
                        .filter(p -> p.getPropId() == 104038)
                        .forEach(p -> p.setState(newState));
                }
            }
            default -> {
                // Skip
            }
        }

        // Persist the final transition before advancing an objective that may unload this group.
        org.hkrpg.server.gameplay.SceneInteractions.persist(prop);
        this.save();
        this.getMissionManager().onPropInteraction(this.getFloorId(), prop.getGroupId(), prop.getInstId(), prop.getState().getVal());

        // Return prop when we are done
        return prop;
    }

    public void onMove() {
        // Sanity
        if (this.getScene() == null) return;

        org.hkrpg.server.gameplay.SceneAnchors.onMove(this);
        // Check anchors. We can optimize this later.
        EntityProp nearestAnchor = this.getScene().getNearestSpring(25_000_000); // 5000^2
        boolean isInRange = nearestAnchor != null && nearestAnchor.getState() == PropState.CheckPointEnable;

        // Only heal if player isnt already in anchor range
        if (isInRange && isInRange != this.inAnchorRange) {
            this.getCurrentLineup().heal(10000, true);
        }
        this.inAnchorRange = isInRange;
    }

    public void moveTo(int entryId, Position pos) {
        this.entryId = entryId;
        this.moveTo(pos);
    }

    public void moveTo(int entryId, Position pos, Position rot) {
        this.entryId = entryId;
        this.moveTo(pos, rot);
    }

    public void moveTo(Position pos) {
        this.getPos().set(pos);
        this.sendPacket(new PacketSceneEntityMoveScNotify(this));
    }

    public void moveTo(Position pos, Position rot) {
        this.getPos().set(pos);
        this.getRot().set(rot);
        this.sendPacket(new PacketSceneEntityMoveScNotify(this));
    }

    /** Read-only preflight for resource-authored story transitions. */
    public boolean hasSceneAnchor(int entryId, int groupId, int anchorId) {
        var entry = GameData.getMapEntranceExcelMap().get(entryId);
        var floor = entry == null ? null : GameData.getFloorInfo(entry.getPlaneID(), entry.getFloorID());
        return floor != null && floor.isGroupInDimension(groupId, dimensionForEntry(entry))
            && floor.getAnchorInfo(groupId, anchorId) != null;
    }

    private int dimensionForEntry(MapEntranceExcel entry) {
        if (getGameplay().getStoryLines().getCurrentId() != 0)
            return StoryLineDefinitions.dimension(this, getGameplay().getStoryLines().getCurrentId(),
                    GameData.getFloorInfo(entry.getPlaneID(), entry.getFloorID())).orElse(-1);
        var current = getScene();
        return current != null && current.getPlaneId() == entry.getPlaneID()
            && current.getFloorId() == entry.getFloorID() ? current.getDimensionId() : 0;
    }

    public boolean enterSceneAtAnchor(int entryId, int groupId, int anchorId, boolean sendPacket) {
        var entry = GameData.getMapEntranceExcelMap().get(entryId);
        if (entry == null) return false;
        var floor = GameData.getFloorInfo(entry.getPlaneID(), entry.getFloorID());
        if (floor == null || !floor.isGroupInDimension(groupId, dimensionForEntry(entry))) return false;
        var anchor = floor == null ? null : floor.getAnchorInfo(groupId, anchorId);
        if (anchor == null) return false;
        return loadScene(entry.getPlaneID(), entry.getFloorID(), entryId, anchor.getPos(), anchor.getRot(), sendPacket);
    }

    public boolean enterScene(int entryId, int teleportId, boolean sendPacket) {
        // Get map entrance excel
        MapEntranceExcel entry = GameData.getMapEntranceExcelMap().get(entryId);
        if (entry == null) return false;

        // Get floor info
        FloorInfo floor = GameData.getFloorInfo(entry.getPlaneID(), entry.getFloorID());
        if (floor == null) return false;

        // Get teleport anchor info (contains position) from the entry id
        int startGroup = entry.getStartGroupID();
        int anchorId = entry.getStartAnchorID();

        if (teleportId != 0) {
            PropInfo teleport = floor.getCachedTeleports().get(teleportId);
            if (!org.hkrpg.server.gameplay.SceneAnchors.unlocked(this, entry, teleport)) return false;
            if (teleport != null) {
                startGroup = teleport.getAnchorGroupID();
                anchorId = teleport.getAnchorID();
            }
        } else if (anchorId == 0) {
            var group = floor.getGroupInfoByIndex(floor.getStartGroupIndex());
            if (group == null) return false;

            startGroup = group.getId();
            anchorId = floor.getStartAnchorID();
        }

        if (!floor.isGroupInDimension(startGroup, dimensionForEntry(entry))) return false;
        AnchorInfo anchor = floor.getAnchorInfo(startGroup, anchorId);
        if (anchor == null) return false;

        // Move player to scene
        return this.loadScene(entry.getPlaneID(), entry.getFloorID(), entry.getId(), anchor.getPos(), anchor.getRot(), sendPacket);
    }

    /** Location rollback for a gameplay transaction whose player save did not commit. */
    public static final class SceneLocationSnapshot {
        private final Player owner;
        private final int planeId, floorId, entryId, worldId, sceneEntryId;
        private final Scene scene;
        private final Position pos, rot;
        private final org.hkrpg.server.gameplay.SceneTeleportLocation teleportLocation;
        private final int teleportMapLayer;

        private SceneLocationSnapshot(Player owner) {
            this.owner = owner;
            this.planeId = owner.planeId;
            this.floorId = owner.floorId;
            this.entryId = owner.entryId;
            this.worldId = owner.worldId;
            this.scene = owner.scene;
            this.sceneEntryId = scene == null ? 0 : scene.getEntryId();
            this.pos = new Position(owner.getPos());
            this.rot = new Position(owner.getRot());
            this.teleportLocation = owner.sceneTeleportLocation;
            this.teleportMapLayer = scene == null ? 0 : scene.getTeleportMapLayer();
        }
    }

    public final SceneLocationSnapshot captureSceneLocation() {
        return new SceneLocationSnapshot(this);
    }

    public final void restoreSceneLocation(SceneLocationSnapshot snapshot) {
        Objects.requireNonNull(snapshot);
        if (snapshot.owner != this) throw new IllegalArgumentException("Scene location belongs to another player");
        this.planeId = snapshot.planeId;
        this.floorId = snapshot.floorId;
        this.entryId = snapshot.entryId;
        this.worldId = snapshot.worldId;
        this.scene = snapshot.scene;
        this.getPos().set(snapshot.pos);
        this.getRot().set(snapshot.rot);
        this.sceneTeleportLocation = snapshot.teleportLocation;
        if (this.scene != null) this.scene.setTeleportMapLayer(snapshot.teleportMapLayer);
        if (this.scene != null) this.scene.setEntryId(snapshot.sceneEntryId);
    }

    public boolean loadScene(int planeId, int floorId, int entryId, Position pos, Position rot, boolean sendPacket) {
        int dimension = 0;
        if (getGameplay().getStoryLines().getCurrentId() != 0) {
            var selected = StoryLineDefinitions.dimension(this, getGameplay().getStoryLines().getCurrentId(),
                    GameData.getFloorInfo(planeId, floorId));
            if (selected.isEmpty()) return false;
            dimension = selected.getAsInt();
        } else if (getScene() != null && getScene().getPlaneId() == planeId && getScene().getFloorId() == floorId) {
            dimension = getScene().getDimensionId();
        }
        return loadScene(planeId, floorId, entryId, pos, rot, sendPacket, dimension);
    }

    public boolean loadScene(int planeId, int floorId, int entryId, Position pos, Position rot,
            boolean sendPacket, int dimensionId) {
        int oldPlaneId = this.planeId, oldFloorId = this.floorId, oldEntryId = this.entryId;
        // Get maze plane excel
        MazePlaneExcel planeExcel = GameData.getMazePlaneExcelMap().get(planeId);
        if (planeExcel == null) return false;

        // Unstuck check
        switch (planeExcel.getPlaneType()) {
            case Challenge:
                if (this.getInstance(ChallengeInstance.class) == null) {
                    return enterScene(GameConstants.CHALLENGE_ENTRANCE, 0, false);
                }
                break;
            case Rogue:
                break;
            case TrialActivity:
                if (this.getInstance(TrialInstance.class) == null) {
                    return enterScene(GameConstants.START_ENTRY_ID, 0, false);
                }
                break;
            default:
                break;
        }

        var outgoingFloorObjectives = org.hkrpg.server.gameplay.MissionFloorChanges.capture(
                this, planeId, floorId, entryId, dimensionId);

        // Get scene that we want to enter
        Scene nextScene = null;

        if (getScene() != null && getScene().getPlaneId() == planeId && getScene().getFloorId() == floorId && getScene().getPlaneType() != PlaneType.Rogue
                && getScene().getDimensionId() == dimensionId
                && getScene().getStoryLineId() == getGameplay().getStoryLines().getCurrentId()) {
            // Don't create a new scene if were already in the one we want to teleport to
            nextScene = this.scene;
        } else {
            nextScene = new Scene(this, planeExcel, floorId, dimensionId);
        }

        var previousLocation = captureSceneLocation();
        String visitKey = "train:visited:" + planeExcel.getWorldID();
        boolean hadVisit = getGameplay().getStoryValues().containsKey(visitKey);
        Integer previousVisit = getGameplay().getStoryValues().get(visitKey);
        // Keep confirmed ordinary-world visits with the location in the same player document.
        boolean trainWorldRecorded = false;
        if (planeExcel.getPlaneType() == PlaneType.Town || planeExcel.getPlaneType() == PlaneType.Maze) {
            this.worldId = planeExcel.getWorldID();
            trainWorldRecorded = org.hkrpg.server.gameplay.TrainWorlds.remember(this, this.worldId);
        }

        // Set player position
        if (nextScene == this.scene && (entryId != this.entryId
                || !this.getPos().equals(pos) || !this.getRot().equals(rot))) {
            // A different anchor has no layer report yet; retain the legacy initial layer.
            nextScene.setTeleportMapLayer(1);
        }
        this.getPos().set(pos);
        this.getRot().set(rot);

        // Save if scene has changed
        if (trainWorldRecorded || this.planeId != planeId || this.floorId != floorId || this.entryId != entryId) {
            this.planeId = planeId;
            this.floorId = floorId;
            this.entryId = entryId;
            try {
                this.save();
            } catch (RuntimeException failed) {
                // No destination scene or arrival callback has been published yet. Keep location
                // and the visit receipt together, so retry/reconnect starts from the saved scene.
                restoreSceneLocation(previousLocation);
                if (hadVisit) getGameplay().getStoryValues().put(visitKey, previousVisit);
                else getGameplay().getStoryValues().remove(visitKey);
                throw failed;
            }
        }

        // Set player scene
        this.scene = nextScene;
        this.scene.setEntryId(entryId);
        this.scene.getTeleportMapLayer(); // Bind a restored layer before the first ordinary movement.
        getMissionTrialManager().reconcile();
        getStoryLineManager().onSceneLoaded();
        this.getMissionManager().onFloorChanged(outgoingFloorObjectives);
        this.getMissionManager().onSceneTransition(oldPlaneId, oldFloorId, oldEntryId, planeId, floorId, entryId);

        // Send packet
        if (sendPacket) {
            this.getSession().send(CmdId.SyncServerSceneChangeNotify);
            this.getSession().send(new PacketEnterSceneByServerScNotify(this));
        }

        // Done, return success
        return true;
    }

    public boolean loadScene(Location location, boolean sendPacket) {
        return loadScene(location.getPlaneId(), location.getFloorId(), location.getEntryId(), location.getPos(), location.getRot(), sendPacket);
    }


    public void setAssistAvatars(RepeatedInt avatars) {
        // Check size
        if (avatars.length() > 3) {
            return;
        }

        // Clear
        this.getAssistAvatars().clear();

        // Parse list from proto
        for (int id : avatars) {
            GameAvatar avatar = this.getAvatarById(id);
            if (avatar == null) continue;

            this.getAssistAvatars().add(avatar.getId());
        }

        // Save player
        this.save();
    }

    public void setDisplayAvatars(RepeatedMessage<DisplayAvatar> avatars) {
        // Check size
        if (avatars.length() > 5) {
            return;
        }

        // Clear
        this.getDisplayAvatars().clear();

        // Parse list from proto
        for (int i = 0; i < avatars.length(); i++) {
            var info = avatars.get(i);

            if (info.getAvatarId() > 0) {
                GameAvatar avatar = this.getAvatarById(info.getAvatarId());
                if (avatar == null) continue;

                this.getDisplayAvatars().add(avatar.getId());
            } else {
                this.getDisplayAvatars().add(null);
            }
        }

        // Save player
        this.save();
    }

    public void sendMessage(String message) {
        var msg = new ChatMessage(GameConstants.SERVER_CONSOLE_UID, this.getUid(), message);
        this.getChatManager().addChatMessage(msg);
    }

    public void sendPacket(BasePacket packet) {
        if (this.isOnline()) {
            this.getSession().send(packet);
        }
    }

    public void onTick(long timestamp, long delta) {
        org.hkrpg.server.protocol.CompositionLimit46.refresh(this, java.time.Instant.ofEpochMilli(timestamp));
        org.hkrpg.server.protocol.Tutorial46.refresh(this);
        // Update stamina
        this.updateStamina(timestamp);
        // Scene update
        if (this.getScene() != null) {
            this.getScene().onTick(timestamp, delta);
        }
        if (this.getSession() != null) {
            this.getSession().getMissionSceneRecovery().tick(this, timestamp);
            this.getSession().getRogueFirstStoryRecovery().tick(this, timestamp);
        }
    }

    public void onLogin() {
        var pendingTravel = org.hkrpg.server.gameplay.MissionSceneEntrances.captureLogin(this);
        // Set up lineup manager
        this.getLineupManager().setPlayer(this);

        // Load avatars and inventory first
        this.getAvatars().loadFromDatabase();
        this.getInventory().loadFromDatabase();
        this.getLineupManager().loadFromDatabase();
        // Earned story characters may be reconciled during mission refresh on login.
        this.loadUnlocksFromDatabase();
        this.getMissionTrialManager().restoreOnLogin();
        this.getFriendList().loadFromDatabase();
        this.getMailbox().loadFromDatabase();
        this.getChallengeManager().loadFromDatabase();
        this.getQuestManager().refreshUnlocks();
        this.getMissionManager().refresh();
        this.getDailyManager().ensureDay();
        this.getLoginActivityManager().recordLogin();

        // Update stamina
        this.updateStamina(System.currentTimeMillis());

        // Set default world id if we don't have it
        if (this.worldId == 0) {
            this.worldId = GameConstants.DEFAULT_WORLD_ID;
        }

        // Set default world id if we don't have it
        if (this.worldId == 0) {
            this.worldId = GameConstants.DEFAULT_WORLD_ID;
        }

        // Unstuck check, dont load player into raid scenes
        boolean parkourVenue = new ParkourRaid(this).restoreOnLogin();
        boolean missionRaid = new org.hkrpg.server.gameplay.MissionRaid(this).restoreOnLogin();
        boolean trialRestored = this.getTrialManager().restoreOnLogin();
        MazePlaneExcel planeExcel = GameData.getMazePlaneExcelMap().get(planeId);
        if (!trialRestored && (planeExcel == null || planeExcel.getPlaneType().getVal() >= PlaneType.Raid.getVal() && !parkourVenue && !missionRaid)) {
            this.resetPosition();
        }

        // Load into saved scene (should happen after everything else loads)
        if (!trialRestored && !getStoryLineManager().restoreOnLogin()) {
            org.hkrpg.server.gameplay.RogueFirstStorySpawn.recoverOnLogin(this);
            this.loadScene(planeId, floorId, entryId, this.getPos(), this.getRot(), false);
        }

        // Reset position to starting scene in case we couldn't load the scene
        if (this.getScene() == null) {
            this.enterScene(GameConstants.START_ENTRY_ID, 0, false);
        }

        this.getUniverseManager().restoreFirstStoryBlessings();

        // Make sure the current lineup's leader exists
        var lineup = this.getCurrentLineup();
        if (lineup.size() == 0) {
            lineup.getAvatars().add(GameConstants.TRAILBLAZER_AVATAR_ID);
            lineup.setLeader(0);
            lineup.save();
        } else if (lineup.getLeader() >= lineup.size()) {
            lineup.setLeader(0);
            lineup.save();
        }

        // Sanity check lineup to prevent the player from getting stuck in a loading screen if they loaded into the game with an avatar that had 0 hp
        var leader = this.getCurrentLeaderAvatar();
        if (leader != null && leader.getCurrentHp(this.getCurrentLineup()) <= 0
                && this.getInstance(TrialInstance.class) == null
                && !(lineup instanceof org.hkrpg.server.gameplay.RogueLineup)
                && !(lineup instanceof org.hkrpg.server.gameplay.StoryLineup)
                && !(lineup instanceof org.hkrpg.server.gameplay.MissionTrialLineup)) {
            leader.setCurrentHp(this.getCurrentLineup(), 2000);
            leader.save();
        }

        // Send welcome mail after we load managers from the database
        if (this.isNew) {
            this.getMailbox().sendWelcomeMail();
        }

        org.hkrpg.server.gameplay.BoxingClubManager.restore(this);

        // The client may not replay an accepted EnterMap after a fade/disconnect. Resume only a
        // fully validated, single-action transfer saved before this login and before publishing it.
        if (getSession() != null && getSession().getProtocol().experimental())
            org.hkrpg.server.gameplay.MissionSceneEntrances.resumeOnLogin(this, pendingTravel);

        // Set logged in flag
        this.lastActiveTime = System.currentTimeMillis() / 1000;
        this.loggedIn = true;
        // this.getMissionManager().onLogin();
    }

    public void onLogout() {
        getTrialManager().recordState();
        getStoryLineManager().recordLocation();
        new org.hkrpg.server.gameplay.MissionRaid(this).recordLocation();
        this.loggedIn = false;
        this.lastActiveTime = System.currentTimeMillis() / 1000;
    }

    // Database

    public void save() {
        org.hkrpg.server.gameplay.SceneTeleportLocation.record(this);
        getTrialManager().recordState();
        getStoryLineManager().recordLocation();
        new org.hkrpg.server.gameplay.MissionRaid(this).recordLocation();
        if (this.uid <= 0) {
            LunarCore.getLogger().error("Tried to save a player object without a uid!");
            return;
        }

        LunarCore.getGameDatabase().save(this);
    }

    public void delete() {
        // Close session first
        if (this.getSession() != null) {
            this.getSession().close();
        }

        // Cache filter object so we can reuse it for our delete queries
        var filter = Filters.eq("ownerUid", uid);
        var datastore = LunarCore.getGameDatabase().getDatastore();

        // Delete data from collections
        datastore.getCollection(GameAvatar.class).deleteMany(filter);
        datastore.getCollection(ChallengeHistory.class).deleteMany(filter);
        datastore.getCollection(ChallengeGroupReward.class).deleteMany(filter);
        datastore.getCollection(AvatarMultiPath.class).deleteMany(filter);
        datastore.getCollection(GameItem.class).deleteMany(filter);
        datastore.getCollection(PlayerLineup.class).deleteMany(filter);
        datastore.getCollection(PlayerExtraLineup.class).deleteMany(filter);
        datastore.getCollection(Mail.class).deleteMany(filter);
        // Morphia stores PlayerUnlockData.ownerUid as @Id, hence Mongo's _id field.
        datastore.getCollection(PlayerUnlockData.class).deleteOne(Filters.eq("_id", uid));

        // Delete friendships
        datastore.getCollection(Friendship.class).deleteMany(Filters.or(Filters.eq("ownerUid", uid), Filters.eq("friendUid", uid)));

        // Delete the player last
        LunarCore.getGameDatabase().delete(this);
    }

    private void loadUnlocksFromDatabase() {
        this.unlocks = LunarCore.getGameDatabase().getObjectByField(PlayerUnlockData.class, "ownerUid", this.getUid());

        if (this.unlocks == null) {
            this.unlocks = new PlayerUnlockData(this);
        } else {
            this.unlocks.setOwner(this);
        }
    }

    // Player sync

    public void onSync(PlayerSyncScNotify proto) {
        proto.setBasicInfo(this.toProto());
    }

    // Protobuf serialization

    public PlayerBasicInfo toProto() {
        var proto = PlayerBasicInfo.newInstance()
                .setNickname(this.getName())
                .setLevel(this.getLevel())
                .setExp(this.getDisplayExp())
                .setWorldLevel(this.getWorldLevel())
                .setScoin(this.getScoin())
                .setHcoin(this.getHcoin())
                .setMcoin(this.getMcoin())
                .setStamina(this.getStamina());

        return proto;
    }

    public PlayerMocDisplayInfo getMocDisplayInfo() {
        // todo: get from DB
        var mocDisplayInfo = PlayerMocDisplayInfo.newInstance()
            .setFloorNum(12)
            .setStarCount(36)
            .setSeasonId(21)
            .setInt1(1015); // from pcap

        return mocDisplayInfo;
    }

    public PlayerDisplayInfo getDisplayInfo() {
        var playerDisplayInfo = PlayerDisplayInfo.newInstance()
            .setBookCount(BookCollection.count(this))
            .setPhonographCount(60) // same with this field
            .setAchievementCount(getQuestManager().achievementCount())
            .setInt2(1608)
            .setSuWorld(8) // todo: get from DB
            .setMocDisplayInfo(this.getMocDisplayInfo());

        if (this.isOnline()) {
            playerDisplayInfo.setCharacterCount(this.getAvatars().getAvatarCount());
            playerDisplayInfo.setLightconeCount(this.getInventory().getTab(InventoryTabType.EQUIPMENT).getSize());
        }

        return playerDisplayInfo;
    }

    public PlayerDetailInfo toDetailInfo() {
        var proto = PlayerDetailInfo.newInstance()
                .setUid(this.getUid())
                .setNickname(this.getName())
                .setSignature(this.getSignature())
                .setLevel(this.getLevel())
                .setWorldLevel(this.getWorldLevel())
                .setPlatformType(PlatformType.PC)
                .setHeadIcon(this.getHeadIcon())
                .setShowDisplayAvatars(false)
                .setHeadIcon(this.getHeadIcon());

        proto.getMutableRecordInfo().getMutableCollectionInfo();
        proto.getMutableDisplaySettings();
        proto.getMutableHeadIconFrameInfo();
        var outfitData = proto.getMutablePlayerOutfitData();
        this.getGameplay().getEquippedOutfits().forEach(outfitData::addOutfitIdList);
        proto.getMutableChallengeDetail()
            .getMutableUnkInfo()
            .getMutableUnkInfo()
            .getMutableUnkInfo();

        proto.setUnkDetail("");

        for (int i = 0; i < this.getAssistAvatars().size(); i++) {
            ObjectId objectId = this.getAssistAvatars().get(i);

            GameAvatar avatar = this.getAvatarById(objectId);
            if (avatar == null && this.getSession() == null) {
                avatar = this.getAvatars().loadAvatarByObjectId(objectId);
            }

            if (avatar == null) continue;

            var info = avatar.toDisplayAvatarProto();
            info.setPos(i);

            proto.addAssistAvatarList(info);
        }

        for (int i = 0; i < this.getDisplayAvatars().size(); i++) {
            ObjectId objectId = this.getDisplayAvatars().get(i);
            if (objectId == null) continue;

            GameAvatar avatar = this.getAvatarById(objectId);
            if (avatar == null && this.getSession() == null) {
                avatar = this.getAvatars().loadAvatarByObjectId(objectId);
            }

            if (avatar == null) continue;

            var info = avatar.toDisplayAvatarProto();
            info.setPos(i);

            proto.addDisplayAvatarList(info);
        }

        return proto;
    }

    public PlayerSimpleInfo toSimpleInfo() {
        var proto = PlayerSimpleInfo.newInstance()
                .setUid(this.getUid())
                .setNickname(this.getName())
                .setSignature(this.getSignature())
                .setLevel(this.getLevel())
                .setChatBubble(this.getChatBubble())
                .setOnlineStatus(this.isOnline() ? FriendOnlineStatus.FRIEND_ONLINE_STATUS_ONLINE : FriendOnlineStatus.FRIEND_ONLINE_STATUS_OFFLINE)
                .setPlatform(PlatformType.PC)
                .setLogoutTime(this.getLastActiveTime())
                .setHeadIcon(this.getHeadIcon());

        for (int i = 0; i < this.getAssistAvatars().size(); i++) {
            ObjectId objectId = this.getAssistAvatars().get(i);

            GameAvatar avatar = this.getAvatarById(objectId);
            if (avatar == null && this.getSession() == null) {
                avatar = this.getAvatars().loadAvatarByObjectId(objectId);
            }

            if (avatar == null) continue;

            var info = avatar.toAssistSimpleProto();
            info.setPos(i);

            proto.addAssistInfoList(info);
        }

        return proto;
    }

    public BoardDataSync toBoardData() {
        var proto = BoardDataSync.newInstance()
                .setSignature(this.getSignature());

        for (int id : this.getUnlocks().getHeadIcons()) {
            proto.addUnlockedHeadIconList(HeadIcon.newInstance().setId(id));
        }

        for (int id : this.getUnlocks().getHeadIconFrames()) {
            proto.addAllUnlockedHeadIconFrameIdList(id);
        }

        proto.getMutableHeadIconFrameInfo()
            .setHeadIconFrameId(this.getHeadIconFrame());

        return proto;
    }
}
