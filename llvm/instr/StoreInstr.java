package llvm.instr;

import llvm.ReturnType;
import llvm.ValueType;

public class StoreInstr extends Instruction {
    public StoreInstr(ValueType valueType, ReturnType Type, String name) {
        super(valueType, Type, name);
    }
}
