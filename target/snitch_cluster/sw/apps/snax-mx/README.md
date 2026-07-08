# SNAX-MX Generation & Sweep Flow

This directory drives the RTL + test-data generation for the **snax_mx_alu**
accelerator. It provides two entry points:

| Script         | Scope                                                  |
| -------------- | ------------------------------------------------------ |
| `orchestrator.py` | **single design point** — one `params.hjson` → one set of outputs |
| `sweep.py`        | **multi-variant DSE** — Cartesian-product of overrides → one set per variant |

Both scripts ultimately invoke the same generation pipeline (Chisel + snaxgen
+ clustergen). `sweep.py` is just a driver that loops `orchestrator.py` +
`make rtl-gen` with per-variant output paths.

---

## 1. Parameter flow

A single set of `params.hjson` values propagates through **four** generators
to produce **four** output trees:

```
params.hjson
   │
   ▼
[orchestrator.py]──────► hw/snax_mx_alu/source_<v>/PE_Array.sv          (Chisel mx.GeneratePEArray)
   │                ──► hw/snax_mx_alu/source_<v>/PE_Array_wrapper.sv  (orchestrator emit)
   │                ──► cfg/snax_mx_cluster_<v>.hjson                  (patched streamer cfg)
   │                ──► cfg/snax_mx_defines_<v>.mk                     (+define+ flags)
   │                ──► data/data_<v>.h                                (test inputs)
   │
   ▼
[snaxgen.py]      ──► variants/<v>/generated/snax_mx_alu/snax_mx_alu_wrapper.sv
                  ──► variants/<v>/generated/snax_mx_alu/snax_mx_alu_streamer_wrapper.sv
                  ──► variants/<v>/generated/snax_mx_alu/snax_mx_alu_csrman_wrapper.sv
                  ──► variants/<v>/generated/snax_mx_alu/snax_mx_alu_reqrspman_ReqRspManager.sv
                  ──► variants/<v>/generated/snax_mx_alu/snax_mx_alu_Streamer.sv    (Chisel)
                  ──► variants/<v>/generated/SparseInterconnect.sv                  (Chisel)
                  ──► variants/<v>/generated/sparse_interconnect_wrapper.sv
                  ──► variants/<v>/test/testharness.sv
   │
   ▼
[clustergen.py]   ──► variants/<v>/generated/snax_mx_cluster_wrapper.sv
```

For the single-design-point path (`make rtl-gen` with the default
`CFG_OVERRIDE=cfg/snax_mx_cluster.hjson`) the same files land in their
default un-suffixed locations (`hw/snax_mx_alu/source/`,
`target/snitch_cluster/generated/`, `target/snitch_cluster/test/`).

---

## 2. Single design point: `orchestrator.py`

Reads `data/params.hjson` and produces one set of outputs.

```bash
# Driven by make (recommended)
make -C target/snitch_cluster CFG_OVERRIDE=cfg/snax_mx_cluster.hjson rtl-gen

# Or invoked directly (e.g. to skip the sbt RTL step)
python3 target/snitch_cluster/sw/apps/snax-mx/orchestrator.py \
    --swcfg  target/snitch_cluster/sw/apps/snax-mx/data/params.hjson \
    --hwcfg  target/snitch_cluster/cfg/snax_mx_cluster_template.hjson \
    --genhw  target/snitch_cluster/cfg/snax_mx_cluster.hjson \
    --output target/snitch_cluster/sw/apps/snax-mx/data/data.h
```

Useful flags:

| Flag            | Default                                | Purpose                                   |
| --------------- | -------------------------------------- | ----------------------------------------- |
| `--gen-pe-dir`  | `hw/snax_mx_alu/source`                | Where `PE_Array.sv` + `PE_Array_wrapper.sv` go |
| `--defines-mk`  | `<genhw>.parent/snax_mx_defines.mk`    | Where `+define+SCALE_OUTPUT_EN` etc. are written |
| `--skip-rtl`    | off                                    | Skip the sbt Chisel step (re-use existing PE_Array.sv) |

---

## 3. Multi-variant: `sweep.py` + `data/sweep.hjson`

### 3.1 Top-level config (`data/sweep.hjson`)

Three sections:

