package llvm;

import llvm.constant.Constant;

public abstract class GlobalValue extends Value{
    public GlobalValue(ValueType valueType, ReturnType Type, String name) {
        super(valueType, Type, name);
    }

    public void print() {

    }

    public void setValue(Constant value) {

    }
}
