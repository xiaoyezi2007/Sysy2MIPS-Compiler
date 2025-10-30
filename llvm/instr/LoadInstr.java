package llvm.instr;

import llvm.Builder;
import llvm.GlobalValue;
import llvm.GlobalVariable;
import llvm.ReturnType;
import llvm.Value;
import llvm.ValueType;
import llvm.constant.Constant;
import llvm.constant.ConstantInt;
import mips.IInstr;
import mips.LswInstr;
import mips.MipsBuilder;
import mips.Register;

public class LoadInstr extends Instruction {
    public LoadInstr(Value from) {
        super(ValueType.LOAD_INST, from.getType().ptTo(), Builder.getVarName());
        addUseValue(from);
        Builder.addInstr(this);
    }

    @Override
    public int getSpace() {
        return 4;
    }

    @Override
    public void toMips() {
        Value from = getUseValue(0);
        if (from instanceof GlobalVariable) {
            new LswInstr("lw", Register.T0, from.getName().substring(1));
        }
        else if (from.getType().isAddr) {
            loadToReg(from, Register.T1);
            new LswInstr("lw", Register.T0, Register.T1, 0);
        }
        else {
            new LswInstr("lw", Register.T0, Register.SP, -from.getMemPos());
        }
        pushToMem(Register.T0);
    }

    @Override
    public void print() {
        Value from = getUseValue(0);
        System.out.println(name+" = load "+getTypeName()+", "+from.getTypeName()+" "+from.getName());
    }

    @Override
    public Constant getValue() {
        Value from = getUseValue(0);
        if (from instanceof GlobalValue) {
            return from.getValue();
        }
        else if (from instanceof AllocaInstr) {
            return from.getValue();
        }
        else if (from instanceof GepInstr) {
            return from.getValue();
        }
        return null;
    }
}
