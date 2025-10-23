package llvm.instr;

import llvm.Builder;
import llvm.IRType;
import llvm.ReturnType;
import llvm.Value;
import llvm.ValueType;
import llvm.constant.ConstantVoid;

public class RetInstr extends Instruction {
    public RetInstr(Value returnValue) {
        super(ValueType.RETURN_INST, new IRType("void"), "return");
        addUseValue(returnValue);
        Builder.addInstr(this);
    }

    @Override
    public void print() {
        Value returnValue = getUseValue(0);
        if (returnValue instanceof ConstantVoid) {
            System.out.println("ret void");
        }
        else {
            System.out.println("ret i32 "+returnValue.getName());
        }
    }
}
