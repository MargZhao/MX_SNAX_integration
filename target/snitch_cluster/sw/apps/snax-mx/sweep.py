#!/usr/bin/env python3
# Copyright 2024 KU Leuven.
# Licensed under the Apache License, Version 2.0, see LICENSE for details.
# SPDX-License-Identifier: Apache-2.0
#
# sweep.py — multi-variant RTL generation driver.
#
# Reads a top-level sweep config (default: data/sweep.hjson) describing a
# Cartesian product of params.hjson overrides, then for each variant runs
# orchestrator.py + `make rtl-gen` with per-variant output paths so the
# generated trees do not overlap.
#
# Outputs per variant <v>:
#   hw/snax_mx_alu/source_<v>/                — PE_Array.sv + PE_Array_wrapper.sv
#   target/snitch_cluster/cfg/snax_mx_cluster_<v>.hjson      — patched hjson
#   target/snitch_cluster/cfg/snax_mx_defines_<v>.mk         — RTL define flags
#   target/snitch_cluster/sw/apps/snax-mx/data/params_<v>.hjson  — variant params
#   target/snitch_cluster/sw/apps/snax-mx/data/data_<v>.h        — test data
#   target/snitch_cluster/variants/<v>/generated/            — streamer/cluster wrappers
#   target/snitch_cluster/variants/<v>/test/testharness.sv

import argparse
import itertools
import shutil
import subprocess
import sys
from pathlib import Path

import hjson

SCRIPT_DIR     = Path(__file__).parent.resolve()
TARGET_DIR     = (SCRIPT_DIR / "../../..").resolve()      # target/snitch_cluster
REPO_ROOT      = (TARGET_DIR / "../..").resolve()
ORCHESTRATOR   = SCRIPT_DIR / "orchestrator.py"
HWCFG_TEMPLATE = TARGET_DIR / "cfg" / "snax_mx_cluster_template.hjson"
DEFAULT_SWEEP  = SCRIPT_DIR / "data" / "sweep.hjson"
PE_SOURCE_DIR  = REPO_ROOT / "hw" / "snax_mx_alu" / "source"
# Files in PE_SOURCE_DIR that orchestrator (re)writes per variant. Anything
# else in that dir is treated as a static utility (e.g. package, hand-written
# parameterized shell wrapper) and copied verbatim into each source_<v>/ so
# the variant directory is self-contained for off-box synthesis.
ORCH_OUTPUTS   = {"PE_Array.sv", "PE_Array_wrapper.sv"}

# Each entry maps a synth target name to (top_module, [files…]). File paths
# are templated with `<v>` (variant name) and resolved against REPO_ROOT,
# then written into the flist as repo-root-relative paths so the variant
# directory remains portable across machines.
SYNTH_TARGETS = {
    "pe_array": (
        "PE_Array",
        ["hw/snax_mx_alu/source_<v>/PE_Array.sv"],
    ),
    "shell_wrapper": (
        "snax_mx_alu_shell_wrapper",
        [
            "hw/snax_mx_alu/source_<v>/mxfp8_pkg.sv",
            "hw/snax_mx_alu/source_<v>/PE_Array.sv",
            "hw/snax_mx_alu/source_<v>/PE_Array_wrapper.sv",
            "hw/snax_mx_alu/source_<v>/snax_mx_alu_shell_wrapper.sv",
        ],
    ),
    "streamer": (
        "snax_mx_alu_Streamer",
        ["target/snitch_cluster/variants/<v>/generated/snax_mx_alu/snax_mx_alu_Streamer.sv"],
    ),
    "sparse_interconnect": (
        "SparseInterconnect",
        ["target/snitch_cluster/variants/<v>/generated/SparseInterconnect.sv"],
    ),
}

# Fields used to derive the variant name. Order = positional order in the name.
NAME_FIELDS = (
    "A_dtype",
    "B_dtype",
    "parfor_M",
    "parfor_N",
    "parfor_K",
    "block_size",
    "quantize_mode",
    "shared_format",
)


def variant_name(params: dict) -> str:
    m, n, k = params["parfor_M"], params["parfor_N"], params["parfor_K"]
    return (f"{params['A_dtype']}-{params['B_dtype']}-"
            f"{m}x{n}x{k}-blk{params.get('block_size', 32)}-"
            f"q{params['quantize_mode']}-s{params.get('shared_format', 0)}")


def expand_variants(sweep_cfg: dict) -> list[dict]:
    base = dict(sweep_cfg.get("base", {}))
    dims = sweep_cfg.get("sweep_dims") or {}
    skip = sweep_cfg.get("skip") or []

    keys = list(dims.keys())
    values = [list(dims[k]) for k in keys]
    if not keys:
        return [base]

    variants = []
    for combo in itertools.product(*values):
        params = dict(base)
        for k, v in zip(keys, combo):
            params[k] = v
        if any(all(params.get(sk) == sv for sk, sv in entry.items()) for entry in skip):
            continue
        variants.append(params)
    return variants


def write_synth_flists(name: str, synth_dir: Path) -> None:
    """Emit one .flist per synth top in SYNTH_TARGETS. Paths are
    repo-root-relative so the resulting variant tree is location-independent.

    Each file is verified to exist; missing entries fail loudly rather than
    producing a broken flist."""
    synth_dir.mkdir(parents=True, exist_ok=True)
    for target_name, (top_module, files) in SYNTH_TARGETS.items():
        resolved = [f.replace("<v>", name) for f in files]
        for rel in resolved:
            if not (REPO_ROOT / rel).is_file():
                sys.exit(f"[sweep] missing file for synth target {target_name!r}: {rel}")
        flist_path = synth_dir / f"{target_name}.flist"
        with open(flist_path, "w", encoding="utf-8") as f:
            f.write(f"# Variant: {name}\n")
            f.write(f"# Synth top: {top_module}\n")
            f.write(f"# Paths are relative to the repo root ({REPO_ROOT}).\n")
            f.write(f"# Usage (from repo root):\n")
            f.write(f"#   dc_shell -f target/snitch_cluster/variants/{name}/synth/{target_name}.flist\n")
            f.write(f"\n")
            for rel in resolved:
                f.write(f"{rel}\n")
        print(f"[sweep]   wrote synth flist {target_name} (top={top_module}) → {flist_path}")


