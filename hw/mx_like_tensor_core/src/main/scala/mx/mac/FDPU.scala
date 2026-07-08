package mx.mac

import chisel3._
import chisel3.util.Cat

// ============================================================
// Fused Dot-Product Unit — Post-Scale Reduction Tree (BASELINE)
// ============================================================
/** Fused dot-product unit: MAC → FixedFP Reduction Tree → ScaleComposition → Fused FP Accumulator.
 *
 *  Baseline cycleFP architecture used by the thesis.  Two earlier
 *  architecture explorations (blockdef, kulisch) lived in this file and
 *  have been moved to [[mx.mac.deferred_archs]]; they are reachable from
 *  the DSE-compare emit but are not on the deployed PE path.
 *
 *  Pipeline (fully combinational within each cycle):
 *
 *    ┌─ lane 0 ─┐
 *    │ CustomOp ├──►┐
 *    └──────────┘   │   ┌───────────────────┐   ┌──────────────────┐   ┌──────────────────────┐
 *        ...        ├──►│  FixedFP          ├──►│ ScaleComposition ├──►│ FusedScaleAccumulator │──► accOut
 *    ┌─ lane N-1─┐  │   │  Reduction Tree   │   │ (raw fixed term) │   │ align+add+LZD+RNE      │
 *    │ CustomOp  ├──►   └───────────────────┘   └──────────────────┘   └──────────────────────┘
 *    └───────────┘
 *
 *  Scaled-term path RNE count = 2 (tree-exit + fused accumulator).  The old
 *  standalone ScaleToFPn (a 3rd RNE + its own LZC) has been fused into the
 *  accumulator so the datapath matches the "Scale composition → Alignment(E_SW)
 *  → + → LZD Norm + RNE" figure exactly.
 *
 *  Reduction tree design:
 *    - One maxExp comparator tree across N lanes
 *    - N bounded alignment right-shifts (≤ productExpRange positions)
 *    - (N−1) plain 2's-complement integer adders
 *    - One LZC + normalisation shift + RNE round at the output
 *
 *  Correctness: sa/sb are shared across all lanes, so by the distributive property:
 *    Σᵢ(macᵢ × sa × sb) = (Σᵢ macᵢ) × sa × sb
 *
 *  Accumulator precision:
 *    The accumulator register stores a reduced-precision FP value with `accMantBits`
 *    mantissa bits (resolved from data/macc_final_selection.csv by the emit flow).
 *    This is sufficient because accumulation noise stays below the requant noise floor
 *    when accMantBits ≥ rqFloor + ½·log₂(K).
 *    accOut is (1+8+accMantBits) bits wide — the native register width with no
 *    zero-padding.  Downstream consumers that need IEEE-754 FP32 must right-pad
 *    the mantissa to 23 bits.
 *
 *  @param scfg        ScaleAddConfig describing element and scale types.
 *  @param vectorSize  Number of parallel MACs per cycle (>= 1).
 *  @param K           Accumulation depth.  Used to derive the default accMantBits.
 *  @param accMantBits Accumulator mantissa bits — required; resolved from
 *                     data/macc_final_selection.csv by the emit flow. 23 = FP32.
 *  @param treeArch    Tree architecture.  Only Generic is deployed (the
 *                     specialised arch variants were pruned).
 *  @param istest      Enable debug ports for simulation visibility.
 *  @param noEarlyRNE  Counterfactual: skip the tree-exit RNE; the tree emits
 *                     its full absMagW mantissa unrounded, leaving only the
 *                     fused-accumulator RNE; forces M_acc = 23 (FP32).  Used by
 *                     the chapter's area-saved comparison vs. our 2-RNE design
 *                     (tree-exit + FusedScaleAccumulator).
 *  @param widenUE8M0  UE8M0-only optimisation: widen tree-exit mantissa to M_acc
 *                     bits so the per-cycle tree-exit RNE becomes M_acc-dependent.
 *                     Default true.  Strict area-iso, accuracy-gain change.
 */
