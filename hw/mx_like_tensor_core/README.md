# tensor_core_gen

Self-contained Chisel emitter for the MX-format tensor-core **PE array**
(`TemplateDPU` tiles + integrated requantizer). Designed to be embedded in a
system-level orchestrator (Python / Makefile / shell) that resolves the desired
`(act, weight, scale, geometry)` tuple and invokes this project to produce
SystemVerilog.

The processing element is **`TemplateDPU`** — a config-parameterised 4-way
dot-product unit with a **BF16 accumulator** (no per-config bit-width
optimisation; every datapath width is a monotone function of the format
parameters, so the emitted hardware reflects each configuration's intrinsic
requirement). It is a drop-in `PE_Array`: the external ports are independent of
the accumulator, so the wrapper / shell / cluster integration are unaffected by
the format choice.

## Quick start

```bash
# Emit E5M2 × E5M2 with UE6M2 scale, E5M2 output (quantize-mode 2)
sbt "runMain mx.EmitTensorCore --act E5M2 --weight E5M2 --scale UE6M2 --quantize-mode 2"

# Custom geometry + output directory, INT8 output (quantize-mode 4)
sbt "runMain mx.EmitTensorCore \
      --act INT8 --weight INT8 --scale UE8M0 --quantize-mode 4 \
      --tile-rows 8 --tile-cols 8 --vec 4 --block-size 32 \
      --outdir out/INT8sq_8x8"

# Emit just the DPU (unit), across all 126 (act≥weight)×scale configs
sbt "runMain mx_template.EmitAllTemplateDPU generated"
```

## CLI reference (`mx.EmitTensorCore`)

| Flag | Type | Default | Notes |
|---|---|---|---|
| `--act`    | `INT8 \| E5M2 \| E4M3 \| E3M2 \| E2M3 \| E2M1` | required | Activation element format. |
| `--weight` | same | required | Weight element format. |
| `--scale`  | `UE8M0 \| UE7M1 \| UE6M2 \| UE5M3 \| UE4M4 \| UE4M3` | required | Shared block scale. |
| `--out-type` / `--quantize-mode` | element / `0..7` | = act type | Output format: 0=FP32 1=BF16 2=E5M2 3=E4M3 4=INT8 5=E2M3 6=E3M2 7=E2M1. |
| `--vec`    | int | 4 | Vector size per cycle (parfor_K), = DPU lane count `N`. |
| `--tile-rows` | int | 4 | Tile row count. |
| `--tile-cols` | int | 16 | Tile column count. |
| `--block-size` | int | 16 | MX block size (must be a multiple of `--vec`). |
| `--outdir` | path | `generated/<label>/` | `<label> = <act>_<weight>_<scale>_out<outType>`. |
| `--no-integrated` | flag | false | Skip emitting the integrated `PE_Array.sv`. |
| `--no-standalone-requant` | flag | false | (requant is always bundled into `PE_Array.sv`.) |

Output modes: **FP8/FP6** (E5M2/E4M3/E3M2/E2M3/E2M1) and **INT8** go through the
bundled block requantizer; **BF16** packs the accumulator directly; **FP32** is
the BF16 accumulator right-padded to IEEE-754.

## Output layout

```
<outdir>/PE_Array.sv     Integrated PE array: TemplateDPU tiles + requant block.
```

## Directory layout

```
tensor_core_gen/
├── build.sbt                         Standalone sbt project (Chisel 6.4.0, Scala 2.13.14)
├── project/build.properties          sbt version pin
├── README.md
└── src/main/scala/mx/
    ├── EmitTensorCore.scala          Parametric CLI entry point (emits PE_Array)
    ├── array/
    │   └── TemplatePEArray.scala     Drop-in "PE_Array": TemplateDPU tiles + requant
    ├── mac_template/                 The DPU + the single format type system
    │   ├── Parameter.scala           Elem, Scale, BF16, DPUConfig, Widths
    │   ├── TemplateDPU.scala         4-way DP unit: Preprocess ×N → AlignSumTree
    │   │                             → ScaleMult → AccUpdate (BF16 accreg)
    │   └── EmitTemplateDPU.scala     Standalone per-config / all-config DPU emit
    └── requant/
        ├── Parameter.scala           RequantConfig
        ├── RequantFP8.scala          FP8/FP6 output requantizer
        └── RequantINT8.scala         INT8 output requantizer
```

## Format types

`mx_template.{Elem, Scale}` is the **single** element / scale format type
(shared by the DPU, the requantizer, and the emitter); `DPUConfig(A, W, S, N)`
carries one PE's configuration. Element formats: `E5M2, E4M3, E3M2, E2M3, E2M1,
INT8`; scale formats: `UE8M0, UE7M1, UE6M2, UE5M3, UE4M4, UE4M3` (UE4M3 is 7-bit).

## Guarantees

- The emitted `PE_Array` module has the **same external port list** across
  output modes for a given geometry, so it is a drop-in for the system wrapper.
- Self-contained on Chisel 6.4.0 + Scala 2.13.14 alone — no external subprojects.
