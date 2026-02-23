// Copyright 2024 KU Leuven.
// Solderpad Hardware License, Version 0.51, see LICENSE for details.
// SPDX-License-Identifier: SHL-0.51

// Ryan Antonio <ryan.antonio@esat.kuleuven.be>

//-------------------------------
// Accelerator wrapper
//-------------------------------
module snax_exercise_shell_wrapper #(
  // What parameters should we use?
  // This is a hint but you can still
  // have your own defined parameters
  parameter int unsigned InDataWidth  = 64,
  parameter int unsigned OutDataWidth = 128,
  parameter int unsigned NumEle       = 8,
  parameter int unsigned RegRWCount   = 4,
  parameter int unsigned RegROCount   = 2,
  parameter int unsigned RegDataWidth = 32
)(
  //-------------------------------
  // Clocks and reset
  //-------------------------------
  input  logic clk_i,
  input  logic rst_ni,

  //-------------------------------
  // Accelerator ports
  //-------------------------------
  // What are the accelerator ports that you need to use?
  // You might want to check the `*_shell_wrapper` template
  // Alternatively, you could use the generations script

   // Ports from accelerator to streamer
  output logic [(OutDataWidth)-1:0] acc2stream_0_data_o,
  output logic acc2stream_0_valid_o,
  input  logic acc2stream_0_ready_i,

  // Ports from streamer to accelerator
  input  logic [(NumEle*InDataWidth)-1:0] stream2acc_0_data_i,
  input  logic stream2acc_0_valid_i,
  output logic stream2acc_0_ready_o,

  input  logic [(NumEle*InDataWidth)-1:0] stream2acc_1_data_i,
  input  logic stream2acc_1_valid_i,
  output logic stream2acc_1_ready_o,

  //-------------------------------
  // CSR manager ports
  //-------------------------------
  // What are the control ports that you need to use?
  // You might want to check the `*_shell_wrapper` template
  // Alternatively, you could use the generations script
  input  logic [RegRWCount-1:0][RegDataWidth-1:0] csr_reg_set_i,
  input  logic                                    csr_reg_set_valid_i,
  output logic                                    csr_reg_set_ready_o,
  output logic [RegROCount-1:0][RegDataWidth-1:0] csr_reg_ro_set_o
);

  //-------------------------------
  // What should we fill in here?
  //-------------------------------
  // Hint:
  // You only need to instantiate the top-level module
  // but you also need to re-wire things a bit

  // We instantiate it for you!
  snax_exercise_top #(
    .RegDataWidth             (RegDataWidth ),
    .DataWidth                (InDataWidth )
  ) i_snax_exercise_top (
    //-------------------------------
    // Clocks and reset
    //-------------------------------
    .clk_i                    (clk_i        ),
    .rst_ni                   (rst_ni       ),
    //-------------------------------
    // Register RW from CSR manager
    //-------------------------------
    .csr_rw_reg_upper_i       (csr_reg_set_i[0]),
    .csr_rw_reg_lower_i       ( csr_reg_set_i[1]),
    .csr_rw_reg_len_i         ( csr_reg_set_i[2]),
    .csr_rw_reg_start_i       ( csr_reg_set_i[3]),
    .csr_rw_reg_valid_i       ( csr_reg_set_valid_i),
    .csr_rw_reg_ready_o       ( csr_reg_set_ready_o),
    //-------------------------------
    // Register RO to CSR manager
    //-------------------------------
    .csr_ro_reg_busy_o        ( csr_reg_ro_set_o[0]),
    .csr_ro_reg_perf_count_o  ( csr_reg_ro_set_o[1]),
    //-------------------------------
    // Data path IO
    //-------------------------------
    .a_i                      (stream2acc_0_data_i ),
    .a_valid_i                (stream2acc_0_valid_i ),
    .a_ready_o                (stream2acc_0_ready_o ),
    .b_i                      (stream2acc_1_data_i ),
    .b_valid_i                (stream2acc_1_valid_i ),
    .b_ready_o                (stream2acc_1_ready_o ),
    .out_o                    (acc2stream_0_data_o ),
    .out_valid_o              (acc2stream_0_valid_o ),
    .out_ready_i              (acc2stream_0_ready_i )
  );

endmodule
