package llvm.instr;

import llvm.Builder;
import llvm.GlobalValue;
import llvm.ReturnType;
import llvm.Value;
import llvm.ValueType;
import llvm.constant.Constant;
import llvm.constant.ConstantInt;

public class LoadInstr extends Instruction {
    public LoadInstr(Value from) {
        super(ValueType.LOAD_INST, from.getType().ptTo(), Builder.getVarName());
        addUseValue(from);
        Builder.addInstr(this);
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
        return new ConstantInt(0);
    }
}
