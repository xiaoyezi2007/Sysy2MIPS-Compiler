package llvm.instr;

import llvm.ReturnType;
import llvm.ValueType;

public class LoadInstr extends Instruction {
    public LoadInstr(ValueType valueType, ReturnType Type, String name) {
        super(valueType, Type, name);
    }
}
