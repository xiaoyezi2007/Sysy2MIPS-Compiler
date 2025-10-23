package llvm.instr;

import llvm.Builder;
import llvm.IRType;
import llvm.Value;
import llvm.ValueType;

public class GepInstr extends Instruction {
    public GepInstr(Value base, Value index) {
        super(ValueType.GEP_INST, new IRType("ptr", new IRType("i32")), Builder.getVarName());
        addUseValue(base);
        addUseValue(index);
        Builder.addInstr(this);
    }

    public void print() {
        Value base = getUseValue(0);
        Value index = getUseValue(1);
        System.out.print(name+" = getelementptr inbounds "+base.getType().ptTo().toString()
        +", "+base.getTypeName()+" "+base.getName()+", ");
        if (base.getType().ptTo().isArray()) {
            System.out.print("i32 0, ");
        }
        System.out.println(index.getTypeName()+" "+index.getName());
    }
}
