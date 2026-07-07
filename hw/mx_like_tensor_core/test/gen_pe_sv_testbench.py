#!/usr/bin/env python3
# Copyright 2024 KU Leuven.
# SPDX-License-Identifier: Apache-2.0
#
# Emit a self-contained SystemVerilog testbench that replays a per-PE stimulus
# vector (from gen_pe_testvectors.py) into the emitted BFP_PE, for power
# analysis (VCD) and a functional self-check against the float64 golden.
#
# Inputs:
#   --json   <combo>.json          stimulus produced by gen_pe_testvectors.py
#   --pedir  generated/pe_tb/<...>  dir holding BFP_PE.sv + pe_meta.txt (from PEEmitMain)
# Output:
#   <pedir>/tb_BFP_PE.sv
#
# Reset convention: the PE uses an active-low async reset
# (asyncRstN = (!reset).asAsyncReset), so `reset` is driven LOW to async-clear
# the registers, then held HIGH for normal operation; io_resetAcc clears the
# accumulator synchronously (mirrors the chiseltest).

import argparse
import json
import os


def read_meta(pedir):
    meta = {}
    with open(os.path.join(pedir, "pe_meta.txt")) as f:
        for line in f:
            k, v = line.split()
            meta[k] = v
    return meta


def emit_tb(vec, meta, pedir):
    op_a_w = int(meta["op_a_w"])
    op_b_w = int(meta["op_b_w"])
    acc_w  = int(meta["acc_w"])
    scale_w = int(meta["scale_w"])
    m_acc  = int(meta["m_acc"])
    cycles = vec["cycles"]
    n = len(cycles)
    golden = vec["golden_dot"]
    tag = f"{vec['act']}_{vec['weight']}_{vec['scale']}"

    a_init = "\n".join(f"    stim_a[{i}] = {op_a_w}'d{c['op_a']};" for i, c in enumerate(cycles))
    b_init = "\n".join(f"    stim_b[{i}] = {op_b_w}'d{c['op_b']};" for i, c in enumerate(cycles))
    sa_init = "\n".join(f"    stim_sa[{i}] = {scale_w}'d{c['scaleA']};" for i, c in enumerate(cycles))
    sb_init = "\n".join(f"    stim_sb[{i}] = {scale_w}'d{c['scaleB']};" for i, c in enumerate(cycles))

    tb = f"""// AUTO-GENERATED per-PE power/functional testbench — do not edit.
// Combo: {vec['act']} x {vec['weight']} / {vec['scale']}   K={vec['K']} bs={vec['block_size']} vec={vec['vector_size']}
// Stimulus: production quantize_mx_v6 (fitted distribution).  Golden = ideal
// dequantized dot product (float64).  Drives BFP_PE and dumps a VCD.
`timescale 1ns/1ps
module tb_BFP_PE;
  localparam int N      = {n};
  localparam int M_ACC  = {m_acc};
  localparam real GOLDEN = {golden!r};
  localparam real REL_TOL = 0.05;
  localparam real ABS_TOL = 1.0e-3;

  reg                      clock;
  reg                      reset;
  reg                      io_validIn;
  reg                      io_resetAcc;
  reg  [{op_a_w-1}:0]           io_op_a_i;
  reg  [{op_b_w-1}:0]           io_op_b_i;
  reg  [{scale_w-1}:0]            io_share_exp_A_i;
  reg  [{scale_w-1}:0]            io_share_exp_B_i;
  wire                     io_validOut;
  wire [{acc_w-1}:0]           io_accOut;

  BFP_PE dut (
    .clock            (clock),
    .reset            (reset),
    .io_op_a_i        (io_op_a_i),
    .io_op_b_i        (io_op_b_i),
    .io_share_exp_A_i (io_share_exp_A_i),
    .io_share_exp_B_i (io_share_exp_B_i),
    .io_validIn       (io_validIn),
    .io_resetAcc      (io_resetAcc),
    .io_validOut      (io_validOut),
    .io_accOut        (io_accOut)
  );

  // stimulus memories
  reg [{op_a_w-1}:0] stim_a  [0:N-1];
  reg [{op_b_w-1}:0] stim_b  [0:N-1];
  reg [{scale_w-1}:0]  stim_sa [0:N-1];
  reg [{scale_w-1}:0]  stim_sb [0:N-1];

  // clock: 10ns period, posedge is the active edge
  initial clock = 1'b0;
  always #5 clock = ~clock;

  integer i;
  integer sgn, ex;
  reg [M_ACC-1:0] mant;
  real got, relerr;

  initial begin
    $dumpfile("tb_BFP_PE_{tag}.vcd");
    $dumpvars(0, tb_BFP_PE);

{a_init}
{b_init}
{sa_init}
{sb_init}

    // init
    reset = 1'b0;                 // async-assert (active-low): clear registers
    io_validIn = 1'b0; io_resetAcc = 1'b0;
    io_op_a_i = 0; io_op_b_i = 0; io_share_exp_A_i = 0; io_share_exp_B_i = 0;
    repeat (2) @(negedge clock);
    reset = 1'b1;                 // release async reset -> operate

    // synchronous accumulator clear
    io_resetAcc = 1'b1; @(negedge clock);
    io_resetAcc = 1'b0;
    if (io_validOut !== 1'b0) $display("[tb] WARN validOut not low after resetAcc");

    // drive the accumulation, one vector per cycle
    for (i = 0; i < N; i = i + 1) begin
      io_op_a_i        = stim_a[i];
      io_op_b_i        = stim_b[i];
      io_share_exp_A_i = stim_sa[i];
      io_share_exp_B_i = stim_sb[i];
      io_validIn       = 1'b1;
      @(negedge clock);
      if (io_validOut !== 1'b1) $display("[tb] WARN validOut not high at cycle %0d", i);
    end
    io_validIn = 1'b0;
    @(negedge clock);
    if (io_validOut !== 1'b0) $display("[tb] WARN validOut not low after deassert");

    // decode narrow-FP accOut and self-check
    sgn  = io_accOut[{acc_w-1}];
    ex   = io_accOut[{acc_w-2} -: 8];
    mant = io_accOut[M_ACC-1:0];
    if (ex == 0) got = 0.0;
    else got = (sgn ? -1.0 : 1.0) * (1.0 + mant / (2.0 ** M_ACC)) * (2.0 ** (ex - 127));
    relerr = (GOLDEN == 0.0) ? got : ((got - GOLDEN) / GOLDEN);
    if (relerr < 0) relerr = -relerr;

    $display("[tb {tag}] accOut=%h  got=%f  golden=%f  relErr=%f%%",
             io_accOut, got, GOLDEN, relerr * 100.0);
    if (((got > GOLDEN ? got - GOLDEN : GOLDEN - got)) <= REL_TOL * (GOLDEN < 0 ? -GOLDEN : GOLDEN) + ABS_TOL)
      $display("[tb {tag}] RESULT: PASS");
    else
      $display("[tb {tag}] RESULT: FAIL");

    repeat (2) @(negedge clock);
    $finish;
  end
endmodule
"""
    out = os.path.join(pedir, "tb_BFP_PE.sv")
    with open(out, "w") as f:
        f.write(tb)
    print(f"[sv] {out}  (N={n} cycles, golden={golden:.6g})")
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--json", required=True)
    ap.add_argument("--pedir", required=True)
    args = ap.parse_args()
    vec = json.load(open(args.json))
    meta = read_meta(args.pedir)
    emit_tb(vec, meta, args.pedir)


if __name__ == "__main__":
    main()
