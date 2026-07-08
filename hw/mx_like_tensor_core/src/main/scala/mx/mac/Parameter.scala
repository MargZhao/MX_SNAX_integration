package mx.mac

case class ElementType(
    elementWidthExp: Int,
    elementWidthMant: Int,
    name: String,
    implicitScaleExp: Int = 0,                   // implicit scale factor 2^implicitScaleExp (e.g. -6 for INT8)
    reservesMaxExpForSpecial: Boolean = false    // IEEE-like Inf/NaN reservation on max exp encoding.
                                                  //   true  → max usable encoded exp = (1<<W) − 2 (E5M2)
                                                  //   false → max usable encoded exp = (1<<W) − 1 (OCP MX
                                                  //          E4M3 / E3M2 / E2M3 / E2M1 — no Inf, NaN at
                                                  //          most at a single mant code that we don't filter)
){
    // For FP types: 1 (sign) + exp + mant
    // For integer types (exp == 0): 2 + mant, the extra '1' pads to the full hardware word width (e.g. INT8: 2+6=8)
    def totalWidth: Int = if (elementWidthExp == 0) 2 + elementWidthMant else 1 + elementWidthExp + elementWidthMant
                                  // running-case  E5M2:  1+5+2 = 8
    def bias: Int = if(elementWidthExp>0){(1<<(elementWidthExp-1))-1}else{0}
                                  // running-case  E5M2:  (1<<4)-1 = 15
}

case class ScaleType(
    expScaleWidth: Int,
    mantScaleWidth: Int,
    name: String
){
    def totalScaleWidth: Int = expScaleWidth + mantScaleWidth
                                  // running-case  UE6M2: 6+2 = 8
    def bias: Int = if(expScaleWidth>0){(1<<(expScaleWidth-1))-1}else{0}
                                  // running-case  UE6M2: (1<<5)-1 = 31
}

case class OperatorConfig(
    elementTypeA: ElementType,
    elementTypeB: ElementType
){
    // add implicit bit +1
    private def getExtendedMantWidth(t: ElementType): Int = {
        t.elementWidthMant + 1 // 加上隐式位
                                  // running-case  E5M2:  2+1 = 3
    }
    // minimum exponent for this element format(include implicit scale -6 for INT8)
    private def minAdjExp(t: ElementType): Int = {
        if (t.elementWidthExp == 0) t.implicitScaleExp //for INT8. exp=0,so worst case is only -6
        else (1 - t.bias) + t.implicitScaleExp  // for other cases, they are always subnormal (1-bias)
                                  // running-case  E5M2:  (1-15)+0 = -14
    }
    // 容纳负值 v 所需的 SInt 位宽
    private def sIntBitsForNeg(v: Int): Int =
        if (v >= 0) 1 else BigInt(-v).bitLength + 2 //负数-v 需要bitLength(-v)+1(sign), 1bit for overflow
                                  // running-case  v=-28:  bitLength(28)=5 → 5+2 = 7

    val maxElementExp = elementTypeA.elementWidthExp max elementTypeB.elementWidthExp
                                  // running-case  E5M2²:  max(5,5) = 5
    private val minSumAdjExp = minAdjExp(elementTypeA) + minAdjExp(elementTypeB)
                                  // running-case  E5M2²:  -14 + -14 = -28
    val resOperatorExpWidth = (maxElementExp + 2) max sIntBitsForNeg(minSumAdjExp)
                                  // running-case  E5M2²:  max(5+2, 7) = 7
    //指数相加： expenent output width
    //upper bound max of two exp element , + 2 for overflow
    //lower bound: 两个最小指数相加，
    val resOperatorMantWidth = getExtendedMantWidth(elementTypeA) + getExtendedMantWidth(elementTypeB)
                                  // running-case  E5M2²:  3 + 3 = 6
    //尾数相乘： 两个尾数位宽直接相加，不用考虑溢出
}

