package gg.flyte.christmas.minigame.games

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.protocol.entity.data.EntityData
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes
import com.github.retrooper.packetevents.protocol.entity.pose.EntityPose
import com.github.retrooper.packetevents.wrapper.PacketWrapper
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityAnimation
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRelativeMove
import dev.shreyasayyengar.menuapi.menu.MenuItem
import gg.flyte.christmas.minigame.engine.EventMiniGame
import gg.flyte.christmas.minigame.engine.GameConfig
import gg.flyte.christmas.minigame.world.MapRegion
import gg.flyte.christmas.minigame.world.MapSinglePoint
import gg.flyte.christmas.npc.WorldNPC
import gg.flyte.christmas.util.SongReference
import gg.flyte.christmas.util.Util
import gg.flyte.christmas.util.colourise
import gg.flyte.christmas.util.eventController
import gg.flyte.christmas.util.formatInventory
import gg.flyte.christmas.util.style
import gg.flyte.christmas.util.title
import gg.flyte.twilight.event.event
import gg.flyte.twilight.extension.hidePlayer
import gg.flyte.twilight.extension.playSound
import gg.flyte.twilight.extension.showPlayer
import gg.flyte.twilight.scheduler.TwilightRunnable
import gg.flyte.twilight.scheduler.delay
import gg.flyte.twilight.scheduler.repeatingTask
import gg.flyte.twilight.time.TimeUnit
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.FireworkEffect
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Firework
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerToggleFlightEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.util.Vector
import kotlin.math.ceil
import kotlin.random.Random

@Suppress("DuplicatedCode") // nature of this game is very similar to other music-controlled games.
class BlockParty() : EventMiniGame(GameConfig.BLOCK_PARTY) {
    private lateinit var overviewTask: TwilightRunnable

    private val colourMap = mapOf(
        Material.WHITE_CONCRETE to NamedTextColor.WHITE,
        Material.ORANGE_CONCRETE to NamedTextColor.GOLD,
        Material.MAGENTA_CONCRETE to NamedTextColor.DARK_PURPLE,
        Material.LIGHT_BLUE_CONCRETE to NamedTextColor.AQUA,
        Material.YELLOW_CONCRETE to NamedTextColor.YELLOW,
        Material.LIME_CONCRETE to NamedTextColor.GREEN,
        Material.PINK_CONCRETE to NamedTextColor.LIGHT_PURPLE,
        Material.GRAY_CONCRETE to NamedTextColor.DARK_GRAY,
        Material.LIGHT_GRAY_CONCRETE to NamedTextColor.GRAY,
        Material.CYAN_CONCRETE to NamedTextColor.DARK_AQUA,
        Material.PURPLE_CONCRETE to NamedTextColor.DARK_PURPLE,
        Material.BLUE_CONCRETE to NamedTextColor.BLUE,
        Material.BROWN_CONCRETE to NamedTextColor.GOLD,
        Material.GREEN_CONCRETE to NamedTextColor.GREEN,
        Material.RED_CONCRETE to NamedTextColor.RED,
        Material.BLACK_CONCRETE to NamedTextColor.BLACK
    )
    private var selectedMaterial: Material = colourMap.keys.random()
    private val groupedSquares = mutableListOf<MapRegion>()
    private val eliminateBelow = 104
    private var roundNumber = 0
    private var harder = false
    private var powerUpLocation: MapSinglePoint? = null
    private var secondsForRound = 10
    private var safeBlocks = mutableListOf<MapSinglePoint>()
    private var bombedSquares = mutableListOf<MapSinglePoint>()
    private var currentBossBar: BossBar? = null
    private var bossBarTask: TwilightRunnable? = null
    private var gameLogicTask: TwilightRunnable? = null
    private var isCountdownActive = false

    override fun startGameOverview() {
        for (x in 600..632 step 3) {
            for (z in 784..816 step 3) {
                val region = MapRegion(MapSinglePoint(x, 110, z), MapSinglePoint(x + 2, 110, z + 2))
                groupedSquares.add(region) // add all 3x3s
            }
        }

        overviewTask = repeatingTask(10) {
            groupedSquares.forEach { region ->
                val material = colourMap.keys.random()
                region.toSingleBlockLocations().forEach { point ->
                    point.block.type = material
                }
            }
        }

        super.startGameOverview()
    }

    override fun preparePlayer(player: Player) {
        player.formatInventory()
        player.gameMode = GameMode.ADVENTURE
        player.teleport(gameConfig.spawnPoints.random().randomLocation())
    }

