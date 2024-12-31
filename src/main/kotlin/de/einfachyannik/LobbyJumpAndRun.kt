package de.einfachyannik

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.event.player.UseItemCallback
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.block.Block
import net.minecraft.block.Blocks
import net.minecraft.component.DataComponentTypes
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import net.minecraft.util.ActionResult
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import net.minecraft.world.World
import org.slf4j.LoggerFactory
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

object LobbyJumpAndRun : ModInitializer {
	private val logger = LoggerFactory.getLogger("lobbyjumpandruntest")
	private val playerGameStates = ConcurrentHashMap<UUID, PlayerGameState>()
	private val playerHighScores = ConcurrentHashMap<UUID, Int>()
	private val highScoreFile = File("highscores.json")
	private val gson = Gson()

	private val glassBlocks = listOf(
		Blocks.WHITE_STAINED_GLASS,
		Blocks.LIGHT_GRAY_STAINED_GLASS,
		Blocks.GRAY_STAINED_GLASS,
		Blocks.BLACK_STAINED_GLASS,
		Blocks.BROWN_STAINED_GLASS,
		Blocks.RED_STAINED_GLASS,
		Blocks.ORANGE_STAINED_GLASS,
		Blocks.YELLOW_STAINED_GLASS,
		Blocks.LIME_STAINED_GLASS,
		Blocks.GREEN_STAINED_GLASS,
		Blocks.CYAN_STAINED_GLASS,
		Blocks.LIGHT_BLUE_STAINED_GLASS,
		Blocks.BLUE_STAINED_GLASS,
		Blocks.PURPLE_STAINED_GLASS,
		Blocks.MAGENTA_STAINED_GLASS,
		Blocks.PINK_STAINED_GLASS
	)

