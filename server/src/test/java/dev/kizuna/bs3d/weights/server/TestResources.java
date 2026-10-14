package dev.kizuna.bs3d.weights.server;

import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourcePack;
import net.minecraft.util.Identifier;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;
/** 内存资源目录，仅模拟规则加载用到的 ResourceManager 行为。 */
record TestResources(Map<Identifier, Resource> files) implements ResourceManager {
    @Override
    public Map<Identifier, Resource> findResources(String prefix, Predicate<Identifier> filter) {
        // 按目录和文件筛选，保持输入顺序，让规则优先级测试独立于加载次序。
        var found = new LinkedHashMap<Identifier, Resource>();
        for (var entry : files.entrySet()) {
            var id = entry.getKey();
            if (id.getPath().startsWith(prefix + "/") && filter.test(id)) {
                found.put(id, entry.getValue());
            }
        }
        return found;
    }

    @Override
    public Optional<Resource> getResource(Identifier id) {
        return Optional.ofNullable(files.get(id));
    }

    @Override
    public Set<String> getAllNamespaces() {
        return Set.of("test");
    }

    @Override
    public List<Resource> getAllResources(Identifier id) {
        return getResource(id).stream().toList();
    }

    @Override
    public Stream<ResourcePack> streamResourcePacks() {
        return Stream.empty();
    }

    @Override
    public Map<Identifier, List<Resource>> findAllResources(String prefix, Predicate<Identifier> filter) {
        throw new UnsupportedOperationException("规则加载不使用资源包堆叠");
    }
}
