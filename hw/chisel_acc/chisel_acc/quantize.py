#!/usr/bin/env python3

# Copyright 2025 KU Leuven.
# Licensed under the Apache License, Version 2.0, see LICENSE for details.
# SPDX-License-Identifier: Apache-2.0

"""
Floating-point and integer quantization utilities.

Supported dtypes (as strings):
  'fp8_e4m3'  - FP8 E4M3 (bias=7,  max=448)
  'fp8_e5m2'  - FP8 E5M2 (bias=15, max=57344)
  'fp6_e3m2'  - FP6 E3M2 (bias=3,  max=28)
  'fp6_e2m3'  - FP6 E2M3 (bias=1,  max=7.5)
  'fp4_e2m1'  - FP4 E2M1 (bias=1,  max=6)
  'mxint8'    - MXINT8: 1S+7M, implicit scale 2^-6, range ≈ [-1.984, 1.984]
  'int8'      - Signed 8-bit integer [-128, 127] (global affine)

Public API:
  quantize(arr, dtype, packed=False)
      FP formats  -> (q_float32, q_raw_uint8)
      'int8'      -> (q_float32, scale, q_raw_int8)

  quantize_mx(arr, dtype, block_size, axis, packed=False)
      -> (q_float32, exp_shared, q_raw)

  quantize_mx_v2(arr, dtype, block_size, axis, packed=False, scale_format='e8m0')
      scale_format: 'e8m0','e7m1','e6m2','e5m3','e4m4','e3m5','e2m6','e1m7','e0m8'
      -> (q_float32, scale_shared, scale_raw_uint8, q_raw)

  packed=False: each element occupies one uint8 (bits in LSBs).
  packed=True:  tight bit-packing per row —
      FP4 (4-bit): 2 elements per byte  (lo nibble = elem[2i], hi = elem[2i+1])
      FP6 (6-bit): 4 elements per 3 bytes (little-endian 6-bit lanes)
      FP8 / int8:  no difference (already 8-bit)
"""

import numpy as np

# ---------------------------------------------------------------------------
# Format classes
# ---------------------------------------------------------------------------

class E5M2:
    """FP8: 1-bit sign, 5-bit exponent (bias=15), 2-bit mantissa. Max=57344."""
    BIAS = 15
    MAX_EXP_BIASED = 30   # 31 is Inf/NaN
    MAX_VAL = 57344.0     # 2^15 * (1 + 3/4)

    def __init__(self, sign=0, exponent=0, mantissa=0):
        self.sign = int(sign)
        self.exponent = int(exponent)
        self.mantissa = int(mantissa)

    def pack(self):
        return np.uint8((self.sign << 7) | (self.exponent << 2) | self.mantissa)

    def unpack(self, value):
        self.sign = (value >> 7) & 0x1
        self.exponent = (value >> 2) & 0x1F
        self.mantissa = value & 0x3
        return self

    def to_float(self):
        if self.exponent == 0:
            val = (2.0 ** (1 - self.BIAS)) * (self.mantissa / 4.0)
        else:
            val = (2.0 ** (self.exponent - self.BIAS)) * (1.0 + self.mantissa / 4.0)
        return -val if self.sign else val

    def quantize(self, x):
        if x == 0.0:
            self.sign, self.exponent, self.mantissa = 0, 0, 0
            return self
        sign = int(x < 0)
        abs_x = abs(x)
        if abs_x >= self.MAX_VAL:
            self.sign, self.exponent, self.mantissa = sign, self.MAX_EXP_BIASED, 3
            return self
        exp = int(np.floor(np.log2(abs_x)))
        exp_enc = exp + self.BIAS
        if exp_enc <= 0:
            mant = int(np.round(abs_x / (2.0 ** (1 - self.BIAS)) * 4))
            if mant == (1 << 2):  # carry out of subnormal → promote to smallest normal
                self.sign, self.exponent, self.mantissa = sign, 1, 0
            else:
                self.sign, self.exponent, self.mantissa = sign, 0, mant
        else:
            mant = int(np.round((abs_x / (2.0 ** exp) - 1.0) * 4))
            if mant >= 4:
                exp_enc += 1
                mant = 0
            exp_enc = min(exp_enc, self.MAX_EXP_BIASED)
            self.sign, self.exponent, self.mantissa = sign, exp_enc, mant
        return self


class E4M3:
    """FP8: 1-bit sign, 4-bit exponent (bias=7), 3-bit mantissa. Max=448.
    Follows OCP MX spec: exp=15,mant=7 is NaN; max normal is exp=15,mant=6 -> 448.
    """
    BIAS = 7
    MAX_EXP_BIASED = 15
    MAX_VAL = 448.0       # 2^(15-7) * (1 + 6/8) = 256 * 1.75

    def __init__(self, sign=0, exponent=0, mantissa=0):
        self.sign = int(sign)
        self.exponent = int(exponent)
        self.mantissa = int(mantissa)

    def pack(self):
        return np.uint8((self.sign << 7) | (self.exponent << 3) | self.mantissa)

    def unpack(self, value):
        self.sign = (value >> 7) & 0x1
        self.exponent = (value >> 3) & 0x0F   # 4-bit mask (fixed: was 0x1F)
        self.mantissa = value & 0x7
        return self

    def to_float(self):
        if self.exponent == 0:
            val = (2.0 ** (1 - self.BIAS)) * (self.mantissa / 8.0)
        else:
            val = (2.0 ** (self.exponent - self.BIAS)) * (1.0 + self.mantissa / 8.0)
        return -val if self.sign else val

    def quantize(self, x):
        if x == 0.0:
            self.sign, self.exponent, self.mantissa = 0, 0, 0
            return self
        sign = int(x < 0)
        abs_x = abs(x)
        if abs_x >= self.MAX_VAL:
            self.sign, self.exponent, self.mantissa = sign, 15, 6   # fixed: was 3
            return self
        exp = int(np.floor(np.log2(abs_x)))
        exp_enc = exp + self.BIAS
        if exp_enc <= 0:
            mant = int(np.round(abs_x / (2.0 ** (1 - self.BIAS)) * 8))
            if mant == (1 << 3):  # carry out of subnormal → promote to smallest normal
                self.sign, self.exponent, self.mantissa = sign, 1, 0
            else:
                self.sign, self.exponent, self.mantissa = sign, 0, mant
        else:
            mant = int(np.round((abs_x / (2.0 ** exp) - 1.0) * 8))
            if mant >= 8:
                exp_enc += 1
                mant = 0
            # Cap at max normal (exp=15, mant=6); exp=15,mant=7 is NaN
            if exp_enc > 15 or (exp_enc == 15 and mant > 6):
                exp_enc, mant = 15, 6
            self.sign, self.exponent, self.mantissa = sign, exp_enc, mant
        return self


