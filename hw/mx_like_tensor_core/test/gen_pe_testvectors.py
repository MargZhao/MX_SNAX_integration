#!/usr/bin/env python3
# Copyright 2024 KU Leuven.
# SPDX-License-Identifier: Apache-2.0
#
# Per-PE (BFP_PE / FDPU) test-vector generator.
#
# Reuses the *production* snax-mx quantization pipeline — `gen_workload`
# (transformer-like "fitted" distribution) + `quantize_mx_v6` (NVFP4-style
# round-up block scale) — to produce a single length-K dot-product accumulation
# for one processing element, then serialises it into per-accumulate-cycle
# hardware stimulus (op_a / op_b / scaleA / scaleB) plus a float64 golden.
#
# The golden is the IDEAL dequantized dot product (double precision).  The
# current mx_like_tensor_core PE is lossy (anchored fixed-point tree + early
# RNE + narrow-FP FusedScaleAccumulator), so the Scala/SV consumers compare the
# decoded accOut against this golden within a relative tolerance — the same
# oracle style as the (disabled) FDPUPostScaleReductionTreeTest.
#
# NOTE on distribution: mirrors gen_workload(mode="fitted") from
#   target/snitch_cluster/sw/apps/snax-mx/data/datagen.py
# with act_std / weight_std defaulting to the pe_vcd_merged_manifest values
# (sigma_act=0.372, sigma_w=0.0366).

import argparse
import json
import os
import sys
import numpy as np

# --- Import the production quantizer -----------------------------------------
_SNAX_MX_DATA = os.path.join(
    os.path.dirname(__file__),
    "../../../target/snitch_cluster/sw/apps/snax-mx/data",
)
sys.path.insert(0, os.path.abspath(_SNAX_MX_DATA))
import quantize  # noqa: E402


class _ScaleFormatN(quantize.ScaleFormat):
    """ScaleFormat variant that allows ebits + mbits != 8 (e.g. 7-bit UE4M3).

    quantize.ScaleFormat asserts an 8-bit total, but the hardware also supports
    the 7-bit UE4M3 (ScaleType(4,3), bias=7).  The generic UExMy encode/decode
    (quantize_round_up + _raw_to_float) is width-agnostic, so we reuse it and
    only relax the width assertion; the fields below mirror ScaleFormat's
    ebits>0,mbits>0 branch exactly.  Correctness is confirmed by the per-combo
    near-zero-error self-check (HW decode == this encode).
    """
    def __init__(self, ebits: int, mbits: int):
        assert ebits > 0 and mbits > 0
        self.ebits = ebits
        self.mbits = mbits
        self.bias = (1 << (ebits - 1)) - 1
        max_exp_b = (1 << ebits) - 1
        max_mant = (1 << mbits) - 1
        self.max_val = (2.0 ** (max_exp_b - self.bias)) * (1.0 + max_mant / (2 ** mbits))
        self.saturate_raw = (max_exp_b << mbits) | max_mant
        self.min_raw = 1
        self.min_val = (2.0 ** (1 - self.bias)) / (2 ** mbits)


# Register UE4M3 (7-bit; HW ScaleType(4,3)) — the production table only holds
# 8-bit scale formats.
if "UE4M3" not in quantize.VALID_SCALE_FORMATS:
    quantize.VALID_SCALE_FORMATS["UE4M3"] = _ScaleFormatN(4, 3)

# Authoritative M_acc source = data/macc_final_selection.csv (same table the
# deployed EmitTensorCore reads). The K-based AccPrecision heuristic is gone.
_MACC_CSV = os.path.join(os.path.dirname(__file__), "../data/macc_final_selection.csv")


def _load_macc_csv(path=_MACC_CSV):
    import csv
    m = {}
    with open(path) as f:
        r = csv.DictReader(f)
        for row in r:
            a = row.get("act") or row.get("typeA")
            w = row.get("weight") or row.get("typeB")
            s = row.get("scale")
            v = row.get("m_acc") or row.get("M_sel")
            if a and w and s and v:
                m[(a, w, s)] = int(v)
    return m


