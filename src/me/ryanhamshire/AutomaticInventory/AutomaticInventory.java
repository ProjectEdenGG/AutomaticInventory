//Copyright 2015 Ryan Hamshire

package me.ryanhamshire.AutomaticInventory;

import org.bstats.bukkit.Metrics;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionData;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

public class AutomaticInventory extends JavaPlugin {
	//for convenience, a reference to the instance of this plugin
	public static AutomaticInventory instance;

	//for logging to the console and log file
	private static Logger log;

	Set<Material> config_noAutoRefill = new HashSet<>();
	Set<Material> config_noAutoDeposit = new HashSet<>();
	public static Map<Material, AutoCraftResult> autoCraftMaterials = new HashMap<Material, AutoCraftResult>() {{
		put(Material.DIAMOND, new AutoCraftResult(Material.DIAMOND_BLOCK, 9));
		put(Material.EMERALD, new AutoCraftResult(Material.EMERALD_BLOCK, 9));
		put(Material.GOLD_INGOT, new AutoCraftResult(Material.GOLD_BLOCK, 9));
		put(Material.IRON_INGOT, new AutoCraftResult(Material.IRON_BLOCK, 9));
		put(Material.REDSTONE, new AutoCraftResult(Material.REDSTONE_BLOCK, 9));
		put(Material.LAPIS_LAZULI, new AutoCraftResult(Material.LAPIS_BLOCK, 9));
		put(Material.COAL, new AutoCraftResult(Material.COAL_BLOCK, 9));
		put(Material.GOLD_NUGGET, new AutoCraftResult(Material.GOLD_INGOT, 9));
		put(Material.IRON_NUGGET, new AutoCraftResult(Material.IRON_BLOCK, 9));
		put(Material.QUARTZ, new AutoCraftResult(Material.QUARTZ_BLOCK, 4));
	}};

	static boolean autosortEnabledByDefault = true;

	//this handles data storage, like player and region data
	public DataStore dataStore;

	public synchronized static void AddLogEntry(String entry) {
		log.info(entry);
	}

	public void onEnable() {
		log = getLogger();
		AddLogEntry("AutomaticInventory enabled.");

		instance = this;

		this.dataStore = new DataStore();

		//read configuration settings (note defaults)
		this.getDataFolder().mkdirs();
		File configFile = new File(this.getDataFolder().getPath() + File.separatorChar + "config.yml");
		FileConfiguration config = YamlConfiguration.loadConfiguration(configFile);
		FileConfiguration outConfig = new YamlConfiguration();

		List<String> noAutoRefillIDs_string = config.getStringList("Auto Refill.Excluded Items");
		if (noAutoRefillIDs_string.size() == 0) {
			noAutoRefillIDs_string.add("AIR");
			noAutoRefillIDs_string.add("POTION");
		}

		for (String idString : noAutoRefillIDs_string) {
			Material material = Material.matchMaterial(idString.toUpperCase());
			if (material == null)
				getLogger().warning(idString + " is not a valid material");
			else
				this.config_noAutoRefill.add(material);
		}

		outConfig.set("Auto Refill.Excluded Items", noAutoRefillIDs_string);

		List<String> noAutoDepositIDs_string = config.getStringList("Auto Deposit.Excluded Items");
		if (noAutoDepositIDs_string.size() == 0) {
			noAutoDepositIDs_string.add("AIR");
			noAutoDepositIDs_string.add("ARROW");
			noAutoDepositIDs_string.add("SPECTRAL_ARROW");
			noAutoDepositIDs_string.add("TIPPED_ARROW");
		}

		for (String idString : noAutoDepositIDs_string) {
			Material material = Material.matchMaterial(idString.toUpperCase());
			if (material == null)
				getLogger().warning(idString + " is not a valid material");
			else
				this.config_noAutoDeposit.add(material);
		}

		outConfig.set("Auto Deposit.Excluded Items", noAutoDepositIDs_string);

		autosortEnabledByDefault = config.getBoolean("autosortEnabledByDefault", true);
		outConfig.set("autosortEnabledByDefault", autosortEnabledByDefault);

		try {
			outConfig.save(configFile);
		} catch (IOException e) {
			AddLogEntry("Encountered an issue while writing to the config file.");
			e.printStackTrace();
		}

		//register for events
		PluginManager pluginManager = this.getServer().getPluginManager();

		AIEventHandler aIEventHandler = new AIEventHandler();
		pluginManager.registerEvents(aIEventHandler, this);

		for (Player player : this.getServer().getOnlinePlayers()) {
			PlayerData.Preload(player);
		}

		try {
			new Metrics(this);
		} catch (Throwable ignored) {
		}

	}

