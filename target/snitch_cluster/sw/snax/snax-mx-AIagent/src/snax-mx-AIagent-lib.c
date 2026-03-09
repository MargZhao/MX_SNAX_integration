// Copyright 2024 KU Leuven.
// Licensed under the Apache License, Version 2.0, see LICENSE for details.
// SPDX-License-Identifier: Apache-2.0

#include "snax-mx-AIagent-lib.h"

void set_mx_streamer_csr(
    int32_t delta_local_a,
    int32_t* Aslstride, int32_t* Atlbound, int32_t* Atlstride,
    int32_t delta_local_b,
    int32_t* Bslstride, int32_t* Btlbound, int32_t* Btlstride,
    int32_t delta_local_shared,
    int32_t* SHslstride, int32_t* SHtlbound, int32_t* SHtlstride,
    int32_t delta_local_o,
    int32_t* Oslstride, int32_t* Otlbound, int32_t* Otlstride) {

    // A (Reader 0)
    csrw_ss(BASE_PTR_READER_0_LOW, (uint32_t)(delta_local_a + snrt_l1_next()));
    for (int i = 0; i < S_STRIDE_NUM_READER_0; i++)
        csrw_ss(S_STRIDE_BASE_READER_0 + i, Aslstride[i]);
    for (int i = 0; i < T_BOUND_NUM_READER_0; i++)
        csrw_ss(T_BOUND_BASE_READER_0 + i, Atlbound[i]);
    for (int i = 0; i < T_STRIDE_NUM_READER_0; i++)
        csrw_ss(T_STRIDE_BASE_READER_0 + i, Atlstride[i]);

    // B (Reader 1)
    csrw_ss(BASE_PTR_READER_1_LOW, (uint32_t)(delta_local_b + snrt_l1_next()));
    for (int i = 0; i < S_STRIDE_NUM_READER_1; i++)
        csrw_ss(S_STRIDE_BASE_READER_1 + i, Bslstride[i]);
    for (int i = 0; i < T_BOUND_NUM_READER_1; i++)
        csrw_ss(T_BOUND_BASE_READER_1 + i, Btlbound[i]);
    for (int i = 0; i < T_STRIDE_NUM_READER_1; i++)
        csrw_ss(T_STRIDE_BASE_READER_1 + i, Btlstride[i]);

    // Shared (Reader 2)
    csrw_ss(BASE_PTR_READER_2_LOW, (uint32_t)(delta_local_shared + snrt_l1_next()));
    for (int i = 0; i < S_STRIDE_NUM_READER_2; i++)
        csrw_ss(S_STRIDE_BASE_READER_2 + i, SHslstride[i]);
    for (int i = 0; i < T_BOUND_NUM_READER_2; i++)
        csrw_ss(T_BOUND_BASE_READER_2 + i, SHtlbound[i]);
    for (int i = 0; i < T_STRIDE_NUM_READER_2; i++)
        csrw_ss(T_STRIDE_BASE_READER_2 + i, SHtlstride[i]);

    // Out (Writer 0)
    csrw_ss(BASE_PTR_WRITER_0_LOW, (uint32_t)(delta_local_o + snrt_l1_next()));
    for (int i = 0; i < S_STRIDE_NUM_WRITER_0; i++)
        csrw_ss(S_STRIDE_BASE_WRITER_0 + i, Oslstride[i]);
    for (int i = 0; i < T_BOUND_NUM_WRITER_0; i++)
        csrw_ss(T_BOUND_BASE_WRITER_0 + i, Otlbound[i]);
    for (int i = 0; i < T_STRIDE_NUM_WRITER_0; i++)
        csrw_ss(T_STRIDE_BASE_WRITER_0 + i, Otlstride[i]);
}

void set_mx_csr(uint32_t mode, uint32_t acc_count, uint32_t out_count) {
    csrw_ss(MX_CSR_MODE,      mode);
    csrw_ss(MX_CSR_ACC_COUNT, acc_count);
    csrw_ss(MX_CSR_OUT_COUNT, out_count);
}

void wait_mx_and_streamer() {
    csrw_ss(MX_CSR_START, 0);
    csrw_ss(MX_CSR_START, 0);
    while (csrr_ss(MX_CSR_BUSY)) {}
    while (csrr_ss(STREAMER_BUSY_CSR)) {}
}

void wait_mx() {
    csrw_ss(MX_CSR_START, 0);
    csrw_ss(MX_CSR_START, 0);
    while (csrr_ss(MX_CSR_BUSY)) {}
}

uint32_t read_mx_streamer_perf_counter() {
    return csrr_ss(STREAMER_PERFORMANCE_COUNTER_CSR);
}

uint32_t read_mx_perf_counter() {
    return csrr_ss(MX_CSR_PERF_COUNT);
}

uint32_t check_mx_result(uint32_t* output, uint32_t* output_golden, int32_t out_len) {
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