```hjson
{
    base: {            // shared by every variant
        K: 16,  N: 16,  M: 16,
        stationary: 0,  use_hw_gemm: 0
    }
    sweep_dims: {      // each key is a sweep axis; Cartesian product = variant set
        A_dtype:       ["fp6_e2m3", "mxint8"]
        B_dtype:       ["mxint8"]
        parfor_M:      [4]
        parfor_N:      [16]
        parfor_K:      [4]
        block_size:    [16]
        quantize_mode: [3, 4]
        shared_format: [4]
    }
    skip: [            // partial-match denylist; drops variants matching ALL listed fields
        // { A_dtype: "mxint8", quantize_mode: 3 }
    ]
}
```

### 3.2 Variant naming

Auto-derived from key fields:

```
<A_dtype>-<B_dtype>-<parfor_M>x<parfor_N>x<parfor_K>-blk<block_size>-q<quantize_mode>-s<shared_format>
```

Example: `fp6_e2m3-mxint8-4x16x4-blk16-q4-s4`.

### 3.3 Usage

```bash
# Activate the pixi env first (verilator, bender, sbt, hjson are there):
source /pixi/entrypoint.sh bash    # one-shot per shell; not needed if the
                                   # devcontainer remoteEnv is configured.

# List planned variants
python3 target/snitch_cluster/sw/apps/snax-mx/sweep.py --list

# Dry-run (print per-variant plan, no sbt/make)
python3 ... sweep.py --dry-run

# Filter by name substring (repeatable)
python3 ... sweep.py --only mxint8-mxint8 --only q4

# Run everything in sweep.hjson
python3 ... sweep.py
```

### 3.4 Per-variant output layout

For each variant `<v>` the driver produces:

```
hw/snax_mx_alu/source_<v>/
   ├── PE_Array.sv                 (Chisel, per-variant elaborated)
   ├── PE_Array_wrapper.sv         (orchestrator, per-variant)
   ├── mxfp8_pkg.sv                (copied verbatim from source/)
   └── snax_mx_alu_shell_wrapper.sv (copied verbatim from source/)

target/snitch_cluster/variants/<v>/
   ├── generated/
   │     ├── snax_mx_cluster_wrapper.sv          (clustergen)
   │     ├── SparseInterconnect.sv               (Chisel)
   │     ├── sparse_interconnect_wrapper.sv
   │     ├── bender_targets.tmp
   │     └── snax_mx_alu/
   │           ├── snax_mx_alu_wrapper.sv         (snaxgen)
   │           ├── snax_mx_alu_streamer_wrapper.sv
   │           ├── snax_mx_alu_csrman_wrapper.sv
   │           ├── snax_mx_alu_reqrspman_ReqRspManager.sv
   │           └── snax_mx_alu_Streamer.sv       (Chisel)
   ├── test/testharness.sv                       (snaxgen)
   └── synth/                                    (synth flists — see §5)
         ├── pe_array.flist
         ├── shell_wrapper.flist
         ├── streamer.flist
         └── sparse_interconnect.flist

target/snitch_cluster/cfg/
   ├── snax_mx_cluster_<v>.hjson                 (patched streamer cfg)
   └── snax_mx_defines_<v>.mk                    (+define+ flags)

target/snitch_cluster/sw/apps/snax-mx/data/
   ├── params_<v>.hjson                          (base + sweep override)
   └── data_<v>.h                                (test inputs)
```

Each `source_<v>/` is **self-contained** — copying the directory to another
machine yields a complete PE-Array RTL package.

---

## 4. Bender / build integration (⚠ gotcha)

`Bender.yml` and the per-subdir `Bender.yml` files point at the **shared**
paths `hw/snax_mx_alu/source/` and `target/snitch_cluster/generated/`. The
sweep produces **archive directories** (`source_<v>/`, `variants/<v>/...`)
but does **not** edit `Bender.yml`. So:

- A bare `bender script verilator …` after a sweep resolves the **shared**
  paths, not the per-variant ones.
- To actually build/simulate variant `<v>` with the current Bender setup
  you must "activate" it by copying its outputs into the shared paths:
  ```bash
  cp -r hw/snax_mx_alu/source_<v>/*                    hw/snax_mx_alu/source/
  cp -r target/snitch_cluster/variants/<v>/generated/* target/snitch_cluster/generated/
  cp    target/snitch_cluster/variants/<v>/test/testharness.sv \
        target/snitch_cluster/test/testharness.sv
  ```
