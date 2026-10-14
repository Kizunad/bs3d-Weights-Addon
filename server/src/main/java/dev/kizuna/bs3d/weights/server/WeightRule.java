package dev.kizuna.bs3d.weights.server;

import com.google.gson.JsonObject;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.util.Identifier;
import net.minecraft.util.JsonHelper;

/**
 * 一个规则恰好选择一种物品或一个 tag。
 *
 * @param resource 规则文件的资源 ID，同优先级时按此 ID 排序
 * @param item 注册物品类型 ID，与 tag 互斥，不是容器实例 UUID
 * @param tag 原版物品标签，与 item 互斥
 * @param grams 单件自重，单位克，不含容器内容
 * @param priority 匹配优先级，越大越先匹配
 */
record WeightRule(
        Identifier resource,
        Identifier item,
        TagKey<Item> tag,
        long grams,
        int priority) {
    static final long MAX_UNIT_GRAMS = 1_000_000_000L;

    static WeightRule read(Identifier resource, JsonObject json) {
        // 先确定选择器，再读取有明确单位的重量，最后读取匹配优先级。
        if (json.has("item") == json.has("tag")) {
            throw new IllegalArgumentException("必须且只能设置 item 或 tag");
        }
        Identifier item = null;
        TagKey<Item> tag = null;
        if (json.has("item")) {
            item = identifier(json, "item");
            if (!Registries.ITEM.containsId(item)) {
                throw new IllegalArgumentException("item 未注册：" + item);
            }
        } else {
            tag = TagKey.of(RegistryKeys.ITEM, identifier(json, "tag"));
        }
        long grams = grams(json);
        int priority = 0;
        if (json.has("priority")) {
            long configured = integer(json, "priority");
            if (configured < Integer.MIN_VALUE || configured > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("priority 必须是 int32 范围内的整数");
            }
            priority = (int) configured;
        }
        return new WeightRule(resource, item, tag, grams, priority);
    }

    static long grams(JsonObject json) {
        // 注册字段与数据包采用同一单位和边界，不能因配置入口不同而绕过校验。
        long grams = integer(json, "grams");
        if (grams < 0 || grams > MAX_UNIT_GRAMS) {
            throw new IllegalArgumentException("grams 必须在 0.." + MAX_UNIT_GRAMS + " 克之间");
        }
        return grams;
    }

    private static Identifier identifier(JsonObject json, String field) {
        // 无效 ID 连同字段名报错，不能把拼错的物品名当作默认重量。
        var id = Identifier.tryParse(JsonHelper.getString(json, field));
        if (id == null) {
            throw new IllegalArgumentException(field + " 必须是有效的资源 ID");
        }
        return id;
    }

    private static long integer(JsonObject json, String field) {
        // 拒绝字符串、小数和溢出，避免 getAsLong 静默截断配置。
        var value = json.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(field + " 必须是 JSON 整数");
        }
        try {
            return value.getAsBigDecimal().longValueExact();
        } catch (ArithmeticException invalid) {
            throw new IllegalArgumentException(field + " 必须是 int64 范围内的整数", invalid);
        }
    }

    boolean matches(ItemStack stack) {
        // 选择器在读取时已保证互斥；按注册类型或原版标签匹配，不使用展示名。
        return item != null ? Registries.ITEM.getId(stack.getItem()).equals(item) : stack.isIn(tag);
    }
}
