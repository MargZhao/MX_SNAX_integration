// mx_like_simple_core — 4-way dot-product unit with BF16 accumulator.
//
// Architecture: Lutz et al. ARITH 2024 "Fused FP8 4-Way Dot Product With
// Scaling and FP32 Accumulation", Section II.B (Early Accumulation).
// Extensions over Lutz: (1) per-config tailored widths, (2) fractional
// scale mantissa via post-tree multiplier, (3) BF16 output.
//
// Module hierarchy (each submodule gets a distinct RTL block so synthesis
// can measure per-stage PPA):
//
//   SimpleDPU_<A>_<W>_<S>
//   ├── LaneMul_<A>_<W>            × N  (per-lane FP or INT multiply)
//   ├── AlignSumTree_<A>_<W>_vec4  × 1  (barrel-shift align + signed sum)
//   ├── ScaleMult_<A>_<W>_<S>      × 1  (only when S.m > 0; UE8M0 skips)
//   └── AccUpdate_<A>_<W>_<S>      × 1  (accreg align + add + LSC + RNE)
//
// Timing / reset: async active-low reset (matches mx_like_tensor_core and
// chisel_acc convention — `withReset((!reset.asBool).asAsyncReset)(RegInit(...))`
// produces `always_ff @(posedge clock or posedge reset)` in the emitted SV).

package mx_simple

import chisel3._
import chisel3.util._

// ─────────────────────────────────────────────────────────────
// Bundles
// ─────────────────────────────────────────────────────────────

/** BF16 output record: 1 sign + 8 exp + 7 mant. */
class BF16Reg extends Bundle {
  val sign = Bool()
  val exp  = UInt(BF16.expBits.W)
  val mant = UInt(BF16.mantBits.W)
}

/** One element operand: 1 sign + eE exp + eM mant.
  * INT8 (e==0) still has a sign bit; the exp field is 0-width. */
class ElemOperand(val el: Elem) extends Bundle {
  val sign = Bool()
  val exp  = UInt(el.e.W)
  val mant = UInt(el.m.W)
}

/** Block scale operand: eE exp + eM mant (unsigned). */
class ScaleOperand(val sc: Scale) extends Bundle {
  val exp  = UInt(sc.e.W)
  val mant = UInt(sc.m.W)
}

/** Output of one lane's multiply: unsigned mant + signed exp + sign. */
class LaneProduct(prodMantW: Int, expW: Int) extends Bundle {
  val mant = UInt(prodMantW.W)
  val exp  = SInt(expW.W)
  val sign = Bool()
}

// ─────────────────────────────────────────────────────────────
// Submodule 1: LaneMul — per-lane operand multiply
// ─────────────────────────────────────────────────────────────

/** Per-lane multiplier.
  *
  * FP:   sig = {hidden=(exp!=0), mant};  exp_out = (exp-A.bias) + (exp-W.bias)
  *       with subnormal fix (biased 0 -> effective biased 1).
  * INT8: sig = mant with 0 MSB (no hidden);  exp_out = A.impSc + W.impSc (const).
  */
class LaneMul(A: Elem, W: Elem, expW: Int) extends Module {
  override def desiredName = s"LaneMul_${A.name}_${W.name}"

  private val prodMantW = (A.m + 1) + (W.m + 1)

  val io = IO(new Bundle {
    val a   = Input(new ElemOperand(A))
    val w   = Input(new ElemOperand(W))
    val out = Output(new LaneProduct(prodMantW, expW))
  })

  private val aHidden: Bool =
    if (A.hasHiddenBit) (io.a.exp =/= 0.U) else false.B
  private val wHidden: Bool =
    if (W.hasHiddenBit) (io.w.exp =/= 0.U) else false.B

  private val aSig = Cat(aHidden, io.a.mant)          // (A.m+1) bits
  private val wSig = Cat(wHidden, io.w.mant)          // (W.m+1) bits
  io.out.mant := (aSig * wSig)(prodMantW - 1, 0)

  private val aeUn: SInt =
    if (A.isInt) A.impSc.S(expW.W)
    else {
      val fixed = Mux(io.a.exp === 0.U, 1.S(expW.W),
                                         io.a.exp.zext.pad(expW))
      fixed - A.bias.S
    }
  private val weUn: SInt =
    if (W.isInt) W.impSc.S(expW.W)
    else {
      val fixed = Mux(io.w.exp === 0.U, 1.S(expW.W),
                                         io.w.exp.zext.pad(expW))
      fixed - W.bias.S
    }
  io.out.exp  := (aeUn + weUn).pad(expW)
  io.out.sign := io.a.sign ^ io.w.sign
}

