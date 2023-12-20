package com.cjcrafter.armormechanics

import com.cjcrafter.armormechanics.commands.Command
import com.cjcrafter.armormechanics.listeners.*
import listeners.ArmorEquipListener
import me.cjcrafter.auto.UpdateChecker
import me.cjcrafter.auto.UpdateInfo
import me.deecaad.core.MechanicsCore
import me.deecaad.core.events.QueueSerializerEvent
import me.deecaad.core.file.BukkitConfig
import me.deecaad.core.file.SerializeData
import me.deecaad.core.file.SerializerException
import me.deecaad.core.file.TaskChain
import me.deecaad.core.lib.adventure.text.serializer.legacy.LegacyComponentSerializer
import me.deecaad.core.utils.Debugger
import me.deecaad.core.utils.FileUtil
import me.deecaad.core.utils.LogLevel
import me.deecaad.core.utils.ReflectionUtil
import me.deecaad.core.utils.StringUtil
import org.bstats.bukkit.Metrics
import org.bstats.charts.SimplePie
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.util.concurrent.Callable

class ArmorMechanics : JavaPlugin() {
    lateinit var debug: Debugger
    private var metrics: Metrics? = null
    private var update: UpdateChecker? = null
    val effects: MutableMap<String, BonusEffect> = HashMap()
    val armors: MutableMap<String, ItemStack> = HashMap()
    val sets: MutableMap<String, ArmorSet> = HashMap()

    override fun onLoad() {
        INSTANCE = this
        val level = getConfig().getInt("Debug_Level", 2)
        val printTraces = getConfig().getBoolean("Print_Traces", false)
        debug = Debugger(logger, level, printTraces)
        if (ReflectionUtil.getMCVersion() < 13) {
            debug.error(
                "  !!!!! ERROR !!!!!",
                "  !!!!! ERROR !!!!!",
                "  !!!!! ERROR !!!!!",
                "  Plugin only supports Minecraft 1.13 and higher"
            )
            server.pluginManager.disablePlugin(this)
            return
        }
    }

    override fun onEnable() {
        reload()
        registerBStats()
        registerUpdateChecker()
        val pm = server.pluginManager
        pm.registerEvents(ArmorEquipListener(), this)
        pm.registerEvents(ArmorUpdateListener(), this)
        pm.registerEvents(BlockPlaceListener(), this)
        pm.registerEvents(DamageMechanicListener(), this)
        pm.registerEvents(DurabilityListener(), this)
        pm.registerEvents(ImmunePotionCanceller(), this)
        pm.registerEvents(PreventRemoveListener(), this)

        if (pm.getPlugin("WeaponMechanics") != null) {
            pm.registerEvents(WeaponMechanicsDamageListener(), this)
        }

        // Try to hook into MythicMobs, an error will be thrown if the user is
        // using any version below v5.0.0
        if (pm.getPlugin("MythicMobs") != null) {
            try {
                pm.registerEvents(MythicMobsListener(), this)
            } catch (e: Throwable) {
                debug.log(LogLevel.ERROR, "Could not hook into MythicMobs", e)
            }
        }
        Command.register()

        // Automatically reload ArmorMechanics if WeaponMechanics reloads.
        object : Listener {
            @EventHandler
            fun onQueue(event: QueueSerializerEvent) {
                if ("WeaponMechanics" == event.sourceName) reload()
            }
        }
    }

