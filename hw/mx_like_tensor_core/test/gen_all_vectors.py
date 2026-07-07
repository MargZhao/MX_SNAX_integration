#!/usr/bin/env python3
# Copyright 2024 KU Leuven.
# SPDX-License-Identifier: Apache-2.0
#
# Batch-generate per-PE test vectors for every (act, weight, scale) combo in
# data/pe_vcd_merged_manifest.csv, restricted to act-precision >= weight-precision
# (ordering E2M1 < E2M3 < E3M2 < E4M3 < E5M2 < INT8).  UE4M3 (7-bit scale) is
# registered by gen_pe_testvectors.

import csv
import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
import gen_pe_testvectors as g

MANIFEST = os.path.join(os.path.dirname(__file__), "../data/pe_vcd_merged_manifest.csv")
PREC = ["E2M1", "E2M3", "E3M2", "E4M3", "E5M2", "INT8"]  # ascending precision
IDX = {n: i for i, n in enumerate(PREC)}

K, BS, VEC, SEED = 64, 16, 4, 0
OUTDIR = os.path.join(os.path.dirname(__file__), "vectors")


def main():
    rows = list(csv.DictReader(open(MANIFEST)))
    combos = sorted({(r["act"], r["weight"], r["scale"]) for r in rows})
    kept, skipped = [], []
    for (a, w, s) in combos:
        if IDX[a] < IDX[w]:
            skipped.append((a, w, s, "act<weight"))
            continue
        kept.append((a, w, s))

    print(f"manifest unique combos: {len(combos)}  kept (act>=weight): {len(kept)}  skipped: {len(skipped)}")
    os.makedirs(OUTDIR, exist_ok=True)
    fails = []
    for (a, w, s) in kept:
        try:
            vec = g.build_vector(a, w, s, K=K, block_size=BS, vector_size=VEC,
                                 seed=SEED, workload="fitted",
                                 act_std=0.372, weight_std=0.0366, variance=1.0)
            base = f"{a}_{w}_{s}_K{K}_bs{BS}_vec{VEC}_seed{SEED}"
            import json
            json.dump(vec, open(os.path.join(OUTDIR, base + ".json"), "w"), indent=1)
            g.write_tv(os.path.join(OUTDIR, base + ".tv"), vec)
        except Exception as e:
            fails.append((a, w, s, str(e)))
    print(f"generated {len(kept) - len(fails)} vectors into {OUTDIR}")
    for f in fails:
        print("  FAIL:", f)
    if skipped:
        print(f"skipped {len(skipped)} act<weight combos (not in HW scope)")


if __name__ == "__main__":
    main()
