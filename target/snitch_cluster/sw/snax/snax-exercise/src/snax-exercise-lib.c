#include "snax-exercise-lib.h"
#include "streamer_csr_addr_map.h"
//-----------------------
// Streamer 控制
// 底层调用 csrw_ss (写 CSR) 和 csrr_ss (读 CSR)
//-----------------------
void configure_streamer_a(uint32_t base_ptr_low, uint32_t base_ptr_high,
                          uint32_t spatial_stride, uint32_t temporal_bound, uint32_t temporal_stride) {
    csrw_ss(BASE_PTR_READER_0_LOW, base_ptr_low);
    csrw_ss(BASE_PTR_READER_0_HIGH, base_ptr_high);
    csrw_ss(S_STRIDE_READER_0_0, spatial_stride);
    csrw_ss(T_BOUND_READER_0_0, temporal_bound);
    csrw_ss(T_STRIDE_READER_0_0, temporal_stride);
    return;
}

void configure_streamer_b(uint32_t base_ptr_low, uint32_t base_ptr_high,
                          uint32_t spatial_stride, uint32_t temporal_bound, uint32_t temporal_stride) {
    csrw_ss(BASE_PTR_READER_1_LOW, base_ptr_low);
    csrw_ss(BASE_PTR_READER_1_HIGH, base_ptr_high);
    csrw_ss(S_STRIDE_READER_1_0, spatial_stride);
    csrw_ss(T_BOUND_READER_1_0, temporal_bound);
    csrw_ss(T_STRIDE_READER_1_0, temporal_stride);
    return;
}

void configure_streamer_o(uint32_t base_ptr_low, uint32_t base_ptr_high,
                          uint32_t spatial_stride, uint32_t temporal_bound, uint32_t temporal_stride) {
    csrw_ss(BASE_PTR_WRITER_0_LOW, base_ptr_low);
    csrw_ss(BASE_PTR_WRITER_0_HIGH, base_ptr_high);
    csrw_ss(S_STRIDE_WRITER_0_0, spatial_stride);
    csrw_ss(T_BOUND_WRITER_0_0, temporal_bound);
    csrw_ss(T_STRIDE_WRITER_0_0, temporal_stride);
    return;
}

void start_streamer(void) { csrw_ss(STREAMER_START_CSR, 1); }
uint32_t read_busy_streamer(void) { return csrr_ss(STREAMER_BUSY_CSR); }

//-----------------------
// Accelerator 专属控制
//-----------------------
void configure_exercise(uint32_t upper_bias, uint32_t lower_bias, uint32_t data_len) {
    csrw_ss(EXERCISE_CSR_UPPER_BIAS, upper_bias);
    csrw_ss(EXERCISE_CSR_LOWER_BIAS, lower_bias);
    csrw_ss(EXERCISE_CSR_LEN, data_len);
}

void start_exercise(void) { csrw_ss(EXERCISE_CSR_START, 1); }
uint32_t read_busy_exercise(void) { return csrr_ss(EXERCISE_CSR_BUSY); }
uint32_t read_perf_exercise(void) { return csrr_ss(EXERCISE_CSR_PERF_COUNT); }