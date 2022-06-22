package me.cjcrafter.armormechanics;

import me.cjcrafter.armormechanics.listeners.ArmorEquipListener;
import me.cjcrafter.armormechanics.listeners.ImmunePotionCanceller;
import me.cjcrafter.armormechanics.listeners.WeaponMechanicsDamageListener;
import me.deecaad.core.file.SerializeData;
import me.deecaad.core.file.SerializerException;
import me.deecaad.core.utils.Debugger;
import me.deecaad.core.utils.FileUtil;
import me.deecaad.core.utils.ReflectionUtil;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;

public class ArmorMechanics extends JavaPlugin {

    public static ArmorMechanics INSTANCE;

    private Debugger debug;

    public final Map<String, BonusEffect> effects = new HashMap<>();
    public final Map<String, ItemStack> armors = new HashMap<>();
    public final Map<String, ArmorSet> sets = new HashMap<>();


    @Override
    public void onLoad() {
        INSTANCE = this;

        int level = getConfig().getInt("Debug_Level", 2);
        boolean printTraces = getConfig().getBoolean("Print_Traces", false);
        debug = new Debugger(getLogger(), level, printTraces);

        if (ReflectionUtil.getMCVersion() < 13) {
            debug.error("  !!!!! ERROR !!!!!", "  !!!!! ERROR !!!!!", "  !!!!! ERROR !!!!!", "  Plugin only supports Minecraft 1.13 and higher");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Write config from jar to datafolder
        if (!getDataFolder().exists() || getDataFolder().listFiles() == null || getDataFolder().listFiles().length == 0) {
            debug.info("Copying files from jar (This process may take up to 30 seconds during the first load!)");
            try {
                FileUtil.copyResourcesTo(getClassLoader().getResource("ArmorMechanics"), getDataFolder().toPath());
            } catch (IOException | URISyntaxException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void onEnable() {

        // Serialize armor types
        File armorFile = new File(getDataFolder(), "Armor.yml");
        FileConfiguration armorConfig = YamlConfiguration.loadConfiguration(armorFile);

        for (String key : armorConfig.getKeys(false)) {
            ArmorSerializer serializer = new ArmorSerializer();
            SerializeData data = new SerializeData(serializer, armorFile, key, armorConfig);

            try {
                serializer.serialize(data);
            } catch (SerializerException e) {
                e.log(debug);
            }
        }

        File setFile = new File(getDataFolder(), "Set.yml");
        FileConfiguration setConfig = YamlConfiguration.loadConfiguration(setFile);

        for (String key : setConfig.getKeys(false)) {
            ArmorSet serializer = new ArmorSet();
            SerializeData data = new SerializeData(serializer, setFile, key, setConfig);

            try {
                serializer.serialize(data);
            } catch (SerializerException e) {
                e.log(debug);
            }
        }

        PluginManager pm = getServer().getPluginManager();
        boolean wm = pm.isPluginEnabled("WeaponMechanics");
        pm.registerEvents(new ArmorEquipListener(), this);
        pm.registerEvents(new ImmunePotionCanceller(), this);
        if (wm) pm.registerEvents(new WeaponMechanicsDamageListener(), this);

        Command.register();
    }

    @Override
    public void onDisable() {

    }
}