_MACC = _load_macc_csv()


# Manifest element name (E4M3, INT8, …) → quantize dtype string.
NAME2DTYPE = {
    "E5M2": "fp8_e5m2",
    "E4M3": "fp8_e4m3",
    "E3M2": "fp6_e3m2",
    "E2M3": "fp6_e2m3",
    "E2M1": "fp4_e2m1",
    "INT8": "mxint8",
}


def gen_workload_fitted(K, *, seed, act_std, weight_std,
                        outlier_frac=0.05, outlier_gain=(8.0, 32.0)):
    """Single-PE operands: A[1,K], B[K,1].

    Verbatim port of gen_workload(mode="fitted") for M=N=1 — transformer-like
    statistics: clean Gaussian weights + heavy-tailed outlier activation
    channels.  The outliers spread per-block shared exponents (realistic MXFP
    switching activity).
    """
    rng = np.random.default_rng(seed)
    B = (rng.standard_normal((K, 1)) * weight_std).astype(np.float32)
    A = (rng.standard_normal((1, K)) * act_std).astype(np.float32)
    n_out = max(1, int(round(outlier_frac * K)))
    cols = rng.choice(K, size=n_out, replace=False)
    gains = rng.uniform(outlier_gain[0], outlier_gain[1], size=n_out).astype(np.float32)
    A[:, cols] *= gains
    return A, B


def gen_workload_gaussian(K, *, seed, variance):
    rng = np.random.default_rng(seed)
    A = (rng.standard_normal((1, K)) * variance).astype(np.float32)
    B = (rng.standard_normal((K, 1)) * variance).astype(np.float32)
    return A, B


def build_vector(act, weight, scale, *, K, block_size, vector_size,
                 seed, workload, act_std, weight_std, variance):
    if act not in NAME2DTYPE or weight not in NAME2DTYPE:
        raise ValueError(f"Unknown element format {act}/{weight}")
    if scale not in quantize.VALID_SCALE_FORMATS:
        raise ValueError(
            f"scale {scale!r} not in quantize.VALID_SCALE_FORMATS "
            f"({list(quantize.VALID_SCALE_FORMATS)}) — cannot generate."
        )
    if K % block_size != 0:
        raise ValueError(f"K({K}) must be a multiple of block_size({block_size})")
    if block_size % vector_size != 0:
        raise ValueError(f"block_size({block_size}) must be a multiple of vector_size({vector_size})")

    if (act, weight, scale) not in _MACC:
        raise ValueError(f"({act},{weight},{scale}) not in {_MACC_CSV} — cannot resolve M_acc")
    m_acc = _MACC[(act, weight, scale)]

    dtA, dtB = NAME2DTYPE[act], NAME2DTYPE[weight]
    wA = quantize._DTYPE_BITS.get(dtA, 8)
    wB = quantize._DTYPE_BITS.get(dtB, 8)
    _sf = quantize.VALID_SCALE_FORMATS[scale]
    scale_w = _sf.ebits + _sf.mbits   # 8 for the OCP formats, 7 for UE4M3

    if workload == "gaussian":
        A_fp32, B_fp32 = gen_workload_gaussian(K, seed=seed, variance=variance)
    else:
        A_fp32, B_fp32 = gen_workload_fitted(K, seed=seed,
                                             act_std=act_std, weight_std=weight_std)

    # Block quantization (production path).  A: row-wise blocks (axis=1);
    # B: column-wise blocks (axis=0).  packed=False → one raw byte per element.
    qA, _, scA_raw, rawA = quantize.quantize_mx_v6(
        A_fp32, dtA, block_size=block_size, axis=1, scale_format=scale)
    qB, _, scB_raw, rawB = quantize.quantize_mx_v6(
        B_fp32, dtB, block_size=block_size, axis=0, scale_format=scale)

    A_deq = qA[0, :].astype(np.float64)    # [K]
    B_deq = qB[:, 0].astype(np.float64)    # [K]
    golden_dot = float(np.dot(A_deq, B_deq))

    n_cycles = K // vector_size
    maskA, maskB = (1 << wA) - 1, (1 << wB) - 1
    cycles = []
    for c in range(n_cycles):
        blk = (c * vector_size) // block_size
        op_a = 0
        op_b = 0
        for i in range(vector_size):
            k = c * vector_size + i
            op_a |= (int(rawA[0, k]) & maskA) << (i * wA)
            op_b |= (int(rawB[k, 0]) & maskB) << (i * wB)
        cycles.append({
            "op_a": str(op_a),                      # decimal, up to vec*wA bits
            "op_b": str(op_b),
            "scaleA": int(scA_raw[0, blk]),
            "scaleB": int(scB_raw[blk, 0]),
        })

    return {
        "act": act, "weight": weight, "scale": scale,
        "m_acc": m_acc,                              # from macc_final_selection.csv
        "wA": wA, "wB": wB, "scale_width": scale_w,
        "K": K, "block_size": block_size, "vector_size": vector_size,
        "n_cycles": n_cycles,
        "seed": seed, "workload": workload,
        "act_std": act_std, "weight_std": weight_std,
        "golden_dot": golden_dot,
        "A_deq": [float(x) for x in A_deq],
        "B_deq": [float(x) for x in B_deq],
        "cycles": cycles,
    }


