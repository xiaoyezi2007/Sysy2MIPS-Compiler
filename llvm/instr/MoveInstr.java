package llvm.instr;

import llvm.Builder;
import llvm.IRType;
import llvm.ReturnType;
import llvm.Value;
import llvm.ValueType;
import llvm.constant.ConstantInt;
import mips.Register;

public class MoveInstr extends Instruction {
    public MoveInstr(Value from, Value to) {
        super(ValueType.MOVE_INST, new IRType("void"), "void");
        addUseValue(from);
        addUseValue(to);
    }

    public void print() {
        Value from = getUseValue(0);
        Value to = getUseValue(1);
        System.out.println("move "+from.getName()+" to "+to.getName());
    }

    @Override
    public void toMips() {
        Value from = getUseValue(0);
        Value to = getUseValue(1);
        loadToReg(from, Register.T0);
        pushToMem(Register.T0, (Instruction) to);
    }

}
