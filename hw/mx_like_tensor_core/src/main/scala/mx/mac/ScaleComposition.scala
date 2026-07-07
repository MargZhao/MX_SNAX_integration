package mx.mac

import chisel3._
import chisel3.util._
/* ScaleComposition (figure stage (4) "Scale composition")
 *   Composes the shared scale factors with the reduction-tree result:
 *     mant = (M_SA × M_SB) × treeMant   (two multiplies — NOT an "addition")
 *     exp  = (E_SA + E_SB) + treeExp
 *   Emits a RAW, un-normalised fixed-point term that the fused accumulator
 *   (FusedScaleAccumulator) aligns + adds + normalises in one shot.
 */

class ScaleComposition(
  val scfg: ScaleAddConfig,
  /** Override the input operator-mantissa width.  Default (-1) uses the
   *  legacy `scfg.resOperatorMantWidth`.  Set this to the FixedFPReductionTree's
   *  effective outMantW when the tree's output is widened beyond
   *  resOperatorMantWidth (cap-binding fix). */
  val inOpMantWOverride: Int = -1,
  /** Override the input operator-exponent width.  Default (-1) uses the
   *  legacy `scfg.resOperatorExpWidth`.  Must be widened in lockstep with
   *  inOpMantWOverride when the tree's outMantW grows, because the tree's
   *  LZC normalisation can drive its outExp below the legacy expW's
   *  representable range (sign truncation → 2^k value wraparound). */
  val inOpExpWOverride: Int = -1
) extends Module {
  val effectiveInOpMantW: Int =
    if (inOpMantWOverride > 0) inOpMantWOverride else scfg.resOperatorMantWidth
  val effectiveInOpExpW: Int =
    if (inOpExpWOverride > 0) inOpExpWOverride else scfg.resOperatorExpWidth
  /** scaleMantProduct width is fixed by the scale type (independent of the
   *  operator mantissa width). */
  val effectiveOutMantW: Int  =
    effectiveInOpMantW + scfg.resScaleMantWidth
  /** Effective output-exp width: must accommodate the (now-possibly-wider)
   *  input exp plus the scale-exp sum plus carry. */
  val effectiveOutExpW: Int =
    math.max(scfg.resScaleAddExpWidth, effectiveInOpExpW + 2)

  override def desiredName = {
    val tag = if (inOpMantWOverride > 0) s"_wide${effectiveInOpMantW}" else ""
    s"ScaleComposition_${scfg.elementTypeA.name}_to_${scfg.elementTypeB.name}" +
    s"_scale_${scfg.stype.name}${tag}"
  }

  val io = IO(new Bundle {
    //input
    val inOpSign = Input(UInt(1.W))
    val inOpExp  = Input(SInt(effectiveInOpExpW.W))
    val inOpMant = Input(UInt(effectiveInOpMantW.W))
    val inShareScaleA = Input(UInt(scfg.stype.totalScaleWidth.W))
    val inShareScaleB = Input(UInt(scfg.stype.totalScaleWidth.W))
    //output
    val outSign = Output(UInt(1.W))
    val outExp  = Output(SInt(effectiveOutExpW.W))
    val outMant = Output(UInt(effectiveOutMantW.W))
  })

  def getScaledParts(in:UInt, stype:ScaleType):(UInt,UInt) = {
    val exp = in(stype.totalScaleWidth - 1, stype.mantScaleWidth)
    if (stype.mantScaleWidth == 0) {
      (exp, 1.U(1.W)) // Implicit bit only
    } else {
      val mant = in(stype.mantScaleWidth - 1, 0)
      val implicitBit = exp.orR
      (exp, Cat(implicitBit, mant))
    }
  }

  val(expScaleA,fullMantScaleA) = getScaledParts(io.inShareScaleA,scfg.stype)
  val(expScaleB,fullMantScaleB) = getScaledParts(io.inShareScaleB,scfg.stype)

  //扩展位宽防止溢出
  val safeBias = scfg.stype.bias.S

  // 次正规数 scale（exp=0 且有 mantissa 位）的指数应为 1-bias，而非 0-bias，
  // 与 CustomOperator 对次正规数元素的处理保持一致。
  val adjExpScaleA = Mux(expScaleA === 0.U && scfg.stype.mantScaleWidth.U > 0.U,
                         1.S - safeBias,
                         expScaleA.zext - safeBias)
  val adjExpScaleB = Mux(expScaleB === 0.U && scfg.stype.mantScaleWidth.U > 0.U,
                         1.S - safeBias,
                         expScaleB.zext - safeBias)

  //+& means keep the carry bit
  val scaleExpSum = adjExpScaleA +& adjExpScaleB
  val scaleMantProduct = fullMantScaleA * fullMantScaleB

  val ExpAdd = scaleExpSum +& io.inOpExp
  val MantProduct = scaleMantProduct * io.inOpMant

  io.outSign := io.inOpSign
  io.outExp  := ExpAdd
  io.outMant := MantProduct
 
}

object OperatorWithScaleMain extends App {
  def gen(elementTypeA: ElementType, elementTypeB: ElementType, stype: ScaleType): Unit = {
    // Wrap into the config object
    val scfg = ScaleAddConfig(elementTypeA, elementTypeB, stype)
    val folderName = s"gen_scale_${stype.name}_${elementTypeA.name}_to_${elementTypeB.name}"
    
    emitVerilog(
      new ScaleComposition(scfg),
      Array("--target-dir", s"generated/$folderName")
    )
  }

  // Example call
  gen(MXFormats.E5M2, MXFormats.E4M3, ScaleFormats.UE5M3)
}

object AllOperatorWithScaleMain extends App{
  // iterate all A and B combinations
  for {
    typeA <- MXFormats.allElementTypes
    typeB <- MXFormats.allElementTypes
  } {
    val currentCfg = OperatorConfig(typeA, typeB)
    val dirName = s"generated/${typeA.name}_${typeB.name}"
    
    println(s"正在为 ${typeA.name} x ${typeB.name} 生成硬件...")
    
    emitVerilog(
      new CustomOperator(currentCfg), 
      Array("--target-dir", dirName)
    )
  }

}