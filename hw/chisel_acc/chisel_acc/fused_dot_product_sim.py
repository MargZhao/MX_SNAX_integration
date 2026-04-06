#!/usr/bin/env python3
"""
Bit-exact Python simulation of FusedDotProductUnit.

Mirrors the Chisel RTL pipeline:
  CustomOperator → ScaleAddition → ScaleToFP32 → FP32ReduceTree → FP32Accumulator

All integer arithmetic matches Chisel's UInt/SInt truncation and sign-extension
behaviour, including bit-exact FP32 addition and RNE rounding.

No NaN / Inf handling — consistent with the hardware.
"""

import struct
from dataclasses import dataclass, field
from typing import List, Tuple


# ============================================================
# Parameter types  (mirrors Parameter.scala)
# ============================================================

@dataclass
class ElementType:
    elementWidthExp:  int
    elementWidthMant: int
    name:             str
    implicitScaleExp: int = 0

    @property
    def totalWidth(self) -> int:
        return 1 + self.elementWidthExp + self.elementWidthMant

    @property
    def bias(self) -> int:
        if self.elementWidthExp > 0:
            return (1 << (self.elementWidthExp - 1)) - 1
        return 0


@dataclass
class ScaleType:
    expScaleWidth:  int
    mantScaleWidth: int
    name:           str

    @property
    def totalScaleWidth(self) -> int:
        return self.expScaleWidth + self.mantScaleWidth

    @property
    def bias(self) -> int:
        if self.expScaleWidth > 0:
            return (1 << (self.expScaleWidth - 1)) - 1
        return 0


class MXFormats:
    E5M2 = ElementType(5, 2, "E5M2")
    E4M3 = ElementType(4, 3, "E4M3")
    E3M2 = ElementType(3, 2, "E3M2")
    E2M3 = ElementType(2, 3, "E2M3")
    E2M1 = ElementType(2, 1, "E2M1")
    INT8 = ElementType(0, 7, "INT8", implicitScaleExp=-6)


class ScaleFormats:
    UE8M0 = ScaleType(8, 0, "UE8M0")
    UE7M1 = ScaleType(7, 1, "UE7M1")
    UE6M2 = ScaleType(6, 2, "UE6M2")
    UE5M3 = ScaleType(5, 3, "UE5M3")
    UE4M4 = ScaleType(4, 4, "UE4M4")
    UE3M5 = ScaleType(3, 5, "UE3M5")
    UE2M6 = ScaleType(2, 6, "UE2M6")


# ============================================================
# Config computed parameters  (mirrors OperatorConfig / ScaleAddConfig)
# ============================================================

class OperatorConfig:
    def __init__(self, elementTypeA: ElementType, elementTypeB: ElementType):
        self.elementTypeA = elementTypeA
        self.elementTypeB = elementTypeB

        def get_ext_mant_width(t: ElementType) -> int:
            return t.elementWidthMant if t.name == "INT8" else t.elementWidthMant + 1

        def min_adj_exp(t: ElementType) -> int:
            if t.elementWidthExp == 0:
                return t.implicitScaleExp
            return (1 - t.bias) + t.implicitScaleExp

        def sint_bits_for_neg(v: int) -> int:
            return 1 if v >= 0 else ((-v).bit_length() + 2)

        self.maxElementExp       = max(elementTypeA.elementWidthExp, elementTypeB.elementWidthExp)
        min_sum                  = min_adj_exp(elementTypeA) + min_adj_exp(elementTypeB)
        self.resOperatorExpWidth = max(self.maxElementExp + 2, sint_bits_for_neg(min_sum))
        self.resOperatorMantWidth = (get_ext_mant_width(elementTypeA) +
                                     get_ext_mant_width(elementTypeB))


