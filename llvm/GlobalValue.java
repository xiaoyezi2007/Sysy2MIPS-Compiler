package llvm;

public abstract class GlobalValue extends Value{
    public GlobalValue(ValueType valueType, ReturnType Type, String name) {
        super(valueType, Type, name);
    }
}
