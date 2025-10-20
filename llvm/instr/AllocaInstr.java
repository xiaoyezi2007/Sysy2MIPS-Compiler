package llvm.instr;

import llvm.Builder;
import llvm.ReturnType;
import llvm.ValueType;

public class AllocaInstr extends Instruction {
    public AllocaInstr() {
        super(ValueType.ALLOCA_INST, ReturnType.POINTER, Builder.getVarName());
    }

    @Override
    public void print() {
        System.out.println(name+" = alloca i32");
    }
}
