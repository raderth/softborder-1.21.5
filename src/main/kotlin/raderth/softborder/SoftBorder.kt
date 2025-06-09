// Fixed main.kt - Single file version for Minecraft 1.21.5
package raderth.softborder
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.server.world.ServerWorld
import net.minecraft.world.chunk.WorldChunk
import net.minecraft.util.math.ChunkPos
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import net.minecraft.text.Text
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvents
import net.minecraft.particle.ParticleTypes
import net.minecraft.util.Identifier
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionProvider
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import org.slf4j.LoggerFactory
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import net.minecraft.world.World
import net.minecraft.util.WorldSavePath
import kotlin.math.pow
import kotlin.math.sqrt
import net.minecraft.particle.DustParticleEffect



// ===== MAIN MOD CLASS =====
class SoftBorder : ModInitializer {
    companion object {
        const val MOD_ID = "softborder"
        val LOGGER = LoggerFactory.getLogger(MOD_ID)
    }
    override fun onInitialize() {
        LOGGER.info("Initializing Border Zones mod")
       
        // Register commands
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            BorderZoneCommands.register(dispatcher)
        }
       
        // Register tick event for player monitoring
        ServerTickEvents.END_SERVER_TICK.register { server ->
            PlayerBorderTracker.tick(server)
        }
       
        // Initialize zone manager
        ZoneManager.initialize()
    }
}

// ===== EXCLUSION ZONE DATA MODEL =====
@Serializable
data class ExclusionZone(
    val id: String,
    val dimension: String,
    val centerX: Int,
    val centerZ: Int,
    val radius: Int,
    val name: String = "Unnamed Zone"
) {
    fun containsChunk(chunkPos: ChunkPos): Boolean {
        // Convert chunk coordinates to block coordinates for proper comparison
        val chunkBlockX = chunkPos.x * 16
        val chunkBlockZ = chunkPos.z * 16
        
        // Check if any part of the 16x16 chunk overlaps with the zone
        val chunkEndX = chunkBlockX + 15
        val chunkEndZ = chunkBlockZ + 15
        val zoneMinX = centerX - radius
        val zoneMaxX = centerX + radius
        val zoneMinZ = centerZ - radius
        val zoneMaxZ = centerZ + radius
        
        return !(chunkEndX < zoneMinX || chunkBlockX > zoneMaxX || 
                chunkEndZ < zoneMinZ || chunkBlockZ > zoneMaxZ)
    }
   
    fun containsPosition(x: Double, z: Double): Boolean {
        return x >= (centerX - radius) &&
               x <= (centerX + radius) &&
               z >= (centerZ - radius) &&
               z <= (centerZ + radius)
    }
   
    fun isNearBorder(x: Double, z: Double, threshold: Double = 5.0): Boolean {
        val distToEdge = minOf(
            x - (centerX - radius), // Distance to left edge
            (centerX + radius) - x, // Distance to right edge
            z - (centerZ - radius), // Distance to top edge
            (centerZ + radius) - z  // Distance to bottom edge
        )
        return distToEdge <= threshold && distToEdge >= 0
    }
}

// ===== ZONE MANAGER =====
object ZoneManager {
    private val zones = mutableMapOf<String, MutableList<ExclusionZone>>()
    private val configFile = File("config/softborder.json")
    private val json = Json { prettyPrint = true }
   
    fun initialize() {
        loadZones()
    }
   
    fun addZone(zone: ExclusionZone) {
        zones.computeIfAbsent(zone.dimension) { mutableListOf() }.add(zone)
        saveZones()
    }
   
    fun removeZoneByName(dimension: String, zoneName: String): Boolean {
        val dimensionZones = zones[dimension] ?: return false
        val removed = dimensionZones.removeIf { it.name.equals(zoneName, ignoreCase = true) }
        if (removed) saveZones()
        return removed
    }
   
    fun getZones(dimension: String): List<ExclusionZone> {
        return zones[dimension]?.toList() ?: emptyList()
    }
   
    fun getAllZones(): Map<String, List<ExclusionZone>> {
        return zones.mapValues { it.value.toList() }
    }
   