class ScaleAddConfig:
    def __init__(self, elementTypeA: ElementType, elementTypeB: ElementType, stype: ScaleType):
        self.elementTypeA = elementTypeA
        self.elementTypeB = elementTypeB
        self.stype        = stype

        def get_ext_mant_width(t: ElementType) -> int:
            return t.elementWidthMant if t.name == "INT8" else t.elementWidthMant + 1

        def get_scale_mant_width(s: ScaleType) -> int:
            return 1 if s.mantScaleWidth == 0 else s.mantScaleWidth + 1

        def min_adj_exp(t: ElementType) -> int:
            if t.elementWidthExp == 0:
                return t.implicitScaleExp
            return (1 - t.bias) + t.implicitScaleExp

        def sint_bits_for_neg(v: int) -> int:
            return 1 if v >= 0 else ((-v).bit_length() + 2)

        self.maxElementExp        = max(elementTypeA.elementWidthExp, elementTypeB.elementWidthExp)
        min_sum                   = min_adj_exp(elementTypeA) + min_adj_exp(elementTypeB)
        self.resOperatorExpWidth  = max(self.maxElementExp + 2, sint_bits_for_neg(min_sum))
        self.resOperatorMantWidth = (get_ext_mant_width(elementTypeA) +
                                     get_ext_mant_width(elementTypeB))

        self.resScaleExpWidth    = stype.expScaleWidth + 2
        self.resScaleMantWidth   = get_scale_mant_width(stype) * 2
        self.maxScaleAddExp      = max(self.resOperatorExpWidth, self.resScaleExpWidth)
        self.resScaleAddExpWidth = self.maxScaleAddExp + 2
        self.resScaleAddMantWidth = 32   # fixed in RTL


# ============================================================
# Bit helpers
# ============================================================

def _mask(n: int) -> int:
    """n-bit all-ones mask."""
    return (1 << n) - 1

def _trunc(x: int, n: int) -> int:
    """Keep the lower n bits (UInt assignment in Chisel)."""
    return x & _mask(n)

def _leading_zeros(x: int, width: int) -> int:
    """
    Count leading zeros of x in a 'width'-bit field.
    Equivalent to PriorityEncoder(x.asBools.reverse) in Chisel.
    """
    if x == 0:
        return width
    return width - x.bit_length()


# ============================================================
# CustomOperator  (mirrors CustomOperator.scala)
# ============================================================

def custom_operator(inA: int, inB: int,
                    cfg: OperatorConfig) -> Tuple[int, int, int]:
    """
    Element-wise product of two MX elements.

    Returns (outSign: 0|1, outExp: signed int, outMant: unsigned int)
    where outMant is truncated to cfg.resOperatorMantWidth bits.
    """
    def get_extended_mantissa(inp: int, etype: ElementType):
        sign = (inp >> (etype.totalWidth - 1)) & 1
        if etype.name == "INT8":
            # sign-magnitude: bits[6:0] = magnitude
            magnitude = inp & _mask(etype.elementWidthMant)
            return sign, 0, magnitude
        else:
            exp  = (inp >> etype.elementWidthMant) & _mask(etype.elementWidthExp)
            mant = inp & _mask(etype.elementWidthMant)
            implicit = 1 if exp != 0 else 0          # 0 for subnormal
            full_mant = (implicit << etype.elementWidthMant) | mant
            return sign, exp, full_mant

    signA, expA, fullMantA = get_extended_mantissa(inA, cfg.elementTypeA)
    signB, expB, fullMantB = get_extended_mantissa(inB, cfg.elementTypeB)

    def adjusted_exp(exp_raw: int, etype: ElementType) -> int:
        if etype.elementWidthExp == 0:
            # INT8: exponent field is always 0; only implicit scale applies
            return etype.implicitScaleExp
        else:
            # Normal formats: subnormal exponent is fixed to 1-bias
            raw = (1 - etype.bias) if exp_raw == 0 else (exp_raw - etype.bias)
            return raw + etype.implicitScaleExp

    adj_a = adjusted_exp(expA, cfg.elementTypeA)
    adj_b = adjusted_exp(expB, cfg.elementTypeB)

    out_sign = signA ^ signB
    out_exp  = adj_a + adj_b                          # signed Python int
    product  = fullMantA * fullMantB
    out_mant = _trunc(product, cfg.resOperatorMantWidth)

    return out_sign, out_exp, out_mant


# ============================================================
# ScaleAddition  (mirrors ScaleAddition.scala)
# ============================================================

