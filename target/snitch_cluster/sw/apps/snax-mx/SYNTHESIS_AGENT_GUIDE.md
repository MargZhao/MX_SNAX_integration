# Synthesis + Power Bundle — Agent Guide

Self-contained bundle to (1) synthesize the SNAX-MX cluster with Design Compiler
and (2) extract a **area + power breakdown** and compute **area efficiency** and
**energy/op** with PrimeTime PX. No Bender / Docker needed here.

> **[FILL IN]** = host/PDK-specific (PDK `.db`, SRAM datasheet). Do not guess.
> Config-specific numbers (dims, OP count, cycles) are in `manifest.txt` /
> `params.hjson` of THIS bundle — read them, don't assume.

---

## 1. File structure
```
rtl/                    Full self-contained system RTL (bender flist-plus flattened)
pickle.f                Compile filelist (order + +incdir+/+define+; paths → rtl/)
sim.vcd.gz              Gzipped switching activity (gunzip before use)
data.h params.hjson snax_mx_cluster.hjson   SW/HW config of this run
manifest.txt            Provenance: config, git SHA, VCD window (cycles)
SYNTHESIS_AGENT_GUIDE.md  This file
```
**Top module:** `snax_mx_cluster_wrapper` (instance `i_snax_mx_cluster`).
**VCD window:** see `manifest.txt` `vcd_window`. 2 VCD-TIME = 1 clock cycle.
The one-time runtime boot is excluded; the window is the per-op active region
(DMA-load → CSR-setup → compute → writeback). The functional printf verification
is NOT in the sim (removed) — there is no self-check, but power activity is real.

---

## 2. Synthesis (Design Compiler) — target 400 MHz (2.5 ns)
Base script: `syn/run_area.tcl` (top=`snax_mx_cluster_wrapper`, parses `pickle.f`,
`BLACKBOX_SRAM`). Steps:
1. **PDK libs [FILL IN]** — `target_library`/`link_library` = your stdcell `.db`.
2. **`BLACKBOX_SRAM = 1`** — DC gives logic area; add SRAM macro area from the
   memory-compiler datasheet (see §5 SPM).
3. **Clock:** `create_clock -period 2.500 [get_ports <clk_port>]` (identify the
   cluster clock input from the netlist; do not hardcode).
4. `compile_ultra` **keeping hierarchy** (`-no_autoungroup`, do NOT flatten) so
   the breakdown below works. Then `report_area -hierarchy`, `report_timing`,
   write `netlist.v` + `netlist.sdc`.

---

## 3. Power (PrimeTime PX)
```tcl
read_verilog netlist.v ; current_design snax_mx_cluster_wrapper ; link   # libs [FILL IN]
read_sdc     netlist.sdc                                                  # 2.5 ns clock
# gunzip sim.vcd.gz first:
read_vcd sim.vcd -strip_path <tb_top→dut>          # map VCD scope onto the design
set_power_analysis_mode -method averaged
update_power
report_power -hierarchy -levels 5  > reports/power_hier.rpt
```
The VCD is already windowed to the per-op region (see manifest). For a
*compute-only* sub-number, restrict PT to the compute cycles (from `acc_busy`
high; see §7). SPM (blackboxed) has NO PT power → compute separately (§5).

---

## 4. ⭐ Bucketing — map EVERY instance (this is where "others" usually bloats)

Fig.8 buckets: **SPM / MX core / Data streamers / DMA / CPU / ICache / others**.
The design has two subtrees under `i_snax_mx_cluster`:
- `i_cluster` — Snitch cluster (cores, I$, TCDM, DMA, interconnect)
- `i_snax_core_0_acc_0_snax_mx_alu` — the MX accelerator (MX core + streamers)

Run `report_power -hierarchy -levels 5` and assign by **SUBTREE** (a whole
instance subtree → one bucket), per this table. Paths are relative to
`i_snax_mx_cluster`:

| Bucket | Instance subtree(s) | Note |
|---|---|---|
| **SPM** | `i_cluster.gen_tcdm_super_bank[0..3]` (all `tc_sram` banks); `i_cluster.i_snitch_data_mem` | SRAM. Blackboxed → power/area from datasheet (§5). |
| **CPU** | `i_cluster.gen_core[0]` **and** `i_cluster.gen_core[1]` → their `i_snitch_cc.i_snitch` (RISC-V core) + FPU + `i_sync_*` | ⚠ **SUBTRACT the DMA subtree** (next row) — it is nested inside `gen_core[1]`. |
| **DMA** | `i_cluster.gen_core[1].i_snitch_cc.gen_dma.*` (iDMA engine); `i_cluster.i_axi_dma_xbar`; `i_cluster.i_axi_to_mem_dma`; `i_cluster.i_dma_interconnect` | ⚠ iDMA is **nested inside gen_core[1]** — carve it out or CPU double-counts. |
| **ICache** | `i_cluster.gen_hive[0].i_snitch_hive.*` | It is named `i_snitch_hive` (shared I$ + frontend), NOT "icache". |
| **MX core** (= tensor core) | **The WHOLE accelerator's CSR + compute:** `i_snax_core_0_acc_0_snax_mx_alu.i_snax_mx_alu_csrman_wrapper` (**CSR manager** — the accelerator config registers) + `.i_snax_mx_alu_reqrspman_*` (CSR bus interface) + `.i_snax_mx_alu_shell_wrapper` (PE_Array/`BFP_PE` **array** + `RequantFP8` **requant** + FSM); plus the CSR-routing glue `i_cluster.gen_snax_control_connection[*]` | Tensor core = **CSR manager + array + requant** (per design intent). Everything CSR-related for the accelerator lives here, NOT in others. |
| **Data streamers** | `i_snax_core_0_acc_0_snax_mx_alu.i_snax_mx_alu_streamer_wrapper.*` + `i_cluster.gen_yes_snax_tcdm_interconnect.*` | Readers/writer + AGU + FIFOs + the sparse interconnect (streamer ↔ TCDM access path). |
| **others** | remaining `i_cluster` children: `i_axi_to_reg`, `i_axi_to_tcdm`, `i_cluster_xbar`, `i_cut_ext_*` (AXI cuts), `i_reqrsp_mux_*`, `i_reqrsp_to_axi_*`, `i_popcount_*`, `i_snitch_barrier`, `i_snitch_cluster_peripheral` | AXI cuts/xbars, reqrsp muxes, barrier, periph. Should be a few %. |