class FDPU(
  val scfg:        ScaleAddConfig,
  val vectorSize:  Int,
  val K:           Int      = 32,
  val accMantBits: Int,          // required — resolved from macc_final_selection.csv (no K-based auto)
  val treeArch:    TreeArch = TreeArch.Generic,
  val istest:      Boolean  = false,
  val noEarlyRNE:  Boolean  = false,
  val widenUE8M0:  Boolean  = true,
  /** Override the productExpRange (alignment-shift cap) handed to the reduction
   *  tree.  Default −1 → use scfg.productExpRange (the strict upper bound on
   *  diffRaw).  Setting a smaller value caps alignment shifts: any lane whose
   *  exp differs from maxExp by more than this gets clamped, trading precision
   *  on outlier lanes for narrower tree internal width.  Used by the tree
   *  shrink DSE — see DSE notes in the chapter. */
  val treeProductExpRangeOverride: Int = -1,
) extends Module {
  require(vectorSize >= 1, "vectorSize must be >= 1")
  require(K >= 1, "K must be >= 1")

  // ── Shared width derivation ───────────────────────────────────────────
  private val w = FDPUWidthMath(scfg, vectorSize, K,
    accMantBits = accMantBits,
    noEarlyRNE  = noEarlyRNE,
    widenUE8M0  = widenUE8M0)
  /** Public so tests can pull the resolved M_acc without re-deriving. */
  val actualAccMantBits: Int       = w.actualAccMantBits
  val treeExtraMantBits: Int       = w.treeExtraMantBits
  val effectiveTreeOutMantW: Int   = w.effectiveTreeOutMantW
  val effectiveScaleAddMantW: Int  = w.effectiveScaleAddMantW
  val effectiveExpW: Int           = w.effectiveExpW
  private val fpNW = w.fpNW

  private val wA = scfg.elementTypeA.totalWidth
  private val wB = scfg.elementTypeB.totalWidth

  override def desiredName =
    if (!istest) "BFP_PE"
    else s"FDPUPostScale_${scfg.elementTypeA.name}_x_${scfg.elementTypeB.name}" +
         s"_scale_${scfg.stype.name}_vec${vectorSize}_K${K}_acc${actualAccMantBits}b"

  val io = IO(new Bundle {
    val op_a_i        = Input(UInt((vectorSize * wA).W))
    val op_b_i        = Input(UInt((vectorSize * wB).W))
    val share_exp_A_i = Input(UInt(scfg.stype.totalScaleWidth.W))
    val share_exp_B_i = Input(UInt(scfg.stype.totalScaleWidth.W))
    val validIn  = Input(Bool())
    val resetAcc = Input(Bool())
    val validOut = Output(Bool())
    /** Narrow FP output: {sign[1], exp[8], mant[actualAccMantBits]}.
     *  Downstream consumers that need IEEE-754 FP32 must zero-extend the
     *  mantissa to 23 bits (right-pad). */
    val accOut   = Output(UInt(fpNW.W))

    val debug = if (istest) Some(new Bundle {
      val all_lanes_op_sign = Output(Vec(vectorSize, UInt(1.W)))
      val all_lanes_op_exp  = Output(Vec(vectorSize, SInt(scfg.resOperatorExpWidth.W)))
      val all_lanes_op_mant = Output(Vec(vectorSize, UInt(scfg.resOperatorMantWidth.W)))
      val tree_out_sign     = Output(UInt(1.W))
      val tree_out_exp      = Output(SInt(scfg.resOperatorExpWidth.W))
      val tree_out_mant     = Output(UInt(scfg.resOperatorMantWidth.W))
      val sa_out_sign       = Output(UInt(1.W))
      val sa_out_exp        = Output(SInt(scfg.resScaleAddExpWidth.W))
      val sa_out_mant       = Output(UInt(scfg.resScaleAddMantWidth.W))
      val reducedSum        = Output(UInt(32.W))
      /** Widths padded to 96 bits for uniform debug ports across arch variants.
       *  Baseline: innerAcc == outerAcc == the single accReg. */
      val innerAccReg       = Output(UInt(96.W))
      val outerAccReg       = Output(UInt(96.W))
      val blockDoneFlag     = Output(Bool())
    }) else None
  })

  // ── Per-lane MAC ───────────────────────────────────────────────────────
  val laneOp = Wire(Vec(vectorSize, new CustomFP(scfg.resOperatorExpWidth, scfg.resOperatorMantWidth)))
  for (i <- 0 until vectorSize) {
    val op = Module(new CustomOperator(OperatorConfig(scfg.elementTypeA, scfg.elementTypeB)))
    op.io.inA := io.op_a_i((i + 1) * wA - 1, i * wA)
    op.io.inB := io.op_b_i((i + 1) * wB - 1, i * wB)

    laneOp(i).sign := op.io.outSign
    laneOp(i).mant := op.io.outMant
    laneOp(i).exp  := op.io.outExp

    io.debug.foreach { d =>
      d.all_lanes_op_sign(i) := op.io.outSign
      d.all_lanes_op_exp(i)  := op.io.outExp
      d.all_lanes_op_mant(i) := op.io.outMant
    }
  }

  // ── FixedFP reduction tree ────────────────────────────────────────────
  // outMantW = effectiveTreeOutMantW exposes additional bits beyond the legacy
  // resScaleAddMantWidth−3 cap; expW is widened in lockstep to absorb the
  // extra negative range from the post-LZC exp arithmetic.
  val effectiveProductExpRange =
    if (treeProductExpRangeOverride > 0) treeProductExpRangeOverride
    else scfg.productExpRange
  val tree = Module(new FixedFPReductionTree(
    expW            = effectiveExpW,
    inMantW         = scfg.resOperatorMantWidth,
    outMantW        = effectiveTreeOutMantW,
    vectorSize      = vectorSize,
    productExpRange = effectiveProductExpRange,
    arch            = treeArch,
    skipFinalRound  = noEarlyRNE   // Counterfactual: skip tree-exit RNE
  ))
  for (i <- 0 until vectorSize) {
    tree.io.inputs(i).sign := laneOp(i).sign
    tree.io.inputs(i).mant := laneOp(i).mant
    tree.io.inputs(i).exp  := laneOp(i).exp
  }
  val treeOut = tree.io.out  // CustomFP(effectiveExpW, effectiveTreeOutMantW)

  io.debug.foreach { d =>
    d.tree_out_sign := treeOut.sign
    d.tree_out_exp  := treeOut.exp
    val mantDebug = if (effectiveTreeOutMantW >= scfg.resOperatorMantWidth)
                      treeOut.mant(effectiveTreeOutMantW - 1,
                                   effectiveTreeOutMantW - scfg.resOperatorMantWidth)
                    else treeOut.mant
    d.tree_out_mant := mantDebug
  }

  // ── Reduced-precision FP accumulator register ─────────────────────────
  // accReg holds fpNW = (1 + 8 + actualAccMantBits) bits.  Declared before the
  // path select because the fused accumulator reads accReg (its E_SW anchor).
  val asyncRstN = (!reset.asBool).asAsyncReset
  val accReg    = withReset(asyncRstN)(RegInit(0.U(fpNW.W)))
  val validReg  = withReset(asyncRstN)(RegInit(false.B))

  // ── Scale composition + accumulate: path selected at elaboration time ──
  // All intermediate values use fpNW = 1+8+actualAccMantBits bits throughout.
  //   UE8M0     : DirectToFPn (pure exp arith, already normalised) → FPNAdder.
  //               Already 2 RNE (tree-exit + adder); left unchanged.
  //   non-UE8M0 : ScaleComposition (raw fixed-point term) → FusedScaleAccumulator,
  //               a single align+add+LZD+RNE.  Matches the figure's stage 4/5
  //               fusion; 2 RNE end-to-end (was 3 via the old ScaleToFPn).
  val nextAcc = Wire(UInt(fpNW.W))

  if (scfg.stype.mantScaleWidth == 0) {
    // ── Path A: UE8M0 — DirectToFPn → FPNAdder ───────────────────────────
    val d2fpn = Module(new DirectToFPn(scfg, actualAccMantBits,
      opMantWOverride = if (widenUE8M0) effectiveTreeOutMantW else -1,
      opExpWOverride  = effectiveExpW))
    d2fpn.io.inOpSign      := treeOut.sign
    d2fpn.io.inOpExp       := treeOut.exp
    d2fpn.io.inOpMant      := treeOut.mant
    d2fpn.io.inShareScaleA := io.share_exp_A_i
    d2fpn.io.inShareScaleB := io.share_exp_B_i

    val accAdder = Module(new FPNAdder(actualAccMantBits))
    accAdder.io.a := accReg
    accAdder.io.b := d2fpn.io.out
    nextAcc       := accAdder.io.out

    io.debug.foreach { d =>
      val adjA = io.share_exp_A_i.zext - scfg.stype.bias.S
      val adjB = io.share_exp_B_i.zext - scfg.stype.bias.S
      val expDebug = Wire(SInt(scfg.resScaleAddExpWidth.W))
      expDebug := treeOut.exp + adjA + adjB
      d.sa_out_sign := treeOut.sign
      d.sa_out_exp  := expDebug
      d.sa_out_mant := Cat(0.U(2.W), treeOut.mant)
      d.reducedSum  := (if (actualAccMantBits < 23)
                          Cat(d2fpn.io.out, 0.U((23 - actualAccMantBits).W))
                        else d2fpn.io.out)
    }
  } else {
    // ── Path B: non-UE8M0 — ScaleComposition → FusedScaleAccumulator ─────
    val sc = Module(new ScaleComposition(scfg,
      inOpMantWOverride = effectiveTreeOutMantW,
      inOpExpWOverride  = effectiveExpW))
    sc.io.inOpSign      := treeOut.sign
    sc.io.inOpExp       := treeOut.exp
    sc.io.inOpMant      := treeOut.mant
    sc.io.inShareScaleA := io.share_exp_A_i
    sc.io.inShareScaleB := io.share_exp_B_i

    val fusedAcc = Module(new FusedScaleAccumulator(scfg,
      mantBits  = actualAccMantBits,
      termMantW = sc.effectiveOutMantW,
      termExpW  = sc.effectiveOutExpW))
    fusedAcc.io.accIn    := accReg
    fusedAcc.io.termSign := sc.io.outSign
    fusedAcc.io.termExp  := sc.io.outExp
    fusedAcc.io.termMant := sc.io.outMant
    nextAcc              := fusedAcc.io.accOut

    io.debug.foreach { d =>
      d.sa_out_sign := sc.io.outSign
      d.sa_out_exp  := sc.io.outExp(scfg.resScaleAddExpWidth - 1, 0).asSInt
      val saMantDebug = if (effectiveScaleAddMantW >= scfg.resScaleAddMantWidth)
                          sc.io.outMant(effectiveScaleAddMantW - 1,
                                        effectiveScaleAddMantW - scfg.resScaleAddMantWidth)
                        else sc.io.outMant
      d.sa_out_mant := saMantDebug
      // Fused path has no standalone FPn term; reducedSum debug port retired.
      d.reducedSum  := 0.U
    }
  }

  when(io.resetAcc) {
    accReg   := 0.U
    validReg := false.B
  }.elsewhen(io.validIn) {
    accReg   := nextAcc
    validReg := true.B
  }.otherwise {
    validReg := false.B
  }

  io.validOut := validReg
  io.accOut := accReg

  io.debug.foreach { d =>
    // Baseline: innerAcc == outerAcc == the one accReg.
    d.innerAccReg   := Cat(0.U((96 - fpNW).W), accReg)
    d.outerAccReg   := Cat(0.U((96 - fpNW).W), accReg)
    d.blockDoneFlag := io.validIn   // every valid cycle is "block-done"
  }
}

