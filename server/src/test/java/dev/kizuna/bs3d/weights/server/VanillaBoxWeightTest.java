package dev.kizuna.bs3d.weights.server;

import dev.kizuna.backpack3d.item.ContainerItems;
import dev.kizuna.backpack3d.protocol.StackCodec;
import dev.kizuna.backpack3d.protocol.Volumes;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Item;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Mount;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.WornContainer;
import dev.kizuna.backpack3d.server.InventoryAccess;
import dev.kizuna.backpack3d.service.TransferService;
import dev.kizuna.bs3d.weights.protocol.pb.WeightsProtocol.WeightState;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 原版潜影盒、收纳袋的内容递归计重：放在哪都算、按件数算、不双计、超上限整次拒绝。 */
class VanillaBoxWeightTest {
    private static final Identifier POUCH = Identifier.fromNamespaceAndPath("bs3d", "herb_pouch");
    private static final Identifier BASKET = Identifier.fromNamespaceAndPath("bs3d", "gu_jia_bei_lou");
    // 夹具单重：铁块 1000 g，其余物品（含潜影盒、收纳袋、BS3D 容器外壳）250 g。
    private static final long IRON = 1000;
    private static final long SHELL = 250;
    private static final int FULL_STACK = 64;
    // 原版一段连续容器最深的序号：潜影盒 0，收纳袋 1–17；与 WeightCalculator 的上限对应。
    private static final int DEEPEST_BOX = 17;
    // 与 WeightCalculator 的访问上限一致；一个收纳袋多装一件就超。
    private static final int MAX_VISITED_STACKS = 4096;
    private static final int FIRST_MAIN_SLOT = 9;
    private static final int PLAYER_SLOTS = BurdenSlots.PLAYER_SLOTS;
    private static final BurdenFactors UNIT_FACTORS = BurdenFactors.parse(
            "hotbar = 1\nmain = 1\narmor = 1\noffhand = 1\ncursor = 1\nworn_containers = 1\n");
    private final WeightCalculator calculator = calculator(UNIT_FACTORS);

    @BeforeAll
    static void bootstrap() {
        TestRegistries.bootstrap();
    }

    @Test
    void fullShulkerOfIronBlocksWeighsItsContents() {
        var full = new ItemStack[ShulkerBoxBlockEntity.CONTAINER_SIZE];
        for (int slot = 0; slot < full.length; slot++) {
            full[slot] = new ItemStack(Items.IRON_BLOCK, FULL_STACK);
        }
        long empty = carried(List.of(shulker())).getTotalGrams();
        var state = carried(List.of(shulker(full)));
        assertEquals(SHELL, empty);
        assertEquals(SHELL + ShulkerBoxBlockEntity.CONTAINER_SIZE * FULL_STACK * IRON, state.getTotalGrams());
        // 原版容器没有实例身份，不进容器明细。
        assertEquals(0, state.getContainersCount());
    }

    @Test
    void bundleInsideBundleCountsEveryLayer() {
        var inner = bundle(new ItemStack(Items.IRON_BLOCK, 2));
        var outer = bundle(inner, new ItemStack(Items.IRON_BLOCK));
        assertEquals(SHELL + SHELL + 2 * IRON + IRON, carried(List.of(outer)).getTotalGrams());
    }

    @Test
    void backpackHoldingShulkerHoldingBundleCountsIntoBackpack() {
        var pouch = filled(POUCH, shulker(bundle(new ItemStack(Items.IRON_BLOCK, 3))));
        var state = calculator.calculate(snapshot(List.of(), List.of(pouch), Map.of()), ItemStack.EMPTY,
                TestRegistries.VANILLA);
        assertEquals(SHELL + SHELL + SHELL + 3 * IRON, state.getTotalGrams());
        assertEquals(1, state.getContainersCount());
        var detail = state.getContainers(0);
        assertEquals(ContainerItems.identity(pouch), detail.getContainerId());
        assertEquals(SHELL + SHELL + 3 * IRON, detail.getContentsGrams());
        assertEquals(state.getTotalGrams(), detail.getTotalGrams());
        assertTrue(detail.getCarried());
    }

