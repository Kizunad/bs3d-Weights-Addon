package dev.kizuna.bs3d.weights.smoke;

import com.mojang.authlib.GameProfile;
import dev.kizuna.backpack3d.item.ContainerItems;
import dev.kizuna.bs3d.weights.protocol.pb.WeightsProtocol.Status;
import dev.kizuna.bs3d.weights.protocol.pb.WeightsProtocol.WeightState;
import dev.kizuna.bs3d.weights.server.Burden;
import dev.kizuna.bs3d.weights.server.BurdenSource;
import dev.kizuna.bs3d.weights.server.CarriedStack;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemContainerContents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 现有 smoke 源集中的无客户端专服检查，成功标记由 Gradle 核对，失败不能仅靠停服退出码判断。 */
final class MeasureSmoke {
    private static final Logger LOGGER = LoggerFactory.getLogger("weights_measure_smoke");
    private static final Identifier BASKET = Identifier.parse("bs3d:gu_jia_bei_lou");
    private static final int FIRST_MAIN_SLOT = 9;
    private static final int WORKER_TIMEOUT_SECONDS = 10;
    private static final long STONE_GRAMS = 1000;
    private static final long REGISTERED_GOLD_GRAMS = 375;
    private static final long DEFAULT_GRAMS = 100;
    private static final int GOLD_COUNT = 2;
    private static final Path MARKER = Path.of("weights-measure-ok.txt");

    private MeasureSmoke() {}

    static void register() {
        // 世界和数据包已加载后再读正式入口，不依赖客户端登录或手动执行命令。
        ServerLifecycleEvents.SERVER_STARTED.register(MeasureSmoke::run);
    }

    private static void run(MinecraftServer server) {
        try {
            var level = server.overworld();
            currentRulesAndImmediateRead(level);
            playerAndRootsAgree(level);
            invalidAndEmptyAreDistinct(level);
            badParametersThrow(level);
            wrongThreadThrows(level);
            writeSuccess();
        } catch (RuntimeException | Error failure) {
            LOGGER.error("WEIGHTS_MEASURE_FAILED", failure);
        } finally {
            // 只停止本次 Gradle 启动的隔离专服；缺少成功标记会让 runSmoke 失败。
            server.halt(false /* waitForServer */);
        }
    }

    private static void currentRulesAndImmediateRead(ServerLevel level) {
        // 注册字段和数据包各选一种物品，确认入口没有另建默认规则或复用旧读数。
        var gold = new ItemStack(Items.GOLD_INGOT, GOLD_COUNT);
        var before = gold.copy();
        var roots = List.of(new CarriedStack(gold, BurdenSource.OFFHAND));
        var first = Burden.measure(level, roots);
        assertWeight(first, GOLD_COUNT * REGISTERED_GOLD_GRAMS, GOLD_COUNT * REGISTERED_GOLD_GRAMS);
        if (!ItemStack.matches(gold, before)) {
            throw new AssertionError("读取修改了输入物品");
        }
        gold.setCount(1);
        assertWeight(Burden.measure(level, roots), REGISTERED_GOLD_GRAMS, REGISTERED_GOLD_GRAMS);
        assertWeight(first, GOLD_COUNT * REGISTERED_GOLD_GRAMS, GOLD_COUNT * REGISTERED_GOLD_GRAMS);
        assertWeight(Burden.measure(level, List.of(new CarriedStack(new ItemStack(Items.STONE), BurdenSource.MAIN))),
                STONE_GRAMS, 2 * STONE_GRAMS);
        LOGGER.info("WEIGHTS_MEASURE_PASS currentRulesAndImmediateRead");
    }

    private static void playerAndRootsAgree(ServerLevel level) {
        // 原版 ServerPlayer 使用真实 Inventory 和 BS3D 存储 mixin；本检查不模拟客户端登录或网络会话。
        var profile = new GameProfile(UUID.randomUUID(), "weights_measure");
        var player = new ServerPlayer(level.getServer(), level, profile, ClientInformation.createDefault());
        var roots = equipPlayer(player);
        var measured = Burden.measure(level, roots);
        long total = Burden.totalGrams(player).orElseThrow();
        long effective = Burden.effectiveGrams(player).orElseThrow();
        assertWeight(measured, total, effective);
        // 手持石头不另加，潜影盒内容进入 MAIN，头甲、BODY 和 SADDLE 都进入 ARMOR。
        if (total != 2 * STONE_GRAMS + GOLD_COUNT * REGISTERED_GOLD_GRAMS + 4 * DEFAULT_GRAMS) {
            throw new AssertionError("玩家实重重复计算主手或漏算容器／装备");
        }
        if (effective != 2 * (STONE_GRAMS + DEFAULT_GRAMS)
                + GOLD_COUNT * REGISTERED_GOLD_GRAMS + 3 * DEFAULT_GRAMS) {
            throw new AssertionError("来源或有效克数不符");
        }
        player.getInventory().setItem(FIRST_MAIN_SLOT, ItemStack.EMPTY);
        if (Burden.totalGrams(player).orElseThrow() != total) {
            throw new AssertionError("新入口破坏了玩家同 tick 读数缓存");
        }
        LOGGER.info("WEIGHTS_MEASURE_PASS playerAndRootsAgree");
    }

