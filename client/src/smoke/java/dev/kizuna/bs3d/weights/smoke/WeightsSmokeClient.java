package dev.kizuna.bs3d.weights.smoke;

import dev.kizuna.backpack3d.client.VolumeModule;
import dev.kizuna.backpack3d.client.VolumeScreen;
import dev.kizuna.backpack3d.protocol.Volumes;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Phase;
import dev.kizuna.bs3d.weights.client.WeightsClient;
import dev.kizuna.bs3d.weights.protocol.pb.WeightsProtocol.Status;
import dev.kizuna.inventoryui.client.ClientModule;
import dev.kizuna.inventoryui.client.InventoryUiClient;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screen.ConnectScreen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.resource.DataConfiguration;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.WorldPresets;
import net.minecraft.world.level.LevelInfo;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;

/** 实际独立服务器/单机连接、转移、Protobuf 显示、热重载和重开世界验收。 */
public final class WeightsSmokeClient implements ClientModInitializer {
    private static final String WORLD = "weights-smoke";
    private static final String ADDRESS = "127.0.0.1:25583";
    private static final long TIMEOUT_MILLIS = 240_000;
    private static final int SETTLE_TICKS = 15;
    private static final int DISCONNECT_SETTLE_TICKS = SETTLE_TICKS * 2;

    private enum Stage {
        CONNECT,
        INITIAL_STATE,
        TRANSFER_STARTED,
        TRANSFER_CANCELLED,
        TRANSFER_COMMITTED,
        RULES_RELOADED,
        DISCONNECT,
        REOPEN_WORLD,
        WORLD_RESTORED,
        FINISHED
    }

    private long started;
    private Stage stage = Stage.CONNECT;
    private int settle;
    private boolean integrated;
    private String pouch;
    private String oldSession;

