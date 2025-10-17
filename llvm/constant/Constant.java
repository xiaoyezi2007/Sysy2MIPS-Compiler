package llvm.constant;
import llvm.ReturnType;
import llvm.Value;
import llvm.ValueType;

public abstract class Constant extends Value {
    public Constant(ValueType valueType, ReturnType Type, String name) {
        super(valueType, Type, name);
    }
}