def run(cmd: list[str], **kw) -> None:
    print(f"[sweep] $ {' '.join(str(c) for c in cmd)}", flush=True)
    result = subprocess.run(cmd, **kw)
    if result.returncode != 0:
        sys.exit(f"[sweep] command failed (exit {result.returncode}): {' '.join(str(c) for c in cmd)}")


def generate_variant(params: dict, name: str, dry_run: bool) -> None:
    cfg_dir       = TARGET_DIR / "cfg"
    data_dir      = SCRIPT_DIR / "data"
    var_root      = TARGET_DIR / "variants" / name

    params_path   = data_dir / f"params_{name}.hjson"
    genhw_path    = cfg_dir / f"snax_mx_cluster_{name}.hjson"
    defines_mk    = cfg_dir / f"snax_mx_defines_{name}.mk"
    data_h        = data_dir / f"data_{name}.h"
    pe_dir        = REPO_ROOT / "hw" / "snax_mx_alu" / f"source_{name}"
    gen_dir       = var_root / "generated"
    test_dir      = var_root / "test"
    synth_dir     = var_root / "synth"

    print(f"\n========================================================")
    print(f"[sweep] variant {name}")
    print(f"[sweep]   params       = {params}")
    print(f"[sweep]   params_path  = {params_path}")
    print(f"[sweep]   genhw_path   = {genhw_path}")
    print(f"[sweep]   pe_dir       = {pe_dir}")
    print(f"[sweep]   gen_dir      = {gen_dir}")
    print(f"[sweep]   test_dir     = {test_dir}")
    print(f"[sweep]   synth_dir    = {synth_dir}")
    print(f"[sweep]   data_h       = {data_h}")
    print(f"========================================================\n", flush=True)

    if dry_run:
        return

    for d in (var_root, gen_dir, test_dir, pe_dir, params_path.parent):
        d.mkdir(parents=True, exist_ok=True)

    # 1. Materialize per-variant params.hjson.
    with open(params_path, "w", encoding="utf-8") as f:
        hjson.dump(params, f, indent=4)

    # 2. Run orchestrator (PE-Array gen + hjson patch + datagen).
    run([
        sys.executable, str(ORCHESTRATOR),
        "--swcfg",       str(params_path),
        "--hwcfg",       str(HWCFG_TEMPLATE),
        "--genhw",       str(genhw_path),
        "--output",      str(data_h),
        "--gen-pe-dir",  str(pe_dir),
        "--defines-mk",  str(defines_mk),
    ])

    # 2b. Copy the static SV utilities from the shared source/ into source_<v>/
    # so each variant directory is self-contained and can be git-pushed
    # standalone to a synthesis machine.
    if PE_SOURCE_DIR.is_dir():
        for src_file in PE_SOURCE_DIR.iterdir():
            if not src_file.is_file() or src_file.name in ORCH_OUTPUTS:
                continue
            shutil.copy2(src_file, pe_dir / src_file.name)
            print(f"[sweep]   copied static {src_file.name} → {pe_dir}/")

    # 3. Run make rtl-gen with per-variant CFG_OVERRIDE / GENERATED_DIR /
    #    SNAX_TEST_PATH so snaxgen + clustergen + bender_targets write to the
    #    variant tree instead of the shared default.
    cfg_override_rel = genhw_path.relative_to(TARGET_DIR)  # e.g. cfg/snax_mx_cluster_<v>.hjson
    run([
        "make", "-C", str(TARGET_DIR), "rtl-gen",
        f"CFG_OVERRIDE={cfg_override_rel}",
        f"GENERATED_DIR={gen_dir}",
        f"SNAX_TEST_PATH={test_dir}/",
    ])

    # 4. Emit synth-ready flists (one per DSE-interesting standalone top).
    write_synth_flists(name, synth_dir)


def main() -> None:
    ap = argparse.ArgumentParser(description="Multi-variant RTL generation driver")
    ap.add_argument("--sweep", default=DEFAULT_SWEEP, type=Path,
                    help="path to sweep config hjson (default: data/sweep.hjson)")
    ap.add_argument("--only", action="append", default=[],
                    help="only run variants whose name CONTAINS this substring; repeatable")
    ap.add_argument("--list", action="store_true",
                    help="list variants and exit (no generation)")
    ap.add_argument("--dry-run", action="store_true",
                    help="print per-variant plan but skip orchestrator/make")
    args = ap.parse_args()

    with open(args.sweep, encoding="utf-8") as f:
        sweep_cfg = hjson.load(f)

    variants = expand_variants(sweep_cfg)
    named = [(variant_name(v), v) for v in variants]

    if args.only:
        named = [(n, v) for (n, v) in named
                 if any(filt in n for filt in args.only)]

    print(f"[sweep] {len(named)} variant(s) selected from {args.sweep}")
    for name, _ in named:
        print(f"[sweep]   - {name}")
    if args.list:
        return
    if not named:
        sys.exit("[sweep] no variants selected; nothing to do.")

    for name, params in named:
        generate_variant(params, name, dry_run=args.dry_run)

    print(f"\n[sweep] done. {len(named)} variant(s) generated.")


if __name__ == "__main__":
    main()
