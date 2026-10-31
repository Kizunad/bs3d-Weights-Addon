package dev.kizuna.bs3d.weights.server;

import dev.kizuna.backpack3d.item.ContainerItems;
import dev.kizuna.backpack3d.protocol.StackCodec;
import dev.kizuna.backpack3d.server.InventoryAccess;
import dev.kizuna.bs3d.weights.protocol.pb.WeightsProtocol.ContainerWeight;
import dev.kizuna.bs3d.weights.protocol.pb.WeightsProtocol.Status;
import dev.kizuna.bs3d.weights.protocol.pb.WeightsProtocol.WeightState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToLongFunction;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;

/** 从真实库存树自底向上累计，每个根只加入总重一次；有效负重按根所在来源加权。 */
public final class WeightCalculator {
    // 每次计算允许访问的非空物品栈数量，限制损坏库存带来的遍历工作量。
    private static final int MAX_VISITED_STACKS = 4096;
    private final ToLongFunction<ItemStack> unitGrams;
    private final BurdenSlots slots;
    private final BurdenFactors factors;

    WeightCalculator(ToLongFunction<ItemStack> unitGrams, BurdenSlots slots, BurdenFactors factors) {
        this.unitGrams = unitGrams;
        this.slots = slots;
        this.factors = factors;
    }

    public WeightState calculate(InventoryAccess.Snapshot inventory, ItemStack cursor, HolderLookup.Provider registries) {
        // 每次计算使用独立遍历状态；损坏树必须抛错，不能发布看似正常的部分总重。
        // 容器内容按数据组件保存，解码须用玩家所在服务器的注册表。
        var walk = new Walk(registries);
        var playerSlots = inventory.playerSlots();
        for (int slot = 0; slot < playerSlots.size(); slot++) {
            var stack = playerSlots.get(slot);
            var source = slots.source(slot);
            if (source.isPresent()) {
                walk.carry(stack, source.get());
            } else {
                // Pocket 已移除的旧主库存槽仍计实重，但不属于任何有效负重来源。
                walk.carry(stack);
            }
        }
        walk.carry(cursor, BurdenSource.CURSOR);
        for (var worn : inventory.equipment().getWornList()) {
            walk.carry(StackCodec.decode(worn.getStackNbt(), registries), BurdenSource.WORN_CONTAINERS);
        }
        // 地面容器仅产生明细，其子树也带 carried=false，不能混入随身总重。
        for (var stack : inventory.ground().values()) {
            walk.stack(stack, false, 0);
        }
        walk.containers.sort(Comparator.comparing(ContainerWeight::getContainerId));
        return WeightState.newBuilder()
                .setStatus(Status.OK)
                .setTotalGrams(walk.total)
                .setEffectiveGrams(factors.effectiveGrams(walk.carried))
                .addAllContainers(walk.containers)
                .build();
    }

    private final class Walk {
        private final HolderLookup.Provider registries;
        private final Set<String> identities = new HashSet<>();
        private final List<ContainerWeight> containers = new ArrayList<>();
        private final Map<BurdenSource, Long> carried = new EnumMap<>(BurdenSource.class);
        private long total;
        private int visitedStacks;

        private Walk(HolderLookup.Provider registries) {
            this.registries = registries;
        }

        private long carry(ItemStack stack) {
            // 随身根只在这里加入实重，嵌套内容已由递归累计到根上。
            long grams = stack(stack, true, 0);
            total = Math.addExact(total, grams);
            return grams;
        }

        private void carry(ItemStack stack, BurdenSource source) {
            // 同一来源的多个根先合计实重，系数在全部遍历完成后统一套用。
            long grams = carry(stack);
            carried.merge(source, grams, Math::addExact);
        }

        private long stack(ItemStack stack, boolean carried, int depth) {
            // 空槽不计重；每件非空栈限制工作量与堆叠数量。
            if (stack.isEmpty()) {
                return 0;
            }
            visitedStacks++;
            if (visitedStacks > MAX_VISITED_STACKS) {
                throw new IllegalArgumentException("重量计算访问的物品栈数量超过上限");
            }
            if (stack.getCount() > stack.getMaxStackSize()) {
                throw new IllegalArgumentException("物品堆叠数量超过该物品的上限");
            }
            long unit = unitGrams.applyAsLong(stack);
            if (unit < 0 || unit > WeightRule.MAX_UNIT_GRAMS) {
                throw new IllegalArgumentException("单件重量超出范围");
            }
            long own = Math.multiplyExact(unit, stack.getCount());
            if (ContainerItems.definition(stack) == null) {
                return own;
            }

            // 嵌套上限针对容器层数，最深容器内的普通物品仍须正常计重。
            if (depth > ContainerItems.MAX_NESTING_DEPTH) {
                throw new IllegalArgumentException("容器嵌套层数超过上限");
            }

            String id = ContainerItems.identity(stack);
            if (!identities.add(id)) {
                throw new IllegalArgumentException("容器实例身份重复：" + id);
            }
            if (identities.size() > ContainerItems.MAX_CONTAINERS) {
                throw new IllegalArgumentException("容器数量超过上限");
            }
            long contents = 0;
            for (var item : ContainerItems.contents(stack).getItemsList()) {
                var child = StackCodec.decode(item.getStackNbt(), registries);
                contents = Math.addExact(contents, stack(child, carried, depth + 1));
            }
            long total = Math.addExact(own, contents);
            containers.add(ContainerWeight.newBuilder()
                    .setContainerId(id)
                    .setTitle(stack.getHoverName().getString())
                    .setContentsGrams(contents)
                    .setTotalGrams(total)
                    .setCarried(carried)
                    .build());
            return total;
        }
    }
}