def scale_addition(in_op_sign: int, in_op_exp: int, in_op_mant: int,
                   in_share_scale_a: int, in_share_scale_b: int,
                   scfg: ScaleAddConfig) -> Tuple[int, int, int]:
    """
    Multiply the operator result by the two shared MX scale factors.

    Returns (outSign: 0|1, outExp: signed int, outMant: unsigned int)
    where outMant is truncated to scfg.resScaleAddMantWidth (== 32) bits.
    """
    stype = scfg.stype

    def get_scaled_parts(inp: int, st: ScaleType) -> Tuple[int, int]:
        exp = (inp >> st.mantScaleWidth) & _mask(st.expScaleWidth)
        if st.mantScaleWidth == 0:
            full_mant = 1                             # UE8M0: implicit-only mantissa
        else:
            mant = inp & _mask(st.mantScaleWidth)
            implicit = 1 if exp != 0 else 0
            full_mant = (implicit << st.mantScaleWidth) | mant
        return exp, full_mant

    exp_sa, mant_sa = get_scaled_parts(in_share_scale_a, stype)
    exp_sb, mant_sb = get_scaled_parts(in_share_scale_b, stype)

    def adj_scale_exp(exp_raw: int, st: ScaleType) -> int:
        # Subnormal scale (exp=0 with explicit mantissa bits): fixed to 1-bias
        if st.mantScaleWidth > 0 and exp_raw == 0:
            return 1 - st.bias
        return exp_raw - st.bias

    scale_exp  = adj_scale_exp(exp_sa, stype) + adj_scale_exp(exp_sb, stype)
    scale_mant = mant_sa * mant_sb

    out_sign = in_op_sign
    out_exp  = scale_exp + in_op_exp
    out_mant = _trunc(scale_mant * in_op_mant, scfg.resScaleAddMantWidth)

    return out_sign, out_exp, out_mant


# ============================================================
# ScaleToFP32  (mirrors ScaleToFP32.scala)
# ============================================================

def scale_to_fp32(in_sign: int, in_exp: int, in_mant: int,
                  scfg: ScaleAddConfig) -> int:
    """
    Normalize and convert (sign, biased_exp, wide_mant) to IEEE-754 FP32
    with RNE rounding.

    Returns a 32-bit unsigned integer (bit pattern).
    """
    def elem_frac(t: ElementType) -> int:
        return 0 if t.name == "INT8" else t.elementWidthMant

    frac_bits  = (elem_frac(scfg.elementTypeA) + elem_frac(scfg.elementTypeB) +
                  2 * scfg.stype.mantScaleWidth)
    mant_width = scfg.resScaleAddMantWidth   # 32
    exp_bias   = 127 + mant_width - 1 - frac_bits

    # Zero check
    if in_mant == 0:
        return 0

    # Leading-zero count on the mant_width-bit mantissa
    lzc          = _leading_zeros(in_mant, mant_width)
    shifted_mant = _trunc(in_mant << lzc, mant_width)

    # EXTRA padding so that mant23 / guard / round / sticky fields always exist.
    # For mant_width == 32, EXTRA == 0.
    EXTRA = max(0, 27 - mant_width)
    if EXTRA > 0:
        padded_shift = _trunc(shifted_mant << EXTRA, mant_width + EXTRA)
    else:
        padded_shift = shifted_mant

    # safe_extract_pos: bit index of the first fractional bit (just below implicit-1)
    safe_extract_pos = EXTRA + mant_width - 2   # == 30 when mant_width == 32

    # 23-bit mantissa field, guard, round, sticky
    mant23    = (padded_shift >> (safe_extract_pos - 22)) & _mask(23)
    guard_bit = (padded_shift >> (safe_extract_pos - 23)) & 1
    round_bit = (padded_shift >> (safe_extract_pos - 24)) & 1
    # Sticky = any bit in [safe_extract_pos-25 : 0]  OR  lzc overflowed the field
    low_bits   = padded_shift & _mask(safe_extract_pos - 24)   # bits below round_bit
    sticky_bit = int(low_bits != 0) | int(lzc > mant_width - 1)

    # RNE round-up condition
    round_up = int(guard_bit and (mant23 & 1 or round_bit or sticky_bit))
    rounded_m   = mant23 + round_up              # up to 24 bits; bit 23 = carry
    round_carry = (rounded_m >> 23) & 1
    final_mant  = rounded_m & _mask(23)

    # Biased exponent (Chisel: inExp - lzc + expBias + roundCarry)
    adj_exp = in_exp - lzc + exp_bias + round_carry

    if adj_exp >= 255:
        final_exp = 255
    elif adj_exp <= 0:
        final_exp = 0
    else:
        final_exp = adj_exp & 0xFF

    return (in_sign << 31) | (final_exp << 23) | final_mant


