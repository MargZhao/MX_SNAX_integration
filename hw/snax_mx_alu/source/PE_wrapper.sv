module PE_wrapper #(
	parameter M_out_width = 16,
	parameter TileRows = 1,//parfor M
	parameter TileCols = 1,//parfor N
	parameter Vectorsize = 1,
	parameter Input_width = 8, //TODO: should seperate for A and B
	parameter Output_width = 32//FIXME: should be determined by output format(In CSR0)
)
(
	input  logic        clk_i,
	input  logic        rst_n,

	// 1 W CSR for all this control signals
	//Control for MACs in Block_PE
	input  logic [1:0]  prec_mode,
	input  logic [2:0]  A_mode,
	input  logic [2:0]  B_mode,

	//Control for requantization in Block_PE
	input  logic        send_output,
	input  logic [1:0]  Result_mode_quan,

	//NEW
    input  logic [3:0]  group_size,
    input  logic [3:0]  shared_format,
    //NEW

	input  logic        A_valid,
	input  logic        B_valid,
	output  logic        A_ready,
	output  logic        B_ready,

	//Data in
	input  logic [0:TileRows-1][Vectorsize*Input_width-1:0] A, 
	
	input  logic [0:TileCols-1][Vectorsize*Input_width-1:0] B,

	
	// TODO: 8bits,further split
	input  logic [7:0]      shared_exp_A,
	// 8bits
	input  logic [7:0]      shared_exp_B,

	//Data out
	output logic [0:TileRows-1][0:TileCols-1][7:0] Out, //Sign,Exponent,Mantissa,padding_zeros
	output logic [7:0]           shared_exp_out

);

//Ready for inputs if we are not reseting or sending outputs
assign A_ready = (~send_output); 
assign B_ready = (~send_output);

// output declaration of module mxfp8_dotp
wire [DST_WIDTH-1:0] result_o;

genvar i, j;
generate
	for (i = 0; i < TileRows; i++) begin
		for (j = 0; j < TileCols; j++) begin
			mxfp8_dotp #(
				.VectorSize 	(Vectorsize  ))
			u_mxfp8_dotp(
				.clk_i        	(clk_i         ),
				.rst_ni       	(rst_ni        ),
				.operands_a_i 	(A[i]  ),
				.operands_b_i 	(B[j]  ),
				.src_fmt_i    	('d0    ),//E5M2
				.dst_fmt_i    	('d2    ),//FP32
				.scale_i      	({shared_exp_A,shared_exp_B}),//TODO: update with groupsize
				.a_valid_i    	(A_valid     ),
				.b_valid_i    	(B_valid     ),
				.init_save_i  	(send_output   ),//when sending output, reset accumulation
				.acc_clr_i    	(send_output    ),
				.result_o     	(result_o      )
			); //TODO: assign to correct position, further split result_o into sign,
		end
	end
endgenerate



endmodule
