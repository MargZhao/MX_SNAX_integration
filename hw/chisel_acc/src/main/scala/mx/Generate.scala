package mx

import chisel3._
import mx.mac._

/**
 * 通过命令行参数控制 FusedDotProductUnit 生成。
 *
 * 用法（通过 sbt）：
 *   sbt "runMain mx.GenerateFusedDotProduct --type-a E5M2 --type-b E4M3 --scale UE8M0 --vec 16 --out-dir generated/fused_dot"
 *
 * 参数（全部可选，有默认值）：
 *   --type-a  <name>   元素类型 A，默认 E5M2
 *   --type-b  <name>   元素类型 B，默认 E5M2
 *   --scale   <name>   共享 Scale 格式，默认 UE8M0
 *   --vec     <int>    向量宽度（并行 MAC 数），默认 8
 *   --out-dir <path>   输出目录，默认自动生成路径
 *
 * 合法值：
 *   type-a / type-b : INT8 E5M2 E4M3 E3M2 E2M3 E2M1
 *   scale           : UE8M0 UE7M1 UE6M2 UE5M3 UE4M4 UE3M5 UE2M6
 */
object GenerateFusedDotProduct extends App {

  def getArg(flag: String, default: String): String = {
    val idx = args.indexOf(flag)
    if (idx >= 0 && idx + 1 < args.length) args(idx + 1) else default
  }

  val typeAName  = getArg("--type-a", "E5M2")
  val typeBName  = getArg("--type-b", "E5M2")
  val scaleName  = getArg("--scale",  "UE8M0")
  val vectorSize = getArg("--vec",    "8").toInt

  val elementA = MXFormats.allElementTypes.find(_.name == typeAName)
    .getOrElse(sys.error(s"未知 type-a: '$typeAName'，可选: ${MXFormats.allElementTypes.map(_.name).mkString(", ")}"))

  val elementB = MXFormats.allElementTypes.find(_.name == typeBName)
    .getOrElse(sys.error(s"未知 type-b: '$typeBName'，可选: ${MXFormats.allElementTypes.map(_.name).mkString(", ")}"))

  val scale = ScaleFormats.allScaleTypes.find(_.name == scaleName)
    .getOrElse(sys.error(s"未知 scale: '$scaleName'，可选: ${ScaleFormats.allScaleTypes.map(_.name).mkString(", ")}"))

  val outDir = getArg("--out-dir",
    s"generated/fused_dot/${typeAName}_${typeBName}_${scaleName}_vec${vectorSize}")

  val scfg = ScaleAddConfig(elementA, elementB, scale)

  println(s"[Generate] A=$typeAName  B=$typeBName  Scale=$scaleName  vec=$vectorSize  → $outDir")

  emitVerilog(
    new FusedDotProductUnit(scfg, vectorSize, istest = false),
    Array("--target-dir", outDir)
  )

  println(s"[Generate] 完成 → $outDir/BFP_PE.sv")
}
