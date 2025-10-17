package llvm.instr;

import llvm.ReturnType;
import llvm.ValueType;

public class RetInstr extends Instruction {
    public RetInstr(ValueType valueType, ReturnType Type, String name) {
        super(valueType, Type, name);
    }
}
