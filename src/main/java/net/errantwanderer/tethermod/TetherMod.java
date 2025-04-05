package net.errantwanderer.tethermod;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import net.errantwanderer.tethermod.network.TetherConfigPayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.RespawnAnchorBlock;
import net.minecraft.command.argument.BlockPosArgumentType;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class TetherMod implements ModInitializer {
	public static final String MOD_ID = "tethermod";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static double maxDistance = 10.0;
	private static final double TETHER_FORCE = 0.5;
	private static final double MAX_FORCE = 0.5;
	private static final double DAMPING_FACTOR = 0.85;
	private static boolean tetherEnabled = true;
	private final Set<ServerPlayerEntity> pendingTeleport = new HashSet<>();

	@Override
	public void onInitialize() {
		LOGGER.info("Tether Mod Initialized!");

		UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
			if (world.isClient) return ActionResult.PASS;

			BlockPos pos = hitResult.getBlockPos();
			var state = world.getBlockState(pos);

			if (state.getBlock() instanceof RespawnAnchorBlock || state.getBlock().asItem().toString().contains("bed")) {
				float spawnAngle = player.getHeadYaw();
				boolean forced = false; // Can be set true if needed
				setGlobalSpawn((ServerWorld) world, pos, spawnAngle, forced);
			}
			return ActionResult.PASS;
		});

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (tetherEnabled) {
				applyTetherEffect(server.getPlayerManager().getPlayerList());
			}

			// Handle pending teleport (simulate spawn repositioning)
			for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
				if (pendingTeleport.contains(player)) {
					handleTetherRespawnTeleport(player);
				}
			}
			pendingTeleport.clear();
		});

		ServerTickEvents.START_SERVER_TICK.register(server -> {
			for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
				if (player.age < 5 && player.isAlive() && !pendingTeleport.contains(player)) {
					pendingTeleport.add(player);
				}
			}
		});

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (tetherEnabled) {
				applyTetherEffect(server.getPlayerManager().getPlayerList());
			}
		});

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			dispatcher.register(CommandManager.literal("spawnpoint")
					.executes(context -> {
						ServerPlayerEntity player = context.getSource().getPlayer();
						BlockPos pos = player.getBlockPos();
						setGlobalSpawn(player.getServerWorld(), pos, player.getHeadYaw(), false);
						context.getSource().sendFeedback(() -> Text.literal("Global spawn set to " + pos), true);
						return 1;
					})
					.then(CommandManager.argument("target", EntityArgumentType.players())
							.then(CommandManager.argument("pos", BlockPosArgumentType.blockPos())
									.executes(ctx -> {
										BlockPos pos = BlockPosArgumentType.getBlockPos(ctx, "pos");
										ServerPlayerEntity sourcePlayer = ctx.getSource().getPlayer();
										setGlobalSpawn(sourcePlayer.getServerWorld(), pos, sourcePlayer.getHeadYaw(), false);
										ctx.getSource().sendFeedback(() -> Text.literal("Global spawn set to " + pos), true);
										return 1;
									})))
			);
			dispatcher.register(CommandManager.literal("tether")
					.then(CommandManager.literal("toggle")
							.executes(context -> {
								tetherEnabled = !tetherEnabled;
								boolean render = tetherEnabled; // match render with logic

								// Send render toggle packet to all players
								for (ServerPlayerEntity player : context.getSource().getServer().getPlayerManager().getPlayerList()) {
									ServerPlayNetworking.send(player, new TetherConfigPayload("render", 0, render));
								}

								context.getSource().sendFeedback(() -> Text.literal("Tether " + (tetherEnabled ? "enabled" : "disabled")), false);
								return 1;
							}))
					.then(CommandManager.literal("setdistance")
							.then(CommandManager.argument("distance", DoubleArgumentType.doubleArg(1, 100))
									.executes(context -> {
										maxDistance = DoubleArgumentType.getDouble(context, "distance");
										context.getSource().sendFeedback(() -> Text.literal("Tether distance set to " + maxDistance), false);
										return 1;
									})))
					.then(CommandManager.literal("client")
							.then(CommandManager.literal("render")
									.then(CommandManager.literal("on").executes(ctx -> {
										sendBoolConfigPacket(ctx.getSource(), "render", true);
										sendClientMessage(ctx.getSource(), "Tether rendering ENABLED");
										return 1;
									}))
									.then(CommandManager.literal("off").executes(ctx -> {
										sendBoolConfigPacket(ctx.getSource(), "render", false);
										sendClientMessage(ctx.getSource(), "Tether rendering DISABLED");
										return 1;
									})))
							.then(CommandManager.literal("randomColor")
									.then(CommandManager.literal("on").executes(ctx -> {
										sendBoolConfigPacket(ctx.getSource(), "randomColor", true);
										sendClientMessage(ctx.getSource(), "Random color ON");
										return 1;
									}))
									.then(CommandManager.literal("off").executes(ctx -> {
										sendBoolConfigPacket(ctx.getSource(), "randomColor", false);
										sendClientMessage(ctx.getSource(), "Random color OFF");
										return 1;
									})))
							.then(CommandManager.literal("thickness")
									.then(CommandManager.argument("value", DoubleArgumentType.doubleArg(0.05, 1))
											.executes(ctx -> {
												float value = (float) DoubleArgumentType.getDouble(ctx, "value");
												sendFloatConfigPacket(ctx.getSource(), "thickness", value);
												sendClientMessage(ctx.getSource(), "Beam thickness set.");
												return 1;
											})))
							.then(CommandManager.literal("color")
									.then(CommandManager.argument("r", DoubleArgumentType.doubleArg(0, 255))
											.then(CommandManager.argument("g", DoubleArgumentType.doubleArg(0, 255))
													.then(CommandManager.argument("b", DoubleArgumentType.doubleArg(0, 255))
															.then(CommandManager.argument("a", DoubleArgumentType.doubleArg(0, 255))
																	.executes(ctx -> {
																		int r = (int) DoubleArgumentType.getDouble(ctx, "r");
																		int g = (int) DoubleArgumentType.getDouble(ctx, "g");
																		int b = (int) DoubleArgumentType.getDouble(ctx, "b");
																		int a = (int) DoubleArgumentType.getDouble(ctx, "a");
																		sendColorPacket(ctx.getSource(), "color", r, g, b, a);
																		sendClientMessage(ctx.getSource(), "Beam color set.");
																		return 1;
																	})))))))
            );
		});
	}

	private void handleTetherRespawnTeleport(ServerPlayerEntity respawned) {
		List<ServerPlayerEntity> others = respawned.getServer().getPlayerManager().getPlayerList().stream()
				.filter(p -> p != respawned && p.isAlive())
				.toList();

		if (others.isEmpty()) return; // No one to tether to, use global spawn

		ServerPlayerEntity target = others.get(respawned.getRandom().nextInt(others.size()));
		Vec3d basePos = target.getPos();

		double radius = maxDistance * 0.8;
		double angle = Math.random() * 2 * Math.PI;
		double dx = Math.cos(angle) * radius;
		double dz = Math.sin(angle) * radius;

		Vec3d newPos = new Vec3d(basePos.x + dx, basePos.y, basePos.z + dz);
		respawned.requestTeleport(newPos.x, newPos.y, newPos.z);

		// make the player face the tether target
		Vec3d directionToTarget = target.getPos().subtract(newPos);
		float yaw = (float) (Math.toDegrees(Math.atan2(directionToTarget.z, directionToTarget.x)) - 90);
		respawned.setYaw(yaw);
		respawned.setPitch(0);

		LOGGER.info("Player {} respawned near {} at {}", respawned.getName().getString(), target.getName().getString(), newPos);
	}

	private void setGlobalSpawn(World world, BlockPos pos, float angle, boolean forced) {
		if (!(world instanceof ServerWorld serverWorld)) return;

		for (ServerPlayerEntity otherPlayer : serverWorld.getServer().getPlayerManager().getPlayerList()) {
			otherPlayer.setSpawnPoint(serverWorld.getRegistryKey(), pos, angle, forced, false);
		}
		TetherMod.LOGGER.info("Global spawn point set to: " + pos);
	}

	private void applyTetherEffect(List<ServerPlayerEntity> players) {
		List<ServerPlayerEntity> alivePlayers = players.stream()
				.filter(ServerPlayerEntity::isAlive)
				.toList();

		int playerCount = alivePlayers.size();
		if (playerCount < 2) return;

		for (int i = 0; i < playerCount; i++) {
			ServerPlayerEntity player = alivePlayers.get(i);
			ServerPlayerEntity nextPlayer = alivePlayers.get((i + 1) % playerCount);

			Vec3d position = player.getPos();
			Vec3d nextPosition = nextPlayer.getPos();
			double distance = position.distanceTo(nextPosition);

			if (distance > maxDistance) {
				Vec3d direction = nextPosition.subtract(position).normalize();
				double strength = Math.min((distance - maxDistance) * TETHER_FORCE, MAX_FORCE);
				Vec3d force = direction.multiply(strength);

				Vec3d velocity = player.getVelocity().add(force).multiply(DAMPING_FACTOR);
				player.setVelocity(velocity);
				player.velocityModified = true;
			}
		}
	}

	private void sendClientMessage(ServerCommandSource source, String msg) {
		if (source.getEntity() instanceof ServerPlayerEntity player) {
			player.sendMessage(Text.literal(msg), false);
		}
	}

	private void sendBoolConfigPacket(ServerCommandSource source, String key, boolean val) {
		if (source.getEntity() instanceof ServerPlayerEntity player) {
			ServerPlayNetworking.send(player, new TetherConfigPayload(key, 0, val));
		}
	}

	private void sendFloatConfigPacket(ServerCommandSource source, String key, float val) {
		if (source.getEntity() instanceof ServerPlayerEntity player) {
			ServerPlayNetworking.send(player, new TetherConfigPayload(key, 1, val));
		}
	}

	private void sendColorPacket(ServerCommandSource source, String key, int r, int g, int b, int a) {
		if (source.getEntity() instanceof ServerPlayerEntity player) {
			ServerPlayNetworking.send(player, new TetherConfigPayload(key, 2, new int[]{r, g, b, a}));
		}
	}
}