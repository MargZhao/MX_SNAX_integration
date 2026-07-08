# tensor_core_gen

Self-contained Chisel emitter for the MX-format tensor-core baseline PE array
(reduction tree + per-cycle scale composition + narrow FP accumulator +
integrated requantizer). Designed to be embedded in a system-level orchestrator
(Python / Makefile / shell) that resolves the desired (act, weight, scale,
M_acc, geometry) tuple and invokes this project to produce SystemVerilog.

Emits **the deployed thesis baseline** (cycleFP, per-cycle scale apply,
single narrow FP accumulator with M_acc-bit early rounding). No blockdef,
kulisch, or DSE experimental variants — this package is intentionally scoped
to what ships.

## Quick start

```bash
# Emit E5M2 × E5M2 with UE6M2 scale, M_acc auto-resolved from data/macc_final_selection.csv (= 11)
sbt "runMain mx.EmitTensorCore --act E5M2 --weight E5M2 --scale UE6M2"

# Explicit M_acc override
sbt "runMain mx.EmitTensorCore --act E2M1 --weight E2M1 --scale UE4M3 --m-acc 9"

# Custom geometry + output directory
sbt "runMain mx.EmitTensorCore \
      --act INT8 --weight INT8 --scale UE8M0 --m-acc 13 \
      --tile-rows 8 --tile-cols 8 --vec 4 --block-size 32 \
      --outdir out/INT8sq_8x8"

# Skip standalone requant emit (only produce integrated PE_Array.sv)
sbt "runMain mx.EmitTensorCore --act E5M2 --weight E5M2 --scale UE6M2 --no-standalone-requant"
```

## CLI reference

| Flag | Type | Default | Notes |
|---|---|---|---|
| `--act`    | `INT8 \| E5M2 \| E4M3 \| E3M2 \| E2M3 \| E2M1` | required | Activation element format. |
| `--weight` | same | required | Weight element format. |
| `--scale`  | `UE8M0 \| UE7M1 \| UE6M2 \| UE5M3 \| UE4M4 \| UE4M3` | required | Shared block scale. |
| `--m-acc`  | int in `[1,23]` | resolved from CSV, else per-act fallback | Accumulator mantissa bits. |
| `--vec`    | int | 4 | Vector size per cycle (parfor_K). |
| `--tile-rows` | int | 4 | Tile row count. |
| `--tile-cols` | int | 16 | Tile column count. |
| `--block-size` | int | 16 | MX block size (must be a multiple of `--vec`). |
| `--outdir` | path | `generated/<label>/` | `<label> = <act>_<weight>_<scale>_M<M_acc>`. |
| `--no-integrated` | flag | false | Skip emitting the integrated `PE_Array.sv`. |
| `--no-standalone-requant` | flag | false | Skip emitting the standalone requant block. |
| `--csv` | path | `data/macc_final_selection.csv` | Override CSV path for M_acc lookup. |

## Output layout

```
<outdir>/
├── PE_Array.sv                      Integrated PE array + requant block
└── requant_standalone/
    └── requant_in<W>.sv             Standalone requant block (for separate DC synth)
```

## Config resolution

**M_acc** is resolved in this order:

1. `--m-acc <int>` CLI flag (highest priority).
2. `data/macc_final_selection.csv` row matching `(act, weight, scale)`.
3. Per-activation fallback (hard-coded; see `DEFAULT_M_PER_ACT` in
   `EmitTensorCore.scala`): `INT8→14, E5M2→11, E4M3→12, E3M2→11, E2M3→12, E2M1→9`.

The resolved M_acc and its source are echoed to stdout — pipe or grep from the
orchestrator to make deterministic decisions.

### The M_acc CSV — full 126-config coverage