    fun isChunkProtected(dimension: String, chunkPos: ChunkPos): Boolean {
        return zones[dimension]?.any { it.containsChunk(chunkPos) } ?: false
    }
   
    fun getZoneAt(dimension: String, x: Double, z: Double): ExclusionZone? {
        return zones[dimension]?.find { it.containsPosition(x, z) }
    }
   
    fun getZoneByName(dimension: String, zoneName: String): ExclusionZone? {
        return zones[dimension]?.find { it.name.equals(zoneName, ignoreCase = true) }
    }
   
    fun getUnprotectedChunks(dimension: String, allChunks: Set<ChunkPos>): Set<ChunkPos> {
        val dimensionZones = zones[dimension] ?: return allChunks
        return allChunks.filter { chunk ->
            dimensionZones.none { zone -> zone.containsChunk(chunk) }
        }.toSet()
    }
   
    private fun saveZones() {
        try {
            configFile.parentFile?.mkdirs()
            val zonesData = zones.mapValues { it.value.toList() }
            configFile.writeText(json.encodeToString(zonesData))
        } catch (e: Exception) {
            SoftBorder.LOGGER.error("Failed to save zones", e)
        }
    }
   
    private fun loadZones() {
        try {
            if (configFile.exists()) {
                val zonesData: Map<String, List<ExclusionZone>> =
                    json.decodeFromString(configFile.readText())
                zones.clear()
                zonesData.forEach { (dimension, zoneList) ->
                    zones[dimension] = zoneList.toMutableList()
                }
            }
        } catch (e: Exception) {
            SoftBorder.LOGGER.error("Failed to load zones", e)
        }
    }
}

object BorderVisualizer {
    fun showBorderParticles(server: MinecraftServer) {
        server.playerManager.playerList.forEach { player ->
            val dimension = player.world.registryKey.value.toString()
            val pos = player.pos
            
            // Find nearby zones
            val nearbyZones = ZoneManager.getZones(dimension).filter { zone ->
                val distanceToCenter = kotlin.math.sqrt(
                    (pos.x - zone.centerX).pow(2) + (pos.z - zone.centerZ).pow(2)
                )
                distanceToCenter <= zone.radius + 50 // Show border when within 50 blocks
            }
            
            nearbyZones.forEach { zone ->
                if (zone.isNearBorder(pos.x, pos.z, 30.0)) {
                    createParticleWall(player, zone)
                }
            }
            
            // Show red particles for players outside ALL zones (in danger)
            if (nearbyZones.isEmpty() || nearbyZones.none { it.containsPosition(pos.x, pos.z) }) {
                // Find the closest zone to show danger particles
                val closestZone = ZoneManager.getZones(dimension).minByOrNull { zone ->
                    val distanceToCenter = kotlin.math.sqrt(
                        (pos.x - zone.centerX).pow(2) + (pos.z - zone.centerZ).pow(2)
                    )
                    distanceToCenter
                }
                
                closestZone?.let { zone ->
                    val distanceToZone = kotlin.math.sqrt(
                        (pos.x - zone.centerX).pow(2) + (pos.z - zone.centerZ).pow(2)
                    )
                    // Only show if reasonably close to a zone (within 100 blocks)
                    if (distanceToZone <= zone.radius + 100) {
                        createParticleWall(player, zone)
                    }
                }
            }
        }
    }
    
    private fun createParticleWall(player: ServerPlayerEntity, zone: ExclusionZone) {
        val world = player.serverWorld
        val playerPos = player.pos
        val viewDistance = 32 // blocks
        
        // Calculate border points visible to player
        val borderPoints = getBorderPointsNearPlayer(zone, playerPos, viewDistance)
        
        borderPoints.forEach { point ->
            // Use dust particles for both safe and danger zones
            if (zone.containsPosition(playerPos.x, playerPos.z)) {
                // Green dust particles - you're in the safe zone
                createDustPillar(world, point, 0.0f, 1.0f, 0.0f) // RGB: Green
            } else {
                // Red dust particles - you're outside safe zone
                createDustPillar(world, point, 1.0f, 0.0f, 0.0f) // RGB: Red
            }
        }
    }
    