class E3M2:
    """FP6: 1-bit sign, 3-bit exponent (bias=3), 2-bit mantissa. Max=28.
    Packed as the lower 6 bits of a uint8: S EEE MM.
    No Inf/NaN encoding (all bit patterns are valid finite values).
    """
    BIAS = 3
    MAX_EXP_BIASED = 7
    MAX_VAL = 28.0        # 2^(7-3) * (1 + 3/4) = 16 * 1.75

    def __init__(self, sign=0, exponent=0, mantissa=0):
        self.sign = int(sign)
        self.exponent = int(exponent)
        self.mantissa = int(mantissa)

    def pack(self):
        return np.uint8((self.sign << 5) | (self.exponent << 2) | self.mantissa)

    def unpack(self, value):
        self.sign = (value >> 5) & 0x1
        self.exponent = (value >> 2) & 0x7
        self.mantissa = value & 0x3
        return self

    def to_float(self):
        if self.exponent == 0:
            val = (2.0 ** (1 - self.BIAS)) * (self.mantissa / 4.0)
        else:
            val = (2.0 ** (self.exponent - self.BIAS)) * (1.0 + self.mantissa / 4.0)
        return -val if self.sign else val

    def quantize(self, x):
        if x == 0.0:
            self.sign, self.exponent, self.mantissa = 0, 0, 0
            return self
        sign = int(x < 0)
        abs_x = abs(x)
        if abs_x >= self.MAX_VAL:
            self.sign, self.exponent, self.mantissa = sign, self.MAX_EXP_BIASED, 3
            return self
        exp = int(np.floor(np.log2(abs_x)))
        exp_enc = exp + self.BIAS
        if exp_enc <= 0:
            # 除以步长，并使用 np.round 进行银行家舍入 (RNTE)
            mant = int(np.round(abs_x / (2.0 ** (1 - self.BIAS)) * 4))
            
            # 如果尾数进位溢出 (比如 E2M1 中 mant 变成了 2)
            # 意味着它跨越了边界，晋升为最小的正规值 (Normal)
            if mant == (1 << 2):
                self.sign, self.exponent, self.mantissa = sign, 1, 0
            else:
                self.sign, self.exponent, self.mantissa = sign, 0, mant
        else:
            mant = int(np.round((abs_x / (2.0 ** exp) - 1.0) * 4))
            if mant >= 4:
                exp_enc += 1
                mant = 0
            exp_enc = min(exp_enc, self.MAX_EXP_BIASED)
            self.sign, self.exponent, self.mantissa = sign, exp_enc, mant
        return self


class E2M3:
    """FP6: 1-bit sign, 2-bit exponent (bias=1), 3-bit mantissa. Max=7.5.
    Packed as the lower 6 bits of a uint8: S EE MMM.
    No Inf/NaN encoding.
    """
    BIAS = 1
    MAX_EXP_BIASED = 3
    MAX_VAL = 7.5         # 2^(3-1) * (1 + 7/8) = 4 * 1.875

    def __init__(self, sign=0, exponent=0, mantissa=0):
        self.sign = int(sign)
        self.exponent = int(exponent)
        self.mantissa = int(mantissa)

    def pack(self):
        return np.uint8((self.sign << 5) | (self.exponent << 3) | self.mantissa)

    def unpack(self, value):
        self.sign = (value >> 5) & 0x1
        self.exponent = (value >> 3) & 0x3
        self.mantissa = value & 0x7
        return self

    def to_float(self):
        if self.exponent == 0:
            val = (2.0 ** (1 - self.BIAS)) * (self.mantissa / 8.0)
        else:
            val = (2.0 ** (self.exponent - self.BIAS)) * (1.0 + self.mantissa / 8.0)
        return -val if self.sign else val

    def quantize(self, x):
        if x == 0.0:
            self.sign, self.exponent, self.mantissa = 0, 0, 0
            return self
        sign = int(x < 0)
        abs_x = abs(x)
        if abs_x >= self.MAX_VAL:
            self.sign, self.exponent, self.mantissa = sign, self.MAX_EXP_BIASED, 7
            return self
        exp = int(np.floor(np.log2(abs_x)))
        exp_enc = exp + self.BIAS
        # if exp_enc < 0:
        #     self.sign, self.exponent, self.mantissa = sign, 0, 0
        # elif exp_enc == 0:
        #     mant = int(np.round(abs_x / (2.0 ** (1 - self.BIAS)) * 8))
        #     mant = max(0, min(7, mant))
        #     self.sign, self.exponent, self.mantissa = sign, 0, mant
        if exp_enc <= 0:
            # 除以步长，并使用 np.round 进行银行家舍入 (RNTE)
            mant = int(np.round(abs_x / (2.0 ** (1 - self.BIAS)) * 8))
            
            # 如果尾数进位溢出 (比如 E2M1 中 mant 变成了 2)
            # 意味着它跨越了边界，晋升为最小的正规值 (Normal)
            if mant == (1 << 3):
                self.sign, self.exponent, self.mantissa = sign, 1, 0
            else:
                self.sign, self.exponent, self.mantissa = sign, 0, mant
        else:
            mant = int(np.round((abs_x / (2.0 ** exp) - 1.0) * 8))
            if mant >= 8:
                exp_enc += 1
                mant = 0
            exp_enc = min(exp_enc, self.MAX_EXP_BIASED)
            self.sign, self.exponent, self.mantissa = sign, exp_enc, mant
        return self