// ─────────────────────────────────────────────────────────────
// Submodule 2: AlignSumTree — barrel-shift align + signed sum
// ─────────────────────────────────────────────────────────────

/** Places each lane's signed product at bit position `sopShift + prodExp[i]`
  * in a `sopFieldW`-bit signed field, then reduces via signed add. */
class AlignSumTree(cfg: DPUConfig) extends Module {
  private val ww = Widths(cfg)
  override def desiredName =
    s"AlignSumTree_${cfg.A.name}_${cfg.W.name}_vec${cfg.N}"

  val io = IO(new Bundle {
    val lanes    = Input(Vec(cfg.N, new LaneProduct(ww.prodMantW, ww.expSignedW)))
    val sopField = Output(SInt(ww.sopFieldW.W))
  })

  private val shiftAmtW =
    log2Ceil(ww.sopShift + ww.pMax + 1).max(1)

  private val signedProd = VecInit(io.lanes.map { ln =>
    val mag = ln.mant.zext                                  // widen to signed
    val sgn = Mux(ln.sign, -mag, mag)                        // 2's complement
    sgn.pad(ww.signedProdW).asSInt
  })

  private val alignedProd = VecInit(io.lanes.zip(signedProd).map { case (ln, sp) =>
    val shiftAmt = (ln.exp + ww.sopShift.S).asUInt.pad(shiftAmtW)
    (sp.pad(ww.sopFieldW) << shiftAmt)(ww.sopFieldW - 1, 0).asSInt
  })

  io.sopField := alignedProd.reduce(_ + _)
}

// ─────────────────────────────────────────────────────────────
// Submodule 3: ScaleMult — post-tree scale mant multiply
// ─────────────────────────────────────────────────────────────

/** SoP field × (1.sA_mant)(1.sW_mant) → scaled term.
  * Only instantiated when `S.m > 0` (UE8M0 bypasses this stage). */
class ScaleMult(cfg: DPUConfig) extends Module {
  private val ww = Widths(cfg)
  require(cfg.S.m > 0, "ScaleMult should not be instantiated for UE8M0")
  override def desiredName =
    s"ScaleMult_${cfg.A.name}_${cfg.W.name}_${cfg.S.name}"

  val io = IO(new Bundle {
    val sopField    = Input(SInt(ww.sopFieldW.W))
    val scaleAhid   = Input(Bool())                 // = (scaleA.exp != 0)
    val scaleAmant  = Input(UInt(cfg.S.m.W))
    val scaleWhid   = Input(Bool())                 // = (scaleW.exp != 0)
    val scaleWmant  = Input(UInt(cfg.S.m.W))
    val scaledTerm  = Output(SInt(ww.scaledTermW.W))
  })

  // Scale significand: {hidden, mant}. hidden=0 for subnormal scale (biased
  // exp = 0), hidden=1 for normal. IEEE FP convention.
  private val scaleAsig = Cat(io.scaleAhid, io.scaleAmant)   // (mS+1) bits
  private val scaleWsig = Cat(io.scaleWhid, io.scaleWmant)   // (mS+1) bits
  private val smp       = (scaleAsig * scaleWsig).pad(ww.scaleMantProdW + 1)
  io.scaledTerm := (io.sopField * smp.zext).pad(ww.scaledTermW).asSInt
}

// ─────────────────────────────────────────────────────────────
// Submodule 4: AccUpdate — accreg align + signed CPA + LSC + RNE + accreg
// ─────────────────────────────────────────────────────────────

/** Combines a per-cycle `scaledTerm` with the internal BF16 accreg.
  * Contains the sole state register in the whole design. */
class AccUpdate(cfg: DPUConfig, debug: Boolean = false) extends Module {
  private val ww = Widths(cfg)
  private val S  = cfg.S
  override def desiredName =
    s"AccUpdate_${cfg.A.name}_${cfg.W.name}_${cfg.S.name}"

