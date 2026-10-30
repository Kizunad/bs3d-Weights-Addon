package dev.kizuna.bs3d.weights.server;

/** 有效负重的计重来源；配置键与 burden.toml 一一对应。 */
enum BurdenSource {
    HOTBAR("hotbar"),
    MAIN("main"),
    ARMOR("armor"),
    OFFHAND("offhand"),
    CURSOR("cursor"),
    WORN_CONTAINERS("worn_containers");

    private final String key;

    BurdenSource(String key) {
        this.key = key;
    }

    String key() {
        return key;
    }
}
