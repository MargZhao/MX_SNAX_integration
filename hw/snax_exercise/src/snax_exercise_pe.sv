// Copyright 2024 KU Leuven.
// Solderpad Hardware License, Version 0.51, see LICENSE for details.
// SPDX-License-Identifier: SHL-0.51

// Ryan Antonio <ryan.antonio@esat.kuleuven.be>

module snax_exercise_pe #(
  parameter int unsigned DataWidth    = 64,
  parameter int unsigned RegDataWidth = 32,
  parameter int unsigned SpatPar      = 8
)(
  input  logic                      clk_i,
  input  logic                      rst_ni,
  input  logic [7:0][DataWidth-1:0] a_i,
  input  logic                      a_valid_i,
  output logic                      a_ready_o,
  input  logic [7:0][DataWidth-1:0] b_i,
  input  logic                      b_valid_i,
  output logic                      b_ready_o,
  output logic [2*DataWidth-1:0]    out_o,
  output logic                      out_valid_o,
  input  logic                      out_ready_i,
  input  logic                      acc_ready_i,
  input  logic [RegDataWidth-1:0]   upper_bias_i,
  input  logic [RegDataWidth-1:0]   lower_bias_i
);

  //-------------------------------
  // Wires and combinational logic
  //-------------------------------
  logic      [2*DataWidth-1:0] final_result;
  logic [SpatPar-1:0][2*DataWidth-1:0] partial_prods;
  logic      [  DataWidth-1:0] bias;

  logic input_success;

  assign input_success  = (a_valid_i && a_ready_o) && (b_valid_i && b_ready_o);
  assign bias = {upper_bias_i, lower_bias_i};

  //-------------------------------
  // Fully-combinational Output
  //-------------------------------
  always_comb begin

    for(int i = 0; i < 8; i++) begin
      partial_prods[i] = a_i[i] * b_i[i];
    end

    final_result = partial_prods[0] +
                   partial_prods[1] +
                   partial_prods[2] +
                   partial_prods[3] +
                   partial_prods[4] +
                   partial_prods[5] +
                   partial_prods[6] +
                   partial_prods[7] +
                   bias;
  end
  //-------------------------------
  // Assignments
  //-------------------------------
  // Input ports are ready when the output ready
  // is also ready and when busy state is high
  assign a_ready_o   = acc_ready_i && out_ready_i;
  assign b_ready_o   = acc_ready_i && out_ready_i;
  assign out_valid_o = input_success;
  assign out_o       = final_result;

endmodule