- Or generate a per-variant `Bender.local` with path overrides (not yet
  implemented).

For **off-box synthesis** the synth flists in `variants/<v>/synth/` bypass
Bender entirely — see next section.

---

## 5. DSE synth flists

`sweep.py` emits one flist per DSE-interesting standalone top into
`variants/<v>/synth/`. Each flist:

- Lists files **relative to the repo root**.
- Has a header comment naming the top module.
- Is self-contained (no Bender, no `+incdir+`, no third-party packages —
  every standalone top here is a Chisel-emitted single .sv).

| Flist                       | Top module                  | Use it to study |
| --------------------------- | --------------------------- | --------------- |
| `pe_array.flist`            | `PE_Array`                  | MAC array + requant area / max-freq vs dtype, parfor, block_size, quantize_mode |
| `shell_wrapper.flist`       | `snax_mx_alu_shell_wrapper` | Total accelerator (PE_Array + repack + flow ctrl); "what does one quantize_mode switch cost overall" |
| `streamer.flist`            | `snax_mx_alu_Streamer`      | Streamer area vs FIFO depth / TCDM port count / spatial config |
| `sparse_interconnect.flist` | `SparseInterconnect`        | Crossbar area scaling with port count |

**Run synth from the repo root:**

```bash
# Synopsys DC
dc_shell -f target/snitch_cluster/variants/<v>/synth/pe_array.flist

# Or load the flist manually
read_file -format sverilog [open variants/<v>/synth/pe_array.flist r]
```

### What's NOT covered by flists

- The full cluster top (`snax_mx_cluster_wrapper.sv`) — depends on Bender to
  pull in snitch core, AXI, TCDM banks, etc. Synth ROI is low for DSE since
  ~90% of the area is fixed cost. Use the existing `make` flow with `bender
  script synopsys` if you really need a SoC-level number.
- snitch core, common_cells, AXI — never change with mx-acc params.

---

## 6. Files in this directory

| File                | Purpose                                                       |
| ------------------- | ------------------------------------------------------------- |
| `orchestrator.py`   | Single-design-point driver (PE-Array gen + hjson patch + datagen) |
| `sweep.py`          | Multi-variant driver (loops orchestrator + make rtl-gen + flists) |
| `data/params.hjson` | Single-design-point sw config (consumed by `make rtl-gen`) |
| `data/sweep.hjson`  | Top-level sweep config (consumed by `sweep.py`)                |
| `data/datagen.py`   | Emits `data.h` test inputs                                     |
| `data/quantize.py`  | Quantization reference model for test data                     |
| `data/data.h`       | Test data emitted by datagen.py for the active design point    |
| `Makefile`          | Cluster-level Makefile hooks for this app                      |
| `src/`              | Snax-mx C++ kernel + RTL test harness sources                  |

---

## 7. Quick recipes

**Generate one variant (no sweep):** edit `data/params.hjson`, then
```bash
make -C target/snitch_cluster CFG_OVERRIDE=cfg/snax_mx_cluster.hjson rtl-gen
```

**Run a fresh sweep:** edit `data/sweep.hjson`, then
```bash
python3 target/snitch_cluster/sw/apps/snax-mx/sweep.py
```

**Sanity-check one variant from the sweep, without going through sbt:**
```bash
python3 ... sweep.py --only fp6_e2m3-mxint8-4x16x4-blk16-q4-s4 --dry-run
```

**Ship a variant to a synth machine:**
```bash
VAR=fp6_e2m3-mxint8-4x16x4-blk16-q4-s4
git add hw/snax_mx_alu/source_$VAR/ \
        target/snitch_cluster/variants/$VAR/ \
        target/snitch_cluster/cfg/snax_mx_cluster_$VAR.hjson \
        target/snitch_cluster/cfg/snax_mx_defines_$VAR.mk \
        target/snitch_cluster/sw/apps/snax-mx/data/params_$VAR.hjson \
        target/snitch_cluster/sw/apps/snax-mx/data/data_$VAR.h
git commit -m "snax-mx variant $VAR"
git push
# On synth host: git pull, then dc_shell -f target/snitch_cluster/variants/$VAR/synth/<top>.flist
```
