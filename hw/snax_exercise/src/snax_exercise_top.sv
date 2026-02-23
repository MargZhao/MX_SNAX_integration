// Copyright 2024 KU Leuven.
// Solderpad Hardware License, Version 0.51, see LICENSE for details.
// SPDX-License-Identifier: SHL-0.51

// Ryan Antonio <ryan.antonio@esat.kuleuven.be>

module snax_exercise_top #(
  parameter int unsigned RegDataWidth = 32,
  parameter int unsigned DataWidth    = 64
)(
  //-------------------------------
  // Clocks and reset
  //-------------------------------
  input  logic                      clk_i,
  input  logic                      rst_ni,
  //-------------------------------
  // Register RW from CSR manager
  //-------------------------------
  input  logic [RegDataWidth-1:0]   csr_rw_reg_upper_i,
  input  logic [RegDataWidth-1:0]   csr_rw_reg_lower_i,
  input  logic [RegDataWidth-1:0]   csr_rw_reg_len_i,
  input  logic [RegDataWidth-1:0]   csr_rw_reg_start_i,
  input  logic                      csr_rw_reg_valid_i,
  output logic                      csr_rw_reg_ready_o,
  //-------------------------------
  // Register RO to CSR manager
  //-------------------------------
  output logic [RegDataWidth-1:0]   csr_ro_reg_busy_o,
  output logic [RegDataWidth-1:0]   csr_ro_reg_perf_count_o,
  //-------------------------------
  // Data path IO
  //-------------------------------
  input  logic [7:0][DataWidth-1:0] a_i,
  input  logic                      a_valid_i,
  output logic                      a_ready_o,
  input  logic [7:0][DataWidth-1:0] b_i,
  input  logic                      b_valid_i,
  output logic                      b_ready_o,
  output logic [2*DataWidth-1:0]    out_o,
  output logic                      out_valid_o,
  input  logic                      out_ready_i
);

  //-------------------------------
  // Direct register control signals
  // to and from the ALU PEs
  //-------------------------------
  logic                    acc_output_success;
  logic                    acc_ready;
  logic [RegDataWidth-1:0] upper_bias;
  logic [RegDataWidth-1:0] lower_bias;

  assign acc_output_success = out_valid_o && out_ready_i;

  snax_exercise_csr #(
    .RegDataWidth            ( RegDataWidth            )
  ) i_snax_exercise_csr (
    //-------------------------------
    // Clocks and reset
    //-------------------------------
    .clk_i                   ( clk_i                   ),
    .rst_ni                  ( rst_ni                  ),
    //-------------------------------
    // Register RW from CSR manager
    //-------------------------------
    .csr_rw_reg_upper_i      ( csr_rw_reg_upper_i      ),
    .csr_rw_reg_lower_i      ( csr_rw_reg_lower_i      ),
    .csr_rw_reg_len_i        ( csr_rw_reg_len_i        ),
    .csr_rw_reg_start_i      ( csr_rw_reg_start_i      ),
    .csr_rw_reg_valid_i      ( csr_rw_reg_valid_i      ),
    .csr_rw_reg_ready_o      ( csr_rw_reg_ready_o      ),
    //-------------------------------
    // Register RO to CSR manager
    //-------------------------------
    .csr_ro_reg_busy_o       ( csr_ro_reg_busy_o       ),
    .csr_ro_reg_perf_count_o ( csr_ro_reg_perf_count_o ),
    //-------------------------------
    // Direct register control signals
    // to and from the ALU PEs
    //-------------------------------
    .acc_output_success_i    ( acc_output_success      ),
    .acc_ready_o             ( acc_ready               ),
    .csr_upper_bias_o        ( upper_bias              ),
    .csr_lower_bias_o        ( lower_bias              )
  );

  snax_exercise_pe #(
    .DataWidth    (DataWidth   ),
    .RegDataWidth (RegDataWidth)
  ) i_snax_exercise_pe (
    //-------------------------------
    // Clocks and reset
    //-------------------------------
    .clk_i        (clk_i      ),
    .rst_ni       (rst_ni     ),
    //-------------------------------
    // Inputs
    //-------------------------------
    .a_i          (a_i        ),
    .a_valid_i    (a_valid_i  ),
    .a_ready_o    (a_ready_o  ),
    .b_i          (b_i        ),
    .b_valid_i    (b_valid_i  ),
    .b_ready_o    (b_ready_o  ),
    //-------------------------------
    // Outputs
    //-------------------------------
    .out_o        (out_o      ),
    .out_valid_o  (out_valid_o),
    .out_ready_i  (out_ready_i),
    //-------------------------------
    // Control signals
    //-------------------------------
    .acc_ready_i  (acc_ready  ),
    .upper_bias_i (upper_bias ),
    .lower_bias_i (lower_bias )
  );

endmodule
