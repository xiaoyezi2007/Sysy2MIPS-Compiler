package llvm.instr;

import llvm.Builder;
import llvm.ReturnType;
import llvm.Value;
import llvm.ValueType;

public class LoadInstr extends Instruction {
    public LoadInstr(Value from) {
        super(ValueType.LOAD_INST, ReturnType.INTEGER, Builder.getVarName());
        addUseValue(from);
    }

    @Override
    public void print() {
        Value from = getUseValue(0);
        System.out.println(name+" = load i32, i32* "+from.getName());
    }
}
