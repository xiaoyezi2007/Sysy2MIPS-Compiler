package mips;

import llvm.Function;

import java.util.stream.StreamSupport;

public class IInstr extends MipsInstr {
    private Register rs;
    private Register rt = null;
    private Integer immediate;
    private String label = "";
    private Function func = null;

    public IInstr(String op, Register rs, Register rt, int immediate) {
        this.rs = rs;
        this.rt = rt;
        this.immediate = immediate;
        this.op = op;
        MipsBuilder.addInstr(this);
        /*
        if (op.equals("addi") && rs.equals(Register.SP) && rt.equals(Register.SP)) {
            MipsBuilder.memory += immediate;
        }
        else if (op.equals("subi") && rs.equals(Register.SP) && rt.equals(Register.SP)) {
            MipsBuilder.memory -= immediate;
        }

         */
    }

    public IInstr(String op, Register rs, int immediate, String label) {
        this.op = op;
        this.rs = rs;
        this.immediate = immediate;
        this.label = label;
        MipsBuilder.addInstr(this);
    }

    /**
     * Optimization-friendly constructor.
     * When emit is false, the instruction is created detached and must be inserted into a list manually.
     */
    public IInstr(String op, Register rs, int immediate, String label, boolean emit) {
        this.op = op;
        this.rs = rs;
        this.immediate = immediate;
        this.label = label;
        if (emit) {
            MipsBuilder.addInstr(this);
        }
    }

    public IInstr(String op, Function function) {
        this.func = function;
        this.op = op;
        MipsBuilder.addInstr(this);
    }

    public void print() {
        if (op.equals("sw") || op.equals("lw")) {
            System.out.println(op+" "+rs.toString()+" "+ immediate +"("+rt.toString()+")");
        }
        else if (!label.isEmpty()) {
            System.out.println(op+" "+rs.toString()+" "+ immediate +" "+label);
        }
        else if (func != null) {
            System.out.println(op+" "+Register.SP+" "+ Register.SP +" "+func.getStackSpace());
        }
        else {
            System.out.println(op+" "+rs.toString()+" "+rt.toString()+" "+immediate);
        }
    }

    public String getLabel() {
        return label;
    }

    public int getImmediate() {
        if (func != null) {
            return func.getStackSpace();
        }
        return immediate;
    }

    public Register getRs() {
        if (func != null) {
            return Register.SP;
        }
        return rs;
    }

    public Register getRt() {
        if (func != null) {
            return Register.SP;
        }
        return rt;
    }
}
