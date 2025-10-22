package llvm.constant;

import llvm.IRType;
import llvm.ReturnType;
import llvm.ValueType;

public class ConstantString extends Constant {
    private String name;

    public ConstantString(String name) {
        super(ValueType.CONSTANT, new IRType("string"), name);
        this.name = name;
    }

    public int getLength() {
        return name.length()+1;
    }
}
