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
        this.immediate = immediate;
        this.to = to;
        MipsBuilder.addInstr(this);
    }

    public void print() {
        System.out.println("li "+to.toString()+" "+immediate);
    }
}
