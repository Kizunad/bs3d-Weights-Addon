package dev.kizuna.bs3d.weights.server;

import dev.kizuna.backpack3d.item.ContainerItems;
import dev.kizuna.backpack3d.protocol.StackCodec;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Mount;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.WornContainer;
import dev.kizuna.backpack3d.server.InventoryAccess;
import dev.kizuna.backpack3d.service.TransferService;
import dev.kizuna.bs3d.weights.protocol.pb.WeightsProtocol.WeightState;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 共用读数：间隔内不读库存，输入逐项没变就不重算，任何一项变了都重算。 */
class WeightReadingTest {
    private static final Identifier POUCH = Identifier.fromNamespaceAndPath("bs3d", "herb_pouch");
    private static final int INTERVAL = WeightReadings.UPDATE_INTERVAL_TICKS;
    private static final int FIRST_MAIN_SLOT = 9;
    private static final int PLAYER_SLOTS = BurdenSlots.PLAYER_SLOTS;
    private static final long RULES = 1;
    private static final BurdenFactors UNIT_FACTORS = BurdenFactors.parse(
            "hotbar = 1\nmain = 1\narmor = 1\noffhand = 1\ncursor = 1\nworn_containers = 1\n");
    private static final WeightCalculator CALCULATOR = new WeightCalculator(
            stack -> stack.is(Items.IRON_BLOCK) ? 1000 : 250, BurdenSlots.VANILLA, UNIT_FACTORS);

    @BeforeAll
    static void bootstrap() {
        TestRegistries.bootstrap();
    }

    @Test
    void firstReadCalculatesAndReadsWithinTheIntervalReuseIt() {
        var reads = new AtomicInteger();
        var calculations = new AtomicInteger();
        var first = WeightReading.next(null, 100, INTERVAL, counted(reads, inputs(slots(), RULES)),
                counted(calculations));
        assertEquals(1, reads.get());
        assertEquals(1, calculations.get());
        assertEquals(100, first.tick());
        // 间隔内连库存都不读，拿到的是同一份读数。
        var again = WeightReading.next(first, 100 + INTERVAL - 1, INTERVAL, counted(reads, inputs(slots(), RULES)),
                counted(calculations));
        assertSame(first, again);
        assertEquals(1, reads.get());
        assertEquals(1, calculations.get());
    }

    @Test
    void unchangedInventoryIsReadButNotRecalculated() {
        var calculations = new AtomicInteger();
        var first = WeightReading.next(null, 100, INTERVAL, () -> inputs(slots(), RULES), counted(calculations));
        // 到点重读一份内容相同、对象不同的快照：不重算，结果对象沿用，读取时刻更新。
        var reads = new AtomicInteger();
        var next = WeightReading.next(first, 100 + INTERVAL, INTERVAL, counted(reads, inputs(slots(), RULES)),
                counted(calculations));
        assertEquals(1, reads.get());
        assertEquals(1, calculations.get());
        assertSame(first.state(), next.state());
        assertEquals(100 + INTERVAL, next.tick());
    }

    @Test
    void anyChangedInputRecalculates() {
        var base = inputs(slots(), RULES);
        assertTrue(base.sameAs(inputs(slots(), RULES)));

        // 主库存里一格数量变了。
        var more = slots();
        more.set(FIRST_MAIN_SLOT, new ItemStack(Items.IRON_BLOCK, 3));
        assertRecalculates(base, inputs(more, RULES), 1000);

        // 潜影盒里多了一块铁：只改了组件里的内容，外面的物品和数量都没变。
        var heavierBox = slots();
        heavierBox.set(0, shulker(new ItemStack(Items.IRON_BLOCK, 2)));
        assertRecalculates(base, inputs(heavierBox, RULES), 1000);

        // 光标上拿起一件。
        var holding = WeightInputs.of(snapshot(slots(), List.of(), Map.of()), new ItemStack(Items.IRON_BLOCK),
                TestRegistries.VANILLA, RULES);
        assertRecalculates(base, holding, 1000);

        // 穿上一个背包。
        var wearing = WeightInputs.of(snapshot(slots(), List.of(ContainerItems.create(POUCH)), Map.of()),
                ItemStack.EMPTY, TestRegistries.VANILLA, RULES);
        assertRecalculates(base, wearing, 250);

        // 打开一个地面背包：只进明细，不进随身总重，但明细变了也得重发。
        var pouch = ContainerItems.create(POUCH);
        var ground = WeightInputs.of(snapshot(slots(), List.of(), Map.of(ContainerItems.identity(pouch), pouch)),
                ItemStack.EMPTY, TestRegistries.VANILLA, RULES);
        assertFalse(base.sameAs(ground));

        // 数据包重载换了规则：库存一点没变也要重算。
        assertFalse(base.sameAs(inputs(slots(), RULES + 1)));

        // 换了一个服务器的注册表。
        var otherServer = WeightInputs.of(base.inventory(), ItemStack.EMPTY, VanillaRegistries.createLookup(), RULES);
        assertFalse(base.sameAs(otherServer));
    }