// ============================================================
// Emission helpers (baseline only)
// ============================================================
// Single-config + full-sweep emit objects.  The DSE-compare emit
// (DSECompareEmitMain) moved to mx.mac.deferred_archs.DSEEmitDriver
// since its 4-way comparison includes the dropped blockdef/kulisch archs.

/** M_acc source of truth = data/macc_final_selection.csv — the same table the
 *  deployed EmitTensorCore reads.  The K-based AccPrecision heuristic has been
 *  removed, so every emit resolves M_acc from this CSV. */
object MaccCsv {
  private lazy val table: Map[(String, String, String), Int] = {
    val f = new java.io.File("data/macc_final_selection.csv")
    require(f.exists, s"M_acc CSV not found: ${f.getAbsolutePath}")
    val src   = scala.io.Source.fromFile(f)
    val lines = try src.getLines().toList finally src.close()
    val hdr   = lines.head.split(",").map(_.trim)
    val (iA, iB, iS, iM) = (hdr.indexOf("act"), hdr.indexOf("weight"), hdr.indexOf("scale"), hdr.indexOf("m_acc"))
    require(iA >= 0 && iB >= 0 && iS >= 0 && iM >= 0,
      "macc_final_selection.csv must have columns act,weight,scale,m_acc")
    lines.drop(1).flatMap { ln =>
      val c = ln.split(",").map(_.trim)
      if (c.length > iM) Some((c(iA), c(iB), c(iS)) -> c(iM).toInt) else None
    }.toMap
  }
  def lookup(act: String, weight: String, scale: String): Int =
    table.getOrElse((act, weight, scale),
      throw new NoSuchElementException(s"M_acc for ($act,$weight,$scale) not in data/macc_final_selection.csv"))
}