	public boolean onCommand(CommandSender sender, Command cmd, String commandLabel, String[] args) {
		Player player = null;
		PlayerData playerData = null;
		if (sender instanceof Player) {
			player = (Player) sender;
			playerData = PlayerData.FromPlayer(player);
		}

		if (cmd.getName().equalsIgnoreCase("debugai") && player != null) {
			PlayerInventory inventory = player.getInventory();
			inventory.getItemInMainHand().setDurability(Short.MAX_VALUE);
		    /*
		    for(int i = 0; i < inventory.getSize(); i++)
		    {
		        ItemStack stack = inventory.getItem(i);
		        if(stack != null)
		        AutomaticInventory.AddLogEntry(String.valueOf(i) + " : " + stack.getType().name());
		    }*/

			return true;
		} else if (cmd.getName().equalsIgnoreCase("autosort") && player != null) {
			if (args.length < 1) {
				sendMessage(player, TextMode.Instr, Messages.AutoSortHelp);
				return true;
			}

			String optionName = args[0].toLowerCase();
			if (optionName.startsWith("chest")) {
				if (!hasPermission(Features.SortChests, player)) {
					sendMessage(player, TextMode.Err, Messages.NoPermissionForFeature);
					return true;
				}

				playerData.setSortChests(!playerData.isSortChests());

				if (playerData.isSortChests())
					sendMessage(player, TextMode.Success, Messages.ChestSortEnabled);
				else
					sendMessage(player, TextMode.Success, Messages.ChestSortDisabled);
			} else if (optionName.startsWith("inv")) {
				if (!hasPermission(Features.SortInventory, player)) {
					sendMessage(player, TextMode.Err, Messages.NoPermissionForFeature);
					return true;
				}

				playerData.setSortInventory(!playerData.isSortInventory());

				if (playerData.isSortInventory())
					sendMessage(player, TextMode.Success, Messages.InventorySortEnabled);
				else
					sendMessage(player, TextMode.Success, Messages.InventorySortDisabled);
			} else if (optionName.startsWith("autocraft")) {
				if (!hasPermission(Features.AutoCraft, player)) {
					sendMessage(player, TextMode.Err, Messages.NoPermissionForFeature);
					return true;
				}

				playerData.setAutoCrafting(!playerData.isAutoCrafting());

				if (playerData.isAutoCrafting())
					sendMessage(player, TextMode.Success, Messages.AutoCraftEnabled);
				else
					sendMessage(player, TextMode.Success, Messages.AutoCraftDisabled);
			} else {
				sendMessage(player, TextMode.Err, Messages.AutoSortHelp);
				return true;
			}

			DeliverTutorialHyperlink(player);

			return true;
		} else if (cmd.getName().equalsIgnoreCase("depositall") && player != null) {
			//ensure player has feature enabled
			if (!AIEventHandler.featureEnabled(Features.DepositAll, player)) {
				AutomaticInventory.sendMessage(player, TextMode.Err, Messages.NoPermissionForFeature);
				return true;
			}

			//gather snapshots of adjacent chunks
			Location location = player.getLocation();
			Chunk centerChunk = location.getChunk();
			World world = location.getWorld();
			ChunkSnapshot[][] snapshots = new ChunkSnapshot[3][3];
			for (int x = -1; x <= 1; x++) {
				for (int z = -1; z <= 1; z++) {
					Chunk chunk = world.getChunkAt(centerChunk.getX() + x, centerChunk.getZ() + z);
					snapshots[x + 1][z + 1] = chunk.getChunkSnapshot();
				}
			}

			//create a thread to search those snapshots and create a chain of quick deposit attempts
			int minY = Math.max(0, player.getEyeLocation().getBlockY() - 10);
			int maxY = Math.min(world.getMaxHeight(), player.getEyeLocation().getBlockY() + 10);
			int startY = player.getEyeLocation().getBlockY();
			int startX = player.getEyeLocation().getBlockX();
			int startZ = player.getEyeLocation().getBlockZ();
			Thread thread = new FindChestsThread(world, snapshots, minY, maxY, startX, startY, startZ, player);
			thread.setPriority(Thread.MIN_PRIORITY);
			thread.start();

			playerData.setUsedDepositAll(true);
			return true;
		}

		return false;
	}

	void DeliverTutorialHyperlink(Player player) {
		//todo: deliver tutorial link to player
	}

	public void onDisable() {
		for (Player player : this.getServer().getOnlinePlayers()) {
			PlayerData data = PlayerData.FromPlayer(player);
			data.saveChanges();
			data.waitForSaveComplete();
		}

		AddLogEntry("AutomaticInventory disabled.");
	}

	static boolean hasPermission(Features feature, Player player) {
		boolean hasPermission = false;
		switch (feature) {
			case SortInventory:
				hasPermission = player.hasPermission("automaticinventory.sortinventory");
				break;
			case SortChests:
				hasPermission = player.hasPermission("automaticinventory.sortchests");
				break;
			case RefillStacks:
				hasPermission = player.hasPermission("automaticinventory.refillstacks");
				break;
			case QuickDeposit:
				hasPermission = player.hasPermission("automaticinventory.quickdeposit");
				break;
			case DepositAll:
				hasPermission = player.hasPermission("automaticinventory.depositall");
				break;
			case AutoCraft:
				hasPermission = player.hasPermission("automaticinventory.autocraft");
				break;
		}

		return hasPermission;
	}

