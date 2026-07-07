#!/usr/bin/env python3
# Copyright 2024 KU Leuven.
# SPDX-License-Identifier: Apache-2.0
#
# Batch: for every emitted PE under generated/pe_tb/<combo>/ (BFP_PE.sv +
# pe_meta.txt), generate tb_BFP_PE.sv from the matching test/vectors/<combo>.json,
# then (optionally) compile+run with Verilator to produce the power VCD and a
# functional self-check.
#
# Usage:
#   python3 test/gen_all_sv.py            # generate tbs only
#   python3 test/gen_all_sv.py --run      # also verilate + run each (slow)

import argparse
import glob
import json
import os
import subprocess
import sys

sys.path.insert(0, os.path.dirname(__file__))
import gen_pe_sv_testbench as sv

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
PE_TB = os.path.join(ROOT, "generated", "pe_tb")
VECS  = os.path.join(ROOT, "test", "vectors")
K, BS, VEC, SEED = 64, 16, 4, 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--run", action="store_true", help="verilate + run each tb (produces VCD)")
    args = ap.parse_args()

    pedirs = sorted(d for d in glob.glob(os.path.join(PE_TB, "*")) if os.path.isdir(d))
    made, results = 0, []
    for pedir in pedirs:
        meta = sv.read_meta(pedir)
        combo = f"{meta['act']}_{meta['weight']}_{meta['scale']}"
        jpath = os.path.join(VECS, f"{combo}_K{K}_bs{BS}_vec{VEC}_seed{SEED}.json")
        if not os.path.exists(jpath):
            print(f"  SKIP {combo}: no vector {jpath}")
            continue
        vec = json.load(open(jpath))
        sv.emit_tb(vec, meta, pedir)
        made += 1
        if args.run:
            r = subprocess.run(
                ["verilator", "--binary", "--timing", "--trace", "-Wno-fatal",
                 "--top-module", "tb_BFP_PE", "BFP_PE.sv", "tb_BFP_PE.sv"],
                cwd=pedir, capture_output=True, text=True)
            if r.returncode != 0:
                results.append((combo, "VERILATE_FAIL", r.stderr[-400:]))
                continue
            run = subprocess.run(["./obj_dir/Vtb_BFP_PE"], cwd=pedir,
                                 capture_output=True, text=True)
            line = next((l for l in run.stdout.splitlines() if "relErr" in l), "")
            verdict = "PASS" if "RESULT: PASS" in run.stdout else "FAIL"
            results.append((combo, verdict, line.strip()))

    print(f"\n[gen_all_sv] generated {made} testbenches under {PE_TB}")
    if args.run:
        npass = sum(1 for _, v, _ in results if v == "PASS")
        print(f"[gen_all_sv] verilator: {npass}/{len(results)} PASS")
        for combo, v, info in results:
            if v != "PASS":
                print(f"  {v}  {combo}  {info}")


if __name__ == "__main__":
    main()
