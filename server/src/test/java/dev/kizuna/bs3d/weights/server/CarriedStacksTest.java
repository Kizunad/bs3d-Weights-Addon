package dev.kizuna.bs3d.weights.server;

import dev.kizuna.backpack3d.item.ContainerItems;
import dev.kizuna.backpack3d.protocol.StackCodec;
import dev.kizuna.backpack3d.protocol.Volumes;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Item;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Mount;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.WornContainer;
import dev.kizuna.backpack3d.server.InventoryAccess;
import dev.kizuna.backpack3d.service.TransferService;
import dev.kizuna.bs3d.weights.protocol.pb.WeightsProtocol.Status;
import dev.kizuna.bs3d.weights.protocol.pb.WeightsProtocol.WeightState;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemContainerContents;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 同一计算器的两种根适配必须保持实物、来源和整次失败边界一致。 */
class CarriedStacksTest {
    private static final Identifier BASKET = Identifier.parse("bs3d:gu_jia_bei_lou");
    private static final int FIRST_MAIN_SLOT = 9;
    private static final int MOB_INVENTORY_SLOTS = 18;
    private static final int FIRST_ARMOR_SLOT = 36;
    private static final int MAX_VISITED_STACKS = 4096;
    private static final int DEEPEST_BOX = 17;
    private static final BurdenFactors FACTORS = BurdenFactors.parse("""
            hotbar = 0.25
            main = 0.25
            armor = 0.5
            offhand = 2
            cursor = 3
            worn_containers = 4
            """);
    private final WeightCalculator calculator = new WeightCalculator(stack -> 1, BurdenSlots.POCKET, FACTORS);

    @BeforeAll
    static void bootstrap() {
        TestRegistries.bootstrap();
    }

    @Test
    void rootsRoundOncePerSourceAfterSummingAllStacks() {
        var roots = List.of(root(new ItemStack(Items.STONE), BurdenSource.MAIN),
                root(new ItemStack(Items.STICK), BurdenSource.MAIN),
                root(new ItemStack(Items.STONE), BurdenSource.HOTBAR),
                root(new ItemStack(Items.STICK), BurdenSource.HOTBAR));
        assertWeight(measure(roots), 4, 2);
    }

    @Test
    void samePhysicalItemsMatchPlayerAndRootAdaptersIncludingEquipmentAndCursor() {
        // 玩家主手就是 0 号快捷格；根清单只列一次，并给每个来源设置不同于默认表的系数。
        var hand = new ItemStack(Items.BOW);
        var box = shulker(bundle(new ItemStack(Items.STONE, 3)));
        var armor = new ItemStack(Items.IRON_HELMET);
        var offhand = new ItemStack(Items.TOTEM_OF_UNDYING);
        var body = new ItemStack(Items.IRON_HORSE_ARMOR);
        var saddle = new ItemStack(Items.SADDLE);
        var cursor = new ItemStack(Items.STICK, 2);
        var worn = filled(new ItemStack(Items.STONE, 2));
        var slots = playerSlots();
        slots.set(0, hand);
        slots.set(FIRST_MAIN_SLOT, box);
        slots.set(FIRST_ARMOR_SLOT, armor);
        slots.set(Inventory.SLOT_OFFHAND, offhand);
        slots.set(Inventory.SLOT_BODY_ARMOR, body);
        slots.set(Inventory.SLOT_SADDLE, saddle);
        var snapshot = snapshot(slots, worn);
        var roots = List.of(root(hand, BurdenSource.HOTBAR), root(box, BurdenSource.MAIN),
                root(armor, BurdenSource.ARMOR), root(offhand, BurdenSource.OFFHAND),
                root(body, BurdenSource.ARMOR), root(saddle, BurdenSource.ARMOR),
                root(cursor, BurdenSource.CURSOR), root(worn, BurdenSource.WORN_CONTAINERS));
        var player = calculator.calculate(snapshot, cursor, TestRegistries.VANILLA);
        var carried = measure(roots);
        assertWeight(player, 15, 23);
        assertEquals(player, carried);
    }

    @Test
    void eighteenSlotsAndIndependentMainHandCountNineteenPhysicalRoots() {
        // 数量 1..18 合计 171，另有一把弓；相同物品的不同栈不能按内容去重。
        var roots = new ArrayList<CarriedStack>();
        for (int slot = 0; slot < MOB_INVENTORY_SLOTS; slot++) {
            BurdenSource source = BurdenSource.MAIN;
            if (slot < FIRST_MAIN_SLOT) {
                source = BurdenSource.HOTBAR;
            }
            roots.add(root(new ItemStack(Items.STONE, slot + 1), source));
        }
        roots.add(root(new ItemStack(Items.BOW), BurdenSource.HOTBAR));
        assertWeight(measure(roots), 172, 44);
    }

