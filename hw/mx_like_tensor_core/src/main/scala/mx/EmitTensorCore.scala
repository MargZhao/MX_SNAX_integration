package mx

import chisel3._
import mx.mac.{MXFormats, ScaleFormats, ScaleAddConfig, TreeArch, ElementType, ScaleType}
import mx.array.{
  ArchOverride,
  PEArrayConfig, PEArrayINT8Config, PEArrayFP32Config, PEArrayBF16Config,
  PEArrayWrapper, PEArrayWrapperINT8, PEArrayWrapperFP32, PEArrayWrapperBF16
}
import mx.requant.{RequantConfig, RequantFP8, RequantINT8, RequantINT8Config}
import java.io.File
import scala.io.Source

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
  private val FMT = Map[String, ElementType](
    "INT8" -> MXFormats.INT8, "E5M2" -> MXFormats.E5M2,
    "E4M3" -> MXFormats.E4M3, "E3M2" -> MXFormats.E3M2,
    "E2M3" -> MXFormats.E2M3, "E2M1" -> MXFormats.E2M1)
  private val SCL = Map[String, ScaleType](
    "UE8M0" -> ScaleFormats.UE8M0, "UE7M1" -> ScaleFormats.UE7M1,
    "UE6M2" -> ScaleFormats.UE6M2, "UE5M3" -> ScaleFormats.UE5M3,
    "UE4M4" -> ScaleFormats.UE4M4, "UE4M3" -> ScaleFormats.UE4M3)

  private val DEFAULT_M_PER_ACT = Map(
    "INT8" -> 14, "E5M2" -> 11, "E4M3" -> 12,
    "E3M2" -> 11, "E2M3" -> 12, "E2M1" ->  9)

  // ── M_acc lookup: CLI > CSV > per-activation fallback ─────────────────────
  //
  // Accepts either of two CSV schemas:
  //   (a) 126-config extended    : act,   weight, scale, m_acc [, source, ...]
  //   (b) 14-config raw sweep    : typeA, typeB, scale, M_sel [, eps_acc, floor]
  // Column names picked in that priority order.  Extra columns ignored.
  private def loadCsvMacc(csvPath: String): Map[(String, String, String), Int] = {
    val f = new File(csvPath)
    if (!f.exists) {
      System.err.println(s"[warn] CSV not found: $csvPath — falling back to per-activation defaults")
      return Map.empty
    }
    val src = Source.fromFile(f)
    val lines = try src.getLines().toList finally src.close()
    if (lines.isEmpty) return Map.empty
    val header = lines.head.split(",").map(_.trim)
    // Try schema (a) first, then (b).
    val (iA, iB, iS, iM, schema) = {
      val a = (header.indexOf("act"),   header.indexOf("weight"), header.indexOf("scale"), header.indexOf("m_acc"))
      if (a._1 >= 0 && a._2 >= 0 && a._3 >= 0 && a._4 >= 0)
        (a._1, a._2, a._3, a._4, "extended")
      else {
        val b = (header.indexOf("typeA"), header.indexOf("typeB"), header.indexOf("scale"), header.indexOf("M_sel"))
        if (b._1 >= 0 && b._2 >= 0 && b._3 >= 0 && b._4 >= 0)
          (b._1, b._2, b._3, b._4, "raw-sweep")
        else
          (-1, -1, -1, -1, "unknown")
      }
    }
    if (schema == "unknown") {
      System.err.println(s"[warn] CSV $csvPath missing expected columns " +
                         "(act,weight,scale,m_acc) or (typeA,typeB,scale,M_sel); ignoring")
      return Map.empty
    }
    lines.drop(1).flatMap { ln =>
      val c = ln.split(",").map(_.trim)
      if (c.length > math.max(math.max(iA, iB), math.max(iS, iM)))
        Some((c(iA), c(iB), c(iS)) -> c(iM).toInt)
      else None
    }.toMap
  }

  private def resolveMacc(o: Opts): (Int, String) = {
    o.mAcc match {
      case Some(v) => (v, "CLI --m-acc")
      case None =>
        val csv = loadCsvMacc(o.csvPath)
        csv.get((o.act, o.weight, o.scale)) match {
          case Some(v) => (v, s"CSV ${o.csvPath}")
          case None =>
            DEFAULT_M_PER_ACT.get(o.act) match {
              case Some(v) => (v, "per-activation fallback")
              case None =>
                System.err.println(s"[error] Cannot resolve M_acc for (${o.act}, ${o.weight}, ${o.scale}). " +
                                   "Pass --m-acc explicitly or extend DEFAULT_M_PER_ACT.")
                sys.exit(1)
            }
        }
    }
  }

  // ── Main ──────────────────────────────────────────────────────────────────
  private val opts0 = parseArgs(args)

  // Resolve output type first (so M_acc override for FP32 can apply below).
  private val outTypeStr0: String =
    if (opts0.outType.nonEmpty) opts0.outType else opts0.act

  // FP32 pass-through: force M_acc=23 so the emitted io_result element width
  // is genuinely 32 bits (1+8+23) — otherwise the wrapper's narrow-FP output
  // (1+8+M_acc where M_acc is typically 8..14) won't match the orchestrator's
  // _o_bitwidth(quantize_mode=0)=32 assumption on the streamer writer side.
  private val opts =
    if (outTypeStr0 == "FP32" && opts0.mAcc.isEmpty)
      opts0.copy(mAcc = Some(23))
    else opts0

  private val (mAcc, mAccSrc0) = resolveMacc(opts)
  private val mAccSrc =
    if (outTypeStr0 == "FP32" && opts0.mAcc.isEmpty) "forced-FP32-passthrough"
    else mAccSrc0

  private val actType   = FMT.getOrElse(opts.act,    { System.err.println(s"Unknown --act ${opts.act}"); sys.exit(1) })
  private val wtType    = FMT.getOrElse(opts.weight, { System.err.println(s"Unknown --weight ${opts.weight}"); sys.exit(1) })
  private val scaleType = SCL.getOrElse(opts.scale,  { System.err.println(s"Unknown --scale ${opts.scale}"); sys.exit(1) })

  // Resolve output type: --out-type / --quantize-mode > default (= act type)
  private val outTypeStr: String =
    if (opts.outType.nonEmpty) opts.outType else opts.act
  private val label  = s"${opts.act}_${opts.weight}_${opts.scale}_M${mAcc}_out${outTypeStr}"
  private val outdir = opts.outdir.getOrElse(s"generated/$label")
  new File(outdir).mkdirs()

  private val macCfg = ScaleAddConfig(actType, wtType, scaleType)
  private val arch   = Some(ArchOverride(TreeArch.Generic, 1))
  private val inW    = 1 + 8 + mAcc

  println(s"=== EmitTensorCore ===")
  println(s"  input:    ${opts.act} × ${opts.weight} + ${opts.scale}")
  println(s"  M_acc:    ${mAcc} (${mAccSrc})")
  println(s"  output:   ${outTypeStr}")
  println(s"  geometry: tile=${opts.tileRows}×${opts.tileCols}, vec=${opts.vec}, block=${opts.blockSize}")
  println(s"  label:    ${label}")
  println(s"  outdir:   ${outdir}")
  println(s"  PE narrow-FP output width (before requant): 1 + 8 + M_acc = FP${inW}")

  // ── Dispatch on OUTPUT TYPE (not input) — orchestrator quantize_mode semantics.
  outTypeStr match {

    case "FP32" =>
      // Pass-through: PE array's FP32 accumulator IS the output.  No requant.
      val arrayCfg = PEArrayFP32Config(
        macCfg              = macCfg,
        vectorSize          = opts.vec,
        tileRows            = opts.tileRows,
        tileCols            = opts.tileCols,
        accMantBitsOverride = mAcc)
      if (opts.emitIntegrated)
        emitVerilog(new PEArrayWrapperFP32(arrayCfg), Array("--target-dir", outdir))
      if (opts.emitStandaloneRq)
        println("  [note] --emit-standalone-requant ignored for FP32 pass-through (no requant block)")

    case "BF16" =>
      // BF16 truncation with no block scaling — RequantBF16 is a per-element
      // FP32 → BF16 rounding, independent of the shared scale.
      val arrayCfg = PEArrayBF16Config(
        macCfg              = macCfg,
        vectorSize          = opts.vec,
        tileRows            = opts.tileRows,
        tileCols            = opts.tileCols,
        accMantBitsOverride = mAcc)
      if (opts.emitIntegrated)
        emitVerilog(new PEArrayWrapperBF16(arrayCfg), Array("--target-dir", outdir))
      if (opts.emitStandaloneRq)
        println("  [note] --emit-standalone-requant ignored for BF16 (bundled into wrapper)")

    case "INT8" =>
      val rqCfg = RequantINT8Config(
        blockSize      = opts.blockSize,
        tileRows       = opts.tileRows,
        tileCols       = opts.tileCols,
        scaleType      = scaleType,
        inputMantWidth = mAcc)
      val arrayCfg = PEArrayINT8Config(
        macCfg              = macCfg,
        vectorSize          = opts.vec,
        tileRows            = opts.tileRows,
        tileCols            = opts.tileCols,
        requantCfg          = rqCfg,
        archOverride        = arch,
        accMantBitsOverride = mAcc)
      if (opts.emitIntegrated)
        emitVerilog(new PEArrayWrapperINT8(arrayCfg), Array("--target-dir", outdir))
      if (opts.emitStandaloneRq)
        emitVerilog(new RequantINT8(rqCfg), Array("--target-dir", s"$outdir/requant_standalone"))

    case fp if Seq("E5M2", "E4M3", "E3M2", "E2M3", "E2M1").contains(fp) =>
      val outType = FMT(fp)
      val rqCfg = RequantConfig(
        blockSize      = opts.blockSize,
        tileRows       = opts.tileRows,
        tileCols       = opts.tileCols,
        outputType     = outType,
        scaleType      = scaleType,
        inputMantWidth = mAcc)
      val arrayCfg = PEArrayConfig(
        macCfg              = macCfg,
        vectorSize          = opts.vec,
        tileRows            = opts.tileRows,
        tileCols            = opts.tileCols,
        requantCfg          = rqCfg,
        archOverride        = arch,
        accMantBitsOverride = mAcc)
      if (opts.emitIntegrated)
        emitVerilog(new PEArrayWrapper(arrayCfg), Array("--target-dir", outdir))
      if (opts.emitStandaloneRq)
        emitVerilog(new RequantFP8(rqCfg), Array("--target-dir", s"$outdir/requant_standalone"))

    case other =>
      System.err.println(
        s"[error] Unknown --out-type / quantize-mode target: $other. " +
        "Valid: FP32, BF16, INT8, E5M2, E4M3, E3M2, E2M3, E2M1.")
      sys.exit(1)
  }

  println(s"[ok] Emitted to $outdir/")
}
