package llvm.instr;

import llvm.ReturnType;
import llvm.User;
import llvm.ValueType;

public abstract class Instruction extends User {
    public Instruction(ValueType valueType, ReturnType Type, String name) {
        super(valueType, Type, name);
    }
}
