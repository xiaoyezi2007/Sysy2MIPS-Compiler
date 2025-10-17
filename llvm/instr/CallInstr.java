package llvm.instr;

import llvm.ReturnType;
import llvm.ValueType;

public class CallInstr extends Instruction {
    public CallInstr(ValueType valueType, ReturnType Type, String name) {
        super(valueType, Type, name);
    }
}
