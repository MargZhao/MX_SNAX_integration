// Copyright 2024 KU Leuven.
// Licensed under the Apache License, Version 2.0, see LICENSE for details.
// SPDX-License-Identifier: Apache-2.0
//
// Zijun Zhao <zijun.zhao@student.kuleuven.be>

#include "snax-mx-lib.h"
#include <stdint.h>
#include "snrt.h"
#include "streamer_csr_addr_map.h"

void set_mx_streamer_csr(
    int32_t delta_local_a,
    int32_t* Aslstride, int32_t* Atlbound, int32_t* Atlstride,

    int32_t delta_local_b,
    int32_t* Bslstride, int32_t* Btlbound, int32_t* Btlstride,

    int32_t delta_local_shared,
    int32_t* SHslstride, int32_t* SHtlbound, int32_t* SHtlstride,

    int32_t delta_local_o,
    int32_t* Oslstride, int32_t* Otlbound, int32_t* Otlstride) {

    // ----------------------------------A (Reader 0)-----------------------------------
    // ----------------------------------A (Reader 0)-----------------------------------
    // ----------------------------------A (Reader 0)-----------------------------------
    // base ptr for A
    csrw_ss(BASE_PTR_READER_0_LOW, (uint32_t)(delta_local_a + snrt_l1_next()));

    // spatial strides for A
    for (int i = 0; i < S_STRIDE_NUM_READER_0; i++) {
        csrw_ss(S_STRIDE_BASE_READER_0 + i, Aslstride[i]);
    }

    // loop bounds, from innermost to outermost, for data mover A
    for (int i = 0; i < T_BOUND_NUM_READER_0; i++) {
        csrw_ss(T_BOUND_BASE_READER_0 + i, Atlbound[i]);
    }

    // temporal strides for A
    for (int i = 0; i < T_STRIDE_NUM_READER_0; i++) {
        csrw_ss(T_STRIDE_BASE_READER_0 + i, Atlstride[i]);
    }

    // ----------------------------------B (Reader 1)-----------------------------------
    // ----------------------------------B (Reader 1)-----------------------------------
    // ----------------------------------B (Reader 1)-----------------------------------
    // base ptr for B
    csrw_ss(BASE_PTR_READER_1_LOW, (uint32_t)(delta_local_b + snrt_l1_next()));

    // spatial strides for B
    for (int i = 0; i < S_STRIDE_NUM_READER_1; i++) {
        csrw_ss(S_STRIDE_BASE_READER_1 + i, Bslstride[i]);
    }

    // loop bounds, from innermost to outermost, for data mover B
    for (int i = 0; i < T_BOUND_NUM_READER_1; i++) {
        csrw_ss(T_BOUND_BASE_READER_1 + i, Btlbound[i]);
    }

    // temporal strides for B
    for (int i = 0; i < T_STRIDE_NUM_READER_1; i++) {
        csrw_ss(T_STRIDE_BASE_READER_1 + i, Btlstride[i]);
    }

    // ----------------------------------Shared (Reader 2)------------------------------
    // ----------------------------------Shared (Reader 2)------------------------------
    // ----------------------------------Shared (Reader 2)------------------------------
    // base ptr for Shared exponent
    csrw_ss(BASE_PTR_READER_2_LOW, (uint32_t)(delta_local_shared + snrt_l1_next()));

    // spatial strides for Shared
    for (int i = 0; i < S_STRIDE_NUM_READER_2; i++) {
        csrw_ss(S_STRIDE_BASE_READER_2 + i, SHslstride[i]);
    }

    // loop bounds, from innermost to outermost, for data mover Shared
    for (int i = 0; i < T_BOUND_NUM_READER_2; i++) {
        csrw_ss(T_BOUND_BASE_READER_2 + i, SHtlbound[i]);
    }

    // temporal strides for Shared
    for (int i = 0; i < T_STRIDE_NUM_READER_2; i++) {
        csrw_ss(T_STRIDE_BASE_READER_2 + i, SHtlstride[i]);
    }

    // ----------------------------------Out (Writer 0)---------------------------------
    // ----------------------------------Out (Writer 0)---------------------------------
    // ----------------------------------Out (Writer 0)---------------------------------
    // base ptr for output
    csrw_ss(BASE_PTR_WRITER_0_LOW, (uint32_t)(delta_local_o + snrt_l1_next()));

    // spatial strides for output
    for (int i = 0; i < S_STRIDE_NUM_WRITER_0; i++) {
        csrw_ss(S_STRIDE_BASE_WRITER_0 + i, Oslstride[i]);
    }

    // loop bounds, from innermost to outermost, for data mover Out
    for (int i = 0; i < T_BOUND_NUM_WRITER_0; i++) {
        csrw_ss(T_BOUND_BASE_WRITER_0 + i, Otlbound[i]);
    }

    // temporal strides for output
    for (int i = 0; i < T_STRIDE_NUM_WRITER_0; i++) {
        csrw_ss(T_STRIDE_BASE_WRITER_0 + i, Otlstride[i]);
    }
}

// Set MX accelerator configuration CSR
void set_mx_csr(uint32_t mode, uint32_t acc_count, uint32_t out_count) {
    csrw_ss(MX_CSR_MODE,      mode);
    csrw_ss(MX_CSR_ACC_COUNT, acc_count);
    csrw_ss(MX_CSR_OUT_COUNT, out_count);
}

// Stall until MX accelerator and streamer finish
void wait_mx_and_streamer() {
    csrw_ss(STREAMER_START_CSR, 0);
    csrw_ss(STREAMER_START_CSR, 0);
    csrw_ss(MX_CSR_START, 0);
    printf("Waiting for MX Core to end...\n");
    while (csrr_ss(MX_CSR_BUSY)) {
    }
    printf("Waiting for Streamer to end...\n");
    while (csrr_ss(STREAMER_BUSY_CSR)) {
    }
}

void wait_mx() {
    csrw_ss(MX_CSR_START, 0);
    csrw_ss(MX_CSR_START, 0);
    while (csrr_ss(MX_CSR_BUSY)) {
    }
}

// Read performance counter of the Streamer, a read-only CSR
uint32_t read_mx_streamer_perf_counter() {
    return csrr_ss(STREAMER_PERFORMANCE_COUNTER_CSR);
}

// Read performance counter of MX accelerator, a read-only CSR
uint32_t read_mx_perf_counter() {
    return csrr_ss(MX_CSR_PERF_COUNT);
}

// Check the result of MX accelerator output against golden model
uint32_t check_mx_result(uint32_t* output, uint32_t* output_golden,
                         int32_t out_len) {
    uint32_t err = 0;
    for (int i = 0; i < out_len; i++) {
        if (output[i] != output_golden[i]) {
            err++;
            printf("Mismatch at [%d]: expected 0x%08x, got 0x%08x\n",
                   i, output_golden[i], output[i]);
        }
    }
    return err;
}