object FDPUMain extends App {
  val (act, weight, scale) = ("E2M1", "E2M1", "UE5M3")
  val scfg       = ScaleAddConfig(MXFormats.E2M1, MXFormats.E2M1, ScaleFormats.UE5M3)
  val vectorSize = 4
  emitVerilog(
    new FDPU(scfg, vectorSize, accMantBits = MaccCsv.lookup(act, weight, scale), istest = false),
    Array("--target-dir", s"generated/fdpu_test/default_vec${vectorSize}")
  )
}

/** Parameterised single-PE emitter for the per-PE testbench / power flow.
 *  Usage: runMain mx.mac.PEEmitMain --act E4M3 --weight E2M1 --scale UE5M3 --vec 4 --K 64 --outdir <dir>
 *  Emits BFP_PE.sv plus a pe_meta.txt sidecar (op_a/op_b/acc/scale widths, M_acc).
 */
object PEEmitMain extends App {
  private val elem = Map("E5M2"->MXFormats.E5M2, "E4M3"->MXFormats.E4M3, "E3M2"->MXFormats.E3M2,
                         "E2M3"->MXFormats.E2M3, "E2M1"->MXFormats.E2M1, "INT8"->MXFormats.INT8)
  private val scal = Map("UE8M0"->ScaleFormats.UE8M0, "UE7M1"->ScaleFormats.UE7M1,
                         "UE6M2"->ScaleFormats.UE6M2, "UE5M3"->ScaleFormats.UE5M3,
                         "UE4M4"->ScaleFormats.UE4M4, "UE4M3"->ScaleFormats.UE4M3)
  private def arg(k: String, d: String): String = {
    val i = args.indexOf(k); if (i >= 0 && i + 1 < args.length) args(i + 1) else d
  }
  val act    = arg("--act", "E4M3")
  val weight = arg("--weight", "E2M1")
  val scale  = arg("--scale", "UE5M3")
  val vec    = arg("--vec", "4").toInt
  val K      = arg("--K", "64").toInt
  val outdir = arg("--outdir", s"generated/pe_tb/${act}_${weight}_${scale}_vec${vec}_K${K}")

