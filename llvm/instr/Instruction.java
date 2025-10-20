package llvm.instr;

import llvm.Builder;
import llvm.ReturnType;
import llvm.User;
import llvm.ValueType;
import llvm.constant.Constant;
import llvm.constant.ConstantInt;

public abstract class Instruction extends User {
    public Instruction(ValueType valueType, ReturnType Type, String name) {
        super(valueType, Type, name);
    }

    public Instruction(ValueType valueType, ReturnType Type) {
        super(valueType, Type, Builder.getVarName());
    }

    public void print() {

    }

    public Constant getValue() {
        return new ConstantInt(0);
    }
}