case class ScaleAddConfig(
    elementTypeA: ElementType,
    elementTypeB: ElementType,
    stype: ScaleType
){
    // 定义内部函数来计算扩展后的尾数位宽
    //For Element
    private def getExtendedMantWidth(t: ElementType): Int = {
         t.elementWidthMant + 1 // 加上隐式位
                                  // running-case  E5M2:  2+1 = 3
    }
    //For Scale
    private def getScaleMantWidth(s: ScaleType): Int = {
        if (s.mantScaleWidth == 0) 1 else s.mantScaleWidth + 1
                                  // running-case  UE6M2: 2+1 = 3
    }
    // 计算每种格式的最小调整指数（含隐含缩放）
    private def minAdjExp(t: ElementType): Int = {
        if (t.elementWidthExp == 0) t.implicitScaleExp
        else (1 - t.bias) + t.implicitScaleExp
                                  // running-case  E5M2:  (1-15)+0 = -14
    }
    private def sIntBitsForNeg(v: Int): Int =
        if (v >= 0) 1 else BigInt(-v).bitLength + 2
                                  // running-case  v=-28:  bitLength(28)=5 → 5+2 = 7

    //Operator
    val maxElementExp = elementTypeA.elementWidthExp max elementTypeB.elementWidthExp
                                  // running-case  E5M2²:    max(5,5) = 5
    private val minSumAdjExp = minAdjExp(elementTypeA) + minAdjExp(elementTypeB)
                                  // running-case  E5M2²:    -14 + -14 = -28
    val resOperatorExpWidth = (maxElementExp + 2) max sIntBitsForNeg(minSumAdjExp)
                                  // running-case  E5M2²:    max(5+2, 7) = 7
    val resOperatorMantWidth = getExtendedMantWidth(elementTypeA) + getExtendedMantWidth(elementTypeB)
                                  // running-case  E5M2²:    3 + 3 = 6
    //Scale
    val resScaleExpWidth = stype.expScaleWidth + 2
                                  // running-case  UE6M2:    6 + 2 = 8
    val resScaleMantWidth = getScaleMantWidth(stype) * 2
                                  // running-case  UE6M2:    3 * 2 = 6
    //Scale Operate
    val maxScaleAddExp = resOperatorExpWidth max resScaleExpWidth
                                  // running-case  /UE6M2:   max(7, 8) = 8
    val resScaleAddExpWidth = maxScaleAddExp + 1
                                  // running-case  /UE6M2:   8 + 1 = 9
    val resScaleAddMantWidth = resOperatorMantWidth + resScaleMantWidth
                                  // running-case  E5M2²/UE6M2:  6 + 6 = 12
    //val resScaleAddMantWidth = 32

    // Use a custom reduction tree when mantissa is narrow enough to be cheaper than FP32 adders
    def useCustomTree: Boolean = resScaleAddMantWidth < 24
                                  // running-case  E5M2²/UE6M2:  12 < 24 → true

    // Maximum possible exponent of a product (both operands at their largest value).
    //   IEEE-like FP (E5M2):   max usable encoded exp = (1<<W) − 2, top encoding reserved for Inf/NaN
    //   OCP MX FP (others):    max usable encoded exp = (1<<W) − 1, all encodings are valid normals
    //                          (E4M3 has one NaN code at S.1111.111, but the max adjExp = 8 is still
    //                           reachable via mant ≠ 7, and the hardware doesn't filter NaN anyway)
    private def maxExpOf(t: ElementType): Int =
      if (t.elementWidthExp == 0) t.implicitScaleExp
      else {
        val bias = (1 << (t.elementWidthExp - 1)) - 1
        val maxEncReserved = if (t.reservesMaxExpForSpecial) 2 else 1
        ((1 << t.elementWidthExp) - maxEncReserved) - bias + t.implicitScaleExp
                                  // running-case  E5M2:  (32-2)-15+0 = 15
      }
    // Range of product exponents: used by FixedFPReductionTree to bound alignment shifts.
    // INT8×INT8 → 0 (all products have the same implicit exponent, no alignment needed).
    val productExpRange: Int =
      (maxExpOf(elementTypeA) + maxExpOf(elementTypeB)) - (minAdjExp(elementTypeA) + minAdjExp(elementTypeB))
                                  // running-case  E5M2²:    (15+15) - (-14-14) = 58

    // Bounds on the signed product exponent (compile-time). Used by FDPU's
    // Kulisch (Arch-IV.b) path as the global anchor: every product is
    // left-shifted by (exp − minProductExp) into a wide fixed-point accumulator.
    val minProductExp: Int = minAdjExp(elementTypeA) + minAdjExp(elementTypeB)
                                  // running-case  E5M2²:    -14 + -14 = -28
    val maxProductExp: Int = maxExpOf(elementTypeA) + maxExpOf(elementTypeB)
                                  // running-case  E5M2²:    15 + 15 = 30
}