    @Test
    void recursivelyCountsBs3dShulkerAndBundleUnderRootSource() {
        var basket = filled(shulker(bundle(new ItemStack(Items.STONE, 3))));
        var before = basket.copy();
        var state = measure(List.of(root(basket, BurdenSource.OFFHAND)));
        assertWeight(state, 6, 12);
        assertEquals(1, state.getContainersCount());
        var detail = state.getContainers(0);
        assertEquals(5, detail.getContentsGrams());
        assertEquals(6, detail.getTotalGrams());
        assertTrue(detail.getCarried());
        assertTrue(ItemStack.matches(before, basket));
    }

    @Test
    void duplicateIdentityAcrossRootsAndNestedContentsRejectsWholeResult() {
        var basket = filled(new ItemStack(Items.STONE));
        assertInvalid(measure(List.of(root(basket, BurdenSource.HOTBAR),
                root(shulker(basket.copy()), BurdenSource.MAIN))));
    }

    @Test
    void corruptContentsRemainInvalidEvenWhenSourceFactorIsZero() {
        var basket = ContainerItems.create(BASKET);
        CustomData.update(DataComponents.CUSTOM_DATA, basket, tag -> tag.getCompound("bs3d_container")
                .orElseThrow().putByteArray("contents", new byte[]{(byte) 0xff}));
        var zero = new WeightCalculator(stack -> 1, BurdenSlots.POCKET, zeroFactors());
        assertInvalid(zero.measure(List.of(root(basket, BurdenSource.HOTBAR)), TestRegistries.VANILLA));
    }

    @Test
    void deepestBs3dContentsCountAndExtraLayerIsInvalid() {
        var nested = filled(new ItemStack(Items.STONE));
        for (int depth = 0; depth < ContainerItems.MAX_NESTING_DEPTH; depth++) {
            nested = filled(nested);
        }
        assertWeight(measure(List.of(root(nested, BurdenSource.OFFHAND))), 6, 12);
        assertInvalid(measure(List.of(root(filled(nested), BurdenSource.OFFHAND))));
    }

    @Test
    void deepestVanillaContentsCountAndExtraLayerIsInvalid() {
        ItemStack nested = new ItemStack(Items.STONE);
        for (int depth = 0; depth < DEEPEST_BOX; depth++) {
            nested = bundle(nested);
        }
        var legal = shulker(nested);
        assertWeight(measure(List.of(root(legal, BurdenSource.OFFHAND))), 19, 38);
        assertInvalid(measure(List.of(root(bundle(legal), BurdenSource.OFFHAND))));
    }

    @Test
    void visitedStackBudgetIsSharedAcrossRootsAndContents() {
        var roots = new ArrayList<CarriedStack>();
        for (int index = 0; index < MAX_VISITED_STACKS; index++) {
            roots.add(root(new ItemStack(Items.STONE), BurdenSource.MAIN));
        }
        assertWeight(measure(roots), MAX_VISITED_STACKS, MAX_VISITED_STACKS / 4);
        roots.add(root(bundle(new ItemStack(Items.STONE)), BurdenSource.MAIN));
        assertInvalid(measure(roots));
    }

    @Test
    void containerIdentityBudgetIsSharedAcrossRoots() {
        var roots = new ArrayList<CarriedStack>();
        for (int index = 0; index < ContainerItems.MAX_CONTAINERS; index++) {
            roots.add(root(ContainerItems.create(BASKET), BurdenSource.OFFHAND));
        }
        assertWeight(measure(roots), ContainerItems.MAX_CONTAINERS, 2 * ContainerItems.MAX_CONTAINERS);
        roots.add(root(ContainerItems.create(BASKET), BurdenSource.OFFHAND));
        assertInvalid(measure(roots));
    }

    @Test
    void effectiveOverflowReturnsInvalidInsteadOfPartialRealWeight() {
        var factors = BurdenFactors.parse("""
                hotbar = 0
                main = 1e300
                armor = 1
                offhand = 1
                cursor = 1
                worn_containers = 1
                """);
        var overflow = new WeightCalculator(stack -> 1, BurdenSlots.POCKET, factors);
        assertInvalid(overflow.measure(List.of(root(new ItemStack(Items.STONE), BurdenSource.MAIN)),
                TestRegistries.VANILLA));
    }

