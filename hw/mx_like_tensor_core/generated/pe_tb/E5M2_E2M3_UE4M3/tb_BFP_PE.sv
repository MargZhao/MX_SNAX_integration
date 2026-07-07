// AUTO-GENERATED per-PE power/functional testbench — do not edit.
// Combo: E5M2 x E2M3 / UE4M3   K=64 bs=16 vec=4
// Stimulus: production quantize_mx_v6 (fitted distribution).  Golden = ideal
// dequantized dot product (float64).  Drives BFP_PE and dumps a VCD.
`timescale 1ns/1ps
module tb_BFP_PE;
  localparam int N      = 16;
  localparam int M_ACC  = 11;
  localparam real GOLDEN = -0.10254669189453125;
  localparam real REL_TOL = 0.05;
  localparam real ABS_TOL = 1.0e-3;

  reg                      clock;
  reg                      reset;
  reg                      io_validIn;
  reg                      io_resetAcc;
  reg  [31:0]           io_op_a_i;
  reg  [23:0]           io_op_b_i;
  reg  [6:0]            io_share_exp_A_i;
  reg  [6:0]            io_share_exp_B_i;
  wire                     io_validOut;
  wire [19:0]           io_accOut;

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
  reg [6:0]  stim_sa [0:N-1];
  reg [6:0]  stim_sb [0:N-1];

  // clock: 10ns period, posedge is the active edge
  initial clock = 1'b0;
  always #5 clock = ~clock;

  integer i;
  integer sgn, ex;
  reg [M_ACC-1:0] mant;
  real got, relerr;

  initial begin
    $dumpfile("tb_BFP_PE_E5M2_E2M3_UE4M3.vcd");
    $dumpvars(0, tb_BFP_PE);

    stim_a[0] = 32'd1549652564;
    stim_a[1] = 32'd1481236312;
    stim_a[2] = 32'd3697137498;
    stim_a[3] = 32'd1581865688;
    stim_a[4] = 32'd3671382096;
    stim_a[5] = 32'd1549228252;
    stim_a[6] = 32'd1574133208;
    stim_a[7] = 32'd1515378901;
    stim_a[8] = 32'd3706443600;
    stim_a[9] = 32'd3671087702;
    stim_a[10] = 32'd1473829977;
    stim_a[11] = 32'd1305957855;
    stim_a[12] = 32'd3646443998;
    stim_a[13] = 32'd1532586332;
    stim_a[14] = 32'd3695925593;
    stim_a[15] = 32'd1390205390;
    stim_b[0] = 24'd854211;
    stim_b[1] = 24'd5341805;
    stim_b[2] = 24'd462385;
    stim_b[3] = 24'd13076863;
    stim_b[4] = 24'd6880050;
    stim_b[5] = 24'd3622757;
    stim_b[6] = 24'd15163672;
    stim_b[7] = 24'd10719793;
    stim_b[8] = 24'd2909221;
    stim_b[9] = 24'd7162162;
    stim_b[10] = 24'd5351161;
    stim_b[11] = 24'd8239688;
    stim_b[12] = 24'd14988958;
    stim_b[13] = 24'd3384480;
    stim_b[14] = 24'd13341837;
    stim_b[15] = 24'd12443245;
    stim_sa[0] = 7'd1;
    stim_sa[1] = 7'd1;
    stim_sa[2] = 7'd1;
    stim_sa[3] = 7'd1;
    stim_sa[4] = 7'd1;
    stim_sa[5] = 7'd1;
    stim_sa[6] = 7'd1;
    stim_sa[7] = 7'd1;
    stim_sa[8] = 7'd1;
    stim_sa[9] = 7'd1;
    stim_sa[10] = 7'd1;
    stim_sa[11] = 7'd1;
    stim_sa[12] = 7'd1;
    stim_sa[13] = 7'd1;
    stim_sa[14] = 7'd1;
    stim_sa[15] = 7'd1;
    stim_sb[0] = 7'd6;
    stim_sb[1] = 7'd6;
    stim_sb[2] = 7'd6;
    stim_sb[3] = 7'd6;
    stim_sb[4] = 7'd4;
    stim_sb[5] = 7'd4;
    stim_sb[6] = 7'd4;
    stim_sb[7] = 7'd4;
    stim_sb[8] = 7'd5;
    stim_sb[9] = 7'd5;
    stim_sb[10] = 7'd5;
    stim_sb[11] = 7'd5;
    stim_sb[12] = 7'd5;
    stim_sb[13] = 7'd5;
    stim_sb[14] = 7'd5;
    stim_sb[15] = 7'd5;

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
    sgn  = io_accOut[19];
    ex   = io_accOut[18 -: 8];
    mant = io_accOut[M_ACC-1:0];
    if (ex == 0) got = 0.0;
    else got = (sgn ? -1.0 : 1.0) * (1.0 + mant / (2.0 ** M_ACC)) * (2.0 ** (ex - 127));
    relerr = (GOLDEN == 0.0) ? got : ((got - GOLDEN) / GOLDEN);
    if (relerr < 0) relerr = -relerr;

    $display("[tb E5M2_E2M3_UE4M3] accOut=%h  got=%f  golden=%f  relErr=%f%%",
             io_accOut, got, GOLDEN, relerr * 100.0);
    if (((got > GOLDEN ? got - GOLDEN : GOLDEN - got)) <= REL_TOL * (GOLDEN < 0 ? -GOLDEN : GOLDEN) + ABS_TOL)
      $display("[tb E5M2_E2M3_UE4M3] RESULT: PASS");
    else
      $display("[tb E5M2_E2M3_UE4M3] RESULT: FAIL");

    repeat (2) @(negedge clock);
    $finish;
  end
endmodule
