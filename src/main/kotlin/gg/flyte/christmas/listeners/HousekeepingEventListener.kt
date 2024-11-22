package gg.flyte.christmas.listeners

import com.destroystokyo.paper.event.player.PlayerStopSpectatingEntityEvent
import com.destroystokyo.paper.event.server.PaperServerListPingEvent
import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.event.PacketListener
import com.github.retrooper.packetevents.event.PacketListenerPriority
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.protocol.entity.data.EntityData
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes
import com.github.retrooper.packetevents.protocol.entity.pose.EntityPose
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.protocol.player.InteractionHand
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityAnimation
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityHeadLook
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRotation
import dev.shreyasayyengar.menuapi.menu.MenuItem
import dev.shreyasayyengar.menuapi.menu.StandardMenu
import gg.flyte.christmas.ChristmasEventPlugin
import gg.flyte.christmas.donation.DonateEvent
import gg.flyte.christmas.npc.WorldNPC
import gg.flyte.christmas.util.*
import gg.flyte.christmas.visual.CameraSequence
import gg.flyte.twilight.event.event
import gg.flyte.twilight.extension.RemoteFile
import gg.flyte.twilight.extension.playSound
import gg.flyte.twilight.scheduler.async
import gg.flyte.twilight.scheduler.delay
import gg.flyte.twilight.scheduler.sync
import io.papermc.paper.chat.ChatRenderer
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.Component.text
import net.kyori.adventure.text.format.TextColor
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.entity.Snowball
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityCombustEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.FoodLevelChangeEvent
import org.bukkit.event.inventory.*
import org.bukkit.event.player.*
import org.bukkit.inventory.PlayerInventory
import java.util.*
import kotlin.math.ceil

/**
 * This class is responsible for handling all the housekeeping events that are not directly related to any instance
 * of a game. This includes handling player joins, quits, chat messages, resource pack status, and more.
 */
