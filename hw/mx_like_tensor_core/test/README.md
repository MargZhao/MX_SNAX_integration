# Per-PE (BFP_PE / FDPU) data + power testbench

Bit-level functional verification **and** real-workload power-VCD generation for
the single processing element, driven by the *production* snax-mx quantization
pipeline.

## What it does

1. **Stimulus** comes from the production quantizer — `gen_workload` (fitted:
   transformer-like Gaussian weights + heavy-tailed outlier activation channels,
   `sigma_act=0.372 / sigma_w=0.0366`, from `data/pe_vcd_merged_manifest.csv`) +
   `quantize_mx_v6` (NVFP4-style round-up block scale). One length-`K` dot-product
   accumulation per PE is serialised into per-accumulate-cycle inputs
   (`op_a / op_b / scaleA / scaleB`) + a float64 golden (ideal dequantized dot).
2. **Functional check** (Scala/chiseltest): drive `FDPU`, decode `accOut`
   (narrow-FP `{s,e8,m}`), compare to golden within a relative tolerance, and
   check timing (`validOut` vs `validIn`, `resetAcc`).
3. **Power check** (SV/Verilator): replay the *same* stimulus into the emitted
   `BFP_PE`, dump a VCD, self-check against the golden. VCD → SAIF → PrimeTime.

Oracle = float64 ideal dot product + tolerance (the PE is intentionally lossy:
anchored fixed-point tree + early RNE + narrow-FP `FusedScaleAccumulator`).

Dimensions: `K=64, block_size=16, vector_size=4` (4 blocks × 4 cycles = 16
accumulate cycles). Scope: manifest combos with **act precision ≥ weight
precision** (`E2M1 < E2M3 < E3M2 < E4M3 < E5M2 < INT8`) × 6 scale formats
(`UE8M0 UE7M1 UE6M2 UE5M3 UE4M4 UE4M3`) = 126 combos. `UE4M3` is a 7-bit scale
(`ScaleType(4,3)`) registered by `gen_pe_testvectors._ScaleFormatN`.

## Files

| file | role |
|---|---|
| `gen_pe_testvectors.py` | one combo → `vectors/<combo>.json` + `.tv` + golden |
| `gen_all_vectors.py`    | all 126 manifest combos (act≥weight) |
| `gen_pe_sv_testbench.py`| one combo's `tb_BFP_PE.sv` from json + `pe_meta.txt` |
| `gen_all_sv.py [--run]` | all tbs; `--run` also verilates + runs → VCDs |
| `pe_correctness_table.csv` | per-combo golden/got/relErr (functional run) |
| `../src/test/scala/mx/mac/PEAccumTestbenchSpec.scala` | Scala functional suite (auto-discovers `vectors/*.tv`) |
| `mx.mac.PEEmitMain` / `mx.mac.AllPETbEmitMain` | emit `BFP_PE.sv` + `pe_meta.txt` per combo |

## Run

```bash
# one combo, functional + power
python3 test/gen_pe_testvectors.py --act E4M3 --weight E2M1 --scale UE5M3
sbt "testOnly mx.mac.PEAccumTestbenchSpec"                     # functional (all vectors present)
sbt "runMain mx.mac.PEEmitMain --act E4M3 --weight E2M1 --scale UE5M3 --outdir generated/pe_tb/E4M3_E2M1_UE5M3"
python3 test/gen_pe_sv_testbench.py --json test/vectors/E4M3_E2M1_UE5M3_K64_bs16_vec4_seed0.json \
        --pedir generated/pe_tb/E4M3_E2M1_UE5M3
( cd generated/pe_tb/E4M3_E2M1_UE5M3 && \
  verilator --binary --timing --trace -Wno-fatal --top-module tb_BFP_PE BFP_PE.sv tb_BFP_PE.sv && \
  ./obj_dir/Vtb_BFP_PE )                                        # -> tb_BFP_PE_<combo>.vcd

# all 126
python3 test/gen_all_vectors.py            # vectors
sbt "testOnly mx.mac.PEAccumTestbenchSpec" # functional table
sbt "runMain mx.mac.AllPETbEmitMain 4 64"  # emit 126 BFP_PE + meta
python3 test/gen_all_sv.py --run           # 126 tbs + VCDs
```