class E2M1:
    """FP4: 1-bit sign, 2-bit exponent (bias=1), 1-bit mantissa. Max=6.
    Packed as the lower 4 bits of a uint8: S EE M.
    """
    BIAS = 1
    MAX_EXP_BIASED = 3
    MAX_VAL = 6.0         # 2^(3-1) * (1 + 1/2) = 4 * 1.5

    def __init__(self, sign=0, exponent=0, mantissa=0):
        self.sign = int(sign)
        self.exponent = int(exponent)
        self.mantissa = int(mantissa)

    def pack(self):
        return np.uint8((self.sign << 3) | (self.exponent << 1) | self.mantissa)

    def unpack(self, value):
        self.sign = (value >> 3) & 0x1
        self.exponent = (value >> 1) & 0x3
        self.mantissa = value & 0x1
        return self

    def to_float(self):
        if self.exponent == 0:
            val = (2.0 ** (1 - self.BIAS)) * (self.mantissa / 2.0)
        else:
            val = (2.0 ** (self.exponent - self.BIAS)) * (1.0 + self.mantissa / 2.0)
        return -val if self.sign else val

    def quantize(self, x):
        if x == 0.0:
            self.sign, self.exponent, self.mantissa = 0, 0, 0
            return self
        sign = int(x < 0)
        abs_x = abs(x)
        if abs_x >= self.MAX_VAL:
            self.sign, self.exponent, self.mantissa = sign, self.MAX_EXP_BIASED, 1
            return self
        exp = int(np.floor(np.log2(abs_x)))   # fixed: was np.round
        exp_enc = exp + self.BIAS
        # if exp_enc < 0:
        #     self.sign, self.exponent, self.mantissa = sign, 0, int(np.round(abs_x / (2.0 ** (1 - self.BIAS)) * 2))
        # elif exp_enc == 0:
        #     mant = int(np.round(abs_x / (2.0 ** (1 - self.BIAS)) * 2))
        #     mant = max(0, min(1, mant))
        #     self.sign, self.exponent, self.mantissa = sign, 0, mant
        if exp_enc <= 0:
            # 计算次极小值（subnormal）的步长
            # E2M1中为: 2^(1 - 1) * 0.5 = 0.5
            subnormal_step = (2.0 ** (1 - self.BIAS)) / 2.0
            
            # 使用 np.round 进行缩放后的四舍五入。
            # Python/NumPy 的 np.round 默认就是 Ties to Even
            mant = int(np.round(abs_x / subnormal_step))
            
            if mant == 0:
                self.sign, self.exponent, self.mantissa = sign, 0, 0
            elif mant == 1:
                self.sign, self.exponent, self.mantissa = sign, 0, 1
            else:
                self.sign, self.exponent, self.mantissa = sign, 1, 0
        else:
            mant = int(np.round((abs_x / (2.0 ** exp) - 1.0) * 2))
            if mant >= 2:
                exp_enc += 1
                mant = 0
            exp_enc = min(exp_enc, self.MAX_EXP_BIASED)
            self.sign, self.exponent, self.mantissa = sign, exp_enc, mant
        return self

class MXINT8:
    """MXINT8: 1-bit sign + 7-bit magnitude. Value = sign × magnitude / 64.
    Implicit scale 2^-6; range ≈ [-1.984, 1.984] (max magnitude = 127/64).
    """

    def __init__(self, sign=0, exponent=0):
        self.sign = int(sign)          # 1 bit
        self.exponent = int(exponent)  # 7-bit magnitude field

    def pack(self):
        bits = (self.sign << 7) | self.exponent
        return np.uint8(bits)

    def unpack(self, value):
        self.sign = (value >> 7) & 0x1
        self.exponent = value & 0x7F
        return self

    def to_float(self):
        val = self.exponent / 64.0  # implicit scale 2^-6
        return -val if self.sign else val

    def quantize(self, x):
        if x == 0:
            self.sign, self.exponent = 0, 0
            return self
        self.sign = int(x < 0)
        abs_x = abs(x)
        if abs_x >= 2.0:
            self.exponent = 127
            return self
        self.exponent = int(np.round(abs_x * 64))
        return self


# ---------------------------------------------------------------------------
# (NEW) Generalized shared scale format support (E8M0 through E0M8)
# ---------------------------------------------------------------------------

class ScaleFormat:
    """无符号 8-bit 共享 scale 格式（ebits + mbits = 8，无符号位）。

    支持的格式（ebits, mbits）：
      E8M0(8,0), E7M1(7,1), E6M2(6,2), E5M3(5,3), E4M4(4,4),
      E3M5(3,5), E2M6(2,6), E1M7(1,7), E0M8(0,8)

    NaN/Inf 规则：
      E8M0：raw=255 → NaN（OCP MX 标准），saturate_raw=254
      E7M1-E0M8：无 NaN，所有 256 个 bit pattern 均为有效正数，saturate_raw=255
    """

    def __init__(self, ebits: int, mbits: int):
        assert ebits + mbits == 8, "ebits + mbits must equal 8"
        assert ebits >= 0 and mbits >= 0
        self.ebits = ebits
        self.mbits = mbits

        if ebits == 0:                          # E0M8：纯定点，无指数位
            self.bias = 0
            self.max_val = 255.0 / 128.0        # 255 × 2^(-7)
            self.saturate_raw = 255
        elif mbits == 0:                        # E8M0：OCP MX 标准，raw=255=NaN
            self.bias = 127
            self.max_val = 2.0 ** 127
            self.saturate_raw = 254             # raw=255 reserved for NaN
        else:                                   # E7M1 ~ E1M7：全1指数有效，无 NaN
            self.bias     = (1 << (ebits - 1)) - 1
            max_exp_b     = (1 << ebits) - 1    # all-ones exponent is VALID (not NaN)
            max_mant      = (1 << mbits)  - 1
            self.max_val  = (2.0 ** (max_exp_b - self.bias)) * (1.0 + max_mant / (2 ** mbits))
            self.saturate_raw = (max_exp_b << mbits) | max_mant  # always 0xFF = 255

    def _raw_to_float(self, raw: int) -> float:
        """将 8-bit 无符号 raw 值解码为 float。"""
        if self.ebits == 0:                     # E0M8
            return raw / 128.0
        if self.mbits == 0:                     # E8M0
            return 2.0 ** (raw - self.bias)
        # 通用 ExMy
        exp_biased = raw >> self.mbits
        mant_bits  = raw & ((1 << self.mbits) - 1)
        if exp_biased == 0:                     # 次正规
            return (2.0 ** (1 - self.bias)) * (mant_bits / (2 ** self.mbits))
        return (2.0 ** (exp_biased - self.bias)) * (1.0 + mant_bits / (2 ** self.mbits))

    def quantize(self, ideal_scale: float) -> tuple:
        """将理想 scale 值量化到本格式。

        Returns:
            (quantized_float, raw_uint8): 量化后的 float 值和 8-bit 原始编码
        """
        if ideal_scale <= 0.0:
            return 0.0, 0

        if ideal_scale >= self.max_val:         # 饱和（含上溢保护）
            sat_float = self._raw_to_float(self.saturate_raw)
            return sat_float, self.saturate_raw

        if self.ebits == 0:                     # E0M8：定点，步长 1/128
            raw = int(np.clip(int(np.round(ideal_scale * 128.0)), 0, 255))
            return raw / 128.0, raw

        if self.mbits == 0:                     # E8M0：纯 2 的幂
            exp = int(np.floor(np.log2(ideal_scale + 1e-45)))
            raw = int(np.clip(exp + self.bias, 0, self.saturate_raw))
            return 2.0 ** (raw - self.bias), raw

        # 通用 ExMy（ebits>0, mbits>0）
        exp       = int(np.floor(np.log2(ideal_scale + 1e-45)))
        exp_biased = exp + self.bias

        if exp_biased <= 0:                     # 次正规
            subnorm_unit = 2.0 ** (1 - self.bias) / (2 ** self.mbits)
            mant = int(np.round(ideal_scale / subnorm_unit))
            if mant >= (1 << self.mbits):       # 进位 → 最小正规数
                raw = 1 << self.mbits           # exp_biased=1, mant=0
            else:
                mant = int(np.clip(mant, 0, (1 << self.mbits) - 1))
                raw  = mant
            return self._raw_to_float(raw), raw

        # 正规数
        frac = ideal_scale / (2.0 ** exp)
        mant = int(np.round((frac - 1.0) * (2 ** self.mbits)))
        if mant >= (1 << self.mbits):           # 进位
            mant = 0
            exp_biased += 1
        max_exp_b = (1 << self.ebits) - 1
        if exp_biased > max_exp_b:              # 上溢 → saturate（max_val 应已拦截）
            return self._raw_to_float(self.saturate_raw), self.saturate_raw
        raw = (exp_biased << self.mbits) | mant
        return self._raw_to_float(raw), raw


