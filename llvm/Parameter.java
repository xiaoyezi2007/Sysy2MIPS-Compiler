package llvm;

public class Parameter extends Value {

    public Parameter() {
        super(ValueType.ARGUMENT, ReturnType.INTEGER, Builder.getVarName());
    }
}
