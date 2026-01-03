package mips.fake;

import llvm.IRType;
import llvm.ValueType;
import llvm.instr.Instruction;
import mips.MipsBuilder;
import mips.MipsInstr;
import mips.Register;

public class LiInstr extends MipsInstr {
    private int immediate;
    private Register to;

    public LiInstr(Register to, int immediate) {
        this(to, immediate, true);
    }

    /**
     * Optimization-friendly constructor.
     * When emit is false, the instruction is created detached and must be inserted into a list manually.
     */
    public LiInstr(Register to, int immediate, boolean emit) {
        this.immediate = immediate;
        this.to = to;
        if (emit) {
            MipsBuilder.addInstr(this);
        }
    }

    public void print() {
        System.out.println("li "+to.toString()+" "+immediate);
    }

    public int getImmediate() {
        return immediate;
    }

    public Register getTo() {
        return to;
    }
}
