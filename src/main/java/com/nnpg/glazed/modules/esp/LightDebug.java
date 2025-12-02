package com.nnpg.glazed.modules.esp;

import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.ShapeMode;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LightDebug extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgThreading = settings.createGroup("Threading");
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Integer> chunkRadius = sgGeneral.add(new IntSetting.Builder()
        .name("chunk-radius")
        .description("Radius of chunks to scan around the player.")
        .defaultValue(4)
        .min(1)
        .max(12)
        .sliderMax(12)
        .build()
    );

    private final Setting<Integer> minY = sgGeneral.add(new IntSetting.Builder()
        .name("min-y")
        .description("Minimum Y level to scan.")
        .defaultValue(-63)
        .min(-64)
        .max(319)
        .sliderMin(-64)
        .sliderMax(319)
        .build()
    );

    private final Setting<Integer> maxY = sgGeneral.add(new IntSetting.Builder()
        .name("max-y")
        .description("Maximum Y level to scan.")
        .defaultValue(319)
        .min(-64)
        .max(319)
        .sliderMin(-64)
        .sliderMax(319)
        .build()
    );

    private final Setting<Integer> minLight = sgGeneral.add(new IntSetting.Builder()
        .name("min-light-level")
        .description("Minimum block light level to show.")
        .defaultValue(5)
        .min(0)
        .max(15)
        .sliderMax(15)
        .build()
    );

    private final Setting<Boolean> useThreading = sgThreading.add(new BoolSetting.Builder()
        .name("enable-threading")
        .description("Use multi-threading for chunk scanning (less lag).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> threadPoolSize = sgThreading.add(new IntSetting.Builder()
        .name("thread-pool-size")
        .description("Number of threads for scanning")
        .defaultValue(4)
        .min(1)
        .max(8)
        .visible(useThreading::get)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .description("Side color of highlighted blocks")
        .defaultValue(new SettingColor(255, 255, 0, 60))
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("Outline color of highlighted blocks")
        .defaultValue(new SettingColor(255, 255, 0, 200))
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the boxes are rendered")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Set<BlockPos> lightBlocks = ConcurrentHashMap.newKeySet();
    private ExecutorService threadPool;

    public LightDebug() {
        super(GlazedAddon.esp, "LightDebug", "Highlights blocks with sufficient light efficiently.");
    }

    @Override
    public void onActivate() {
        if (useThreading.get()) threadPool = Executors.newFixedThreadPool(threadPoolSize.get());
        lightBlocks.clear();
    }

    @Override
    public void onDeactivate() {
        if (threadPool != null && !threadPool.isShutdown()) {
            threadPool.shutdown();
            threadPool = null;
        }
        lightBlocks.clear();
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.world == null || mc.player == null) return;

        ChunkPos playerChunk = mc.player.getChunkPos();
        int radius = chunkRadius.get();

        for (int cx = playerChunk.x - radius; cx <= playerChunk.x + radius; cx++) {
            for (int cz = playerChunk.z - radius; cz <= playerChunk.z + radius; cz++) {
                final int fx = cx, fz = cz;

                if (useThreading.get() && threadPool != null && !threadPool.isShutdown()) {
                    threadPool.submit(() -> scanChunk(fx, fz));
                } else scanChunk(fx, fz);
            }
        }

        // Render all blocks correctly using Box and ShapeMode
        for (BlockPos pos : new HashSet<>(lightBlocks)) {
            event.renderer.box(new Box(pos), sideColor.get(), lineColor.get(), shapeMode.get(), 0);
        }
    }

    private void scanChunk(int chunkX, int chunkZ) {
        if (mc.world == null) return;

        Chunk chunk = mc.world.getChunk(chunkX, chunkZ);
        if (chunk == null || !chunk.getStatus().isAtLeast(ChunkStatus.FULL)) return;

        ChunkPos cpos = new ChunkPos(chunkX, chunkZ);
        Set<BlockPos> chunkLightBlocks = new HashSet<>();

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = minY.get(); y <= maxY.get(); y++) {
                    BlockPos pos = new BlockPos(chunkX * 16 + x, y, chunkZ * 16 + z);
                    int lightLevel = mc.world.getLightLevel(pos);

                    if (lightLevel >= minLight.get()) chunkLightBlocks.add(pos);
                }
            }
        }

        // Remove outdated blocks in this chunk
        lightBlocks.removeIf(pos -> new ChunkPos(pos).equals(cpos) && !chunkLightBlocks.contains(pos));
        lightBlocks.addAll(chunkLightBlocks);
    }
}