VALID_SCALE_FORMATS = {
    'UE8M0': ScaleFormat(8, 0),   # OCP MX 默认（纯 2 的幂，E8M0）
    'UE7M1': ScaleFormat(7, 1),
    'UE6M2': ScaleFormat(6, 2),
    'UE5M3': ScaleFormat(5, 3),
    'UE4M4': ScaleFormat(4, 4),
    'UE3M5': ScaleFormat(3, 5),
    'UE2M6': ScaleFormat(2, 6),
    'UE1M7': ScaleFormat(1, 7),
    'UE0M8': ScaleFormat(0, 8),
}


# ---------------------------------------------------------------------------
# Internal dispatch table
# ---------------------------------------------------------------------------

_DATA_CLASSES = {
    'fp8_e4m3': E4M3,
    'fp8_e5m2': E5M2,
    'fp6_e3m2': E3M2,
    'fp6_e2m3': E2M3,
    'fp4_e2m1': E2M1,
    'mxint8':   MXINT8,
}

# Maximum unbiased exponent representable by each element format.
# Used to compute the MX shared exponent:
#   shared_exp = floor(log2(max_abs)) - EMAX_elem
# This ensures max_abs / 2^shared_exp fits within the element format.
_EMAX = {
    'fp8_e5m2': 15,   # max unbiased exp = 30 - 15
    'fp8_e4m3': 8,    # max unbiased exp = 15 - 7  (using exp=15, mant=6 → 448)
    'fp6_e3m2': 4,    # max unbiased exp = 7 - 3
    'fp6_e2m3': 2,    # max unbiased exp = 3 - 1
    'fp4_e2m1': 2,    # max unbiased exp = 3 - 1
    'mxint8':   0,    # values in [-1.984, 1.984]; shared_exp = floor(log2(max_abs))
}

_AMAX = {
    'fp8_e4m3': 57344,
    'fp8_e5m2': 448,
    'fp6_e3m2': 7.5,
    'fp6_e2m3': 28.0,
    'fp4_e2m1': 6.0,
    'mxint8':   1.984375,
}

# Logical bit width of each element format (before any bit-packing)
_DTYPE_BITS = {
    'fp8_e4m3': 8,
    'fp8_e5m2': 8,
    'fp6_e3m2': 6,
    'fp6_e2m3': 6,
    'fp4_e2m1': 4,
    'mxint8':   8,
}

VALID_DTYPES = list(_DATA_CLASSES.keys()) + ['int8']


# ---------------------------------------------------------------------------
# Bit-packing helper
# ---------------------------------------------------------------------------

def _pack_raw_1d(raw_bytes: np.ndarray, dtype: str) -> np.ndarray:
    """Pack a 1D array of per-element uint8 (from .pack()) into tight bit packing.

    FP4 (fp4_e2m1):        2 elements per byte — lo nibble = elem[2i], hi nibble = elem[2i+1]
    FP6 (fp6_e3m2/e2m3):  4 elements per 3 bytes — little-endian 6-bit lanes
    FP8 / int8:            no-op (already 8-bit)
    """
    raw = raw_bytes.astype(np.uint8)

    if dtype == 'fp4_e2m1':
        n = len(raw)
        if n % 2:
            raw = np.concatenate([raw, np.zeros(1, dtype=np.uint8)])
        out = (raw[0::2] & 0xF) | ((raw[1::2] & 0xF) << 4)
        return out.astype(np.uint8)

    if dtype in ('fp6_e3m2', 'fp6_e2m3'):
        n = len(raw)
        pad = (4 - n % 4) % 4
        if pad:
            raw = np.concatenate([raw, np.zeros(pad, dtype=np.uint8)])
        raw = raw & 0x3F  # keep only 6 bits per element， AND with the whole array
        # groups of 4 elements → 3 bytes (24 bits), little-endian lane order:
        #   bits[ 5: 0] = elem0,  bits[11: 6] = elem1,
        #   bits[17:12] = elem2,  bits[23:18] = elem3
        #e0 is the array that contains element 0,4,8...
        e0, e1, e2, e3 = raw[0::4], raw[1::4], raw[2::4], raw[3::4]
        n_groups = len(raw) // 4
        out = np.zeros(n_groups * 3, dtype=np.uint8)
        # |(xxxxxx)(xx|xxxx)(xxxx|xx)(xxxxxx)|
        out[0::3] = e0        | ((e1 & 0x03) << 6)
        out[1::3] = (e1 >> 2) | ((e2 & 0x0F) << 4)
        out[2::3] = (e2 >> 4) | (e3 << 2)
        return out

    return raw  # FP8 / int8: already byte-aligned


