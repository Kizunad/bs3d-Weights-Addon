#!/usr/bin/env python3
"""从固定 Bong 物品定义导入有直接 ID 对应的容器自重，不推测其他模型的重量。"""

import argparse
from decimal import Decimal
import hashlib
import json
from pathlib import Path
import subprocess
import tomllib

ROOT = Path(__file__).resolve().parents[1]
COMMIT = "c01576513e451490bd73df12513227bb8637222b"
SOURCE = "server/assets/items/workbench_materials.toml"
BLOB = "870f45af7da2113f37393588243f0b9f01c47652"
IDS = ("herb_pouch", "coin_box", "moisture_guard", "ore_sack", "water_skin")
GRAMS_PER_KILOGRAM = 1000
OUTPUT = ROOT / "common/src/main/resources/data/bs3d_weights/bs3d_weights/weights"


def outputs(bong):
    raw = subprocess.check_output(["git", "-C", str(bong), "show", f"{COMMIT}:{SOURCE}"])
    digest = hashlib.sha1(b"blob " + str(len(raw)).encode() + b"\0" + raw).hexdigest()
    if digest != BLOB:
        raise ValueError(f"Bong 源对象不符：{digest}")
    items = {item["id"]: item for item in tomllib.loads(raw.decode(), parse_float=Decimal)["item"]}
    result = {}
    for item_id in IDS:
        grams = items[item_id]["base_weight"] * GRAMS_PER_KILOGRAM
        if grams != int(grams):
            raise ValueError(f"{item_id} 的原始重量不能精确转换为整数克")
        rule = {"item": f"bs3d:{item_id}", "grams": int(grams), "priority": 0}
        result[OUTPUT / f"bong_{item_id}.json"] = json.dumps(rule, ensure_ascii=False, indent=2) + "\n"
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bong", type=Path, default=ROOT.parent / "Bong")
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    for path, text in outputs(args.bong).items():
        if args.check:
            if not path.is_file() or path.read_text() != text:
                raise SystemExit(f"输出与固定来源不同：{path}")
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text)
    print(f"固定 Bong 重量检查通过：{len(IDS)} 件，commit={COMMIT}，blob={BLOB}")


if __name__ == "__main__":
    main()
