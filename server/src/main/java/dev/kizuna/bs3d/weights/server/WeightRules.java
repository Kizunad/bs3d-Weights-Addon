package dev.kizuna.bs3d.weights.server;

import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.item.ItemStack;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import net.minecraft.util.JsonHelper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** /reload 后整体替换服务端规则；不向客户端发送或信任客户端重量配置。 */
public final class WeightRules implements SimpleSynchronousResourceReloadListener {
    /** 未登记物品的单件自重，单位克。 */
    public static final long DEFAULT_UNIT_GRAMS = 100;
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
        // 按优先级选取一条规则；未登记物品仍可使用，采用文档公开的默认自重。
        for (var rule : rules) {
            if (rule.matches(stack)) {
                return rule.grams();
            }
        }
        return DEFAULT_UNIT_GRAMS;
    }
}