    @Test
    void backpackInsideShulkerIsListedAndCarried() {
        var pouch = filled(POUCH, new ItemStack(Items.IRON_BLOCK));
        var state = carried(List.of(shulker(pouch)));
        assertEquals(SHELL + SHELL + IRON, state.getTotalGrams());
        assertEquals(1, state.getContainersCount());
        assertEquals(SHELL + IRON, state.getContainers(0).getTotalGrams());
        assertTrue(state.getContainers(0).getCarried());
    }

    @Test
    void sameBackpackInsideShulkerAndWornIsRejected() {
        // 盒子里的 BS3D 实例照样登记身份，和穿戴的同一实例重复时不发布双计的总重。
        var pouch = ContainerItems.create(POUCH);
        var inventory = snapshot(List.of(shulker(pouch)), List.of(pouch.copy()), Map.of());
        assertThrows(IllegalArgumentException.class, () -> calculator.calculate(inventory, ItemStack.EMPTY,
                TestRegistries.VANILLA));
    }

    @Test
    void stackedFilledBoxesCountEachCopy() {
        // 只有命令能做出一堆多件的带内容盒子；每件都装着这份内容。
        var pair = shulker(new ItemStack(Items.IRON_BLOCK));
        pair.set(DataComponents.MAX_STACK_SIZE, 2);
        pair.setCount(2);
        assertEquals(2 * (SHELL + IRON), carried(List.of(pair)).getTotalGrams());

        var twins = shulker(ContainerItems.create(POUCH));
        twins.set(DataComponents.MAX_STACK_SIZE, 2);
        twins.setCount(2);
        // 两件盒子里是同一个 BS3D 实例，按件展开后查出重复。
        assertThrows(IllegalArgumentException.class, () -> carried(List.of(twins)));
    }

    @Test
    void deepestVanillaChainCountsAndOneMoreLayerIsRejected() {
        // 潜影盒（序号 0）里套 17 层收纳袋，最里层装一块铁；再包一层就超过原版能套出的层数。
        var nested = bundle(new ItemStack(Items.IRON_BLOCK));
        for (int depth = 1; depth < DEEPEST_BOX; depth++) {
            nested = bundle(nested);
        }
        var deepest = shulker(nested);
        assertEquals((DEEPEST_BOX + 1) * SHELL + IRON, carried(List.of(deepest)).getTotalGrams());

        var tooDeep = bundle(deepest);
        assertThrows(IllegalArgumentException.class, () -> carried(List.of(tooDeep)));
    }

    @Test
    void backpackDepthRestartsInsideVanillaBox() {
        // 最深的合法 BS3D 套娃里放潜影盒，盒里的背包是另一棵树，层数从零数起。
        ItemStack nested = shulker(filled(POUCH, new ItemStack(Items.IRON_BLOCK)));
        for (int depth = 0; depth <= ContainerItems.MAX_NESTING_DEPTH; depth++) {
            nested = filled(BASKET, nested);
        }
        var state = carried(List.of(nested));
        int baskets = ContainerItems.MAX_NESTING_DEPTH + 1;
        assertEquals(baskets * SHELL + SHELL + SHELL + IRON, state.getTotalGrams());
        assertEquals(baskets + 1, state.getContainersCount());
    }

    @Test
    void boxContentsShareTheVisitedStackLimit() {
        // 命令做出的超大收纳袋：内容逐件计入访问上限，超了整次拒绝，不返回部分总重。
        var bundle = bundle(Collections.nCopies(MAX_VISITED_STACKS, new ItemStack(Items.STONE)));
        assertThrows(IllegalArgumentException.class, () -> carried(List.of(bundle)));
    }

    @Test
    void boxInGroundBackpackStaysOutOfCarriedTotal() {
        var pouch = filled(POUCH, shulker(new ItemStack(Items.IRON_BLOCK, 2)));
        var state = calculator.calculate(snapshot(List.of(), List.of(), Map.of(ContainerItems.identity(pouch), pouch)),
                ItemStack.EMPTY, TestRegistries.VANILLA);
        assertEquals(0, state.getTotalGrams());
        assertEquals(SHELL + SHELL + 2 * IRON, state.getContainers(0).getTotalGrams());
        assertFalse(state.getContainers(0).getCarried());
    }

