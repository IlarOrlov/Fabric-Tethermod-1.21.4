package net.errantwanderer.tethermod.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.errantwanderer.tethermod.network.TetherConfigPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class TetherModClient implements ClientModInitializer {
    public static int beamRed = 172, beamGreen = 116, beamBlue = 76, beamAlpha = 255;
    public static float beamThickness = 0.05f;
    public static boolean randomColor = false;
    private float currentR = 1.0f, currentG = 1.0f, currentB = 1.0f;
    private float targetR = 1.0f, targetG = 1.0f, targetB = 1.0f;
    private long lastColorChangeTime = 0;
    public static boolean renderTetherEnabled = true; // default to true
    private final long COLOR_CYCLE_INTERVAL_MS = 2000; // change every 5 seconds
    private final float COLOR_LERP_SPEED = 0.01f; // smooth transition

    @Override
    public void onInitializeClient() {
        PayloadTypeRegistry.playS2C().register(
                TetherConfigPayload.PACKET_ID,
                PacketCodec.of(TetherConfigPayload::write, TetherConfigPayload::read)
        );

        PayloadTypeRegistry.playC2S().register(
                TetherConfigPayload.PACKET_ID,
                PacketCodec.of(TetherConfigPayload::write, TetherConfigPayload::read)
        );

        ClientPlayNetworking.registerGlobalReceiver(TetherConfigPayload.PACKET_ID, (payload, context) -> {
            MinecraftClient client = MinecraftClient.getInstance();

            client.execute(() -> {
                String key = payload.key();
                int type = payload.type();
                Object value = payload.value();

                switch (type) {
                    case 0 -> {
                        boolean val = (Boolean) value;
                        if (key.equals("render")) renderTetherEnabled = val;
                        else if (key.equals("randomColor")) randomColor = val;
                    }
                    case 1 -> {
                        float thickness = (Float) value;
                        if (key.equals("thickness")) beamThickness = thickness;
                    }
                    case 2 -> {
                        int[] rgba = (int[]) value;
                        if (key.equals("color")) {
                            beamRed = rgba[0];
                            beamGreen = rgba[1];
                            beamBlue = rgba[2];
                            beamAlpha = rgba[3];
                            randomColor = false;
                        }
                    }
                }
            });
        });

        WorldRenderEvents.AFTER_ENTITIES.register(this::renderTether);
    }

    private void renderTether(WorldRenderContext context) {
        if (!renderTetherEnabled) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null) return;

        List<PlayerEntity> players = client.world.getPlayers().stream()
                .sorted(Comparator.comparing(p -> p.getUuid().toString()))
                .collect(Collectors.toList());

        if (players.size() < 2) return;

        MatrixStack matrices = context.matrixStack();
        matrices.push();

        Camera camera = context.camera();
        Vec3d camPos = camera.getPos();
        float tickDelta = context.tickCounter().getTickDelta(true);

        Matrix4f matrix = matrices.peek().getPositionMatrix();
        VertexConsumerProvider provider = context.consumers();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.lineWidth(beamThickness);

        VertexConsumer buffer = provider.getBuffer(RenderLayer.getLines());

        for (int i = 0; i < players.size(); i++) {
            PlayerEntity p1 = players.get(i);
            PlayerEntity p2 = players.get((i + 1) % players.size()); // next in loop

            Vec3d from = interpolatePlayerPos(p1, tickDelta).add(0, 0.8, 0).subtract(camPos); // belly
            Vec3d to = interpolatePlayerPos(p2, tickDelta).add(0, 0.8, 0).subtract(camPos);   // belly

            drawColorBeam(buffer, matrix, from.add(camPos), to.add(camPos), camera);
        }

        matrices.pop();
        RenderSystem.lineWidth(1.0f);
        RenderSystem.enableDepthTest();
    }

    private void drawThickBeam(VertexConsumer buffer, Matrix4f matrix, Vec3d from, Vec3d to,
                               Camera camera, int r, int g, int b, int a) {
        Vec3d dir = to.subtract(from).normalize();
        Vec3d camVec = camera.getPos();

        // Find two perpendicular vectors for thickness
        Vec3d up = new Vec3d(0, 1, 0);
        if (Math.abs(dir.dotProduct(up)) > 0.99) {
            up = new Vec3d(1, 0, 0); // avoid parallel vectors
        }

        Vec3d side1 = dir.crossProduct(up).normalize().multiply(beamThickness * 0.05);
        Vec3d side2 = dir.crossProduct(side1).normalize().multiply(beamThickness * 0.05);

        Vec3d[] fromCorners = new Vec3d[4];
        Vec3d[] toCorners = new Vec3d[4];

        // Define square cross-sections at both ends
        fromCorners[0] = from.add(side1).add(side2);
        fromCorners[1] = from.add(side1).subtract(side2);
        fromCorners[2] = from.subtract(side1).subtract(side2);
        fromCorners[3] = from.subtract(side1).add(side2);

        toCorners[0] = to.add(side1).add(side2);
        toCorners[1] = to.add(side1).subtract(side2);
        toCorners[2] = to.subtract(side1).subtract(side2);
        toCorners[3] = to.subtract(side1).add(side2);

        // Draw 6 faces (12 triangles total)
        for (int i = 0; i < 4; i++) {
            Vec3d v1 = fromCorners[i].subtract(camVec);
            Vec3d v2 = fromCorners[(i + 1) % 4].subtract(camVec);
            Vec3d v3 = toCorners[(i + 1) % 4].subtract(camVec);
            Vec3d v4 = toCorners[i].subtract(camVec);

            addQuad(buffer, matrix, v1, v2, v3, v4, r, g, b, a); // side face
        }

        // Front face (at 'from')
        addQuad(buffer, matrix,
                fromCorners[0].subtract(camVec),
                fromCorners[1].subtract(camVec),
                fromCorners[2].subtract(camVec),
                fromCorners[3].subtract(camVec), r, g, b, a);

        // Back face (at 'to')
        addQuad(buffer, matrix,
                toCorners[3].subtract(camVec),
                toCorners[2].subtract(camVec),
                toCorners[1].subtract(camVec),
                toCorners[0].subtract(camVec), r, g, b, a);
    }

    private void addQuad(VertexConsumer buffer, Matrix4f matrix, Vec3d v1, Vec3d v2, Vec3d v3, Vec3d v4,
                         int r, int g, int b, int a) {
        addVertex(buffer, matrix, v1, r, g, b, a);
        addVertex(buffer, matrix, v2, r, g, b, a);
        addVertex(buffer, matrix, v3, r, g, b, a);

        addVertex(buffer, matrix, v3, r, g, b, a);
        addVertex(buffer, matrix, v4, r, g, b, a);
        addVertex(buffer, matrix, v1, r, g, b, a);
    }

    private void addVertex(VertexConsumer buffer, Matrix4f matrix, Vec3d pos, int r, int g, int b, int a) {
        buffer.vertex(matrix, (float) pos.x, (float) pos.y, (float) pos.z)
                .color(r, g, b, a)
                .normal(0, 1, 0);
    }

    private void drawColorBeam(VertexConsumer buffer, Matrix4f matrix, Vec3d from, Vec3d to, Camera camera) {
        int r = beamRed, g = beamGreen, b = beamBlue;
        if (randomColor) {
            updateSmoothColor();
            r = (int) (currentR * 255);
            g = (int) (currentG * 255);
            b = (int) (currentB * 255);
        }
        drawThickBeam(buffer, matrix, from, to, camera, r, g, b, beamAlpha);
    }

    private Vec3d interpolatePlayerPos(PlayerEntity player, float tickDelta) {
        double x = MathHelper.lerp(tickDelta, player.lastRenderX, player.getX());
        double y = MathHelper.lerp(tickDelta, player.lastRenderY, player.getY());
        double z = MathHelper.lerp(tickDelta, player.lastRenderZ, player.getZ());
        return new Vec3d(x, y, z);
    }

    private void updateSmoothColor() {
        long currentTime = System.currentTimeMillis();

        // If time to pick new target
        if (currentTime - lastColorChangeTime > COLOR_CYCLE_INTERVAL_MS) {
            targetR = (float) Math.random();
            targetG = (float) Math.random();
            targetB = (float) Math.random();
            lastColorChangeTime = currentTime;
        }

        // Lerp toward target
        currentR += (targetR - currentR) * COLOR_LERP_SPEED;
        currentG += (targetG - currentG) * COLOR_LERP_SPEED;
        currentB += (targetB - currentB) * COLOR_LERP_SPEED;
    }
}