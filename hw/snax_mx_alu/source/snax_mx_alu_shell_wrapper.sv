// Copyright 2024 KU Leuven.
// Solderpad Hardware License, Version 0.51, see LICENSE for details.
// SPDX-License-Identifier: SHL-0.51

// Ryan Antonio <ryan.antonio@esat.kuleuven.be>

//-------------------------------
// Accelerator wrapper
//-------------------------------
module snax_mx_alu_shell_wrapper #(
  // Custom parameters. As much as possible,
  // these parameters should not be taken from outside
  //---------------CSR manager parameters-----------------------
  parameter int unsigned RegRWCount   = 4,
  parameter int unsigned RegROCount   = 2,
  //---------------Accelerator and streamer parameters-----------------------
  parameter int unsigned TileRows     = 8,//parfor M
  parameter int unsigned TileCols     = 8,//parfor N
  parameter int unsigned VectorSize   = 4,//parfor K // Accelerator parameters
  parameter int unsigned NumPE       = TileRows*TileCols,
  parameter int unsigned OutputDataWidth = 32,//TODO: FP32, later can be BF16
  parameter int unsigned InputDataWidth  = 8,
  parameter int unsigned Portwidth =64,
  parameter int unsigned InPortsNeeded = (InputDataWidth * NumPE + Portwidth - 1) / Portwidth,
  parameter int unsigned OutPortsNeeded = (OutputDataWidth * NumPE + Portwidth - 1) / Portwidth,
  // A port 
  parameter int unsigned StreamADataWidth = InPortsNeeded*Portwidth,
  // B port
  parameter int unsigned StreamBDataWidth = InPortsNeeded*Portwidth,
  // 1 Shared exponent port
  parameter int unsigned StreamSharedExpDataWidth = (TileRows + TileCols) * 8,
  
  parameter int unsigned StreamCDataWidth = OutPortsNeeded*Portwidth,
  //---------------Overall parameters-----------------------
  parameter int unsigned RegDataWidth = 32,
  parameter int unsigned RegAddrWidth = 32
)(
  //-------------------------------
  // Clocks and reset
  //-------------------------------
  input  logic clk_i,
  input  logic rst_ni,

  //-------------------------------
  // Accelerator ports
  //-------------------------------
  // Note, we maintained the form of these signals
  // just to comply with the top-level wrapper

  // Ports from accelerator to streamer
  output logic [(StreamCDataWidth)-1:0] acc2stream_0_data_o,
  output logic acc2stream_0_valid_o,
  input  logic acc2stream_0_ready_i,

  // Ports from streamer to accelerator
  input  logic [(StreamADataWidth)-1:0] stream2acc_0_data_i,
  input  logic stream2acc_0_valid_i,
  output logic stream2acc_0_ready_o,

  input  logic [(StreamBDataWidth)-1:0] stream2acc_1_data_i,
  input  logic stream2acc_1_valid_i,
  output logic stream2acc_1_ready_o,

  input logic [(StreamSharedExpDataWidth)-1:0] stream2acc_2_data_i,
  input logic stream2acc_2_valid_i,
  output logic stream2acc_2_ready_o,

  //-------------------------------
  // CSR manager ports
  //-------------------------------
  input  logic [RegRWCount-1:0][RegDataWidth-1:0] csr_reg_set_i,
  input  logic                                    csr_reg_set_valid_i,
  output logic                                    csr_reg_set_ready_o,
  output logic [RegROCount-1:0][RegDataWidth-1:0] csr_reg_ro_set_o
);

  //-------------------------------
  //FSM to control the accelerator
  //-------------------------------
  typedef enum logic {
    IDLE,
    BUSY
  } state_t;
  state_t current_state, next_state;

  always_ff @(posedge clk_i or negedge rst_ni) begin
    if (rst_ni == 1'b0) begin
      current_state <= IDLE;
    end else begin
      current_state <= next_state;
    end
  end

  logic config_fire; // when the configuration is fired, the accelerator starts to compute
  assign config_fire = (current_state == IDLE) && (csr_reg_set_valid_i && csr_reg_set_ready_o);
  
  // store the CSR configuration
  logic [RegRWCount-1:0][RegDataWidth-1:0] csr_reg_set_buffer;
  // TODO: format the register code
  // CSR 0: precision mode, 6 precision types(INT8,FP8x2,FP6x2,FP4), so 3 bit for operand a and 3 bit for oprand b, reserve 2 bit for operation type
  // CSR 1: accumulation count, how many accumulation to get a final output
  // CSR 2: output count
  // TODO: variable block size, and shared scaling factor, adding to above CSR or use a new CSR?
  always_ff @(posedge clk_i or negedge rst_ni) begin
    if (rst_ni == 1'b0) begin
      csr_reg_set_buffer <= '0;
    end else begin
      if (config_fire) begin
        csr_reg_set_buffer <= csr_reg_set_i;
      end
    end
  end

  logic acc_busy;
  assign acc_busy = (current_state == BUSY);

  logic output_fire;
  assign output_fire = acc2stream_0_valid_o && acc2stream_0_ready_i && (acc_busy);
  logic [31:0] output_fire_counter;

  always_ff @(posedge clk_i or negedge rst_ni) begin
    if (rst_ni == 1'b0) begin
      output_fire_counter <= 32'b0;  // Reset counter
    end else begin
      if (output_fire) begin
        output_fire_counter <= output_fire_counter + 1;
      end else if (current_state == IDLE) begin
        output_fire_counter <= 32'b0;  // Reset counter on current_state change
      end
    end
  end

  logic compute_finish;//when output_fire_counter reaches the preset output count, the computation is finished
  assign compute_finish = (output_fire_counter == csr_reg_set_buffer[2]);

  always_comb begin
    next_state = current_state;
    case (current_state)
      IDLE: begin
        if (config_fire) begin
          next_state = BUSY;
        end
      end
      BUSY: begin
        if (compute_finish) begin
          next_state = IDLE;
        end
      end
      default: begin
      end
    endcase
  end
  //-------------------------------
  // Wires
  //-------------------------------

  // Wiring for accelerator ports
  // TODO: Inputs, we have 8x8 MAC in total, for 1 mac it can do 1 INT8 op per cycle, or 4 FP8/FP6, or 8 FP4 ops per cycle. 
  // logic [0:7][7:0] A_INT8;
  // logic [0:7][7:0] B_INT8;

  logic [0:TileRows-1][0:VectorSize-1][InputDataWidth-1:0] A_i;
  logic [0:TileCols-1][0:VectorSize-1][InputDataWidth-1:0] B_i;

  // logic [0:7][0:3][5:0] A_FP6;
  // logic [0:7][0:3][5:0] B_FP6;

  // logic [0:7][0:7][3:0] A_FP4;
  // logic [0:7][0:7][3:0] B_FP4;
  
  //TODO: further split for NVFP4
  logic [0:TileRows-1][7:0] shared_exp_A;
  logic [0:TileCols-1][7:0] shared_exp_B;
  
   // Outputs
  logic [0:TileRows-1][0:TileCols-1][OutputDataWidth-1:0] Out;
  logic [7:0] shared_exp_out;

  //-------------------------------
  //data re-mapping
  //-------------------------------
  // symmetry
   generate
    // ----------------------------------------------------------
    // Top-level loop over the 8 vector lanes
    // ----------------------------------------------------------
    for (genvar i = 0; i < TileRows; i = i + 1) begin : gen_a_fp8_loop
      for (genvar j = 0; j < VectorSize; j = j + 1) begin 
      assign A_i[i][j]= stream2acc_0_data_i[(i*8*VectorSize)+(j*8)+:8]; 
      end   
    end
    for (genvar i = 0; i < TileCols; i = i + 1) begin : gen_b_fp8_loop
      for (genvar j = 0; j < VectorSize; j = j + 1) begin 
      assign B_i[i][j] = stream2acc_1_data_i[(i*8*VectorSize)+(j*8)+:8];
      end
    end
  endgenerate
  //  generate
  //   // ----------------------------------------------------------
  //   // Top-level loop over the 8 vector lanes
  //   // ----------------------------------------------------------
  //   for (genvar i = 0; i < 8; i = i + 1) begin : gen_i_loop
  //     //--------------------------------------------------------
  //     // INT8 inputs (1 byte per element)
  //     //--------------------------------------------------------
  //     // A_INT8[7-0]  <= stream2acc_0_data_i[7:0]
  //     // A_INT8[7-1] <= stream2acc_1_data_i[15:8]
  //     assign A_INT8[8-1-i] = stream2acc_0_data_i[i*8+:8];
  //     assign B_INT8[8-1-i] = stream2acc_1_data_i[i*8+:8];

  //     //--------------------------------------------------------
  //     // FP8 inputs (4 elements × 8 bits inside the 32-bit lane)
  //     //--------------------------------------------------------
  //     for (genvar j = 0; j < 4; j = j + 1) begin : gen_j_fp8_loop
  //       assign A_i[8-1-i][4-1-j] = stream2acc_0_data_i[(i*32)+(j*8)+:8];
  //       assign B_i[8-1-i][4-1-j] = stream2acc_1_data_i[(i*32)+(j*8)+:8];
  //     end

  //     //--------------------------------------------------------
  //     // FP6 inputs (4 elements × 6 bits inside a 24-bit slice)
  //     //--------------------------------------------------------
  //     for (genvar j = 0; j < 4; j = j + 1) begin : gen_j_fp6_loop
  //       assign A_FP6[8-1-i][4-1-j] = stream2acc_0_data_i[(i*24)+(j*6)+:6];
  //       assign B_FP6[8-1-i][4-1-j] = stream2acc_1_data_i[(i*24)+(j*6)+:6];
  //     end

  //     //--------------------------------------------------------
  //     // FP4 inputs (8 elements × 4 bits inside a 32-bit slice)
  //     //--------------------------------------------------------
  //     for (genvar  j = 0; j < 8; j = j + 1) begin : gen_j_fp4_loop
  //       assign A_FP4[8-1-i][8-1-j] = stream2acc_0_data_i[(i*32)+(j*4)+:4];
  //       assign B_FP4[8-1-i][8-1-j] = stream2acc_1_data_i[(i*32)+(j*4)+:4];
  //     end
  //   end
  // endgenerate

  // TODO: asymmetry
  
  //--------------------------------------------------------------
  // Shared default exponents
  //--------------------------------------------------------------
  localparam A_SHARE_WIDTH = TileRows * 8;
  localparam B_SHARE_WIDTH = TileCols * 8;
  generate
    for (genvar i = 0; i < TileRows; i++) begin
        assign shared_exp_A[i] = stream2acc_2_data_i[i*8 +: 8];
    end
    for (genvar i = 0; i < TileCols; i++) begin
        assign shared_exp_B[i] = stream2acc_2_data_i[A_SHARE_WIDTH + i*8 +: 8];
    end
    endgenerate

  // Output data gathering
  always_comb begin
    acc2stream_0_data_o = '0;
    for (int i = 0; i < TileRows; i++) begin
      for (int j = 0; j < TileCols; j++) begin
        acc2stream_0_data_o[(i*TileCols+j)*OutputDataWidth+:OutputDataWidth] = Out[i][j];
      end
    end
    //TODO: add logic when shared exponent output is neeeded
    //acc2stream_0_data_o[StreamCDataWidth-1-64+:8] = shared_exp_out;
  end
  // always_comb begin
  //   acc2stream_0_data_o = '0;
  //   for (int i = 0; i < 8; i++) begin
  //     for (int j = 0; j < 8; j++) begin
  //       acc2stream_0_data_o[(i*64)+(j*8)+:8] = Out[8-1-i][8-1-j];
  //     end
  //   end
  //   acc2stream_0_data_o[StreamCDataWidth-1-64+:8] = shared_exp_out;
  // end

  logic A_valid;
  logic B_valid;
  logic A_ready;
  logic B_ready;
  logic send_output;//to send accumulation sum

  //TODO: check this part, create own PE wrapper
  // M_out_width is ony Mantissa width of output, so for FP32 should be 23 not 32
  PE_Array_wrapper #(
    .SRC_WIDTH(InputDataWidth),
    .DST_WIDTH(OutputDataWidth),
    .TileRows(TileRows),
    .TileCols(TileCols),
    .VectorSize(VectorSize),
    .SCALE_WIDTH(8)
  ) u_gemm_engine (
    //clk and rst
    .clk_i(clk_i),
    .rst_ni(rst_ni),
    //CSR config
    // Control Signals for inputs
    .A_mode  (csr_reg_set_buffer[0][4:2]),  //  mode for input A
    .B_mode  (csr_reg_set_buffer[0][7:5]),  //  mode for input B
    //TODO: check if this is needed
    //.prec_mode_quan(csr_reg_set_buffer[0][9:8]),  // Precision mode for quantization of result
    .Result_mode_quan  (csr_reg_set_buffer[0][11:10]),  // FP mode for quantization
    .group_size(csr_reg_set_buffer[0][15:14]), 
    .shared_format_i(csr_reg_set_buffer[0][19:16]),// ExMy format  
    // Data Inputs/outputs
    .op_a_i(A_i),
    .op_b_i(B_i),
    .shared_exp_A_i(shared_exp_A),
    .shared_exp_B_i(shared_exp_B),
    .results_o(Out),
    //TODO: .shared_exp_out_o(shared_exp_out),
    // Control signal for in/out
    .acc_reset_i(reset_acc),// when send_output is 1, the PE will save the output result and prepare for next accumulation, otherwise it will keep accumulating
    .send_output_i(send_output),// to indicate when to send out the output, this is determined by the accumulation count and the preset count in CSR
    .A_valid_i(A_valid),//good to go if all input are valid
    .A_ready_o(A_ready),
    .B_valid_i(B_valid),
    .B_ready_o(B_ready)
    //.Res_valid_o  (O_valid),
    //.Res_ready_i  (O_ready)
  );
  

  // -----------------------------------------------------------
  // -----------------------------------------------------------
  // handshake logic
  // -----------------------------------------------------------
  // -----------------------------------------------------------

  // considering the back pressure signal from the streamer
  logic keep_output;
  logic next_cycle_keep_output;
  // when the streamer is not ready, Keep output valid in the next cycle until streamer is ready
  assign next_cycle_keep_output = acc2stream_0_valid_o && !acc2stream_0_ready_i;
  always_ff @(posedge clk_i or negedge rst_ni) begin : blockName
    if (rst_ni == 1'b0) begin
      keep_output <= 1'b0;  // Reset current_state
    end else begin
      keep_output <= next_cycle_keep_output;
    end
  end

  // computation_fire if all the input fires
  logic computation_fire;
  logic all_input_valid;
  assign all_input_valid = stream2acc_0_valid_i && stream2acc_1_valid_i && stream2acc_2_valid_i;//A, B and shared factor
  logic all_input_ready;
  assign all_input_ready = stream2acc_0_ready_o && stream2acc_1_ready_o && stream2acc_2_ready_o;
  assign computation_fire = all_input_valid && all_input_ready && acc_busy;

  assign stream2acc_0_ready_o = all_input_valid && A_ready && acc_busy && !next_cycle_keep_output;
  assign stream2acc_1_ready_o = all_input_valid && B_ready && acc_busy && !next_cycle_keep_output;
  //TODO: need to implement the logic to only update the shared exponent after a block
  assign stream2acc_2_ready_o = stream2acc_0_ready_o && stream2acc_1_ready_o;

  logic [31:0] accumulation_counter;

  always_ff @(posedge clk_i or negedge rst_ni) begin
    if (rst_ni == 1'b0) begin
      accumulation_counter <= 32'b0;  // Reset counter
    end else begin
      if (computation_fire && (accumulation_counter <= csr_reg_set_buffer[1] - 1)) begin
        accumulation_counter <= accumulation_counter + 1;
      end else if (send_output) begin
        accumulation_counter <= 32'b0;  // Reset counter on output fire once give the send_out signal
      end else if (current_state == IDLE) begin
        accumulation_counter <= 32'b0;  // Reset counter on current_state change
      end
    end
  end

  //to reset the accumulation
  logic reset_acc;
  // assign reset_acc = (accumulation_counter == 32'b0);
  assign reset_acc = send_output;

  assign A_valid = computation_fire;
  assign B_valid = computation_fire;

  // after programmed time of success computation, set the send_output signal to 1 only when there is on stall on the output
  assign send_output = (accumulation_counter == csr_reg_set_buffer[1]) && acc2stream_0_ready_i && acc_busy;

  assign acc2stream_0_valid_o = send_output && acc_busy;

  assign csr_reg_set_ready_o = ~acc_busy;  // Always ready to accept CSR writes

  assign csr_reg_ro_set_o[0] = {31'b0, acc_busy};  // read-only CSR value

  logic [31:0] performance_counter;
  always_ff @(posedge clk_i or negedge rst_ni) begin
    if (rst_ni == 1'b0) begin
      performance_counter <= 32'b0;  // Reset counter
    end else begin
      if (current_state == BUSY) begin
        performance_counter <= performance_counter + 1;
      end else if (config_fire) begin
        performance_counter <= 32'b0;  // Reset counter on config fire
      end
    end
  end

  assign csr_reg_ro_set_o[1] = performance_counter;

 


endmodule