    private fun createDustPillar(world: ServerWorld, point: Vec3d, r: Float, g: Float, b: Float) {
        // Create a pillar of dust particles with no breaks
        val pillarHeight = 8.0 
        val particleSpacing = 0.5 // Spacing between particles in the pillar (smaller = more dense)
        
        // Create the dust particle effect - color as RGB int and scale
        val color = ((r * 255).toInt() shl 16) or ((g * 255).toInt() shl 8) or (b * 255).toInt()
        val dustEffect = DustParticleEffect(color, 1.0f)
        
        var currentHeight = 0.0
        while (currentHeight <= pillarHeight) {
            world.spawnParticles(
                dustEffect,
                point.x,
                point.y + currentHeight, // Start 1 block above ground
                point.z,
                1, // One particle per height level
                0.0, 0.0, 0.0, // No spread
                0.0 // Speed
            )
            currentHeight += particleSpacing
        }
    }
    
    private fun getBorderPointsNearPlayer(zone: ExclusionZone, playerPos: Vec3d, viewDistance: Int): List<Vec3d> {
        val points = mutableListOf<Vec3d>()
        val spacing = 3.0 // blocks between particles (adjust for performance)
        
        // Get the 4 border lines of the square zone
        val minX = zone.centerX - zone.radius
        val maxX = zone.centerX + zone.radius
        val minZ = zone.centerZ - zone.radius
        val maxZ = zone.centerZ + zone.radius
        
        // Top edge (north)
        var x = minX.toDouble()
        while (x <= maxX) {
            val z = minZ.toDouble()
            if (kotlin.math.abs(x - playerPos.x) <= viewDistance && 
                kotlin.math.abs(z - playerPos.z) <= viewDistance) {
                points.add(Vec3d(x, playerPos.y, z))
            }
            x += spacing
        }
        
        // Bottom edge (south)
        x = minX.toDouble()
        while (x <= maxX) {
            val z = maxZ.toDouble()
            if (kotlin.math.abs(x - playerPos.x) <= viewDistance && 
                kotlin.math.abs(z - playerPos.z) <= viewDistance) {
                points.add(Vec3d(x, playerPos.y, z))
            }
            x += spacing
        }
        
        // Left edge (west)
        var z = minZ.toDouble()
        while (z <= maxZ) {
            val x = minX.toDouble()
            if (kotlin.math.abs(x - playerPos.x) <= viewDistance && 
                kotlin.math.abs(z - playerPos.z) <= viewDistance) {
                points.add(Vec3d(x, playerPos.y, z))
            }
            z += spacing
        }
        
        // Right edge (east)
        z = minZ.toDouble()
        while (z <= maxZ) {
            val x = maxX.toDouble()
            if (kotlin.math.abs(x - playerPos.x) <= viewDistance && 
                kotlin.math.abs(z - playerPos.z) <= viewDistance) {
                points.add(Vec3d(x, playerPos.y, z))
            }
            z += spacing
        }
        
        return points
    }
}

// ===== PLAYER BORDER TRACKER =====
object PlayerBorderTracker {
    private val playerLastZone = mutableMapOf<UUID, String?>()
   
    fun tick(server: MinecraftServer) {
        server.playerManager.playerList.forEach { player ->
            checkPlayerBorder(player)
        }
        

        BorderVisualizer.showBorderParticles(server)
    }
   
    private fun checkPlayerBorder(player: ServerPlayerEntity) {
        val dimension = player.world.registryKey.value.toString()
        val pos = player.pos
        val currentZone = ZoneManager.getZoneAt(dimension, pos.x, pos.z)
        val lastZone = playerLastZone[player.uuid]
       
        // Check if player entered a new zone or left a zone
        if (currentZone?.id != lastZone) {
            playerLastZone[player.uuid] = currentZone?.id
           
            if (currentZone != null && lastZone == null) {
                // Player entered a protected zone from outside
                onPlayerEnterZone(player, currentZone.name)
            } else if (currentZone == null && lastZone != null) {
                // Player left a protected zone
                onPlayerLeaveZone(player)
            }
        }
    }
   
