package llvm.constant;
import llvm.ReturnType;
import llvm.Value;
import llvm.ValueType;

public abstract class Constant extends Value {
    public Constant(ValueType valueType, ReturnType Type, String name) {
        super(valueType, Type, name);
    }

    public Constant getValue() {
        return this;
    }

    public Constant cal(String op, Constant right) {
        return null;
    }

    @Override
    public void print() {

    }
}