	private static void sendMessage(Player player, String message) {
		if (player != null) {
			player.sendMessage(message);
		} else {
			AutomaticInventory.AddLogEntry(message);
		}
	}

	static void sendMessage(Player player, ChatColor color, Messages messageID, String... args) {
		sendMessage(player, color, messageID, 0, args);
	}

	static void sendMessage(Player player, ChatColor color, Messages messageID, long delayInTicks, String... args) {
		String message = AutomaticInventory.instance.dataStore.getMessage(messageID, args);
		sendMessage(player, color, message, delayInTicks);
	}

	static void sendMessage(Player player, ChatColor color, String message) {
		if (message == null || message.length() == 0) return;

		if (player == null) {
			AutomaticInventory.AddLogEntry(color + message);
		} else {
			player.sendMessage(color + message);
		}
	}

	static void sendMessage(Player player, ChatColor color, String message, long delayInTicks) {
		SendPlayerMessageTask task = new SendPlayerMessageTask(player, color, message);
		if (delayInTicks > 0) {
			AutomaticInventory.instance.getServer().getScheduler().runTaskLater(AutomaticInventory.instance, task, delayInTicks);
		} else {
			task.run();
		}
	}

	static DepositRecord depositMatching(PlayerInventory source, Inventory destination, boolean depositHotbar) {
		HashSet<String> eligibleSignatures = new HashSet<String>();
		DepositRecord deposits = new DepositRecord();
		for (int i = 0; i < destination.getSize(); i++) {
			ItemStack destinationStack = destination.getItem(i);
			if (destinationStack == null) continue;

			String signature = getSignature(destinationStack);
			eligibleSignatures.add(signature);
		}
		int sourceStartIndex = depositHotbar ? 0 : 9;
		int sourceSize = Math.min(source.getSize(), 36);
		for (int i = sourceStartIndex; i < sourceSize; i++) {
			ItemStack sourceStack = source.getItem(i);
			if (sourceStack == null) continue;

			if (AutomaticInventory.instance.config_noAutoDeposit.contains(sourceStack.getType())) continue;

			String signature = getSignature(sourceStack);
			int sourceStackSize = sourceStack.getAmount();
			if (eligibleSignatures.contains(signature)) {
				HashMap<Integer, ItemStack> notMoved = destination.addItem(sourceStack);
				if (notMoved.isEmpty()) {
					source.clear(i);
					deposits.totalItems += sourceStackSize;
				} else {
					int notMovedCount = notMoved.values().iterator().next().getAmount();
					int movedCount = sourceStackSize - notMovedCount;
					if (movedCount == 0) {
						eligibleSignatures.remove(signature);
					} else {
						int newAmount = sourceStackSize - movedCount;
						sourceStack.setAmount(newAmount);
						deposits.totalItems += movedCount;
					}
				}
			}
		}

		if (destination.firstEmpty() == -1) {
			deposits.destinationFull = true;
		}

		return deposits;
	}

	private static String getSignature(ItemStack stack) {
		String signature = stack.getType().name();
		if (stack.getMaxStackSize() > 1) {
			signature += "." + String.valueOf(stack.getData().getData());
		}

		//differentiate potion types. Original credit to pugabyte: https://github.com/Pugabyte/AutomaticInventory/commit/01bbdbfa0ea1bc7dc397fc8a8ff625f3f22e1ed6
		//Modified to use PotionType instead of PotionEffectType in signature
		if (stack.getType().toString().toLowerCase().contains("potion")) {
			PotionData potionData = ((PotionMeta) stack.getItemMeta()).getBasePotionData();
			signature += "." + potionData.getType().toString();
			if (potionData.isExtended()) signature += ".extended";
			if (potionData.isUpgraded()) signature += ".upgraded";
		}

		return signature;
	}

	public class FakePlayerInteractEvent extends PlayerInteractEvent {
		public FakePlayerInteractEvent(Player player, Action rightClickBlock, ItemStack itemInHand, Block clickedBlock, BlockFace blockFace) {
			super(player, rightClickBlock, itemInHand, clickedBlock, blockFace);
		}

	}

	/**
	 * Function to check if a chest would open based only on its block above
	 *
	 * @param aboveBlockID the block above the ctest
	 * @return whether or not the chest would not open
	 */
	static boolean preventsChestOpen(Material container, Material aboveBlockID) {
		switch (container) {
			case BARREL:
				return false;
		}

		if (aboveBlockID == null)
			return false;
		return aboveBlockID.isOccluding();
	}

	public static class AutoCraftResult {
		private Material result;
		private int amount;

		public AutoCraftResult(Material result, int amount) {
			this.result = result;
			this.amount = amount;
		}

		public Material getResult() {
			return result;
		}

		public void setResult(Material result) {
			this.result = result;
		}

		public int getAmount() {
			return amount;
		}

		public void setAmount(int amount) {
			this.amount = amount;
		}

	}

}