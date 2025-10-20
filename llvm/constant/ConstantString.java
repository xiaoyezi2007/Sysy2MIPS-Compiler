package llvm.constant;

import llvm.ReturnType;
import llvm.ValueType;

public class ConstantString extends Constant {
    private String name;

    public ConstantString(String name) {
        super(ValueType.CONSTANT, ReturnType.POINTER, name);
        this.name = name;
    }

    public int getLength() {
        return name.length()+1;
    }
}
