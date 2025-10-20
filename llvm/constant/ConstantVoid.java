package llvm.constant;

import llvm.ReturnType;
import llvm.ValueType;

public class ConstantVoid extends Constant{
    public ConstantVoid() {
        super(ValueType.CONSTANT, ReturnType.VOID, "void");
    }
}