  val io = IO(new Bundle {
    val scaledTerm = Input(SInt(ww.scaledTermW.W))
    val scaleAexp  = Input(UInt(S.e.W))
    val scaleWexp  = Input(UInt(S.e.W))
    val clearAcc   = Input(Bool())
    val enable     = Input(Bool())
    val accOut     = Output(new BF16Reg)
    val dbg_scaleExpSum    = if (debug) Some(Output(SInt(ww.scaleExpSumW.W))) else None
    val dbg_accShiftSigned = if (debug) Some(Output(SInt(16.W))) else None
    val dbg_sumField       = if (debug) Some(Output(SInt(ww.finalAdderW.W))) else None
    val dbg_lz             = if (debug) Some(Output(UInt(8.W))) else None
    val dbg_resExpUnbiased = if (debug) Some(Output(SInt(16.W))) else None
    val dbg_accSig         = if (debug) Some(Output(UInt(BF16.sigBits.W))) else None
    val dbg_accShiftedInt  = if (debug) Some(Output(SInt(ww.finalAdderW.W))) else None
  })

  // ── State register (async active-HIGH reset).
  // Emitted SV uses `always_ff @(posedge clock or posedge reset)`; polarity
  // is: reset=1 asserts reset (registers clear), reset=0 normal operation.
  // Rationale (vs tensor_core's `(!reset).asAsyncReset` active-low convention):
  // chiseltest's default reset polarity is active-high — matching it here
  // avoids needing to manually poke reset in every test. The synthesized
  // testbench (test/gen_simple_dpu_tb.py) drives the same polarity explicitly.
  val accreg = withReset(reset.asAsyncReset)(RegInit({
    val z = Wire(new BF16Reg)
    z.sign := false.B; z.exp := 0.U; z.mant := 0.U
    z
  }))

  // ── Scale exp addition ─────────────────────────────────────
  // Subnormal fix (same as element side): biased exp == 0 → treat as biased 1
  // so the value = 0.mant * 2^(1-bias) is computed correctly downstream.
  private val scaleAexpFixed: SInt =
    Mux(io.scaleAexp === 0.U, 1.S(ww.scaleExpSumW.W),
                               io.scaleAexp.zext.pad(ww.scaleExpSumW))
  private val scaleWexpFixed: SInt =
    Mux(io.scaleWexp === 0.U, 1.S(ww.scaleExpSumW.W),
                               io.scaleWexp.zext.pad(ww.scaleExpSumW))
  private val scaleExpSum: SInt =
    scaleAexpFixed + scaleWexpFixed - (2 * S.bias).S(ww.scaleExpSumW.W)

  // ── Accreg alignment ───────────────────────────────────────
  private val accHidden = accreg.exp =/= 0.U
  private val accSig    = Cat(accHidden, accreg.mant)                 // 8 bits
  private val accUnbiasedExp = accreg.exp.zext - BF16.bias.S

  private val accShiftW = log2Ceil(
    ww.termUnitPos + BF16.bias + (1 << S.e)
  ).max(4) + 2

  private val constOffset = ww.termUnitPos - (BF16.sigBits - 1)
  private val accShiftSigned = (
    constOffset.S(accShiftW.W) + accUnbiasedExp.pad(accShiftW) -
      scaleExpSum.pad(accShiftW)
  )

  private val accShiftLimit  = ww.finalAdderW - 1
  private val accShiftIsRight = accShiftSigned < 0.S
  private val accShiftMag = Mux(accShiftIsRight,
                                (-accShiftSigned).asUInt,
                                accShiftSigned.asUInt)
  private val accShiftClamped = Mux(accShiftMag > accShiftLimit.U,
                                    accShiftLimit.U,
                                    accShiftMag)

  private val accSigMag: SInt   = accSig.zext
  private val accSignedSig: SInt = Mux(accreg.sign, -accSigMag, accSigMag)
  private val accWide: SInt = accSignedSig.pad(ww.finalAdderW)

  // Left-shift path
  private val accShiftedLeftGrown: SInt = accWide << accShiftClamped
  private val accShiftedLeftS: SInt =
    accShiftedLeftGrown.asUInt.apply(ww.finalAdderW - 1, 0).asSInt

  // Right-shift path (with sticky)
  private val accRightShift = Mux(accShiftIsRight, accShiftClamped, 0.U)
  private val accLostMask = ((1.U << accRightShift) - 1.U)
  private val accSticky = (accSignedSig.asUInt.pad(ww.finalAdderW) & accLostMask).orR
  private val accShiftedRightS: SInt = (accWide >> accRightShift).pad(ww.finalAdderW)

  private val accAligned: SInt = Mux(accShiftIsRight, accShiftedRightS, accShiftedLeftS)

  // ── Signed CPA sum ─────────────────────────────────────────
  private val termInField: SInt = io.scaledTerm.pad(ww.finalAdderW)
  private val stickyLSB: SInt = Mux(accShiftIsRight, accSticky.asSInt.pad(1), 0.S(1.W))
  private val sumField: SInt = termInField + accAligned + stickyLSB

