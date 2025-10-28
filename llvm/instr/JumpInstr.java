package llvm.instr;

import llvm.BasicBlock;
import llvm.Builder;
import llvm.IRType;
import llvm.ReturnType;
import llvm.Value;
import llvm.ValueType;
import mips.JInstr;

public class JumpInstr extends Instruction {
    public JumpInstr(BasicBlock block) {
        super(ValueType.JUMP_INST, new IRType("void"), "void");
        addUseValue(block);
        Builder.addInstr(this);
    }

    @Override
    public void toMips() {
        Value block = getUseValue(0);
        new JInstr("j", ((BasicBlock) block).getMipsLabel());
    }

    public void print() {
        Value block = getUseValue(0);
        System.out.println("br label %"+block.getName());
    }
}
