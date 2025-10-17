package llvm.instr;

import llvm.ReturnType;
import llvm.ValueType;

public class AllocaInstr extends Instruction {
    public AllocaInstr(ValueType valueType, ReturnType Type, String name) {
        super(valueType, Type, name);
    }
}