# ============================================================
# FP32Adder  (mirrors FP32Adder in FusedDotProductUnit.scala)
# ============================================================

def fp32_adder(a: int, b: int) -> int:
    """
    Custom IEEE-754 single-precision adder with RNE rounding.
    No NaN / Inf handling (consistent with the hardware).
    Inputs and output are 32-bit unsigned integers (bit patterns).
    """
    # Unpack operands
    val_a_s = (a >> 31) & 1
    val_a_e = (a >> 23) & 0xFF
    val_a_m = ((1 if val_a_e else 0) << 23) | (a & _mask(23))   # 24-bit with implicit-1

    val_b_s = (b >> 31) & 1
    val_b_e = (b >> 23) & 0xFF
    val_b_m = ((1 if val_b_e else 0) << 23) | (b & _mask(23))

    # Which operand has larger magnitude?
    exp_diff  = val_a_e - val_b_e                                 # signed
    if exp_diff > 0:
        a_greater = True
    elif exp_diff == 0:
        a_greater = val_a_m >= val_b_m
    else:
        a_greater = False

    far_e = val_a_e if a_greater else val_b_e
    far_m = val_a_m if a_greater else val_b_m
    far_s = val_a_s if a_greater else val_b_s
    near_m = val_b_m if a_greater else val_a_m

    abs_diff = abs(exp_diff)

    # nearM with 3 guard bits appended (27-bit value)
    near_m27 = _trunc(near_m << 3, 27)

    # Align near operand: logical right-shift
    aligned_near_m = near_m27 >> abs_diff          # Python ints: safe for large shifts

    # Sticky: any bit of near_m27 shifted past bit 0
    if abs_diff == 0:
        sticky_from_align = 0
    else:
        lost_mask = _mask(min(abs_diff, 27))
        sticky_from_align = int((near_m27 & lost_mask) != 0)

    # far operand with 3 guard zeros (27 bits)
    far_m27 = _trunc(far_m << 3, 27)

    # Add or subtract (result is 28 bits)
    is_sub = val_a_s ^ val_b_s
    if is_sub:
        res_mag = _trunc(far_m27 - aligned_near_m, 28)
    else:
        res_mag = _trunc(far_m27 + aligned_near_m, 28)

    res_sign = far_s

    # Exact cancellation → zero
    if res_mag == 0:
        return 0

    # Normalize: left-shift so that implicit-1 lands at bit 27
    lzc        = _leading_zeros(res_mag, 28)
    norm_shift = res_mag << lzc                    # wider than 28 bits is fine

    # Extract mantissa / guard / round / sticky from the shifted result
    # Bit layout after normalization: bit27=implicit-1, bits[26:4]=mant, bit3=G, bit2=R, bits[1:0]=S
    mant23    = (norm_shift >> 4) & _mask(23)
    guard_bit = (norm_shift >> 3) & 1
    round_bit = (norm_shift >> 2) & 1
    sticky_bit = int((norm_shift & _mask(2)) != 0) | sticky_from_align

    # RNE rounding
    round_up  = int(guard_bit and (mant23 & 1 or round_bit or sticky_bit))
    rounded_m = mant23 + round_up                  # up to 24 bits
    mant_carry = (rounded_m >> 23) & 1
    final_m   = rounded_m & _mask(23)

    # Chisel: finalE = farExp.zext - resLZC.asSInt + 1.S + mantCarry.zext
    final_e = far_e - lzc + 1 + mant_carry
    final_e = max(0, min(255, final_e))            # clamp (hardware doesn't, but range is safe)

    return (res_sign << 31) | (final_e << 23) | final_m


# ============================================================
# FP32 balanced reduction tree
# ============================================================

