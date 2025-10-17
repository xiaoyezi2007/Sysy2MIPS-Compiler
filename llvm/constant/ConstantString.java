package llvm.constant;

import llvm.ReturnType;
import llvm.ValueType;

public class ConstantString extends Constant {
    private String value;

    public ConstantString(ValueType valueType, ReturnType Type, String name) {
        super(valueType, Type, name);
    }
}