Note: `i_cluster.gen_yes_snax_tcdm_interconnect` (the sparse interconnect between
the ~40 streamer ports and the TCDM) is assigned to **Data streamers** — it is
the streamer's memory-access path. State this in the report caption.

### Rules that prevent "others" bloat
1. **Carve nested blocks** — the three that bite: DMA inside `gen_core[1]`,
   MX+streamers inside the accelerator, I$ inside `gen_hive`. Assign by subtree,
   not by top-level instance name.
2. **Report deep enough** (`-levels 5`) so `gen_dma`, `i_snitch_hive`, the
   shell/streamer are individually visible.
3. After assigning the table, **"others" should be only** AXI cuts / reqrsp muxes
   / barrier / periph (small). If "others" is large, something named above got
   missed — check `gen_dma`, `gen_hive`, `gen_tcdm_super_bank`, the accelerator
   subtree, and the TCDM interconnect.
4. **Sanity-check coverage**: Σ(all buckets) area/power == top `snax_mx_cluster_wrapper`.

---

## 5. SPM (SRAM) power/area — blackboxed
`tc_sram` is a black box → DC/PT report NO SRAM power/area. Add separately:
- **Area [FILL IN]**: SRAM-macro area from your memory-compiler datasheet ×
  (#banks = 32, or the 4 superbanks × 8).
- **Energy [FILL IN]**: `E_SPM = n_read·E_rd + n_write·E_wr + leakage·T`. Access
  counts from the TCDM port toggles in the VCD (streamer reads A/B/scale, writer
  writes O), or from the streamer loop counts in `data.h`.

---

## 6. Area efficiency
```
MACs/cycle = tileRows × tileCols × vectorSize = 4 × 16 × 4 = 256   (see params.hjson)
Peak = 256 MAC/cycle × 400e6 = 102.4 GMAC/s ; OP = 2×MAC → 204.8 GOP/s = 0.2048 TOP/s
Area efficiency = 204.8 GOP/s / (A_logic[DC] + A_SPM_macro[datasheet])  → TOP/s/mm²
```
Use PEAK throughput (hardware capability); state it is peak. Report total and,
if useful, MX-core-only.

---

## 7. Energy / OP  (full-system, per-op; OP = 2·MAC)
```
OPs = 2 × M × K × N            (M,K,N from params.hjson; e.g. 32×1024×32 → 2·32·1024·32)
T_op = window_cycles × 2.5 ns  (window from manifest; per-op region, boot excluded)
E_bucket = P_bucket(over window) × T_op    (+ E_SPM from datasheet)
Energy/OP = Σ_bucket E_bucket / OPs   → pJ/OP  (stacked bar = the 7 buckets)
```
Phase notes (measured, this-class run; verify against `acc_busy`/`dma_busy_o`):
- **compute+writeback are OVERLAPPED** (block-streaming: each output block is
  requantized and written back while the next block accumulates). Treat
  `[acc_busy↑ .. streamer drain done]` as one phase.
- **DMA** (`gen_core[1]...dma_busy_o` high) is a small slice; **CSR-setup** (CPU
  writing ~50 accelerator CSRs, heavily stalled on the SNAX CSR bus) is the
  largest non-compute chunk.
- Caveat to state: this is an **unpipelined single op** (DMA→setup→compute
  serialized). Dynamic energy per bucket is pipeline-independent; for
  throughput/leakage use a pipelined per-op TIME = max(stage times), and/or
  amortize weight-DMA + one-time CSR-setup over many ops. State the assumption.

---

## 8. Signals for windowing / phase ID (in the VCD)
- Compute: `…i_snax_core_0_acc_0_snax_mx_alu.i_snax_mx_alu_shell_wrapper.acc_busy` (1=computing)
- Per-output-block: same scope `.valid_out_sig` (= `acc2stream_0_valid_o`, pulses OUT_CNT times)
- DMA: `…i_cluster.gen_core[1].i_snitch_cc.gen_dma.i_axi_dma_tc_snitch_fe.dma_busy_o`
