package mx.mac

import chisel3.util.log2Ceil

/** Shared width-derivation math used by all FDPU variants
 *  (FDPUPostScaleReductionTree baseline, deferred_archs.FDPUBlockDeferred,
 *  deferred_archs.FDPUKulischDeferred).
 *
 *  Centralises the per-config widths that all three Module classes need so
 *  the math lives in exactly one place:
 *    - SAFETY_G                guard/round/sticky bits below M_acc
 *    - actualAccMantBits       M_acc (required; from macc_final_selection.csv)
 *    - treeExtraMantBits       tree-exit mantissa-widening beyond raw product
 *    - effectiveTreeOutMantW   tree-exit mantissa width
 *    - effectiveScaleAddMantW  ScaleComposition output mantissa width
 *    - effectiveExpW           tree-exit signed-exp width
 *    - fpNW                    post-scale FP word width (1 + 8 + M_acc)
 *
 *  Pure value class (no Chisel hardware) so it can be instantiated cheaply at
 *  elaboration time.
 *
 *  Construction mirrors the legacy FDPUPostScaleReductionTree constructor:
 *  pass scfg / vectorSize / K and the M_acc override + counterfactual flags,
 *  the derived widths follow.
 *
 *  @param scfg        ScaleAddConfig describing element and scale types.
 *  @param vectorSize  Number of parallel MACs per cycle (>= 1).
 *  @param K           Accumulation depth (no longer sizes M_acc).
 *  @param accMantBits M_acc (required; from macc_final_selection.csv).
 *  @param noEarlyRNE  Counterfactual: skip tree-exit RNE, widen tree mantissa
 *                     to absMagW, force M_acc=23 (FP32).
 *  @param widenUE8M0  UE8M0-only optimisation: widen tree-exit to M_acc bits
 *                     so the per-cycle tree-exit RNE noise is M_acc-dependent.
 */
final case class FDPUWidthMath(
  scfg:        ScaleAddConfig,
  vectorSize:  Int,
  K:           Int,
  accMantBits: Int,
  noEarlyRNE:  Boolean = false,
  widenUE8M0:  Boolean = true,
) {
  val SAFETY_G: Int = 3

  private val log2N   = log2Ceil(vectorSize.max(2))
  /** Tree's internal magnitude width (resOperatorMantWidth + productExpRange
   *  + log2N).  Upper bound on what the tree could emit without RNE.
   *
   *  NOTE: SAFETY_G is intentionally NOT included here.  It used to add 3 bits
   *  to absMagW for "alignment headroom", but in practice the alignment
   *  shiftAmt is bounded by productExpRange (by definition), so the bottom G
   *  bits of absMag would stay permanently zero — dead width.  Dropping G
   *  here matches the equivalent change in CustomReduction.scala:buildGeneric
   *  (fracBitsBase = productExpRange, no +G).  Behavior is preserved
   *  bit-for-bit because zero-OR is identity for the RNE sticky calculation. */
  val absMagW: Int =
    scfg.resOperatorMantWidth + scfg.productExpRange + log2N

  /** Resolved accumulator mantissa width.  noEarlyRNE forces 23 (FP32).
   *  M_acc must be supplied explicitly (from macc_final_selection.csv via the
   *  emit flow) — the K-based AccPrecision heuristic has been removed. */
  val actualAccMantBits: Int = {
    if (noEarlyRNE) 23
    else { require(accMantBits >= 1 && accMantBits <= 23,
                   s"accMantBits must be in [1, 23] (from macc_final_selection.csv), got $accMantBits")
           accMantBits }
  }

  /** Tree-exit extra mantissa bits beyond raw resOperatorMantWidth.  See
   *  the comment block in FDPUPostScaleReductionTree for the derivation
   *  of each branch.  Summary:
   *    noEarlyRNE → widen all the way to absMagW
   *    UE8M0 + widenUE8M0 → widen to M_acc bits
   *    UE8M0 (legacy)     → no widening
   *    mantissa-bearing scale → widen to (M_acc − G) bits
   */
  val treeExtraMantBits: Int =
    if (noEarlyRNE)
      absMagW - scfg.resOperatorMantWidth
    else if (scfg.stype.mantScaleWidth == 0 && widenUE8M0)
      math.max(0, actualAccMantBits - scfg.resOperatorMantWidth)
    else if (scfg.stype.mantScaleWidth == 0)
      0
    else
      math.max(0, actualAccMantBits - SAFETY_G - scfg.resOperatorMantWidth)

  /** Effective tree output mantissa width (fed into ScaleComposition / DirectToFPn). */
  val effectiveTreeOutMantW: Int = scfg.resOperatorMantWidth + treeExtraMantBits

  /** Effective post-scale mantissa width (fed into FusedScaleAccumulator's align + LZD + RNE). */
  val effectiveScaleAddMantW: Int = effectiveTreeOutMantW + scfg.resScaleMantWidth

  private def sIntBitsForNeg(v: Int): Int =
    if (v >= 0) 1 else BigInt(-v).bitLength + 2

  /** Effective tree-exit exponent width.  When outMantW > inMantW the
   *  tree's LZC normalisation can drive outExp below the legacy
   *  resOperatorExpWidth's representable range; widen to fit. */
  val effectiveExpW: Int = {
    val fracBits = scfg.productExpRange + 3
    val absMagWConservative = scfg.resOperatorMantWidth + fracBits + log2N
    val minExp = scfg.maxProductExp - (absMagWConservative - 1) - treeExtraMantBits
    math.max(scfg.resOperatorExpWidth, sIntBitsForNeg(minExp))
  }

  /** Internal FP word width used throughout the post-tree pipeline. */
  val fpNW: Int = 1 + 8 + actualAccMantBits
}