    override fun startGame() {
        overviewTask.cancel()
        simpleCountdown { newRound() }
    }

    private fun newRound() {
        roundNumber++
        if (secondsForRound > 2) secondsForRound--

        when {
            roundNumber == 12 && !harder -> {
                harder = true
                roundNumber = 8 // hard round needs more time to find safe squares first.

                Util.handlePlayers(
                    eventPlayerAction = {
                        it.title("<game_colour>Hard Mode!".style(), Component.empty())
                        it.sendMessage("<red><b>The floor will now change right before the timer starts... stay quick!".style())
                        it.playSound(Sound.ENTITY_ENDER_DRAGON_GROWL)
                    },
                    optedOutAction = {
                        it.sendMessage("<game_colour>The game is getting harder!".style())
                        it.playSound(Sound.ENTITY_ENDER_DRAGON_GROWL)
                    }
                )
            }
        }

        // let song play for a few rounds
        if (roundNumber % 3 == 0) {
            eventController().startPlaylist(SongReference.ALL_I_WANT_FOR_CHRISTMAS_IS_YOU) // beginning makes it hard to differentiate when it has stopped.
        } else {
            eventController().songPlayer?.isPlaying = true
        }

        newFloor()
        handlePowerUp()

        val delayBeforePrepareRemoveFloor = (6..10).random()
        tasks += delay(delayBeforePrepareRemoveFloor, TimeUnit.SECONDS) {
            if (!isCountdownActive) { // prevent double-prepare due to random condition
                prepareRemoveFloor()
            }
        }

        remainingPlayers().forEach { eventController().addPoints(it.uniqueId, 10) }
    }

    private fun prepareRemoveFloor() {
        isCountdownActive = true

        if (harder) newFloor(false) // hard mode changes floor right before countdown starts

        eventController().songPlayer?.isPlaying = false
        remainingPlayers().forEach { it.playSound(Sound.BLOCK_NOTE_BLOCK_BASEDRUM) }

        // hint players with the material they need to stand on
        val itemStack = MenuItem(ItemStack(selectedMaterial)).itemStack.apply {
            itemMeta = itemMeta.apply { isHideTooltip = true }
        }
        remainingPlayers().forEach {
            for ((index, stack) in it.inventory.storageContents.withIndex()) {
                if (stack == null) it.inventory.setItem(index, itemStack)
            }
        }

        val timerBar: BossBar = BossBar.bossBar(
            "<game_colour><b>Time left: $secondsForRound".style(),
            1.0f,
            BossBar.Color.RED,
            BossBar.Overlay.PROGRESS
        )

        currentBossBar = timerBar

        Util.handlePlayers(
            eventPlayerAction = { it.showBossBar(timerBar) },
            optedOutAction = { it.showBossBar(timerBar) } // all can see ticker
        )

        val totalTicks = secondsForRound * 20
        var remainingTicks = totalTicks

        // game logic timer
        gameLogicTask = repeatingTask(5, 5) {
            if (remainingTicks <= 0) {
                this.cancel()
                gameLogicTask = null

                powerUpLocation?.block?.type = Material.AIR
                powerUpLocation = null

                for (groupSquares in groupedSquares) {
                    for (loc in groupSquares.toSingleBlockLocations()) {
                        if (!(safeBlocks.contains(loc) || bombedSquares.contains(loc))) {
                            loc.block.type = Material.AIR
                        }
                    }
                }

                remainingPlayers().forEach { player ->
                    player.inventory.remove(selectedMaterial)
                    player.inventory.setItemInOffHand(null)
                    player.playSound(Sound.ENTITY_EVOKER_FANGS_ATTACK)
                }

                isCountdownActive = false
                tasks += delay(80) { newRound() }
            } else {
                remainingPlayers().forEach { it.playSound(Sound.BLOCK_NOTE_BLOCK_BASEDRUM) }
            }
        }

        // BossBar ticker
        bossBarTask = repeatingTask(1, 1) {
            if (remainingTicks <= 0) {
                this.cancel()

                Util.handlePlayers(eventPlayerAction = { it.hideBossBar(timerBar) }, optedOutAction = { it.hideBossBar(timerBar) })
                bossBarTask = null
                currentBossBar = null
            } else {
                val progress = remainingTicks.toDouble() / totalTicks
                timerBar.progress(progress.toFloat())

                val secondsRemaining = ceil(remainingTicks / 20.0).toInt()
                timerBar.name("<game_colour><b>Time left: $secondsRemaining".style())
                remainingTicks--
            }
        }

        tasks += bossBarTask
        tasks += gameLogicTask
    }