    private fun onPlayerEnterZone(player: ServerPlayerEntity, zoneName: String) {
        // Send action bar message when entering safe zone
        player.sendMessage(
            Text.literal("§aYou have returned to a safe zone"),
            true // Action bar
        )
        
        // Play sounds when entering
        playSoundToPlayer(player, SoundEvents.BLOCK_SLIME_BLOCK_BREAK, 0.2f, 2.0f)
        playSoundToPlayer(player, SoundEvents.ENTITY_WARDEN_SONIC_BOOM, 0.1f, 2.0f)
        playSoundToPlayer(player, SoundEvents.BLOCK_CONDUIT_ACTIVATE, 0.5f, 2.0f)
        
        // Spawn particles
        spawnParticles(player)
    }
   
    private fun onPlayerLeaveZone(player: ServerPlayerEntity) {
        // Send warning message when leaving protected zone
        player.sendMessage(
            Text.literal("§cYou have entered an area which might be wiped, be aware any builds may be lost"),
            false
        )
        
        // Play sounds when leaving (conduit deactivate instead of activate)
        playSoundToPlayer(player, SoundEvents.BLOCK_SLIME_BLOCK_BREAK, 0.2f, 2.0f)
        playSoundToPlayer(player, SoundEvents.ENTITY_WARDEN_SONIC_BOOM, 0.1f, 2.0f)
        playSoundToPlayer(player, SoundEvents.BLOCK_CONDUIT_DEACTIVATE, 0.5f, 2.0f)
        
        // Spawn particles
        spawnParticles(player)
    }
   
    private fun playSoundToPlayer(player: ServerPlayerEntity, sound: net.minecraft.sound.SoundEvent, volume: Float, pitch: Float) {
        // Use the world's method to play sound to specific player
        player.world.playSound(
            null, // Don't exclude any player
            player.blockPos,
            sound,
            SoundCategory.VOICE,
            volume,
            pitch
        )
    }
   
    private fun spawnParticles(player: ServerPlayerEntity) {
        val pos = player.pos.add(0.0, 1.0, 0.0)
        // Use the server world to spawn witch particles around the player
        val world = player.serverWorld
        world.spawnParticles(
            ParticleTypes.WITCH,
            pos.x,
            pos.y,
            pos.z,
            50,
            1.0,
            1.0,
            1.0,
            0.02
        )
    }
}

// ===== BORDER ZONE COMMANDS =====
object BorderZoneCommands {

    // Suggestion provider for dimensions (remains the same)
    private val DIMENSION_SUGGESTIONS = SuggestionProvider<ServerCommandSource> { context, builder ->
        val server = context.source.server
        server.worlds.forEach { world ->
            val dimensionName = world.registryKey.value.toString()
            val shortName = dimensionName.substringAfterLast(":") // e.g., "overworld", "the_nether"
            builder.suggest(shortName)
        }
        builder.buildFuture()
    }

    // Suggestion provider for zone names (remains the same)
    private val ZONE_NAME_SUGGESTIONS = SuggestionProvider<ServerCommandSource> { context, builder ->
        val dimension = context.source.world.registryKey.value.toString()
        ZoneManager.getZones(dimension).forEach { zone ->
            builder.suggest(zone.name)
        }
        builder.buildFuture()
    }