    @Test
    void boxContentsTakeTheFactorOfTheirRoot() {
        // Burden 附属读的有效负重按根的来源加权，盒子内容跟着盒子所在的格子走。
        var factors = BurdenFactors.parse(
                "hotbar = 0\nmain = 2\narmor = 1\noffhand = 1\ncursor = 1\nworn_containers = 1\n");
        List<ItemStack> slots = new ArrayList<>(Collections.nCopies(PLAYER_SLOTS, ItemStack.EMPTY));
        slots.set(0, shulker(new ItemStack(Items.IRON_BLOCK, 4)));
        slots.set(FIRST_MAIN_SLOT, bundle(new ItemStack(Items.IRON_BLOCK, 2)));
        var state = calculator(factors).calculate(snapshot(slots, List.of(), Map.of()), ItemStack.EMPTY,
                TestRegistries.VANILLA);
        assertEquals(SHELL + 4 * IRON + SHELL + 2 * IRON, state.getTotalGrams());
        assertEquals(2 * (SHELL + 2 * IRON), state.getEffectiveGrams());
    }

    private static WeightCalculator calculator(BurdenFactors factors) {
        return new WeightCalculator(stack -> stack.is(Items.IRON_BLOCK) ? IRON : SHELL, BurdenSlots.VANILLA, factors);
    }

    private WeightState carried(List<ItemStack> slots) {
        return calculator.calculate(snapshot(slots, List.of(), Map.of()), ItemStack.EMPTY, TestRegistries.VANILLA);
    }

    private static ItemStack shulker(ItemStack... contents) {
        NonNullList<ItemStack> items = NonNullList.withSize(ShulkerBoxBlockEntity.CONTAINER_SIZE, ItemStack.EMPTY);
        for (int slot = 0; slot < contents.length; slot++) {
            items.set(slot, contents[slot]);
        }
        var shulker = new ItemStack(Items.SHULKER_BOX);
        shulker.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(items));
        return shulker;
    }

    private static ItemStack bundle(ItemStack... contents) {
        return bundle(List.of(contents));
    }

    private static ItemStack bundle(List<ItemStack> contents) {
        List<ItemStackTemplate> templates = new ArrayList<>();
        for (var stack : contents) {
            templates.add(ItemStackTemplate.fromNonEmptyStack(stack));
        }
        var bundle = new ItemStack(Items.BUNDLE);
        bundle.set(DataComponents.BUNDLE_CONTENTS, new BundleContents(templates));
        return bundle;
    }

    private static InventoryAccess.Snapshot snapshot(List<ItemStack> slots, List<ItemStack> worn,
                                                     Map<String, ItemStack> ground) {
        var saved = TransferService.empty().toBuilder();
        for (var stack : worn) {
            saved.addWorn(WornContainer.newBuilder()
                    .setStackNbt(StackCodec.encode(stack, TestRegistries.VANILLA))
                    .setMount(Mount.TORSO_BACK));
        }
        return new InventoryAccess.Snapshot(saved.build(), slots, ground);
    }

    private static ItemStack filled(Identifier type, ItemStack child) {
        // 容器子项沿用实例 UUID；其他物品（含原版盒子）用独立条目身份、单位尺寸。
        var stack = ContainerItems.create(type);
        var definition = ContainerItems.definition(child);
        var size = definition == null ? Volumes.UNIT : definition.outside();
        var id = definition == null ? UUID.randomUUID().toString() : ContainerItems.identity(child);
        var contents = Item.newBuilder()
                .setId(id)
                .setContainer(ContainerItems.identity(stack))
                .setStackNbt(StackCodec.encode(child, TestRegistries.VANILLA))
                .setBaseSize(size)
                .setSize(size)
                .build();
        ContainerItems.setContents(stack, List.of(contents));
        return stack;
    }
}