    @Override
    public void onInitializeClient() {
        // 夹具按独服或单机运行，生产源码不注册此自动验收入口。
        integrated = System.getProperty("weights.smoke.mode", "dedicated").equals("integrated");
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(MinecraftClient client) {
        // 每个阶段先等真实服务器状态，再执行一个动作；超时给出阶段和路由诊断。
        if (started == 0) {
            started = System.currentTimeMillis();
        }
        if (System.currentTimeMillis() - started > TIMEOUT_MILLIS) {
            throw new IllegalStateException("重量验收超时 stage=" + stage
                    + " protocol=" + InventoryUiClient.runtime().protocolError());
        }
        if (client.currentScreen instanceof AccessibilityOnboardingScreen) {
            client.setScreen(new TitleScreen());
        }
        if (stage == Stage.CONNECT && client.currentScreen instanceof TitleScreen && client.getOverlay() == null) {
            stage = Stage.INITIAL_STATE;
            if (integrated) {
                var info = new LevelInfo(WORLD, GameMode.CREATIVE, false, Difficulty.PEACEFUL, true,
                        new GameRules(), DataConfiguration.SAFE_MODE);
                client.createIntegratedServerLoader().createAndStart(WORLD, info, new GeneratorOptions(1L, false, false),
                        registry -> registry.get(RegistryKeys.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createDimensionsRegistryHolder());
            } else {
                ConnectScreen.connect(new TitleScreen(), client, ServerAddress.parse(ADDRESS),
                        new ServerInfo("Weights smoke", ADDRESS, false), false);
            }
        }
        var modules = FabricLoader.getInstance().getEntrypoints("kizuna_inventory_ui", ClientModule.class);
        var weights = modules.stream()
                .filter(WeightsClient.class::isInstance)
                .map(WeightsClient.class::cast)
                .findFirst()
                .orElseThrow();
        var volume = modules.stream()
                .filter(VolumeModule.class::isInstance)
                .map(VolumeModule.class::cast)
                .findFirst()
                .orElseThrow();
        if (stage == Stage.REOPEN_WORLD && client.player == null && client.getOverlay() == null) {
            require(weights.state().getStatus() == Status.STATUS_UNSPECIFIED, "断线没有清空重量");
            staleHostLevelData(client);
            stage = Stage.WORLD_RESTORED;
            client.createIntegratedServerLoader().start(new TitleScreen(), WORLD);
            return;
        }
        if (client.player == null || !volume.ready() || weights.state().getStatus() != Status.OK) {
            return;
        }
        client.getToastManager().clear();
        switch (stage) {
            case INITIAL_STATE -> {
                // 初始树由服务端夹具创建：三只容器，随身合计 5 kg。
                if (weights.state().getTotalGrams() != 5000) {
                    return;
                }
                require(weights.state().getContainersCount() == 3, "没有递归统计三个容器");
                require(integrated == client.isInSingleplayer(), "运行环境不符合验收要求");
                pouch = volume.state().getContainersList().stream()
                        .filter(container -> container.getWorn())
                        .findFirst()
                        .orElseThrow()
                        .getId();
                volume.open();
                ((VolumeScreen) client.currentScreen).openContainer(pouch);
                volume.deposit(volume.playerSlot(0), pouch, Volumes.cell(0, 0, 0), true, 0);
                stage = Stage.TRANSFER_STARTED;
            }
            case TRANSFER_STARTED -> {
                // 确认转移已开始但尚未改重量，再主动取消。
                if (volume.state().getTransfer().getPhase() != Phase.RUNNING) {
                    return;
                }
                require(weights.state().getTotalGrams() == 5000, "转移中总重提前改变");
                volume.cancel();
                stage = Stage.TRANSFER_CANCELLED;
            }
            case TRANSFER_CANCELLED -> {
                // 取消不能增加库存修订；重新开始同一操作验证最终提交。
                if (volume.busy()) {
                    return;
                }
                require(volume.state().getRevision() == 1, "取消改变了库存修订");
                require(weights.state().getTotalGrams() == 5000, "取消改变了重量");
                volume.deposit(volume.playerSlot(0), pouch, Volumes.cell(0, 0, 0), true, 0);
                stage = Stage.TRANSFER_COMMITTED;
            }
            case TRANSFER_COMMITTED -> {
                // 先等库存提交和重量推送，再请求服务端真实数据包重载。
                if (volume.busy() || volume.state().getRevision() != 2) {
                    return;
                }
                var detail = weights.state().getContainersList().stream()
                        .filter(container -> container.getContainerId().equals(pouch))
                        .findFirst()
                        .orElseThrow();
                if (detail.getTotalGrams() != 2250) {
                    return;
                }
                require(weights.state().getTotalGrams() == 5000, "容器间转移重复计重");
                client.getNetworkHandler().sendChatCommand("weights-fixture-reload");
                stage = Stage.RULES_RELOADED;
            }
            case RULES_RELOADED -> {
                // 重载后等待 UI 稳定再截图，记录旧会话供重开验证。
                if (weights.state().getTotalGrams() != 8000) {
                    return;
                }
                require(weights.summary().getString().contains("8 kg"), "显示没有使用服务端快照");
                require(weights.container(pouch).getString().equals("4.25 kg"), "容器文字重量错误");
                settle++;
                if (settle < SETTLE_TICKS) {
                    return;
                }
                screenshot(client);
                oldSession = weights.state().getSession();
                stage = Stage.DISCONNECT;
            }
            case DISCONNECT -> {
                // 专服完成后退出；单机还需验证保存和同世界重开。
                settle++;
                if (settle < DISCONNECT_SETTLE_TICKS) {
                    return;
                }
                if (integrated) {
                    stage = Stage.REOPEN_WORLD;
                    disconnect(client);
                } else {
                    finish(client, "dedicated: server weights, nested containers, cancel, commit, reload, protobuf, UI passed");
                }
            }
            case WORLD_RESTORED -> {
                // 新会话须读取已提交 playerdata，不能被较旧的 level.dat 回滚。
                require(!oldSession.equals(weights.state().getSession()), "重开世界沿用了旧连接");
                require(volume.state().getRevision() == 2, "较旧 level.dat 回滚了库存事务");
                require(weights.state().getTotalGrams() == 8000, "单机重开重量没有恢复");
                finish(client, "integrated: all dedicated checks plus world restart, stale level.dat recovery, new session passed");
            }
            default -> { }
        }
    }

    private static void staleHostLevelData(MinecraftClient client) {
        // 仅修改本次新建的隔离世界，模拟 level.dat 尚未跟上已提交 playerdata 的崩溃窗口。
        var file = client.runDirectory.toPath().resolve("saves/" + WORLD + "/level.dat");
        try {
            var level = NbtIo.readCompressed(file.toFile());
            level.getCompound("Data").put("Player", new NbtCompound());
            NbtIo.writeCompressed(level, file.toFile());
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static void screenshot(MinecraftClient client) {
        // 抓取真实帧缓冲，产物留在夹具目录供人工查看。
        ScreenshotRecorder.saveScreenshot(client.runDirectory, "weights.png", client.getFramebuffer(), message -> {});
    }

    private void finish(MinecraftClient client, String result) {
        // 通过标记只属于这次隔离运行；随后按原版顺序断线、保存并结束客户端。
        try {
            Files.writeString(client.runDirectory.toPath().resolve("weights-ok.txt"), result);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        stage = Stage.FINISHED;
        disconnect(client);
        client.scheduleStop();
    }

    private static void disconnect(MinecraftClient client) {
        // 先关闭世界连接，内置服务器才会开始停服；客户端随后等待保存完成。
        if (client.world != null) {
            client.world.disconnect();
        }
        client.disconnect(new TitleScreen());
    }

    private static void require(boolean condition, String reason) {
        // 夹具断言失败立即结束当前流程，不留下伪造的通过标记。
        if (!condition) {
            throw new IllegalStateException(reason);
        }
    }
}