def fp32_reduce_tree(inputs: List[int]) -> int:
    """
    Balanced binary tree of FP32Adder instances.
    Odd-length input: the last element passes through unmodified (no adder).
    """
    if len(inputs) == 1:
        return inputs[0]
    next_level = []
    for i in range(0, len(inputs), 2):
        if i + 1 < len(inputs):
            next_level.append(fp32_adder(inputs[i], inputs[i + 1]))
        else:
            next_level.append(inputs[i])          # odd lane passes through
    return fp32_reduce_tree(next_level)


# ============================================================
# FusedDotProductUnit — one combinational cycle
# ============================================================

def fused_dot_product_unit(
    op_a_packed:    int,
    op_b_packed:    int,
    share_exp_a:    int,
    share_exp_b:    int,
    scfg:           ScaleAddConfig,
    vector_size:    int,
    acc_reg:        int  = 0,
    valid_in:       bool = True,
    reset_acc:      bool = False,
) -> Tuple[int, int, List[int]]:
    """
    Simulate one clock cycle of FusedDotProductUnit.

    Inputs
    ------
    op_a_packed  : packed bit vector [vectorSize*wA-1 : 0]; lane i at bits [i*wA +: wA]
    op_b_packed  : packed bit vector [vectorSize*wB-1 : 0]
    share_exp_a  : shared MX scale for A (stype.totalScaleWidth bits)
    share_exp_b  : shared MX scale for B
    scfg         : ScaleAddConfig
    vector_size  : number of parallel MAC lanes
    acc_reg      : current accumulator value (FP32 bit pattern), default 0
    valid_in     : drive the validIn control signal
    reset_acc    : drive the resetAcc control signal (synchronous)

    Returns
    -------
    (new_acc_reg, reduced_sum, lane_fp32_results)
      new_acc_reg      : updated accumulator (FP32 bit pattern)
      reduced_sum      : output of the FP32 reduction tree (FP32 bit pattern)
      lane_fp32_results: list of per-lane FP32 bit patterns
    """
    wA = scfg.elementTypeA.totalWidth
    wB = scfg.elementTypeB.totalWidth
    op_cfg = OperatorConfig(scfg.elementTypeA, scfg.elementTypeB)

    lane_results: List[int] = []
    for i in range(vector_size):
        inA = (op_a_packed >> (i * wA)) & _mask(wA)
        inB = (op_b_packed >> (i * wB)) & _mask(wB)

        # Stage 1: CustomOperator
        op_sign, op_exp, op_mant = custom_operator(inA, inB, op_cfg)

        # Stage 2: ScaleAddition
        sa_sign, sa_exp, sa_mant = scale_addition(
            op_sign, op_exp, op_mant,
            share_exp_a, share_exp_b, scfg
        )

        # Stage 3: ScaleToFP32
        fp32_val = scale_to_fp32(sa_sign, sa_exp, sa_mant, scfg)
        lane_results.append(fp32_val)

    # Stage 4: FP32 balanced reduction tree
    reduced_sum = fp32_reduce_tree(lane_results)

    # Stage 5: FP32 accumulator register logic
    if reset_acc:
        new_acc = 0
    elif valid_in:
        new_acc = fp32_adder(acc_reg, reduced_sum)
    else:
        new_acc = acc_reg

    return new_acc, reduced_sum, lane_results


# ============================================================
# Utility helpers
# ============================================================

def fp32_bits_to_float(bits: int) -> float:
    """Convert a 32-bit FP32 bit pattern to Python float."""
    return struct.unpack('f', struct.pack('I', bits & 0xFFFFFFFF))[0]

def float_to_fp32_bits(f: float) -> int:
    """Convert a Python float to its 32-bit FP32 bit pattern."""
    return struct.unpack('I', struct.pack('f', f))[0]


# ============================================================
# Self-test / usage example
# ============================================================