    override fun eliminate(player: Player, reason: EliminationReason) {
        if (currentBossBar != null) player.hideBossBar(currentBossBar!!)

        Util.handlePlayers(
            eventPlayerAction = {
                it.sendMessage("<red>${player.name} <grey>has been eliminated!".style())
            },
            optedOutAction = {
                it.sendMessage("<red>${player.name} <grey>has been eliminated!".style())
            }
        )

        player.apply {
            if (allowFlight) allowFlight = false // if had double-jump

            if (reason == EliminationReason.ELIMINATED) {
                if (gameMode != GameMode.SPECTATOR) world.strikeLightning(location) // don't strike if in camera sequence

                val itemDisplay = world.spawn(location, ItemDisplay::class.java) {
                    it.setItemStack(ItemStack(Material.AIR))
                    it.teleportDuration = 59 // max (minecraft limitation)
                }

                addPotionEffect(PotionEffect(PotionEffectType.BLINDNESS, 20 * 4, 1, false, false, false))
                playSound(Sound.ENTITY_PLAYER_HURT)

                delay(1) {
                    val randomSpecLocation = gameConfig.spectatorSpawnLocations.random()
                    itemDisplay.teleport(randomSpecLocation)
                    itemDisplay.addPassenger(player)
                    player.hidePlayer()

                    delay(59) {
                        itemDisplay.remove()
                        player.teleport(randomSpecLocation)
                        player.showPlayer()
                    }
                }
            } // animate death
        }

        super.eliminate(player, reason)

        // hard mode starts at round 12 but dials back to round 8 for adjusted time.
        val roundNumber = if (harder) roundNumber + 12 else roundNumber
        when (remainingPlayers().size) {
            0 -> {
                formattedWinners.put(player.uniqueId, roundNumber.toString())
                endGame()
            }

            2 -> formattedWinners.put(player.uniqueId, roundNumber.toString())
            3 -> formattedWinners.put(player.uniqueId, roundNumber.toString())
        }
    }

    override fun endGame() {
        tasks.forEach { it?.cancel() } // this will cancel all game tasks.

//        val winner = remainingPlayers().first()
        val winner = Bukkit.getOnlinePlayers().random() // TODO change back
        eventController().addPoints(winner.uniqueId, 15)

        Util.handlePlayers(
            eventPlayerAction = {
                it.hideBossBar(if (currentBossBar != null) currentBossBar!! else return@handlePlayers)
            },
            optedOutAction = {
                it.hideBossBar(if (currentBossBar != null) currentBossBar!! else return@handlePlayers)
            },
        )
        doWinAnimation(winner)
    }

    private fun newFloor(clearBombs: Boolean = true) {
        if (clearBombs) bombedSquares.clear()
        safeBlocks.clear()

        this.selectedMaterial = colourMap.keys.random()
        val safeSquare1 = groupedSquares.indices.random()
        val safeSquare2 = groupedSquares.indices.random()

        groupedSquares.forEachIndexed { index, groupedSquareRegion ->
            val mat: Material = if (index == safeSquare1 || index == safeSquare2) selectedMaterial else colourMap.keys.random()
            var blockLocations = groupedSquareRegion.toSingleBlockLocations()

            if (mat == selectedMaterial) safeBlocks.addAll(blockLocations)

            blockLocations.forEach { it.block.type = mat }
        }

        bombedSquares.forEach { it.block.type = selectedMaterial } // if colour bombs used, change those squares to safe.

        Util.handlePlayers(
            eventPlayerAction = {
                it.playSound(Sound.BLOCK_BEACON_ACTIVATE)
                for (itemStack in it.inventory.storageContents) {
                    if (itemStack?.type == selectedMaterial) itemStack.type = Material.AIR // ensure no power-up items are removed
                }
            },
            optedOutAction = {
                it.playSound(Sound.BLOCK_BEACON_ACTIVATE)
            }
        )
    }

