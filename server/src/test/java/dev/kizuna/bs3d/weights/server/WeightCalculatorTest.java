package dev.kizuna.bs3d.weights.server;

import dev.kizuna.backpack3d.item.ContainerItems;
import dev.kizuna.backpack3d.protocol.StackCodec;
import dev.kizuna.backpack3d.protocol.Volumes;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Item;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Mount;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.WornContainer;
import dev.kizuna.backpack3d.server.InventoryAccess;
import dev.kizuna.backpack3d.service.TransferService;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class WeightCalculatorTest {
    private final WeightCalculator calculator = new WeightCalculator(stack -> stack.isOf(Items.STONE) ? 1000 : 250);

    @BeforeAll
    static void bootstrap() {
        // 注册原版和 BS3D 物品后才构造真实 ItemStack，不能用假注册表替代规则。
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
        ContainerItems.initialize();
    }

    @Test
    void countsStacksArmorOffhandAndCursor() {
        var inventory = snapshot(List.of(new ItemStack(Items.STONE, 2), new ItemStack(Items.IRON_HELMET),
                new ItemStack(Items.IRON_SWORD)), List.of(), Map.of());
        assertEquals(3500, calculator.calculate(inventory, new ItemStack(Items.STONE)).getTotalGrams());
    }

    @Test
    void nestedWornContainerIsCountedOnce() {
        var pouch = filled(ContainerItems.POUCH, new ItemStack(Items.STONE, 3));
        var basket = filled(ContainerItems.BASKET, pouch);
        var inventory = snapshot(List.of(), List.of(basket), Map.of());
        var result = calculator.calculate(inventory, ItemStack.EMPTY);
        assertEquals(3500, result.getTotalGrams());
        assertEquals(2, result.getContainersCount());
        String basketId = ContainerItems.identity(basket);
        var outer = result.getContainersList().stream()
                .filter(container -> container.getContainerId().equals(basketId))
                .findFirst()
                .orElseThrow();
        assertEquals(3250, outer.getContentsGrams());
        assertEquals(3500, outer.getTotalGrams());
        assertTrue(outer.getCarried());
    }

    @Test
    void groundContentsAppearButNeverIncreaseCarriedTotal() {
        var pouch = filled(ContainerItems.POUCH, new ItemStack(Items.STONE, 4));
        var result = calculator.calculate(snapshot(List.of(new ItemStack(Items.STONE)), List.of(),
                Map.of(ContainerItems.identity(pouch), pouch)), ItemStack.EMPTY);
        assertEquals(1000, result.getTotalGrams());
        assertEquals(4250, result.getContainers(0).getTotalGrams());
        assertFalse(result.getContainers(0).getCarried());
    }

    @Test
    void movingBetweenRootsPreservesTotal() {
        var pouch = filled(ContainerItems.POUCH, new ItemStack(Items.STONE, 2));
        var worn = calculator.calculate(snapshot(List.of(), List.of(pouch), Map.of()), ItemStack.EMPTY);
        var held = calculator.calculate(snapshot(List.of(pouch), List.of(), Map.of()), ItemStack.EMPTY);
        assertEquals(worn, held);
    }

    @Test
    void duplicateIdentityRejectsEntireCalculation() {
        var pouch = ContainerItems.create(ContainerItems.POUCH);
        assertThrows(IllegalArgumentException.class, () -> calculator.calculate(
                snapshot(List.of(pouch), List.of(pouch.copy()), Map.of()), ItemStack.EMPTY));
    }

    @Test
    void corruptContentsNeverBecomeZeroWeight() {
        var pouch = ContainerItems.create(ContainerItems.POUCH);
        pouch.getSubNbt("bs3d_container").putByteArray("contents", new byte[]{(byte) 0xff});
        assertThrows(IllegalArgumentException.class, () -> calculator.calculate(
                snapshot(List.of(pouch), List.of(), Map.of()), ItemStack.EMPTY));
    }

    @Test
    void weightsUseInt64WithoutInt32Overflow() {
        var heavy = new WeightCalculator(stack -> WeightRule.MAX_UNIT_GRAMS);
        assertEquals(64_000_000_000L, heavy.calculate(snapshot(List.of(new ItemStack(Items.STONE, 64)),
                List.of(), Map.of()), ItemStack.EMPTY).getTotalGrams());
    }

    @Test
    void deepestAllowedContainerStillCountsOrdinaryContents() {
        // 先覆盖上限内的最深容器，再加一层验证整棵库存被拒绝。
        var nested = filled(ContainerItems.BOX, new ItemStack(Items.STONE));
        for (int depth = 0; depth < ContainerItems.MAX_NESTING_DEPTH; depth++) {
            nested = filled(ContainerItems.BOX, nested);
        }
        var state = calculator.calculate(snapshot(List.of(nested), List.of(), Map.of()), ItemStack.EMPTY);
        assertEquals(2250, state.getTotalGrams());
        assertEquals(5, state.getContainersCount());

        var tooDeep = filled(ContainerItems.BOX, nested);
        assertThrows(IllegalArgumentException.class, () -> calculator.calculate(
                snapshot(List.of(tooDeep), List.of(), Map.of()), ItemStack.EMPTY));
    }

    @Test
    void emptyAndZeroWeightAreValid() {
        var zero = new WeightCalculator(stack -> 0);
        assertEquals(0, zero.calculate(
                snapshot(List.of(new ItemStack(Items.STONE)), List.of(), Map.of()), ItemStack.EMPTY).getTotalGrams());
        assertEquals(0, calculator.calculate(snapshot(List.of(), List.of(), Map.of()), ItemStack.EMPTY).getTotalGrams());
    }

    private static InventoryAccess.Snapshot snapshot(List<ItemStack> slots, List<ItemStack> worn, Map<String, ItemStack> ground) {
        // 穿戴根与原版槽位分别提供，确保测试能发现把同一棵树重复加入总重的问题。
        var saved = TransferService.empty().toBuilder();
        for (var stack : worn) {
            saved.addWorn(WornContainer.newBuilder()
                    .setStackNbt(StackCodec.encode(stack))
                    .setMount(Mount.TORSO_BACK));
        }
        return new InventoryAccess.Snapshot(saved.build(), slots, ground);
    }

    private static ItemStack filled(Identifier type, ItemStack child) {
        // 容器子项沿用实例 UUID；普通物品使用独立条目身份，尺寸取真实定义。
        var stack = ContainerItems.create(type);
        var definition = ContainerItems.definition(child);
        var size = definition == null ? Volumes.UNIT : definition.outside();
        var id = definition == null ? UUID.randomUUID().toString() : ContainerItems.identity(child);
        var contents = Item.newBuilder()
                .setId(id)
                .setContainer(ContainerItems.identity(stack))
                .setStackNbt(StackCodec.encode(child))
                .setBaseSize(size)
                .setSize(size)
                .build();
        ContainerItems.setContents(stack, List.of(contents));
        return stack;
    }
}