# ---------------------------------------------------------------------------
# Part 1: Element-wise array quantization
# ---------------------------------------------------------------------------

def quantize(arr: np.ndarray, dtype: str, packed: bool = False):
    """Quantize a float32 array element-wise to the specified format.

    Args:
        arr:    Input array (any shape). Cast to float32 internally.
        dtype:  One of 'fp8_e4m3', 'fp8_e5m2', 'fp6_e3m2', 'fp6_e2m3',
                'fp4_e2m1', 'int8'.
        packed: If True, apply tight bit-packing to the raw output (affects
                FP4 and FP6 only; FP8/int8 are already byte-aligned).
                The packed raw array is 1D regardless of input shape.

    Returns:
        For FP formats:
            (q_float32, q_raw)
            q_float32 — dequantized values, same shape as arr, float32.
            q_raw     — raw bit patterns, uint8.
                        packed=False: same shape as arr, one element per byte.
                        packed=True:  1D, tightly packed (see module docstring).
        For 'int8':
            (q_float32, scale, q_raw_int8)
            q_float32  — dequantized float32, same shape as arr.
            scale      — float, global scale = max(|arr|)/127.
            q_raw_int8 — raw int8 values, same shape as arr.
    """
    arr = np.asarray(arr, dtype=np.float32)

    if dtype == 'int8':
        max_abs = float(np.max(np.abs(arr)))
        scale = max_abs / 127.0 if max_abs > 0 else 1.0
        q_int8 = np.round(arr / scale).clip(-128, 127).astype(np.int8)
        q_float = q_int8.astype(np.float32) * scale
        return q_float, scale, q_int8

    if dtype not in _DATA_CLASSES:
        raise ValueError(f"Unknown dtype {dtype!r}. Choose from {VALID_DTYPES}")

    cls = _DATA_CLASSES[dtype]
    flat = arr.flatten()
    q_flat = np.empty(flat.shape, dtype=np.float32)
    raw_flat = np.empty(flat.shape, dtype=np.uint8)

    for i, val in enumerate(flat):
        obj = cls().quantize(float(val))
        q_flat[i] = obj.to_float()
        raw_flat[i] = obj.pack()

    q_float = q_flat.reshape(arr.shape)
    raw = _pack_raw_1d(raw_flat, dtype) if packed else raw_flat.reshape(arr.shape)
    return q_float, raw


# ---------------------------------------------------------------------------
# Part 2: MX (Microscaling) block quantization with shared exponent
# ---------------------------------------------------------------------------

def _find_shared_exp(block: np.ndarray, dtype: str) -> float:
    """Compute the E8M0 shared exponent for one block.

    For FP element formats:
        shared_exp = floor(log2(max_abs)) - EMAX_elem
    For INT8:
        shared_exp = ceil(log2(max_abs / 127))
        (ensures max_abs / 2^shared_exp <= 127)

    The result is clipped to [-127, 127].
    """
    max_abs = float(np.max(np.abs(block)))
    if max_abs == 0.0:
        return -127.0

    if dtype == 'int8':
        shared_exp = float(np.ceil(np.log2(max_abs / 127.0 + 1e-30)))
    else:
        emax = _EMAX[dtype]
        shared_exp = float(np.floor(np.log2(max_abs + 1e-30))) - emax

    return float(np.clip(shared_exp, -127, 127))


# (NEW) Generalized scale computation supporting any ScaleFormat; old _find_shared_exp kept
def _compute_block_scale(block: np.ndarray, dtype: str, scale_fmt: ScaleFormat) -> tuple:
    """Compute and quantize the shared scale for one block using the given ScaleFormat.

    For FP element formats:
        ideal_scale = 2^((log2(max_abs)) - EMAX_elem)
    Returns:
        (scale_float, scale_raw): quantized scale as float and raw 8-bit encoding
    """
    max_abs = float(np.max(np.abs(block)))
    if max_abs == 0.0:
        return 0.0, 0
    if dtype == 'int8':
        ideal_scale = max_abs / 127.0
    else:
        # if scale_fmt == 'UE8M0':
        #     ideal_scale = 2.0 ** (float(np.floor(np.log2(max_abs + 1e-30))) - _EMAX[dtype])
        # else:
        ideal_scale = 2.0 ** (float(np.log2(max_abs + 1e-30)) - _EMAX[dtype])
    return scale_fmt.quantize(ideal_scale)


def _quantize_block(block: np.ndarray, dtype: str, shared_exp: float):
    """Normalize a block by 2^shared_exp, quantize, then denormalize.

    Returns:
        q_float (np.ndarray float32): dequantized block values.
        raw     (np.ndarray uint8):   per-element raw bit patterns (one per byte).
                                      For int8 the raw array has dtype int8.
    """
    scale = 2.0 ** shared_exp
    norm = block / scale

    if dtype == 'int8':
        q_int8 = np.round(norm).clip(-128, 127).astype(np.int8)
        return (q_int8.astype(np.float32) * scale), q_int8

    cls = _DATA_CLASSES[dtype]
    q_float = np.empty(block.shape, dtype=np.float32)
    raw = np.empty(block.shape, dtype=np.uint8)
    for i, val in enumerate(norm.flat):
        obj = cls().quantize(float(val))
        q_float.flat[i] = obj.to_float() * scale
        raw.flat[i] = obj.pack()
    return q_float, raw


