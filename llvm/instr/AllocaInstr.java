package llvm.instr;

import llvm.Builder;
import llvm.IRType;
import llvm.ReturnType;
import llvm.ValueType;

public class AllocaInstr extends Instruction {
    public AllocaInstr(IRType Type) {
        super(ValueType.ALLOCA_INST, new IRType("ptr", Type), Builder.getVarName());
        Builder.addInstr(this);
    }

    @Override
    public void print() {
        System.out.println(name+" = alloca "+getType().ptTo().toString());
    }
}
