package llvm.instr;

import llvm.ReturnType;
import llvm.Value;
import llvm.ValueType;
import llvm.constant.ConstantVoid;

public class RetInstr extends Instruction {
    public RetInstr(Value returnValue) {
        super(ValueType.RETURN_INST, ReturnType.VOID, "return");
        addUseValue(returnValue);
    }

    @Override
    public void print() {
        Value returnValue = getUseValue(0);
        returnValue.print();
        if (returnValue instanceof ConstantVoid) {
            System.out.println("ret void");
        }
        else {
            System.out.println("ret i32 "+returnValue.getName());
        }
    }
}
