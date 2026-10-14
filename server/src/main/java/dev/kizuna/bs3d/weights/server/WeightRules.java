package dev.kizuna.bs3d.weights.server;

import com.google.gson.JsonParser;
import dev.kizuna.backpack3d.item.ItemMetadata;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import net.minecraft.util.JsonHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;

/** /reload 后整体替换服务端规则；不向客户端发送或信任客户端重量配置。 */
public final class WeightRules implements SimpleSynchronousResourceReloadListener {
    /** BS3D optional 中由本附属解释的字段，值为包含 grams 的 JSON 对象。 */
    public static final Identifier WEIGHT_FIELD = new Identifier("bs3d_weights", "weight");
    /** 未登记物品的单件自重，单位克。 */
    public static final long DEFAULT_UNIT_GRAMS = 100;
    private static final Logger LOGGER = LoggerFactory.getLogger(WeightRules.class);
    // 按物品类型缓存注册字段解析结果；资源重载可在工作线程执行，因此缓存支持并发清理。
    private final Map<Item, OptionalLong> registeredWeights = new ConcurrentHashMap<>();
    private volatile List<WeightRule> rules = List.of();

    @Override
    public Identifier getFabricId() {
        return new Identifier("bs3d_weights", "rules");
    }

    @Override
    public void reload(ResourceManager resources) {
        // 所有文件都成功后才发布新目录，重载失败时保留上一份可用规则。
        var next = new ArrayList<WeightRule>();
        var files = resources.findResources("bs3d_weights/weights", id -> id.getPath().endsWith(".json"));
        for (var entry : files.entrySet()) {
            next.add(read(entry.getKey(), entry.getValue()));
        }
        next.sort(Comparator.comparingInt(WeightRule::priority)
                .reversed()
                .thenComparing(WeightRule::resource));
        rules = List.copyOf(next);
        registeredWeights.clear();
    }

    private static WeightRule read(Identifier id, Resource resource) {
        // 文件层只负责关闭资源与补充路径，字段解析的意图留在 WeightRule 中。
        try (var reader = resource.getReader()) {
            var json = JsonHelper.asObject(JsonParser.parseReader(reader), "重量规则");
            return WeightRule.read(id, json);
        } catch (IOException | RuntimeException failure) {
            throw new IllegalArgumentException("无法加载重量规则 " + id + "：" + failure.getMessage(), failure);
        }
    }

    public long unitGrams(ItemStack stack) {
        // 空槽不匹配字段或告警。只读取本进程的类型注册，物品 NBT 不能覆盖权威重量。
        if (stack.isEmpty()) {
            return 0;
        }
        OptionalLong registered = registeredWeights.computeIfAbsent(stack.getItem(), this::readRegistered);
        if (registered.isPresent()) {
            return registered.getAsLong();
        }
        // 缺少或无效的附属字段已记录 WARN；先回退到现有数据包规则，再使用默认自重。
        for (var rule : rules) {
            if (rule.matches(stack)) {
                return rule.grams();
            }
        }
        return DEFAULT_UNIT_GRAMS;
    }

    private OptionalLong readRegistered(Item item) {
        // 每轮规则加载中每种物品只解析一次，避免服务端每次 tick 重复刷出相同警告。
        Identifier itemId = Registries.ITEM.getId(item);
        var field = ItemMetadata.of(new ItemStack(item)).optional(WEIGHT_FIELD);
        if (field.isEmpty()) {
            LOGGER.warn("物品 {} 未注册 optional 字段 {}；回退到数据包规则，无匹配时使用默认 {} 克",
                    itemId, WEIGHT_FIELD, DEFAULT_UNIT_GRAMS);
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(WeightRule.grams(field.get()));
        } catch (IllegalArgumentException invalid) {
            LOGGER.warn("物品 {} 的 optional 字段 {} 无效：{}；回退到数据包规则，无匹配时使用默认 {} 克",
                    itemId, WEIGHT_FIELD, invalid.getMessage(), DEFAULT_UNIT_GRAMS);
            return OptionalLong.empty();
        }
    }
}