    fun register(dispatcher: CommandDispatcher<ServerCommandSource>) {
        dispatcher.register(
            net.minecraft.server.command.CommandManager.literal("softborder")
                .requires { it.hasPermissionLevel(3) }
                .then(
                    net.minecraft.server.command.CommandManager.literal("create")
                        .then(
                            net.minecraft.server.command.CommandManager.argument("name", StringArgumentType.string())
                                .then(
                                    net.minecraft.server.command.CommandManager.argument("radius", IntegerArgumentType.integer(1, 10000))
                                        .executes { createZone(it) }
                                )
                        )
                )
                .then(
                    net.minecraft.server.command.CommandManager.literal("remove")
                        .then(
                            net.minecraft.server.command.CommandManager.argument("zoneName", StringArgumentType.string())
                                .suggests(ZONE_NAME_SUGGESTIONS)
                                .executes { removeZone(it) }
                        )
                )
                .then(
                    net.minecraft.server.command.CommandManager.literal("list")
                        .executes { listZones(it) }
                )
                .then(
                    net.minecraft.server.command.CommandManager.literal("info")
                        .then(
                            net.minecraft.server.command.CommandManager.argument("zoneName", StringArgumentType.string())
                                .suggests(ZONE_NAME_SUGGESTIONS)
                                .executes { zoneInfo(it) }
                        )
                )
                .then(
                    net.minecraft.server.command.CommandManager.literal("reset")
                        .then(
                            net.minecraft.server.command.CommandManager.argument("dimension", StringArgumentType.string())
                                .suggests(DIMENSION_SUGGESTIONS)
                                .executes { resetDimension(it) }
                        )
                )
        )
    }

    private fun createZone(context: CommandContext<ServerCommandSource>): Int {
        val source = context.source
        val name = StringArgumentType.getString(context, "name")
        val radius = IntegerArgumentType.getInteger(context, "radius")

        val player = source.playerOrThrow
        val pos = player.blockPos
        val dimension = player.world.registryKey.value.toString()

        if (ZoneManager.getZoneByName(dimension, name) != null) {
            source.sendFeedback(
                { Text.literal("§cA zone with the name '${name}' already exists in this dimension") },
                false
            )
            return 0
        }

        val zone = ExclusionZone(
            id = UUID.randomUUID().toString(),
            dimension = dimension,
            centerX = pos.x,
            centerZ = pos.z,
            radius = radius,
            name = name
        )

        ZoneManager.addZone(zone)

        source.sendFeedback(
            { Text.literal("§aCreated exclusion zone '${name}' with radius ${radius} at (${pos.x}, ${pos.z}) in ${dimension.substringAfterLast(":")}") },
            true
        )
        return 1
    }

    private fun removeZone(context: CommandContext<ServerCommandSource>): Int {
        val source = context.source
        val zoneName = StringArgumentType.getString(context, "zoneName")
        val dimension = source.world.registryKey.value.toString()

        val success = ZoneManager.removeZoneByName(dimension, zoneName)

        if (success) {
            source.sendFeedback(
                { Text.literal("§aRemoved exclusion zone: ${zoneName}") },
                true
            )
        } else {
            source.sendFeedback(
                { Text.literal("§cZone with name '${zoneName}' not found in current dimension") },
                false
            )
        }
        return if (success) 1 else 0
    }

    private fun listZones(context: CommandContext<ServerCommandSource>): Int {
        val source = context.source
        val dimensionString = source.world.registryKey.value.toString()
        val zones = ZoneManager.getZones(dimensionString)

        if (zones.isEmpty()) {
            source.sendFeedback(
                { Text.literal("§eNo exclusion zones in current dimension (${dimensionString.substringAfterLast(":")})") },
                false
            )
        } else {
            source.sendFeedback(
                { Text.literal("§aExclusion zones in ${dimensionString.substringAfterLast(":")}:") },
                false
            )
            zones.forEach { zone ->
                source.sendFeedback(
                    { Text.literal("§7- ${zone.name} at (${zone.centerX}, ${zone.centerZ}) radius ${zone.radius}") },
                    false
                )
            }
        }
        return zones.size
    }

    private fun zoneInfo(context: CommandContext<ServerCommandSource>): Int {
        val source = context.source
        val zoneName = StringArgumentType.getString(context, "zoneName")
        val dimension = source.world.registryKey.value.toString()
        val zone = ZoneManager.getZoneByName(dimension, zoneName)

        if (zone == null) {
            source.sendFeedback(
                { Text.literal("§cZone with name '${zoneName}' not found") },
                false
            )
            return 0
        }

        source.sendFeedback({ Text.literal("§aZone Information:") }, false)
        source.sendFeedback({ Text.literal("§7Name: ${zone.name}") }, false)
        source.sendFeedback({ Text.literal("§7Dimension: ${zone.dimension.substringAfterLast(":")}") }, false)
        source.sendFeedback({ Text.literal("§7Center: (${zone.centerX}, ${zone.centerZ})") }, false)
        source.sendFeedback({ Text.literal("§7Radius: ${zone.radius}") }, false)
        return 1
    }

