package mx

import chisel3._
import mx_template.{Elem, Scale, DPUConfig}
import mx.array.{TemplateArrayConfig, TemplatePEArray, TemplateOut}
import java.io.File

/** Parametric CLI to emit a single tensor-core baseline PE_Array.
 *
 *  Designed to be driven from a system-level orchestrator (Python / Makefile /
 *  shell) that resolves the desired (act, weight, scale, M_acc) tuple and
 *  invokes this main to produce SV.
 *
 *  Usage
 *  =====
 *
 *    sbt "runMain mx.EmitTensorCore
 *           --act E5M2 --weight E5M2 --scale UE6M2
 *           [--m-acc 11]
 *           [--vec 4] [--tile-rows 4] [--tile-cols 16] [--block-size 16]
 *           [--outdir generated/E5M2_E5M2_UE6M2_M11]
 *           [--no-integrated] [--no-standalone-requant]"
 *
 *  Output layout (default outdir = generated/<label>/, label = <act>_<weight>_<scale>_M<M_acc>):
 *
 *    <outdir>/PE_Array.sv                  Integrated PE array + requant
 *    <outdir>/requant_standalone/          Standalone requant block SV files
 *
 *  M_acc resolution order:
 *    1. --m-acc CLI value (if provided)
 *    2. data/macc_final_selection.csv row for the requested (act, weight, scale)
 *    3. hard-coded per-activation fallback (see DEFAULT_M_PER_ACT)
 *
 *  Exit codes:  0 = success, 1 = usage error / lookup failure.
 */
object EmitTensorCore extends App {

  // ── CLI parsing ───────────────────────────────────────────────────────────
  //
  // out-type / quantize-mode is INDEPENDENT of the input dtypes (act, weight):
  // the wrapper only demands input compatibility with the reduction tree;
  // the requant block downstream can convert to any supported output format.
  // This mirrors the orchestrator's `quantize_mode` knob:
  //
  //   quantize_mode  out-type   requant path                wrapper class
  //     0            FP32       none (pass-through)         PEArrayWrapperFP32
  //     1            BF16       RequantBF16 (no scale)      PEArrayWrapperBF16
  //     2            E5M2       RequantFP8 (block-scaled)   PEArrayWrapper
  //     3            E4M3       RequantFP8                  PEArrayWrapper
  //     4            INT8       RequantINT8 (block-scaled)  PEArrayWrapperINT8
  //     5            E2M3       RequantFP8                  PEArrayWrapper
  //     6            E3M2       RequantFP8                  PEArrayWrapper
  //     (E2M1        FP4         RequantFP8                  PEArrayWrapper — no assigned mode #)
  //
  // If --out-type is omitted, defaults to activation type (backwards-compat
  // with the 6-key-configs behaviour where output = activation).
  private case class Opts(
    act:                String  = "",
    weight:             String  = "",
    scale:              String  = "",
    outType:            String  = "",            // "" = use act (default)
    mAcc:               Option[Int] = None,
    vec:                Int     = 4,
    tileRows:           Int     = 4,
    tileCols:           Int     = 16,
    blockSize:          Int     = 16,
    outdir:             Option[String] = None,
    emitIntegrated:     Boolean = true,
    emitStandaloneRq:   Boolean = true,
    csvPath:            String  = "data/macc_final_selection.csv",
    dpu:                String  = "fused",       // "fused" = FDPU (narrow-FP M_acc); "simple" = TemplateDPU (BF16)
  )

  // quantize_mode → out-type (matches orchestrator gen_pe_array_rtl.py _REQUANT_LABEL)
  private val QMODE_TO_OUT = Map(
    0 -> "FP32", 1 -> "BF16", 2 -> "E5M2", 3 -> "E4M3",
    4 -> "INT8", 5 -> "E2M3", 6 -> "E3M2", 7 -> "E2M1")

  private def usage(): Nothing = {
    System.err.println(
      """Usage: sbt "runMain mx.EmitTensorCore --act X --weight Y --scale Z [OPTIONS]"
        |
        |Required:
        |  --act    <INT8|E5M2|E4M3|E3M2|E2M3|E2M1>   Activation element type
        |  --weight <same>                             Weight element type
        |  --scale  <UE8M0|UE7M1|UE6M2|UE5M3|UE4M4|UE4M3>   Shared scale format
        |
        |Output selection (choose ONE):
        |  --out-type <FP32|BF16|E5M2|E4M3|E3M2|E2M3|E2M1|INT8>   Explicit output element type
        |  --quantize-mode <0..6>   Orchestrator-style: 0=FP32 1=BF16 2=E5M2 3=E4M3 4=INT8 5=E2M3 6=E3M2
        |  (omit both → output type = activation type)
        |
        |Optional:
        |  --m-acc <int>            Accumulator mantissa bits (default: from CSV, else fallback)
        |  --vec <int>              Vector size per cycle (default 4)
        |  --tile-rows <int>        Tile row count (default 4)
        |  --tile-cols <int>        Tile column count (default 16)
        |  --block-size <int>       MX block size (default 16)
        |  --outdir <path>          Output directory (default: generated/<label>/)
        |  --no-integrated          Skip integrated PE_Array.sv emit
        |  --no-standalone-requant  Skip standalone requant*.sv emit
        |  --csv <path>             M_acc CSV path (default data/macc_final_selection.csv)
        |""".stripMargin)
    sys.exit(1)
  }