    @Test
    void emptyAndZeroAreOkButOversizedStackIsNot() {
        assertWeight(measure(List.of()), 0, 0);
        assertWeight(measure(List.of(root(ItemStack.EMPTY, BurdenSource.MAIN))), 0, 0);
        var zero = new WeightCalculator(stack -> 0, BurdenSlots.POCKET, FACTORS);
        var stone = new ItemStack(Items.STONE);
        assertWeight(zero.measure(List.of(root(stone, BurdenSource.MAIN)), TestRegistries.VANILLA), 0, 0);
        stone.setCount(stone.getMaxStackSize() + 1);
        assertInvalid(measure(List.of(root(stone, BurdenSource.MAIN))));
    }

    @Test
    void readsEveryCallWithoutMutatingInputOrEarlierResult() {
        var stone = new ItemStack(Items.STONE, 2);
        var roots = List.of(root(stone, BurdenSource.MAIN));
        var before = stone.copy();
        var first = measure(roots);
        assertWeight(first, 2, 1);
        assertTrue(ItemStack.matches(before, stone));
        stone.setCount(6);
        assertWeight(measure(roots), 6, 2);
        assertWeight(first, 2, 1);
    }

    @Test
    void nullParametersThrowInsteadOfReturningInventoryStatus() {
        assertThrows(NullPointerException.class, () -> new CarriedStack(null, BurdenSource.MAIN));
        assertThrows(NullPointerException.class, () -> new CarriedStack(ItemStack.EMPTY, null));
        assertThrows(NullPointerException.class, () -> measure(null));
        assertThrows(NullPointerException.class, () -> measure(Arrays.asList((CarriedStack) null)));
        assertThrows(NullPointerException.class, () -> calculator.measure(List.of(), null));
    }

    private WeightState measure(List<CarriedStack> roots) {
        return calculator.measure(roots, TestRegistries.VANILLA);
    }

    private static CarriedStack root(ItemStack stack, BurdenSource source) {
        return new CarriedStack(stack, source);
    }

    private static void assertWeight(WeightState state, long total, long effective) {
        // 先确认状态有效，再比较两种克数。
        assertEquals(Status.OK, state.getStatus());
        assertEquals(total, state.getTotalGrams());
        assertEquals(effective, state.getEffectiveGrams());
    }

    private static void assertInvalid(WeightState state) {
        // 整次失败不保留合法前缀的重量或明细。
        assertEquals(Status.INVALID_INVENTORY, state.getStatus());
        assertEquals(0, state.getTotalGrams());
        assertEquals(0, state.getEffectiveGrams());
        assertEquals(0, state.getContainersCount());
    }

    private static List<ItemStack> playerSlots() {
        return new ArrayList<>(Collections.nCopies(BurdenSlots.PLAYER_SLOTS, ItemStack.EMPTY));
    }

    private static InventoryAccess.Snapshot snapshot(List<ItemStack> slots, ItemStack worn) {
        var container = WornContainer.newBuilder()
                .setStackNbt(StackCodec.encode(worn, TestRegistries.VANILLA))
                .setMount(Mount.TORSO_BACK)
                .build();
        var saved = TransferService.empty().toBuilder()
                .addWorn(container)
                .build();
        return new InventoryAccess.Snapshot(saved, slots, Map.of());
    }

    private static ItemStack filled(ItemStack child) {
        // BS3D 子容器沿用真实身份；其他物品只需要独立存档条目身份。
        var basket = ContainerItems.create(BASKET);
        String identity = UUID.randomUUID().toString();
        var size = Volumes.UNIT;
        var definition = ContainerItems.definition(child);
        if (definition != null) {
            identity = ContainerItems.identity(child);
            size = definition.outside();
        }
        var item = Item.newBuilder()
                .setId(identity)
                .setContainer(ContainerItems.identity(basket))
                .setStackNbt(StackCodec.encode(child, TestRegistries.VANILLA))
                .setBaseSize(size)
                .setSize(size)
                .build();
        ContainerItems.setContents(basket, List.of(item));
        return basket;
    }

    private static ItemStack shulker(ItemStack child) {
        var box = new ItemStack(Items.SHULKER_BOX);
        box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(child)));
        return box;
    }

    private static ItemStack bundle(ItemStack child) {
        var bag = new ItemStack(Items.BUNDLE);
        var template = ItemStackTemplate.fromNonEmptyStack(child);
        bag.set(DataComponents.BUNDLE_CONTENTS, new BundleContents(List.of(template)));
        return bag;
    }

    private static BurdenFactors zeroFactors() {
        return BurdenFactors.parse("""
                hotbar = 0
                main = 0
                armor = 0
                offhand = 0
                cursor = 0
                worn_containers = 0
                """);
    }
}