  val scfg = ScaleAddConfig(elem(act), elem(weight), scal(scale))
  val mAcc = MaccCsv.lookup(act, weight, scale)      // from macc_final_selection.csv
  emitVerilog(new FDPU(scfg, vec, K = K, accMantBits = mAcc, istest = false),
              Array("--target-dir", outdir))

  val opAW  = vec * elem(act).totalWidth
  val opBW  = vec * elem(weight).totalWidth
  val accW  = 1 + 8 + mAcc
  val scW   = scal(scale).totalScaleWidth
  val meta  = s"act $act\nweight $weight\nscale $scale\nvec $vec\nK $K\n" +
              s"m_acc $mAcc\nop_a_w $opAW\nop_b_w $opBW\nacc_w $accW\nscale_w $scW\n"
  java.nio.file.Files.write(java.nio.file.Paths.get(outdir, "pe_meta.txt"), meta.getBytes)
  println(s"[PEEmit] $act x $weight / $scale vec=$vec K=$K -> $outdir (M_acc=$mAcc, accW=$accW)")
}

/** Batch-emit BFP_PE + pe_meta.txt for every (act>=weight) x scale combo in the
 *  manifest, into generated/pe_tb/<act>_<weight>_<scale>/ — the per-PE power flow.
 *  One sbt session (much faster than 126 separate runMain calls).
 *  Usage: runMain mx.mac.AllPETbEmitMain [vec] [K]
 */