`data/macc_final_selection.csv` is the **complete lookup table** for all
supported `(act, weight, scale)` combinations:

  - 6 element formats × 6 element formats × 6 scale formats, filtered to the
    21 `precision(act) ≥ precision(weight)` pairs → 21 × 6 = **126 rows**
  - Each row: `act, weight, scale, m_acc, source`
  - `source` column labels how the M_acc was derived:
    - `sweep`             — measured directly by the accuracy sweep
      (`ε_acc < ½·ε_req` on q_proj_l0 + gate_proj_l1)
    - `scale_extrap`      — same `(act, weight)`, different scale; used the
      max M_acc across measured scales for that pair
    - `symmetric_anchor`  — no `(act, weight)` measurement; used the
      symmetric `(act, act)` anchor's max M_acc
    - (last-resort per-activation default — should be rare)

**Regenerating the CSV** when the sweep is refreshed:

```bash
# 1. Update sweep source CSVs (produced by your accuracy sweep):
#    data/macc_sweep_all_q_proj_l0_final.csv
#    data/macc_sweep_all_gate_proj_l1_final.csv
# 2. Regenerate the full 126-row selection:
python3 scripts/compute_macc_per_config.py
# → data/macc_final_selection.csv is rewritten
```

The extension script (`scripts/compute_macc_per_config.py`) applies the
3-layer fallback (sweep → scale_extrap → symmetric_anchor) and reports
coverage breakdown.

## Directory layout

```
tensor_core_gen/
├── build.sbt                        Standalone sbt project (Chisel 6.4.0, Scala 2.13.14)
├── project/build.properties         sbt version pin
├── README.md
├── data/
│   └── macc_final_selection.csv     M_acc sweep output (source of truth)
└── src/main/scala/mx/
    ├── EmitTensorCore.scala         Parametric CLI entry point
    ├── array/
    │   ├── Parameter.scala          PEArrayConfig / PEArrayINT8Config / ArchOverride
    │   └── PEArray.scala            PEArrayWrapper (MXFP output) + PEArrayWrapperINT8
    ├── mac/
    │   ├── Parameter.scala          MXFormats, ScaleFormats, ScaleAddConfig, TreeArch, ...
    │   ├── FDPUWidthMath.scala      Shared M_acc / width derivation
    │   ├── FDPUPostScaleReductionTree.scala   BFP_PE (cycleFP baseline)
    │   ├── CustomOperator.scala     Per-lane MAC (3×3 raw product)
    │   ├── CustomReduction.scala    Anchored align + adder tree + LZC + RNE
    │   ├── ScaleAddition.scala      Shared-scale mantissa composition
    │   └── FP32Common.scala         FPNAdder, ScaleToFPn, DirectToFPn, FPxScale
    └── requant/
        ├── Parameter.scala          RequantConfig, RequantINT8Config
        ├── RequantFP8.scala         FP output requantizer
        └── RequantINT8.scala        INT8 output requantizer
```

## Orchestrator integration example

```makefile
# Makefile snippet — call this from the parent SoC build to generate the PE array
# for one config on demand.  Depends on sbt being available and the
# tensor_core_gen/ folder being in the same tree (or as a git submodule).

TENSOR_CORE_GEN := hw/tensor_core_gen
CFG_ACT    ?= E5M2
CFG_WEIGHT ?= E5M2
CFG_SCALE  ?= UE6M2

gen/PE_Array.sv:
	cd $(TENSOR_CORE_GEN) && sbt \
	  "runMain mx.EmitTensorCore \
	     --act $(CFG_ACT) --weight $(CFG_WEIGHT) --scale $(CFG_SCALE) \
	     --outdir $(abspath gen)"
```

## Guarantees

- The emitted `PE_Array.sv` is **bit-identical** to what
  `hw/chisel_acc/src/main/scala/mx/EmitKeyConfigs.scala` produces for
  the same `(act, weight, scale, M_acc)` — the two share the same Chisel
  source under different sbt project boundaries.
- The M_acc CSV is read at elaboration time; regenerating the CSV
  automatically flows into the next emit.
- No hidden dependencies on `chisel_acc/subprojects/` — this project is
  self-contained on Chisel 6.4.0 + Scala 2.13.14 alone.