  // ── Normalize (sign-magnitude + LSC) ──────────────────────
  private val sumSign = sumField(ww.finalAdderW - 1)
  private val sumMag = Mux(sumSign, (-sumField).asUInt, sumField.asUInt)(
    ww.finalAdderW - 1, 0)

  private val lzcW = log2Ceil(ww.finalAdderW).max(1) + 1
  private val sumMagRev = Reverse(sumMag)
  private val lz = PriorityEncoder(sumMagRev).pad(lzcW)
  private val sumIsZero = sumMag === 0.U

  private val normalized = (sumMag << lz)(ww.finalAdderW - 1, 0)

  // ── RNE to BF16 ────────────────────────────────────────────
  private val resExpUnbiased = scaleExpSum.pad(lzcW + S.e + 4) +
    (ww.finalAdderW - 1 - ww.termUnitPos).S -
    lz.zext

  private val gField = normalized(ww.finalAdderW - 1, ww.finalAdderW - BF16.sigBits)
    .pad(BF16.sigBits)
  private val guardBit = normalized(ww.finalAdderW - BF16.sigBits - 1)
  private val roundBit = normalized(ww.finalAdderW - BF16.sigBits - 2)
  private val stickyBits = normalized(ww.finalAdderW - BF16.sigBits - 3, 0).orR
  private val stickyPlus = stickyBits | (stickyLSB =/= 0.S)

  private val roundUp = guardBit && (roundBit || stickyPlus || gField(0))
  // `+&` GROWS the width so a carry-out from an all-ones gField is captured
  // in bit BF16.sigBits (the roundCarry signal below). `+` (same-width add)
  // silently discards the carry, dropping the exp bump and producing hw ≈
  // golden/2 whenever RNE triggers a mantissa overflow (seen on the "last
  // cycle" of many workload configs where accumulated sums happen to have
  // that pattern).
  private val gFieldPlusRnd = (gField +& roundUp.asUInt).pad(BF16.sigBits + 1)

  private val roundCarry = gFieldPlusRnd(BF16.sigBits)
  private val mantAfterRnd = Mux(roundCarry,
                                 gFieldPlusRnd(BF16.sigBits, 1),
                                 gFieldPlusRnd(BF16.sigBits - 1, 0)
                                 )(BF16.mantBits - 1, 0)
  private val expAfterRnd = resExpUnbiased + Mux(roundCarry, 1.S, 0.S)

  // ── Pack BF16 ─────────────────────────────────────────────
  private val finalBiased = expAfterRnd + BF16.bias.S
  private val expUnderflow = finalBiased <= 0.S
  private val expOverflow  = finalBiased >= ((1 << BF16.expBits) - 1).S

  private val newAcc = Wire(new BF16Reg)
  newAcc.sign := sumSign && !sumIsZero
  newAcc.exp  := Mux(sumIsZero || expUnderflow, 0.U,
                 Mux(expOverflow, ((1 << BF16.expBits) - 2).U,
                     finalBiased.asUInt.pad(BF16.expBits)))
  newAcc.mant := Mux(sumIsZero || expUnderflow, 0.U, mantAfterRnd)

  // ── State update ──────────────────────────────────────────
  when(io.clearAcc) {
    accreg.sign := false.B
    accreg.exp  := 0.U
    accreg.mant := 0.U
  }.elsewhen(io.enable) {
    accreg := newAcc
  }

  io.accOut := accreg

  // ── Debug taps (only wired when debug=true) ──────────────
  io.dbg_scaleExpSum.foreach(_ := scaleExpSum)
  io.dbg_accShiftSigned.foreach(_ := accShiftSigned.pad(16))
  io.dbg_sumField.foreach(_ := sumField)
  io.dbg_lz.foreach(_ := lz.pad(8))
  io.dbg_resExpUnbiased.foreach(_ := resExpUnbiased.pad(16))
  io.dbg_accSig.foreach(_ := accSig)
  io.dbg_accShiftedInt.foreach(_ := accAligned)
}

// ─────────────────────────────────────────────────────────────
// Top: SimpleDPU — glues LaneMul × N + AlignSumTree + ScaleMult + AccUpdate
// ─────────────────────────────────────────────────────────────

/** Debug port bundle — internal per-stage signals for offline analysis.
  * Only elaborated when SimpleDPU is instantiated with `debug=true`. */