    @Test
    void cursorIsCopiedSoLaterChangesStillShow() {
        // 光标栈是玩家手里的活栈；留存的那份不能跟着它变，否则下一次比较会误判没变。
        var cursor = new ItemStack(Items.IRON_BLOCK, 2);
        var held = WeightInputs.of(snapshot(slots(), List.of(), Map.of()), cursor, TestRegistries.VANILLA, RULES);
        cursor.shrink(1);
        var later = WeightInputs.of(snapshot(slots(), List.of(), Map.of()), cursor, TestRegistries.VANILLA, RULES);
        assertFalse(held.sameAs(later));
    }

    private static void assertRecalculates(WeightInputs base, WeightInputs changed, long extraGrams) {
        // 变了的输入要重算，而且重算出的总重确实按变化的那一份变了。
        assertFalse(base.sameAs(changed));
        var calculations = new AtomicInteger();
        var first = WeightReading.next(null, 0, INTERVAL, () -> base, counted(calculations));
        var next = WeightReading.next(first, INTERVAL, INTERVAL, () -> changed, counted(calculations));
        assertEquals(2, calculations.get());
        assertEquals(first.state().getTotalGrams() + extraGrams, next.state().getTotalGrams());
    }

    private static Supplier<WeightInputs> counted(AtomicInteger reads, WeightInputs inputs) {
        return () -> {
            reads.incrementAndGet();
            return inputs;
        };
    }

    private static Function<WeightInputs, WeightState> counted(AtomicInteger calculations) {
        return inputs -> {
            calculations.incrementAndGet();
            return CALCULATOR.calculate(inputs.inventory(), inputs.cursor(), inputs.registries());
        };
    }

    private static WeightInputs inputs(List<ItemStack> slots, long rules) {
        return WeightInputs.of(snapshot(slots, List.of(), Map.of()), ItemStack.EMPTY, TestRegistries.VANILLA, rules);
    }

    private static List<ItemStack> slots() {
        // 每次都新建栈对象，比较的是内容不是引用。
        List<ItemStack> slots = new ArrayList<>(Collections.nCopies(PLAYER_SLOTS, ItemStack.EMPTY));
        slots.set(0, shulker(new ItemStack(Items.IRON_BLOCK)));
        slots.set(FIRST_MAIN_SLOT, new ItemStack(Items.IRON_BLOCK, 2));
        slots.set(FIRST_MAIN_SLOT + 1, new ItemStack(Items.STONE, 16));
        return slots;
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

    private static InventoryAccess.Snapshot snapshot(List<ItemStack> slots, List<ItemStack> worn,
                                                     Map<String, ItemStack> ground) {
        var saved = TransferService.empty().toBuilder();
        for (var stack : worn) {
            saved.addWorn(WornContainer.newBuilder()
                    .setStackNbt(StackCodec.encode(stack, TestRegistries.VANILLA))
                    .setMount(Mount.TORSO_BACK));
        }
        return new InventoryAccess.Snapshot(saved.build(), List.copyOf(slots), Map.copyOf(ground));
    }
}
