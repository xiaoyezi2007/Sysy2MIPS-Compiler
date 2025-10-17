package llvm.instr;

import llvm.ReturnType;
import llvm.ValueType;

public class AluInstr extends Instruction {
    public AluInstr(ValueType valueType, ReturnType Type, String name) {
        super(valueType, Type, name);
    }
}