    private static List<CarriedStack> equipPlayer(ServerPlayer player) {
        // 两份表示使用同组物品的副本，玩家主手只占选中快捷格，装备通过原版槽位写入。
        var hand = new ItemStack(Items.STONE);
        var box = new ItemStack(Items.SHULKER_BOX);
        box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.STONE))));
        var offhand = new ItemStack(Items.GOLD_INGOT, GOLD_COUNT);
        var helmet = new ItemStack(Items.IRON_HELMET);
        var body = new ItemStack(Items.IRON_HORSE_ARMOR);
        var saddle = new ItemStack(Items.SADDLE);
        var inventory = player.getInventory();
        inventory.setSelectedSlot(0);
        inventory.setItem(0, hand.copy());
        inventory.setItem(FIRST_MAIN_SLOT, box.copy());
        inventory.setItem(Inventory.SLOT_OFFHAND, offhand.copy());
        player.setItemSlot(EquipmentSlot.HEAD, helmet.copy());
        player.setItemSlot(EquipmentSlot.BODY, body.copy());
        player.setItemSlot(EquipmentSlot.SADDLE, saddle.copy());
        if (!ItemStack.matches(hand, player.getMainHandItem())) {
            throw new AssertionError("选中槽未对应玩家主手");
        }
        return List.of(new CarriedStack(hand, BurdenSource.HOTBAR), new CarriedStack(box, BurdenSource.MAIN),
                new CarriedStack(offhand, BurdenSource.OFFHAND), new CarriedStack(helmet, BurdenSource.ARMOR),
                new CarriedStack(body, BurdenSource.ARMOR), new CarriedStack(saddle, BurdenSource.ARMOR));
    }

    private static void invalidAndEmptyAreDistinct(ServerLevel level) {
        // 成功零克和损坏库存都可能携带零字段，必须用状态区分。
        assertWeight(Burden.measure(level, List.of()), 0, 0);
        var basket = ContainerItems.create(BASKET);
        var roots = List.of(new CarriedStack(basket, BurdenSource.MAIN),
                new CarriedStack(basket.copy(), BurdenSource.OFFHAND));
        assertInvalid(Burden.measure(level, roots));
        CustomData.update(DataComponents.CUSTOM_DATA, basket, tag -> tag.getCompound("bs3d_container")
                .orElseThrow().putByteArray("contents", new byte[]{(byte) 0xff}));
        assertInvalid(Burden.measure(level, List.of(new CarriedStack(basket, BurdenSource.HOTBAR))));
        LOGGER.info("WEIGHTS_MEASURE_PASS invalidAndEmptyAreDistinct");
    }

    private static void badParametersThrow(ServerLevel level) {
        // 参数误接不能被库存异常转换吞掉。
        expectNull(() -> Burden.measure(null, List.of()));
        expectNull(() -> Burden.measure(level, null));
        expectNull(() -> Burden.measure(level, Arrays.asList((CarriedStack) null)));
        LOGGER.info("WEIGHTS_MEASURE_PASS badParametersThrow");
    }

    private static void wrongThreadThrows(ServerLevel level) {
        // 只在工作线程调用空清单，确认参数为空也不能跳过真实服务器线程检查。
        var future = CompletableFuture.runAsync(() -> {
            try {
                Burden.measure(level, List.of());
            } catch (IllegalStateException expected) {
                return;
            }
            throw new AssertionError("非服务器主线程未被拒绝");
        });
        try {
            future.get(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("线程检查被打断", interrupted);
        } catch (ExecutionException | TimeoutException failure) {
            throw new IllegalStateException("线程检查失败", failure);
        }
        LOGGER.info("WEIGHTS_MEASURE_PASS wrongThreadThrows");
    }

    private static void expectNull(Runnable call) {
        // 精确检查参数异常，其他错误继续向外传播。
        try {
            call.run();
        } catch (NullPointerException expected) {
            return;
        }
        throw new AssertionError("null 参数没有报错");
    }

    private static void assertWeight(WeightState state, long total, long effective) {
        // 状态有效后才读克数，不把默认零当作空身。
        var status = state.getStatus();
        if (status != Status.OK) {
            throw new AssertionError("预期 OK，实际为 " + status);
        }
        long actualTotal = state.getTotalGrams();
        long actualEffective = state.getEffectiveGrams();
        if (actualTotal != total || actualEffective != effective) {
            throw new AssertionError("重量不符：" + actualTotal + "/" + actualEffective
                    + "，预期 " + total + "/" + effective);
        }
    }

    private static void assertInvalid(WeightState state) {
        // 无效结果不能泄漏已累计的部分重量或明细。
        if (state.getStatus() != Status.INVALID_INVENTORY) {
            throw new AssertionError("无效库存没有返回 INVALID_INVENTORY");
        }
        if (state.getTotalGrams() != 0 || state.getEffectiveGrams() != 0 || state.getContainersCount() != 0) {
            throw new AssertionError("无效结果包含部分重量或明细");
        }
    }

    private static void writeSuccess() {
        // 只有全部断言成功才写标记，Gradle 会独立检查该标记。
        try {
            Files.writeString(MARKER, "WEIGHTS_MEASURE_OK cases=5\n");
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        LOGGER.info("WEIGHTS_MEASURE_OK cases=5");
    }
}
