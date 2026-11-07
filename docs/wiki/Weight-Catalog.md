# 穿搭与容器重量来源

本表区分已有来源与待定数值。待定物品继续使用已有数据包覆盖或默认 **100 g** 回退；
这个回退值不表示实物重量已确定。本轮不改变 `burden.toml` 的任何系数。

## 有直接来源的容器

来源为 Bong 固定提交 `c01576513e451490bd73df12513227bb8637222b` 的
`server/assets/items/workbench_materials.toml`，blob `870f45af7da2113f37393588243f0b9f01c47652`。
按原物品相同 ID 将 `base_weight` 从 kg 精确转换为整数 g，只取空容器自重，不取容量上限。

| BS3D ID | 自重 |
|---|---|
| `bs3d:herb_pouch` | 300 g |
| `bs3d:coin_box` | 800 g |
| `bs3d:moisture_guard` | 100 g |
| `bs3d:ore_sack` | 300 g |
| `bs3d:water_skin` | 400 g |

随模组提供普通数据包规则，仍遵守[规则优先级](Weight-Rules.md)。
`python3 scripts/import_bong_weights.py --check` 检查固定 Git 对象和输出；省略 `--check` 可重现这五个 JSON。
该脚本不读取 Bong 未提交文件，不修改模型或容量。

## 待定清单

| 范围 | 缺少的依据 |
|---|---|
| `bs3d:gu_jia_bei_lou`、`za_pi_xing_nang`、`xiu_kuang_bei_jia`、`pi_nang_xiong_gua` | 固定源模型没有可直接对应的物品自重定义 |
| `bs3d:ku_teng_yao_dai` | 同上；不能把藤材料重量当作整条带挂件腰带的重量 |
| `bs3d_gear:can_bu_tou_jin`、`cu_bu_bang_tui`、`cu_bu_mian_jin`、`cu_ma_dou_peng`、`gu_pian_jian_jia`、`ku_cao_guo_jiao`、`pi_sheng_hu_wan`、`shou_ya_xiang_lian`、`xiu_pian_hu_mu` | 尚无认可的整件重量 |
| `bs3d_gear:war_helm`、`war_chest`、`war_legs`、`war_cloak`、`war_suit` | 固定 War 模型无自重；Bong 的 `armor_iron_*` 是另一组物品，尚无认可映射；铁甲级防御不等于相同质量 |
| 原版皮革、锁链、铜、铁、金、钻石、下界合金盔甲及海龟壳；原版动物身体甲 | Minecraft 原生定义无克数，现有 Weights 无认可单重；不能用护甲值或配方原料数量推算 |

WarChest 是已有 War 套资源，未新增胸甲单件品类。WarLegs 的腿和靴、WarSuit 的四甲层与披风
都随同一个实际栈计一次自重，不以模型段数量或独立件重量相加替代待定整件重量。

## 计重入口

所有真实 BS3D 穿搭根（含零防御外观件）计入 `armor` 来源；容器继续使用 `worn_containers`。
原版四个人体甲槽、26.2 的身体甲与鞍槽也计入 `armor`，副手仍属 `offhand`。
多层穿搭、原版库存、光标和容器嵌套共享实例去重校验；重复 UUID 拒绝整次读取。
管理员可用[数据包](Weight-Rules.md)为待定物品填入自己认可的数值，无需修改模型或附属源码。