    private fun handlePowerUp() {
        var reducedFrequency = remainingPlayers().size < 4 && roundNumber % 4 == 0 // 4 remaining -> every 4th round
        var regularPowerUp = remainingPlayers().size > 4 && roundNumber % 2 == 0 // 5+ remaining -> every 2nd round

        if (reducedFrequency || regularPowerUp) {

            val localLocation = groupedSquares.random().randomLocation()
            powerUpLocation = MapSinglePoint(localLocation.blockX, localLocation.blockY + 1.0, localLocation.blockZ)
            powerUpLocation!!.block.type = Material.BEACON
            powerUpLocation!!.world.spawn(powerUpLocation!!, Firework::class.java) {
                it.fireworkMeta = it.fireworkMeta.apply {
                    addEffect(
                        FireworkEffect.builder()
                            .with(FireworkEffect.Type.BALL_LARGE)
                            .withColor(Color.FUCHSIA, Color.PURPLE, Color.MAROON).withFade(Color.FUCHSIA, Color.PURPLE, Color.MAROON).build()
                    )
                }
                it.detonate()
            }


            val notification = "<game_colour><b>>> A mysterious power-up has spawned on the floor! <<".style()
            Util.handlePlayers(
                eventPlayerAction = {
                    it.sendMessage(notification)
                    it.sendMessage("<grey>Find the beacon on the map to unlock it!".style())
                    it.playSound(Sound.BLOCK_NOTE_BLOCK_PLING)
                },
                optedOutAction = {
                    it.sendMessage(notification)
                }
            )
        }
    }

    private fun doWinAnimation(player: Player) {
        newFloor(true) // platform for NPCs to stand on.
        val worldNPCs = mutableListOf<WorldNPC>()
        val animationTasks = mutableListOf<TwilightRunnable>()

        repeat(25) {
            var location = groupedSquares.random().randomLocation()
            location.yaw = (0..360).random().toFloat()
            location.pitch = (-25..0).random().toFloat()
            location.y += 1

            var randomColour: String = listOf("4", "c", "6", "2", "a", "9").random()
            val displayName: String = "§$randomColour${player.name}".colourise()

            val npc = WorldNPC.createFromLive(displayName, player, location).also { worldNPCs.add(it) }

            Bukkit.getOnlinePlayers().forEach { loopedPlayer ->
                npc.spawnFor(loopedPlayer)

                animationTasks += repeatingTask((3..5).random(), (1..3).random()) {
                    val packet: PacketWrapper<*>

                    if (Random.nextBoolean()) {
                        packet = WrapperPlayServerEntityAnimation(
                            npc.npc.id,
                            if (Random.nextBoolean()) WrapperPlayServerEntityAnimation.EntityAnimationType.SWING_MAIN_ARM else WrapperPlayServerEntityAnimation.EntityAnimationType.SWING_OFF_HAND
                        )
                    } else {
                        val pose = if (Random.nextBoolean()) EntityPose.STANDING else EntityPose.CROUCHING
                        packet = WrapperPlayServerEntityMetadata(npc.npc.id, listOf(EntityData(6, EntityDataTypes.ENTITY_POSE, pose)))
                    }

                    if (loopedPlayer != null) PacketEvents.getAPI().playerManager.getUser(loopedPlayer).sendPacket(packet)
                } // NPC Crouching & Swinging

                var jumpIndex = 0;
                animationTasks += repeatingTask(((1..5)).random(), 1) {
                    val yUpdates = listOf(
                        0.2083333333333333333333,
                        0.2083333333333333333333,
                        0.2083333333333333333333,
                        0.2083333333333333333333,
                        0.2083333333333333333333,
                        0.2083333333333333333333,
                        -0.2083333333333333333333,
                        -0.2083333333333333333333,
                        -0.2083333333333333333333,
                        -0.2083333333333333333333,
                        -0.2083333333333333333333,
                        -0.2083333333333333333333,
                    )
                    if (jumpIndex == yUpdates.size) jumpIndex = 0 // jump again!

                    val packet = WrapperPlayServerEntityRelativeMove(npc.npc.id, 0.0, (yUpdates[jumpIndex]), 0.0, true)
                    if (loopedPlayer != null) PacketEvents.getAPI().playerManager.getUser(loopedPlayer).sendPacket(packet)

                    jumpIndex++
                } // NPC Jumping

                delay(15, TimeUnit.SECONDS) {
                    worldNPCs.forEach { it.despawnFor(loopedPlayer) }
                    animationTasks.forEach { it.cancel() }
                    super.endGame()
                }
            }
        }
    }

