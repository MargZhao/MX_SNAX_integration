// AUTO-GENERATED per-PE power/functional testbench — do not edit.
// Combo: INT8 x E2M3 / UE6M2   K=64 bs=16 vec=4
// Stimulus: production quantize_mx_v6 (fitted distribution).  Golden = ideal
// dequantized dot product (float64).  Drives BFP_PE and dumps a VCD.
`timescale 1ns/1ps
module tb_BFP_PE;
  localparam int N      = 16;
  localparam int M_ACC  = 14;
  localparam real GOLDEN = -0.11446142196655273;
  localparam real REL_TOL = 0.05;
  localparam real ABS_TOL = 1.0e-3;

  reg                      clock;
  reg                      reset;
  reg                      io_validIn;
  reg                      io_resetAcc;
  reg  [31:0]           io_op_a_i;
  reg  [23:0]           io_op_b_i;
  reg  [7:0]            io_share_exp_A_i;
  reg  [7:0]            io_share_exp_B_i;
  wire                     io_validOut;
  wire [22:0]           io_accOut;

  BFP_PE dut (
    .clock            (clock),
    .reset            (reset),
    .io_op_a_i        (io_op_a_i),
    .io_op_b_i        (io_op_b_i),
    .io_share_exp_A_i (io_share_exp_A_i),
    .io_share_exp_B_i (io_share_exp_B_i),
    .io_validIn       (io_validIn),
    .io_resetAcc      (io_resetAcc),
    .io_validOut      (io_validOut),
    .io_accOut        (io_accOut)
  );

  // stimulus memories
  reg [31:0] stim_a  [0:N-1];
  reg [23:0] stim_b  [0:N-1];
  reg [7:0]  stim_sa [0:N-1];
  reg [7:0]  stim_sb [0:N-1];

  // clock: 10ns period, posedge is the active edge
  initial clock = 1'b0;
  always #5 clock = ~clock;

  integer i;
  integer sgn, ex;
  reg [M_ACC-1:0] mant;
  real got, relerr;

  initial begin
    $dumpfile("tb_BFP_PE_INT8_E2M3_UE6M2.vcd");
    $dumpvars(0, tb_BFP_PE);

    stim_a[0] = 32'd1213657618;
    stim_a[1] = 32'd620988450;
    stim_a[2] = 32'd3093552695;
    stim_a[3] = 32'd1828926428;
    stim_a[4] = 32'd3152599052;
    stim_a[5] = 32'd1378166959;
    stim_a[6] = 32'd1693346768;
    stim_a[7] = 32'd1091621349;
    stim_a[8] = 32'd4169792769;
    stim_a[9] = 32'd4211017219;
    stim_a[10] = 32'd83687429;
    stim_a[11] = 32'd33358579;
    stim_a[12] = 32'd4177986284;
    stim_a[13] = 32'd185105422;
    stim_a[14] = 32'd4060153864;
    stim_a[15] = 32'd49478143;
    stim_b[0] = 24'd854211;
    stim_b[1] = 24'd5341805;
    stim_b[2] = 24'd462385;
    stim_b[3] = 24'd13076863;
    stim_b[4] = 24'd7150516;
    stim_b[5] = 24'd4155366;
    stim_b[6] = 24'd15434010;
    stim_b[7] = 24'd10990194;
    stim_b[8] = 24'd2909221;
    stim_b[9] = 24'd7162162;
    stim_b[10] = 24'd5351161;
    stim_b[11] = 24'd8239688;
    stim_b[12] = 24'd14988958;
    stim_b[13] = 24'd3384480;
    stim_b[14] = 24'd13341837;
    stim_b[15] = 24'd12443245;
    stim_sa[0] = 8'd119;
    stim_sa[1] = 8'd119;
    stim_sa[2] = 8'd119;
    stim_sa[3] = 8'd119;
    stim_sa[4] = 8'd118;
    stim_sa[5] = 8'd118;
    stim_sa[6] = 8'd118;
    stim_sa[7] = 8'd118;
    stim_sa[8] = 8'd132;
    stim_sa[9] = 8'd132;
    stim_sa[10] = 8'd132;
    stim_sa[11] = 8'd132;
    stim_sa[12] = 8'd129;
    stim_sa[13] = 8'd129;
    stim_sa[14] = 8'd129;
    stim_sa[15] = 8'd129;
    stim_sb[0] = 8'd98;
    stim_sb[1] = 8'd98;
    stim_sb[2] = 8'd98;
    stim_sb[3] = 8'd98;
    stim_sb[4] = 8'd95;
    stim_sb[5] = 8'd95;
    stim_sb[6] = 8'd95;
    stim_sb[7] = 8'd95;
    stim_sb[8] = 8'd97;
    stim_sb[9] = 8'd97;
    stim_sb[10] = 8'd97;
    stim_sb[11] = 8'd97;
    stim_sb[12] = 8'd97;
    stim_sb[13] = 8'd97;
    stim_sb[14] = 8'd97;
    stim_sb[15] = 8'd97;

    // init
    reset = 1'b0;                 // async-assert (active-low): clear registers
    io_validIn = 1'b0; io_resetAcc = 1'b0;
    io_op_a_i = 0; io_op_b_i = 0; io_share_exp_A_i = 0; io_share_exp_B_i = 0;
    repeat (2) @(negedge clock);
    reset = 1'b1;                 // release async reset -> operate

    // synchronous accumulator clear
    io_resetAcc = 1'b1; @(negedge clock);
    io_resetAcc = 1'b0;
    if (io_validOut !== 1'b0) $display("[tb] WARN validOut not low after resetAcc");

    // drive the accumulation, one vector per cycle
    for (i = 0; i < N; i = i + 1) begin
      io_op_a_i        = stim_a[i];
      io_op_b_i        = stim_b[i];
      io_share_exp_A_i = stim_sa[i];
      io_share_exp_B_i = stim_sb[i];
      io_validIn       = 1'b1;
      @(negedge clock);
      if (io_validOut !== 1'b1) $display("[tb] WARN validOut not high at cycle %0d", i);
    end
    io_validIn = 1'b0;
    @(negedge clock);
    if (io_validOut !== 1'b0) $display("[tb] WARN validOut not low after deassert");

    // decode narrow-FP accOut and self-check
    sgn  = io_accOut[22];
    ex   = io_accOut[21 -: 8];
    mant = io_accOut[M_ACC-1:0];
    if (ex == 0) got = 0.0;
    else got = (sgn ? -1.0 : 1.0) * (1.0 + mant / (2.0 ** M_ACC)) * (2.0 ** (ex - 127));
    relerr = (GOLDEN == 0.0) ? got : ((got - GOLDEN) / GOLDEN);
    if (relerr < 0) relerr = -relerr;

    $display("[tb INT8_E2M3_UE6M2] accOut=%h  got=%f  golden=%f  relErr=%f%%",
             io_accOut, got, GOLDEN, relerr * 100.0);
    if (((got > GOLDEN ? got - GOLDEN : GOLDEN - got)) <= REL_TOL * (GOLDEN < 0 ? -GOLDEN : GOLDEN) + ABS_TOL)
      $display("[tb INT8_E2M3_UE6M2] RESULT: PASS");
    else
      $display("[tb INT8_E2M3_UE6M2] RESULT: FAIL");

    repeat (2) @(negedge clock);
    $finish;
  end
endmodule