# (NEW) Accepts a scale value directly instead of an exponent; old _quantize_block kept
def _quantize_block_by_scale(block: np.ndarray, dtype: str, scale: float) -> tuple:
    """Normalize a block by `scale`, quantize, then denormalize.

    Identical to _quantize_block but takes a scale value (float) instead of a
    shared exponent, allowing non-power-of-2 scales from generalized ScaleFormats.

    Returns:
        q_float (np.ndarray float32): dequantized block values.
        raw     (np.ndarray uint8):   per-element raw bit patterns (int8 for dtype='int8').
    """
    if scale == 0.0:
        zero_raw = np.zeros(block.shape, dtype=np.int8 if dtype == 'int8' else np.uint8)
        return np.zeros(block.shape, dtype=np.float32), zero_raw

    norm = block / scale

    if dtype == 'int8':
        q_int8 = np.round(norm).clip(-128, 127).astype(np.int8)
        return (q_int8.astype(np.float32) * scale), q_int8

    cls = _DATA_CLASSES[dtype]
    q_float = np.empty(block.shape, dtype=np.float32)
    raw = np.empty(block.shape, dtype=np.uint8)
    for i, val in enumerate(norm.flat):
        obj = cls().quantize(float(val))
        q_float.flat[i] = obj.to_float() * scale
        raw.flat[i] = obj.pack()
    return q_float, raw


def quantize_mx(
    arr: np.ndarray,
    dtype: str,
    block_size: int = 32,
    axis: int = 1,
    packed: bool = False,
) -> tuple:
    """Microscaling quantization with per-block shared exponent.

    Divides the array into blocks of size `block_size` along `axis`,
    computes one shared exponent per block (E8M0 format, stored as a
    float representing the actual exponent value), then quantizes each
    element relative to that shared exponent.

    Args:
        arr:        2D float32 input array of shape (Row, Col).
        dtype:      Element format. One of 'fp8_e4m3', 'fp8_e5m2',
                    'fp6_e3m2', 'fp6_e2m3', 'fp4_e2m1', 'int8'.
        block_size: Number of elements per block along the chosen axis.
        axis:       0 → blocks of `block_size` rows; exp_shared shape = (ceil(Row/block_size), Col)
                    1 → blocks of `block_size` cols; exp_shared shape = (Row, ceil(Col/block_size))
        packed:     If True, each row of the raw output is tightly bit-packed.
                    Affects FP4 and FP6 only; FP8/int8 are already byte-aligned.

    Returns:
        q_arr      (np.ndarray float32, same shape as arr): dequantized values.
        exp_shared (np.ndarray float32): shared exponent per block.
        q_raw      Raw quantized array:
                     packed=False → same shape as arr, dtype uint8 (or int8 for 'int8').
                     packed=True  → shape (Row, packed_cols), dtype uint8,
                                    each row independently packed.
    """
    arr = np.asarray(arr, dtype=np.float32)
    if arr.ndim != 2:
        raise ValueError(f"quantize_mx expects a 2D array, got shape {arr.shape}")
    if dtype not in VALID_DTYPES:
        raise ValueError(f"Unknown dtype {dtype!r}. Choose from {VALID_DTYPES}")

    Row, Col = arr.shape
    raw_dtype = np.int8 if dtype == 'int8' else np.uint8
    q_arr = np.zeros_like(arr)
    raw_arr = np.zeros((Row, Col), dtype=raw_dtype)

    if axis == 1:
        n_blocks = int(np.ceil(Col / block_size))
        exp_shared = np.zeros((Row, n_blocks), dtype=np.float32)
        for i in range(Row):
            for b in range(n_blocks):
                j0 = b * block_size
                j1 = min(j0 + block_size, Col)
                block = arr[i, j0:j1]
                se = _find_shared_exp(block, dtype)
                exp_shared[i, b] = se
                q_arr[i, j0:j1], raw_arr[i, j0:j1] = _quantize_block(block, dtype, se)

    elif axis == 0:
        n_blocks = int(np.ceil(Row / block_size))
        exp_shared = np.zeros((n_blocks, Col), dtype=np.float32)
        for b in range(n_blocks):
            i0 = b * block_size
            i1 = min(i0 + block_size, Row)
            for j in range(Col):
                block = arr[i0:i1, j]
                se = _find_shared_exp(block, dtype)
                exp_shared[b, j] = se
                q_arr[i0:i1, j], raw_arr[i0:i1, j] = _quantize_block(block, dtype, se)

    else:
        raise ValueError(f"axis must be 0 or 1, got {axis}")

    #FIXME: do not use this!!! it is wrong
    if packed and dtype not in ('fp8_e4m3', 'fp8_e5m2', 'int8'):
        # Pack each row independently
        packed_rows = [_pack_raw_1d(raw_arr[i], dtype) for i in range(Row)]
        q_raw = np.stack(packed_rows, axis=0)
    else:
        q_raw = raw_arr

    return q_arr, exp_shared, q_raw


