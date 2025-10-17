package llvm.constant;

import llvm.ReturnType;
import llvm.ValueType;

public class ConstantInt extends Constant {
    private int value;

    public ConstantInt(ValueType valueType, ReturnType Type, String name) {
        super(valueType, Type, name);
    }
}
