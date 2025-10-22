package llvm.constant;

import llvm.IRType;
import llvm.ReturnType;
import llvm.ValueType;

public class ConstantVoid extends Constant{
    public ConstantVoid() {
        super(ValueType.CONSTANT, new IRType("void"), "void");
    }
}