class HousekeepingEventListener : Listener, PacketListener {
    init {
        PacketEvents.getAPI().eventManager.registerListener(this, PacketListenerPriority.NORMAL)

        event<PaperServerListPingEvent> {
            // TODO finish sponsors
            val footer = text("       ")
                .append("<gradient:#fffdb8:#ffffff>ᴄᴀʀʙᴏɴ.ʜᴏꜱᴛ</gradient>".style())
                .append("<grey> • ".style())
                .append("<gradient:#FF1285:#FA0000>ᴊᴇᴛʙʀ</gradient><gradient:#FA0000:#FF6921>ᴀɪɴs</gradient>".style())
                .append("<grey> • ".style())
                .append("<colour:#4885be>ʙᴜɪʟᴛʙʏʙɪᴛ.ᴄᴏᴍ".style())

            val motd = Component.empty()
                .append("<b><obf><white>        ||||||  ".style())
                .append("<gradient:#F396E1:#FFFFFF>ꜰʟʏᴛ</gradient><gradient:#FFFFFF:#FFFFFF>ᴇ</gradient> ".style())
                .append("<gradient:#51F651:#FAEDCB>ᴄʜʀɪsᴛᴍ</gradient><gradient:#FAEDCB:#D12020>ᴀs ᴇᴠ</gradient><gradient:#D12020:#D12020>ᴇɴᴛ</gradient>".style())
                .append("<b><obf><white>  ||||||".style())
                .append("\n".style())
                .append(footer)

            motd(motd)
            this.maxPlayers = 1
            this.numPlayers = 1
            this.listedPlayers.clear()
            listOf(
                "<st><grey>        <reset> ❆ <bold><light_purple>ꜰʟʏᴛᴇ.ɢɢ <red>ᴄʜʀɪsᴛᴍᴀs <green>ᴇᴠᴇɴᴛ <reset><white>❆ <reset><st><grey>         ".style(),
                "  <bold><light_purple>ꜰʟʏᴛᴇ.ɢɢ <grey>• <yellow>ᴄᴀʀʙᴏɴ.ʜᴏꜱᴛ <grey>• <blue>ʙᴜɪʟᴛʙʏʙɪᴛ.ᴄᴏᴍ".style(),
                "".style(),
                "".style(),
                "            <gold>Join <green><b>now <reset><gold>to play <aqua>x-mas minigames".style(),
                "              <gold>and support <red>[charity name]".style() // TODO fill charity name and centre
            ).forEach {
                this.listedPlayers.add(PaperServerListPingEvent.ListedPlayerInfo(it.toLegacyString().colourise(), UUID.randomUUID()))
            }
        }

        event<AsyncChatEvent> {
            renderer(ChatRenderer.viewerUnaware { player, displayName, message ->
                val finalRender = text()

                if (player.isOp) finalRender.append("<red><b>ѕᴛᴀꜰꜰ ".style())

                finalRender.append(text(player.name, TextColor.color(209, 209, 209)))
                    .append("<grey> » ".style())
                    .append("<white> ${signedMessage().message()}".style())
                    .build()
            })
        }

        event<PlayerLoginEvent> {
            if (!ChristmasEventPlugin.instance.canJoin) {
                kickMessage("<red>You cannot join at the moment! Please wait...".style())
                result = PlayerLoginEvent.Result.KICK_OTHER
            }
        }

        event<PlayerJoinEvent>(priority = EventPriority.LOWEST) {
            fun applyTag(player: Player) {
                player.scoreboard = ChristmasEventPlugin.instance.scoreBoardTab
                ChristmasEventPlugin.instance.scoreBoardTab.getTeam(if (player.isOp) "a. staff" else "b. player")?.addEntry(player.name)
            }
            joinMessage(null)

            player.apply {
                async {
                    try {
                        RemoteFile("https://github.com/shreyasayyengar/flyte-christmas-resource-pack/releases/latest/download/RP.zip").apply {
                            setResourcePack(this.url, this.hash, true)
                        }
                    } catch (_: Exception) {
                        sync { kick("<red>Resource pack FAILED to download. Please try joining again.".style()) }
                    }
                }

                gameMode = GameMode.ADVENTURE
                playSound(Sound.ENTITY_PLAYER_LEVELUP)
                formatInventory()

                eventController().points.putIfAbsent(uniqueId, 0)
                eventController().onPlayerJoin(this)
                eventController().songPlayer?.addPlayer(this)

                ChristmasEventPlugin.instance.worldNPCs.forEach { it.spawnFor(this) }

                applyTag(this)

                showBossBar(eventController().donationBossBar)
            }

            val header = text()
                .append("<colour:#ffa1a1>❆ ".style())
                .append("<colour:#aae687>ᴄʜʀɪsᴛᴍᴀs ᴄʜᴀʀɪᴛʏ ᴇᴠᴇɴᴛ".style())
                .append(" <colour:#ffa1a1>❆".style())
                .append("\n".style())
                .append("<grey>\n(${Bukkit.getOnlinePlayers().size} ᴘʟᴀʏᴇʀꜱ)".style())

            // TODO finish sponsors
            val footer = "<light_purple>\nꜰʟʏᴛᴇ.ɢɢ/ᴅᴏɴᴀᴛᴇ\n\n".style()
                .append(" <gradient:#ff80e8:#ffffff>ꜰʟʏᴛᴇ.ɢɢ</gradient>".style())
                .append("<grey> • ".style())
                .append("<gradient:#fffdb8:#ffffff>ᴄᴀʀʙᴏɴ.ʜᴏꜱᴛ</gradient>".style())
                .append("<grey> • ".style())
                .append("<gradient:#FF1285:#FA0000>ᴊᴇᴛʙʀ</gradient><gradient:#FA0000:#FF6921>ᴀɪɴs</gradient>".style())
                .append("<grey> • ".style())
                .append("<colour:#4885be>ʙᴜɪʟᴛʙʏʙɪᴛ.ᴄᴏᴍ ".style())
                .append("\n".style())
            Bukkit.getOnlinePlayers().forEach { it.sendPlayerListHeaderAndFooter(header, footer) }

            eventController().sidebarManager.update()

            WorldNPC.refreshPodium()
        }

        event<PlayerQuitEvent> {
            quitMessage(null)
            delay(1) { eventController().onPlayerQuit(player) } // getOnlinePlayers does not update until the next tick
        }

        event<PlayerMoveEvent> {
            val worldNPCs = ChristmasEventPlugin.instance.worldNPCs
            val playerLocation = player.location
            val npcLocations = worldNPCs.map { it.npc.location.bukkit() }

            // hide player if near any NPC (they obstruct view)
            if (npcLocations.any { it.distance(playerLocation) < 3 }) player.isVisibleByDefault =
                false else if (!player.isVisibleByDefault) player.isVisibleByDefault = true

            // make NPCs look at player if within range
            worldNPCs.forEach { npc ->
                val npcLocation = npc.npc.location.bukkit()
                if (npcLocation.distance(playerLocation) <= 25) {
                    var location = player.location.apply {

                        // since the NPCs are scaled, the look vector is not exact at eye level; this corrects it
                        when (npc.scale) {
                            1.5 -> add(0.0, -4.0, 0.0)
                            2.0 -> add(0.0, -5.0, 0.0)
                            2.5 -> add(0.0, -6.5, 0.0)
                            else -> add(0.0, 0.0, 0.0)
                        }
                    }

                    val lookVector = npcLocation.apply { setDirection(location.toVector().subtract(toVector())) }
                    WrapperPlayServerEntityHeadLook(npc.npc.id, lookVector.yaw).sendPacket(player)
                    WrapperPlayServerEntityRotation(npc.npc.id, lookVector.yaw, lookVector.pitch, false).sendPacket(player)
                }
            }
        }

        event<PlayerResourcePackStatusEvent> {
            var goodStatus = listOf(
                PlayerResourcePackStatusEvent.Status.ACCEPTED,
                PlayerResourcePackStatusEvent.Status.DOWNLOADED,
                PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED
            )

            if (!goodStatus.contains(status)) player.kick("<red>You <white><u>must<reset> <red>accept the resource pack to play on this server!".style())
        }

        event<PlayerDropItemEvent> { isCancelled = true }

        event<PlayerSwapHandItemsEvent> { isCancelled = true }

        event<PlayerInteractEvent> {
            if (item?.type == Material.RECOVERY_COMPASS) {
                openSpectateMenu(player)
            } // NOTE: if other games use compasses, this method will need adjustment.
        }

        event<PlayerStopSpectatingEntityEvent> {
            // prevents any camera sequence from being escaped
            if (CameraSequence.ACTIVE_CAMERAS.contains(spectatorTarget.uniqueId)) {
                isCancelled = true
                return@event
            }

            val currentGame = eventController().currentGame
            if (currentGame?.spectateEntities?.values?.map { it.uniqueId }?.contains(spectatorTarget.uniqueId) == true) {
                player.teleport(currentGame.gameConfig.spectatorSpawnLocations.random())
                player.gameMode = GameMode.ADVENTURE
            }
        }

        event<FoodLevelChangeEvent> { isCancelled = true }

        event<InventoryOpenEvent> {
            if (inventory.type == InventoryType.BARREL) isCancelled = true
        }

        event<InventoryClickEvent> {
            if (clickedInventory !is PlayerInventory) return@event
            isCancelled = slotType == InventoryType.SlotType.ARMOR

            if (currentItem?.type == Material.RECOVERY_COMPASS) {
                openSpectateMenu(whoClicked as Player) // NOTE: if other games use compasses, this method will need adjustment.
            }
        }

        event<EntityCombustEvent> { if (entity is Player) isCancelled = true }

        event<EntityDamageEvent>(priority = EventPriority.LOWEST) {
            isCancelled = true

            entity as? Player ?: return@event // damaged entity
            (this as? EntityDamageByEntityEvent)?.damager as? Snowball ?: return@event // damager
            damage = Double.MIN_VALUE
            isCancelled = false
        }

        event<CraftItemEvent> { isCancelled = true }

        event<PrepareItemCraftEvent> { inventory.result = null }

        event<DonateEvent> { eventController().handleDonation(this) }
    }