    private fun resetDimension(context: CommandContext<ServerCommandSource>): Int {
        val source = context.source
        val dimensionInput = StringArgumentType.getString(context, "dimension")
        val server = source.server

        val world = server.worlds.find { world ->
            val keyString = world.registryKey.value.toString() // Full ID, e.g., "minecraft:the_nether"
            val keyPath = world.registryKey.value.path     // Path part, e.g., "the_nether"
            keyString.equals(dimensionInput, ignoreCase = true) || keyPath.equals(dimensionInput, ignoreCase = true)
        }

        if (world == null) {
            source.sendFeedback({ Text.literal("§cDimension '${dimensionInput}' not found or ambiguous.") }, false)
            return 0
        }

        val actualDimensionIdString = world.registryKey.value.toString() // Used for ZoneManager consistency
        // val dimensionRegistryKey = world.registryKey // Of type RegistryKey<World>
        val dimensionIdentifier = world.registryKey.value // Of type Identifier

        source.sendFeedback({ Text.literal("§eStarting dimension reset for ${actualDimensionIdString.substringAfterLast(":")}...") }, true)

        val worldBaseDir = server.getSavePath(WorldSavePath.ROOT)

        val finalRegionPath: Path = when (dimensionIdentifier) {
            // Use .value to get the Identifier for comparison
            net.minecraft.world.World.OVERWORLD.value -> worldBaseDir.resolve("region")
            net.minecraft.world.World.NETHER.value -> worldBaseDir.resolve("DIM-1").resolve("region")
            net.minecraft.world.World.END.value -> worldBaseDir.resolve("DIM1").resolve("region")
            else -> worldBaseDir.resolve("dimensions")
                .resolve(dimensionIdentifier.namespace)
                .resolve(dimensionIdentifier.path)
                .resolve("region")
        }
        
        source.sendFeedback({ Text.literal("§eScanning region files in: $finalRegionPath") }, false)

        val regionDirFile = finalRegionPath.toFile()
        if (!regionDirFile.exists() || !regionDirFile.isDirectory) {
            source.sendFeedback({ Text.literal("§cRegion directory not found: $finalRegionPath") }, false)
            SoftBorder.LOGGER.warn("Region directory not found: $finalRegionPath")
            return 0
        }

        val allChunksInDimension = mutableSetOf<ChunkPos>()
        val mcaFiles = regionDirFile.listFiles { _, name -> name.endsWith(".mca") && name.startsWith("r.") }

        if (mcaFiles == null) {
            source.sendFeedback({ Text.literal("§cCould not list region files in $finalRegionPath.") }, false)
            SoftBorder.LOGGER.error("Could not list .mca files in $finalRegionPath")
            return 0
        }
        
        if (mcaFiles.isEmpty()) {
            source.sendFeedback({ Text.literal("§eNo .mca region files found in $finalRegionPath.") }, false)
        } else {
            source.sendFeedback({ Text.literal("§eFound ${mcaFiles.size} region files to scan in ${actualDimensionIdString.substringAfterLast(":")}.") }, false)
        }

        for (mcaFile in mcaFiles) {
            try {
                val parts = mcaFile.name.split("\\.".toRegex())
                if (parts.size < 3 || parts[0] != "r") {
                    SoftBorder.LOGGER.warn("Skipping non-standard region file name: ${mcaFile.name}")
                    continue
                }

                val regionX = parts[1].toInt()
                val regionZ = parts[2].toInt()

                RandomAccessFile(mcaFile, "r").use { raf ->
                    for (localZ in 0 until 32) {
                        for (localX in 0 until 32) {
                            val headerOffset = 4 * (localX + localZ * 32)
                            raf.seek(headerOffset.toLong())
                            val chunkHeaderData = raf.readInt()
                            
                            // Check if chunk exists (has location data)
                            if (chunkHeaderData != 0) {
                                // Verify the sector count is reasonable (prevent corruption issues)
                                val sectorCount = chunkHeaderData and 0xFF
                                if (sectorCount > 0 && sectorCount <= 255) {
                                    val globalChunkX = (regionX * 32) + localX
                                    val globalChunkZ = (regionZ * 32) + localZ
                                    allChunksInDimension.add(ChunkPos(globalChunkX, globalChunkZ))
                                } else {
                                    SoftBorder.LOGGER.debug("Skipping potentially corrupted chunk at local ($localX, $localZ) in ${mcaFile.name}")
                                }
                            }
                        }
                    }
                }
            } catch (e: NumberFormatException) {
                SoftBorder.LOGGER.warn("Could not parse region file name: ${mcaFile.name}", e)
            } catch (e: java.io.IOException) {
                SoftBorder.LOGGER.error("IO Error scanning region file ${mcaFile.name}: ${e.message}", e)
                source.sendFeedback({ Text.literal("§cIO Error scanning ${mcaFile.name}. Check logs.") }, false)
            } catch (e: Exception) {
                SoftBorder.LOGGER.error("Unexpected error scanning region file ${mcaFile.name}: ${e.message}", e)
                source.sendFeedback({ Text.literal("§cError scanning ${mcaFile.name}. Check logs.") }, false)
            }
        }
        
        if (allChunksInDimension.isEmpty() && mcaFiles.isNotEmpty()) {
             source.sendFeedback({ Text.literal("§eNo chunk entries found in region files for ${actualDimensionIdString.substringAfterLast(":")}.") }, false)
        } else if (allChunksInDimension.isNotEmpty()) {
            source.sendFeedback({ Text.literal("§eFound ${allChunksInDimension.size} existing chunk entries in ${actualDimensionIdString.substringAfterLast(":")}.") }, false)
        }

        val chunksToDelete = ZoneManager.getUnprotectedChunks(actualDimensionIdString, allChunksInDimension)
        source.sendFeedback({ Text.literal("§eIdentified ${chunksToDelete.size} unprotected chunks to delete.") }, false)

        if (chunksToDelete.isEmpty()) {
            source.sendFeedback({ Text.literal("§aNo unprotected chunks to delete in ${actualDimensionIdString.substringAfterLast(":")}.") }, true)
            return 1
        }

        evacuatePlayersFromChunks(world, chunksToDelete, source)
        
        source.sendFeedback({ Text.literal("§eAttempting to delete ${chunksToDelete.size} chunks...") }, true)

        return try {
            val successCount = deleteChunksFromRegionFiles(source, chunksToDelete, finalRegionPath)
            
            source.sendFeedback({ Text.literal("§aSuccessfully processed ${successCount} chunk entries for deletion in ${actualDimensionIdString.substringAfterLast(":")}.") }, true)
            if (successCount > 0) {
                 source.sendFeedback({ Text.literal("§eSTRONGLY recommend RESTARTING the server for changes to safely take effect.") }, true)
            }
            successCount
        } catch (e: Exception) {
            SoftBorder.LOGGER.error("Failed to delete chunks for $actualDimensionIdString", e)
            source.sendFeedback({ Text.literal("§cFailed to delete chunks: ${e.message}. Check logs.") }, false)
            0
        }
    }