	override fun onInitialize() {
		loadHighScores()

		UseItemCallback.EVENT.register(UseItemCallback { player, world, hand ->
			val stack = player.getStackInHand(hand)
			if (stack.item == Items.GHAST_TEAR && stack.name.string == "§a§lJump And Run" && !world.isClient && player is ServerPlayerEntity) {
				val serverPlayer = player as ServerPlayerEntity
				val playerState = PlayerGameState()
				playerGameStates[serverPlayer.uuid] = playerState

				val spawnPos = findSuitableSpawnLocation(world)
				serverPlayer.requestTeleport(spawnPos.x + 0.5, spawnPos.y.toDouble() + 1.0, spawnPos.z + 0.5)

				playerState.oldBlockPos = spawnPos
				world.setBlockState(playerState.oldBlockPos, playerState.currentGlassBlock.defaultState)
				playerState.previousPositions.add(playerState.oldBlockPos!!)

				val playerHighScore = playerHighScores.getOrDefault(serverPlayer.uuid, 0)
				val actionbarText = Text.literal("§aStart! Your Highscore: §l§n$playerHighScore")
				serverPlayer.sendMessage(actionbarText, true)

				playerState.newBlockPos = spawnNextBlock(world, playerState.oldBlockPos!!, playerState)

				ActionResult.SUCCESS
			} else {
				ActionResult.PASS
			}
		})

		ServerTickEvents.END_SERVER_TICK.register(ServerTickEvents.EndTick { server ->
			val playersToRemove = mutableListOf<UUID>()

			playerGameStates.forEach { (uuid, state) ->
				val player = server.playerManager.getPlayer(uuid)
				if (player != null) {
					val world = player.world
					val playerStandingPos = player.blockPos.down()
					if (playerStandingPos == state.newBlockPos && state.oldBlockPos != null) {
						state.jumpCounter++
						val actionbarText = Text.literal("§6§l+1§r§6! Score: §l§n${state.jumpCounter}")
						player.sendMessage(actionbarText, true)

						world.setBlockState(state.oldBlockPos, Blocks.AIR.defaultState)

						state.oldBlockPos = state.newBlockPos
						state.newBlockPos = spawnNextBlock(world, state.oldBlockPos!!, state)
					} else if (playerStandingPos.y < state.oldBlockPos!!.y - 5) {

						val currentHighScore = playerHighScores.getOrDefault(player.uuid, 0)
						if (state.jumpCounter > currentHighScore) {
							playerHighScores[player.uuid] = state.jumpCounter
							player.sendMessage(Text.literal("§eNew Highscore: §l§n${state.jumpCounter}"), false)
							saveHighScores()
						}
						player.sendMessage(Text.literal("§c§lGame Over! §r§cFinal Score: §l§n${state.jumpCounter}"), false)
						resetGame(player, world)
						playersToRemove.add(uuid)
					}
				} else {
					playersToRemove.add(uuid)
				}
			}

			playersToRemove.forEach { uuid ->
				cleanupPlayerBlocks(server.getWorld(World.OVERWORLD), playerGameStates[uuid])
				playerGameStates.remove(uuid)
			}
		})

		ServerPlayConnectionEvents.JOIN.register { handler, _, _ ->
			val player = handler.player
			val jumpAndRunItem = ItemStack(Items.GHAST_TEAR)
			jumpAndRunItem.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§a§lJump And Run"))
			player.inventory.insertStack(jumpAndRunItem)
		}

		ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
			val player = handler.player
			cleanupPlayerBlocks(player.world, playerGameStates[player.uuid])
			playerGameStates.remove(player.uuid)
		}

	}

	private fun findSuitableSpawnLocation(world: World): BlockPos {
		val spawnY = 200
		val searchRadius = 50
		var attempts = 0
		val maxAttempts = 100

		while (attempts < maxAttempts) {
			val x = Random.nextInt(-searchRadius, searchRadius + 1)
			val z = Random.nextInt(-searchRadius, searchRadius + 1)
			val pos = BlockPos(x, spawnY, z)

			if (world.getBlockState(pos).isAir && world.getBlockState(pos.down()).isAir) {
				return pos
			}

			attempts++
		}

		return BlockPos(0, spawnY, 0)
	}

	private fun spawnNextBlock(world: World, currentPos: BlockPos, state: PlayerGameState): BlockPos {
		var newPos: BlockPos
		var attempts = 0
		val maxAttempts = 100

		do {
			val direction = getRandomDirection()
			val distance = getRandomDistance()
			val height = getRandomHeight(distance)

			newPos = currentPos.add(
				direction.offsetX * distance,
				height,
				direction.offsetZ * distance
			)

			attempts++
			if (attempts >= maxAttempts) {
				return currentPos
			}
		} while (state.previousPositions.contains(newPos) || !world.getBlockState(newPos).isAir)

		world.setBlockState(newPos, state.currentGlassBlock.defaultState)
		state.previousPositions.add(newPos)
		if (state.previousPositions.size > 10) {
			state.previousPositions.removeAt(0)
		}
		return newPos
	}

	private fun getRandomDirection(): Direction {
		return Direction.values().filter { it.axis.isHorizontal }.random()
	}

	private fun getRandomDistance(): Int {
		return Random.nextInt(1, 5)
	}

	private fun getRandomHeight(distance: Int): Int {
		return if (distance == 4) {
			0
		} else {
			Random.nextInt(0, 2)
		}
	}

	private fun resetGame(player: ServerPlayerEntity, world: World) {
		cleanupPlayerBlocks(world, playerGameStates[player.uuid])
		playerGameStates.remove(player.uuid)
		player.requestTeleport(0.0, 95.0, 0.0)
	}

	private fun cleanupPlayerBlocks(world: World?, state: PlayerGameState?) {
		if (world == null || state == null) return
		state.oldBlockPos?.let { world.setBlockState(it, Blocks.AIR.defaultState) }
		state.newBlockPos?.let { world.setBlockState(it, Blocks.AIR.defaultState) }
		state.previousPositions.forEach { pos ->
			world.setBlockState(pos, Blocks.AIR.defaultState)
		}
	}

	private fun loadHighScores() {
		if (highScoreFile.exists()) {
			val json = highScoreFile.readText()
			val type = object : TypeToken<Map<UUID, Int>>() {}.type
			val loadedScores: Map<UUID, Int> = gson.fromJson(json, type)
			playerHighScores.putAll(loadedScores)
		}
	}

	private fun saveHighScores() {
		val json = gson.toJson(playerHighScores)
		highScoreFile.writeText(json)
	}

	private class PlayerGameState {
		var oldBlockPos: BlockPos? = null
		var newBlockPos: BlockPos? = null
		val previousPositions = mutableListOf<BlockPos>()
		var jumpCounter = 0
		var currentGlassBlock: Block = glassBlocks.random()
	}
}