# (NEW) Supports arbitrary scale format; old quantize_mx kept
def quantize_mx_v2(
    arr: np.ndarray,
    dtype: str,
    block_size: int = 32,
    axis: int = 1,
    packed: bool = False,
    scale_format: str = 'UE8M0',
) -> tuple:
    """MX block quantization with generalized 8-bit unsigned shared scale format.

    Identical to quantize_mx except:
      - scale is not restricted to powers-of-2 (E8M0); any format in
        VALID_SCALE_FORMATS is supported (e8m0 through e0m8).
      - Returns a 4-tuple instead of 3-tuple, adding scale_raw.

    Args:
        arr:          2D float32 input array of shape (Row, Col).
        dtype:        Element format. One of VALID_DTYPES.
        block_size:   Number of elements per block along the chosen axis.
        axis:         0 -> row-wise blocks; 1 -> column-wise blocks (default).
        packed:       If True, tightly bit-pack the raw element output (FP4/FP6).
        scale_format: Shared scale encoding. One of:
                      'e8m0','e7m1','e6m2','e5m3','e4m4','e3m5','e2m6','e1m7','e0m8'

    Returns:
        q_arr        (np.ndarray float32, same shape as arr): dequantized values.
        scale_shared (np.ndarray float32): quantized scale value per block (float).
        scale_raw    (np.ndarray uint8): hardware-ready biased 8-bit encoding of scale
                     per block. Bit layout (directly feeds hardware):
                       E8M0  : raw = biased_exp (exp + 127); raw=255 reserved for NaN.
                       ExMy  : raw = (biased_exp << mbits) | mantissa_bits;
                               biased_exp = unbiased_exp + bias, bias = 2^(ebits-1)-1.
                       E0M8  : raw = round(scale * 128); value = raw / 128 (fixed-point).
        q_raw        (np.ndarray): per-element raw quantized encoding
                     (same layout rules as quantize_mx).
    """
    arr = np.asarray(arr, dtype=np.float32)
    if arr.ndim != 2:
        raise ValueError(f"quantize_mx_v2 expects a 2D array, got shape {arr.shape}")
    if dtype not in VALID_DTYPES:
        raise ValueError(f"Unknown dtype {dtype!r}. Choose from {VALID_DTYPES}")
    if scale_format not in VALID_SCALE_FORMATS:
        raise ValueError(f"Unknown scale_format {scale_format!r}. Choose from {list(VALID_SCALE_FORMATS)}")

    scale_fmt = VALID_SCALE_FORMATS[scale_format]
    Row, Col = arr.shape
    raw_dtype = np.int8 if dtype == 'int8' else np.uint8
    q_arr   = np.zeros_like(arr)
    raw_arr = np.zeros((Row, Col), dtype=raw_dtype)

    if axis == 1:
        n_blocks     = int(np.ceil(Col / block_size))
        scale_shared = np.zeros((Row, n_blocks), dtype=np.float32)
        scale_raw    = np.zeros((Row, n_blocks), dtype=np.uint8)
        for i in range(Row):
            for b in range(n_blocks):
                j0 = b * block_size
                j1 = min(j0 + block_size, Col)
                block = arr[i, j0:j1]
                sc_f, sc_r = _compute_block_scale(block, dtype, scale_fmt)
                scale_shared[i, b] = sc_f
                scale_raw[i, b]    = sc_r
                q_arr[i, j0:j1], raw_arr[i, j0:j1] = _quantize_block_by_scale(block, dtype, sc_f)

    elif axis == 0:
        n_blocks     = int(np.ceil(Row / block_size))
        scale_shared = np.zeros((n_blocks, Col), dtype=np.float32)
        scale_raw    = np.zeros((n_blocks, Col), dtype=np.uint8)
        for b in range(n_blocks):
            i0 = b * block_size
            i1 = min(i0 + block_size, Row)
            for j in range(Col):
                block = arr[i0:i1, j]
                sc_f, sc_r = _compute_block_scale(block, dtype, scale_fmt)
                scale_shared[b, j] = sc_f
                scale_raw[b, j]    = sc_r
                q_arr[i0:i1, j], raw_arr[i0:i1, j] = _quantize_block_by_scale(block, dtype, sc_f)

    else:
        raise ValueError(f"axis must be 0 or 1, got {axis}")

    if packed and dtype not in ('fp8_e4m3', 'fp8_e5m2', 'int8'):
        packed_rows = [_pack_raw_1d(raw_arr[i], dtype) for i in range(Row)]
        q_raw = np.stack(packed_rows, axis=0)
    else:
        q_raw = raw_arr

    return q_arr, scale_shared, scale_raw, q_raw


# ---------------------------------------------------------------------------
# Verification / demo
# ---------------------------------------------------------------------------

def _rmse(a, b):
    return float(np.sqrt(np.mean((np.asarray(a) - np.asarray(b)) ** 2)))


def _print_sample_table(orig, quant, n=8, label=""):
    """Print a side-by-side table of original vs quantized values."""
    if label:
        print(f"  [{label}]")
    print(f"  {'idx':>4}  {'original':>12}  {'quantized':>12}  {'error':>12}")
    print(f"  {'----':>4}  {'------------':>12}  {'------------':>12}  {'------------':>12}")
    for i in range(min(n, len(orig))):
        err = float(orig[i]) - float(quant[i])
        print(f"  {i:>4}  {float(orig[i]):>12.6f}  {float(quant[i]):>12.6f}  {err:>12.6f}")


