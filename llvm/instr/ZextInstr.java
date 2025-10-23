package llvm.instr;

import llvm.Builder;
import llvm.IRType;
import llvm.Value;
import llvm.ValueType;

public class ZextInstr extends Instruction{
    public ZextInstr(IRType type, Value from) {
        super(ValueType.ZEXT_INST, type, Builder.getVarName());
        addUseValue(from);
        Builder.addInstr(this);
    }

    public void print() {
        Value from = getUseValue(0);
        System.out.println(name+" = zext "+ from.getTypeName()+" "+from.getName()+" to "+Type.toString());
    }
}