class SimpleDPUDebug(cfg: DPUConfig) extends Bundle {
  private val ww = Widths(cfg)
  val laneMant  = Output(Vec(cfg.N, UInt(ww.prodMantW.W)))
  val laneExp   = Output(Vec(cfg.N, SInt(ww.expSignedW.W)))
  val laneSign  = Output(Vec(cfg.N, Bool()))
  val sopField  = Output(SInt(ww.sopFieldW.W))
  val scaledTerm= Output(SInt(ww.scaledTermW.W))
  val scaleExpSum = Output(SInt(ww.scaleExpSumW.W))
  val accShiftSigned = Output(SInt(16.W))  // signed enough
  val sumField  = Output(SInt(ww.finalAdderW.W))
  val lz        = Output(UInt(8.W))
  val resExpUnbiased = Output(SInt(16.W))
  val accSig    = Output(UInt(BF16.sigBits.W))
  val accShiftedInt = Output(SInt(ww.finalAdderW.W))
}

class SimpleDPUIO(cfg: DPUConfig, debug: Boolean = false) extends Bundle {
  val enable   = Input(Bool())
  val clearAcc = Input(Bool())
  val a        = Input(Vec(cfg.N, new ElemOperand(cfg.A)))
  val w        = Input(Vec(cfg.N, new ElemOperand(cfg.W)))
  val scaleA   = Input(new ScaleOperand(cfg.S))
  val scaleW   = Input(new ScaleOperand(cfg.S))
  val accOut   = Output(new BF16Reg)
  val dbg      = if (debug) Some(new SimpleDPUDebug(cfg)) else None
}

class SimpleDPU(val cfg: DPUConfig, val debug: Boolean = false) extends Module {
  override def desiredName =
    s"SimpleDPU_${cfg.A.name}_${cfg.W.name}_${cfg.S.name}"

  val io = IO(new SimpleDPUIO(cfg, debug))
  private val ww = Widths(cfg)

  // ── LaneMul × N ────────────────────────────────────────────
  private val lanes = Wire(Vec(cfg.N, new LaneProduct(ww.prodMantW, ww.expSignedW)))
  for (i <- 0 until cfg.N) {
    val lm = Module(new LaneMul(cfg.A, cfg.W, ww.expSignedW))
    lm.io.a := io.a(i)
    lm.io.w := io.w(i)
    lanes(i) := lm.io.out
  }

  // ── AlignSumTree ───────────────────────────────────────────
  private val tree = Module(new AlignSumTree(cfg))
  tree.io.lanes := lanes

  // ── ScaleMult (only when S.m > 0) ─────────────────────────
  private val scaledTerm: SInt = if (cfg.S.m == 0) {
    tree.io.sopField
  } else {
    val sm = Module(new ScaleMult(cfg))
    sm.io.sopField   := tree.io.sopField
    sm.io.scaleAhid  := io.scaleA.exp =/= 0.U
    sm.io.scaleAmant := io.scaleA.mant
    sm.io.scaleWhid  := io.scaleW.exp =/= 0.U
    sm.io.scaleWmant := io.scaleW.mant
    sm.io.scaledTerm
  }

  // ── AccUpdate (contains accreg register) ──────────────────
  private val acc = Module(new AccUpdate(cfg, debug))
  acc.io.scaledTerm := scaledTerm
  acc.io.scaleAexp  := io.scaleA.exp
  acc.io.scaleWexp  := io.scaleW.exp
  acc.io.clearAcc   := io.clearAcc
  acc.io.enable     := io.enable
  io.accOut         := acc.io.accOut

  // ── Debug taps (only wired when debug=true) ──────────────
  io.dbg.foreach { d =>
    for (i <- 0 until cfg.N) {
      d.laneMant(i) := lanes(i).mant
      d.laneExp(i)  := lanes(i).exp
      d.laneSign(i) := lanes(i).sign
    }
    d.sopField       := tree.io.sopField
    d.scaledTerm     := scaledTerm
    d.scaleExpSum    := acc.io.dbg_scaleExpSum.get
    d.accShiftSigned := acc.io.dbg_accShiftSigned.get
    d.sumField       := acc.io.dbg_sumField.get
    d.lz             := acc.io.dbg_lz.get
    d.resExpUnbiased := acc.io.dbg_resExpUnbiased.get
    d.accSig         := acc.io.dbg_accSig.get
    d.accShiftedInt  := acc.io.dbg_accShiftedInt.get
  }
}
