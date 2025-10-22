package llvm.instr;

import llvm.Builder;
import llvm.IRType;
import llvm.ReturnType;
import llvm.ValueType;

public class AllocaInstr extends Instruction {
    public AllocaInstr(IRType Type) {
        super(ValueType.ALLOCA_INST, new IRType("ptr", Type), Builder.getVarName());
    }

    @Override
    public void print() {
        if (isPrint) {
            return;
        }
        isPrint = true;
        System.out.println(name+" = alloca "+getType().ptTo().toString());
    }
}