def main():
    ap = argparse.ArgumentParser(description="Per-PE test-vector generator")
    ap.add_argument("--act", required=True)
    ap.add_argument("--weight", required=True)
    ap.add_argument("--scale", required=True)
    ap.add_argument("--K", type=int, default=64)
    ap.add_argument("--block-size", type=int, default=16)
    ap.add_argument("--vector-size", type=int, default=4)
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--workload", default="fitted", choices=["fitted", "gaussian"])
    ap.add_argument("--act-std", type=float, default=0.372)      # manifest sigma_act
    ap.add_argument("--weight-std", type=float, default=0.0366)  # manifest sigma_w
    ap.add_argument("--variance", type=float, default=1.0)
    ap.add_argument("--outdir", default=os.path.join(os.path.dirname(__file__), "vectors"))
    args = ap.parse_args()

    vec = build_vector(
        args.act, args.weight, args.scale,
        K=args.K, block_size=args.block_size, vector_size=args.vector_size,
        seed=args.seed, workload=args.workload,
        act_std=args.act_std, weight_std=args.weight_std, variance=args.variance,
    )
    os.makedirs(args.outdir, exist_ok=True)
    base = f"{args.act}_{args.weight}_{args.scale}_K{args.K}_bs{args.block_size}_vec{args.vector_size}_seed{args.seed}"
    jpath = os.path.join(args.outdir, base + ".json")
    with open(jpath, "w") as f:
        json.dump(vec, f, indent=1)
    write_tv(os.path.join(args.outdir, base + ".tv"), vec)
    print(f"[gen] {jpath}  golden_dot={vec['golden_dot']:.6g}  cycles={vec['n_cycles']}")


def write_tv(path, vec):
    """Dependency-free flat text for the Scala chiseltest consumer.

    Header 'key value' lines, then a blank line, then one 'op_a op_b scaleA
    scaleB' line per accumulate cycle (op_a/op_b are decimal, LSB = lane 0).
    """
    with open(path, "w") as f:
        for k in ("act", "weight", "scale", "m_acc", "wA", "wB", "scale_width",
                  "K", "block_size", "vector_size", "n_cycles",
                  "seed", "workload"):
            f.write(f"{k} {vec[k]}\n")
        f.write(f"golden_dot {vec['golden_dot']!r}\n")
        f.write("\n")
        for cy in vec["cycles"]:
            f.write(f"{cy['op_a']} {cy['op_b']} {cy['scaleA']} {cy['scaleB']}\n")


if __name__ == "__main__":
    main()
