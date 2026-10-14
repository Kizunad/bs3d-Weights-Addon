package dev.kizuna.bs3d.weights.server;

import dev.kizuna.backpack3d.item.ContainerItems;
import dev.kizuna.backpack3d.protocol.StackCodec;
import dev.kizuna.backpack3d.server.InventoryAccess;
import dev.kizuna.bs3d.weights.protocol.pb.WeightsProtocol.ContainerWeight;
import dev.kizuna.bs3d.weights.protocol.pb.WeightsProtocol.Status;
import dev.kizuna.bs3d.weights.protocol.pb.WeightsProtocol.WeightState;
import net.minecraft.item.ItemStack;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.ToLongFunction;

/** 从真实库存树自底向上累计，每个根只加入总重一次。 */
public final class WeightCalculator {
    // 每次计算允许访问的非空物品栈数量，限制损坏库存带来的遍历工作量。
    private static final int MAX_VISITED_STACKS = 4096;
    private final ToLongFunction<ItemStack> unitGrams;

    public WeightCalculator(ToLongFunction<ItemStack> unitGrams) {
        this.unitGrams = unitGrams;
    }

    public WeightState calculate(InventoryAccess.Snapshot inventory, ItemStack cursor) {
        // 每次计算使用独立遍历状态；损坏树必须抛错，不能发布看似正常的部分总重。
        var walk = new Walk();
        long total = 0;
        for (var stack : inventory.playerSlots()) {
            total = Math.addExact(total, walk.stack(stack, true, 0));
        }
        total = Math.addExact(total, walk.stack(cursor, true, 0));
        for (var worn : inventory.equipment().getWornList()) {
            total = Math.addExact(total, walk.stack(StackCodec.decode(worn.getStackNbt()), true, 0));
        }
        // 地面容器仅产生明细，其子树也带 carried=false，不能混入随身总重。
        for (var stack : inventory.ground().values()) {
            walk.stack(stack, false, 0);
        }
        walk.containers.sort(Comparator.comparing(ContainerWeight::getContainerId));
        return WeightState.newBuilder()
                .setStatus(Status.OK)
                .setTotalGrams(total)
                .addAllContainers(walk.containers)
                .build();
    }

    private final class Walk {
        private final Set<String> identities = new HashSet<>();
        private final List<ContainerWeight> containers = new ArrayList<>();
        private int visitedStacks;

        private long stack(ItemStack stack, boolean carried, int depth) {
            // 空槽不计重；每件非空栈限制工作量与堆叠数量。
            if (stack.isEmpty()) {
                return 0;
            }
            visitedStacks++;
            if (visitedStacks > MAX_VISITED_STACKS) {
                throw new IllegalArgumentException("重量计算访问的物品栈数量超过上限");
            }
            if (stack.getCount() > stack.getMaxCount()) {
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
                var child = StackCodec.decode(item.getStackNbt());
                contents = Math.addExact(contents, stack(child, carried, depth + 1));
            }
            long total = Math.addExact(own, contents);
            containers.add(ContainerWeight.newBuilder()
                    .setContainerId(id)
                    .setTitle(stack.getName().getString())
                    .setContentsGrams(contents)
                    .setTotalGrams(total)
                    .setCarried(carried)
                    .build());
            return total;
        }
    }
}