object AllPETbEmitMain extends App {
  private val prec  = List("E2M1", "E2M3", "E3M2", "E4M3", "E5M2", "INT8")  // ascending precision
  private val elem  = Map("E5M2"->MXFormats.E5M2, "E4M3"->MXFormats.E4M3, "E3M2"->MXFormats.E3M2,
                          "E2M3"->MXFormats.E2M3, "E2M1"->MXFormats.E2M1, "INT8"->MXFormats.INT8)
  private val scales = List("UE8M0", "UE7M1", "UE6M2", "UE5M3", "UE4M4", "UE4M3")
  private val scal  = Map("UE8M0"->ScaleFormats.UE8M0, "UE7M1"->ScaleFormats.UE7M1,
                          "UE6M2"->ScaleFormats.UE6M2, "UE5M3"->ScaleFormats.UE5M3,
                          "UE4M4"->ScaleFormats.UE4M4, "UE4M3"->ScaleFormats.UE4M3)
  val vec = if (args.length > 0) args(0).toInt else 4
  val K   = if (args.length > 1) args(1).toInt else 64

  var n = 0
  for {
    (a, ai) <- prec.zipWithIndex
    (w, wi) <- prec.zipWithIndex
    if ai >= wi                                    // act precision >= weight precision
    s <- scales
  } {
    val scfg   = ScaleAddConfig(elem(a), elem(w), scal(s))
    val outdir = s"generated/pe_tb/${a}_${w}_${s}"
    val mAcc   = MaccCsv.lookup(a, w, s)             // from macc_final_selection.csv
    emitVerilog(new FDPU(scfg, vec, K = K, accMantBits = mAcc, istest = false),
                Array("--target-dir", outdir))
    val meta = s"act $a\nweight $w\nscale $s\nvec $vec\nK $K\n" +
               s"m_acc $mAcc\nop_a_w ${vec * elem(a).totalWidth}\nop_b_w ${vec * elem(w).totalWidth}\n" +
               s"acc_w ${1 + 8 + mAcc}\nscale_w ${scal(s).totalScaleWidth}\n"
    java.nio.file.Files.write(java.nio.file.Paths.get(outdir, "pe_meta.txt"), meta.getBytes)
    n += 1
  }
  println(s"[AllPETbEmit] emitted $n PEs (vec=$vec K=$K) under generated/pe_tb/")
}

object AllFDPUMain extends App {
  val vectorSizes  = Seq(4, 16)
  val elementTypes = MXFormats.allElementTypes
  val scaleTypes   = Seq(ScaleFormats.UE8M0, ScaleFormats.UE6M2, ScaleFormats.UE4M4)
  val lowPrecision = Set(MXFormats.E3M2, MXFormats.E2M3, MXFormats.E2M1)

  for {
    (typeA, i) <- elementTypes.zipWithIndex
    typeB      <- elementTypes.drop(i)
    if !(lowPrecision(typeA) && lowPrecision(typeB))
    stype      <- scaleTypes
    vsize      <- vectorSizes
  } {
    val scfg = ScaleAddConfig(typeA, typeB, stype)
    println(
      s"Generating FDPU: ${typeA.name} x ${typeB.name}, " +
      s"scale ${stype.name}, vectorSize=$vsize"
    )
    emitVerilog(
      new FDPU(scfg, vsize, accMantBits = MaccCsv.lookup(typeA.name, typeB.name, stype.name), istest = false),
      Array("--target-dir",
        s"generated/post_scale/${typeA.name}_${typeB.name}_${stype.name}_vec${vsize}")
    )
  }
}