if __name__ == "__main__":
    # ----------------------------------------------------------------
    # Config: INT8 × E2M1 / UE8M0, vectorSize = 4
    # ----------------------------------------------------------------
    scfg        = ScaleAddConfig(MXFormats.INT8, MXFormats.E2M1, ScaleFormats.UE8M0)
    vector_size = 4

    print("=" * 60)
    print(f"Config: {scfg.elementTypeA.name} x {scfg.elementTypeB.name} / {scfg.stype.name}")
    print(f"  resOperatorExpWidth   = {scfg.resOperatorExpWidth}")
    print(f"  resOperatorMantWidth  = {scfg.resOperatorMantWidth}")
    print(f"  resScaleAddExpWidth   = {scfg.resScaleAddExpWidth}")
    print(f"  resScaleAddMantWidth  = {scfg.resScaleAddMantWidth}")
    print("=" * 60)

    # ----------------------------------------------------------------
    # Single-cycle test
    # ----------------------------------------------------------------
    # INT8 encoding: sign-magnitude, bit[7]=sign, bits[6:0]=magnitude
    #   0x01 → sign=0, mag=1  →  value = 1 × 2^-6 = 0.015625
    # E2M1 encoding: 4 bits, exp=2b, mant=1b, bias=1
    #   0x05 = 0b0101 → sign=0, exp=0b10=2, mant=0b1=1 → 2^(2-1) × 1.5 = 3.0
    # UE8M0 scale: 0x7F = 2^(127-127) = 1.0
    inA     = 0x01   # MXINT8: value = 0.015625
    inB     = 0x05   # E2M1:   value = 3.0
    scale_a = 0x7F   # UE8M0:  1.0
    scale_b = 0x7F   # UE8M0:  1.0

    # Pack 4 identical lanes
    op_a = sum(inA << (i * scfg.elementTypeA.totalWidth) for i in range(vector_size))
    op_b = sum(inB << (i * scfg.elementTypeB.totalWidth) for i in range(vector_size))

    new_acc, reduced_sum, lane_fp32 = fused_dot_product_unit(
        op_a, op_b, scale_a, scale_b, scfg, vector_size
    )

    print("\nPer-lane FP32 results:")
    for i, fp in enumerate(lane_fp32):
        print(f"  Lane {i}: 0x{fp:08X}  {fp32_bits_to_float(fp):.6f}")

    print(f"\nReduction tree output: 0x{reduced_sum:08X}  {fp32_bits_to_float(reduced_sum):.6f}")
    print(f"Accumulator (cycle 1): 0x{new_acc:08X}  {fp32_bits_to_float(new_acc):.6f}")

    # Software golden: each lane = (1 × 2^-6) × 3.0 × 1.0 × 1.0 = 0.046875
    # Sum of 4 lanes = 0.1875
    expected = 4.0 * (1 * 2**-6) * 3.0 * 1.0 * 1.0
    print(f"\nSoftware golden (float64): {expected:.6f}")

    # ----------------------------------------------------------------
    # Multi-cycle accumulation test
    # ----------------------------------------------------------------
    print("\n--- Multi-cycle accumulation (4 cycles, same inputs) ---")
    acc = 0
    for cycle in range(4):
        acc, rs, _ = fused_dot_product_unit(
            op_a, op_b, scale_a, scale_b, scfg, vector_size,
            acc_reg=acc, valid_in=True, reset_acc=False
        )
        print(f"  Cycle {cycle + 1}: acc = 0x{acc:08X}  {fp32_bits_to_float(acc):.6f}")

    print(f"\nSoftware golden after 4 cycles: {4.0 * expected:.6f}")

    # ----------------------------------------------------------------
    # FP32Adder sanity check
    # ----------------------------------------------------------------
    print("\n--- FP32Adder sanity checks ---")
    cases = [
        (float_to_fp32_bits(1.0),  float_to_fp32_bits(2.0),  3.0),
        (float_to_fp32_bits(0.5),  float_to_fp32_bits(-0.5), 0.0),
        (float_to_fp32_bits(1e10), float_to_fp32_bits(1.0),  1e10 + 1.0),
    ]
    for a_bits, b_bits, expected_f in cases:
        result = fp32_adder(a_bits, b_bits)
        result_f = fp32_bits_to_float(result)
        expected_bits = float_to_fp32_bits(expected_f)
        match = "OK" if result == expected_bits else f"MISMATCH (expected 0x{expected_bits:08X})"
        print(f"  {fp32_bits_to_float(a_bits)} + {fp32_bits_to_float(b_bits)}"
              f" = {result_f:.6g}  [{match}]")