    fun reload(): TaskChain {
        return TaskChain(this)
            .thenRunAsync(Runnable {
                // Write config from jar to datafolder
                if (!dataFolder.exists() || (dataFolder.listFiles()?.size ?: 0) == 0) {
                    debug.info("Copying files from jar (This process may take up to 30 seconds during the first load!)")
                    FileUtil.copyResourcesTo(classLoader.getResource("ArmorMechanics"), dataFolder.toPath())
                }
            })
            .thenRunSync(Runnable {
                reloadConfig()
                // Reload durability prefix and format
                DURABILITY_PREFIX = LegacyComponentSerializer.legacySection().serialize(MechanicsCore.getPlugin().message.deserialize(StringUtil.colorAdventure(config.getString("Durability_Prefix"))!!))
                DURABILITY_FORMAT = StringUtil.colorAdventure(config.getString("Durability_Format"))!!

                // Clear old data
                effects.clear()
                armors.clear()
                sets.clear()

                // Serialize armor types
                val armorFile = File(dataFolder, "Armor.yml")
                val armorConfig: FileConfiguration = YamlConfiguration.loadConfiguration(armorFile)
                for (key in armorConfig.getKeys(false)) {
                    val serializer = ArmorSerializer()
                    val data = SerializeData(serializer, armorFile, key, BukkitConfig(armorConfig))
                    try {
                        serializer.serialize(data)
                    } catch (e: SerializerException) {
                        e.log(debug)
                    }
                }
                if (armors.isEmpty()) {
                    debug.error(
                        "Couldn't find any armors from '$armorFile'",
                        "Keys: " + armorConfig.getKeys(false)
                    )
                    return@Runnable
                }
                val setFile = File(dataFolder, "Set.yml")
                val setConfig: FileConfiguration = YamlConfiguration.loadConfiguration(setFile)
                for (key in setConfig.getKeys(false)) {
                    val serializer = ArmorSet()
                    val data = SerializeData(serializer, setFile, key, BukkitConfig(setConfig))
                    try {
                        serializer.serialize(data)
                    } catch (e: SerializerException) {
                        e.log(debug)
                    }
                }
            })
    }

    private fun registerBStats() {
        if (metrics != null) return
        debug.debug("Registering bStats")

        // See https://bstats.org/plugin/bukkit/ArmorMechanics/15777. This is
        // the bStats plugin id used to track information.
        val id = 15777
        metrics = Metrics(this, id)
        metrics!!.addCustomChart(SimplePie("registered_armors", Callable {
            val count = armors.size
            if (count <= 10) {
                return@Callable "0-10"
            } else if (count <= 20) {
                return@Callable "11-20"
            } else if (count <= 30) {
                return@Callable "21-30"
            } else if (count <= 50) {
                return@Callable "31-50"
            } else if (count <= 100) {
                return@Callable "51-100"
            } else {
                return@Callable ">100"
            }
        }))
        metrics!!.addCustomChart(SimplePie("registered_sets", Callable {
            val count = sets.size
            if (count <= 2) {
                return@Callable "0-2"
            } else if (count <= 5) {
                return@Callable "3-5"
            } else if (count <= 10) {
                return@Callable "6-10"
            } else if (count <= 20) {
                return@Callable "11-20"
            } else if (count <= 50) {
                return@Callable "21-50"
            } else {
                return@Callable ">50"
            }
        }))
    }

    private fun registerUpdateChecker() {
        update = UpdateChecker(this, UpdateChecker.github("WeaponMechanics", "ArmorMechanics"))
        val listener: Listener = object : Listener {
            @EventHandler
            fun onJoin(event: PlayerJoinEvent) {
                if (event.player.isOp) {
                    TaskChain(this@ArmorMechanics)
                        .thenRunAsync { callback: Any? -> update!!.hasUpdate() }
                        .thenRunSync { callback: Any? ->
                            val update = callback as UpdateInfo?
                            if (callback != null) event.player.sendMessage(ChatColor.RED.toString() + "ArmorMechanics is out of date! " + update!!.current + " -> " + update.newest)
                            null
                        }
                }
            }
        }
        Bukkit.getPluginManager().registerEvents(listener, this)
    }

    companion object {
        lateinit var INSTANCE: ArmorMechanics
        lateinit var DURABILITY_PREFIX: String
        lateinit var DURABILITY_FORMAT: String
    }
}
