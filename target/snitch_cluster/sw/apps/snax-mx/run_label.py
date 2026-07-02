#!/usr/bin/env python3
# Copyright 2024 KU Leuven.
# Licensed under the Apache License, Version 2.0, see LICENSE for details.
# SPDX-License-Identifier: Apache-2.0
#
# run_label.py — print a compact, filesystem-safe run label derived from
# params.hjson, used by the Makefile `package-run` target to name per-run
# RTL+VCD bundles. Convention mirrors the data/ file tags plus workload dims.
#
#   <A_dtype>-<B_dtype>-<M>x<K>x<N>-pf<pM>x<pN>x<pK>-blk<block>-q<qmode>-s<scale>
#   e.g.  fp4_e2m1-fp4_e2m1-32x64x32-pf4x16x4-blk16-q7-s0

import sys
import hjson


def main() -> None:
    with open(sys.argv[1], encoding="utf-8") as f:
        p = hjson.load(f)
    print(
        f"{p['A_dtype']}-{p['B_dtype']}"
        f"-{p['M']}x{p['K']}x{p['N']}"
        f"-pf{p['parfor_M']}x{p['parfor_N']}x{p['parfor_K']}"
        f"-blk{p['block_size']}"
        f"-q{p['quantize_mode']}"
        f"-s{p['shared_format']}"
    )


if __name__ == "__main__":
    main()
