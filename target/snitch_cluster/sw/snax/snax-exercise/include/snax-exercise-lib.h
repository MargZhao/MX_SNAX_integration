// Copyright 2024 KU Leuven.
// Licensed under the Apache License, Version 2.0, see LICENSE for details.
// SPDX-License-Identifier: Apache-2.0
//
// 

#include "snrt.h"

#include <stdbool.h>
#include "stdint.h"

// Accelerator Register Addresses, followed by streamer reg addr
#define EXERCISE_CSR_UPPER_BIAS   978
#define EXERCISE_CSR_LOWER_BIAS   979
#define EXERCISE_CSR_LEN          980
#define EXERCISE_CSR_START        981
#define EXERCISE_CSR_BUSY         982
#define EXERCISE_CSR_PERF_COUNT   983

// Streamer functions
void configure_streamer_a(uint32_t base_ptr_low, uint32_t base_ptr_high,
                          uint32_t spatial_stride, uint32_t temporal_bound,
                          uint32_t temporal_stride);

void configure_streamer_b(uint32_t base_ptr_low, uint32_t base_ptr_high,
                          uint32_t spatial_stride, uint32_t temporal_bound,
                          uint32_t temporal_stride);

void configure_streamer_o(uint32_t base_ptr_low, uint32_t base_ptr_high,
                          uint32_t spatial_stride, uint32_t temporal_bound,
                          uint32_t temporal_stride);

void start_streamer(void);

uint32_t read_busy_streamer(void);

// Accelerator functions
void configure_exercise(uint32_t upper_bias, uint32_t lower_bias, uint32_t data_len);
void start_exercise(void);
uint32_t read_busy_exercise(void);
uint32_t read_perf_exercise(void);
