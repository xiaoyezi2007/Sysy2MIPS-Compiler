package llvm.instr;

import llvm.Builder;
import llvm.ReturnType;
import llvm.Value;
import llvm.ValueType;

public class LoadInstr extends Instruction {
    public LoadInstr(Value from) {
        super(ValueType.LOAD_INST, from.getType().ptTo(), Builder.getVarName());
        addUseValue(from);
    }

    @Override
    public void print() {
        if (isPrint) {
            return;
        }
        isPrint = true;
        Value from = getUseValue(0);
        if (from instanceof GepInstr) {
            from.print();
        }
        System.out.println(name+" = load "+getTypeName()+", "+from.getTypeName()+" "+from.getName());
    }
}
