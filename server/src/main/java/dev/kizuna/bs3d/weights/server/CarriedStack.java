package dev.kizuna.bs3d.weights.server;

import java.util.Objects;
import net.minecraft.world.item.ItemStack;

/**
 * 一件随身根物品及其全部内容所属的有效负重来源。
 *
 * <p>这里只保存栈引用，不是深不可变快照。调用方在服务器主线程组装清单，计算返回前不改栈；
 * {@link Burden#measure} 复制后读取，不修改或留存调用方的物品。每件实物只列一次，不另列容器内容。
 */
public record CarriedStack(ItemStack stack, BurdenSource source) {
    public CarriedStack {
        // 缺少栈或来源是调用参数错误，不能当作零克或库存损坏。
        Objects.requireNonNull(stack, "stack");
        Objects.requireNonNull(source, "source");
    }
}