    override fun onPacketReceive(event: PacketReceiveEvent) {
        if (event.packetType != PacketType.Play.Client.INTERACT_ENTITY) return
        WrapperPlayClientInteractEntity(event).apply {
            ChristmasEventPlugin.instance.worldNPCs.find { it.npc.id == entityId }?.let { clickedNPC ->

                if (action == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
                    event.user.sendPacket(
                        WrapperPlayServerEntityAnimation(clickedNPC.npc.id, WrapperPlayServerEntityAnimation.EntityAnimationType.SWING_MAIN_ARM)
                    )
                } else if (action == WrapperPlayClientInteractEntity.InteractAction.INTERACT) { // fired twice
                    if (hand != InteractionHand.MAIN_HAND) return

                    WrapperPlayServerEntityMetadata(
                        clickedNPC.npc.id,
                        listOf(EntityData(6, EntityDataTypes.ENTITY_POSE, EntityPose.CROUCHING))
                    ).also { event.user.sendPacket(it) }

                    delay(3) {
                        WrapperPlayServerEntityMetadata(
                            clickedNPC.npc.id,
                            listOf(EntityData(6, EntityDataTypes.ENTITY_POSE, EntityPose.STANDING))
                        ).also { event.user.sendPacket(it) }
                    }
                }
            }
        }
    }

    private fun openSpectateMenu(player: Player) {
        var options = eventController().currentGame!!.gameConfig.spectatorCameraLocations.size
        var standardMenu = StandardMenu("Spectate Map:", ceil(options.div(9.0)).toInt() * 9)

        for (i in 0 until options) {

            val menuItem = MenuItem(Material.PLAYER_HEAD)
                .setName("Spectate Point $i")
                .setSkullTexture("66f88107041ff1ad84b0a4ae97298bd3d6b59d0402cbc679bd2f77356d454bc4")
                .onClick { whoClicked, itemStack, clickType, inventoryClickEvent ->
                    val requestedCameraEntity = eventController().currentGame!!.spectateEntities[i]
                    whoClicked.gameMode = GameMode.SPECTATOR
                    whoClicked.spectatorTarget = requestedCameraEntity
                    whoClicked.closeInventory()
                }

            standardMenu.setItem(i, menuItem)
        }

        standardMenu.open(false, player)
    }
}
