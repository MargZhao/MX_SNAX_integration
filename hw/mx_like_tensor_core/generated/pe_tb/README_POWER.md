# SKILL — Per-PE (BFP_PE) synthesis + power flow (DC Compiler / PrimeTime PX)

You are the downstream agent running **Synopsys Design Compiler** + **PrimeTime PX**
on a licensed host. This bundle contains everything to synthesize the MX tensor-core
**processing element** and get real-workload power for **126 format combinations**.

Repo: `https://github.com/MargZhao/MX_SNAX_integration.git`
(see the commit message for the exact commit id this bundle was cut from.)

---

## 1. What is in `generated/pe_tb/<ACT>_<WEIGHT>_<SCALE>/`

| file | role |
|---|---|
| `BFP_PE.sv` | the PE RTL — top module **`BFP_PE`**, fully self-contained (all submodules inlined, no includes) |
| `tb_BFP_PE.sv` | stimulus testbench (drives a real-workload MXFP dot-product accumulation, `$dumpvars` a VCD, self-checks vs a float64 golden) |
| `tb_BFP_PE_<combo>.vcd` | RTL switching-activity VCD from that testbench |
| `pe_meta.txt` | `act weight scale vec K m_acc op_a_w op_b_w acc_w scale_w` for this combo |

126 combos = **act-precision ≥ weight-precision** (`E2M1 < E2M3 < E3M2 < E4M3 < E5M2 < INT8`)
× 6 scale formats (`UE8M0 UE7M1 UE6M2 UE5M3 UE4M4 UE4M3`). **`UE4M3` is a 7-bit scale**
(`ScaleType(4,3)`), so `scale_w = 7`; the other five are 8-bit. Element widths per combo
are in `pe_meta.txt` (`op_a_w = vec*wA`, `op_b_w = vec*wB`, `acc_w = 1+8+M_acc`).

Per-combo functional correctness is already verified in
`test/pe_correctness_table.csv` (all 126 within **< 0.81 %** of the float64 ideal;
the tb prints `RESULT: PASS`).

---

## 2. Design facts you need for constraints

- **Top module:** `BFP_PE`. **Single clock** port `clock`. One clock domain.
- **Reset `reset` is ACTIVE-LOW async** (internally `asyncRstN = !reset`): the design
  **operates with `reset = 1`**, and `reset = 0` async-clears the registers. Treat `reset`
  as an async reset control, **not** a clock. (The provided tb drives this correctly:
  `reset=0` two cycles to clear, then held `1`; `io_resetAcc` gives a synchronous clear.)
- **Sequential state:** one accumulator register (`accReg`, `acc_w` bits) + `validReg`.
  Everything else is combinational.
- **Ports:** `io_op_a_i[op_a_w]`, `io_op_b_i[op_b_w]`, `io_share_exp_A_i[scale_w]`,
  `io_share_exp_B_i[scale_w]`, `io_validIn`, `io_resetAcc`, `io_validOut`, `io_accOut[acc_w]`.
- **Target frequency:** per combo in `data/pe_vcd_merged_manifest.csv` column
  `target_freq_mhz`. The tb runs at a nominal **100 MHz (10 ns)** — override with the
  manifest target (or your own) in `create_clock`.

---

## 3. Synthesis (DC Compiler)

Per combo dir (RTL is self-contained — no search paths needed):

```tcl
read_file -format sverilog BFP_PE.sv
current_design BFP_PE
link
# clock period from pe_vcd_merged_manifest target_freq_mhz for this combo
create_clock -name clk -period <period_ns> [get_ports clock]
set_ideal_network            [get_ports reset]     ;# async control, not a clock
set_input_delay  <d> -clock clk [remove_from_collection [all_inputs] [get_ports clock]]
set_output_delay <d> -clock clk [all_outputs]
# ... your library / load / operating-condition setup ...
compile_ultra
write -format verilog -hierarchy -output BFP_PE.mapped.v
write_sdc BFP_PE.sdc
report_area ; report_timing
```

Use your standard library, corner, and constraint methodology — none is baked in here.

---

## 4. VCD → SAIF

The VCD is RTL-level activity from `tb_BFP_PE`, with the DUT instantiated as
`dut` (hierarchy `tb_BFP_PE/dut`). Convert with the repo converter
(this bundle ships one; a Synopsys `vcd2saif` on the DC host works too):

```bash
python3 target/snitch_cluster/sw/apps/snax-mx/vcd2saif.py \
    generated/pe_tb/<combo>/tb_BFP_PE_<combo>.vcd  <combo>.saif \
    --scope tb_BFP_PE/dut --top-instance tb_BFP_PE
```

`--scope tb_BFP_PE/dut` keeps only the `BFP_PE` instance nets → small backward-SAIF.

**Gate-level power:** the VCD here is RTL-level. For gate-accurate power, re-simulate
the DC-mapped netlist (`BFP_PE.mapped.v`) with the *same* `tb_BFP_PE.sv` (it is
technology-independent — behavioral driver + `$dumpvars`), then SAIF that VCD.

---

## 5. Power (PrimeTime PX)

```tcl
set power_enable_analysis true
set power_analysis_mode    averaged
read_verilog BFP_PE.mapped.v ; current_design BFP_PE ; link_design
read_sdc     BFP_PE.sdc
# read_parasitics <spef>            ;# your extraction
read_saif <combo>.saif -strip_path tb_BFP_PE/dut   ;# map onto the BFP_PE instance
update_power
report_power -hierarchy > <combo>.power.rpt
```

Cross-check against `data/pe_vcd_merged_manifest.csv` (`power_vcd_W`,
`submodule_powers_vcd`) — that manifest was produced by the same per-PE VCD-driven
power methodology on the previous RTL.

---

## 6. Regenerating a stimulus (optional)

The stimulus comes from the production MXFP pipeline (`quantize_mx_v6`, fitted
transformer-like distribution, `sigma_act=0.372 / sigma_w=0.0366`,
`K=64, block=16, vec=4`). To regenerate for a combo (e.g. different seed/K):

```bash
python3 test/gen_pe_testvectors.py --act E4M3 --weight E2M1 --scale UE5M3
sbt "runMain mx.mac.PEEmitMain --act E4M3 --weight E2M1 --scale UE5M3 --outdir generated/pe_tb/E4M3_E2M1_UE5M3"
python3 test/gen_pe_sv_testbench.py --json test/vectors/E4M3_E2M1_UE5M3_K64_bs16_vec4_seed0.json --pedir generated/pe_tb/E4M3_E2M1_UE5M3
```

See `test/README.md` for the full generator/verification pipeline.