    private fun evacuatePlayersFromChunks(world: ServerWorld, chunks: Set<ChunkPos>, source: ServerCommandSource) {
        val playersEvacuated = mutableListOf<String>()
        
        world.players.forEach { player ->
            val playerChunk = ChunkPos(player.blockPos)
            if (chunks.contains(playerChunk)) {
                // Give player a moment to see the message
                player.sendMessage(Text.literal("§cYou are being disconnected: your current chunk is being reset."))
                
                // Use server scheduler to delay disconnect slightly
                val server = source.server
                server.execute {
                    try {
                        player.networkHandler.disconnect(Text.literal("Chunk deletion in progress - please rejoin in a moment"))
                        playersEvacuated.add(player.gameProfile.name)
                    } catch (e: Exception) {
                        SoftBorder.LOGGER.warn("Failed to disconnect player ${player.gameProfile.name}: ${e.message}")
                    }
                }
            }
        }
        
        if (playersEvacuated.isNotEmpty()) {
            source.sendFeedback(
                { Text.literal("§eDisconnecting players from deletion zones: ${playersEvacuated.joinToString(", ")}") },
                false
            )
        }
    }

    private fun deleteChunksFromRegionFiles(
        source: ServerCommandSource,
        chunksToDelete: Set<ChunkPos>,
        regionFolderPath: Path
    ): Int {
        source.sendFeedback(
            { Text.literal("§eOperating on region files in: $regionFolderPath") },
            false
        )
        
        var successCount = 0
        val openRegionFiles = mutableMapOf<String, RandomAccessFile>()
        
        try {
            // Group chunks by region file for efficient processing
            val chunksByRegion = chunksToDelete.groupBy { chunkPos ->
                val regionX = chunkPos.x shr 5
                val regionZ = chunkPos.z shr 5
                "r.$regionX.$regionZ.mca"
            }
            
            for ((regionFileName, chunksInRegion) in chunksByRegion) {
                val regionFilePath = regionFolderPath.resolve(regionFileName)
                
                if (!regionFilePath.toFile().exists()) {
                    SoftBorder.LOGGER.debug("Region file $regionFileName does not exist, skipping ${chunksInRegion.size} chunks")
                    continue
                }
                
                val regionFile = RandomAccessFile(regionFilePath.toFile(), "rw")
                openRegionFiles[regionFileName] = regionFile
                
                for (chunkPos in chunksInRegion) {
                    val chunkLocalX = chunkPos.x and 31
                    val chunkLocalZ = chunkPos.z and 31
                    val offsetInHeader = 4 * (chunkLocalX + chunkLocalZ * 32)
                    
                    regionFile.seek(offsetInHeader.toLong())
                    val chunkLocationData = regionFile.readInt()
                    
                    if (chunkLocationData != 0) {
                        // Clear the chunk data sectors
                        val sectorOffset = chunkLocationData ushr 8
                        val sectorCount = chunkLocationData and 0xFF
                        
                        if (sectorCount > 0) {
                            val sectorStart = sectorOffset * 4096L
                            val dataSize = sectorCount * 4096
                            
                            regionFile.seek(sectorStart)
                            val zeroBytes = ByteArray(dataSize)
                            regionFile.write(zeroBytes)
                        }
                        
                        // Clear the header entry (location data)
                        regionFile.seek(offsetInHeader.toLong())
                        regionFile.writeInt(0)
                        
                        // Clear the timestamp entry
                        val timestampOffset = offsetInHeader + 4096
                        regionFile.seek(timestampOffset.toLong())
                        regionFile.writeInt(0)
                        
                        successCount++
                        SoftBorder.LOGGER.debug("Deleted chunk $chunkPos from region file $regionFileName")
                    } else {
                        SoftBorder.LOGGER.debug("Chunk $chunkPos already empty in $regionFileName")
                    }
                }
                
                source.sendFeedback(
                    { Text.literal("§7Processed ${chunksInRegion.size} chunks in $regionFileName") },
                    false
                )
            }
        } catch (e: java.io.IOException) {
            SoftBorder.LOGGER.error("IO Error during chunk deletion", e)
            source.sendFeedback({Text.literal("§cIO Error during chunk deletion: ${e.message}")}, false)
            throw e 
        } catch (e: Exception) {
            SoftBorder.LOGGER.error("Unexpected error during chunk deletion", e)
            source.sendFeedback({Text.literal("§cUnexpected error during chunk deletion: ${e.message}")}, false)
            throw e
        } finally {
            openRegionFiles.values.forEach { raf ->
                try {
                    raf.channel.force(true) // Force write to disk
                    raf.close()
                } catch (ex: Exception) {
                    SoftBorder.LOGGER.warn("Failed to close region file cleanly: ${ex.message}", ex)
                }
            }
        }
        return successCount
    }
}