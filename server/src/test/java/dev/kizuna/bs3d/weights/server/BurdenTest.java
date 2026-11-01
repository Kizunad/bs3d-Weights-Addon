package dev.kizuna.bs3d.weights.server;

import dev.kizuna.backpack3d.item.ContainerItems;
import dev.kizuna.backpack3d.protocol.StackCodec;
import dev.kizuna.backpack3d.protocol.Volumes;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Item;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Mount;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.WornContainer;
import dev.kizuna.backpack3d.server.InventoryAccess;
import dev.kizuna.backpack3d.service.TransferService;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;


class BurdenTest {
    // 26.2 原版玩家库存：36 格主库存、4 格盔甲，以及副手、身体盔甲与鞍各 1 格。
    private static final int PLAYER_SLOTS = BurdenSlots.PLAYER_SLOTS;
    private static final int HOTBAR_SLOT = 0;
    private static final int FIRST_MAIN_SLOT = 9;
    private static final int LAST_POCKET_SLOT = 17;
    private static final int HIDDEN_BY_POCKET_SLOT = 20;
    private static final int ARMOR_SLOT = 36;
    private static final Identifier POUCH = Identifier.fromNamespaceAndPath("bs3d", "herb_pouch");

    @BeforeAll
    static void bootstrap() {
        // 注册原版和 BS3D 物品后才构造真实 ItemStack 与容器。
        TestRegistries.bootstrap();
    }

    @Test
    void shippedFactorsWeightEachSourceAndSnapshotCarriesBothNumbers() throws IOException {
        // 默认表：快捷栏 0、主库存 2、盔甲/副手/光标 1、穿戴容器及内容 1。
        var calculator = new WeightCalculator(BurdenTest::stoneOrSmall, BurdenSlots.VANILLA, shipped());
        var slots = slots(Map.of(
                HOTBAR_SLOT, new ItemStack(Items.STONE, 2),
                FIRST_MAIN_SLOT, new ItemStack(Items.STONE, 3),
                ARMOR_SLOT, new ItemStack(Items.IRON_HELMET),
                Inventory.SLOT_OFFHAND, new ItemStack(Items.IRON_SWORD)));
        var pouch = pouch(new ItemStack(Items.STONE, 3));
        var state = calculator.calculate(snapshot(slots, List.of(pouch)), new ItemStack(Items.STONE), TestRegistries.VANILLA);
        assertEquals(2000 + 3000 + 250 + 250 + 1000 + 3250, state.getTotalGrams());
        assertEquals(0 + 6000 + 250 + 250 + 1000 + 3250, state.getEffectiveGrams());
    }

    @Test
    void hotbarStonesAreFreeButMainInventoryStonesCountTwice() throws IOException {
        var calculator = new WeightCalculator(BurdenTest::stoneOrSmall, BurdenSlots.VANILLA, shipped());
        var hotbar = calculator.calculate(snapshot(slots(Map.of(HOTBAR_SLOT, new ItemStack(Items.STONE, 64))),
                List.of()), ItemStack.EMPTY, TestRegistries.VANILLA);
        var main = calculator.calculate(snapshot(slots(Map.of(FIRST_MAIN_SLOT, new ItemStack(Items.STONE, 64))),
                List.of()), ItemStack.EMPTY, TestRegistries.VANILLA);
        assertEquals(64_000, hotbar.getTotalGrams());
        assertEquals(0, hotbar.getEffectiveGrams());
        assertEquals(64_000, main.getTotalGrams());
        assertEquals(128_000, main.getEffectiveGrams());
    }

    @Test
    void pocketKeepsOnlyTheFirstMainRow() throws IOException {
        // 范围由常量决定：原版主库存到 35 号槽，装了 Pocket 只到 17 号槽。
        assertEquals(Inventory.INVENTORY_SIZE, BurdenSlots.VANILLA.mainEnd());
        assertEquals(LAST_POCKET_SLOT + 1, BurdenSlots.POCKET.mainEnd());
        assertEquals(BurdenSlots.VANILLA, BurdenSlots.detect());
        var lastRow = slots(Map.of(LAST_POCKET_SLOT, new ItemStack(Items.STONE)));
        var hidden = slots(Map.of(HIDDEN_BY_POCKET_SLOT, new ItemStack(Items.STONE)));
        var vanilla = new WeightCalculator(BurdenTest::stoneOrSmall, BurdenSlots.VANILLA, shipped());
        var pocket = new WeightCalculator(BurdenTest::stoneOrSmall, BurdenSlots.POCKET, shipped());
        assertEquals(2000, vanilla.calculate(snapshot(lastRow, List.of()), ItemStack.EMPTY, TestRegistries.VANILLA).getEffectiveGrams());
        assertEquals(2000, pocket.calculate(snapshot(lastRow, List.of()), ItemStack.EMPTY, TestRegistries.VANILLA).getEffectiveGrams());
        assertEquals(2000, vanilla.calculate(snapshot(hidden, List.of()), ItemStack.EMPTY, TestRegistries.VANILLA).getEffectiveGrams());
        var pocketHidden = pocket.calculate(snapshot(hidden, List.of()), ItemStack.EMPTY, TestRegistries.VANILLA);
        assertEquals(1000, pocketHidden.getTotalGrams());
        assertEquals(0, pocketHidden.getEffectiveGrams());
    }

