// Copyright 2024 KU Leuven.
// Licensed under the Apache License, Version 2.0, see LICENSE for details.
// SPDX-License-Identifier: Apache-2.0
//
// Zijun Zhao <zijun.zhao@student.kuleuven.be>

#pragma once

#include <stdint.h>
#include "snrt.h"
#include "streamer_csr_addr_map.h"

// MX Accelerator CSRs (start after streamer at STREAMER_PERFORMANCE_COUNTER_CSR)
#define MX_CSR_ADDR_BASE    (STREAMER_PERFORMANCE_COUNTER_CSR + 1)
#define MX_CSR_MODE         (MX_CSR_ADDR_BASE + 0)   // rw: accumulator mode
#define MX_CSR_ACC_COUNT    (MX_CSR_ADDR_BASE + 1)   // rw: k_blocks per output tile
#define MX_CSR_OUT_COUNT    (MX_CSR_ADDR_BASE + 2)   // rw: total output tiles
#define MX_CSR_START        (MX_CSR_ADDR_BASE + 3)   // rw: write 1 to start
#define MX_CSR_BUSY         (MX_CSR_ADDR_BASE + 4)   // ro: 1 while running
#define MX_CSR_PERF_COUNT   (MX_CSR_ADDR_BASE + 5)   // ro: cycle counter

// Configure all 4 streamers in one call.
// delta_local_*: byte offset from snrt_l1_next() to the data buffer in TCDM.
// slstride[S_STRIDE_NUM]: spatial strides (bytes between adjacent elements in one beat).
// tlbound[T_BOUND_NUM]: temporal loop iteration counts (innermost first).
// tlstride[T_STRIDE_NUM]: temporal strides in bytes (innermost first).
void set_mx_streamer_csr(
    int32_t delta_local_a,
    int32_t* Aslstride, int32_t* Atlbound, int32_t* Atlstride,

    int32_t delta_local_b,
    int32_t* Bslstride, int32_t* Btlbound, int32_t* Btlstride,

    int32_t delta_local_shared,
    int32_t* SHslstride, int32_t* SHtlbound, int32_t* SHtlstride,

    int32_t delta_local_o,
    int32_t* Oslstride, int32_t* Otlbound, int32_t* Otlstride);

// Trigger streamer to start
inline void set_mx_streamer_start() { csrw_ss(STREAMER_START_CSR, 1); }

// Configure MX accelerator CSRs (mode, acc_count, out_count)
void set_mx_csr(uint32_t mode, uint32_t acc_count, uint32_t out_count);

// Trigger MX accelerator to start
inline void set_mx_start() { csrw_ss(MX_CSR_START, 1); }

// Poll until both MX accelerator and streamer finish
void wait_mx_and_streamer();

// Poll until MX accelerator alone finishes (streamer may still be draining)
void wait_mx();

// Read streamer performance counter
uint32_t read_mx_streamer_perf_counter();

// Read MX accelerator performance counter
uint32_t read_mx_perf_counter();

// Compare output vs golden model, return number of mismatches
uint32_t check_mx_result(uint32_t* output, uint32_t* output_golden, int32_t out_len);
