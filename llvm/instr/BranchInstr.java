package llvm.instr;

import llvm.BasicBlock;
import llvm.Builder;
import llvm.IRType;
import llvm.ReturnType;
import llvm.Value;
import llvm.ValueType;

public class BranchInstr extends Instruction {

    public BranchInstr(Value Cond, BasicBlock Block1, BasicBlock Block2) {
        super(ValueType.BRANCH_INST, new IRType("void"), "void");
        addUseValue(Cond);
        addUseValue(Block1);
        addUseValue(Block2);
        Builder.addInstr(this);
    }

    public void print() {
        if (isPrint) {
            return;
        }
        isPrint = true;
        Value Cond = getUseValue(0);
        Value Block1 = getUseValue(1);
        Value Block2 = getUseValue(2);
        System.out.println("br i1 "+Cond.getName()+", label %"+Block1.getName()+", label %"+Block2.getName());
    }
}