    @Test
    void fractionalFactorsRoundOncePerSource() {
        // 两个主库存根各 1 克，按来源合计 2 克再乘 0.25 得 0.5，四舍五入为 1 克。
        var factors = BurdenFactors.parse(table("0.25"));
        var calculator = new WeightCalculator(stack -> 1, BurdenSlots.VANILLA, factors);
        var slots = slots(Map.of(
                FIRST_MAIN_SLOT, new ItemStack(Items.STICK),
                FIRST_MAIN_SLOT + 1, new ItemStack(Items.STICK)));
        assertEquals(1, calculator.calculate(snapshot(slots, List.of()), ItemStack.EMPTY, TestRegistries.VANILLA).getEffectiveGrams());
    }

    @Test
    void effectiveOverflowRejectsTheWholeCalculation() {
        var factors = BurdenFactors.parse(table("1e300"));
        var calculator = new WeightCalculator(BurdenTest::stoneOrSmall, BurdenSlots.VANILLA, factors);
        var slots = slots(Map.of(FIRST_MAIN_SLOT, new ItemStack(Items.STONE)));
        assertThrows(ArithmeticException.class,
                () -> calculator.calculate(snapshot(slots, List.of()), ItemStack.EMPTY, TestRegistries.VANILLA));
    }

    @Test
    void invalidFactorTablesAreRejected() {
        // 整数和小数都接受；负数、nan、inf、字符串、缺键、多余键和语法错误都拒绝整份表。
        assertDoesNotThrow(() -> BurdenFactors.parse(table("2")));
        assertDoesNotThrow(() -> BurdenFactors.parse(table("2.5")));
        assertThrows(IllegalArgumentException.class, () -> BurdenFactors.parse(table("-1")));
        assertThrows(IllegalArgumentException.class, () -> BurdenFactors.parse(table("nan")));
        assertThrows(IllegalArgumentException.class, () -> BurdenFactors.parse(table("inf")));
        assertThrows(IllegalArgumentException.class, () -> BurdenFactors.parse(table("\"2\"")));
        assertThrows(IllegalArgumentException.class, () -> BurdenFactors.parse("hotbar = 0\nmain = 2\n"));
        assertThrows(IllegalArgumentException.class, () -> BurdenFactors.parse(table("2") + "mian = 2\n"));
        assertThrows(IllegalArgumentException.class, () -> BurdenFactors.parse("hotbar = = 0"));
    }

    @Test
    void slotOutsidePlayerInventoryIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> BurdenSlots.VANILLA.source(PLAYER_SLOTS));
        assertThrows(IllegalArgumentException.class, () -> BurdenSlots.VANILLA.source(-1));
    }

    private static long stoneOrSmall(ItemStack stack) {
        // 与 WeightCalculatorTest 相同的单重：石头 1000 克，其余 250 克。
        return stack.is(Items.STONE) ? 1000 : 250;
    }

    private static BurdenFactors shipped() throws IOException {
        // 读取随 JAR 发布的默认表，确认表中数值就是任务约定的系数。
        try (var source = BurdenFactors.class.getResourceAsStream(BurdenFactors.DEFAULT_RESOURCE)) {
            return BurdenFactors.parse(new String(source.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static String table(String main) {
        // 只换主库存系数，其余键保持默认表的值。
        return "hotbar = 0\nmain = " + main + "\narmor = 1\noffhand = 1\ncursor = 1\nworn_containers = 1\n";
    }

    private static List<ItemStack> slots(Map<Integer, ItemStack> occupied) {
        // 按原版槽号铺满 43 格，未指定的槽为空栈。
        var slots = new ArrayList<ItemStack>();
        for (int slot = 0; slot < PLAYER_SLOTS; slot++) {
            slots.add(occupied.getOrDefault(slot, ItemStack.EMPTY));
        }
        return slots;
    }

    private static InventoryAccess.Snapshot snapshot(List<ItemStack> slots, List<ItemStack> worn) {
        var saved = TransferService.empty().toBuilder();
        for (var stack : worn) {
            saved.addWorn(WornContainer.newBuilder()
                    .setStackNbt(StackCodec.encode(stack, TestRegistries.VANILLA))
                    .setMount(Mount.TORSO_BACK));
        }
        return new InventoryAccess.Snapshot(saved.build(), slots, Map.of());
    }

    private static ItemStack pouch(ItemStack child) {
        // 灵草囊内放一件普通物品，条目身份独立，尺寸取单位体积。
        var stack = ContainerItems.create(POUCH);
        var contents = Item.newBuilder()
                .setId(UUID.randomUUID().toString())
                .setContainer(ContainerItems.identity(stack))
                .setStackNbt(StackCodec.encode(child, TestRegistries.VANILLA))
                .setBaseSize(Volumes.UNIT)
                .setSize(Volumes.UNIT)
                .build();
        ContainerItems.setContents(stack, List.of(contents));
        return stack;
    }
}
