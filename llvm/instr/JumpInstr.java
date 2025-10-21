package llvm.instr;

import llvm.BasicBlock;
import llvm.ReturnType;
import llvm.Value;
import llvm.ValueType;

public class JumpInstr extends Instruction {
    public JumpInstr(BasicBlock block) {
        super(ValueType.JUMP_INST, ReturnType.VOID, "void");
        addUseValue(block);
    }

    public void print() {
        Value block = getUseValue(0);
        System.out.println("br label %"+block.getName());
    }
}
