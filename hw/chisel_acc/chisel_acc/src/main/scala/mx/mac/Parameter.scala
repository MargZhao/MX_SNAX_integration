package mx.mac

case class ElementType(
    elementWidthExp: Int,
    elementWidthMant: Int,
    name: String,
    implicitScaleExp: Int = 0  // implicit scale factor 2^implicitScaleExp (e.g. -6 for INT8)
){
    def totalWidth: Int = 1+ elementWidthExp + elementWidthMant
    def bias: Int = if(elementWidthExp>0){(1<<(elementWidthExp-1))-1}else{0}
}

case class ScaleType(
    expScaleWidth: Int,
    mantScaleWidth: Int,
    name: String
){
    def totalScaleWidth: Int = expScaleWidth + mantScaleWidth
    def bias: Int = if(expScaleWidth>0){(1<<(expScaleWidth-1))-1}else{0}
    assert( expScaleWidth + mantScaleWidth == 8)
}

case class OperatorConfig(
    elementTypeA: ElementType,
    elementTypeB: ElementType
){
    // 定义内部函数来计算扩展后的尾数位宽
    private def getExtendedMantWidth(t: ElementType): Int = {
        if (t.name == "INT8") t.elementWidthMant
        else t.elementWidthMant + 1 // 加上隐式位
    }
    // 计算每种格式的最小调整指数（含隐含缩放）
    private def minAdjExp(t: ElementType): Int = {
        if (t.elementWidthExp == 0) t.implicitScaleExp
        else (1 - t.bias) + t.implicitScaleExp  // 次正规数情形
    }
    // 容纳负值 v 所需的 SInt 位宽
    private def sIntBitsForNeg(v: Int): Int =
        if (v >= 0) 1 else BigInt(-v).bitLength + 2

    val maxElementExp = elementTypeA.elementWidthExp max elementTypeB.elementWidthExp
    private val minSumAdjExp = minAdjExp(elementTypeA) + minAdjExp(elementTypeB)
    val resOperatorExpWidth = (maxElementExp + 2) max sIntBitsForNeg(minSumAdjExp)
    val resOperatorMantWidth = getExtendedMantWidth(elementTypeA) + getExtendedMantWidth(elementTypeB)
}

case class ScaleAddConfig(
    elementTypeA: ElementType,
    elementTypeB: ElementType,
    stype: ScaleType
){
    // 定义内部函数来计算扩展后的尾数位宽
    //For Element
    private def getExtendedMantWidth(t: ElementType): Int = {
        if (t.name == "INT8") t.elementWidthMant
        else t.elementWidthMant + 1 // 加上隐式位
    }
    //For Scale
    private def getScaleMantWidth(s: ScaleType): Int = {
        if (s.mantScaleWidth == 0) 1 else s.mantScaleWidth + 1
    }
    // 计算每种格式的最小调整指数（含隐含缩放）
    private def minAdjExp(t: ElementType): Int = {
        if (t.elementWidthExp == 0) t.implicitScaleExp
        else (1 - t.bias) + t.implicitScaleExp
    }
    private def sIntBitsForNeg(v: Int): Int =
        if (v >= 0) 1 else BigInt(-v).bitLength + 2

    //Operator
    val maxElementExp = elementTypeA.elementWidthExp max elementTypeB.elementWidthExp
    private val minSumAdjExp = minAdjExp(elementTypeA) + minAdjExp(elementTypeB)
    val resOperatorExpWidth = (maxElementExp + 2) max sIntBitsForNeg(minSumAdjExp)
    val resOperatorMantWidth = getExtendedMantWidth(elementTypeA) + getExtendedMantWidth(elementTypeB)
    //Scale
    val resScaleExpWidth = stype.expScaleWidth + 2
    val resScaleMantWidth = getScaleMantWidth(stype) * 2 
    //Scale Operate
    val maxScaleAddExp = resOperatorExpWidth max resScaleExpWidth
    val resScaleAddExpWidth = maxScaleAddExp + 2
    //val resScaleAddMantWidth = resOperatorMantWidth + resScaleMantWidth + 5
    val resScaleAddMantWidth = 32
}




object ScaleFormats{
    val UE8M0 = ScaleType(8,0,"UE8M0")
    val UE7M1 = ScaleType(7,1,"UE7M1")
    val UE6M2 = ScaleType(6,2,"UE6M2")
    val UE5M3 = ScaleType(5,3,"UE5M3")
    val UE4M4 = ScaleType(4,4,"UE4M4")
    val UE3M5 = ScaleType(3,5,"UE3M5")
    val UE2M6 = ScaleType(2,6,"UE2M6")

    val allScaleTypes = List(UE8M0,UE7M1,UE6M2,UE5M3,UE4M4,UE3M5,UE2M6)
  
}

object MXFormats{
    val E5M2 = ElementType(5,2,"E5M2")
    val E4M3 = ElementType(4,3,"E4M3")
    val E3M2 = ElementType(3,2,"E3M2")
    val E2M3 = ElementType(2,3,"E2M3")
    val E2M1 = ElementType(2,1,"E2M1")
    val INT8 = ElementType(0, 7, "INT8", implicitScaleExp = -6)


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