def main():
    np.random.seed(42)
    arr2d = (np.random.randn(32, 32)*3).astype(np.float32)
    arr1d = arr2d.flatten()

    # ----------------------------------------------------------------
    # Part 1: element-wise quantization — error metrics
    # ----------------------------------------------------------------
    print("=" * 68)
    print("Part 1: element-wise quantization — error metrics")
    print("=" * 68)
    print(f"  {'dtype':12s}  {'RMSE':>10}  {'max_err':>10}  {'max_err%':>9}")
    print(f"  {'-'*12}  {'-'*10}  {'-'*10}  {'-'*9}")

    quant_results = {}
    for dt in ['fp8_e4m3', 'fp8_e5m2', 'fp6_e3m2', 'fp6_e2m3', 'fp4_e2m1', 'mxint8']:
        q, raw = quantize(arr1d, dt)
        rmse = _rmse(arr1d, q)
        max_err = float(np.max(np.abs(arr1d - q)))
        max_pct = max_err / (float(np.max(np.abs(arr1d))) + 1e-9) * 100
        quant_results[dt] = (q, raw)
        print(f"  {dt:12s}  {rmse:>10.6f}  {max_err:>10.6f}  {max_pct:>8.2f}%  raw:{raw.dtype} {raw.shape}")

    q_int, scale, raw_int = quantize(arr1d, 'int8')
    rmse = _rmse(arr1d, q_int)
    max_err = float(np.max(np.abs(arr1d - q_int)))
    max_pct = max_err / (float(np.max(np.abs(arr1d))) + 1e-9) * 100
    quant_results['int8'] = (q_int, raw_int)
    print(f"  {'int8':12s}  {rmse:>10.6f}  {max_err:>10.6f}  {max_pct:>8.2f}%  raw:{raw_int.dtype} {raw_int.shape}  (scale={scale:.5f})")

    # ----------------------------------------------------------------
    # Part 1: before vs after sample values + raw bytes (first 8 elements)
    # ----------------------------------------------------------------
    print()
    print("Part 1: before / after sample (first 8 elements) + raw bytes")
    print()
    for dt in ['fp8_e4m3', 'fp6_e3m2', 'fp4_e2m1', 'mxint8', 'int8']:
        q, raw = quant_results[dt]
        _print_sample_table(arr1d, q, n=8, label=dt)
        # show raw bytes as hex
        raw_hex = " ".join(f"0x{int(v) & 0xFF:02x}" for v in raw[:8])
        print(f"    raw (hex): {raw_hex}")
        print()

    # ----------------------------------------------------------------
    # Part 1: packed vs unpacked raw for FP4 / FP6
    # ----------------------------------------------------------------
    print("Part 1: packed=True raw shape comparison")
    print()
    for dt in ['fp6_e3m2', 'fp4_e2m1']:
        _, raw_unp = quantize(arr1d[:16], dt, packed=False)
        _, raw_pk  = quantize(arr1d[:16], dt, packed=True)
        print(f"  {dt:12s}: unpacked shape={raw_unp.shape}  packed shape={raw_pk.shape}")
        pk_hex = " ".join(f"0x{int(v):02x}" for v in raw_pk[:6])
        print(f"    packed bytes (first 6): {pk_hex}")
    print()

    # ----------------------------------------------------------------
    # Part 2: MX quantization — error metrics
    # ----------------------------------------------------------------
    print("=" * 68)
    print("Part 2: MX quantization — error metrics")
    print("=" * 68)
    print(f"  {'dtype':12s}  {'axis':>4}  {'bs':>4}  {'RMSE':>10}  {'max_err':>10}  {'exp_shape':>12}")
    print(f"  {'-'*12}  {'-'*4}  {'-'*4}  {'-'*10}  {'-'*10}  {'-'*12}")

    mx_results = {}
    for dt in ['fp8_e4m3', 'fp8_e5m2', 'fp6_e3m2', 'fp6_e2m3', 'fp4_e2m1', 'mxint8', 'int8']:
        q, exp, raw = quantize_mx(arr2d, dt, block_size=32, axis=1)
        rmse = _rmse(arr2d, q)
        max_err = float(np.max(np.abs(arr2d - q)))
        mx_results[dt] = (q, exp, raw)
        print(f"  {dt:12s}  {1:>4}  {32:>4}  {rmse:>10.6f}  {max_err:>10.6f}  exp={str(exp.shape)}  raw:{raw.dtype}{list(raw.shape)}")

    # Effect of block_size and axis on fp8_e4m3
    print()
    print("  fp8_e4m3 — varying block_size and axis:")
    for ax, bs in [(1, 32), (1, 16), (1, 8), (0, 32), (0, 16)]:
        q, exp, raw = quantize_mx(arr2d, 'fp8_e4m3', block_size=bs, axis=ax)
        rmse = _rmse(arr2d, q)
        max_err = float(np.max(np.abs(arr2d - q)))
        print(f"  {'fp8_e4m3':12s}  {ax:>4}  {bs:>4}  {rmse:>10.6f}  {max_err:>10.6f}  {str(exp.shape):>12}")

    # packed=True for FP4/FP6 in MX
    print()
    print("  MX packed=True raw shape (fp6_e3m2, fp4_e2m1, axis=1, bs=32):")
    for dt in ['fp6_e3m2', 'fp4_e2m1']:
        _, _, raw_unp = quantize_mx(arr2d, dt, block_size=32, axis=1, packed=False)
        _, _, raw_pk  = quantize_mx(arr2d, dt, block_size=32, axis=1, packed=True)
        print(f"  {dt:12s}: unpacked={list(raw_unp.shape)}  packed={list(raw_pk.shape)}")

    # ----------------------------------------------------------------
    # Part 2: MX before vs after sample (row 0, first 8 elements) + raw bytes
    # ----------------------------------------------------------------
    print()
    print("Part 2: MX before / after sample (arr2d row 0, first 8 elements)")
    print()
    row0 = arr2d[0]
    for dt in ['fp8_e4m3', 'fp6_e3m2', 'fp4_e2m1', 'mxint8', 'int8']:
        q_row = mx_results[dt][0][0]
        exp_val = mx_results[dt][1][0, 0]
        raw_row = mx_results[dt][2][0]
        _print_sample_table(row0, q_row, n=8,
                            label=f"MX {dt}  shared_exp[row0,blk0]={exp_val:.1f}")
        raw_hex = " ".join(f"0x{int(v) & 0xFF:02x}" for v in raw_row[:8])
        print(f"    raw (hex): {raw_hex}")
        print()

    # ----------------------------------------------------------------
    # Boundary / known-value checks (assertions)
    # ----------------------------------------------------------------
    print("=" * 68)
    print("Boundary / known-value checks")
    print("=" * 68)

    e = E4M3().quantize(448.0)
    print(f"  E4M3.quantize(448):      exp={e.exponent:2d}  mant={e.mantissa}  -> {e.to_float()}")
    assert e.to_float() == 448.0, "E4M3 saturation failed"

    packed = E4M3(sign=0, exponent=15, mantissa=6).pack()
    e2 = E4M3().unpack(packed)
    assert e2.exponent == 15 and e2.mantissa == 6, "E4M3 unpack mask wrong"
    print(f"  E4M3 pack/unpack(15,6):  exp={e2.exponent:2d}  mant={e2.mantissa}  -> {e2.to_float()}")

    e = E2M1().quantize(1.5)
    print(f"  E2M1.quantize(1.5):      exp={e.exponent:2d}  mant={e.mantissa}  -> {e.to_float()}")
    assert e.to_float() == 1.5, "E2M1 floor fix failed"

    e = E2M1().quantize(0.7)
    print(f"  E2M1.quantize(0.7):      exp={e.exponent:2d}  mant={e.mantissa}  -> {e.to_float()}  (nearest FP4)")

    e = E3M2().quantize(1.3)
    print(f"  E3M2.quantize(1.3):      exp={e.exponent:2d}  mant={e.mantissa}  -> {e.to_float()}")

    e = E2M3().quantize(-3.7)
    print(f"  E2M3.quantize(-3.7):     exp={e.exponent:2d}  mant={e.mantissa}  -> {e.to_float()}")

    zero = np.zeros((4, 4), dtype=np.float32)
    q, exp, raw = quantize_mx(zero, 'fp4_e2m1', block_size=4, axis=1)
    assert np.all(q == 0), "All-zeros MX failed"
    assert np.all(raw == 0), "All-zeros MX raw failed"
    print(f"  all-zeros MX fp4_e2m1:   max(q)={np.max(q)}  exp=[{exp.min()},{exp.max()}]  raw all-zero={np.all(raw==0)}")

    print()
    print("All checks passed.")


if __name__ == "__main__":
    main()
