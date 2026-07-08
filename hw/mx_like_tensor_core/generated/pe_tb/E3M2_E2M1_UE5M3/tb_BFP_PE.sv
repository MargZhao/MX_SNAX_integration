// AUTO-GENERATED per-PE power/functional testbench — do not edit.
// Combo: E3M2 x E2M1 / UE5M3   K=64 bs=16 vec=4
// Stimulus: production quantize_mx_v6 (fitted distribution).  Golden = ideal
// dequantized dot product (float64).  Drives BFP_PE and dumps a VCD.
`timescale 1ns/1ps
module tb_BFP_PE;
  localparam int N      = 16;
  localparam int M_ACC  = 11;
  localparam real GOLDEN = -0.14034605026245117;
  localparam real REL_TOL = 0.05;
  localparam real ABS_TOL = 1.0e-3;

  reg                      clock;
  reg                      reset;
  reg                      io_validIn;
  reg                      io_resetAcc;
  reg  [23:0]           io_op_a_i;
  reg  [15:0]           io_op_b_i;
  reg  [7:0]            io_share_exp_A_i;
  reg  [7:0]            io_share_exp_B_i;
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
  reg [23:0] stim_a  [0:N-1];
  reg [15:0] stim_b  [0:N-1];
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
    $dumpfile("tb_BFP_PE_E3M2_E2M1_UE5M3.vcd");
    $dumpvars(0, tb_BFP_PE);

    stim_a[0] = 24'd7462100;
    stim_a[1] = 24'd6332376;
    stim_a[2] = 24'd15855130;
    stim_a[3] = 24'd7902904;
    stim_a[4] = 24'd15953490;
    stim_a[5] = 24'd7706237;
    stim_a[6] = 24'd8083450;
    stim_a[7] = 24'd7425719;
    stim_a[8] = 24'd12581507;
    stim_a[9] = 24'd12202825;
    stim_a[10] = 24'd3066829;
    stim_a[11] = 24'd696882;
    stim_a[12] = 24'd12116404;
    stim_a[13] = 24'd4519826;
    stim_a[14] = 24'd13116366;
    stim_a[15] = 24'd2304932;
    stim_b[0] = 16'd5009;
    stim_b[1] = 16'd17707;
    stim_b[2] = 16'd3036;
    stim_b[3] = 16'd52639;
    stim_b[4] = 16'd25532;
    stim_b[5] = 16'd15737;
    stim_b[6] = 16'd60694;
    stim_b[7] = 16'd44588;
    stim_b[8] = 16'd8505;
    stim_b[9] = 16'd25756;
    stim_b[10] = 16'd18030;
    stim_b[11] = 16'd30370;
    stim_b[12] = 16'd57959;
    stim_b[13] = 16'd11848;
    stim_b[14] = 16'd52803;
    stim_b[15] = 16'd47083;
    stim_sa[0] = 8'd79;
    stim_sa[1] = 8'd79;
    stim_sa[2] = 8'd79;
    stim_sa[3] = 8'd79;
    stim_sa[4] = 8'd76;
    stim_sa[5] = 8'd76;
    stim_sa[6] = 8'd76;
    stim_sa[7] = 8'd76;
    stim_sa[8] = 8'd105;
    stim_sa[9] = 8'd105;
    stim_sa[10] = 8'd105;
    stim_sa[11] = 8'd105;
    stim_sa[12] = 8'd100;
    stim_sa[13] = 8'd100;
    stim_sa[14] = 8'd100;
    stim_sa[15] = 8'd100;
    stim_sb[0] = 8'd71;
    stim_sb[1] = 8'd71;
    stim_sb[2] = 8'd71;
    stim_sb[3] = 8'd71;
    stim_sb[4] = 8'd65;
    stim_sb[5] = 8'd65;
    stim_sb[6] = 8'd65;
    stim_sb[7] = 8'd65;
    stim_sb[8] = 8'd69;
    stim_sb[9] = 8'd69;
    stim_sb[10] = 8'd69;
    stim_sb[11] = 8'd69;
    stim_sb[12] = 8'd68;
    stim_sb[13] = 8'd68;
    stim_sb[14] = 8'd68;
    stim_sb[15] = 8'd68;

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

    $display("[tb E3M2_E2M1_UE5M3] accOut=%h  got=%f  golden=%f  relErr=%f%%",
             io_accOut, got, GOLDEN, relerr * 100.0);
    if (((got > GOLDEN ? got - GOLDEN : GOLDEN - got)) <= REL_TOL * (GOLDEN < 0 ? -GOLDEN : GOLDEN) + ABS_TOL)
      $display("[tb E3M2_E2M1_UE5M3] RESULT: PASS");
    else
      $display("[tb E3M2_E2M1_UE5M3] RESULT: FAIL");

    repeat (2) @(negedge clock);
    $finish;
  end
endmodule