  private def parseArgs(argv: Array[String]): Opts = {
    def parse(remaining: List[String], acc: Opts): Opts = remaining match {
      case Nil => acc
      case "--act"    :: v :: t => parse(t, acc.copy(act = v))
      case "--weight" :: v :: t => parse(t, acc.copy(weight = v))
      case "--scale"  :: v :: t => parse(t, acc.copy(scale = v))
      case "--out-type" :: v :: t => parse(t, acc.copy(outType = v))
      case "--quantize-mode" :: v :: t =>
        val ot = QMODE_TO_OUT.getOrElse(v.toInt,
          { System.err.println(s"Unknown --quantize-mode $v (valid: 0..7)"); sys.exit(1) })
        parse(t, acc.copy(outType = ot))
      case "--m-acc"  :: v :: t => parse(t, acc.copy(mAcc = Some(v.toInt)))
      case "--vec"       :: v :: t => parse(t, acc.copy(vec       = v.toInt))
      case "--tile-rows" :: v :: t => parse(t, acc.copy(tileRows  = v.toInt))
      case "--tile-cols" :: v :: t => parse(t, acc.copy(tileCols  = v.toInt))
      case "--block-size":: v :: t => parse(t, acc.copy(blockSize = v.toInt))
      case "--outdir" :: v :: t => parse(t, acc.copy(outdir = Some(v)))
      case "--csv"    :: v :: t => parse(t, acc.copy(csvPath = v))
      case "--dpu"    :: v :: t => parse(t, acc.copy(dpu = v))
      case "--no-integrated"        :: t => parse(t, acc.copy(emitIntegrated   = false))
      case "--no-standalone-requant":: t => parse(t, acc.copy(emitStandaloneRq = false))
      case "--help" :: _ | "-h" :: _ => usage()
      case unknown :: _ =>
        System.err.println(s"Unknown option: $unknown"); usage()
    }
    val o = parse(argv.toList, Opts())
    if (o.act.isEmpty || o.weight.isEmpty || o.scale.isEmpty) {
      System.err.println("--act, --weight, --scale are required."); usage()
    }
    o
  }

  // ── Format maps ───────────────────────────────────────────────────────────
  private val FMT = Map[String, Elem](
    "INT8" -> Elem.INT8, "E5M2" -> Elem.E5M2,
    "E4M3" -> Elem.E4M3, "E3M2" -> Elem.E3M2,
    "E2M3" -> Elem.E2M3, "E2M1" -> Elem.E2M1)
  private val SCL = Map[String, Scale](
    "UE8M0" -> Scale.UE8M0, "UE7M1" -> Scale.UE7M1,
    "UE6M2" -> Scale.UE6M2, "UE5M3" -> Scale.UE5M3,
    "UE4M4" -> Scale.UE4M4, "UE4M3" -> Scale.UE4M3)

  // ── Main (TemplateDPU-only) ──────────────────────────────────────────────────
  // The FDPU (fused) datapath + its M_acc/CSV resolution have been removed; this
  // emitter now produces only the TemplateDPU "PE_Array" (BF16 accumulator).
  private val opts = parseArgs(args)

  private val actType   = FMT.getOrElse(opts.act,    { System.err.println(s"Unknown --act ${opts.act}"); sys.exit(1) })
  private val wtType    = FMT.getOrElse(opts.weight, { System.err.println(s"Unknown --weight ${opts.weight}"); sys.exit(1) })
  private val scaleType = SCL.getOrElse(opts.scale,  { System.err.println(s"Unknown --scale ${opts.scale}"); sys.exit(1) })

  // Output type: --out-type / --quantize-mode > default (= act type)
  private val outTypeStr: String = if (opts.outType.nonEmpty) opts.outType else opts.act
  private val label  = s"${opts.act}_${opts.weight}_${opts.scale}_out${outTypeStr}"
  private val outdir = opts.outdir.getOrElse(s"generated/$label")
  new File(outdir).mkdirs()

  private val dpuCfg = DPUConfig(actType, wtType, scaleType, N = opts.vec)

  println(s"=== EmitTensorCore (TemplateDPU) ===")
  println(s"  input:    ${opts.act} × ${opts.weight} + ${opts.scale}")
  println(s"  output:   ${outTypeStr}")
  println(s"  geometry: tile=${opts.tileRows}×${opts.tileCols}, vec=${opts.vec}, block=${opts.blockSize}")
  println(s"  DPU:      TemplateDPU (BF16 accumulator)")
  println(s"  outdir:   ${outdir}")

  // TemplateDPU "PE_Array": drop-in module (same external ports as the old FDPU
  // wrapper); the requant block is bundled inside.
  private val simpleOut: TemplateOut = outTypeStr match {
    case "FP32" => TemplateOut.FP32
    case "BF16" => TemplateOut.BF16
    case "INT8" => TemplateOut.INT8
    case fp if Seq("E5M2", "E4M3", "E3M2", "E2M3", "E2M1").contains(fp) => TemplateOut.FP8(FMT(fp))
    case other  =>
      System.err.println(s"[error] Unknown output type: $other " +
        "(valid: FP32, BF16, INT8, E5M2, E4M3, E3M2, E2M3, E2M1).")
      sys.exit(1)
  }
  private val sCfg = TemplateArrayConfig(dpuCfg, opts.tileRows, opts.tileCols, simpleOut, opts.blockSize)
  if (opts.emitIntegrated)
    emitVerilog(new TemplatePEArray(sCfg), Array("--target-dir", outdir))
  println(s"[ok] Emitted to $outdir/")
}