object ScaleFormats{
    val UE8M0 = ScaleType(8,0,"UE8M0")
    val UE7M1 = ScaleType(7,1,"UE7M1")
    val UE6M2 = ScaleType(6,2,"UE6M2")
    val UE5M3 = ScaleType(5,3,"UE5M3")
    val UE4M4 = ScaleType(4,4,"UE4M4")
    val UE3M5 = ScaleType(3,5,"UE3M5")
    val UE2M6 = ScaleType(2,6,"UE2M6")
    val UE4M3 = ScaleType(4,3,"UE4M3") 

    val allScaleTypes = List(UE8M0,UE7M1,UE6M2,UE5M3,UE4M4,UE4M3,UE3M5,UE2M6)
  
}

// ============================================================
// Reduction-tree micro-architecture selection (DSE knob)
// ============================================================
/** Reduction-tree micro-architecture variants.
 *
 *  Only the Generic architecture is deployed:
 *    align-once  →  integer adder tree  →  normalize-once
 *
 *    Generic — full barrel align + balanced binary ripple adder tree + LZC
 *              normalize.  Always correct across every (productExpRange,
 *              mantissa-width) config.
 *
 *  The specialised alignment explorations (IntOnlySigned / SmallFixedShift /
 *  TwoStageBarrel / KulischInner) were bit-equivalent area/delay variants and
 *  have been pruned — the deployed datapath uses Generic everywhere.
 */
sealed trait TreeArch {
  /** Short tag embedded in module desiredName for sweep tracing. */
  def name: String
}
object TreeArch {
  case object Generic extends TreeArch { val name = "generic" }

  /** Only Generic remains; kept as a function for call-site stability. */
  def recommended(scfg: ScaleAddConfig, vectorSize: Int): TreeArch = Generic
}

object MXFormats{
    // E5M2 is the only MX FP format that follows IEEE-754 Inf/NaN conventions
    // (max exp encoding = Inf/NaN, max usable encoded exp = 30).
    // E4M3/E3M2/E2M3/E2M1 per OCP MX spec v1.0: no Inf, at most one NaN; all
    // other max-exp encodings are valid normal numbers and must be in range.
    val E5M2 = ElementType(5, 2, "E5M2", reservesMaxExpForSpecial = true)
    val E4M3 = ElementType(4, 3, "E4M3")
    val E3M2 = ElementType(3, 2, "E3M2")
    val E2M3 = ElementType(2, 3, "E2M3")
    val E2M1 = ElementType(2, 1, "E2M1")
    val INT8 = ElementType(0, 6, "INT8", implicitScaleExp = -6)


    val defaultConfig = OperatorConfig(
        elementTypeA = E5M2,
        elementTypeB = E5M2
    )

    val e5m2_e4m3_config = OperatorConfig(
        elementTypeA = E5M2,
        elementTypeB = E4M3
    )

    val e4m3_e2m1_config = OperatorConfig(
        elementTypeA = E4M3,
        elementTypeB = E2M1
    )

    val allElementTypes = List(INT8,E5M2,E4M3,E3M2,E2M3,E2M1)

}