    override fun handleGameEvents() {
        listeners += event<InventoryClickEvent> { isCancelled = true }

        listeners += event<PlayerMoveEvent> {
            if (player.location.blockY < eliminateBelow) {
                if (eliminatedPlayers.contains(player.uniqueId)) return@event
                eliminate(player, EliminationReason.ELIMINATED)
            }
        }

        listeners += event<PlayerInteractEvent> {
            if (clickedBlock?.type == Material.BEACON) {
                clickedBlock?.type = Material.AIR
                var randomPowerUp = PowerUp.entries.random()

                Util.handlePlayers(
                    eventPlayerAction = {
                        if (it == player) {
                            it.sendMessage("<green><b>You've found a ${randomPowerUp.displayName} power-up!".style())
                        } else {
                            it.sendMessage("<green><b>>> ${player.displayName()} has found a {${randomPowerUp.displayName} power-up! <<")
                        }
                    },
                    optedOutAction = {
                        it.sendMessage("<green><b>>> ${player.displayName()} has found a {${randomPowerUp.displayName} power-up! <<")
                    }
                )

                when (randomPowerUp) {
                    PowerUp.ENDER_PEARL -> player.inventory.setItem(0, ItemStack(Material.ENDER_PEARL, 1))

                    PowerUp.COLOR_BOMB -> {
                        val x = clickedBlock!!.location.blockX
                        val y = clickedBlock!!.location.blockY
                        val z = clickedBlock!!.location.blockZ

                        // First loop: 5x5 randomness
                        for (i in (x - 3) until (x + 3)) {
                            for (k in (z - 3) until (z + 3)) {
                                if (Random.nextBoolean()) {
                                    if (!(i in 600..632 && k in 784..816)) {
                                        continue
                                    } // bomb outside of map

                                    val block = clickedBlock!!.world.getBlockAt(i, y - 1, k)
                                    if (block.type != Material.AIR) {
                                        block.type = selectedMaterial
                                        safeBlocks.add(MapSinglePoint(i, y - 1, k))

                                        bombedSquares.add(MapSinglePoint(i, y - 1, k))
                                    }
                                }
                            }
                        }

                        // Second loop: central area
                        for (i in (x - 1) until (x + 1)) {
                            for (k in (z - 1) until (z + 1)) {
                                if (!(i in 600..632 && k in 784..816)) {
                                    continue
                                } // bomb outside of map

                                val block = clickedBlock!!.world.getBlockAt(i, y - 1, k)
                                if (block.type != Material.AIR) {
                                    block.type = selectedMaterial
                                    safeBlocks.add(MapSinglePoint(i, y - 1, k))

                                    bombedSquares.add(MapSinglePoint(i, y - 1, k))
                                }
                            }
                        }

                        clickedBlock!!.world.playSound(clickedBlock!!.location, Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 1.0f)
                    }

                    PowerUp.JUMP_BOOST -> player.addPotionEffect(PotionEffect(PotionEffectType.JUMP_BOOST, 20 * 8, 3, false, false, false))

                    PowerUp.FISHING_ROD -> player.inventory.setItem(0, ItemStack(Material.FISHING_ROD, 1))

                    PowerUp.SLOWNESS -> player.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, 20 * 10, 2, false, false, false))

                    PowerUp.BLINDNESS -> player.addPotionEffect(PotionEffect(PotionEffectType.BLINDNESS, 20 * 10, 2, false, false, false))

                    PowerUp.RANDOM_TP -> player.teleport(groupedSquares.random().randomLocation().add(0.0, 1.5, 0.0))

                    PowerUp.PUSH_SELF -> player.velocity = player.location.direction.multiply(2).add(Vector(0.0, 1.5, 0.0))

                    PowerUp.PUSH_RANDOM -> {
                        var eventPlayer = player // prevent shadowing
                        remainingPlayers().random().apply {
                            velocity = this.location.direction.multiply(2).add(Vector(0.0, 1.5, 0.0))
                            sendMessage("<game_colour>You've been pushed by a power-up!".style())
                            eventPlayer.sendMessage("<game_colour>You've pushed a random player ($name) with the power-up!".style())
                        }
                    }

                    PowerUp.DOUBLE_JUMP -> player.allowFlight = true
                }
            }
        }

        listeners += event<PlayerToggleFlightEvent> {
            if (!(remainingPlayers().contains(player))) return@event // OP'd players need to be able to fly
            isCancelled = true
            player.allowFlight = false
            player.isFlying = false

            player.velocity = player.location.direction.multiply(0.5).add(Vector(0.0, 1.0, 0.0))
        } // double-jump
    }

    private enum class PowerUp(
        val displayName: String,
    ) {
        ENDER_PEARL("Ender Pearl"),
        COLOR_BOMB("Color Bomb"),
        JUMP_BOOST("Jump Boost"),
        FISHING_ROD("Fishing Rod"),
        SLOWNESS("Slowness"),
        BLINDNESS("Blindness"),
        RANDOM_TP("Random TP"),
        PUSH_SELF("Random Self-Boost"),
        PUSH_RANDOM("Random Player Boost"),
        DOUBLE_JUMP("Double Jump")
    }
}
